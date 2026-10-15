package ai.proxyma.core.connector.spi;

import java.util.Map;

/**
 * One source type: a stateless Spring bean that describes the type and opens sessions over its
 * sources. Everything the application asks about a type it asks of {@link #descriptor()}, through
 * {@link ConnectorCatalog} - adding a type is a plugin, a session, one enum constant, and nothing
 * else in a shared file.
 *
 * <p>STATELESS. A plugin is shared by every source of its type and by every thread; whatever one
 * source needs lives in its {@link SourceContext} and the session opened over it.
 */
public interface ConnectorPlugin {

    ConnectorDescriptor descriptor();

    /** A session over one source. Cheap and with no network I/O - the first request is the session's. */
    ConnectorSession open(SourceContext context);

    /**
     * What makes two registrations the SAME source, from its validated configuration - a
     * repository's "owner/repo", a project's key. Two registrations with one identity on one server
     * are one source; it is also the source's name unless {@link #displayName} says otherwise. The
     * default joins the required fields' values.
     */
    default String identity(Map<String, String> config) {
        StringBuilder out = new StringBuilder();
        for (FieldSpec f : descriptor().fields()) {
            if (!f.required()) continue;
            String v = config.get(f.key());
            if (v == null || v.isBlank()) continue;
            if (!out.isEmpty()) out.append('/');
            out.append(v.trim());
        }
        return out.toString();
    }

    /**
     * Whether a source of this type is told apart by {@link #identity} ALONE, its name being only a
     * label - so two sources of one name are two sources, and a second gets a number. False for most
     * types, whose name (read through one connection) is what makes them one source. True for an
     * Outlook folder or calendar: two calendars both called "Calendar" (the owner's and a shared
     * mailbox's) are two sources. The registry then matches an existing source by
     * {@link #storedIdentity} rather than by name.
     */
    default boolean identifiedByConfig() {
        return false;
    }

    /**
     * The configuration a registration stores, from the fields the descriptor's own rules have
     * already validated: the rules a field list cannot state (one field required only when another
     * has a given value) and the normal form an identity compares (a mailbox lower-cased). Throws
     * {@link IllegalArgumentException} with the sentence the person reads. The default stores the
     * fields as they are.
     */
    default Map<String, String> prepare(Map<String, String> config) {
        return config;
    }

    /**
     * The identity of a source already stored, from its config column as it stands - for a type
     * {@link #identifiedByConfig() identified by its configuration}, whose older rows may be in a
     * shape of their own. The default reads the column the one way ({@link StoredConfig}).
     */
    default String storedIdentity(String storedConfig) {
        return identity(StoredConfig.fields(descriptor().type(), storedConfig));
    }

    /**
     * Whether new sources of the type are offered right now. False where a deployment switch has
     * turned the type off (Teams channels, Teams chats, Outlook) - the Connect menu leaves it out;
     * sources registered while it was on keep their index.
     */
    default boolean offered() {
        return true;
    }

    /** The name a new source is listed under when the person gave none. */
    default String displayName(Map<String, String> config) {
        return identity(config);
    }

    /**
     * The source's own page in the service - what its name links to on the Sources page - or null
     * when it has none. {@code serverUrl} is the address its connection reaches (null for a type
     * with no connection). Only an http(s) address is ever shown; anything else is dropped.
     */
    default String webUrl(Map<String, String> config, String serverUrl) {
        return null;
    }
}
