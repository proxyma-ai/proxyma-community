package ai.proxyma.core.provider.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * vLLM, Ollama's /v1 and LM Studio send a model's thinking as {@code reasoning}; Spring AI reads
 * only {@code reasoning_content}. The rename is the only way that thinking reaches Proxyma at all.
 */
class ReasoningFieldAliasTest {

    @Test
    @DisplayName("a string-valued reasoning delta is renamed to reasoning_content")
    void renamesTheDelta() {
        assertEquals("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"let me think\"}}]}",
                ReasoningFieldAlias.rewrite("data: {\"choices\":[{\"delta\":{\"reasoning\":\"let me think\"}}]}"));
        assertEquals("{\"reasoning_content\": null}", ReasoningFieldAlias.rewrite("{\"reasoning\": null}"));
    }

    @Test
    @DisplayName("left alone: reasoning_details, an object value, a line that already has reasoning_content, and the word inside text")
    void leavesEverythingElse() {
        String details = "{\"delta\":{\"reasoning_details\":[{\"type\":\"reasoning.text\"}]}}";
        assertEquals(details, ReasoningFieldAlias.rewrite(details));
        String object = "{\"reasoning\":{\"effort\":\"low\"}}";
        assertEquals(object, ReasoningFieldAlias.rewrite(object));
        String both = "{\"reasoning\":\"a\",\"reasoning_content\":\"a\"}";
        assertEquals(both, ReasoningFieldAlias.rewrite(both));
        String inText = "{\"delta\":{\"content\":\"my \\\"reasoning\\\": it adds up\"}}";
        assertEquals(inText, ReasoningFieldAlias.rewrite(inText));
        assertEquals("data: [DONE]", ReasoningFieldAlias.rewrite("data: [DONE]"));
    }
}
