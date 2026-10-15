package ai.proxyma.core.evaluation.listener;

import ai.proxyma.core.chat.event.ChatTurnLatencyRecordedEvent;
import ai.proxyma.core.evaluation.model.ChatTurnMetric;
import ai.proxyma.core.evaluation.model.SearchStageMetric;
import ai.proxyma.core.evaluation.repository.ChatTurnMetricRepository;
import ai.proxyma.core.evaluation.repository.SearchStageMetricRepository;
import ai.proxyma.core.search.event.SearchStageLatencyRecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;

// Persists Evaluation Mode's events into their own append-only tables - mirrors
// UserTokenQuotaService.onChatTurnCostRecorded's exact pattern (proxyma-core-auth): @Async so
// this never shares a thread/transaction with the chat turn or search call that published the
// event, and every handler is wrapped in try/catch so a listener failure (e.g. a transient DB
// hiccup) can never propagate back and affect that chat turn or search call. @EnableAsync is
// already configured deployment-wide (see proxyma-core-network's AsyncConfig and both
// ProxymaDesktopApplication/ProxymaServerApplication) - no need to declare it again here.
@Component
public class EvaluationMetricsListener {

    private static final Logger log = LoggerFactory.getLogger(EvaluationMetricsListener.class);

    private final ChatTurnMetricRepository chatTurnMetricRepository;
    private final SearchStageMetricRepository searchStageMetricRepository;

    public EvaluationMetricsListener(ChatTurnMetricRepository chatTurnMetricRepository,
                                     SearchStageMetricRepository searchStageMetricRepository) {
        this.chatTurnMetricRepository = chatTurnMetricRepository;
        this.searchStageMetricRepository = searchStageMetricRepository;
    }

    @Async
    @EventListener
    public void onChatTurnLatencyRecorded(ChatTurnLatencyRecordedEvent event) {
        try {
            chatTurnMetricRepository.save(new ChatTurnMetric(
                    event.provider(), event.model(),
                    event.timeToFirstTokenMs(), event.totalDurationMs(), event.retryCount(),
                    event.success(), event.outputTokenCount(), Instant.now()));
        } catch (Exception e) {
            log.warn("[EvaluationMetricsListener] Failed to persist chat turn latency: {}", e.getMessage());
        }
    }

    @Async
    @EventListener
    public void onSearchStageLatencyRecorded(SearchStageLatencyRecordedEvent event) {
        try {
            searchStageMetricRepository.save(new SearchStageMetric(
                    event.queryLength(), event.variantCount(), event.expansionMs(), event.denseMs(),
                    event.bm25Ms(), event.rerankMs(), event.totalMs(), event.resultCount(), Instant.now())
                    .withGraph(event.graphChannel(), event.graphMs()));
        } catch (Exception e) {
            log.warn("[EvaluationMetricsListener] Failed to persist search stage latency: {}", e.getMessage());
        }
    }

}
