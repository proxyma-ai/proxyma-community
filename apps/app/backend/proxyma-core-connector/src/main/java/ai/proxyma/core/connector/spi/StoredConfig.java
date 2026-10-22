package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectorType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A source's configuration as its session reads it, from the stored config column - the ONE reader
 * of that column, so sync, the source's card and the agent's status listing agree on what a source
 * is configured with.
 *
 * <p>What the column holds: the descriptor's fields as a JSON object ({@link ConfigJson}) for every
 * type, except the three file-backed ones ({@link ConnectorType#isFileBacked()}), whose column is the
 * folder's absolute path as it is - read here as the field {@code path}. A path never reaches a
 * client and is not a field anybody edits through the generic form, so it is not wrapped in one.
 *
 * <p>A row written before its type stored JSON is read in the shape it was written in, so a source
 * an older build registered still syncs: nothing is written back, and every new registration stores
 * JSON. Every install is a first install, so this is a courtesy to a database that outlived its
 * build, not a migration.
 */
public final class StoredConfig {

    private StoredConfig() {}

    /** The source's configuration by field key; empty when nothing is stored. */
    public static Map<String, String> fields(ConnectorType type, String stored) {
        if (stored == null || stored.isBlank()) return Map.of();
        if (type != null && type.isFileBacked()) {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("path", stored);
            return out;
        }
        if (stored.strip().startsWith("{")) return ConfigJson.parse(stored);
        Map<String, String> out = new LinkedHashMap<>();
        if (type == null) return out;
        switch (type) {
            // The whole network path.
            case NETWORK_SHARE -> out.put("uncPath", stored.strip());
            // The whole repository URL.
            case SVN -> out.put("repoPath", stored.strip());
            // The site's address; its name is the source's (fields with a name, below).
            case SHAREPOINT -> out.put("siteUrl", stored.strip());
            // Newline key=value lines; the last occurrence of a key wins, as it did.
            case WEB_PAGE, TEAMS_CHAT -> lines(stored, out);
            case OUTLOOK -> {
                lines(stored, out);
                // The folder's or calendar's name was folderName or calendarName; the field is name.
                String name = out.containsKey("calendarName") ? out.get("calendarName") : out.get("folderName");
                if (name != null && !out.containsKey("name")) out.put("name", name);
            }
            default -> {
                // A Jira project, a Confluence space, a Bitbucket repository or a Teams channel stored
                // its server's URL or nothing: what identifies it is its name (fields with a name).
            }
        }
        return out;
    }

    /**
     * As {@link #fields(ConnectorType, String)}, for a source whose NAME is in hand - which several
     * types' older rows need, because the name was the only place their key was kept: a Jira
     * project's or Confluence space's key, a Bitbucket repository's {@code PROJ/repo}, a Teams
     * channel's {@code team/channel}, a SharePoint site's name. Read without it, the required fields
     * came up empty on the source's dialog, which refused to save without them, and the values typed
     * in were then refused as "a different source" - so the source could not even be moved to
     * another connection (0.36.3 feedback). The sessions have always read the name this way.
     */
    public static Map<String, String> fields(ConnectorType type, String stored, String name) {
        Map<String, String> out = fields(type, stored);
        if (type == null || name == null || name.isBlank()) return out;
        Map<String, String> filled = new LinkedHashMap<>(out);
        String n = name.strip();
        switch (type) {
            case JIRA -> fillIfBlank(filled, "projectKey", n);
            case CONFLUENCE -> fillIfBlank(filled, "spaceKey", n);
            case SHAREPOINT -> {
                if (!blank(filled.get("siteUrl"))) fillIfBlank(filled, "siteName", n);
            }
            case BITBUCKET -> {
                int slash = n.indexOf('/');
                if (slash > 0 && slash < n.length() - 1 && blank(filled.get("projectKey")) && blank(filled.get("repoSlug"))) {
                    filled.put("projectKey", n.substring(0, slash));
                    filled.put("repoSlug", n.substring(slash + 1));
                }
            }
            case TEAMS -> {
                int slash = n.indexOf('/');
                if (slash > 0 && slash < n.length() - 1 && blank(filled.get("teamName")) && blank(filled.get("channelName"))) {
                    filled.put("teamName", n.substring(0, slash));
                    filled.put("channelName", n.substring(slash + 1));
                }
            }
            default -> {
                return out;
            }
        }
        return filled;
    }

    private static void fillIfBlank(Map<String, String> config, String key, String value) {
        if (blank(config.get(key))) config.put(key, value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void lines(String stored, Map<String, String> into) {
        for (String line : stored.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) into.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
    }
}
