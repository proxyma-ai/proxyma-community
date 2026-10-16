package ai.proxyma.core.system.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every exit closes the application on a thread of its own and ends the process either way; one
 * that overruns closes what registered a last-resort close (Desktop's H2 databases) and halts.
 * 0.36.0 feedback: a restart was halted with its databases open, which H2 recovers from at the next
 * start minus the writes of its last second.
 */
class ProcessExitTest {

    // Records how the process would have ended, where the real one would end the test run.
    private static final class Recorder implements ProcessExit.Jvm {
        final List<String> calls = new CopyOnWriteArrayList<>();
        @Override public void exit(int code) { calls.add("exit " + code); }
        @Override public void halt(int code) { calls.add("halt " + code); }
    }

    // A context whose close blocks in a bean's destroy until released - the 2026-09-16 hang, and
    // the 0.36.0 one (a page render holding the browser's lock).
    private static GenericApplicationContext stuckContext(CountDownLatch release) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("blocker", AutoCloseable.class, () -> release::await);
        context.refresh();
        return context;
    }

    @AfterEach
    void forget() {
        ProcessExit.clearBeforeHalt();
    }

    @Test
    void aCloseThatFinishesExitsWithItsCodeAndHaltsNothing() {
        Recorder jvm = new Recorder();
        List<String> closed = new CopyOnWriteArrayList<>();
        ProcessExit.beforeHalt("db", () -> closed.add("db"));
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();

        ProcessExit.closeAndExit(context, ProcessExit.RESTART, 5_000, jvm);

        assertEquals(List.of("exit 3"), jvm.calls);
        assertFalse(context.isActive(), "the context was closed before the exit");
        assertTrue(closed.isEmpty(), "a clean close needs no last-resort close");
    }

    @Test
    void aCloseThatOverrunsClosesWhatRegisteredAndHalts() {
        Recorder jvm = new Recorder();
        List<String> closed = new CopyOnWriteArrayList<>();
        ProcessExit.beforeHalt("db", () -> closed.add("db before " + jvm.calls));
        CountDownLatch release = new CountDownLatch(1);
        ProcessExit.closeAndExit(stuckContext(release), 0, 300, jvm);
        List<String> ended = List.copyOf(jvm.calls);
        release.countDown();

        assertEquals(List.of("halt 0"), ended);
        assertEquals(List.of("db before []"), closed, "the databases are closed BEFORE the halt");
    }

    @Test
    void aLastResortCloseThatHangsDoesNotHoldTheHalt() {
        CountDownLatch never = new CountDownLatch(1);
        List<String> closed = new CopyOnWriteArrayList<>();
        ProcessExit.beforeHalt("stuck", () -> {
            try { never.await(); } catch (InterruptedException ignored) { }
        });
        ProcessExit.beforeHalt("other", () -> closed.add("other"));

        long started = System.nanoTime();
        ProcessExit.runBeforeHalt(300);
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        never.countDown();
        assertTrue(tookMillis < 2_000, "bounded, took " + tookMillis + " ms");
        assertEquals(List.of("other"), closed, "one stuck close does not keep the others from running");
    }

    @Test
    void aSecondRegistrationUnderOneNameReplacesTheFirst() {
        List<String> closed = new CopyOnWriteArrayList<>();
        ProcessExit.beforeHalt("db", () -> closed.add("first"));
        ProcessExit.beforeHalt("db", () -> closed.add("second"));
        ProcessExit.runBeforeHalt(2_000);
        assertEquals(List.of("second"), closed);
    }
}
