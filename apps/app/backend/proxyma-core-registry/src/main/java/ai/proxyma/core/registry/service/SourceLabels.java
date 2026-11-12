package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.SourceLabel;
import ai.proxyma.core.connector.spi.StoredConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a session's {@link SourceLabel} makes of a source's name and config - one rule for the
 * registration's candidate and for the row a pass updates, so the two cannot name a source
 * differently.
 *
 * <p>Only for a type whose name is a label ({@link ConnectorPlugin#identifiedByConfig()}); for any
 * other the name is what makes it which source, and is never changed here. A blank name keeps the
 * current one; a page that is not an http(s) address is not kept. Never throws: the name is a
 * courtesy, and must not fail a registration or a pass.
 */
final class SourceLabels {

    /** The name column's length. */
    static final int MAX_NAME_CHARS = 255;

    private SourceLabels() {}

    /** The name and config a source takes from its label. */
    record Labelled(String name, String config) {}

    /**
     * The source's name and config after its label, or empty when the label changes nothing (or
     * may not change this type).
     */
    static Optional<Labelled> apply(ConnectorPlugin plugin, ConnectorType type, String name, String config,
                                    SourceLabel label) {
        if (plugin == null || label == null || type == null || !plugin.identifiedByConfig()) return Optional.empty();
        String nextName = cleanName(label.name());
        if (nextName == null) nextName = name;
        String nextConfig = config;
        if (!type.isFileBacked()) {
            Map<String, String> before = StoredConfig.fields(type, config);
            Map<String, String> fields = new LinkedHashMap<>(before);
            String url = label.webUrl() == null ? null : label.webUrl().strip();
            if (url != null && (url.startsWith("https://") || url.startsWith("http://")) && url.length() <= 2048) {
                fields.put(SourceLabel.WEB_URL, url);
            } else {
                fields.remove(SourceLabel.WEB_URL);
            }
            if (!fields.equals(before)) {
                String written = ConfigJson.write(fields);
                if (written == null || written.length() <= ConnectorService.MAX_CONFIG_CHARS) nextConfig = written;
            }
        }
        if (Objects.equals(nextName, name) && Objects.equals(nextConfig, config)) return Optional.empty();
        if (nextName == null || nextName.isBlank()) return Optional.empty();
        return Optional.of(new Labelled(nextName, nextConfig));
    }

    // One line, its runs of white space as one space, within the column - or null when nothing is left.
    private static String cleanName(String raw) {
        if (raw == null) return null;
        String one = raw.replaceAll("[\\s\\p{Cc}]+", " ").strip();
        if (one.isEmpty()) return null;
        if (one.length() <= MAX_NAME_CHARS) return one;
        int end = Character.isHighSurrogate(one.charAt(MAX_NAME_CHARS - 1)) ? MAX_NAME_CHARS - 1 : MAX_NAME_CHARS;
        return one.substring(0, end).strip();
    }
}
