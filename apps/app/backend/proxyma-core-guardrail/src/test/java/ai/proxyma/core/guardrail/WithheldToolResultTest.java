package ai.proxyma.core.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the agent - and, from the stored result, the chat page - is handed in place of a tool
 * result the retrieval guardrail withheld.
 *
 * <p>It has to stay one valid JSON object (a bare sentence once parsed as something else and
 * SUCCEEDED), and since 2026-11-08 it carries the categories as a list beside the sentence: the
 * page reads them to say under the answer that a source was held back and why.
 */
class WithheldToolResultTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final GuardrailService service = new GuardrailService(null, null, null, null, null, null);

    private static GuardrailDecision blocked(String... categories) {
        List<GuardrailFinding> findings = new ArrayList<>();
        for (String c : categories) findings.add(GuardrailFinding.of("rule." + c, c, "a finding"));
        return GuardrailDecision.block("the original text", findings);
    }

    private JsonNode parse(GuardrailDecision decision) throws Exception {
        return JSON.readTree(service.withheldToolResult(decision));
    }

    @Test
    @DisplayName("the result is a failure the agent knows how to read, marked as the guardrail's, with its categories as a list")
    void carriesTheCategoriesAsAList() throws Exception {
        JsonNode result = parse(blocked("prompt-injection", "secret"));
        assertFalse(result.get("success").asBoolean());
        assertTrue(result.get("guardrailBlocked").asBoolean());
        assertEquals(List.of("prompt-injection", "secret"),
                JSON.convertValue(result.get("categories"), List.class));
        String error = result.get("error").asText();
        assertTrue(error.startsWith("This result was withheld by the configured guardrails (prompt-injection, secret). "), error);
        assertTrue(error.contains("calling the same tool again will not change that"), error);
    }

    @Test
    @DisplayName("with no category it is still valid JSON, with an empty list and no empty parenthesis")
    void noCategories() throws Exception {
        JsonNode result = parse(blocked());
        assertTrue(result.get("categories").isArray());
        assertEquals(0, result.get("categories").size());
        assertTrue(result.get("error").asText().startsWith("This result was withheld by the configured guardrails. "));
    }

    @Test
    @DisplayName("a category carrying a quote or a backslash cannot break the JSON, in the list or in the sentence")
    void hostileCategoryStaysValidJson() throws Exception {
        JsonNode result = parse(blocked("policy \"Q3 plan\\draft\".pdf", "\"\\"));
        assertEquals(List.of("policy Q3 plandraft.pdf"), JSON.convertValue(result.get("categories"), List.class));
        assertTrue(result.get("error").asText().contains("(policy Q3 plandraft.pdf)"));
    }

    @Test
    @DisplayName("the text that was withheld never reaches the result")
    void theWithheldTextIsNotInIt() {
        assertFalse(service.withheldToolResult(blocked("secret")).contains("the original text"));
    }
}
