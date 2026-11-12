package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectionTokenProvider;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.TokenSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A YOUTUBE CONNECTION STILL CALLED "YouTube" IS NAMED AFTER ITS CHANNEL, with nobody pressing Test (2026-11-11, the
 * maintainer: "What if the user don't hit the connection test when quota is come back?"). The Add a channel dialog
 * gives a connection that name until its channel is known; when the check at sign-in could not say - the daily limit
 * spent - a team's comment sync names it on its next pass (CommentQueue.nameAfterChannel), and a connection no team
 * reads is named here: once an hour, each such connection is tested as Test would, and a test that says the channel's
 * title names it (ConnectorConnectionService.nameAfterAccount). One unit each, and none while the built-in sign-in's
 * daily share is spent - this runs on a scheduler thread, which never takes the identity reserve.
 */
@Component
public class ConnectionNaming {

    private static final Logger log = LoggerFactory.getLogger(ConnectionNaming.class);

    /** The name the Add a channel dialog gives a YouTube connection until the channel is known (youtubeSetup.ts). */
    public static final String YOUTUBE_PLACEHOLDER = "YouTube";

    private final ConnectorConnectionRepository repository;
    private final ConnectorConnectionService service;
    private final ConnectorCatalog catalog;
    private final ConnectorHttp http;
    private final ObjectProvider<ConnectionTokenProvider> tokenProviders;
    private final ObjectProvider<OAuth2ConnectionService> oauth;

    public ConnectionNaming(ConnectorConnectionRepository repository, ConnectorConnectionService service,
                            ConnectorCatalog catalog, ConnectorHttp http,
                            ObjectProvider<ConnectionTokenProvider> tokenProviders,
                            ObjectProvider<OAuth2ConnectionService> oauth) {
        this.repository = repository;
        this.service = service;
        this.catalog = catalog;
        this.http = http;
        this.tokenProviders = tokenProviders;
        this.oauth = oauth;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    public void nameWaitingConnections() {
        try {
            int named = nameWaiting();
            if (named > 0) log.info("[Connections] {} YouTube connection(s) named after their channel", named);
        } catch (RuntimeException e) {
            log.warn("[Connections] YouTube connections could not be named: {}", e.getMessage());
        }
    }

    /** Names every YouTube connection still called {@link #YOUTUBE_PLACEHOLDER} whose test says its channel. How many. */
    int nameWaiting() {
        List<ConnectorConnection> waiting = repository.findByProtocolAndName(ConnectionProtocol.YOUTUBE, YOUTUBE_PLACEHOLDER);
        if (waiting.isEmpty()) return 0;
        ConnectionPlugin plugin = catalog.connectionPlugin(ConnectionProtocol.YOUTUBE).orElse(null);
        if (plugin == null) return 0;
        OAuth2ConnectionService signIns = oauth == null ? null : oauth.getIfAvailable();
        int named = 0;
        for (ConnectorConnection c : waiting) {
            if (signIns != null && signIns.builtInSpentUntil(c.getId()).isPresent()) continue;
            ResolvedConnection r = service.resolve(c.getId()).orElse(null);
            if (r == null) continue;
            ConnectionTestResult result = test(plugin, r, tokenProviders, http);
            String channel = result.ok() && result.params() != null ? result.params().get("user") : null;
            if (service.nameAfterAccount(c.getId(), YOUTUBE_PLACEHOLDER, channel)) named++;
        }
        return named;
    }

    /**
     * A connection tested through its plugin as Settings > Connections tests it (ConnectorConnectionController): its
     * secrets, settings and a token supplier that refreshes. A plugin that throws is a failed test.
     */
    static ConnectionTestResult test(ConnectionPlugin plugin, ResolvedConnection c,
                                     ObjectProvider<ConnectionTokenProvider> tokenProviders, ConnectorHttp http) {
        try {
            if (plugin instanceof LegacyConnectionPlugins.Adapter legacy) return legacy.test(c);
            TokenSupplier tokens = tokenProviders == null ? null : tokenProviders.orderedStream()
                    .filter(p -> p.supports(c.protocol(), c.authKind()))
                    .findFirst().map(p -> p.tokensFor(c.id())).orElse(null);
            return plugin.test(new ResolvedCredentials(c.id(), c.protocol(), c.authKind(), c.baseUrl(), c.username(),
                    c.secrets(), c.settings(), tokens, ai.proxyma.core.connector.spi.ConfigJson.parse(c.accountJson())), http);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getMessage() == null ? "The test could not run." : e.getMessage());
        }
    }
}
