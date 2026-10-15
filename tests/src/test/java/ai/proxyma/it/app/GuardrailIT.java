package ai.proxyma.it.app;

import ai.proxyma.it.harness.Chat;
import ai.proxyma.it.harness.Http;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guardrail refusing a turn, and refusing it in the right shape.
 *
 * <p>The deterministic rules are already pinned in depth by unit tests next to the scanner -
 * invisible characters, homoglyphs, bidi overrides, redaction offsets. What those cannot show is
 * that the rule is WIRED IN: that the chat pipeline consults it before calling a provider, that
 * the verdict reaches the user as its own kind of event, and that the event does not carry the
 * thing the rule exists to contain.
 */
@DisplayName("Guardrail: refusing a turn, and how the refusal is reported")
class GuardrailIT extends DesktopJourney {

    private static final String DENIED = "ZARQUON";

    @AfterEach
    void clearTheRule() {
        // Ordered so a failure in the first still leaves the second attempted: MONITOR leaking
        // into the next test makes the guardrail silently stop blocking, which reads as a
        // guardrail bug rather than as leftover state.
        try {
            app.clearSetting("guardrail", "deny-terms");
        } finally {
            app.setting("guardrail", "mode", "ENFORCE");
        }
    }

    @Test
    @DisplayName("a denied term blocks the turn before any provider call is made")
    void aDeniedTermBlocksTheTurn() {
        app.setting("guardrail", "deny-terms", DENIED);
        llm().clear();

        Chat.Turn turn = Chat.send(app, "Tell me about " + DENIED + " please.");

        assertThat(turn.has("guardrail"))
                .as("a blocked turn reports a guardrail event. Saw: %s", turn.types())
                .isTrue();

        // NOT AN ERROR, AND THE DISTINCTION IS THE POINT. An error means the product failed, and a
        // client that rendered this as one invites the user to retry the identical message -
        // which will do the identical thing. This means the product did what its operator
        // configured it to do.
        assertThat(turn.has("error")).as("a guardrail block is not an error").isFalse();

        // Input violations BLOCK by default, and a blocked input has cost nothing yet: no provider
        // call, no turn written. A guardrail that fired after the model call would still refuse
        // the user and still be billed for it.
        assertThat(llm().chatRequests())
                .as("nothing was sent to the provider for a blocked input")
                .isEmpty();
    }

    @Test
    @DisplayName("the guardrail event names categories and never quotes what matched")
    void theEventDoesNotLeakWhatItCaught() throws java.io.IOException {
        app.setting("guardrail", "deny-terms", DENIED);

        Chat.Turn turn = Chat.send(app, "Tell me about " + DENIED + " please.");
        JsonNode event = turn.first("guardrail");

        // THE CONTROL LEAKING THE THING IT CONTAINS. The reason a secret rule fires is that the
        // span it matched IS a credential, so an event quoting it would publish the secret to the
        // client, the browser console and any log that records the stream. A deny term is not a
        // credential, but the rule is the same one and it has to hold for both - which is why this
        // is asserted on the channel rather than on the rule that happens to be cheap to trigger.
        assertThat(event.toString())
                .as("the guardrail event must not carry the matched text")
                .doesNotContain(DENIED);

        // The verdict rides in `data` as a JSON STRING that has to be parsed again - the SSE
        // payload has exactly five keys (type, callId, toolName, data, parentCallId) and `data`
        // carries whatever the event type wants.
        //
        // Worth stating plainly because ChatEvent.Type.GUARDRAIL's own javadoc says "`text` is the
        // sentence to show the user" and THERE IS NO text FIELD - not on the record, not on the
        // wire. The sentence is data.message. An earlier draft of this test believed the comment,
        // asserted on a field that does not exist, and failed against a product that was right.
        JsonNode verdict = Http.JSON.readTree(event.path("data").asText());

        assertThat(verdict.path("categories").toString())
                .as("the event names WHY, so a client can explain it: %s", verdict)
                .contains("deny-list");
        assertThat(verdict.path("message").asText())
                .as("the user is told something they can act on")
                .isNotBlank();
        assertThat(verdict.path("stage").asText()).isEqualTo("input");
        assertThat(verdict.path("verdict").asText()).isEqualTo("block");
    }

    @Test
    @DisplayName("MONITOR records the verdict and lets the turn through")
    void monitorModeDoesNotBlock() {
        app.setting("guardrail", "deny-terms", DENIED);
        app.setting("guardrail", "mode", "MONITOR");
        llm().clear();

        Chat.Turn turn = Chat.send(app, "Tell me about " + DENIED + " @@text allowed through");

        // This is how a threshold gets chosen without experimenting on people: what WOULD have
        // happened shows up in the metrics while every message still goes through untouched. A
        // MONITOR mode that quietly blocked would make the calibration run worthless, and a
        // MONITOR mode that recorded nothing would make it invisible.
        assertThat(turn.text()).isEqualTo("allowed through");
        assertThat(llm().chatRequests())
                .as("in MONITOR the turn still reaches the provider").isNotEmpty();
    }

    @Test
    @DisplayName("a blocked turn is counted in the metrics")
    void blocksAreCounted() {
        app.setting("guardrail", "deny-terms", DENIED);
        Chat.send(app, "Tell me about " + DENIED + ".");

        JsonNode metrics = api().get("/api/v1/guardrail/metrics").ok().json();
        // A guardrail that is on and doing nothing, with no signal that it is doing nothing, is
        // the failure this module keeps designing against. The counter is that signal.
        assertThat(metrics.toString()).as("metrics record the decision: %s", metrics).isNotEqualTo("{}");
    }

    @Test
    @DisplayName("an ordinary message is untouched when the guardrail is on")
    void anOrdinaryMessagePassesThrough() {
        app.setting("guardrail", "deny-terms", DENIED);

        Chat.Turn turn = Chat.send(app, "@@text perfectly ordinary").succeeded();

        // The false-positive half. A guardrail that refuses everything is trivially safe and
        // useless, and nothing else in this class would notice.
        assertThat(turn.has("guardrail")).isFalse();
        assertThat(turn.text()).isEqualTo("perfectly ordinary");
    }
}
