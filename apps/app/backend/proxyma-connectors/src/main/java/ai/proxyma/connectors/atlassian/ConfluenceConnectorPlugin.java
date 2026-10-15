package ai.proxyma.connectors.atlassian;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.SourceContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * A Confluence space as a source - its pages, each with its comments - on Confluence Cloud or on
 * Data Center and Server, whichever its connection signs in to.
 *
 * <p><b>Identity</b> is the space key as typed; the registry compares names without case and
 * compares the server, so one key on two servers is two sources.
 *
 * <p><b>Incremental</b>: a CQL search for pages AND comments changed since the last complete pass,
 * widened by a day and 15 minutes because Confluence reads the cutoff in its own zone. A comment
 * makes no page version, so it stands for its page - the reason a new comment re-indexes the page it
 * is on.
 *
 * <p><b>Deletion</b>: a full pass lists every current page of the space, and the sync engine removes
 * what is absent; a page deleted between passes is gone at its next fetch (a 404).
 */
@Component
public class ConfluenceConnectorPlugin implements ConnectorPlugin {

    static final String SPACE_KEY = "spaceKey";

    @Override
    public ConnectorDescriptor descriptor() {
        return CoreDescriptors.of(ConnectorType.CONFLUENCE);
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new ConfluenceSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        String key = config.get(SPACE_KEY);
        return key == null ? "" : key.strip();
    }

    /**
     * The space's page: {@code /display/KEY} on Data Center, {@code /wiki/spaces/KEY} on a Cloud
     * site. Which one is told by the server's address, since the connection's way of signing in is
     * not in hand here.
     */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String key = identity(config);
        if (key.isEmpty() || serverUrl == null || serverUrl.isBlank()) return null;
        String base = serverUrl.strip().replaceAll("/+$", "");
        if (base.toLowerCase(java.util.Locale.ROOT).contains(".atlassian.net")) {
            return base.replaceAll("(?i)/wiki$", "") + "/wiki/spaces/" + key;
        }
        return base + "/display/" + key;
    }
}
