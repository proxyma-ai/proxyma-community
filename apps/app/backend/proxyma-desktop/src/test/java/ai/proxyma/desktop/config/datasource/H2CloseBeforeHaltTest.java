package ai.proxyma.desktop.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A database still open when the process is about to be halted is closed first, with what was
 * committed written. Desktop's databases no longer close in H2's own JVM hook (DB_CLOSE_ON_EXIT=FALSE,
 * 0.36.0 feedback) and a halt runs no hook - so without this a halted restart left them open.
 */
class H2CloseBeforeHaltTest {

    @TempDir
    Path dir;

    private String url;
    private HikariDataSource pool;

    @BeforeEach
    void aDesktopDatabase() throws SQLException {
        // As application-desktop.properties opens them.
        url = "jdbc:h2:file:" + dir.resolve("chat_history").toAbsolutePath()
                + ";LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE";
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername("sa");
        pool.setPassword("");
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE note (id INT PRIMARY KEY, text VARCHAR(20))");
        }
    }

    @AfterEach
    void closePool() {
        pool.close();
    }

    @Test
    void anOpenDatabaseIsClosedWithItsCommitsWritten() throws SQLException {
        // A connection the pool has lent out and that nobody gives back - the work a halt cuts off.
        Connection inUse = pool.getConnection();
        try (Statement s = inUse.createStatement()) {
            s.execute("INSERT INTO note VALUES (1, 'committed')");
        }

        assertTrue(H2CloseBeforeHalt.shutdown(pool));

        assertThrows(SQLException.class, () -> {
            try (Statement s = inUse.createStatement()) {
                s.executeQuery("SELECT 1");
            }
        }, "the database was closed under the connection still lent out");
        try (Connection fresh = DriverManager.getConnection(url, "sa", "");
             Statement s = fresh.createStatement();
             ResultSet rs = s.executeQuery("SELECT text FROM note WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals("committed", rs.getString(1));
        }
    }

    @Test
    void aDatabaseWhosePoolClosedIsLeftAlone() {
        // Closing the pool closed the database. Opening it again only to close it would spend part
        // of the halt's few seconds on a database that needs nothing.
        pool.close();
        assertFalse(H2CloseBeforeHalt.shutdown(pool));
        assertTrue(Files.exists(dir.resolve("chat_history.mv.db")));
    }

    @Test
    void onlyFileDatabasesAreClosedThisWay() {
        assertTrue(H2CloseBeforeHalt.isH2File("jdbc:h2:file:C:/data/local_folders;DB_CLOSE_ON_EXIT=FALSE"));
        assertFalse(H2CloseBeforeHalt.isH2File("jdbc:h2:mem:test"));
        assertFalse(H2CloseBeforeHalt.isH2File("jdbc:postgresql://localhost/proxyma"));
        assertFalse(H2CloseBeforeHalt.isH2File(null));
    }
}
