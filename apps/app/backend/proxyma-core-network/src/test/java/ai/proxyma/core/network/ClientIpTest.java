package ai.proxyma.core.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which address the rate limiter charges a request to - SEC-48, and the half of it the first fix
 * missed. An appending proxy (nginx's $proxy_add_x_forwarded_for, which the manual prints) leaves
 * the client's own header FIRST and the address it saw LAST; believing the first entry let a client
 * name a new machine on every login attempt, so the lockout never engaged.
 */
class ClientIpTest {

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    @DisplayName("behind an appending proxy, the entry the proxy wrote wins over the one the client wrote")
    void lastEntryFromAProxy() throws Exception {
        assertEquals("203.0.113.9", ApiRateLimitFilter.clientIp("1.2.3.4, 203.0.113.9", ip("127.0.0.1")));
        assertEquals("203.0.113.9", ApiRateLimitFilter.clientIp("1.2.3.4,5.6.7.8,203.0.113.9", ip("172.18.0.2")));
    }

    @Test
    @DisplayName("a proxy that replaces the header - the bundled Caddy - is read as it always was")
    void singleEntryFromAProxy() throws Exception {
        assertEquals("203.0.113.9", ApiRateLimitFilter.clientIp("203.0.113.9", ip("10.0.0.5")));
    }

    @Test
    @DisplayName("the header is ignored from a peer that is not a proxy")
    void headerIgnoredFromAPublicPeer() throws Exception {
        assertEquals("198.51.100.7", ApiRateLimitFilter.clientIp("1.2.3.4", ip("198.51.100.7")));
    }

    @Test
    @DisplayName("an empty last entry falls back to the peer")
    void emptyLastEntry() throws Exception {
        assertEquals("127.0.0.1", ApiRateLimitFilter.clientIp("1.2.3.4,", ip("127.0.0.1")));
    }
}
