package ai.proxyma.core.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which origins the single-user editions' CORS lets in, pinned against two opposite failures.
 *
 * <p>THE BLANK PAGE (the public demo, removed 2026-10-09). The instance answered 200 to every
 * check anyone ran - the deploy's, curl's, a browser's navigation to index.html - and 403 to the
 * SPA's own JavaScript and stylesheet, which Vite loads with {@code crossorigin} and therefore in
 * CORS mode. A pattern written {@code https://host:*} needs the origin to carry a port, and a
 * browser behind a proxy on 443 sends none. curl sends no Origin header and so could never
 * reproduce it.
 *
 * <p>THE OPEN DOOR (swx-03, 2026-10-14). The cure for the blank page was port wildcards with
 * credentials - {@code http://localhost:*} - and a port is not part of a site, so any page served
 * by any other local process could read every answer this API gave the browser. Now an origin
 * passes only as this app's own page: the Host header's authority, or an allowed host on its
 * default port.
 */
class CorsOriginPatternsTest {

    private static boolean own(String origin, String hostHeader, String... allowedHosts) {
        return WebConfig.isOwnOrigin(origin, hostHeader, List.of(allowedHosts));
    }

    @Nested
    @DisplayName("what Spring actually does with a port wildcard")
    class MatcherSemantics {

        @Test
        @DisplayName("':*' requires a port - which is why the default-port form below exists")
        void portWildcardDoesNotMatchAPortlessOrigin() {
            CorsConfiguration onlyPortPattern = new CorsConfiguration();
            onlyPortPattern.setAllowedOriginPatterns(List.of("https://enterprise-demo.proxyma.ai:*"));
            assertNull(onlyPortPattern.checkOrigin("https://enterprise-demo.proxyma.ai"),
                    "if this ever starts passing, Spring changed its port-wildcard semantics");
            assertEquals("https://enterprise-demo.proxyma.ai:8443",
                    onlyPortPattern.checkOrigin("https://enterprise-demo.proxyma.ai:8443"));
        }
    }

    @Nested
    @DisplayName("the app's own page passes")
    class OwnPage {

        @Test
        @DisplayName("the origin the Host header names - the Desktop window, a direct visit")
        void originMatchingTheHostHeader() {
            assertTrue(own("http://127.0.0.1:4246", "127.0.0.1:4246"));
            assertTrue(own("http://localhost:4299", "localhost:4299"));
            assertTrue(own("http://[::1]:4246", "[::1]:4246"));
            assertTrue(own("http://localhost", "localhost"), "port 80, where neither side names it");
        }

        @Test
        @DisplayName("behind a TLS proxy that keeps the Host - the page says https, the app sees http")
        void tlsProxyKeepingTheHost() {
            assertTrue(own("https://nas.example.com", "nas.example.com", "nas.example.com"));
            assertTrue(own("https://nas.example.com:8443", "nas.example.com:8443"));
        }

        @Test
        @DisplayName("an allowed host on its default port, behind a proxy that rewrote the Host header")
        void allowedHostOnTheDefaultPort() {
            assertTrue(own("https://enterprise-demo.proxyma.ai", "127.0.0.1:4246", "enterprise-demo.proxyma.ai"),
                    "a reverse proxy on 443 sends no port; refusing it serves a blank page");
            assertTrue(own("http://proxyma.internal", "proxyma:4246", "proxyma.internal"));
        }
    }

    @Nested
    @DisplayName("nothing else does")
    class EverythingElse {

        @Test
        @DisplayName("another local port - a docs preview, `python -m http.server` (swx-03)")
        void anotherPortOnTheSameMachine() {
            assertFalse(own("http://localhost:8000", "localhost:4246"));
            assertFalse(own("http://127.0.0.1:5173", "127.0.0.1:4246"));
            assertFalse(own("http://nas.lan:8080", "nas.lan:4246", "nas.lan"),
                    "another web service on the Home Server's own host");
        }

        @Test
        @DisplayName("an allowed host on a port other than the one the Host header names")
        void allowedHostOnAnotherPort() {
            assertFalse(own("https://enterprise-demo.proxyma.ai:8443", "127.0.0.1:4246", "enterprise-demo.proxyma.ai"));
        }

        @Test
        @DisplayName("a host that was never allowed, and the near-miss a sloppy pattern lets through")
        void strangers() {
            assertFalse(own("https://evil.example", "127.0.0.1:4246", "enterprise-demo.proxyma.ai"));
            assertFalse(own("https://enterprise-demo.proxyma.ai.evil.example", "127.0.0.1:4246", "enterprise-demo.proxyma.ai"));
            assertFalse(own("null", "127.0.0.1:4246"), "a sandboxed frame or a file: page");
            assertFalse(own("file://", "127.0.0.1:4246"));
            assertFalse(own(null, "127.0.0.1:4246"));
        }
    }
}
