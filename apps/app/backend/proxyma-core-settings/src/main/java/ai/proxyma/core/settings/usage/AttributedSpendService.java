package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.common.usage.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Folds one model call onto the month's row for (subject, role, provider, model).
 *
 * <p>NOTHING HERE MAY BREAK A MODEL CALL. Every method swallows its own failures and every read
 * degrades to zero, the same discipline ChatSpendService and PlatformSpendService apply for the
 * same reason: this is bookkeeping alongside the work, not part of it, and a user losing an answer
 * because a counter could not be written would be a far worse bug than a figure being short.
 */
@Service
public class AttributedSpendService {

    private static final Logger log = LoggerFactory.getLogger(AttributedSpendService.class);

    private final AttributedSpendRepository repository;
    private final SpendRowWriter writer;
    private final Clock clock = Clock.systemDefaultZone();

    // Enterprise Server. Everywhere else one person makes every call - see SpendSubject.LOCAL.
    private final boolean multiUser;

    public AttributedSpendService(AttributedSpendRepository repository, SpendRowWriter writer,
                                  @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.repository = repository;
        this.writer = writer;
        this.multiUser = multiUser;
    }

    public String currentMonth() {
        return YearMonth.now(clock).toString();
    }

    /**
     * In a transaction of its own (SpendRowWriter): the caller may be inside a chat transaction that
     * rolls back for an unrelated reason, and the tokens were still spent. Two calls racing to open
     * the month's row both land - see SpendRowWriter.
     *
     * <p>Zero-token calls are dropped rather than recorded as a call with no usage - a provider that
     * reports nothing tells us nothing, and a row of zeroes would inflate the call count while
     * teaching the reader nothing. Zero-COST calls are kept: an unpriced model doing real work is
     * exactly what the "no rate set" state exists to show.
     */
    public void record(SpendSubject subject, String role, String provider, String model,
                       TokenUsage usage, double costUsd) {
        try {
            if (subject == null || usage == null || usage == TokenUsage.EMPTY) return;
            SpendSubject filed = subject.forEdition(multiUser);
            if (usage.inputTokens() == 0 && usage.outputTokens() == 0
                    && usage.cacheReadTokens() == 0 && usage.cacheWriteTokens() == 0) return;
            // A blank provider or model would open an unlabelled bucket that looks like a real one
            // on every breakdown this table feeds.
            if (role == null || provider == null || provider.isBlank() || model == null || model.isBlank()) {
                log.debug("[AttributedSpend] call with no role/provider/model - not recorded");
                return;
            }

            String month = currentMonth();
            writer.write(() -> {
                AttributedSpendMonth row = repository
                        .findByYearMonthAndSubjectKindAndSubjectIdAndRoleAndProviderAndModel(
                                month, filed.kind(), filed.id(), role, provider, model)
                        .orElseGet(() -> new AttributedSpendMonth(month, filed, role, provider, model));
                row.add(usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(),
                        usage.cacheWriteTokens(), costUsd);
                repository.saveAndFlush(row);
            });
        } catch (Exception e) {
            log.debug("[AttributedSpend] could not record spend for {}/{}: {}", role, model, e.getMessage());
        }
    }

    // - reads -

    /** One subject's spend this month, split by what it was spent on. */
    public record RoleTotal(String role, double costUsd, long inputTokens, long outputTokens, long calls) {}

    /** One payer's total, with enough to name them on a page. */
    public record SubjectTotal(SpendSubject.Kind kind, String id, double costUsd,
                               long inputTokens, long outputTokens, long calls) {}

    public double totalCost(String month) {
        try {
            return repository.totalCost(month);
        } catch (Exception e) {
            return 0;
        }
    }

    public double anonymousCost(String month) {
        try {
            return repository.anonymousCost(month);
        } catch (Exception e) {
            return 0;
        }
    }

    public List<SubjectTotal> bySubject(String month) {
        try {
            List<SubjectTotal> out = new ArrayList<>();
            for (Object[] r : repository.bySubject(month)) {
                out.add(new SubjectTotal((SpendSubject.Kind) r[0], (String) r[1],
                        num(r[2]).doubleValue(), num(r[3]).longValue(), num(r[4]).longValue(),
                        num(r[5]).longValue()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<RoleTotal> byRole(String month) {
        return roleTotals(() -> repository.byRole(month));
    }

    public List<RoleTotal> forSubject(String month, SpendSubject subject) {
        return roleTotals(() -> repository.forSubjectByRole(month, subject.kind(), subject.id()));
    }

    private List<RoleTotal> roleTotals(java.util.function.Supplier<List<Object[]>> query) {
        try {
            List<RoleTotal> out = new ArrayList<>();
            for (Object[] r : query.get()) {
                out.add(new RoleTotal((String) r[0], num(r[1]).doubleValue(),
                        num(r[2]).longValue(), num(r[3]).longValue(), num(r[4]).longValue()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Number num(Object o) {
        return o instanceof Number n ? n : 0;
    }
}
