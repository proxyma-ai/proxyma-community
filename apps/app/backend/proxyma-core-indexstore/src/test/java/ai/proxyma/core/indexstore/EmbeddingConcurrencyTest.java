package ai.proxyma.core.indexstore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Max concurrent embedding requests" is honored in every edition and without a restart - it was
 * read once at startup by the server edition's store and never by Desktop's (2026-10-01).
 */
class EmbeddingConcurrencyTest {

    @Test
    @Timeout(10)
    void theLimitHoldsAndASlotFreesTheNextCaller() throws Exception {
        EmbeddingConcurrency limiter = new EmbeddingConcurrency(() -> 2);
        limiter.acquire();
        limiter.acquire();

        CountDownLatch third = new CountDownLatch(1);
        Thread waiting = Thread.ofVirtual().start(() -> { limiter.acquire(); third.countDown(); });
        assertFalse(third.await(300, TimeUnit.MILLISECONDS), "a third caller waits while two are in flight");

        limiter.release();
        assertTrue(third.await(2, TimeUnit.SECONDS), "a released slot lets it in");
        waiting.join();
    }

    @Test
    void aNewValueIsReadWithoutARestart() throws Exception {
        AtomicInteger setting = new AtomicInteger(4);
        EmbeddingConcurrency limiter = new EmbeddingConcurrency(setting::get);
        limiter.acquire();
        limiter.release();
        assertEquals(4, limiter.limit());

        setting.set(8);
        Thread.sleep(EmbeddingConcurrency.REFRESH_MS + 100);
        limiter.acquire();
        limiter.release();
        assertEquals(8, limiter.limit());
    }

    @Test
    void zeroOrLessMeansOne() {
        EmbeddingConcurrency limiter = new EmbeddingConcurrency(() -> 0);
        limiter.acquire();
        assertEquals(1, limiter.limit());
    }
}
