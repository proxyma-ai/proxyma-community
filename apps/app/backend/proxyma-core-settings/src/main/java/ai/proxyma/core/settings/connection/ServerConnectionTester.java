package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.spi.AtlassianServer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Test for the server-based protocols that predate the connector framework: Bitbucket and SVN.
 * (Jira and Confluence are tested by their own connection plugins, in proxyma-connectors.)
 *
 * Goes through {@code externalRestClientBuilder}, the same client the connectors sync with, so the
 * proxy and a company CA from Settings > Network apply here too - a Test that succeeded where sync
 * then failed on a certificate would be worse than no Test.
 *
 * Each call is the service's own "who is this token" endpoint where it has one, so a green result
 * means AUTHENTICATED, not merely reachable. Bitbucket answers its whoami anonymously with an empty
 * body - but it also answers EMPTY to a valid HTTP access token on some Data Center servers, since
 * whoami is a servlet and not the REST API those tokens are issued for. So an empty whoami is
 * checked against the REST API before the token is called refused: reported from a server where
 * the agent read repositories with the very token this Test had just rejected.
 *
 * A 200 is not taken on trust either. A sign-in or SSO gateway in front of a Bitbucket server answers
 * whoami with its HTML, which was shown as "Signed in as <!DOCTYPE html>..."; and any web page at an
 * SVN address - ViewVC, WebSVN, a sign-in page - passed, and sync then failed in svn. So whoami counts
 * only when it reads as a user name, and SVN must answer OPTIONS the way Subversion does.
 */
@Component
public class ServerConnectionTester implements ConnectionTester {

    private static final Set<ConnectionProtocol> SUPPORTED = EnumSet.of(
            ConnectionProtocol.BITBUCKET, ConnectionProtocol.SVN);

    static final String PAGE_NOT_API =
            "The server answered with a page instead of its API. Check the URL is the server's own address.";
    static final String NOT_SVN =
            "That address answered, but not as a Subversion repository. Use the repository's root URL.";

    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper();

    public ServerConnectionTester(@Qualifier("externalRestClientBuilder") RestClient.Builder builder) {
        this.client = builder.build();
    }

    @Override
    public boolean supports(ConnectionProtocol protocol) {
        return SUPPORTED.contains(protocol);
    }

    @Override
    public Result test(ResolvedConnection c) {
        if (!c.hasBaseUrl()) return Result.failed("Set the server URL first.");
        if (c.credential() == null) {
            return Result.failed(c.protocol() == ConnectionProtocol.SVN
                    ? "Enter a username and password first." : "Enter an access token first.");
        }
        // A Bitbucket address pasted from one of its pages is cut to the server's root; an SVN URL is
        // the repository's own, and any path in it means something.
        String base = c.protocol() == ConnectionProtocol.BITBUCKET
                ? AtlassianServer.bitbucketRoot(c.baseUrl()) : c.baseUrl().replaceAll("/+$", "");
        try {
            return switch (c.protocol()) {
                case BITBUCKET -> {
                    String who = get(base + "/plugins/servlet/applinks/whoami", bearer(c));
                    yield looksLikeUserName(who)
                            ? Result.ok("Signed in as {user}", "user", who.strip())
                            : bitbucketRest(base, c);
                }
                case SVN -> svn(base, c);
                default -> Result.failed("This connection type cannot be tested here.");
            };
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            // An SVN address refusing OPTIONS is a server that is not Subversion - a web viewer, a
            // plain web server - and "answered 405" would not say what to change.
            if (c.protocol() == ConnectionProtocol.SVN && (status == 405 || status == 501)) return Result.failed(NOT_SVN);
            String service = ai.proxyma.core.connector.spi.CoreDescriptors.of(c.protocol()).displayName();
            if (status == 401) return Result.failed(c.protocol() == ConnectionProtocol.SVN
                    ? "{service} did not accept the username or password." : "{service} did not accept the token.",
                    "service", service);
            if (status == 403) return Result.failed("{service} refused this account (403).", "service", service);
            if (status == 404) return Result.failed("Nothing answered at that URL (404). Check it is the server's root address.");
            return Result.failed("{service} answered {status}.", "service", service, "status", String.valueOf(status));
        } catch (Exception e) {
            Result certificate = certificateProblem(e);
            if (certificate != null) return certificate;
            return Result.failed("Could not reach {url}: {reason}", "url", c.baseUrl(), "reason", rootMessage(e));
        }
    }

