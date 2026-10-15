package ai.proxyma.core.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The executors behind @Async. One pool of four with a queue of a hundred served everything: two
 * slow tasks stopped chat event delivery, and the hundred-and-first waiting event was rejected on
 * the thread running the turn - which left that conversation's stream waiting for ever for the
 * sequence number the rejected event carried.
 */
class AsyncConfigTest {

    private final AsyncConfig config = new AsyncConfig();

    @Test
    @DisplayName("work past the pool's size waits; it is never rejected")
    void nothingIsRejected() throws Exception {
        Executor executor = config.taskExecutor(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        try {
            // Far past the old pool's four threads and queue of a hundred.
            assertDoesNotThrow(() -> {
                for (int i = 0; i < 500; i++) {
                    executor.execute(() -> {
                        await(release);
                        ran.incrementAndGet();
                    });
                }
            });
        } finally {
            release.countDown();
        }
        shutdown(executor);
        assertEquals(500, ran.get());
    }

    @Test
    @DisplayName("the pool uses every thread it has before anything waits")
    void everyThreadIsUsedBeforeQueueing() throws Exception {
        Executor executor = config.taskExecutor(6);
        CountDownLatch started = new CountDownLatch(6);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 6; i++) {
                executor.execute(() -> {
                    started.countDown();
                    await(release);
                });
            }
            // With a core smaller than the maximum and a queue that never fills, only the core
            // would ever run - which is how two slow tasks used to stop everything else.
            assertTrue(started.await(5, TimeUnit.SECONDS), "six tasks run at once on six threads");
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    @Test
    @DisplayName("chat events are delivered while the default pool is busy with slow work")
    void chatEventsDoNotWaitBehindSlowWork() throws Exception {
        Executor slow = config.taskExecutor(2);
        Executor chatEvents = config.chatEventExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        try {
            for (int i = 0; i < 50; i++) slow.execute(() -> await(release));

            chatEvents.execute(delivered::countDown);

            assertTrue(delivered.await(5, TimeUnit.SECONDS), "an event is delivered at once");
        } finally {
            release.countDown();
            shutdown(slow);
            shutdown(chatEvents);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void shutdown(Executor executor) throws InterruptedException {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        pool.getThreadPoolExecutor().shutdown();
        assertTrue(pool.getThreadPoolExecutor().awaitTermination(15, TimeUnit.SECONDS));
    }
}
