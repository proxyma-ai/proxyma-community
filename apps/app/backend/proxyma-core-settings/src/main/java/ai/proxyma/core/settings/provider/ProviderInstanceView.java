package ai.proxyma.core.settings.provider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the API returns for a provider instance. The API key is MASKED here and there is no shape in
 * which this type can carry the real one - the plaintext lives only on {@link ResolvedProvider},
 * which never leaves the server.
 *
 * @param apiKeyMasked  e.g. "sk-a***9f2", or null when no key is stored. Enough for the user to
 *                      recognise WHICH key is configured without the value being readable, which is
 *                      the same rule the rest of this codebase applies to stored credentials.
 * <p>There is no capability field. Every instance is selectable for every role - see
 * {@link ProviderProtocol} for why - so there is nothing here for a dropdown to filter on.
 */
public record ProviderInstanceView(
        UUID id,
        String name,
        ProviderProtocol protocol,
        String protocolLabel,
        String baseUrl,
        String apiKeyMasked,
        boolean apiKeyConfigured,
        /** A credential header's value by its ends (ProviderHeaders), every other value as stored. */
        Map<String, String> headers,
        boolean nativeRerank,
        GatewayApi api,
        boolean blockingCalls) {

    /** For a protocol with one API, which streams. */
    public ProviderInstanceView(UUID id, String name, ProviderProtocol protocol, String protocolLabel,
                                String baseUrl, String apiKeyMasked, boolean apiKeyConfigured,
                                Map<String, String> headers, boolean nativeRerank) {
        this(id, name, protocol, protocolLabel, baseUrl, apiKeyMasked, apiKeyConfigured, headers,
                nativeRerank, null, false);
    }

    /**
     * Shows the first and last few characters only, and never for a key short enough that those
     * would be most of it.
     */
    public static String mask(String key) {
        return ai.proxyma.core.settings.service.SecretMask.mask(key);
    }

    public static List<ProviderProtocolView> protocols() {
        return java.util.Arrays.stream(ProviderProtocol.values())
                .map(p -> new ProviderProtocolView(p, p.displayName(), p.maySupportNativeRerank(),
                        p.choosesApi() ? apis() : List.of(), p.mayCallWithoutStreaming()))
                .toList();
    }

    private static List<GatewayApiView> apis() {
        return java.util.Arrays.stream(GatewayApi.values())
                .map(a -> new GatewayApiView(a, a.displayName()))
                .toList();
    }

    /** The protocol catalogue the Add-provider form renders. Served from the backend rather than
     *  duplicated in the SPA so the two cannot disagree about what protocols exist. {@code apis}
     *  is empty for a protocol with one API. */
    public record ProviderProtocolView(ProviderProtocol value, String label,
            boolean maySupportNativeRerank, List<GatewayApiView> apis, boolean mayCallWithoutStreaming) {}

    public record GatewayApiView(GatewayApi value, String label) {}
}
