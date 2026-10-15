package ai.proxyma.core.common.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one entry point audited code calls: {@code AuditEntry.of(AuditCategory.TOOL, "tool.call")...record()}.
 *
 * <p>STATIC, for the reason {@code Diagnostics} is: the things worth auditing happen in every module
 * from the sign-in filter to the updater, and injecting a bean into all of them for a queue append
 * would widen two dozen constructors and their tests for nothing.
 *
 * <p>NOTHING HERE TOUCHES A DATABASE. An entry is queued and the call returns; proxyma-core-settings'
 * {@code AuditWriter} drains the queue every couple of seconds and writes it in batches. A guardrail
 * check or a tool call never waits on an insert, and a failed insert cannot fail what it recorded.
 *
 * <p>BOUNDED. If nothing drains it - a test, or a writer that cannot reach its database - the
 * queue keeps the newest {@link #CAPACITY} entries and counts what it dropped, rather than growing
 * until the process runs out of memory.
 */
public final class Audit {

    static final int CAPACITY = 20_000;

    private static final ConcurrentLinkedQueue<AuditEntry> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();
    private static final AtomicLong DROPPED = new AtomicLong();

    private Audit() {}

    public static void record(AuditEntry.Builder builder) {
        try {
            record(builder.build());
        } catch (RuntimeException ignored) {
            // Auditing must never fail what it audits.
        }
    }

    public static void record(AuditEntry entry) {
        if (entry == null) return;
        QUEUE.add(entry);
        if (SIZE.incrementAndGet() > CAPACITY && QUEUE.poll() != null) {
            SIZE.decrementAndGet();
            DROPPED.incrementAndGet();
        }
    }

    /** Takes up to {@code max} entries, oldest first. */
    public static List<AuditEntry> drain(int max) {
        List<AuditEntry> out = new ArrayList<>(Math.min(max, Math.max(0, SIZE.get())));
        AuditEntry e;
        while (out.size() < max && (e = QUEUE.poll()) != null) {
            SIZE.decrementAndGet();
            out.add(e);
        }
        return out;
    }

    /** How many entries were dropped because the queue was full, and resets the count. */
    public static long takeDropped() {
        return DROPPED.getAndSet(0);
    }

    public static int pending() {
        return Math.max(0, SIZE.get());
    }
}
