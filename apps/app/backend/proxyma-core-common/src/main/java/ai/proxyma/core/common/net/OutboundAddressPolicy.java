package ai.proxyma.core.common.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Refuses outbound requests to private, loopback, link-local and metadata addresses - where the
 * deployment turns it on (`proxyma.outbound.block-private`, set by the `sandbox` profile).
 *
 * <p><b>WHY.</b> Several features fetch a URL somebody else chose: the agent's webFetch, a web page
 * source and every page it links to, a Jira, Confluence or Bitbucket connection's server, an SVN
 * repository. On a customer's own install that is the point - their intranet wiki is exactly what
 * they want indexed. On a PUBLIC instance anybody can sign in to, it makes the server a proxy into
 * whatever network it sits on: for the Enterprise sandbox, the host that also serves GitLab, the
 * license issuer and its signing key.
 *
 * <p><b>THIS IS THE SECOND LAYER, NOT THE FIRST.</b> The sandbox's own firewall rules
 * (infra/homelab/sandbox-egress.sh) drop every packet from its containers to a private range, and
 * that holds for the stacks this class never sees: HtmlUnit's and Chromium's own networking when a
 * page is rendered, the `svn` process, the Azure SDK, and a DNS answer that changes between this
 * check and the connect. What this class adds is an explanation - a refusal the visitor can read,
 * instead of a request that hangs until it times out - and a check on every redirect hop of the
 * shared HTTP client (RestClientConfig), where a public URL answering 302 to 127.0.0.1 would
 * otherwise be followed.
 *
 * <p>Off everywhere else, so nothing a customer does is affected - except for ONE narrow rule that
 * holds on every edition: link-local addresses (169.254.0.0/16, fe80::/10) and the cloud instance
 * metadata endpoints. Reaching the intranet is a feature; the metadata service is never a legitimate
 * target, and on an Enterprise Server running on a cloud VM or in Kubernetes it hands out the
 * machine's own credentials to whoever can make it fetch {@code http://169.254.169.254/}. An operator
 * who really needs a link-local destination turns that rule off with
 * {@code proxyma.outbound.block-metadata=false}.
 *
 * <p>Static rather than a bean because the call sites span six modules and several are not
 * Spring-managed; the state is set once at startup by RestClientConfig's constructor.
 */
public final class OutboundAddressPolicy {

    private static volatile boolean enabled;
    private static volatile boolean metadataBlocked = true;
    private static volatile java.util.Set<String> allowedHosts = java.util.Set.of();

    // The names cloud providers serve instance metadata under. Refused by name as well as by
    // address, since a proxy resolves the name where this JVM cannot see the answer.
    private static final java.util.Set<String> METADATA_NAMES = java.util.Set.of(
            "metadata.google.internal", "metadata.goog", "metadata", "instance-data",
            "instance-data.ec2.internal");

    private OutboundAddressPolicy() {}

