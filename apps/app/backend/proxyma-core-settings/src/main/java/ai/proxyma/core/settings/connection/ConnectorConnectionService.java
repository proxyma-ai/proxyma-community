package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.connector.networkshare.FileServerHost;
import ai.proxyma.core.connector.networkshare.HostAllowlist;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.common.net.OutboundAddressPolicy;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.AuthMethod;
import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.spi.ConfigValidator;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.settings.service.CredentialEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD for a user's connections, and the one place a stored secret becomes a usable one.
 *
 * <p>The rules it keeps, each for the reason given where it is enforced:
 * <ul>
 *   <li><b>Every read and write is scoped to an owner.</b> A caller names a connection by id and
 *       gets it only if it is theirs; there is no admin path to anybody else's token.
 *   <li><b>The plaintext secret leaves only inside a {@link ResolvedConnection}.</b>
 *   <li><b>A blank secret on update means "keep", never "clear"</b> - the client is never sent the
 *       secret, so it cannot send it back. Clearing is an explicit flag, as for provider keys.
 *   <li><b>Exactly one default per owner and protocol</b> while any exist.
 * </ul>
 */
@Service
public class ConnectorConnectionService implements ai.proxyma.core.connector.networkshare.FileServerPolicy {

    private static final Logger log = LoggerFactory.getLogger(ConnectorConnectionService.class);

    private static final int MAX_NAME_CHARS = 100;
    private static final int MAX_DETAIL_CHARS = 500;

    private final ConnectorConnectionRepository repository;
    private final CredentialEncryptionService encryption;
    private final ApplicationEventPublisher events;
    private final FeatureGateService featureGate;
    private final ObjectProvider<ConnectionUsage> usage;
    private final boolean multiUser;

    public ConnectorConnectionService(ConnectorConnectionRepository repository,
                                      CredentialEncryptionService encryption,
                                      ApplicationEventPublisher events,
                                      FeatureGateService featureGate,
                                      ObjectProvider<ConnectionUsage> usage,
                                      @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.repository = repository;
        this.encryption = encryption;
        this.events = events;
        this.featureGate = featureGate;
        this.usage = usage;
        this.multiUser = multiUser;
    }

    /**
     * Everything a caller may set. Null means "unchanged" on update. {@code secret} is the token or
     * password; an OAuth connection's secret is written only by its sign-in, never through here.
     */
    public record ConnectionInput(String name, ConnectionProtocol protocol, String baseUrl,
            String username, String secret, boolean clearSecret, Boolean isDefault,
            Boolean allowRead, Boolean allowWrite, McpInput mcp, AuthKind authMethod, Map<String, String> fields) {

        /** Every field but the sign-in method and its fields: a service's first method, one secret. */
        public ConnectionInput(String name, ConnectionProtocol protocol, String baseUrl, String username,
                               String secret, boolean clearSecret, Boolean isDefault, Boolean allowRead,
                               Boolean allowWrite, McpInput mcp) {
            this(name, protocol, baseUrl, username, secret, clearSecret, isDefault, allowRead, allowWrite, mcp, null, null);
        }

        /** Every field but {@code mcp}, for every protocol but an MCP server. */
        public ConnectionInput(String name, ConnectionProtocol protocol, String baseUrl, String username,
                               String secret, boolean clearSecret, Boolean isDefault, Boolean allowRead,
                               Boolean allowWrite) {
            this(name, protocol, baseUrl, username, secret, clearSecret, isDefault, allowRead, allowWrite, null);
        }

        /** A field the sign-in method names, from {@code fields}; null when it was not sent. */
        String field(String key) {
            return fields == null ? null : fields.get(key);
        }
    }

    /**
     * An MCP server's settings as the form sends them. {@code headers} and {@code env} map a NAME to
     * a value; a blank value keeps the stored one (the client never has it), a name left out is
     * removed. {@code secret} on the input above is the bearer token. {@code oauthClientId} and
     * {@code oauthClientSecret} are for a server that offers no dynamic client registration.
     */
    public record McpInput(McpServerConfig.Transport transport, String command, List<String> args,
                           McpServerConfig.Auth auth, Map<String, String> headers, Map<String, String> env,
                           Boolean shared, String oauthClientId, String oauthClientSecret, String oauthScope) {}

    public static class InvalidConnectionException extends RuntimeException {
        public InvalidConnectionException(String message) {
            super(message);
        }
    }

    /** The owner key a user id files under. Desktop and Home Server have no user: {@link ConnectorConnection#LOCAL_OWNER}. */
    public static String ownerKey(String userId) {
        return userId == null || userId.isBlank() ? ConnectorConnection.LOCAL_OWNER : userId.trim();
    }

    /**
     * The protocols this installation offers. Microsoft 365 is Enterprise Server only, as its sources
     * always were: it needs the deployment's own Entra app, which only that edition configures.
     */
    public List<ConnectionProtocol> offeredProtocols() {
        return catalog.visibleProtocols(multiUser).stream().map(ProtocolDescriptor::protocol).toList();
    }

    /** The descriptors of the protocols this installation offers, for the Add-connection form. */
    public List<ProtocolDescriptor> offeredProtocolDescriptors() {
        return catalog.visibleProtocols(multiUser);
    }

    /** What a protocol is - its descriptor, from the catalog. */
    public ProtocolDescriptor describe(ConnectionProtocol protocol) {
        ProtocolDescriptor d = catalog.protocol(protocol);
        if (d == null) throw new InvalidConnectionException(protocol + " is not a known type of connection.");
        return d;
    }

