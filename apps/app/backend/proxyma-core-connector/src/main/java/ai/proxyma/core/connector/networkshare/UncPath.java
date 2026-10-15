package ai.proxyma.core.connector.networkshare;

import java.util.ArrayList;
import java.util.List;

/**
 * A location on a Windows file server: {@code (host, share, path)}.
 *
 * <p>ONE NORMALIZED FORM, because three different things compare against it. The registered form is
 * what duplicate detection compares, what the allow-list checks the host of, and what a person
 * reads back in the UI - so {@code \\HOST\Share\a\b\}, {@code //host/Share/a/b} and
 * {@code smb://host/Share/a/b} all have to reduce to the same value. Accepting what people paste
 * and normalizing once here is what keeps those three from disagreeing.
 *
 * <p>The HOST is lower-cased and the SHARE is not. DNS is case-insensitive so the host is a
 * canonical key; a share name is a label the server chose, and Windows preserves its case when it
 * shows it to people.
 *
 * <p>See docs/architecture.html#spec-05-3.
 */
public record UncPath(String host, String share, String path) {

    /** The id ceiling a document row will accept. A longer one is skipped rather than truncated. */
    public static final int MAX_SOURCE_ID_LENGTH = 2048;

    public UncPath {
        host = host == null ? "" : host;
        share = share == null ? "" : share;
        path = path == null ? "" : path;
    }

    /**
     * Parses anything a person is likely to paste.
     *
     * @throws IllegalArgumentException with a message written FOR THAT PERSON - it is shown in the
     *         registration dialog, so it names the shape wanted rather than the rule broken.
     */
    public static UncPath parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Network path must not be empty");
        }
        String text = raw.trim();

        // smb:// is what toSourceId() emits, so a path round-trips through an item id. The other
        // two are what Explorer and a browser respectively put on the clipboard.
        String remainder;
        if (text.regionMatches(true, 0, "smb://", 0, 6)) {
            remainder = text.substring(6);
        } else if (text.startsWith("\\\\") || text.startsWith("//")) {
            remainder = text.substring(2);
        } else {
            throw new IllegalArgumentException(
                    "Network path must start with \\\\server\\share "
                            + "(for example \\\\fileserver.example.com\\share\\folder)");
        }

        // Split on any run of either separator: people paste mixed forms, and a doubled separator
        // in the middle is a typo rather than an empty segment that means something.
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < remainder.length(); i++) {
            char c = remainder.charAt(i);
            if (c == '\\' || c == '/') {
                if (current.length() > 0) {
                    segments.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) segments.add(current.toString());

        if (segments.size() < 2) {
            throw new IllegalArgumentException(
                    "Network path must name a server and a share, e.g. \\\\server\\share");
        }

        String host = segments.get(0).trim().toLowerCase();
        String share = segments.get(1).trim();
        if (host.isEmpty()) throw new IllegalArgumentException("Network path has no server name");
        if (share.isEmpty()) throw new IllegalArgumentException("Network path has no share name");

        StringBuilder tail = new StringBuilder();
        for (int i = 2; i < segments.size(); i++) {
            String segment = segments.get(i).trim();
            if (segment.isEmpty() || ".".equals(segment)) continue;
            // Rejected rather than resolved. Resolving it here would let a registered path climb
            // out of the folder its owner chose, and the allow-list only ever saw the host.
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Network path must not contain '..'");
            }
            if (tail.length() > 0) tail.append('\\');
            tail.append(segment);
        }
        return new UncPath(host, share, tail.toString());
    }

    /** {@code \\host\share[\path]} - the registered form, and what a person reads. */
    public String toUnc() {
        StringBuilder sb = new StringBuilder("\\\\").append(host).append('\\').append(share);
        if (!path.isEmpty()) sb.append('\\').append(path);
        return sb.toString();
    }

    /**
     * {@code smb://host/share[/path]} - the item id.
     *
     * <p>URI-shaped on purpose. An id is rendered in citations and tool results, where a
     * {@code \\server\share\...} string reads as a path on the machine the reader is sitting at;
     * a scheme says plainly that it is somewhere else. It also parses back through {@link #parse}.
     */
    public String toSourceId() {
        StringBuilder sb = new StringBuilder("smb://").append(host).append('/').append(share);
        if (!path.isEmpty()) sb.append('/').append(path.replace('\\', '/'));
        return sb.toString();
    }

    /** The child of this location. */
    public UncPath resolve(String name) {
        String child = path.isEmpty() ? name : path + "\\" + name;
        return new UncPath(host, share, child);
    }

    /** The last path segment, or the share itself at a share root. */
    public String name() {
        if (path.isEmpty()) return share;
        int slash = path.lastIndexOf('\\');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    @Override
    public String toString() {
        return toUnc();
    }
}