    /** Called once at startup from the `proxyma.outbound.block-private` property. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * Host names exempt from the check - `proxyma.outbound.allowed-hosts`, for the operator's own
     * services on the private network, such as a model server the deployment is configured to use.
     * Exact names, compared without case. The firewall needs the same exception.
     */
    public static void setAllowedHosts(java.util.Collection<String> hosts) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (hosts != null) {
            for (String h : hosts) {
                if (h != null && !h.isBlank()) set.add(h.trim().toLowerCase(Locale.ROOT));
            }
        }
        allowedHosts = java.util.Set.copyOf(set);
    }

    /**
     * Called once at startup from {@code proxyma.outbound.block-metadata} (default true): whether
     * link-local and cloud metadata destinations are refused on this deployment even where the full
     * private-address rule is off.
     */
    public static void setMetadataBlocked(boolean on) {
        metadataBlocked = on;
    }

    /** Whether the full private-address rule is on (the public sandbox). */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Whether any check is on - the full rule, or only the metadata one every edition carries. */
    public static boolean isActive() {
        return enabled || metadataBlocked;
    }

    /** Thrown for a refused destination. Its message is the sentence to show the user. */
    public static final class BlockedDestinationException extends IllegalArgumentException {
        public BlockedDestinationException(String message) {
            super(message);
        }
    }

    /** Checks a URL given as text. A malformed URL is left to the caller's own validation. */
    public static void check(String url) {
        if (!isActive() || url == null) return;
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return;
        }
        check(uri);
    }

    /** Throws {@link BlockedDestinationException} if this deployment may not reach the URL's host. */
    public static void check(URI uri) {
        if (!isActive() || uri == null) return;
        if (!enabled) {
            checkMetadata(uri.getHost());
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BlockedDestinationException(
                    "This deployment only reaches http and https addresses, so " + scheme + ":// is not allowed.");
        }
        checkHost(uri.getHost());
    }

    /** Throws if any address the host resolves to is private, loopback, link-local or reserved. */
    public static void checkHost(String host) {
        if (!enabled) {
            checkMetadata(host);
            return;
        }
        if (host == null || host.isBlank()) {
            throw new BlockedDestinationException("The address has no host name.");
        }
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        String lower = bare.toLowerCase(Locale.ROOT);
        if (allowedHosts.contains(lower)) return;
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")
                || lower.endsWith(".internal") || lower.endsWith(".lan") || lower.endsWith(".home.arpa")) {
            throw refused(host);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(bare);
        } catch (UnknownHostException e) {
            // Nothing to connect to, so nothing to protect; the request fails on its own terms.
            return;
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) throw refused(host);
        }
    }

    /**
     * The rule every edition carries unless the operator turned it off: no link-local and no cloud
     * metadata destination. A host that does not resolve is left to fail on its own terms.
     */
    private static void checkMetadata(String host) {
        if (!metadataBlocked || host == null || host.isBlank()) return;
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        String lower = bare.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".")) lower = lower.substring(0, lower.length() - 1);
        if (allowedHosts.contains(lower)) return;
        if (METADATA_NAMES.contains(lower)) throw refusedMetadata(host);
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(bare);
        } catch (UnknownHostException e) {
            return;
        }
        for (InetAddress address : addresses) {
            if (isMetadata(address)) throw refusedMetadata(host);
        }
    }

    /**
     * Link-local (169.254.0.0/16, fe80::/10, where AWS, Azure, GCP and Oracle serve instance
     * metadata), AWS's IPv6 metadata address fd00:ec2::254 and Alibaba Cloud's 100.100.100.200 -
     * IPv4-mapped forms included. Package-visible for the test.
     */
    static boolean isMetadata(InetAddress address) {
        if (address.isLinkLocalAddress()) return true;
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return (b[0] & 0xff) == 100 && (b[1] & 0xff) == 100 && (b[2] & 0xff) == 100 && (b[3] & 0xff) == 200;
        }
        if (address instanceof Inet6Address) {
            if (b.length == 16 && b[0] == (byte) 0xfd && b[1] == 0 && b[2] == 0x0e && b[3] == (byte) 0xc2) {
                boolean rest = true;
                for (int i = 4; i < 14; i++) if (b[i] != 0) { rest = false; break; }
                if (rest && b[14] == 0x02 && (b[15] & 0xff) == 0x54) return true;
            }
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isMetadata(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The ranges this refuses. Package-visible for the test. */
    static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0                                   // 0.0.0.0/8
                    || (first == 100 && (second & 0xc0) == 64)  // 100.64.0.0/10, carrier-grade NAT
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // 192.0.0.0/24
                    || (first == 198 && (second & 0xfe) == 18)  // 198.18.0.0/15, benchmarking
                    || first >= 240;                            // 240.0.0.0/4 and broadcast
        }
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true;             // fc00::/7, unique local
            // IPv4-mapped (::ffff:a.b.c.d) and the NAT64 prefix carry an IPv4 address that must be
            // judged as one, or ::ffff:127.0.0.1 walks straight past every rule above.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
            boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                    && (b[3] & 0xff) == 0x9b;
            if ((mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) || nat64) {
                try {
                    return isBlocked(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BlockedDestinationException refusedMetadata(String host) {
        return new BlockedDestinationException("This deployment does not reach " + host
                + ": it is a link-local or cloud instance metadata address, which no feature may fetch.");
    }

    private static BlockedDestinationException refused(String host) {
        return new BlockedDestinationException("This deployment does not reach " + host
                + ": it is a private or local network address. Only public internet addresses are allowed here.");
    }
}
