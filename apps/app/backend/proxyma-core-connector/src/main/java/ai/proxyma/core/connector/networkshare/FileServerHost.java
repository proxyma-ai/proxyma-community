package ai.proxyma.core.connector.networkshare;

import java.util.Locale;

/**
 * The file server a Network share CONNECTION names - since 2026-10-01, when network shares became a
 * connection like Jira or SVN (maintainer's request) and the connection's server became the one
 * host its sources may reach.
 *
 * <p>People type it every way it appears in Windows: {@code fileserver}, {@code \\fileserver},
 * {@code //fileserver}, {@code smb://fileserver}, or a whole path they copied from Explorer
 * ({@code \\fileserver\share\folder}). All of them name the same server, so all of them reduce to
 * the bare host, lower-cased as {@link UncPath} lower-cases it, so a source's host and its
 * connection's host compare as equal strings.
 *
 * <p>What is REFUSED matters as much: a wildcard, because a connection names one server and a
 * pattern would make it a credential sent to any host matching it; a port, because SMB is 445 and
 * a colon is more likely a typo or an IPv6 literal written without brackets than a real need.
 */
public final class FileServerHost {

    private FileServerHost() {}

    /**
     * @return the bare, lower-cased host
     * @throws IllegalArgumentException with the sentence a person reads, for anything that is not
     *         one server name
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Enter the file server's name.");
        String s = raw.trim();
        if (s.regionMatches(true, 0, "smb://", 0, 6)) s = s.substring(6);
        while (s.startsWith("\\") || s.startsWith("/")) s = s.substring(1);
        int cut = indexOfAny(s, '\\', '/');
        if (cut >= 0) s = s.substring(0, cut);
        s = s.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) throw new IllegalArgumentException("Enter the file server's name.");
        if (s.indexOf('*') >= 0) {
            throw new IllegalArgumentException("A connection names one file server - a pattern such as *.corp.example.com is not allowed.");
        }
        if (s.indexOf(':') >= 0 || s.indexOf(' ') >= 0 || s.indexOf('@') >= 0) {
            throw new IllegalArgumentException("Enter only the file server's name, such as fileserver.corp.example.com.");
        }
        return s;
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a), j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }
}
