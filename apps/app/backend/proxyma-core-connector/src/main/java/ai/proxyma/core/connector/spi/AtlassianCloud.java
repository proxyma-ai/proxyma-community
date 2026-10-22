package ai.proxyma.core.connector.spi;

import java.net.URI;
import java.util.Locale;

/**
 * The site of an Atlassian Cloud address - the one address Jira's and Confluence's Cloud APIs are
 * joined to, whatever page of the site a person copied from the browser.
 *
 * <p>People paste what their browser shows: {@code https://SITE.atlassian.net/wiki} for Confluence,
 * and since Jira's pages moved under it, {@code https://SITE.atlassian.net/jira} - or a board or
 * project page below either. Every one of those paths serves the web app, so an API call joined to
 * it gets a page back instead of JSON. On {@code *.atlassian.net} the site is the scheme and host
 * alone; on any other host (a custom domain) a trailing {@code /wiki} or {@code /jira} is dropped.
 *
 * <p>For Cloud only. A Data Center server is often installed under a path such as {@code /jira},
 * and that path is part of its address.
 */
public final class AtlassianCloud {

    private AtlassianCloud() {}

    public static String site(String baseUrl) {
        if (baseUrl == null) return null;
        String base = baseUrl.strip().replaceAll("/+$", "");
        try {
            URI uri = URI.create(base);
            String host = uri.getHost();
            if (uri.getScheme() != null && host != null && host.toLowerCase(Locale.ROOT).endsWith(".atlassian.net")) {
                return uri.getScheme() + "://" + uri.getRawAuthority();
            }
        } catch (IllegalArgumentException ignored) {
            // Not a parseable address - trimmed below like any other.
        }
        return base.replaceAll("(?i)/(wiki|jira)$", "");
    }

    /** Whether the address is on Atlassian's Cloud domain. */
    public static boolean isCloudHost(String baseUrl) {
        return baseUrl != null && baseUrl.toLowerCase(Locale.ROOT).contains(".atlassian.net");
    }
}
