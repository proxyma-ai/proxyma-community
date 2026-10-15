package ai.proxyma.core.common.spend;

import java.util.function.Supplier;

/**
 * The subject the current thread's model calls are spent on behalf of.
 *
 * <p>NO LONGER READ BY THE GATEWAY, since 2026-09-22: every LlmGateway call takes an explicit
 * {@code LlmCaller} carrying its subject, because a subject read from the thread at call time was
 * filed under SYSTEM on every pool thread nobody had set it on, and under the previous request's
 * user wherever a value leaked. What remains is a carrier for the services several frames below
 * the code that knows the user - OCR inside document conversion, a search's expansion and
 * reranking, the guardrail classifier, and the metered embedding bean the vector stores call from
 * inside Spring AI. Each reads it ONCE at its own boundary and passes the subject on explicitly.
 *
 * <p>WHY THIS IS NOT SearchContext, which already carries a userId: that lives in
 * proxyma-core-search, and the gateway that reads this lives in proxyma-core-provider, which search
 * depends on. Putting it there would invert the dependency. It also answers a different question -
 * SearchContext's userId is about what a search may SEE, this is about who a call is billed to, and
 * they diverge for exactly the case that matters here: ingest is billed to a connector's owner, who
 * is not searching anything.
 *
 * <p>NOT INHERITED BY POOL THREADS, and cannot be: a pooled worker outlives the request that
 * borrowed it, so an inherited value would leak one user's identity onto the next one's work. Where
 * a call hops threads on purpose - several callers wrap the model call in
 * {@code CompletableFuture.supplyAsync} for a timeout - wrap the supplier in {@link #propagating}
 * so the subject is captured on the thread that knows it.
 */
public final class SpendContext {

    private static final ThreadLocal<SpendSubject> SUBJECT = new ThreadLocal<>();

    private SpendContext() {
    }

    /** The subject in force, or SYSTEM where nothing has been set - never null. */
    public static SpendSubject current() {
        SpendSubject subject = SUBJECT.get();
        return subject == null ? SpendSubject.SYSTEM : subject;
    }

    public static void set(SpendSubject subject) {
        if (subject == null) {
            SUBJECT.remove();
        } else {
            SUBJECT.set(subject);
        }
    }

    public static void clear() {
        SUBJECT.remove();
    }

    /**
     * Runs the body with {@code subject} in force and restores whatever was there before.
     *
     * <p>Restores rather than clears, because these nest: a sync attributed to a connector's owner
     * can trigger OCR, and the outer subject has to survive the inner one.
     */
    public static <T> T with(SpendSubject subject, Supplier<T> body) {
        SpendSubject previous = SUBJECT.get();
        set(subject);
        try {
            return body.get();
        } finally {
            set(previous);
        }
    }

    /** The void form of {@link #with}. */
    public static void run(SpendSubject subject, Runnable body) {
        with(subject, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Captures the current subject NOW, on this thread, and applies it wherever the supplier
     * eventually runs. For handing work to an executor without losing who it is for.
     */
    public static <T> Supplier<T> propagating(Supplier<T> body) {
        SpendSubject captured = current();
        return () -> with(captured, body);
    }

}
