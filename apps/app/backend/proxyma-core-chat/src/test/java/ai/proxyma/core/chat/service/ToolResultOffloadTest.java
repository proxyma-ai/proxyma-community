package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.ChatSpendService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A tool result too long to hand back is written to a file the agent can read back - and reading
 * it back must never land in a second file.
 *
 * <p>The loop this pins (2026-10-01): a {@code grep} matching 200 long lines was offloaded, the
 * envelope told the agent to grep or read the new file, and those results were offloaded in turn.
 * The {@code read} one was written as the single character "1", because its line-numbered text
 * parses, leniently, as the number it starts with. The agent repeated the three calls until its
 * step limit, with the content it had been told to read not on disk anywhere.
 */
class ToolResultOffloadTest {

    private static final String CONVERSATION = "conv-1";

    @TempDir
    Path root;

    private SmartChatMemory memory;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ChatMessageRepository rows = mock(ChatMessageRepository.class);
        memory = new SmartChatMemory(new JpaChatMemoryRepository(rows, json), mock(ConversationRepository.class),
                rows, mock(LlmGateway.class), mock(SettingsService.class), mock(ModelPricingService.class),
                mock(ChatSpendService.class), mock(ApplicationEventPublisher.class), mock(ChatEventSink.class),
                root.toString(), json, false);
    }

    /** A page of line-numbered text in the shape the read tool returns, {@code chars} long or more. */
    private static String numberedText(int chars) {
        StringBuilder sb = new StringBuilder();
        for (int line = 1; sb.length() < chars; line++) {
            sb.append(String.format("%6d\t%s%n", line, "| x | x | x | x | row " + line));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("a result up to the documented size is handed back whole")
    void theDocumentedSizeIsInline() {
        String result = "a".repeat(SmartChatMemory.MAX_INLINE_RESULT_CHARS);

        assertSame(result, memory.offloadIfLarge(CONVERSATION, "searchIndexedSources", "t1", result));
    }

    @Test
    @DisplayName("read and grep are never offloaded, however long their result")
    void pagingToolsAreNeverOffloaded() throws Exception {
        String page = numberedText(60_000);

        for (String tool : SmartChatMemory.PAGING_TOOLS) {
            String handedBack = memory.offloadIfLarge(CONVERSATION, tool, "t-" + tool, page);

            assertFalse(handedBack.contains("resultOffloaded"), tool);
            assertTrue(handedBack.startsWith(page.substring(0, 2_000)), tool + " keeps the start of the page");
            assertTrue(handedBack.length() < page.length(), tool + " is cut, not passed through whole");
        }
        assertFalse(Files.exists(root.resolve(CONVERSATION)), "nothing was written for either");
    }

    @Test
    @DisplayName("text that starts with a number is stored as the text, not as the number")
    void lineNumberedTextSurvivesTheOffload() throws Exception {
        // Not a paging tool: the same shape reaches the offload from anything returning plain text.
        String text = numberedText(40_000);

        JsonNode envelope = json.readTree(memory.offloadIfLarge(CONVERSATION, "convert", "t2", text));

        assertTrue(envelope.path("resultOffloaded").asBoolean());
        assertEquals(text.length(), envelope.path("totalLength").asInt());
        assertEquals(text, Files.readString(Path.of(envelope.path("filePath").asText())));
    }

    @Test
    @DisplayName("a JSON result is still rendered into readable lines")
    void jsonIsStillRendered() throws Exception {
        String body = ("line one\nline two\n").repeat(2_500);
        String result = json.writeValueAsString(java.util.Map.of("url", "https://example.test/page", "textContent", body));

        JsonNode envelope = json.readTree(memory.offloadIfLarge(CONVERSATION, "webFetch", "t3", result));
        String stored = Files.readString(Path.of(envelope.path("filePath").asText()));

        assertTrue(stored.startsWith("url: https://example.test/page"), stored.substring(0, 80));
        assertTrue(stored.contains("===== textContent =====\nline one\nline two\n"));
    }
}
