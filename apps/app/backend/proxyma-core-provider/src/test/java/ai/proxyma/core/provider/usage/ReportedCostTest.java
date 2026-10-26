package ai.proxyma.core.provider.usage;

import ai.proxyma.core.common.usage.CallFacts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a response says about its call beyond the counts (2026-10-22): the money it says the call
 * cost, which always wins over any estimate (the maintainer's rule), the tier that served it, the
 * region and the web searches. Shapes as the vendors document them on that day.
 */
class ReportedCostTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("OpenRouter: usage.cost in a stream's last chunk, and the tier at the top")
    void openRouterStreamCost() {
        WireUsage tap = new WireUsage();
        tap.absorbFacts(WireUsage.factsOfEvent("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}],"
                + "\"service_tier\":\"default\",\"usage\":null}", WireUsage.Shape.OPENAI));
        tap.absorbFacts(WireUsage.factsOfEvent("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,"
                + "\"completion_tokens\":5,\"cost\":0.00042,\"is_byok\":false}}", WireUsage.Shape.OPENAI));

        CallFacts facts = tap.facts("OPENROUTER", Instant.EPOCH);
        assertThat(facts.reportedCostUsd()).isEqualTo(0.00042);
        assertThat(facts.serviceTier()).isEqualTo("default");
        assertThat(facts.protocol()).isEqualTo("OPENROUTER");
    }

    @Test
    @DisplayName("OpenRouter with your own key: its fee plus what the provider charged upstream")
    void openRouterByok() throws Exception {
        WireUsage.Facts f = WireUsage.Shape.OPENAI.factsOf(JSON.readTree("{\"usage\":{\"cost\":0.0001,"
                + "\"is_byok\":true,\"cost_details\":{\"upstream_inference_cost\":0.002}}}"));
        assertThat(f.costUsd()).isCloseTo(0.0021, org.assertj.core.api.Assertions.within(1e-12));
    }

    @Test
    @DisplayName("LiteLLM in front of Anthropic: message_delta's usage.cost; Anthropic's tier, region and searches")
    void anthropicShapeFacts() throws Exception {
        WireUsage.Facts f = WireUsage.Shape.ANTHROPIC.factsOf(JSON.readTree("{\"type\":\"message_delta\","
                + "\"usage\":{\"output_tokens\":12,\"cost\":0.031,\"service_tier\":\"standard\","
                + "\"inference_geo\":\"us\",\"server_tool_use\":{\"web_search_requests\":2}}}"));
        assertThat(f.costUsd()).isEqualTo(0.031);
        assertThat(f.serviceTier()).isEqualTo("standard");
        assertThat(f.inferenceGeo()).isEqualTo("us");
        assertThat(f.webSearchRequests()).isEqualTo(2);
        // Anthropic itself states no cost.
        assertThat(WireUsage.Shape.ANTHROPIC.factsOf(JSON.readTree("{\"type\":\"message_start\","
                + "\"message\":{\"usage\":{\"input_tokens\":5}}}")).costUsd()).isNull();
    }

    @Test
    @DisplayName("a later statement replaces an earlier one; a nonsense cost is no cost")
    void foldingAndNonsense() {
        WireUsage tap = new WireUsage();
        tap.absorbFacts(new WireUsage.Facts(0.5, "priority", null, 1));
        tap.absorbFacts(new WireUsage.Facts(0.25, null, null, 0));
        CallFacts facts = tap.facts(null, null);
        assertThat(facts.reportedCostUsd()).isEqualTo(0.25);
        assertThat(facts.serviceTier()).isEqualTo("priority");
        assertThat(facts.webSearchRequests()).isEqualTo(1);

        assertThat(WireUsage.money("-1")).isNull();
        assertThat(WireUsage.money("NaN")).isNull();
        assertThat(WireUsage.money("abc")).isNull();
        assertThat(WireUsage.money(" 0.0123 ")).isEqualTo(0.0123);
        assertThat(new WireUsage().facts("OPENAI", null).reportedCostUsd()).isNull();
    }
}
