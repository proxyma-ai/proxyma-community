package ai.proxyma.core.connector.networkshare;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which file servers this deployment may be pointed at.
 *
 * <p>THIS IS A CREDENTIAL BOUNDARY, not a convenience. Connecting to an SMB server sends it an NTLM
 * exchange for the source owner's domain account - material that can be cracked offline or relayed
 * at another service. Without a list, anyone who can register a source could point it at a server
 * they control and collect that exchange from every user whose source syncs. That includes an
 * indirect route this product has to assume: a retrieved document carrying an injection that talks
 * the agent into calling {@code registerNetworkShare} with an attacker's host.
 *
 * <p>SO AN EMPTY LIST MEANS THE FEATURE IS OFF, not "any host". The inverse default - empty meaning
 * unrestricted - is the version where a deployment that never heard of this connector is the most
 * exposed.
 *
 * <p>It is re-read from settings and re-checked at registration, at the start of every sync walk
 * and before every file fetch, so narrowing it stops sources that are already registered rather
 * than only new ones.
 *
 * <p>See docs/architecture.html#spec-05-4.
 */
public final class HostAllowlist {

    private final Set<String> entries;

    private HostAllowlist(Set<String> entries) {
        this.entries = entries;
    }

    /**
     * Parses {@code network-share.allowed-hosts}. Separators are commas, semicolons and any
     * whitespace, because an administrator pasting a list gets to use whichever they already had.
     */
    public static HostAllowlist parse(String raw) {
        Set<String> parsed = new LinkedHashSet<>();
        if (raw != null) {
            for (String token : raw.split("[,;\\s]+")) {
                String entry = token.trim().toLowerCase();
                // A trailing dot is a fully qualified name written explicitly; it is the same host.
                while (entry.endsWith(".")) entry = entry.substring(0, entry.length() - 1);
                if (!entry.isEmpty()) parsed.add(entry);
            }
        }
        return new HostAllowlist(parsed);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Set<String> entries() {
        return Set.copyOf(entries);
    }

    /**
     * True when {@code host} is listed.
     *
     * <p>An entry is either an exact host name or {@code *.domain.example}, which matches hosts
     * UNDER that domain and not the domain itself - {@code *.corp.example} covers
     * {@code fs1.corp.example} and not {@code corp.example}, because those are different machines
     * and the wildcard was written about the subdomain space.
     *
     * <p>AN IP LITERAL MATCHES ONLY AN IDENTICAL ENTRY. A wildcard is a statement about DNS names,
     * and {@code 10.0.0.1} is not "under" any domain; letting {@code *.example} match an address
     * would turn a name-shaped rule into an address-shaped permission nobody wrote.
     */
    public boolean isAllowed(String host) {
        if (host == null || host.isBlank() || entries.isEmpty()) return false;
        String candidate = host.trim().toLowerCase();
        while (candidate.endsWith(".")) candidate = candidate.substring(0, candidate.length() - 1);
        if (candidate.isEmpty()) return false;

        boolean literalAddress = isIpLiteral(candidate);
        for (String entry : entries) {
            if (entry.equals(candidate)) return true;
            if (!literalAddress && entry.startsWith("*.")) {
                String domain = entry.substring(2);
                if (!domain.isEmpty()
                        && candidate.length() > domain.length() + 1
                        && candidate.endsWith("." + domain)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @throws IllegalArgumentException with the message the source owner sees. The two cases read
     *         differently on purpose: "not enabled" is an administrator's job and "not in the list"
     *         is a request to make of them, and a single message would leave the reader unsure
     *         which they are looking at.
     */
    public void check(String host) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "This source has no usable Network share connection - add one for its file "
                            + "server in Settings > Connections");
        }
        if (!isAllowed(host)) {
            throw new IllegalArgumentException(
                    "File server '" + host + "' is not the server of this source's Network share connection");
        }
    }

    // All digits and dots is IPv4; a colon is IPv6. Deliberately shape-based rather than a parse:
    // the question is only "was this written as a name", and anything ambiguous should fall to the
    // exact-match branch, which is the safe one.
    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true;
        boolean digit = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c >= '0' && c <= '9') { digit = true; continue; }
            if (c == '.') continue;
            return false;
        }
        return digit;
    }
}
