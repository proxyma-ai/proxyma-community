package ai.proxyma.core.network;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.server.ConnectionInfo;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Forwarded headers, believed only from a proxy (editions-assembly-02).
 *
 * <p>{@code server.forward-headers-strategy=native} hands every request to Reactor Netty's default
 * handler, which believes {@code Forwarded} and {@code X-Forwarded-*} FROM ANY PEER and takes the
 * FIRST X-Forwarded-For entry as the client - the one the client wrote. Behind Cloudflare, which
 * appends the real address, {@code X-Forwarded-For: 8.8.8.8} made the request come "from" 8.8.8.8,
 * and a different made-up address per attempt was a fresh sign-in and forgot-password bucket every
 * time: SEC-48 again, one layer below ApiRateLimitFilter, which had only ever seen the rewritten
 * address.
 *
 * <p>This replaces that handler where the strategy is native (Enterprise Server). A request from a
 * peer that is a proxy by ApiRateLimitFilter's definition - loopback or a private network, where the
 * bundled Caddy, a Docker bridge or cloudflared sits - has its scheme, host and client address taken
 * from the headers, the client being the LAST X-Forwarded-For entry, which is the one the proxy
 * appended. A request from anywhere else is taken as it arrived. The scheme and host still matter:
 * Spring Security builds the OIDC redirect_uri from them.
 *
 * <p>{@code X-Forwarded-Prefix} is not supported; no deployment of this app is mounted under a path.
 */
@Configuration
@ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "native")
public class TrustedForwardedHeaders {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

    // Applied after Spring Boot's own `forwarded(true)`, which it replaces.
    @Bean
    public NettyServerCustomizer trustedForwardedHeadersCustomizer() {
        return server -> server.forwarded((info, request) -> apply(info, request, ApiRateLimitFilter::isProxyPeer));
    }

    static ConnectionInfo apply(ConnectionInfo info, HttpRequest request, Predicate<InetAddress> trustedPeer) {
        InetSocketAddress peer = info.getRemoteAddress();
        if (peer == null || peer.getAddress() == null || !trustedPeer.test(peer.getAddress())) return info;
        HttpHeaders headers = request.headers();
        Forwarded forwarded = headers.contains("Forwarded")
                ? fromForwarded(headers.getAll("Forwarded"))
                : fromXForwarded(headers);

        if ("http".equals(forwarded.proto) || "https".equals(forwarded.proto)) info = info.withScheme(forwarded.proto);
        if (forwarded.host != null && !forwarded.host.isBlank()) {
            String host = forwarded.host.trim();
            int port = forwarded.port;
            // host:port, or [v6]:port. A colon after a bracket, or a single colon, is a port.
            int bracket = host.indexOf(']');
            int colon = host.lastIndexOf(':');
            if (colon > 0 && colon > bracket && (bracket >= 0 || host.indexOf(':') == colon)) {
                port = parsePort(host.substring(colon + 1), port);
                host = host.substring(0, colon);
            }
            if (port < 0) port = ConnectionInfo.getDefaultHostPort(info.getScheme());
            info = info.withHostAddress(InetSocketAddress.createUnresolved(host, port), host, port);
        }
        InetAddress client = literalAddress(forwarded.forValue);
        if (client != null) info = info.withRemoteAddress(new InetSocketAddress(client, peer.getPort()));
        return info;
    }

    private record Forwarded(String proto, String host, int port, String forValue) {}

    // Several X-Forwarded-For lines are one list. The LAST entry is the address the proxy saw; the
    // ones before it are whatever the client sent.
    private static Forwarded fromXForwarded(HttpHeaders headers) {
        String proto = first(headers.get("X-Forwarded-Proto"));
        String host = first(headers.get("X-Forwarded-Host"));
        int port = parsePort(first(headers.get("X-Forwarded-Port")), -1);
        List<String> forLines = headers.getAll("X-Forwarded-For");
        String forValue = forLines.isEmpty() ? null : last(String.join(",", forLines));
        return new Forwarded(proto == null ? null : proto.toLowerCase(Locale.ROOT), host, port, forValue);
    }

    // RFC 7239: comma-separated elements, the LAST appended by the nearest proxy, each a list of
    // key=value pairs separated by semicolons, values optionally quoted.
    private static Forwarded fromForwarded(List<String> lines) {
        String element = last(String.join(",", lines));
        String proto = null, host = null, forValue = null;
        if (element != null) {
            for (String pair : element.split(";")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String key = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = unquote(pair.substring(eq + 1).trim());
                switch (key) {
                    case "proto" -> proto = value.toLowerCase(Locale.ROOT);
                    case "host" -> host = value;
                    case "for" -> forValue = value;
                    default -> { }
                }
            }
        }
        return new Forwarded(proto, host, -1, forValue);
    }

    /**
     * An IP literal from a forwarded value - {@code 1.2.3.4}, {@code 1.2.3.4:5678}, {@code ::1},
     * {@code [::1]:5678} - or null. Never a DNS lookup: a name, {@code unknown} and RFC 7239's
     * obfuscated {@code _hidden} identifiers are all null.
     */
    static InetAddress literalAddress(String value) {
        if (value == null) return null;
        String v = value.trim();
        boolean bracketed = v.startsWith("[");
        if (bracketed) {
            int close = v.indexOf(']');
            if (close < 0) return null;
            v = v.substring(1, close);
        } else if (v.indexOf(':') > 0 && v.indexOf(':') == v.lastIndexOf(':')) {
            v = v.substring(0, v.indexOf(':'));     // IPv4 with a port
        }
        try {
            if (IPV4.matcher(v).matches()) return InetAddress.getByName(v);
            // Bracketed, so the resolver treats it as an IPv6 literal and never looks it up.
            if (v.contains(":") && IPV6.matcher(v).matches()) return InetAddress.getByName("[" + v + "]");
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }

    private static String first(String value) {
        if (value == null) return null;
        int comma = value.indexOf(',');
        String first = (comma < 0 ? value : value.substring(0, comma)).trim();
        return first.isEmpty() ? null : first;
    }

    private static String last(String value) {
        if (value == null) return null;
        String last = value.substring(value.lastIndexOf(',') + 1).trim();
        return last.isEmpty() ? null : last;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static int parsePort(String value, int fallback) {
        if (value == null) return fallback;
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
