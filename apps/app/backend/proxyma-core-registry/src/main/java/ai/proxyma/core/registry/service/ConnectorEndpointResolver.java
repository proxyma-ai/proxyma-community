package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a source lives, and what credential reaches it. THE one implementation, deliberately.
 *
 * <p>A source names one of its owner's connections ({@link RegisteredConnector#getConnectionId()});
 * the connection holds the server and the credential. With no source in hand - a tool listing a
 * user's projects - it is the caller's DEFAULT connection for that service.
 *
 * <p>This replaced a chain of three: an override on the source, else the owner's default from
 * Settings &gt; Connections, with an "Other servers" list beside it that sync never read - so a source
 * on a second server indexed with the first server's token. A connection has an id a source can
 * point at, and there is nothing left to chain.
 *
 * <p><b>Why one class.</b> Sync, the chat tools and the MCP tools all have to agree about which
 * server a source lives on. The symptom of disagreement is the worst kind this codebase has: a sync
 * that indexes from server B while a tool call answers from server A, both succeeding.
 *
 * <p><b>The residual risk, stated rather than hidden.</b> The SERVER makes the outbound request, so
 * a user-chosen server URL is this deployment reaching a host of that user's choosing, with that
 * user's own credential - the same shape as {@code webFetch}, and an accepted risk recorded in
 * {@code .claude/memory/security-open-findings.md}. A private-range block is deliberately not
 * applied: a self-hosted Jira on a company LAN is the normal case for this product.
 */
@Service
public class ConnectorEndpointResolver {

    private static final Logger log = LoggerFactory.getLogger(ConnectorEndpointResolver.class);

    /**
     * A resolved endpoint - the connection behind it, flattened for the connectors. {@code EMPTY}
     * when there is no connection to use; callers turn that into a message saying what to add.
     *
     * @param authKind how the connection signs in - which, for Jira and Confluence, is which product
     *                 line it reaches (an email and API token is Cloud), the way their sessions decide it
     */
    public record Endpoint(UUID connectionId, String connectionName, String baseUrl, String credential,
                           boolean allowRead, boolean allowWrite, AuthKind authKind) {

        public static final Endpoint EMPTY = new Endpoint(null, null, null, null, false, false, null);

        public Endpoint(UUID connectionId, String connectionName, String baseUrl, String credential,
                        boolean allowRead, boolean allowWrite) {
            this(connectionId, connectionName, baseUrl, credential, allowRead, allowWrite, null);
        }

        public static Endpoint of(ResolvedConnection c) {
            return new Endpoint(c.id(), c.name(), c.baseUrl(), c.credential(), c.allowRead(), c.allowWrite(),
                    c.authKind());
        }

        public boolean exists() { return connectionId != null; }

        public boolean hasBaseUrl() { return baseUrl != null && !baseUrl.isBlank(); }

        public boolean hasCredential() { return credential != null && !credential.isBlank(); }

        @Override
        public String toString() {
            return "Endpoint[" + connectionName + ", " + baseUrl + ", credential=" + (hasCredential() ? "***" : "none") + "]";
        }
    }

    private final RegisteredConnectorRepository connectorRepository;
    private final ConnectorConnectionService connections;

    public ConnectorEndpointResolver(RegisteredConnectorRepository connectorRepository,
                                     ConnectorConnectionService connections) {
        this.connectorRepository = connectorRepository;
        this.connections = connections;
    }

    /**
     * The endpoint for one registered source, resolved as ITS OWNER - the SYNC path. Background sync
     * legitimately indexes with the owner's credential; nothing about a caller enters into it.
     *
     * <p>The connection must still belong to the source's owner. Registration only ever links a
     * source to its owner's own connection, so a mismatch means a row was edited by hand, and the
     * answer to that is no credential rather than somebody else's.
     */
    public Endpoint resolve(RegisteredConnector connector) {
        return connection(connector).map(Endpoint::of).orElse(Endpoint.EMPTY);
    }

    /**
     * The connection itself, resolved as the source's OWNER - every secret field and setting, for
     * the session a sync opens (SourceContextFactory). The same owner rule as {@link #resolve}.
     */
    public Optional<ResolvedConnection> connection(RegisteredConnector connector) {
        if (connector == null || connector.getConnectionId() == null) return Optional.empty();
        return connections.resolve(connector.getConnectionId())
                .filter(c -> c.ownerId().equals(ConnectorConnectionService.ownerKey(connector.getUserId())));
    }

