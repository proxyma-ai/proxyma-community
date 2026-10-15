package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything that speaks OpenAI's {@code /v1/chat/completions} but is not OpenAI and has no protocol
 * of its own: the servers people run - vLLM, llama.cpp's server, TGI, LM Studio, LocalAI - and
 * services such as DeepSeek, Mistral, Groq, Together, Fireworks, xAI and Nvidia NIM. Each is a name
 * and a base URL; none needed a line of code.
 *
 * <p>What those servers do differently from OpenAI lives HERE and nowhere near
 * {@link OpenAiAdapter}: a server that ignores the credential, a {@code reasoning} field Spring AI
 * cannot read ({@link ReasoningFieldAlias}), several tool calls in one stream delta
 * ({@link ParallelToolCallDeltas}), limits under each server's own field name
 * ({@link ModelLimits#fromOpenAiCompatible}), and vLLM's {@code /v1/rerank}, turned on per
 * instance because most of them answer it with a 404.
 *
 * <p>A blank base URL still means OpenAI's host, as it always has for this protocol.
 */
@Component
public class OpenAiCompatibleAdapter extends OpenAiShapedAdapter {

    // Self-hosted runtimes typically ignore the credential but still require the Authorization
    // header to be well-formed, so an instance with no key gets a placeholder rather than a null
    // that would fail the request builder.
    private static final String PLACEHOLDER_KEY = "not-required";

    public OpenAiCompatibleAdapter(
            @Qualifier("llmClientHttpRequestFactory") ClientHttpRequestFactory externalHttpFactory,
            @Qualifier("externalWebClientBuilder") WebClient.Builder externalWebClientBuilder) {
        super(externalHttpFactory, externalWebClientBuilder);
    }

    @Override
    public ProviderProtocol protocol() {
        return ProviderProtocol.OPENAI_COMPATIBLE;
    }

    @Override
    protected String apiKey(ResolvedProvider provider) {
        return provider.hasApiKey() ? provider.apiKey() : PLACEHOLDER_KEY;
    }

    @Override
    protected List<ExchangeFilterFunction> streamFilters() {
        // A reasoning model's thinking arrives as `reasoning` on most of these servers now and Spring
        // AI reads only `reasoning_content`; several tool calls in one delta end the reply with an
        // exception inside Spring AI.
        return List.of(ReasoningFieldAlias.filter(), ParallelToolCallDeltas.filter());
    }

    @Override
    protected boolean statesLimits() {
        return true;
    }

    @Override
    protected ModelLimits limitsOf(Map<String, Object> model) {
        return ModelLimits.fromOpenAiCompatible(model);
    }

    /**
     * Cohere-compatible request/response shape, which is vLLM's documented default for models
     * served with --task score. Ported unchanged from the former vllm-rerank provider, including
     * its caveat: this API has moved across vLLM releases, so verify the path against the deployed
     * version's own /docs rather than assuming.
     *
     * Only reached when the instance opts in - see ResolvedProvider.nativeRerank - because most
     * OpenAI-compatible services have no /v1/rerank at all.
     */
    @Override
    @SuppressWarnings("unchecked")
    public RerankResponse rerank(ResolvedProvider provider, String query, List<String> documents,
                                 String model) {
        if (!provider.hasBaseUrl()) {
            throw new IllegalStateException(
                    "Provider '" + provider.name() + "' has no base URL, so it cannot be reranked against");
        }
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "query", query,
                "documents", documents,
                "top_n", documents.size());

        Map<String, Object> body = rest(provider).post()
                .uri(provider.baseUrl() + "/v1/rerank")
                .header("Authorization", "Bearer " + apiKey(provider))
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> results = body == null
                ? List.of() : (List<Map<String, Object>>) body.getOrDefault("results", List.of());

        // Preserve server-returned order (typically already relevance-sorted) - do not re-sort.
        List<RerankResult> parsed = new ArrayList<>(results.size());
        for (Map<String, Object> r : results) {
            int index = ((Number) r.get("index")).intValue();
            double score = ((Number) r.get("relevance_score")).doubleValue();
            parsed.add(new RerankResult(index, score));
        }
        return new RerankResponse(parsed, rerankUsage(body));
    }

    /**
     * The usage a rerank endpoint reported, in the two shapes they come in - and EMPTY when it
     * reported none.
     *
     * <p>vLLM and the OpenAI-shaped services answer with {@code usage.prompt_tokens}; Cohere's own
     * API answers with {@code meta.billed_units.search_units}, which is not a token count at all
     * and is deliberately NOT mapped onto one. Counting search units as tokens would price them at
     * the model's per-token rate and report a number that is wrong rather than missing.
     */
    @SuppressWarnings("unchecked")
    private static TokenUsage rerankUsage(Map<String, Object> body) {
        if (body == null) return TokenUsage.EMPTY;
        Object usage = body.get("usage");
        if (!(usage instanceof Map<?, ?> u)) return TokenUsage.EMPTY;
        Map<String, Object> m = (Map<String, Object>) u;
        long prompt = asLong(m.get("prompt_tokens"));
        long total = asLong(m.get("total_tokens"));
        // An endpoint reporting only a total puts it in the input column: rerank returns scores,
        // not generated text, so there is no output half to split it into.
        long input = prompt > 0 ? prompt : total;
        if (input <= 0) return TokenUsage.EMPTY;
        return new TokenUsage(input, 0, 0, 0, input);
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

}
