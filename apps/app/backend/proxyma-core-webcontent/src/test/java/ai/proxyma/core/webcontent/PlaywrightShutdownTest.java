package ai.proxyma.core.webcontent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A page render in flight does not hold the application's stop. 0.36.0 feedback: the destroy waited
 * for the render's lock with no limit, a render holds it for up to 26 seconds, and a restart during a
 * crawl with JavaScript rendering was halted at fifteen - reproduced on the PC, 9.8s spent here.
 */
class PlaywrightShutdownTest {

    private static PlaywrightRenderService engine() {
        ObjectProvider<WebProxyProvider> noProxy = new StaticListableBeanFactory().getBeanProvider(WebProxyProvider.class);
        return new PlaywrightRenderService(noProxy, null, "playwright");
    }

    @Test
    void theStopWaitsAMomentForARenderAndNoLonger() throws Exception {
        PlaywrightRenderService engine = engine();
        CountDownLatch rendering = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        // A render in flight: the lock held for as long as a slow page takes.
        Thread render = Thread.ofPlatform().daemon().start(() -> {
            engine.lock.lock();
            try {
                rendering.countDown();
                finish.await();
            } catch (InterruptedException ignored) {
                // ends the stand-in render
            } finally {
                engine.lock.unlock();
            }
        });
        assertTrue(rendering.await(5, TimeUnit.SECONDS));

        long started = System.nanoTime();
        engine.shutdown();
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        finish.countDown();
        render.join(5_000);
        assertTrue(tookMillis < PlaywrightRenderService.SHUTDOWN_WAIT_MILLIS + 1_000,
                "the stop is bounded, took " + tookMillis + " ms");
    }

    @Test
    void noRenderStartsOnceTheStopHasBegun() throws Exception {
        PlaywrightRenderService engine = engine();
        engine.shutdown();
        // The lock held elsewhere: a render that tried to take it would wait here for as long.
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().daemon().start(() -> {
            engine.lock.lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException ignored) {
                // ends the holder
            } finally {
                engine.lock.unlock();
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));

        try {
            // Refused at once (a wait for the lock would time this out). Null is "keep the page you
            // have", as for any render that cannot be made.
            assertNull(CompletableFuture.supplyAsync(() -> engine.render("http://127.0.0.1:9/"))
                    .get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            holder.join(5_000);
        }
    }
}
