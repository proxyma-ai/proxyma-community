package ai.proxyma.connectors.atlassian;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.AtlassianCloud;
import ai.proxyma.core.connector.spi.AtlassianServer;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * How every request to Jira and Confluence is made, on either product line - which the connection's
 * way of signing in says:
 *
 * <ul>
 *   <li><b>Cloud</b> ({@code https://SITE.atlassian.net}): the Atlassian account's email and an API
 *       token, as HTTP Basic. Jira on REST v3, Confluence on its v2 API under {@code /wiki}.</li>
 *   <li><b>Data Center and Server</b>: a personal access token as a bearer - or, for a token that
 *       holds a colon, {@code user:password} as Basic, which Confluence Server has always taken.
 *       Jira on REST v2, Confluence on {@code /rest/api}.</li>
 * </ul>
 *
 * <p>The credential goes to the connection's server and nowhere else (ApiClient pins it). Both lines
 * answer a burst with 429 and {@code Retry-After}, the standard hint.
 */
final class AtlassianApi {

    static final String MARKDOWN = "text/markdown";

    private static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private AtlassianApi() {}

    /** Whether the connection signs in the Cloud way - an email and an API token. */
    static boolean isCloud(ResolvedCredentials connection) {
        return connection.authKind() == AuthKind.BASIC;
    }

    /** The connection's server without a trailing slash - the address every path is joined to. */
    static String base(ResolvedCredentials connection) {
        if (!connection.hasBaseUrl()) {
            throw new ConnectorAuthException("This connection has no server address. Add one in Settings > Connections.");
        }
        return connection.baseUrl().strip().replaceAll("/+$", "");
    }

    /**
     * The address Jira's paths are joined to: on Cloud the SITE, {@code https://SITE.atlassian.net},
     * whether the connection was typed with {@code /wiki}, {@code /jira} or a page below either - one
     * Cloud connection serves Jira and Confluence alike, each adding its own path ({@link AtlassianCloud});
     * on Data Center the server's root, cut before any page that was pasted with it and keeping the
     * path the server is installed under ({@link AtlassianServer}).
     */
    static String productBase(ResolvedCredentials connection) {
        String base = base(connection);
        return isCloud(connection) ? AtlassianCloud.site(base) : AtlassianServer.jiraRoot(base);
    }

    /**
     * The address Confluence's paths are joined to: on Cloud the site (its API is under {@code /wiki},
     * which the caller adds); on Data Center the server's root, as {@link #productBase} for Jira.
     */
    static String confluenceBase(ResolvedCredentials connection) {
        String base = base(connection);
        return isCloud(connection) ? AtlassianCloud.site(base) : AtlassianServer.confluenceRoot(base);
    }

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection, String service, String baseUrl) {
        return http.client(baseUrl)
                .service(service)
                .auth(auth(connection))
                .rateLimit(RateLimitHint.STANDARD)
                .build();
    }

    // Read on every request, so a connection with nothing to send fails the request with the
    // sentence that says what to add - never at construction, which must not throw.
    private static ApiAuth auth(ResolvedCredentials connection) {
        if (isCloud(connection)) {
            return ApiAuth.header(HttpHeaders.AUTHORIZATION, () -> {
                String email = connection.username();
                String token = connection.secret("token") != null ? connection.secret("token") : connection.soleSecret();
                if (email == null || email.isBlank() || token == null || token.isBlank()) {
                    throw new ConnectorAuthException("This connection has no email address or API token. "
                            + "Add them in Settings > Connections.");
                }
                return basic(email.strip() + ":" + token.strip());
            });
        }
        return ApiAuth.header(HttpHeaders.AUTHORIZATION, () -> {
            String token = connection.accessToken();
            return token.contains(":") ? basic(token) : "Bearer " + token;
        });
    }

    private static String basic(String userAndPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
    }

    /** A Jira timestamp (2026-03-26T14:08:31.000+0000), an ISO one, or null. */
    static Instant instant(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return OffsetDateTime.parse(text, JIRA_TIME).toInstant();
        } catch (RuntimeException e) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (RuntimeException again) {
                try {
                    return Instant.parse(text);
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
    }

    /**
     * A TLS failure said as what it is and whose move it is, or null for any other failure - the
     * JDK's own text ("PKIX path building failed") reads as a network fault to most people.
     */
    static String certificateProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CertificateExpiredException) {
                return "The server's certificate has expired. Its administrator has to renew it.";
            }
            if (t instanceof CertificateNotYetValidException) {
                return "The server's certificate is not valid yet. Check this computer's date and time.";
            }
            String m = t.getMessage();
            if (m != null && m.contains("PKIX path building failed")) {
                return "This computer does not trust the server's certificate. If your company uses "
                        + "its own certificate authority, add it in Settings > Network.";
            }
        }
        return null;
    }

    /** The sentence for a failure of a connection's Test - the certificate in words, else ApiClient's. */
    static String testFailure(ConnectorHttpException e) {
        String certificate = certificateProblem(e);
        if (certificate != null) return certificate;
        if (e.status() == 200) {
            // A 200 that is not JSON is almost always a login page served by a proxy or single
            // sign-on gateway in front of the server - authenticated as nobody.
            return "The server answered with a page instead of its API. Check the URL is the server's own address.";
        }
        if (e.status() == 404) return "Nothing answered at that URL (404). Check it is the server's root address.";
        return e.getMessage();
    }

    /** {@code value} lower-cased for an identity. */
    static String lower(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
