package ai.proxyma.desktop.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Removes the H2 lock files a previous run left behind, before the application opens its databases.
 *
 * <p>WHY. Every desktop database was opened with {@code AUTO_SERVER=TRUE} until 2026-10-14, and
 * still is where a developer turns it back on (proxyma.h2.auto-server), and H2 then writes a
 * {@code <name>.lock.db} naming the address of the server it started. An install upgraded from
 * such a build can have one left over. A run that ended without
 * closing its databases - Windows shutting down, a forced stop after the reaper's grace - leaves
 * that file behind, and the next run treats it as a database that may still be open: it tries the
 * dead server's address (two seconds, its connect timeout) and then waits two more for the owner it
 * cannot find. FOUR SECONDS PER DATABASE, measured 2026-10-10 against H2 2.3.232 with a lock left by
 * a halted process (4.2s against 0.2s), and the application opens four at startup one after
 * another: the maintainer's log shows starts of 33 seconds after such an exit against 15 to 17
 * after a clean one, the difference being exactly those four waits.
 *
 * <p>ONLY A LOCK NOBODY HOLDS. H2's MVStore holds an operating-system lock on {@code <name>.mv.db}
 * for as long as the database is open, in any process. So the lock file is deleted only while THIS
 * process holds that same lock - which it cannot get if anything has the database open, a second
 * tool connected through {@code AUTO_SERVER} included. Run by the launcher between two lives of
 * the application, when its own child has none of them open.
 */
final class StaleDatabaseLocks {

    private static final Logger log = LoggerFactory.getLogger(StaleDatabaseLocks.class);
    private static final String LOCK_SUFFIX = ".lock.db";
    private static final String DATA_SUFFIX = ".mv.db";

    private StaleDatabaseLocks() {}

    /** Deletes every lock file in {@code dataRoot} whose database nothing has open; returns how many. */
    static int clear(Path dataRoot) {
        List<Path> locks;
        try (var entries = Files.list(dataRoot)) {
            locks = entries.filter(p -> p.getFileName().toString().endsWith(LOCK_SUFFIX)).toList();
        } catch (IOException e) {
            return 0;
        }
        int removed = 0;
        for (Path lock : locks) {
            String name = lock.getFileName().toString();
            Path data = lock.resolveSibling(name.substring(0, name.length() - LOCK_SUFFIX.length()) + DATA_SUFFIX);
            if (removeIfStale(lock, data)) removed++;
        }
        return removed;
    }

    private static boolean removeIfStale(Path lock, Path data) {
        // No database beside it: nothing to vouch for, and H2 settles a lone lock file itself.
        if (!Files.isRegularFile(data)) return false;
        try (FileChannel channel = FileChannel.open(data, READ, WRITE)) {
            FileLock held = channel.tryLock();
            if (held == null) return false;
            try {
                Files.deleteIfExists(lock);
                log.info("Removed {}, left by a run that did not close its database", lock.getFileName());
                return true;
            } finally {
                held.release();
            }
        } catch (OverlappingFileLockException | IOException e) {
            // Open in this JVM, or not ours to open: leave it to H2.
            return false;
        }
    }
}