    // Which service each source type reads through - its descriptor's. Setter-injected, so the
    // constructor tests build this with is unchanged.
    private ConnectorCatalog catalog = ConnectorCatalog.descriptorsOnly();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCatalog(ConnectorCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * The endpoint for one registered source as seen BY A PARTICULAR USER.
     *
     * <p><b>A source somebody else owns contributes nothing - neither its server nor its credential.</b>
     * The credential is a person's: the remote server logs the owner, and a write made through it is
     * attributed to them. Lending it would let anyone the source was shared with act AS its owner,
     * with nothing on either side recording who actually asked. Sharing a source grants visibility of
     * what was indexed, never the access that indexed it. Taking the server from the source and the
     * credential from the caller is the mirror-image leak - the caller's token sent to a host the
     * owner chose - so that is refused as well: the caller resolves to their OWN default connection.
     *
     * <p>Desktop has a null owner and a null caller, so nothing is ever foreign there.
     */
    public Endpoint resolve(RegisteredConnector connector, String userId) {
        if (connector == null) return Endpoint.EMPTY;
        if (isForeign(connector, userId)) {
            log.debug("[ConnectorEndpoint] connector={} belongs to another user; using the caller's own default connection",
                    connector.getId());
            return forType(connector.getConnectorType(), userId);
        }
        return resolve(connector);
    }

    /**
     * Whether the deployment's list of allowed file servers lets a network share reach
     * {@code host} - the one rule ConnectorConnectionService applies when a connection is saved,
     * asked again at sync so a narrowed list stops registered sources too. Not a second copy of it:
     * a security ceiling kept twice is two things to keep in step.
     */
    public boolean fileServerAllowedByPolicy(String host) {
        return connections.fileServerAllowedByPolicy(host);
    }

    /**
     * The caller's default connection for a source of {@code type}, or EMPTY: the default of the
     * service it reads through, else another connection that serves it (an Atlassian Cloud site
     * made as Jira, for a Confluence space) - ConnectorConnectionService.defaultServing.
     */
    public Endpoint forType(ConnectorType type, String userId) {
        return defaultConnection(type, userId).map(Endpoint::of).orElse(Endpoint.EMPTY);
    }

    /**
     * The connection a source of {@code type} reads through when none is named: the caller's default
     * of the service the type reads through, else the first of their other connections that serves it
     * (ConnectorCatalog.serves) - an Atlassian Cloud connection made as Jira, for a Confluence space
     * on the same site. Registration asks this too, so a source added with no connection chosen and a
     * tool call naming none land on the same one.
     */
    public Optional<ResolvedConnection> defaultConnection(ConnectorType type, String userId) {
        ConnectionProtocol own = catalog.protocolFor(type);
        if (own == null) return Optional.empty();
        Optional<ResolvedConnection> mine = connections.defaultFor(userId, own);
        if (mine.isPresent()) return mine;
        return servingFromOtherServices(type, userId).stream().findFirst();
    }

    /** One of the caller's own connections by id, or EMPTY if it is not theirs or cannot serve {@code type}. */
    public Endpoint byId(ConnectorType type, UUID connectionId, String userId) {
        return connections.resolveOwned(userId, connectionId)
                .filter(c -> catalog.serves(c.protocol(), c.authKind(), type))
                .map(Endpoint::of)
                .orElse(Endpoint.EMPTY);
    }

    /** All of the caller's connections a source of {@code type} can read through, its own service's default first. */
    public List<ResolvedConnection> connections(ConnectorType type, String userId) {
        ConnectionProtocol own = catalog.protocolFor(type);
        if (own == null) return List.of();
        List<ResolvedConnection> out = new ArrayList<>(connections.resolveAll(userId, own));
        out.addAll(servingFromOtherServices(type, userId));
        return out;
    }

    // The caller's connections of OTHER services that serve a source of this type, each service's
    // default first - only where its descriptor lists the type and the connection's sign-in reaches
    // it, so a Data Center Jira token is never offered for Confluence.
    private List<ResolvedConnection> servingFromOtherServices(ConnectorType type, String userId) {
        ConnectionProtocol own = catalog.protocolFor(type);
        List<ResolvedConnection> out = new ArrayList<>();
        for (ConnectionProtocol p : catalog.servingProtocols(type)) {
            if (p == own) continue;
            for (ResolvedConnection c : connections.resolveAll(userId, p)) {
                if (catalog.serves(c.protocol(), c.authKind(), type)) out.add(c);
            }
        }
        return out;
    }

    /**
     * The endpoint for the source of {@code type} a tool call NAMED, else the caller's default.
     *
     * <p>A fallback rather than an error, deliberately: the key a tool receives is a project or space
     * key in the REMOTE system, and a user may ask about one that is not registered here at all.
     * Matching is on the connector's name, which is what registration stores the key as.
     */
    public Endpoint forSource(ConnectorType type, String sourceKey, String userId) {
        if (sourceKey == null || sourceKey.isBlank()) return forType(type, userId);
        return findVisibleSource(type, sourceKey, userId)
                .map(connector -> resolve(connector, userId))
                .orElseGet(() -> forType(type, userId));
    }

    /**
     * The source of {@code type} named {@code sourceKey} that {@code userId} may use - their OWN
     * first, so another user's registration of the same key can never displace theirs, and only then
     * a shared one. Case-insensitive: remote keys are upper-case and a model repeating one out of
     * prose often is not.
     */
    //
    // ONE MATCH OR NONE, from lists. A name is not unique: one user may register the same project
    // key on two servers (findExistingOnSameServer allows it), and two users the same key each. The
    // own lookup was an Optional finder and threw on the first case - every tool call naming that
    // key failed on Enterprise Server. An ambiguous name is exactly where guessing would be wrong,
    // so it falls back to the caller's own default connection, as the shared lookup always did.
    private Optional<RegisteredConnector> findVisibleSource(ConnectorType type, String sourceKey, String userId) {
        if (userId != null) {
            List<RegisteredConnector> own = connectorRepository
                    .findAllByUserIdAndConnectorTypeAndNameIgnoreCase(userId, type, sourceKey);
            if (own.size() == 1) return Optional.of(own.get(0));
            if (own.size() > 1) return ambiguous(type, sourceKey);
        }
        List<RegisteredConnector> visible = connectorRepository.findAllByConnectorTypeAndNameIgnoreCase(type, sourceKey)
                .stream().filter(c -> c.isShared() || c.getUserId() == null).toList();
        if (visible.size() > 1) return ambiguous(type, sourceKey);
        return visible.stream().findFirst();
    }

    private static Optional<RegisteredConnector> ambiguous(ConnectorType type, String sourceKey) {
        log.debug("[ConnectorEndpoint] more than one {} source is named '{}'; using the caller's own default",
                type, sourceKey);
        return Optional.empty();
    }

    /** Whether this source belongs to somebody other than the caller. Never true on desktop. */
    private static boolean isForeign(RegisteredConnector connector, String userId) {
        String owner = connector.getUserId();
        return owner != null && !owner.equals(userId);
    }
}
