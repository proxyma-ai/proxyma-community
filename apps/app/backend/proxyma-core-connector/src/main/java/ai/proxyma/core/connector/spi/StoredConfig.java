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
            // A crawl's newline key=value lines; the last occurrence of a key wins, as it did.
            case WEB_PAGE -> {
                for (String line : stored.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
            default -> {
                // JSON only - or, for the Microsoft 365 types, read by their own sessions from the
                // raw column (ai.proxyma.connectors.microsoft.StoredConfig).
            }
        }
        return out;
    }
}
