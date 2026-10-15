package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectorType;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Everything one session of one source needs, built fresh for every pass and every fetch batch
 * from that source's own row (SourceContextFactory) - the reason two sources of one type can sync
 * at the same time without seeing each other's server, token or scope. Under the old SPI the
 * connector bean held these in fields, and a pass that read them after another source's
 * configuration had replaced them listed the wrong source.
 *
 * @param sourceId   the source's id; null while a registration is still checking a candidate
 * @param ownerId    the owner's user id; null on Desktop and Home Server
 * @param name       the source's name as listed
 * @param config     the source's configuration, by field key - the descriptor's fields, validated
 * @param rawConfig  the stored config column as it is - a folder source's path, or a row an older
 *                   build wrote before its type stored JSON (see StoredConfig)
 * @param connection the connection it reads through, resolved as its owner; none for a type that
 *                   needs none
 * @param http       the shared HTTP client for connectors (retries, pagination, host pinning)
 */
public record SourceContext(UUID sourceId, ConnectorType type, String ownerId, String name,
                            Map<String, String> config, String rawConfig, UUID connectionId,
                            ResolvedCredentials connection, SourceScope scope, ConnectorHttp http, Clock clock) {

    public SourceContext {
        config = config == null ? Map.of() : Map.copyOf(config);
        if (connection == null) connection = ResolvedCredentials.none();
        if (scope == null) scope = SourceScope.defaults();
        if (clock == null) clock = Clock.systemUTC();
    }

    /** One configuration value, or null. */
    public String get(String key) {
        return config.get(key);
    }

    /** One configuration value as a switch, {@code fallback} when absent. */
    public boolean flag(String key, boolean fallback) {
        String v = config.get(key);
        return v == null || v.isBlank() ? fallback : Boolean.parseBoolean(v.trim());
    }

    /** A context with nothing but a configuration and a connection - for a connector's own tests. */
    public static SourceContext of(ConnectorType type, Map<String, String> config, ResolvedCredentials connection,
                                   ConnectorHttp http) {
        return new SourceContext(null, type, null, null, config, null, connection == null ? null : connection.connectionId(),
                connection, SourceScope.defaults(), http, Clock.systemUTC());
    }
}