    // The catalog every protocol question is asked of. Setter-injected, so the constructor every
    // test builds this with is unchanged; a test gets the descriptors of every built-in service.
    private ConnectorCatalog catalog = ConnectorCatalog.descriptorsOnly();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCatalog(ConnectorCatalog catalog) {
        this.catalog = catalog;
    }

    // - reads -

    @Transactional(readOnly = true)
    public List<ConnectorConnectionView> list(String userId) {
        return repository.findByOwnerIdOrderByNameAsc(ownerKey(userId)).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ConnectorConnectionView get(String userId, UUID id) {
        return toView(requireOwned(userId, id));
    }

    /**
     * A connection by id with no owner check - for SYNC, which runs as the source's owner and has
     * already established that the source is theirs. Everything a person triggers goes through
     * {@link #resolveOwned}.
     */
    @Transactional(readOnly = true)
    public Optional<ResolvedConnection> resolve(UUID id) {
        if (id == null) return Optional.empty();
        return repository.findById(id).map(this::toResolved);
    }

    /** The caller's own connection by id; empty if it does not exist or is somebody else's. */
    @Transactional(readOnly = true)
    public Optional<ResolvedConnection> resolveOwned(String userId, UUID id) {
        if (id == null) return Optional.empty();
        return repository.findByIdAndOwnerId(id, ownerKey(userId)).map(this::toResolved);
    }

    /** All of the caller's connections of one protocol, the default first. */
    @Transactional(readOnly = true)
    public List<ResolvedConnection> resolveAll(String userId, ConnectionProtocol protocol) {
        return repository.findByOwnerIdAndProtocolOrderByCreatedAtAsc(ownerKey(userId), protocol).stream()
                .sorted(Comparator.comparing((ConnectorConnection c) -> !c.isDefaultConnection()))
                .map(this::toResolved)
                .toList();
    }

    /** The caller's default connection of one protocol, if they have any. */
    @Transactional(readOnly = true)
    public Optional<ResolvedConnection> defaultFor(String userId, ConnectionProtocol protocol) {
        return resolveAll(userId, protocol).stream().findFirst();
    }

    // - writes -

    @Transactional
    public ConnectorConnectionView create(String userId, ConnectionInput input) {
        String owner = ownerKey(userId);
        if (input.protocol() == null) throw new InvalidConnectionException("A connection needs a type.");
        validate(owner, input, null, input.protocol());
        ConnectorConnection c = new ConnectorConnection();
        c.setOwnerId(owner);
        c.setProtocol(input.protocol());
        // A protocol with no write tools never stores Write on, even if a client sends it - a switch
        // with nothing behind it would read as a permission somebody granted.
        c.setAllowWrite(false);
        apply(c, input);
        boolean first = repository.findByOwnerIdAndProtocolOrderByCreatedAtAsc(owner, c.getProtocol()).isEmpty();
        c.setDefaultConnection(first || Boolean.TRUE.equals(input.isDefault()));
        repository.save(c);
        if (c.isDefaultConnection()) clearOtherDefaults(c);
        log.info("[Connections] created '{}' ({}) for {}", c.getName(), c.getProtocol(), owner);
        audit(c, "connection.added", "Connection added: " + c.getName(), input);
        publish(c.getId(), false);
        return toView(c);
    }

    @Transactional
    public ConnectorConnectionView update(String userId, UUID id, ConnectionInput input) {
        ConnectorConnection c = requireOwned(userId, id);
        // The protocol is fixed once created: every source reading through this connection was
        // registered against that service, and re-typing it would point them at the wrong API.
        if (input.protocol() != null && input.protocol() != c.getProtocol()) {
            throw new InvalidConnectionException("A connection's type cannot be changed. Add a new connection instead.");
        }
        validate(c.getOwnerId(), input, id, c.getProtocol());
        apply(c, input);
        if (Boolean.TRUE.equals(input.isDefault()) && !c.isDefaultConnection()) {
            c.setDefaultConnection(true);
            clearOtherDefaults(c);
        }
        // Unsetting the default is not a thing a user does directly - they make another one the
        // default. Ignoring `false` keeps "exactly one default" true without a second rule.
        repository.save(c);
        log.info("[Connections] updated '{}' ({})", c.getName(), c.getProtocol());
        audit(c, "connection.changed", "Connection changed: " + c.getName(), input);
        publish(c.getId(), false);
        return toView(c);
    }

    @Transactional
    public void delete(String userId, UUID id) {
        ConnectorConnection c = requireOwned(userId, id);
        ConnectionUsage counter = usage.getIfAvailable();
        long using = counter == null ? 0 : counter.sourcesUsing(id);
        // Two whole sentences rather than one assembled from "source"/"sources" and "it"/"them":
        // the page translates a refusal as a sentence (tools/i18n/server-messages.mjs), and a
        // word chosen by a ternary reaches it as a value it cannot translate.
        if (using == 1) {
            throw new InvalidConnectionException("'" + c.getName()
                    + "' is used by 1 source. Remove it from Sources first.");
        }
        if (using > 1) {
            throw new InvalidConnectionException("'" + c.getName() + "' is used by " + using
                    + " sources. Remove them from Sources first.");
        }
        repository.delete(c);
        repository.flush();
        if (c.isDefaultConnection()) {
            // Another connection of the same protocol inherits the default, oldest first, so tools
            // that named none keep working rather than suddenly finding no server at all.
            repository.findByOwnerIdAndProtocolOrderByCreatedAtAsc(c.getOwnerId(), c.getProtocol()).stream()
                    .findFirst()
                    .ifPresent(next -> {
                        next.setDefaultConnection(true);
                        repository.save(next);
                    });
        }
        log.info("[Connections] deleted '{}' ({})", c.getName(), c.getProtocol());
        audit(c, "connection.removed", "Connection removed: " + c.getName(), null);
        publish(id, true);
    }

    /** Account deletion: every connection the user had, with no usage check - their sources go too. */
    @Transactional
    public void deleteAllOwnedBy(String userId) {
        if (userId == null || userId.isBlank()) return;
        repository.deleteByOwnerId(ownerKey(userId));
    }

    // - OAuth bookkeeping. A Microsoft 365 grant is written by ConnectionGrantStore alone; the
    // recordSignIn/recordRefresh/clearSignIn that sat here had no callers and were a second writer
    // of the same row waiting to disagree with it (settings-connections-usage-01). -

    /** The service refused the stored grant outright. Recorded so it is said once, not retried forever. */
    @Transactional
    public void markNeedsReconnect(UUID id, String detail) {
        repository.findById(id).ifPresent(c -> {
            c.setStatus(ConnectionStatus.NEEDS_RECONNECT);
            c.setStatusDetail(trim(detail, MAX_DETAIL_CHARS));
            repository.save(c);
            publish(id, false);
        });
    }

    // - internals -

    // The audit log's entry for a write. Never the secret - only whether this write replaced or
    // cleared it. The owner is the actor: every write here is the owner's own, and a sign-in
    // callback has no other.
    private void audit(ConnectorConnection c, String action, String summary, ConnectionInput input) {
        String owner = ConnectorConnection.LOCAL_OWNER.equals(c.getOwnerId()) ? null : c.getOwnerId();
        AuditEntry.Builder entry = AuditEntry.of(AuditCategory.SOURCES, action)
                .user(owner)
                .target(c.getName())
                .summary(summary)
                .detail("service", c.getProtocol() == null ? null : catalog.displayName(c.getProtocol()))
                .detail("server", c.getBaseUrl());
        if (input != null) {
            entry.detail("read", c.isAllowRead())
                    .detail("write", c.isAllowWrite())
                    .detail("default", c.isDefaultConnection())
                    .detail("secret", input.clearSecret() ? "cleared"
                            : input.secret() == null || input.secret().isBlank() ? null
                            : "connection.added".equals(action) ? "set" : "replaced");
        }
        entry.record();
    }

    private ConnectorConnection require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NoSuchElementException("No such connection: " + id));
    }

    // NoSuchElement for somebody else's connection as much as for a missing one: a 404 that says
    // "not yours" would confirm the id exists.
    private ConnectorConnection requireOwned(String userId, UUID id) {
        return repository.findByIdAndOwnerId(id, ownerKey(userId))
                .orElseThrow(() -> new NoSuchElementException("No such connection: " + id));
    }

    private void validate(String owner, ConnectionInput input, UUID selfId, ConnectionProtocol protocol) {
        if (input.name() == null || input.name().isBlank()) {
            throw new InvalidConnectionException("A connection needs a name.");
        }
        if (input.name().trim().length() > MAX_NAME_CHARS) {
            throw new InvalidConnectionException("A connection name can be at most " + MAX_NAME_CHARS + " characters.");
        }
        ProtocolDescriptor descriptor = describe(protocol);
        if (!offeredProtocols().contains(protocol)) {
            throw new InvalidConnectionException(descriptor.displayName() + " is not available in this edition.");
        }
        if (protocol == ConnectionProtocol.MCP_SERVER) {
            // Every tier (maintainer's decision, 2026-10-13): an MCP server backs no source, so the
            // per-source license gate below has nothing to ask about.
            requireUniqueName(owner, input, selfId);
            validateMcp(input, selfId);
            return;
        }
        // The license is the one rule above the user, as it is for sources and tools
        // (McpGuardService) - since the two tiers, Microsoft 365 without Enterprise is the only
        // service it leaves out. A token saved for it would unlock nothing; refusing it here says so
        // at the moment someone tries.
        if (!featureGate.getFeatures().isConnectorAllowed(descriptor.edition())) {
            throw new InvalidConnectionException(descriptor.displayName() + " needs an Enterprise license.");
        }
        requireUniqueName(owner, input, selfId);
        if (protocol == ConnectionProtocol.NETWORK_SHARE) {
            String raw = input.baseUrl() == null ? null : input.baseUrl().trim();
            if (selfId == null ? raw == null || raw.isEmpty() : raw != null && raw.isEmpty()) {
                throw new InvalidConnectionException("A network share connection needs its file server's name.");
            }
            if (raw != null && !raw.isEmpty()) requireAllowedFileServer(raw);
        } else if (descriptor.hasBaseUrl()) {
            String url = input.baseUrl() == null ? null : input.baseUrl().trim();
            // Required on create; on update null means unchanged, but blank is an attempt to clear it.
            // A service with a default address (a vendor's public API) takes it when none is typed.
            boolean required = descriptor.baseUrl().required() && descriptor.defaultBaseUrl() == null;
            if (selfId == null ? required && (url == null || url.isEmpty()) : url != null && url.isEmpty() && required) {
                throw new InvalidConnectionException(descriptor.displayName() + " needs a server URL.");
            }
            if (url != null && !url.isEmpty()) requireHttpUrl(url);
        }
        AuthMethod method = descriptor.auth(input.authMethod());
        if (input.authMethod() != null && !descriptor.offers(input.authMethod())) {
            throw new InvalidConnectionException(descriptor.displayName() + " does not sign in that way.");
        }
        String username = input.field(AuthMethod.USERNAME) != null ? input.field(AuthMethod.USERNAME) : input.username();
        if (method.hasUsername() && selfId == null && (username == null || username.isBlank())) {
            throw new InvalidConnectionException(descriptor.displayName() + " needs a username.");
        }
        // The method's settings, checked as the dialog draws them (an edit keeps what it leaves out).
        try {
            ConfigValidator.validate(method.settingFields(), settingsGiven(input, method),
                    selfId == null ? null : storedSettingsOf(selfId));
        } catch (IllegalArgumentException e) {
            throw new InvalidConnectionException(e.getMessage());
        }
    }

    private void requireUniqueName(String owner, ConnectionInput input, UUID selfId) {
        repository.findByOwnerIdAndNameIgnoreCase(owner, input.name().trim())
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new InvalidConnectionException("You already have a connection called '" + existing.getName() + "'.");
                });
    }

    private static final int MAX_MCP_ARGS = 64;
    private static final int MAX_MCP_NAMED_VALUES = 32;

    /**
     * An MCP server's settings. Streamable HTTP and SSE need an http(s) URL the outbound policy
     * allows, checked as every other server URL is; a local command needs a command. On update, null
     * keeps what was saved. WHO may run a local command on Enterprise Server is the controller's
     * question (a Super admin): the caller's role decides it, not the input.
     */
    private void validateMcp(ConnectionInput input, UUID selfId) {
        McpInput mcp = input.mcp();
        if (selfId == null && mcp == null) {
            throw new InvalidConnectionException("An MCP server connection needs its transport.");
        }
        McpServerConfig.Transport stored = selfId == null ? null : storedMcp(selfId).transport();
        McpServerConfig.Transport transport = mcp != null && mcp.transport() != null ? mcp.transport()
                : stored != null ? stored : McpServerConfig.Transport.STREAMABLE_HTTP;
        boolean switched = stored != null && transport != stored;
        if (transport == McpServerConfig.Transport.STDIO) {
            String command = mcp == null ? null : mcp.command();
            boolean required = selfId == null || switched;
            if ((required || command != null) && (command == null || command.isBlank())) {
                throw new InvalidConnectionException("A local MCP server needs the command that starts it.");
            }
        } else {
            String url = input.baseUrl() == null ? null : input.baseUrl().trim();
            boolean required = selfId == null || (switched && stored == McpServerConfig.Transport.STDIO);
            if (required ? url == null || url.isEmpty() : url != null && url.isEmpty()) {
                throw new InvalidConnectionException("An MCP server needs its URL.");
            }
            if (url != null && !url.isEmpty()) requireHttpUrl(url);
        }
        if (mcp == null) return;
        if (mcp.args() != null && mcp.args().size() > MAX_MCP_ARGS) {
            throw new InvalidConnectionException("A local MCP server can have at most " + MAX_MCP_ARGS + " arguments.");
        }
        if ((mcp.headers() != null && mcp.headers().size() > MAX_MCP_NAMED_VALUES)
                || (mcp.env() != null && mcp.env().size() > MAX_MCP_NAMED_VALUES)) {
            throw new InvalidConnectionException("An MCP server can have at most " + MAX_MCP_NAMED_VALUES
                    + " headers and " + MAX_MCP_NAMED_VALUES + " environment variables.");
        }
        if (mcp.headers() != null) {
            for (String name : mcp.headers().keySet()) {
                if (name == null || !name.trim().matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,100}")) {
                    throw new InvalidConnectionException("'" + name + "' is not a valid header name.");
                }
            }
        }
        if (mcp.env() != null) {
            for (String name : mcp.env().keySet()) {
                if (name == null || !name.trim().matches("[A-Za-z_][A-Za-z0-9_]{0,100}")) {
                    throw new InvalidConnectionException("'" + name + "' is not a valid environment variable name.");
                }
            }
        }
    }

    private McpServerConfig storedMcp(UUID id) {
        return repository.findById(id).map(c -> McpServerConfig.parse(c.getMcpConfig()))
                .orElse(McpServerConfig.parse(null));
    }

    /**
     * A file server a Network share connection may name: one host (FileServerHost), not refused by
     * the sandbox's outbound policy, and - on Enterprise Server, when an administrator has listed
     * any - one of the deployment's allowed file servers. That list is a CEILING since 2026-10-01:
     * blank means a user may name any server, because each connection is its owner's own account
     * sent to a server its owner typed. The admin list is what an organization uses to say which
     * servers may receive it at all. Desktop and Home Server have one user, so no ceiling.
     */
    private void requireAllowedFileServer(String raw) {
        String host;
        try {
            host = FileServerHost.normalize(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidConnectionException(e.getMessage());
        }
        try {
            OutboundAddressPolicy.checkHost(host);
        } catch (OutboundAddressPolicy.BlockedDestinationException e) {
            throw new InvalidConnectionException(e.getMessage());
        }
        if (!fileServerAllowedByPolicy(host)) {
            throw new InvalidConnectionException("File server '" + host
                    + "' is not one of this deployment's allowed file servers. Ask an administrator to add it.");
        }
    }

    /**
     * The same ceiling, asked by every Network share session as it opens (NetworkShareConnector) -
     * so a narrowed list stops a source that is already running, not only the next connection.
     */
    @Override
    public boolean allows(String host) {
        return fileServerAllowedByPolicy(host);
    }

    /** Whether the deployment's optional list of file servers lets a connection name {@code host}. */
    public boolean fileServerAllowedByPolicy(String host) {
        if (!multiUser) return true;
        if (settingsService == null) {
            // Only a test builds this without the settings bean. Said, because a ceiling that
            // silently passes everything is the shape this check must never have on a real server.
            log.warn("[Connections] no settings service - the file-server list is not being applied");
            return true;
        }
        String list = settingsService.getNetworkShareAllowedHosts();
        return list == null || list.isBlank() || HostAllowlist.parse(list).isAllowed(host);
    }

    // Setter-injected, so the constructor every test builds this with is unchanged; null means no
    // ceiling, which is right for a test and for the editions that have none.
    private SettingsService settingsService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSettingsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // http and https only: an svn:// or file: URL is not something any connector here speaks, and
    // a bare host with no scheme is the typo this catches most often.
    private static void requireHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new InvalidConnectionException("The server URL must start with http:// or https://.");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidConnectionException("The server URL is not a valid URL.");
        }
        // Outside the try: the refusal is an IllegalArgumentException too, and the catch above
        // would turn "a private address" into "not a valid URL". Off except on the public sandbox.
        try {
            OutboundAddressPolicy.check(url);
        } catch (OutboundAddressPolicy.BlockedDestinationException e) {
            throw new InvalidConnectionException(e.getMessage());
        }
    }

    private void apply(ConnectorConnection c, ConnectionInput input) {
        c.setName(input.name().trim());
        ConnectionProtocol p = c.getProtocol();
        if (p == ConnectionProtocol.MCP_SERVER) {
            applyMcp(c, input);
            return;
        }
        ProtocolDescriptor d = describe(p);
        if (p == ConnectionProtocol.NETWORK_SHARE) {
            if (input.baseUrl() != null) c.setBaseUrl(FileServerHost.normalize(input.baseUrl()));
        } else if (d.hasBaseUrl()) {
            if (input.baseUrl() != null && !input.baseUrl().isBlank()) {
                c.setBaseUrl(input.baseUrl().trim().replaceAll("/+$", ""));
            } else if (c.getBaseUrl() == null && d.defaultBaseUrl() != null) {
                c.setBaseUrl(d.defaultBaseUrl());
            }
        } else {
            c.setBaseUrl(null);
        }
        // Which way it signs in: the one the input names, else the one it was saved with.
        AuthMethod method = d.auth(input.authMethod() != null ? input.authMethod() : storedKind(c));
        if (input.authMethod() != null) c.setAuthMethod(method.kind().name());
        if (method.hasUsername()) {
            String username = input.field(AuthMethod.USERNAME) != null ? input.field(AuthMethod.USERNAME) : input.username();
            if (username != null) c.setUsername(blankToNull(username));
        } else {
            c.setUsername(null);
        }
        applySecrets(c, input, method);
        // The non-secret settings, stored as JSON beside the secret - never inside it.
        if (!method.settingFields().isEmpty()) {
            Map<String, String> settings = ConfigValidator.validate(method.settingFields(), settingsGiven(input, method),
                    c.getId() == null ? null : ConfigJson.parse(c.getSettingsJson()));
            c.setSettingsJson(ConfigJson.write(settings));
        }
        if (input.allowRead() != null) c.setAllowRead(input.allowRead());
        if (input.allowWrite() != null) c.setAllowWrite(d.supportsWrite() && input.allowWrite());
    }

    /**
     * The secret fields of the connection's sign-in method, encrypted on the row.
     *
     * <p>ONE secret field (a token, a password) is stored as it always was - the encrypted value
     * alone - so every connection saved before multi-field secrets reads back unchanged. Several (a
     * key pair, an OAuth client secret beside the grant a sign-in stores) are ONE encrypted JSON
     * object. Either way a field sent blank keeps what is stored, since the page is never sent a
     * secret and so cannot send it back; {@code clearSecret} removes them.
     *
     * <p>A sign-in's grant (its access and refresh tokens) is written by OAuth2ConnectionService and
     * by a service's own sign-in flow (Microsoft 365's), never through here; an edit that saves a
     * client secret keeps it.
     */
    private void applySecrets(ConnectorConnection c, ConnectionInput input, AuthMethod method) {
        List<ai.proxyma.core.connector.spi.FieldSpec> fields = method.secretFields();
        boolean signsIn = ConnectionAuthKind.of(method.kind()) == ConnectionAuthKind.OAUTH;
        if (signsIn && fields.isEmpty()) return; // a service with a sign-in of its own owns the secret
        if (!signsIn && fields.size() <= 1) {
            String key = fields.isEmpty() ? null : fields.get(0).key();
            String value = key != null && input.field(key) != null ? input.field(key) : input.secret();
            if (input.clearSecret()) {
                c.setSecret(null);
            } else if (value != null && !value.isBlank()) {
                c.setSecret(encryption.encrypt(value.trim()));
            }
            return;
        }
        Map<String, String> stored = new java.util.LinkedHashMap<>(ConfigJson.parse(decryptQuietly(c.getSecret())));
        for (int i = 0; i < fields.size(); i++) {
            String key = fields.get(i).key();
            String value = input.field(key) != null ? input.field(key) : i == 0 ? input.secret() : null;
            if (input.clearSecret()) stored.remove(key);
            else if (value != null && !value.isBlank()) stored.put(key, value.trim());
        }
        c.setSecret(stored.isEmpty() ? null : encryption.encrypt(ConfigJson.write(stored)));
    }

    // The settings fields the input carries - from its fields, where the method names them.
    private static Map<String, Object> settingsGiven(ConnectionInput input, AuthMethod method) {
        Map<String, Object> given = new java.util.LinkedHashMap<>();
        for (ai.proxyma.core.connector.spi.FieldSpec f : method.settingFields()) {
            String v = input.field(f.key());
            if (v != null) given.put(f.key(), v);
        }
        return given;
    }

    private Map<String, String> storedSettingsOf(UUID id) {
        return repository.findById(id).map(c -> ConfigJson.parse(c.getSettingsJson())).orElse(Map.of());
    }

    // The sign-in method a row was saved with; null for the service's first.
    private static AuthKind storedKind(ConnectorConnection c) {
        if (c.getAuthMethod() == null) return null;
        try {
            return AuthKind.valueOf(c.getAuthMethod());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // An MCP server's settings and secrets. A change of transport or URL forgets an OAuth grant: it
    // was issued for the server it was signed in to, and sending it to another would hand a token
    // to a server it was never meant for.
    private void applyMcp(ConnectorConnection c, ConnectionInput input) {
        McpServerConfig before = McpServerConfig.parse(c.getMcpConfig());
        McpServerConfig.McpSecrets stored = McpServerConfig.McpSecrets.parse(decryptQuietly(c.getSecret()));
        McpInput mcp = input.mcp();
        McpServerConfig.Transport transport = mcp != null && mcp.transport() != null ? mcp.transport() : before.transport();
        String url = transport == McpServerConfig.Transport.STDIO ? null
                : input.baseUrl() != null ? input.baseUrl().trim() : c.getBaseUrl();
        boolean moved = c.getId() != null
                && (transport != before.transport() || !Objects.equals(url, c.getBaseUrl()));
        c.setBaseUrl(url);
        c.setUsername(null);

        boolean stdio = transport == McpServerConfig.Transport.STDIO;
        String command = stdio ? (mcp != null && mcp.command() != null ? mcp.command().trim() : before.command()) : null;
        List<String> args = stdio
                ? (mcp != null && mcp.args() != null
                    ? mcp.args().stream().filter(a -> a != null && !a.isEmpty()).toList() : before.args())
                : List.of();
        McpServerConfig.Auth auth = stdio ? McpServerConfig.Auth.NONE
                : mcp != null && mcp.auth() != null ? mcp.auth() : before.auth();

        Map<String, String> headers = auth == McpServerConfig.Auth.HEADERS
                ? McpServerConfig.McpSecrets.merge(stored.headers(), mcp == null ? null : mcp.headers()) : Map.of();
        Map<String, String> env = stdio
                ? McpServerConfig.McpSecrets.merge(stored.env(), mcp == null ? null : mcp.env()) : Map.of();
        String bearer = auth != McpServerConfig.Auth.BEARER ? null
                : input.clearSecret() ? null
                : input.secret() != null && !input.secret().isBlank() ? input.secret().trim() : stored.bearerToken();

        McpServerConfig.OAuthClient oauth = null;
        String clientSecret = null;
        String refresh = null;
        if (auth == McpServerConfig.Auth.OAUTH) {
            McpServerConfig.OAuthClient prior = moved ? null : before.oauth();
            String typedClient = mcp == null || mcp.oauthClientId() == null ? null : mcp.oauthClientId().trim();
            String scope = mcp != null && mcp.oauthScope() != null ? blankToNull(mcp.oauthScope())
                    : prior == null ? null : prior.scope();
            if (typedClient != null && !typedClient.isEmpty()
                    && (prior == null || !typedClient.equals(prior.clientId()))) {
                // A client typed by hand replaces whatever was registered; the grant goes with it.
                oauth = new McpServerConfig.OAuthClient(typedClient, null, null, null, scope, false);
                clientSecret = mcp.oauthClientSecret() == null || mcp.oauthClientSecret().isBlank()
                        ? null : mcp.oauthClientSecret().trim();
            } else if (prior != null) {
                oauth = new McpServerConfig.OAuthClient(prior.clientId(), prior.authorizationEndpoint(),
                        prior.tokenEndpoint(), prior.resource(), scope, prior.registered());
                clientSecret = mcp != null && mcp.oauthClientSecret() != null && !mcp.oauthClientSecret().isBlank()
                        ? mcp.oauthClientSecret().trim() : stored.clientSecret();
                refresh = stored.refreshToken();
            } else if (scope != null) {
                oauth = new McpServerConfig.OAuthClient(null, null, null, null, scope, null);
            }
        }
        if (refresh == null) {
            // No grant (any more): nothing is signed in, whatever the row said before.
            c.setStatus(null);
            c.setStatusDetail(null);
            c.setAccountLabel(null);
            c.setConnectedAt(null);
        }

        McpServerConfig config = new McpServerConfig(transport, command, args, auth,
                List.copyOf(headers.keySet()), List.copyOf(env.keySet()), oauth);
        c.setMcpConfig(config.toJson());
        McpServerConfig.McpSecrets secrets = new McpServerConfig.McpSecrets(bearer, headers, env, clientSecret, refresh);
        String json = secrets.toJson();
        c.setSecret("{}".equals(json) ? null : encryption.encrypt(json));
        if (mcp != null && mcp.shared() != null) c.setShared(multiUser && mcp.shared());
        if (input.allowRead() != null) c.setAllowRead(input.allowRead());
        c.setAllowWrite(false);
    }

    // - MCP servers, for the client manager -

    /** The MCP servers a person's agent may use, decrypted - their own, and those shared with everyone. */
    @Transactional(readOnly = true)
    public List<ResolvedMcpServer> mcpServersUsableBy(String userId) {
        return repository.findMcpServersUsableBy(ownerKey(userId)).stream().map(this::toResolvedMcp).toList();
    }

    /** One MCP server by id, with no owner check - for the client manager and the sign-in callback. */
    @Transactional(readOnly = true)
    public Optional<ResolvedMcpServer> resolveMcp(UUID id) {
        if (id == null) return Optional.empty();
        return repository.findById(id).filter(c -> c.getProtocol() == ConnectionProtocol.MCP_SERVER)
                .map(this::toResolvedMcp);
    }

    /** The caller's own MCP server by id - what testing it or starting its sign-in may name. */
    @Transactional(readOnly = true)
    public Optional<ResolvedMcpServer> resolveOwnedMcp(String userId, UUID id) {
        if (id == null) return Optional.empty();
        return repository.findByIdAndOwnerId(id, ownerKey(userId))
                .filter(c -> c.getProtocol() == ConnectionProtocol.MCP_SERVER)
                .map(this::toResolvedMcp);
    }

    /** The OAuth client a sign-in discovered or registered, kept for the next sign-in and every refresh. */
    @Transactional
    public void recordMcpOAuthClient(UUID id, McpServerConfig.OAuthClient client, String clientSecret) {
        repository.findById(id).ifPresent(c -> {
            McpServerConfig config = McpServerConfig.parse(c.getMcpConfig());
            c.setMcpConfig(config.withOAuth(client).toJson());
            if (clientSecret != null) {
                McpServerConfig.McpSecrets secrets = McpServerConfig.McpSecrets.parse(decryptQuietly(c.getSecret()));
                c.setSecret(encryption.encrypt(secrets.withRefresh(clientSecret, secrets.refreshToken()).toJson()));
            }
            repository.save(c);
        });
    }

    /** A completed MCP sign-in ({@code signIn}), or a refresh that may have rotated the refresh token. */
    @Transactional
    public void recordMcpGrant(UUID id, String refreshToken, Instant now, boolean signIn) {
        repository.findById(id).ifPresent(c -> {
            if (refreshToken != null && !refreshToken.isBlank()) {
                McpServerConfig.McpSecrets secrets = McpServerConfig.McpSecrets.parse(decryptQuietly(c.getSecret()));
                c.setSecret(encryption.encrypt(secrets.withRefresh(null, refreshToken).toJson()));
            }
            c.setStatus(ConnectionStatus.CONNECTED);
            c.setStatusDetail(null);
            c.setLastRefreshedAt(now);
            if (signIn) c.setConnectedAt(now);
            repository.save(c);
            if (signIn) {
                audit(c, "connection.signed-in", "Signed in to " + c.getName(), null);
                publish(id, false);
            }
        });
    }

    private ResolvedMcpServer toResolvedMcp(ConnectorConnection c) {
        return new ResolvedMcpServer(c.getId(), c.getOwnerId(), c.getName(), c.getBaseUrl(),
                McpServerConfig.parse(c.getMcpConfig()),
                McpServerConfig.McpSecrets.parse(decryptQuietly(c.getSecret())),
                c.isShared(), c.getStatus(),
                // Changes with every save of settings or secrets - not with a refresh, which only
                // replaces a token the running client already asks for afresh.
                Integer.toHexString(Objects.hash(c.getName(), c.getBaseUrl(), c.getMcpConfig(), c.getSecret() == null)));
    }

    private void clearOtherDefaults(ConnectorConnection keep) {
        for (ConnectorConnection other : repository.findByOwnerIdAndProtocolOrderByCreatedAtAsc(keep.getOwnerId(), keep.getProtocol())) {
            if (!other.getId().equals(keep.getId()) && other.isDefaultConnection()) {
                other.setDefaultConnection(false);
                repository.save(other);
            }
        }
    }

    private void publish(UUID id, boolean deleted) {
        events.publishEvent(new ConnectionChangedEvent(id, deleted));
    }

    /**
     * Whether the connection's service signs in through a flow of its own rather than the generic
     * OAuth2 one - Microsoft 365, with its tenant checks: an OAuth service whose plugin declares no
     * {@code OAuth2Spec}. Its grant is the bare secret (a refresh token), not the generic flow's JSON.
     */
    private boolean ownSignIn(ConnectorConnection c) {
        return catalog.connectionPlugin(c.getProtocol())
                .map(p -> p.oauth2(ConfigJson.parse(c.getSettingsJson()), c.getBaseUrl()))
                .isEmpty();
    }

    private ConnectorConnectionView toView(ConnectorConnection c) {
        // An OAuth connection's secret is a refresh token nobody typed; masking it would show a
        // fragment of a credential the user has never seen. The status says what matters.
        if (c.getProtocol() == ConnectionProtocol.MCP_SERVER) return toMcpView(c);
        ProtocolDescriptor d = describe(c.getProtocol());
        AuthMethod method = d.auth(storedKind(c));
        ConnectionAuthKind kind = ConnectionAuthKind.of(method.kind());
        boolean oauth = kind == ConnectionAuthKind.OAUTH;
        boolean multiSecret = oauth || method.secretFields().size() > 1;
        String plain = decryptQuietly(c.getSecret());
        // Masked only where the secret is ONE value a person typed; a JSON object of several, or a
        // sign-in's tokens nobody typed, is shown by which fields are set.
        String secret = multiSecret ? null : plain;
        Map<String, String> secretFields = multiSecret ? ConfigJson.parse(plain) : Map.of();
        List<String> configured = new java.util.ArrayList<>();
        for (ai.proxyma.core.connector.spi.FieldSpec f : method.secretFields()) {
            if (multiSecret ? secretFields.get(f.key()) != null : plain != null && !plain.isBlank()) configured.add(f.key());
        }
        boolean signedIn = oauth && (ownSignIn(c)
                ? c.getSecret() != null : secretFields.get(OAuth2ConnectionService.ACCESS_TOKEN) != null);
        return new ConnectorConnectionView(
                c.getId(), c.getName(), c.getProtocol(), d.displayName(),
                kind, c.getBaseUrl(), c.getUsername(),
                ConnectorConnectionView.mask(secret),
                oauth ? signedIn : multiSecret ? !configured.isEmpty() : secret != null && !secret.isBlank(),
                c.isDefaultConnection(), c.isAllowRead(), c.isAllowWrite(), d.supportsWrite(),
                c.getStatus(), c.getStatusDetail(), c.getAccountLabel(), c.getConnectedAt(), null,
                method.kind().name(), ConfigJson.parse(c.getSettingsJson()), List.copyOf(configured),
                catalog.sourceTypes(c.getProtocol(), method.kind()).stream().map(ConnectorType::wireName)
                        .distinct().toList());
    }

    // An MCP server as its owner reads it back: the bearer token masked like any token, header and
    // environment NAMES without their values, and whether an OAuth sign-in holds a grant.
    private ConnectorConnectionView toMcpView(ConnectorConnection c) {
        McpServerConfig config = McpServerConfig.parse(c.getMcpConfig());
        McpServerConfig.McpSecrets secrets = McpServerConfig.McpSecrets.parse(decryptQuietly(c.getSecret()));
        // Signed in once the callback has recorded a grant - with or without a refresh token, since some
        // servers issue none and their access token is kept in memory until it expires.
        boolean signedIn = c.getStatus() == ConnectionStatus.CONNECTED && c.getConnectedAt() != null;
        McpServerConfig.OAuthClient oauth = config.oauth();
        ConnectorConnectionView.McpView view = new ConnectorConnectionView.McpView(config.transport(),
                config.command(), config.args(), config.auth(), config.headerNames(), config.envNames(),
                c.isShared(), signedIn, oauth == null ? null : oauth.clientId(), oauth == null ? null : oauth.scope(),
                oauth != null && Boolean.TRUE.equals(oauth.registered()));
        return new ConnectorConnectionView(
                c.getId(), c.getName(), c.getProtocol(), catalog.displayName(c.getProtocol()),
                ConnectionAuthKind.TOKEN, c.getBaseUrl(), null,
                ConnectorConnectionView.mask(secrets.bearerToken()), secrets.bearerToken() != null,
                c.isDefaultConnection(), c.isAllowRead(), false, false,
                c.getStatus(), c.getStatusDetail(), c.getAccountLabel(), c.getConnectedAt(), view);
    }

    private ResolvedConnection toResolved(ConnectorConnection c) {
        String plain = decryptQuietly(c.getSecret());
        ProtocolDescriptor d = catalog.protocol(c.getProtocol());
        if (d == null || c.getProtocol() == ConnectionProtocol.MCP_SERVER) {
            return new ResolvedConnection(c.getId(), c.getOwnerId(), c.getName(), c.getProtocol(), c.getBaseUrl(),
                    c.getUsername(), plain, c.isDefaultConnection(), c.isAllowRead(),
                    c.isAllowWrite(), c.getStatus(), c.getStatusDetail(), c.getAccountJson());
        }
        AuthMethod method = d.auth(storedKind(c));
        boolean signsIn = ConnectionAuthKind.of(method.kind()) == ConnectionAuthKind.OAUTH;
        // The secret fields the method DECLARES and nothing else: a sign-in's refresh token is in
        // the same stored object, and no session is ever handed it - its access token comes from
        // OAuth2ConnectionService through the session's token supplier.
        Map<String, String> secrets = new java.util.LinkedHashMap<>();
        if (signsIn || method.secretFields().size() > 1) {
            Map<String, String> all = ConfigJson.parse(plain);
            for (ai.proxyma.core.connector.spi.FieldSpec f : method.secretFields()) {
                if (all.get(f.key()) != null) secrets.put(f.key(), all.get(f.key()));
            }
        } else if (plain != null && !plain.isBlank()) {
            secrets.put(method.secretFields().isEmpty() ? "token" : method.secretFields().get(0).key(), plain);
        }
        return new ResolvedConnection(c.getId(), c.getOwnerId(), c.getName(), c.getProtocol(), c.getBaseUrl(),
                c.getUsername(), plain, c.isDefaultConnection(), c.isAllowRead(),
                c.isAllowWrite(), c.getStatus(), c.getStatusDetail(), c.getAccountJson(),
                method.kind(), secrets, ConfigJson.parse(c.getSettingsJson()));
    }

    // A secret that will not decrypt is treated as absent, not fatal - see
    // CredentialEncryptionService.decryptOrNull.
    private String decryptQuietly(String stored) {
        return CredentialEncryptionService.decryptOrNull(encryption, stored, "a connection's secret");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
