package ai.proxyma.core.ingest.service;

import ai.proxyma.core.common.concurrent.BlockingWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Starts one of the startup Python capability checks - markitdown, pdfplumber, the OCR rasterizer,
 * Tesseract - on a thread of its own, so they run SIDE BY SIDE.
 *
 * <p>Each spawns an interpreter and pays its import cost, and they ran one after another inside
 * each service's {@code @PostConstruct} - about 3.5 seconds of every start spent waiting on them in
 * turn. Now a start waits for none of them, and together they take as long as the slowest.
 *
 * <p>A result is never read before it exists: every reader of one goes through the future, which
 * WAITS for the check rather than answering with the field's default. A check still running is not
 * a "no" - an indexing pass that asked during it would otherwise skip every PDF as unconvertible.
 *
 * <p>{@link BlockingWork#executor()}, not the common pool, because each of these blocks on a child
 * process for up to a minute.
 */
final class PythonProbe {

    private static final Logger log = LoggerFactory.getLogger(PythonProbe.class);

    private PythonProbe() {
    }

    /**
     * Runs {@code check} on a thread of its own. A check that throws - each catches what it
     * expects, so this is a bug - answers {@code ifFailed} rather than leaving every reader to
     * rethrow it.
     */
    static <T> CompletableFuture<T> start(String what, Supplier<T> check, T ifFailed) {
        return CompletableFuture.supplyAsync(check, BlockingWork.executor())
                .exceptionally(e -> {
                    Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    log.warn("The {} check failed: {}", what, cause.toString());
                    return ifFailed;
                });
    }
}
