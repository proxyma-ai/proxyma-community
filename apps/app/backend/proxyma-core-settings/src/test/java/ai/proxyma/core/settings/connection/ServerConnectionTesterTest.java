package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.testing.LoopbackApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLHandshakeException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateExpiredException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a connection's Test says when TLS is the problem. Reported from an SVN server whose
 * certificate had expired the evening before: the Test showed "Could not reach ...: NotAfter: Sun
 * Sep 27 18:45:53 ICT 2026", which reads as a network fault rather than as somebody else's job.
 */
class ServerConnectionTesterTest {

    @Test
    @DisplayName("an expired certificate is named as one, with its date, however deep the JDK wraps it")
    void expiredCertificate() {
        SSLHandshakeException wrapped = new SSLHandshakeException("PKIX path validation failed");
        wrapped.initCause(new java.security.cert.CertPathValidatorException("validity check failed",
                new CertificateExpiredException("NotAfter: Sun Sep 27 18:45:53 ICT 2026")));

        ConnectionTester.Result result = ServerConnectionTester.certificateProblem(new RuntimeException("I/O error", wrapped));
        assertEquals("The server's certificate expired on Sun Sep 27 18:45:53 ICT 2026. "
                + "Its administrator has to renew it.", result.message());
        // The frontend translates the template and fills the date in itself.
        assertEquals("The server's certificate expired on {date}. Its administrator has to renew it.", result.template());
        assertEquals("Sun Sep 27 18:45:53 ICT 2026", result.params().get("date"));
    }

    @Test
    @DisplayName("an untrusted certificate points at the company CA setting")
    void untrustedCertificate() {
        String message = ServerConnectionTester.certificateProblem(new RuntimeException(new SSLHandshakeException(
                "PKIX path building failed: unable to find valid certification path to requested target"))).message();
        assertTrue(message.contains("Settings > Network"), message);
    }

    @Test
    @DisplayName("anything that is not a certificate keeps the plain reachability message")
    void otherFailures() {
        assertNull(ServerConnectionTester.certificateProblem(new java.net.ConnectException("Connection refused")));
    }

    /**
     * Against a loopback server: what a 200 has to say before the Test calls the connection signed in.
     * A sign-in page in front of Bitbucket was shown as "Signed in as <!DOCTYPE html>...", and any web
     * page at an SVN address passed.
     */
    @Nested
    @DisplayName("against a server")
    class AgainstAServer {

        private static final String SVN_DAV = "1,2, version-control,checkout,working-resource, "
                + "merge,baseline,activity,version-controlled-collection, http://subversion.tigris.org/xmlns/dav/svn/depth";

        private LoopbackApi api;
        private final ServerConnectionTester tester = new ServerConnectionTester(RestClient.builder());

        @BeforeEach
        void start() {
            api = LoopbackApi.start();
        }

        @AfterEach
        void stop() {
            api.close();
        }

        private ConnectionTester.Result test(ConnectionProtocol protocol, String baseUrl) {
            boolean svn = protocol == ConnectionProtocol.SVN;
            return tester.test(new ResolvedConnection(null, "u1", "c", protocol, baseUrl, svn ? "ana" : null,
                    svn ? "secret" : "pat", true, true, false, ConnectionStatus.CONNECTED, null, null));
        }

        private void html(String path) {
            api.on("GET", path).body("<!DOCTYPE html>\n<html><body>Sign in</body></html>".getBytes(StandardCharsets.UTF_8),
                    "text/html");
        }

        @Test
        @DisplayName("Bitbucket: a whoami that is a user name signs in, at the root of an address pasted from a page")
        void bitbucketWhoami() {
            api.on("GET", "/bitbucket/plugins/servlet/applinks/whoami").text("ana");

            ConnectionTester.Result result = test(ConnectionProtocol.BITBUCKET,
                    api.baseUrl() + "/bitbucket/projects/PROJ/repos/demo/browse?at=main");

            assertTrue(result.ok(), result.message());
            assertEquals("Signed in as ana", result.message());
            assertEquals("Bearer pat", api.last().header("Authorization"));
        }

        @Test
        @DisplayName("Bitbucket: a whoami answered with a page asks the REST API, which names the account")
        void bitbucketWhoamiPageThenRest() {
            html("/plugins/servlet/applinks/whoami");
            api.on("GET", "/rest/api/1.0/profile/recent/repos").json("{'values':[]}", "X-AUSERNAME", "ana");

            ConnectionTester.Result result = test(ConnectionProtocol.BITBUCKET, api.baseUrl());

            assertEquals("Signed in as ana", result.message());
        }

        @Test
        @DisplayName("Bitbucket: a page from whoami and from the REST API is a page, not a sign-in")
        void bitbucketPageEverywhere() {
            html("/plugins/servlet/applinks/whoami");
            html("/rest/api/1.0/profile/recent/repos");

            ConnectionTester.Result result = test(ConnectionProtocol.BITBUCKET, api.baseUrl());

            assertFalse(result.ok());
            assertEquals(ServerConnectionTester.PAGE_NOT_API, result.message());
        }

        @Test
        @DisplayName("SVN: OPTIONS answered as mod_dav_svn answers it signs in")
        void svnRepository() {
            api.on("OPTIONS", "/svn/repo").header("DAV", SVN_DAV);

            ConnectionTester.Result result = test(ConnectionProtocol.SVN, api.baseUrl() + "/svn/repo/");

            assertTrue(result.ok(), result.message());
            assertEquals("Signed in as ana", result.message());
            assertTrue(api.last().header("Authorization").startsWith("Basic "));
        }

        @Test
        @DisplayName("SVN: a web page that answers OPTIONS, or refuses it, is not a Subversion repository")
        void svnWebPage() {
            api.on("OPTIONS", "/viewvc/repo").header("Allow", "GET, HEAD, OPTIONS");
            assertEquals(ServerConnectionTester.NOT_SVN, test(ConnectionProtocol.SVN, api.baseUrl() + "/viewvc/repo").message());

            api.on("OPTIONS", "/websvn").status(405);
            assertEquals(ServerConnectionTester.NOT_SVN, test(ConnectionProtocol.SVN, api.baseUrl() + "/websvn").message());

            // A plain WebDAV share is DAV, but not a versioned one.
            api.on("OPTIONS", "/dav").header("DAV", "1,2");
            assertEquals(ServerConnectionTester.NOT_SVN, test(ConnectionProtocol.SVN, api.baseUrl() + "/dav").message());
        }

        @Test
        @DisplayName("SVN: a refused password and a missing path keep their own sentences")
        void svnRefusals() {
            api.on("OPTIONS", "/svn/locked").status(401);
            assertEquals("SVN did not accept the username or password.",
                    test(ConnectionProtocol.SVN, api.baseUrl() + "/svn/locked").message());
            assertEquals("Nothing answered at that URL (404). Check it is the server's root address.",
                    test(ConnectionProtocol.SVN, api.baseUrl() + "/svn/missing").message());
        }
    }

    @Test
    @DisplayName("whoami counts only as a one-line name, never as markup")
    void userNames() {
        assertTrue(ServerConnectionTester.looksLikeUserName(" ana.lima@corp \n"));
        assertTrue(ServerConnectionTester.looksLikeUserName("Ana Lima"));
        assertFalse(ServerConnectionTester.looksLikeUserName(null));
        assertFalse(ServerConnectionTester.looksLikeUserName("  "));
        assertFalse(ServerConnectionTester.looksLikeUserName("<!DOCTYPE html><html></html>"));
        assertFalse(ServerConnectionTester.looksLikeUserName("Sign in\nto continue"));
    }
}
