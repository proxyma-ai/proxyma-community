package ai.proxyma.core.common.net;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which hosts bypass the configured proxy, read the way NO_PROXY lists are written.
 *
 * <p>The forms people actually write (0.27.2 feedback, a list copied from a company laptop's
 * environment): an exact host; ".corp" and "*.corp" for every name under it; a BARE "corp.example"
 * for itself and every name under it, as curl, Go and Python read it; and a "*" anywhere else as a
 * wildcard, the way Windows' proxy bypass list writes "127.0.0.*" or "10.*". Loopback is never sent
 * to a proxy, whatever the list says - a proxy cannot reach this machine's own loopback services.
 *
 * <p>In common so every client that resolves a proxy itself reads the list the same way. The svn
 * subprocess kept a weaker copy that knew only exact names and "*suffix", so a NO_PROXY of
 * "corp.example" sent svn through the proxy for svn.corp.example while every REST client went
 * direct - the disagreement fixed for streaming chat in 0.27.1, in another client.
 */
public final class NoProxyRules {

    private NoProxyRules() {}

    /** Entries separated by commas, '|', ';' or whitespace, lower-cased. */
    public static Set<String> parse(String noProxy) {
        if (noProxy == null || noProxy.isBlank()) return Set.of();
        return Stream.of(noProxy.split("[,|;\\s]+"))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public static boolean shouldBypass(String host, Set<String> bypassHosts) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        if (isLoopback(lower)) return true;
        if (bypassHosts.contains(lower)) return true;
        for (String pattern : bypassHosts) {
            if (pattern.equals("*")) return true;
            if (pattern.startsWith("*.") && (lower.endsWith(pattern.substring(1)) || lower.equals(pattern.substring(2)))) return true;
            if (pattern.startsWith(".") && (lower.endsWith(pattern) || lower.equals(pattern.substring(1)))) return true;
            if (pattern.indexOf('*') >= 0 && lower.matches(globToRegex(pattern))) return true;
            if (pattern.indexOf('*') < 0 && lower.endsWith("." + pattern)) return true;
        }
        return false;
    }

    private static boolean isLoopback(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return h.equals("localhost") || h.endsWith(".localhost") || h.startsWith("127.") || h.equals("::1");
    }

    private static String globToRegex(String glob) {
        StringBuilder re = new StringBuilder();
        for (String part : glob.split("\\*", -1)) {
            if (re.length() > 0) re.append(".*");
            re.append(Pattern.quote(part));
        }
        return re.toString();
    }
}
