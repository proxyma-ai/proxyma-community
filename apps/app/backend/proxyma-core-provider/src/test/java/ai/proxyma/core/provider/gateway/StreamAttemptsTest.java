package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.AttributedSpendService;
import ai.proxyma.core.settings.usage.LlmCallLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A streamed call is accounted PER ATTEMPT, and off the thread that ends it.
 *
 * <ul>
 *   <li>ModelCall retries a stream by resubscribing it. The session's accounting state was made once
 *       per stream() and shared by every attempt, so attempt 1's counts were filed again on attempt
 *       2's ledger row, and a retry that succeeded was recorded as a failure.</li>
 *   <li>The ledger write - a price read and two transactional writes, one behind a global monitor -
 *       ran on the thread that signalled the stream's end: a Reactor Netty event loop, shared with
 *       the web server.</li>
 * </ul>
 */
class StreamAttemptsTest {

    private static final SpendSubject USER = SpendSubject.user("u-1");

    private LlmCallLogService callLog;
    private AttributedSpendService attributed;
    private DefaultLlmGateway gateway;
    private final AtomicInteger attempts = new AtomicInteger();

    @BeforeEach
    void setUp() {
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        SettingsService settings = mock(SettingsService.class);
        ChatSettings chat = new ChatSettings();
        chat.setProvider("p");
        chat.setModel("m");
        when(settings.getSettings(eq(ChatSettings.PREFIX), eq(ChatSettings.class))).thenReturn(chat);
        when(factory.providerDisplayName("p")).thenReturn("Provider");
        when(factory.resolveProvider(anyString())).thenReturn(Optional.empty());

        // Attempt 1 reports a cache write and then fails mid-stream; attempt 2 reads that cache and
        // completes. Each subscription is one attempt, as a resubscribing retry makes it.
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> Flux.defer(() -> {
            if (attempts.incrementAndGet() == 1) {
                return Flux.just(chunk(100, 1, 10_000, 0)).concatWith(Flux.error(new IllegalStateException("overloaded")));
            }
            return Flux.just(chunk(100, 20, 0, 10_000));
        }));
        when(factory.getChatModel()).thenReturn(model);

        callLog = mock(LlmCallLogService.class);
        attributed = mock(AttributedSpendService.class);
        gateway = new DefaultLlmGateway(factory, settings, attributed, callLog, mock(ModelPricingService.class));
    }

    // The usage as WireUsage leaves it on a chunk: the counts the provider stated, as a TokenUsage.
    private static ChatResponse chunk(int input, int output, int cacheWrite, int cacheRead) {
        TokenUsage stated = new TokenUsage(input, output, cacheRead, cacheWrite, input + cacheRead + cacheWrite);
        DefaultUsage usage = new DefaultUsage(input, output, input + output, stated);
        return new ChatResponse(List.of(new Generation(new AssistantMessage("x"))),
                ChatResponseMetadata.builder().usage(usage).build());
    }

    @Test
    @DisplayName("each attempt of a retried stream files its own row, with only its own counts")
    void eachAttemptIsItsOwnCall() {
        gateway.setSpendRecorder(Runnable::run);
        LlmSession session = gateway.open(LlmCaller.of(LlmRole.CHAT, USER), LlmGateway.SessionOptions.plain());

        session.stream(List.of(new UserMessage("hi")), List.of())
                .retryWhen(Retry.max(1))
                .collectList().block(Duration.ofSeconds(10));

        ArgumentCaptor<TokenUsage> usages = ArgumentCaptor.forClass(TokenUsage.class);
        verify(callLog, times(2)).record(eq(USER), eq("CHAT"), eq("Provider"), eq("m"), usages.capture(), anyDouble());
        // In either order: the failed attempt's own end can be signalled after the retry has run.
        TokenUsage first = usages.getAllValues().stream().filter(u -> u.outputTokens() == 1).findFirst().orElseThrow();
        TokenUsage second = usages.getAllValues().stream().filter(u -> u.outputTokens() == 20).findFirst().orElseThrow();
        assertThat(first.cacheWriteTokens()).isEqualTo(10_000);
        // The second attempt's row carries what the second attempt said, and nothing the first did.
        assertThat(second.cacheReadTokens()).isEqualTo(10_000);
        assertThat(second.cacheWriteTokens()).isZero();
    }

    @Test
    @DisplayName("a stream's spend is written on a worker thread, never on the thread that ended the stream")
    void spendIsRecordedOffTheSignallingThread() throws Exception {
        attempts.set(1); // straight to the attempt that completes
        CountDownLatch recorded = new CountDownLatch(1);
        AtomicReference<String> recordedOn = new AtomicReference<>();
        doAnswer(inv -> {
            recordedOn.set(Thread.currentThread().getName());
            recorded.countDown();
            return null;
        }).when(callLog).record(any(), anyString(), anyString(), anyString(), any(), anyDouble());

        LlmSession session = gateway.open(LlmCaller.of(LlmRole.CHAT, USER), LlmGateway.SessionOptions.plain());
        String signalledOn = Thread.currentThread().getName();
        session.stream(List.of(new UserMessage("hi")), List.of()).collectList().block(Duration.ofSeconds(10));

        assertThat(recorded.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(recordedOn.get()).isNotEqualTo(signalledOn).startsWith("work-");
    }
}
