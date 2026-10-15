package ai.proxyma.core.settings.audit;

import ai.proxyma.core.common.audit.Audit;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.settings.usage.UserSpendPort;
import ai.proxyma.core.system.util.SensitivePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drains {@link Audit}'s queue into {@code audit_events}, a batch every couple of seconds.
 *
 * <p>Everything a row carries passes through {@link SensitivePaths#scrub} here, at the one place
 * every entry crosses - the summary, the target and every string in the detail - since an Audit
 * Logs page is a client like any other and an absolute server path must never reach one.
 *
 * <p>On {@code chatTransactionManager}, which is the manager these tables live under in both
 * editions (see SpendRowWriter). A batch that fails is logged and dropped: what it recorded has
 * already happened, and retrying a batch that broke the database forever would hold every later
 * entry behind it.
 */
@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    static final int BATCH = 500;
    /** The most a drain takes in one go, so a burst cannot hold the scheduler thread for long. */
    static final int MAX_PER_DRAIN = 5_000;
    /** A detail larger than this is replaced by a note saying so - it is a log, not a store. */
    static final int MAX_DETAIL_CHARS = 256_000;

    private final AuditEventRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final boolean multiUser;

    private UserSpendPort users;

    public AuditWriter(AuditEventRepository repository,
                       @Qualifier("chatTransactionManager") PlatformTransactionManager manager,
                       ObjectMapper json,
                       @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.repository = repository;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.multiUser = multiUser;
    }

    /** Display names for accounts, on Enterprise Server. Absent elsewhere, where there are none. */
    @Autowired(required = false)
    void setUsers(UserSpendPort users) {
        this.users = users;
    }

    @Scheduled(fixedDelay = 2_000, initialDelay = 5_000)
    public void drain() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("[Audit] write failed", e);
        }
    }

    /** Writes everything queued, up to {@link #MAX_PER_DRAIN}; returns how many rows were written. */
    public synchronized int flush() {
        long dropped = Audit.takeDropped();
        if (dropped > 0) {
            log.warn("[Audit] {} entr(ies) dropped - the queue was full", dropped);
        }
        int written = 0;
        while (written < MAX_PER_DRAIN) {
            List<AuditEntry> batch = Audit.drain(BATCH);
            if (batch.isEmpty()) break;
            List<AuditEvent> rows = toRows(batch);
            try {
                tx.executeWithoutResult(s -> repository.saveAll(rows));
                written += rows.size();
            } catch (RuntimeException e) {
                log.warn("[Audit] a batch of {} entr(ies) could not be written and was dropped", rows.size(), e);
            }
            if (batch.size() < BATCH) break;
        }
        return written;
    }

    @PreDestroy
    void onShutdown() {
        try {
            flush();
        } catch (RuntimeException ignored) {
            // The database may already be going away; nothing else can be done with these.
        }
    }

    List<AuditEvent> toRows(List<AuditEntry> batch) {
        Map<String, String> names = names(batch);
        List<AuditEvent> rows = new ArrayList<>(batch.size());
        for (AuditEntry e : batch) {
            SpendSubject actor = (e.actor() == null ? SpendSubject.SYSTEM : e.actor());
            // One person on Desktop and Home Server, as in the LLM call log - except work nobody
            // asked for, which stays SYSTEM so a scheduled sync is not read as the user's doing.
            if (!multiUser && actor.kind() != SpendSubject.Kind.SYSTEM) actor = SpendSubject.LOCAL;

            AuditEvent row = new AuditEvent();
            row.setOccurredAt(e.at());
            row.setCategory(clip(e.category(), 24));
            row.setAction(clip(e.action(), 64));
            row.setOutcome(clip(e.outcome(), 16));
            row.setActorKind(actor.kind().name());
            row.setActorId(clip(actor.id(), 128));
            String name = e.actorName() != null ? e.actorName() : names.get(actor.id());
            row.setActorName(clip(name, 160));
            row.setTarget(clip(SensitivePaths.scrub(e.target()), 255));
            row.setConversationId(clip(e.conversationId(), 64));
            row.setSummary(clip(SensitivePaths.scrub(e.summary()), 500));
            row.setDurationMs(e.durationMs());
            row.setDetail(detail(e.detail()));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, String> names(List<AuditEntry> batch) {
        if (!multiUser || users == null) return Map.of();
        Set<String> ids = new HashSet<>();
        for (AuditEntry e : batch) {
            if (e.actorName() == null && e.actor() != null && e.actor().kind() == SpendSubject.Kind.USER) {
                ids.add(e.actor().id());
            }
        }
        if (ids.isEmpty()) return Map.of();
        try {
            return users.displayNames(ids);
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private String detail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) return null;
        try {
            String text = json.writeValueAsString(scrubbed(detail));
            if (text.length() <= MAX_DETAIL_CHARS) return text;
            return json.writeValueAsString(Map.of("note",
                    "This entry's detail was " + text.length() + " characters and was not kept."));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Object scrubbed(Object value) {
        if (value instanceof String s) return SensitivePaths.scrub(s);
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), scrubbed(v)));
            return out;
        }
        if (value instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object v : c) out.add(scrubbed(v));
            return out;
        }
        return value;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
