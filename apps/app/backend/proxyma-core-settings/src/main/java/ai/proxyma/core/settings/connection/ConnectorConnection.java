package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * One place a user's sources are read from: a name, a protocol, a server and a credential - the
 * connector counterpart of a {@code ProviderInstance}.
 *
 * <p>It replaced three separate ways of saying the same thing, none of which had an id a source
 * could point at: the default server and token under each connector's settings prefix, the
 * "Other servers" JSON list beside it, and an override URL and token on each source. Sync read
 * two of the three and the tools read all three, so a source on a second server could index with
 * the first server's token.
 *
 * <p><b>Each connection belongs to one user</b> ({@code ownerId}), because the credential is that
 * person's: the remote server logs them and a write made through it is theirs. Desktop and Home
 * Server have no accounts and file everything under {@link #LOCAL_OWNER}. Nothing here is shared,
 * which is why a source somebody else owns lends the caller neither its server nor its token (see
 * ConnectorEndpointResolver).
 *
 * <p>Read and Write are per connection - the agent may write to a test Jira and only read the
 * production one.
 */
@Entity
@Table(name = "connector_connections",
        uniqueConstraints = @UniqueConstraint(name = "uk_connector_connections_owner_name",
                columnNames = {"owner_id", "name"}),
        indexes = @Index(name = "idx_connector_connections_owner", columnList = "owner_id"))
public class ConnectorConnection {

    /** The owner of every connection where there are no accounts - Desktop and Home Server. */
    public static final String LOCAL_OWNER = ai.proxyma.core.common.spend.SpendSubject.LOCAL_ID;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false, length = 100)
    private String ownerId;

    /** What the user calls it - "Work Jira", "Test Confluence". Unique per owner. */
    @Column(nullable = false, length = 100)
    private String name;

    /** STRING, not ordinal: rows outlive releases, and an ordinal re-points them when the enum is reordered. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ConnectionProtocol protocol;

    /** The server's root URL. Null for an OAuth service, whose address is fixed. */
    @Column(length = 2048)
    private String baseUrl;

    /** BASIC auth only. */
    @Column(length = 255)
    private String username;

    /**
     * The token, the password, or an OAuth refresh token - encrypted with CredentialEncryptionService
     * and never returned to a client.
     */
    @Column(length = 8000)
    private String secret;

    /** The one connection of its protocol a tool uses when nothing names another. Exactly one per owner and protocol. */
    @Column(nullable = false)
    private boolean defaultConnection;

    @Column(nullable = false)
    private boolean allowRead = true;

    @Column(nullable = false)
    private boolean allowWrite;

    /**
     * OAuth only: CONNECTED, NEEDS_RECONNECT, or null before the first sign-in. See
     * {@link ConnectionStatus}.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ConnectionStatus status;

    @Column(length = 500)
    private String statusDetail;

    /** OAuth only: who signed in, as the service reports them - an email address, usually. */
    @Column(length = 255)
    private String accountLabel;

    /**
     * OAuth only: what the sign-in returned that later calls check against, as JSON - for Microsoft
     * the tenant, the object id, the app's client id and the granted scopes.
     */
    @Column(length = 4000)
    private String accountJson;

    private Instant connectedAt;

    /**
     * The connection's NON-secret settings as a flat JSON object - a workspace id, a region, an
     * email, an OAuth client id - the fields of its sign-in method that are neither secret nor the
     * username (AuthMethod.settingFields). TEXT, nullable: a NOT NULL column with no default is
     * refused on a populated table. Secrets never go here; they are the encrypted {@link #secret}.
     */
    @Column(columnDefinition = "TEXT")
    private String settingsJson;

    /**
     * Which of its service's sign-in methods this connection uses (AuthKind's name), where the
     * service offers more than one - a token, or a sign-in. Null means the service's first method.
     * Plain text rather than @Enumerated, so a method added later is never refused by a column
     * created before it (the H2 ENUM trap in .claude/rules/backend-maven.md).
     */
    @Column(length = 30)
    private String authMethod;

    // An MCP server connection's settings (McpServerConfig as JSON); null for every other protocol.
    // TEXT, never @Lob - see SubAgentRun.
    @Column(columnDefinition = "TEXT")
    private String mcpConfig;

    // Enterprise Server: an MCP server a Super admin added for everyone. Its owner still edits it;
    // every signed-in user's agent may use it. Never true on any other protocol.
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean shared;

    private Instant lastRefreshedAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ConnectionProtocol getProtocol() { return protocol; }
    public void setProtocol(ConnectionProtocol protocol) { this.protocol = protocol; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public boolean isDefaultConnection() { return defaultConnection; }
    public void setDefaultConnection(boolean defaultConnection) { this.defaultConnection = defaultConnection; }

    public boolean isAllowRead() { return allowRead; }
    public void setAllowRead(boolean allowRead) { this.allowRead = allowRead; }

    public boolean isAllowWrite() { return allowWrite; }
    public void setAllowWrite(boolean allowWrite) { this.allowWrite = allowWrite; }

    public ConnectionStatus getStatus() { return status; }
    public void setStatus(ConnectionStatus status) { this.status = status; }

    public String getStatusDetail() { return statusDetail; }
    public void setStatusDetail(String statusDetail) { this.statusDetail = statusDetail; }

    public String getAccountLabel() { return accountLabel; }
    public void setAccountLabel(String accountLabel) { this.accountLabel = accountLabel; }

    public String getAccountJson() { return accountJson; }
    public void setAccountJson(String accountJson) { this.accountJson = accountJson; }

    public Instant getConnectedAt() { return connectedAt; }
    public void setConnectedAt(Instant connectedAt) { this.connectedAt = connectedAt; }

    public Instant getLastRefreshedAt() { return lastRefreshedAt; }
    public void setLastRefreshedAt(Instant lastRefreshedAt) { this.lastRefreshedAt = lastRefreshedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getSettingsJson() { return settingsJson; }
    public void setSettingsJson(String settingsJson) { this.settingsJson = settingsJson; }

    public String getAuthMethod() { return authMethod; }
    public void setAuthMethod(String authMethod) { this.authMethod = authMethod; }

    public String getMcpConfig() { return mcpConfig; }
    public void setMcpConfig(String mcpConfig) { this.mcpConfig = mcpConfig; }

    public boolean isShared() { return shared; }
    public void setShared(boolean shared) { this.shared = shared; }
}
