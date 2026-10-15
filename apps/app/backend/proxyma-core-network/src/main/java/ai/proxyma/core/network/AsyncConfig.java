package ai.proxyma.core.network;

import ai.proxyma.core.common.concurrent.BlockingWork;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

/**
 * The executors behind {@code @Async} and {@code @Scheduled}.
 *
 * <p>THREE, BY HOW LONG THE WORK TAKES, since 2026-10-03. There was one: two threads, four at
 * most, a queue of a hundred and a rejection that threw. Everything {@code @Async} ran on it - and
 * that was every streamed chat event of every conversation, beside title generation (a model call
 * of up to thirty seconds), a database write after each priced model call, and the indexing
 * events. A pool only grows past its core size once its queue is FULL, so two title calls in
 * progress stopped event delivery for everyone; the queue then filled in milliseconds, and the
 * next event was rejected on the thread running the turn. A rejected event is worse than a late
 * one: ChatEventSink hands out sequence numbers and waits for each, so a conversation's stream
 * stalled for good behind the number that never came.
 *
 * <ul>
 *   <li>{@code chatEventExecutor} - chat events only. Microseconds each, nothing but memory.</li>
 *   <li>{@code taskExecutor} - the default: short database writes and the page's live events.</li>
 *   <li>{@code blockingWorkExecutor} - anything that waits on a model or a remote server
 *       ({@link BlockingWork}). Name it on the annotation: {@code @Async("blockingWorkExecutor")}.</li>
 * </ul>
 *
 * <p>NONE OF THEM REJECTS. The two pools have a fixed number of threads and an unbounded queue, so
 * work waits rather than failing; what is queued is small (an event, an id), and each producer is
 * bounded by something else - a turn by its provider, a write by the call it follows.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /** The bean name {@code @Async} resolves to when it names nothing. */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor(@Value("${proxyma.async.threads:16}") int threads) {
        return fixedPool(threads, "event-");
    }

    @Bean(name = "chatEventExecutor")
    public Executor chatEventExecutor() {
        return fixedPool(Math.max(4, Runtime.getRuntime().availableProcessors()), "chat-event-");
    }

    @Bean(name = "blockingWorkExecutor")
    public Executor blockingWorkExecutor() {
        return BlockingWork.executor();
    }

    // Core and maximum are EQUAL, which is what makes the pool use all its threads before it
    // queues - with a smaller core it would run on that many until the queue filled, and this
    // queue never fills. Idle threads are let go, so the size costs nothing while nothing runs.
    private static ThreadPoolTaskExecutor fixedPool(int threads, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, threads));
        executor.setMaxPoolSize(Math.max(1, threads));
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix(prefix);
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }

    // What each @Scheduled method runs on. Four served two dozen of them, some of which make a
    // model call or run a backup; one slow tick then delayed the rest.
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(@Value("${proxyma.scheduler.threads:8}") int threads) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, threads));
        scheduler.setThreadNamePrefix("scheduler-");
        return scheduler;
    }
}
