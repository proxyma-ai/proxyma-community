package ai.proxyma.core.settings.provider;

import java.util.Map;
import java.util.UUID;

/**
 * A provider instance with its credential DECRYPTED, ready to build a client from.
 *
 * Separate from the {@link ProviderInstance} entity so the plaintext key exists only where it is
 * actually needed - the adapters - and never on the object the CRUD/API layer passes around. The
 * API layer works with {@link ProviderInstanceView}, which carries a masked key and could not leak
 * the real one if it tried.
 */
public record ResolvedProvider(
        UUID id,
        String name,
        ProviderProtocol protocol,
        String baseUrl,
        String apiKey,
        Map<String, String> headers,
        boolean nativeRerank,
        GatewayApi api,
        boolean blockingCalls) {

    /** For a protocol with one API, which streams. */
    public ResolvedProvider(UUID id, String name, ProviderProtocol protocol, String baseUrl, String apiKey,
                            Map<String, String> headers, boolean nativeRerank) {
        this(id, name, protocol, baseUrl, apiKey, headers, nativeRerank, null, false);
    }

    public boolean hasBaseUrl() { return baseUrl != null && !baseUrl.isBlank(); }

    public boolean hasApiKey() { return apiKey != null && !apiKey.isBlank(); }

    /** The API a gateway instance is spoken to through; Anthropic Messages where none was chosen. */
    public GatewayApi gatewayApi() { return api != null ? api : GatewayApi.ANTHROPIC_MESSAGES; }

    /** Whether calls to this instance stream - all do, but a gateway told otherwise. */
    public boolean streams() {
        return !(blockingCalls && protocol != null && protocol.mayCallWithoutStreaming());
    }

    /**
     * Never the key or a header's value - a record's generated toString() prints every component,
     * so one log line or assertion message formatting this would write the decrypted credential to
     * a log that travels with feedback attachments. The key is masked as the rest of the product
     * masks credentials (a***d); headers are named, not shown.
     */
    @Override
    public String toString() {
        return "ResolvedProvider[id=" + id + ", name=" + name + ", protocol=" + protocol + ", baseUrl=" + baseUrl
                + ", apiKey=" + masked(apiKey) + ", headers=" + (headers == null ? "{}" : headers.keySet())
                + ", nativeRerank=" + nativeRerank + ", api=" + api + ", blockingCalls=" + blockingCalls + "]";
    }

    private static String masked(String secret) {
        if (secret == null || secret.isEmpty()) return "(none)";
        if (secret.length() < 4) return "***";
        return secret.charAt(0) + "***" + secret.charAt(secret.length() - 1);
    }
}
