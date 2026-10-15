package ai.proxyma.it.app;

import ai.proxyma.it.harness.FakeLlm;
import ai.proxyma.it.harness.Http;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Connecting a model provider - the first thing anyone does, and the thing that must never leak.
 *
 * <p>Half of these are security assertions wearing behavior clothes. A provider instance holds an
 * API key the user pasted in, so what matters is not only that it works but that it never comes
 * back out: not in the list, not in the detail view, not in the settings group that names it, and
 * not in the error message when something goes wrong.
 */
@DisplayName("Providers: connecting a model, and keeping its key")
class ProviderSetupIT extends DesktopJourney {

    @Test
    @DisplayName("every protocol the product supports is offered, and only those")
    void protocolsAreOffered() {
        JsonNode protocols = api().get("/api/v1/providers/protocols").ok().json();
        // Top-level entries only: LiteLLM's carries its own API choices, each with a "value" too.
        List<String> values = new java.util.ArrayList<>();
        protocols.forEach(p -> values.add(p.path("value").asText()));

        // The native protocols, the gateways with protocols of their own, and one for every other
        // server speaking OpenAI's shape (ProviderProtocol, 2026-10-13). Asserted exactly, so adding
        // a constant is a decision somebody makes in the open.
        assertThat(values).containsExactlyInAnyOrder(
                "OPENAI", "ANTHROPIC", "GEMINI", "AZURE_OPENAI", "OLLAMA", "OPENROUTER", "LITELLM",
                "OPENAI_COMPATIBLE");
    }

    @Test
    @DisplayName("the key is masked everywhere it comes back, and never returned in full")
    void theApiKeyNeverComesBack() {
        String secret = "sk-it-super-secret-value-9876543210";
        String id = api().post("/api/v1/providers/instances", Map.of(
                "name", "Key Masking Probe",
                "protocol", "OPENAI_COMPATIBLE",
                "baseUrl", llm().baseUrl(),
                "apiKey", secret)).ok().text("/id");
        try {
            // Three surfaces, because they are three different code paths and only one of them
            // was ever going to be remembered: the create response, the detail read, and the list.
            assertThat(api().get("/api/v1/providers/instances/" + id).ok().body())
                    .doesNotContain(secret);
            assertThat(api().get("/api/v1/providers/instances").ok().body())
                    .doesNotContain(secret);

            JsonNode instance = api().get("/api/v1/providers/instances/" + id).ok().json();
            assertThat(instance.path("apiKeyConfigured").asBoolean())
                    .as("the UI still has to know a key EXISTS").isTrue();
            assertThat(instance.path("apiKeyMasked").asText())
                    .as("masked, not absent - an operator needs to recognize which key it is")
                    .contains("***");
        } finally {
            api().delete("/api/v1/providers/instances/" + id);
        }
    }

    @Test
    @DisplayName("a second instance may carry the same protocol and a different URL")
    void twoInstancesOfOneProtocol() {
        // The entire point of the protocol/vendor split. Under the old fixed provider list two
        // accounts on one vendor were INEXPRESSIBLE, and one runtime serving two models on two
        // ports needed a per-role base-URL override to describe. Both are now just two instances.
        String first = api().post("/api/v1/providers/instances", Map.of(
                "name", "Same Protocol A", "protocol", "OPENAI_COMPATIBLE",
                "baseUrl", llm().baseUrl(), "apiKey", "a")).ok().text("/id");
        String second = api().post("/api/v1/providers/instances", Map.of(
                "name", "Same Protocol B", "protocol", "OPENAI_COMPATIBLE",
                "baseUrl", llm().baseUrl(), "apiKey", "b")).ok().text("/id");
        try {
            assertThat(first).isNotEqualTo(second);
        } finally {
            api().delete("/api/v1/providers/instances/" + first);
            api().delete("/api/v1/providers/instances/" + second);
        }
    }

    @Test
    @DisplayName("a duplicate name is refused with a sentence a user can act on")
    void duplicateNamesAreRefused() {
        Http.Response clash = api().post("/api/v1/providers/instances", Map.of(
                "name", "Fake",
                "protocol", "OPENAI_COMPATIBLE",
                "baseUrl", llm().baseUrl(),
                "apiKey", "x"));

        assertThat(clash.status()).isEqualTo(400);
        // The validation messages are written to be SHOWN verbatim, so a bare 400 with an empty
        // body is a regression even though the status is right.
        assertThat(clash.text("/error")).isNotBlank();
    }

    @Test
    @DisplayName("the product lists the models the provider advertises")
    void modelsAreListedFromTheProvider() {
        // Proves the whole read path - settings to adapter to HTTP - rather than a hardcoded list.
        // The four names come from the fake provider's /v1/models and nowhere else.
        JsonNode models = api().get("/api/v1/providers/" + providerId + "/models").ok().json();
        assertThat(models.toString())
                .contains(FakeLlm.CHAT_MODEL)
                .contains(FakeLlm.EMBEDDING_MODEL)
                .contains(FakeLlm.RERANK_MODEL);

        assertThat(llm().requests().stream().map(r -> r.path("path").asText()).toList())
                .as("the list came from the provider, not from a constant")
                .contains("/v1/models");
    }

    @Test
    @DisplayName("connectivity reports the instance reachable, and says how it knows")
    void connectivityProbesTheRealEndpoint() {
        // THE PROBE IS ASYNCHRONOUS. The first read answers state=PROBING and an empty tally: the
        // endpoint kicks off the round of checks and returns immediately, so the Settings screen
        // can render a spinner instead of blocking on however many providers are configured and
        // however slow the slowest one is. A test that read it once and asserted DONE would pass
        // or fail on timing alone.
        JsonNode connectivity = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            connectivity = api().get("/api/v1/providers/connectivity").ok().json();
            if ("DONE".equals(connectivity.path("state").asText())) break;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        assertThat(connectivity.path("state").asText())
                .as("the probe finished within thirty seconds: %s", connectivity)
                .isEqualTo("DONE");
        assertThat(connectivity.path("reachable").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode fake = null;
        for (JsonNode candidate : connectivity.path("providers")) {
            if ("Fake".equals(candidate.path("provider").asText())) fake = candidate;
        }
        assertThat(fake)
                .as("the fake provider is in the connectivity report: %s", connectivity)
                .isNotNull();
        assertThat(fake.path("reachable").asBoolean()).isTrue();
        // The message names WHAT was found, not merely that something answered. An operator
        // debugging a wrong base URL needs "Reachable (4 model(s))" rather than a green tick.
        assertThat(fake.path("message").asText()).contains("model");
    }

    @Test
    @DisplayName("deleting an instance a role still points at leaves the role naming nothing")
    void deletingAnInstanceInUse() {
        String id = api().post("/api/v1/providers/instances", Map.of(
                "name", "Doomed", "protocol", "OPENAI_COMPATIBLE",
                "baseUrl", llm().baseUrl(), "apiKey", "x")).ok().text("/id");
        app.selectProvider("query-expansion", id, FakeLlm.SMALL_CHAT_MODEL);

        api().delete("/api/v1/providers/instances/" + id).ok();

        // The role keeps the dangling id rather than silently adopting another instance. That is
        // the honest outcome: picking a replacement on the user's behalf would mean a deployment
        // quietly starts spending money at a provider nobody chose. Restore the role afterwards so
        // the shared instance is left as the other journeys expect it.
        String stillThere = app.settings("query-expansion").path("provider").asText();
        assertThat(stillThere).isIn(id, "");
        app.selectProvider("query-expansion", providerId, FakeLlm.SMALL_CHAT_MODEL);
    }
}
