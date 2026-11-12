package ai.proxyma.core.connector.spi;

/**
 * What the service itself calls a source, and the source's own page there, as a session found them
 * - a YouTube channel's title and its address. Given by {@link ConnectorSession#label()} after the
 * probe or a listing, from what the session already read.
 *
 * <p>The registry names the source after it - at registration and again after every pass, so a
 * channel renamed on YouTube is renamed here too - but only for a type whose name is a label rather
 * than what tells two of its sources apart ({@link ConnectorPlugin#identifiedByConfig()}): renaming
 * any other type would change which source it is. The page is kept in the source's config under
 * {@link #WEB_URL}, for its plugin's {@link ConnectorPlugin#webUrl} to read; only an http(s) address
 * is kept.
 *
 * @param name   the source's name in the service, or null when the session did not learn it
 * @param webUrl the source's own page, or null
 */
public record SourceLabel(String name, String webUrl) {

    /** The config key a source's own page is kept under, beside its descriptor's fields. */
    public static final String WEB_URL = "webUrl";
}
