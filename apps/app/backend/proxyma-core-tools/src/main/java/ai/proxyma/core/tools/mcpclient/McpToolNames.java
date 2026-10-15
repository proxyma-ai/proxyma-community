package ai.proxyma.core.tools.mcpclient;

import ai.proxyma.core.settings.service.WriteToolRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * The name an MCP server's tool is advertised to the model under: {@code mcp__<server>__<tool>}.
 *
 * <p>THREE RULES, and each is somebody else's:
 * <ul>
 *   <li>The prefix ({@link WriteToolRegistry#MCP_TOOL_PREFIX}) is what makes every such call ask
 *       first. A server cannot name a tool out of that by choosing its name.</li>
 *   <li>Providers accept {@code [A-Za-z0-9_-]}, at most 64 characters (Anthropic and OpenAI alike).
 *       A server's own tool name can be anything, so it is cut to that alphabet.</li>
 *   <li>Two servers may both have a tool called {@code search}, and two connections may share a
 *       name across users; the server part carries a short hash of the CONNECTION's id, so the
 *       advertised name is unique without depending on either.</li>
 * </ul>
 * A name that would still be too long keeps its start and gains a hash of the whole, so two long
 * names that differ only at the end stay two names.
 */
public final class McpToolNames {

    static final int MAX = 64;
    private static final int SERVER_SLUG = 16;

    private McpToolNames() {}

    /** The server's part: a slug of its name and four characters of its connection id. */
    public static String serverPart(String serverName, UUID connectionId) {
        String slug = slug(serverName, SERVER_SLUG);
        String id = connectionId == null ? "0000" : connectionId.toString().replace("-", "").substring(0, 4);
        return (slug.isEmpty() ? "server" : slug) + "_" + id;
    }

    /** The full advertised name of one tool of one server. */
    //
    // A name the alphabet CHANGED keeps a hash of the original too. The slug lowercases and folds
    // punctuation, so "Search" and "search", or "files.read" and "files_read", of one server were
    // one advertised name: the second was dropped and a call for it ran the first. A name already
    // in the alphabet is advertised as it is.
    public static String toolName(String serverPart, String toolName) {
        String head = WriteToolRegistry.MCP_TOOL_PREFIX + serverPart + "__";
        String raw = toolName == null ? "" : toolName;
        String tool = slug(raw, MAX);
        boolean changed = !tool.equals(raw);
        if (tool.isEmpty()) tool = "tool";
        String full = changed ? head + tool + "_" + hash(raw) : head + tool;
        if (full.length() <= MAX) return full;
        String hash = hash(raw);
        int room = MAX - head.length() - hash.length() - 1;
        return head + tool.substring(0, Math.max(1, room)) + "_" + hash;
    }

    /** Whether an advertised name belongs to this server part. */
    public static boolean belongsTo(String advertised, String serverPart) {
        return advertised != null && advertised.startsWith(WriteToolRegistry.MCP_TOOL_PREFIX + serverPart + "__");
    }

    static String slug(String text, int max) {
        if (text == null) return "";
        String s = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("_+", "_")
                .replaceAll("^[_-]+|[_-]+$", "");
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 6);
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
