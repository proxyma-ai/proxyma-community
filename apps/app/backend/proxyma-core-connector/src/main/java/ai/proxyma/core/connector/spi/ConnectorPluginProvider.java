package ai.proxyma.core.connector.spi;

import java.util.List;

/**
 * A bean contributing several plugins at once. Today that is the connection side alone:
 * LegacyConnectionPlugins (proxyma-core-settings) presents the services whose Test is still a
 * ConnectionTester - Bitbucket Data Center, SVN, network shares, Microsoft 365, MCP servers - as
 * {@link ConnectionPlugin}s. Every source type is a {@link ConnectorPlugin} bean of its own, and a new
 * service a {@link ConnectionPlugin} bean.
 */
public interface ConnectorPluginProvider {

    /** Source types this provider contributes, if any. */
    default List<ConnectorPlugin> plugins() {
        return List.of();
    }

    /** Services this provider contributes, if any. */
    default List<ConnectionPlugin> connectionPlugins() {
        return List.of();
    }
}
