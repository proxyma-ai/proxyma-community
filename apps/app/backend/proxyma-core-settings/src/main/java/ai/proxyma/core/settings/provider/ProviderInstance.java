package ai.proxyma.core.settings.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One configured place to get models from: a name, a protocol, a URL and a credential.
 *
 * Replaces the fixed provider list, where each vendor was a settings prefix and a Java adapter. The
 * consequence of that design was that two accounts on one vendor, or any vendor nobody had written
 * an adapter for, were simply not expressible - even when the vendor spoke a protocol the codebase
 * already implemented.
 *
 * <p>A ROW, not a settings prefix, because the count is unbounded and user-chosen. The generic
 * settings store is a flat key/value map with `@EncryptedField` annotations resolved per DECLARED
 * field, which cannot describe N instances whose keys all need encrypting.
 *
 * <p>Referenced by id from every model role (chat, embedding, query expansion, reranking,
 * sub-agent, graph, OCR). The id is what roles store - never the name, which the user can rename
 * without meaning "point this role somewhere else".
 */
@Entity
@Table(name = "provider_instances")
public class ProviderInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * What the user calls it - "OpenAI", "OpenRouter", "Work Azure", "The GPU box". Shown in every
     * role dropdown, and unique so those dropdowns are readable.
     *
     * Also what the cost ledger records as the provider of a call, which is the reason a rename is
     * a rename and not a new row: historical spend keeps the old string either way, and keeping the
     * id stable is what stops a rename from silently detaching every role that pointed here.
     */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /**
     * Stored as a STRING, not an ordinal - rows outlive releases, and an ordinal re-points every
     * historical row the day someone reorders the enum. Same reasoning as PlatformSpendRecord.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ProviderProtocol protocol;

    /** Blank means "the protocol's own default host" - OpenAI's, Anthropic's, Gemini's, Ollama's
     *  local one, OpenRouter's (and, for OPENAI_COMPATIBLE, OpenAI's). Azure and LiteLLM have no
     *  meaningful default and are rejected at save time without one (see
     *  ProviderInstanceService.validate). */
    @Column(length = 500)
    private String baseUrl;

    /**
     * Encrypted at rest with the same AES-256-GCM key every other stored credential uses (see
     * CredentialEncryptionService) - the key itself lives in the OS key store, DPAPI on Windows.
     * NEVER returned to a client: the API sends a masked form and a boolean instead.
     */
    @Column(length = 2000)
    private String apiKey;

    /**
     * Extra HTTP headers sent with every request to this instance, as a JSON object - ENCRYPTED, with
     * the key above's key, since isd-02: a header is as often a credential (a relay's Authorization,
     * a gateway's tenant key) as an attribution. Masked on the way out (ProviderHeaders).
     *
     * Exists because a model is often reached through something in between: OpenRouter asks for
     * `HTTP-Referer` and `X-Title` for attribution, corporate gateways want a tenant or cost-centre
     * header, and LiteLLM routers key on their own. Kept on the native protocols too (the
     * maintainer's decision, 2026-10-13), for a relay or proxy that speaks the vendor's API exactly.
     */
    @Column(length = 8000)
    private String headersJson;

    /**
     * Whether to try this instance's `/v1/rerank` before falling back to embedding-cosine scoring.
     * Off by default: it is a vLLM/Cohere extension, and most OpenAI-compatible services answer 404.
     */
    @Column(nullable = false)
    private boolean nativeRerank;

    /**
     * Which API a gateway instance is spoken to through (LiteLLM: Anthropic Messages or OpenAI Chat).
     * Null for every protocol that has one API - see {@link ProviderProtocol#choosesApi()}.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private GatewayApi api;

    /**
     * One blocking call per step instead of a stream, for a gateway deployment that ignores
     * stream=true - see {@link ProviderProtocol#mayCallWithoutStreaming()}. Off by default.
     *
     * <p>{@code columnDefinition} carries the default, because ddl-auto=update cannot add a NOT NULL
     * column to a table that already has rows without one - PostgreSQL refuses, Hibernate logs it
     * and carries on with the column missing.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean blockingCalls;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ProviderProtocol getProtocol() { return protocol; }
    public void setProtocol(ProviderProtocol protocol) { this.protocol = protocol; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getHeadersJson() { return headersJson; }
    public void setHeadersJson(String headersJson) { this.headersJson = headersJson; }

    public boolean isNativeRerank() { return nativeRerank; }
    public void setNativeRerank(boolean nativeRerank) { this.nativeRerank = nativeRerank; }

    public GatewayApi getApi() { return api; }
    public void setApi(GatewayApi api) { this.api = api; }

    public boolean isBlockingCalls() { return blockingCalls; }
    public void setBlockingCalls(boolean blockingCalls) { this.blockingCalls = blockingCalls; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
