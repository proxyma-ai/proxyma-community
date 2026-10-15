package ai.proxyma.core.settings.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.security.cert.CertificateExpiredException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
