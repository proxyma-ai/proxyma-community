package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.common.usage.CostSource;
import ai.proxyma.core.common.usage.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the per-call ledger. One method, called from one place.
 *
 * <p>Failures are swallowed for the same reason every other recorder here swallows them: the
 * tokens were spent whatever the bookkeeping does, and an exception thrown out of accounting would
 * fail the chat turn or the ingest that earned it. An under-counted total is visible and
 * recoverable; a failed answer is neither.
 *
 * <p>REQUIRES_NEW so the row commits on its own, independently of whatever transaction the caller
 * is inside - a chat turn that rolls back still spent the money.
 */
@Service
public class LlmCallLogService {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLogService.class);

    private final LlmCallRepository repository;
    // Enterprise Server. Everywhere else one person makes every call - see SpendSubject.LOCAL.
    private final boolean multiUser;

    public LlmCallLogService(LlmCallRepository repository,
                             @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.repository = repository;
        this.multiUser = multiUser;
    }

    private CostCenterService costCenters;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCostCenters(CostCenterService costCenters) {
        this.costCenters = costCenters;
    }

    // Setter-injected and optional, so the ledger built by hand in tests publishes nothing.
    private org.springframework.context.ApplicationEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * One call, as it happened.
     *
     * <p>Unlike the monthly rollup this keeps ZERO-TOKEN calls: a rerank endpoint that answers with
     * scores and no usage block still made a billable request, and "it happened, the provider did
     * not say what it cost" is a fact worth being able to count. What it drops is a call with no
     * provider or model to file it under, which would open an unlabelled bucket that reads as real.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(SpendSubject subject, String role, String provider, String model,
                       TokenUsage usage, double costUsd) {
        record(subject, role, provider, model, usage, costUsd, usage == null ? null : usage.costSource());
    }

    /** {@link #record(SpendSubject, String, String, String, TokenUsage, double)} with where the cost came from. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(SpendSubject subject, String role, String provider, String model,
                       TokenUsage usage, double costUsd, CostSource source) {
        record(subject, role, provider, model, usage, costUsd, source, null, null);
    }

    /** The same, with what the call did with the provider's cache and until when it stays warm. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(SpendSubject subject, String role, String provider, String model,
                       TokenUsage usage, double costUsd, CostSource source,
                       String cacheOutcome, java.time.Instant cacheWarmUntil) {
        try {
            if (subject == null || role == null) return;
            if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
                log.debug("[LlmCallLog] call with no provider/model - not logged");
                return;
            }
            TokenUsage counts = usage == null ? TokenUsage.EMPTY : usage;
            subject = subject.forEdition(multiUser);

            LlmCall row = new LlmCall();
            row.setSubjectKind(subject.kind());
            row.setSubjectId(subject.id());
            row.setRole(role);
            row.setProvider(provider);
            row.setModel(model);
            row.setInputTokens(counts.inputTokens());
            row.setOutputTokens(counts.outputTokens());
            row.setCacheReadTokens(counts.cacheReadTokens());
            row.setCacheWriteTokens(counts.cacheWriteTokens());
            row.setCostUsd(costUsd);
            row.setCostSource((source != null ? source : CostSource.NONE).name());
            row.setLongCacheWriteTokens(counts.cacheWriteTokens() > 0
                    ? Math.min(counts.longCacheWriteTokens(), counts.cacheWriteTokens()) : null);
            row.setCacheOutcome(cacheOutcome);
            row.setCacheWarmUntil(cacheOutcome == null ? null : cacheWarmUntil);
            repository.save(row);
            // Per-user quotas count from here - see LlmSpendRecordedEvent - and only what the user
            // pays for: a service charged to the platform (CostCenterSettings) is logged against the
            // person it was for and charges them nothing.
            if (events != null && costUsd > 0 && (costCenters == null || costCenters.chargedToUser(role))) {
                events.publishEvent(new ai.proxyma.core.common.spend.LlmSpendRecordedEvent(subject, role, costUsd));
            }
        } catch (Exception e) {
            log.debug("[LlmCallLog] could not log call {}/{}: {}", role, model, e.getMessage());
        }
    }
}
