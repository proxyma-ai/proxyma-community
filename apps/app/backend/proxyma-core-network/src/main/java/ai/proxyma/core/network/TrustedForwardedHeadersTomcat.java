package ai.proxyma.core.network;

import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.regex.Pattern;

/**
 * Forwarded headers on TOMCAT, believed only from a proxy - the half that runs (2026-11-12).
 *
 * <p>The server is Tomcat, not Netty: both exec jars bundle {@code tomcat-embed-core} through
 * {@code spring-boot-starter-web}, and Spring Boot's reactive server auto-configuration prefers Tomcat. So
 * {@link TrustedForwardedHeaders}' Netty customizer has never run anywhere, and under
 * {@code server.forward-headers-strategy=native} (Enterprise Server) the client's address was decided by the
 * {@link RemoteIpValve} Spring Boot installs - on Boot's own list of internal proxies, not the one
 * {@link ApiRateLimitFilter#isProxyPeer} keeps. The two differed in 100.64.0.0/10, the carrier-grade NAT range
 * Tailscale hands out: Boot trusts it, the rate limiter does not, so a client reaching the server from such an
 * address could write its own X-Forwarded-For and be a fresh sign-in bucket each time - SEC-48 again, one layer down.
 *
 * <p>This sets that valve's internal proxies to EXACTLY the rate limiter's: loopback, the private IPv4 ranges,
 * link-local, and IPv6 loopback, link-local, site-local and unique-local. The valve itself keeps doing what makes it safe -
 * headers are read only when the peer is one of those, and X-Forwarded-For is walked from the right, so the client
 * is the last address a trusted proxy appended, never one the client wrote ({@link #INTERNAL_PROXIES}).
 */
@Configuration
@ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "native")
@ConditionalOnClass(name = "org.apache.catalina.valves.RemoteIpValve")
public class TrustedForwardedHeadersTomcat {

    /**
     * The peers whose forwarded headers are believed, as Tomcat matches them: against the peer's address as
     * {@code getRemoteAddr} writes it - dotted IPv4, IPv6 in full uncompressed hex groups. Equal to
     * {@link ApiRateLimitFilter#isProxyPeer} (TrustedForwardedHeadersTomcatTest checks both on the same addresses).
     */
    static final String INTERNAL_PROXIES = String.join("|",
            "127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}",
            "10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}",
            "172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}",
            "192\\.168\\.\\d{1,3}\\.\\d{1,3}",
            "169\\.254\\.\\d{1,3}\\.\\d{1,3}",
            "0:0:0:0:0:0:0:1",
            "::1",
            // fe80::/10 link-local and fec0::/10 site-local (deprecated, still isSiteLocalAddress): fe8..fef.
            "[fF][eE][89a-fA-F]\\p{XDigit}:.*",
            "[fF][cCdD]\\p{XDigit}{2}:.*");

    static final Pattern INTERNAL = Pattern.compile(INTERNAL_PROXIES);

    @Bean
    public WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> trustedForwardedHeadersTomcatCustomizer() {
        return new TrustedProxies();
    }

    /** Runs after Spring Boot's own Tomcat customizer, which adds the valve this narrows. */
    static final class TrustedProxies implements WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>, Ordered {

        @Override
        public void customize(ConfigurableTomcatWebServerFactory factory) {
            java.util.List<Valve> valves = engineValves(factory);
            boolean found = false;
            for (Valve v : valves) {
                if (v instanceof RemoteIpValve ip) {
                    narrow(ip);
                    found = true;
                }
            }
            if (!found) {
                // Boot added none (it does under native): one of our own, with Boot's header names.
                RemoteIpValve ip = new RemoteIpValve();
                ip.setRemoteIpHeader("X-Forwarded-For");
                ip.setProtocolHeader("X-Forwarded-Proto");
                ip.setHostHeader("X-Forwarded-Host");
                ip.setPortHeader("X-Forwarded-Port");
                narrow(ip);
                factory.addEngineValves(ip);
            }
        }

        static void narrow(RemoteIpValve ip) {
            ip.setInternalProxies(INTERNAL_PROXIES);
            // No "trusted" proxies beyond the internal ones: an address in the chain is believed only when it is ours.
            ip.setTrustedProxies(null);
        }

        private static java.util.List<Valve> engineValves(ConfigurableTomcatWebServerFactory factory) {
            if (factory instanceof org.springframework.boot.web.embedded.tomcat.TomcatReactiveWebServerFactory r) {
                return new java.util.ArrayList<>(r.getEngineValves());
            }
            if (factory instanceof org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory s) {
                return new java.util.ArrayList<>(s.getEngineValves());
            }
            return java.util.List.of();
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
