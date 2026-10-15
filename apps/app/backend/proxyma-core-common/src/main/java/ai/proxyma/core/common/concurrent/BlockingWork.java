package ai.proxyma.core.common.concurrent;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Threads for work that BLOCKS FOR A LONG TIME: a chat turn, a tool call, a model call somebody
 * waits on with a timeout.
 *
 * <p>Until 2026-10-03 that work borrowed two pools that are sized for something else, and at any
 * number of users worth the name it starved both.
 *
 * <ul>
 *   <li>Tool calls, and every model call wrapped in {@code CompletableFuture.supplyAsync} for its
 *       timeout (title, query expansion, rerank, graph extraction), ran on the JVM's COMMON pool -
 *       one fewer threads than cores, for the whole server. A web fetch may hold its thread for
 *       five minutes; a handful of slow ones and every other user's tools waited behind them.</li>
 *   <li>A turn ran on Reactor's bounded-elastic scheduler, which also serves every blocking
 *       database read in the product, the user lookup of each authenticated request among them.
 *       At its cap that scheduler queues a task behind ONE worker rather than on a shared queue,
 *       so a five-millisecond read could wait behind a turn that runs for minutes.</li>
 * </ul>
 *
 * <p>One thread per task, reused for a minute, no queue and no ceiling - the shape sub-agents and
 * workflow runs already use. No ceiling is deliberate: this work waits on a provider or a remote
 * server and holds no lock while it does, so a queue would only move the wait somewhere nobody can
 * see it, and what bounds the number of turns is the rate limit on starting one. Platform threads
 * rather than virtual ones, because the code this runs still blocks inside {@code synchronized}
 * (the H2 driver, Lucene), which pins a virtual thread's carrier on JDK 21.
 *
 * <p>A STATIC, not a bean: it is used from five modules by services that tests build by hand, and
 * an executor has nothing to configure per application context.
 *
 * <p>Thread-locals do not come along. A task sets what it needs and clears it in a finally, as
 * each of these call sites already did - see SpendContext.propagating.
 */
public final class BlockingWork {

    private static final AtomicInteger NUMBER = new AtomicInteger();

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "work-" + NUMBER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private BlockingWork() {
    }

    /** For {@code CompletableFuture.supplyAsync(task, BlockingWork.executor())} and the like. */
    public static Executor executor() {
        return EXECUTOR;
    }
}
