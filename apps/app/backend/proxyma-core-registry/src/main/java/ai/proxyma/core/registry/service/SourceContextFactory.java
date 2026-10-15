package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.spi.ConnectionTokenProvider;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.SourceScope;
import ai.proxyma.core.connector.spi.StoredConfig;
import ai.proxyma.core.connector.spi.TokenSupplier;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

/**
 * Builds the {@link SourceContext} a session is opened over, from ONE source's row - fresh for every
 * pass, every fetch batch and every registration probe. This is what lets two sources of one type
 * run at once: nothing a session reads lives anywhere another source can write.
 *
 * <p>The connection is resolved AS THE SOURCE'S OWNER (ConnectorEndpointResolver.connection), the
 * rule sync has always followed: background work has no caller, and indexing with the owner's
 * credential is what the owner asked for. A connection that signs in gets its token supplier here,
 * from the {@link ConnectionTokenProvider} for its protocol - the session never sees a refresh token.
 */
@Component
public class SourceContextFactory {

    private final ConnectorEndpointResolver endpoints;
    private final ConnectorCatalog catalog;
    private final ConnectorHttp http;
    private final ObjectProvider<ConnectionTokenProvider> tokenProviders;

    public SourceContextFactory(ConnectorEndpointResolver endpoints, ConnectorCatalog catalog, ConnectorHttp http,
                                ObjectProvider<ConnectionTokenProvider> tokenProviders) {
        this.endpoints = endpoints;
        this.catalog = catalog;
        this.http = http;
        this.tokenProviders = tokenProviders;
    }

    /** The context of one registered source - or of a candidate a registration is still checking. */
    public SourceContext build(RegisteredConnector source) {
        Map<String, String> config = StoredConfig.fields(source.getConnectorType(), source.getConfig());
        ResolvedCredentials credentials = endpoints.connection(source).map(this::credentials)
                .orElse(ResolvedCredentials.none());
        return new SourceContext(source.getId(), source.getConnectorType(), source.getUserId(), source.getName(),
                config, source.getConfig(), source.getConnectionId(), credentials,
                new SourceScope(source.getFileTypes(), source.isRecursive(), null), http, Clock.systemUTC());
    }

    /** A connection as a session holds it - its secrets decrypted, its sign-in's token supplier attached. */
    public ResolvedCredentials credentials(ResolvedConnection c) {
        return new ResolvedCredentials(c.id(), c.protocol(), c.authKind(), c.baseUrl(), c.username(),
                c.secrets(), c.settings(), tokens(c), ConfigJson.parse(c.accountJson()));
    }

    private TokenSupplier tokens(ResolvedConnection c) {
        if (tokenProviders == null) return null;
        return tokenProviders.orderedStream()
                .filter(p -> p.supports(c.protocol(), c.authKind()))
                .findFirst()
                .map(p -> p.tokensFor(c.id()))
                .orElse(null);
    }
}
