package ai.proxyma.desktop.launcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher removes an H2 lock file only when nothing has its database open - a lock file left by
 * a run that did not close its databases costs the next start four seconds per database, and one
 * removed from under a live database would let two processes write it.
 */
class StaleDatabaseLocksTest {

    @TempDir Path data;

    private static String url(Path db) {
        return "jdbc:h2:file:" + db + ";LOCK_TIMEOUT=10000;AUTO_SERVER=TRUE";
    }

    @Test
    @DisplayName("a lock file left by a run that is gone is removed, and the database opens")
    void staleLockIsRemoved() throws Exception {
        Path db = data.resolve("chat_history");
        Path lock = data.resolve("chat_history.lock.db");
        // A real lock file, naming a server that no longer exists: copied while the database is
        // open, put back after it closed - what a halted process leaves behind.
        byte[] left;
        try (Connection c = DriverManager.getConnection(url(db), "SA", "")) {
            c.createStatement().execute("CREATE TABLE t(x INT)");
            left = Files.readAllBytes(lock);
        }
        assertFalse(Files.exists(lock), "H2 removes its lock file on a clean close");
        Files.write(lock, left);

        assertEquals(1, StaleDatabaseLocks.clear(data));
        assertFalse(Files.exists(lock));
        try (Connection c = DriverManager.getConnection(url(db), "SA", "")) {
            c.createStatement().execute("INSERT INTO t VALUES (1)");
        }
    }

    @Test
    @DisplayName("the lock file of a database somebody has open is left alone")
    void heldLockIsKept() throws Exception {
        Path db = data.resolve("local_folders");
        Path lock = data.resolve("local_folders.lock.db");
        try (Connection c = DriverManager.getConnection(url(db), "SA", "")) {
            assertTrue(Files.exists(lock));
            assertEquals(0, StaleDatabaseLocks.clear(data));
            assertTrue(Files.exists(lock));
            c.createStatement().execute("CREATE TABLE t(x INT)");
        }
    }

    @Test
    @DisplayName("a lock file with no database beside it is not this class's to judge")
    void loneLockIsKept() throws Exception {
        Path lock = Files.writeString(data.resolve("evaluation.lock.db"), "#FileLock\n");
        assertEquals(0, StaleDatabaseLocks.clear(data));
        assertTrue(Files.exists(lock));
    }
}
