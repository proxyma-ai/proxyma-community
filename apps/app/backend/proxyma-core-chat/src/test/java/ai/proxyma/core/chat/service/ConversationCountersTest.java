package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.provider.gateway.LlmSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A conversation's counters, written for real on H2.
 *
 * <p>chat-agent-06: they were read, changed and saved as a whole row from threads that run side by
 * side - the turn, every background sub-agent, the title, a search's own calls - so of two that read
 * the same row one increment was lost, and each save wrote a stale title back over the generated one.
 *
 * <p>chat-agent-08: condensation made its own streamed call and kept the LAST chunk's usage; an
 * Anthropic stream states the prompt in its first chunk only, so the summarizer's input - the whole
 * folded conversation - was recorded as nothing.
 */
class ConversationCountersTest {

    @TempDir
    Path data;

    private ChatJpa jpa;

    @AfterEach
    void tearDown() {
        if (jpa != null) jpa.close();
    }

    private Conversation conversation(String id) {
        return jpa.get(ConversationRepository.class).findById(id).orElseThrow();
    }

    @Test
    @DisplayName("usage added from many threads at once is all counted, and the title it raced is kept")
    void concurrentUsageIsAllCounted() throws Exception {
        jpa = new ChatJpa(data, false);
        SmartChatMemory memory = jpa.get(SmartChatMemory.class);
        memory.ensureConversation("conv-1", "hello", null, null, null, null);
        int writers = 16;
        int each = 25;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        try {
            for (int w = 0; w < writers; w++) {
                done.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < each; i++) {
                        memory.addCumulativeUsage("conv-1", new TokenUsage(10, 1, 0, 0, 10), "P", "m");
                        memory.recordExchange("conv-1");
                    }
                    return null;
                }));
            }
            // The generated title lands in the middle of all that, as it does.
            go.countDown();
            jpa.get(ConversationRepository.class).updateTitle("conv-1", "Generated title");
            for (Future<?> f : done) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Conversation c = conversation("conv-1");
        assertThat(c.getInputTokens()).isEqualTo(10L * writers * each);
        assertThat(c.getOutputTokens()).isEqualTo((long) writers * each);
        assertThat(c.getMessageCount()).isEqualTo(writers * each);
        assertThat(c.getTitle()).isEqualTo("Generated title");
    }

    @Test
    @DisplayName("a condensation's input is counted when the stream states it in its first chunk only")
    void condensationCountsItsInput() {
        jpa = new ChatJpa(data, false);
        SmartChatMemory memory = jpa.get(SmartChatMemory.class);
        LlmGateway gateway = jpa.get(LlmGateway.class);
        when(gateway.isAvailable(LlmRole.CONDENSATION)).thenReturn(true);
        when(gateway.open(any(), any())).thenReturn(new AnthropicLikeSession());

        memory.ensureConversation("conv-1", "first", null, null, null, null);
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        for (int i = 0; i < 4; i++) {
            history.add(new UserMessage("question " + i));
            history.add(new AssistantMessage("answer " + i));
        }
        history.add(new UserMessage("the latest question"));
        memory.add("conv-1", history);

        assertThat(memory.forceCondense("conv-1")).isTrue();

        Conversation c = conversation("conv-1");
        assertThat(c.getInputTokens()).as("the prompt size, from the stream's first chunk").isEqualTo(5_000);
        assertThat(c.getOutputTokens()).isEqualTo(120);
    }

    /** A stream shaped as Anthropic's: the prompt size first, then the text, then the output count. */
    private static final class AnthropicLikeSession implements LlmSession {
        @Override public String provider() { return "Anthropic"; }
        @Override public String model() { return "claude"; }
        @Override public boolean dedicated() { return true; }

        @Override public LlmGateway.LlmResult chat(List<Message> messages, List<ToolCallback> tools) {
            throw new UnsupportedOperationException("this session streams");
        }

        @Override public Flux<ChatResponse> stream(List<Message> messages, List<ToolCallback> tools) {
            return Flux.just(
                    chunk("", new DefaultUsage(5_000, 0)),
                    chunk("The user asked four questions; all were answered.", null),
                    chunk("", new DefaultUsage(0, 120)));
        }

        private static ChatResponse chunk(String text, DefaultUsage usage) {
            Generation generation = new Generation(new AssistantMessage(text));
            return usage == null ? new ChatResponse(List.of(generation))
                    : new ChatResponse(List.of(generation), ChatResponseMetadata.builder().usage(usage).build());
        }
    }
}
