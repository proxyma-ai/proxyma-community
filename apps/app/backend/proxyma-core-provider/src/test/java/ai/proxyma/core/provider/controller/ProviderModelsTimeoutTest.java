package ai.proxyma.core.provider.controller;

import ai.proxyma.core.provider.adapter.LlmProviderAdapter;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.provider.service.ProviderConnectivityService;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Hooks;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A provider slower than the model dropdown's wait is answered once, by the timeout, and its call is
 * ended rather than left running.
 *
 * <p>The 0.36.0 feedback log: four providers behind a proxy that was not answering, and for each one
 * a WARN for the timeout followed by "ERROR reactor.core.publisher.Operators - Operator called
 * default onErrorDropped" and a second stack trace. The timeout cancelled the call, cancelling
 * interrupted its thread, and the interrupted request then failed into a subscriber that had gone.
 */
class ProviderModelsTimeoutTest {

    private final List<Throwable> dropped = new CopyOnWriteArrayList<>();

    @AfterEach
    void resetHooks() {
        Hooks.resetOnErrorDropped();
    }

    @Test
    @DisplayName("a call failing after the timeout answered is neither dropped as an error nor left running")
    void lateFailureIsNotDropped() throws Exception {
        Hooks.onErrorDropped(dropped::add);

        String id = UUID.randomUUID().toString();
        ResolvedProvider resolved = new ResolvedProvider(UUID.fromString(id), "Slow", ProviderProtocol.LITELLM,
                "https://llm.gateway.invalid", "key", Map.of(), false);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        LlmProviderAdapter adapter = mock(LlmProviderAdapter.class);
        when(adapter.listModels(any())).thenAnswer(inv -> {
            try {
                Thread.sleep(30_000);
                return List.of("never");
            } catch (InterruptedException e) {
                // What the JDK client's request does when its thread is interrupted.
                Thread.currentThread().interrupt();
                interrupted.countDown();
                throw new ResourceAccessException("I/O error on GET request: Request was interrupted",
                        new IOException("Request was interrupted", e));
            } finally {
                returned.countDown();
            }
        });
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.resolveProvider(id)).thenReturn(Optional.of(resolved));
        when(factory.getAdapter(id)).thenReturn(adapter);

        ProviderModelsController controller = new ProviderModelsController(factory,
                new ProviderConnectivityService(List.of(), mock(ProviderInstanceService.class)));
        controller.remoteWait = Duration.ofMillis(300);

        ResponseEntity<Object> answer = controller.listModels(id).block(Duration.ofSeconds(10));

        assertThat(answer).isNotNull();
        assertThat(answer.getStatusCode().value()).isEqualTo(504);
        assertThat(answer.getBody()).isEqualTo(Map.of("error", "timeout", "message", "Connection timed out."));
        // Ended by the timeout, not by its own 30 seconds.
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
        // The catch in the controller runs just after the adapter returns; give it the moment.
        Thread.sleep(300);
        assertThat(dropped).isEmpty();
    }

    @Test
    @DisplayName("a failure within the wait is still the classified answer")
    void failureWithinTheWaitIsTheAnswer() {
        String id = UUID.randomUUID().toString();
        ResolvedProvider resolved = new ResolvedProvider(UUID.fromString(id), "Down", ProviderProtocol.LITELLM,
                "https://llm.gateway.invalid", "key", Map.of(), false);
        LlmProviderAdapter adapter = mock(LlmProviderAdapter.class);
        when(adapter.listModels(any())).thenThrow(new ResourceAccessException("I/O error",
                new java.net.ConnectException("Connection refused")));
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.resolveProvider(id)).thenReturn(Optional.of(resolved));
        when(factory.getAdapter(id)).thenReturn(adapter);

        ResponseEntity<Object> answer = new ProviderModelsController(factory,
                new ProviderConnectivityService(List.of(), mock(ProviderInstanceService.class)))
                .listModels(id).block(Duration.ofSeconds(10));

        assertThat(answer).isNotNull();
        assertThat(answer.getStatusCode().value()).isEqualTo(502);
        assertThat(answer.getBody()).isEqualTo(Map.of("error", "connection_refused",
                "message", "Connection refused - is the service running at that address?"));
    }
}
