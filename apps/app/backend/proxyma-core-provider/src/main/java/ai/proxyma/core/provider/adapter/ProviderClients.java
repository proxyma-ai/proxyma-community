package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.provider.ResolvedProvider;

import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Applies an instance's custom headers to the HTTP clients an adapter is about to build.
 *
 * Headers are attached to the CLIENT BUILDER rather than passed to each provider SDK's own header
 * API, because those APIs differ or are absent, while every adapter here already hands Spring AI a
 * RestClient/WebClient builder. One place, every protocol.
 *
 * Both builders are mutable and shared as Spring beans, so callers must pass a clone - the methods
 * below mutate what they are given and return it for chaining.
 */
final class ProviderClients {

    private ProviderClients() {}

    static RestClient.Builder applyHeaders(RestClient.Builder builder, ResolvedProvider provider) {
        for (Map.Entry<String, String> h : headers(provider).entrySet()) {
            builder.defaultHeader(h.getKey(), h.getValue());
        }
        return builder;
    }

    static WebClient.Builder applyHeaders(WebClient.Builder builder, ResolvedProvider provider) {
        for (Map.Entry<String, String> h : headers(provider).entrySet()) {
            builder.defaultHeader(h.getKey(), h.getValue());
        }
        return builder;
    }

    private static Map<String, String> headers(ResolvedProvider provider) {
        return provider == null || provider.headers() == null ? Map.of() : provider.headers();
    }
}