    /**
     * An endpoint only an authenticated user can read - the caller's own recent repositories, which
     * answers 401 anonymously even where anonymous access to public projects is on. Bitbucket names
     * the account behind any authenticated REST response in {@code X-AUSERNAME}.
     */
    private Result bitbucketRest(String base, ResolvedConnection c) {
        var response = client.get().uri(base + "/rest/api/1.0/profile/recent/repos?limit=1")
                .header(HttpHeaders.AUTHORIZATION, bearer(c))
                .header(HttpHeaders.ACCEPT, "application/json")
                .retrieve()
                .toEntity(String.class);
        String user = response.getHeaders().getFirst("X-AUSERNAME");
        if (user != null && !user.isBlank()) return Result.ok("Signed in as {user}", "user", user.strip());
        String body = response.getBody();
        return body != null && body.stripLeading().startsWith("{")
                ? Result.ok("Connected")
                : Result.failed(PAGE_NOT_API);
    }

    /**
     * Whether whoami's body reads as a user name: one line, not markup. Anything else - a page from a
     * proxy or gateway in front of the server - is not an answer, and the REST API is asked instead.
     */
    static boolean looksLikeUserName(String body) {
        if (body == null) return false;
        String name = body.strip();
        return !name.isEmpty() && name.length() <= 255 && !name.startsWith("<")
                && name.indexOf('\n') < 0 && name.indexOf('\r') < 0;
    }

    /**
     * An SVN repository over HTTP is mod_dav_svn, which answers OPTIONS with the DAV classes of a
     * versioned resource ({@code version-control}) and Subversion's own capability URIs, and in newer
     * versions with {@code SVN-*} headers. A 2xx with none of them is some other web page. An
     * {@code svn://} address is not HTTP and is tried as it always was.
     */
    private Result svn(String base, ResolvedConnection c) {
        String scheme = base.toLowerCase(Locale.ROOT);
        if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
            get(base, basic(c));
            return Result.ok("Signed in as {user}", "user", c.username());
        }
        HttpHeaders headers = client.options().uri(base)
                .header(HttpHeaders.AUTHORIZATION, basic(c))
                .retrieve()
                .toBodilessEntity()
                .getHeaders();
        return isSubversion(headers)
                ? Result.ok("Signed in as {user}", "user", c.username())
                : Result.failed(NOT_SVN);
    }

    static boolean isSubversion(HttpHeaders headers) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (name.startsWith("svn-")) return true;
            if (!name.equals("dav")) continue;
            for (String value : header.getValue()) {
                String v = value.toLowerCase(Locale.ROOT);
                if (v.contains("version-control") || v.contains("subversion")) return true;
            }
        }
        return false;
    }

    private String get(String url, String authorization) {
        return client.get().uri(url)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                .retrieve()
                .body(String.class);
    }

    private static String bearer(ResolvedConnection c) {
        return "Bearer " + c.secret();
    }

    private static String basic(ResolvedConnection c) {
        return "Basic " + Base64.getEncoder().encodeToString(c.credential().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A TLS failure said as what it is and whose move it is, or null for any other failure. The raw
     * text is the JDK's ("NotAfter: Sun Sep 27 18:45:53 ICT 2026", "PKIX path building failed"),
     * which reads as a network fault to anybody who has not debugged Java before.
     */
    static Result certificateProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.security.cert.CertificateExpiredException) {
                String m = t.getMessage();
                return m != null && m.startsWith("NotAfter: ")
                        ? Result.failed("The server's certificate expired on {date}. Its administrator has to renew it.",
                                "date", m.substring("NotAfter: ".length()))
                        : Result.failed("The server's certificate has expired. Its administrator has to renew it.");
            }
            if (t instanceof java.security.cert.CertificateNotYetValidException) {
                return Result.failed("The server's certificate is not valid yet. Check this computer's date and time.");
            }
            if (t.getMessage() != null && t.getMessage().contains("PKIX path building failed")) {
                return Result.failed("This computer does not trust the server's certificate. If your company uses "
                        + "its own certificate authority, add it in Settings > Network.");
            }
        }
        return null;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
