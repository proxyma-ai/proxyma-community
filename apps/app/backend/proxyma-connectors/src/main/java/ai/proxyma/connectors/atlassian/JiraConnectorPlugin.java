package ai.proxyma.connectors.atlassian;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.jira.JiraOwnerPreferences;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.SourceContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * A Jira project as a source - its issues, each with its comments and change history - on Jira
 * Cloud or on Data Center and Server, whichever its connection signs in to.
 *
 * <p><b>Identity</b> is the project key, upper case: the same project added twice on one server is
 * one source, and the same key on another server is another (the registry compares the server).
 *
 * <p><b>Incremental</b>: issues updated since the last complete pass, by JQL {@code updated >=} in
 * the token owner's own time zone and widened by 15 minutes ({@link JiraSession#incrementalCutoff}).
 *
 * <p><b>Deletion</b>: a full pass lists every issue key in the project, and the sync engine removes
 * what is absent; an issue deleted between passes is gone at its next fetch (a 404).
 *
 * <p>Its descriptor is the one in CoreDescriptors - every module reads Jira from there - with no
 * legacy traits: the stored configuration is the descriptor's fields as JSON, and sessions of two
 * Jira sources run side by side.
 */
@Component
public class JiraConnectorPlugin implements ConnectorPlugin {

    static final String PROJECT_KEY = "projectKey";

    private final ObjectProvider<JiraOwnerPreferences> preferences;

    @Autowired
    public JiraConnectorPlugin(ObjectProvider<JiraOwnerPreferences> preferences) {
        this.preferences = preferences;
    }

    /** No owner preferences - the built-in custom-field deny list alone. For tests. */
    JiraConnectorPlugin() {
        this.preferences = null;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return CoreDescriptors.of(ConnectorType.JIRA);
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        JiraOwnerPreferences owner = preferences == null ? null : preferences.getIfAvailable();
        String skip = owner == null ? null : owner.skipCustomFields(context.ownerId());
        return new JiraSession(context, skip);
    }

    @Override
    public String identity(Map<String, String> config) {
        String key = config.get(PROJECT_KEY);
        return key == null ? "" : key.strip().toUpperCase(Locale.ROOT);
    }

    @Override
    public String displayName(Map<String, String> config) {
        return identity(config);
    }

    /** The project's page - Data Center and Cloud both answer {@code /projects/KEY}. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String key = identity(config);
        if (key.isEmpty() || serverUrl == null || serverUrl.isBlank()) return null;
        return serverUrl.strip().replaceAll("/+$", "") + "/projects/" + key;
    }
}
