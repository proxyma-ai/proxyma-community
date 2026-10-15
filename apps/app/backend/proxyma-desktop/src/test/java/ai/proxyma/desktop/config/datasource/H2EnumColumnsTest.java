package ai.proxyma.desktop.config.datasource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An installation that has been through an earlier version accepts an enum constant added since.
 *
 * <p>0.22.0 added {@code NETWORK_SHARE} to ConnectionProtocol. On a database created by 0.21 the
 * column was an H2 {@code ENUM} of the five protocols that existed then, and adding a Network share
 * connection failed in the listing query before anything was written. The table below is that
 * column as 0.21 created it, with rows in it - an empty table would prove nothing, since a fresh
 * database is created with the new list.
 */
class H2EnumColumnsTest {

    // What a packaged jar reads from its build-info: a version and the moment it was built.
    private static final String BUILD = "0.22.1 2026-10-01T12:00:00Z";

    private DataSource dataSource;

    @BeforeEach
    void anInstallCreatedBeforeTheNewConstant() throws SQLException {
        // A database of its own per test, kept open by DB_CLOSE_DELAY while connections come and go.
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE connector_connections (id UUID PRIMARY KEY, "
                    + "owner_id VARCHAR(100) NOT NULL, name VARCHAR(100) NOT NULL, "
                    + "protocol ENUM('BITBUCKET','CONFLUENCE','JIRA','MICROSOFT_365','SVN') NOT NULL, "
                    + "status ENUM('CONNECTED','NEEDS_RECONNECT'), "
                    + "CONSTRAINT uk_owner_name UNIQUE (owner_id, name))");
            stmt.execute("INSERT INTO connector_connections VALUES (RANDOM_UUID(), '_local', 'Work Jira', 'JIRA', NULL)");
            stmt.execute("INSERT INTO connector_connections VALUES (RANDOM_UUID(), '_local', 'M365', 'MICROSOFT_365', 'CONNECTED')");
        }
    }

    @Test
    @DisplayName("before the conversion, even a SELECT naming the new constant is refused")
    void theFailureIsReal() {
        SQLException refused = assertThrows(SQLException.class, () -> countByProtocol("NETWORK_SHARE"));
        assertEquals(22030, refused.getErrorCode());
    }

    @Test
    @DisplayName("after it, the new constant can be queried and stored")
    void aNewConstantIsAccepted() throws SQLException {
        H2EnumColumns.relaxed(dataSource, BUILD);

        assertEquals(0, countByProtocol("NETWORK_SHARE"));
        insert("Team share", "NETWORK_SHARE");
        assertEquals(1, countByProtocol("NETWORK_SHARE"));
    }

    @Test
    @DisplayName("the rows already there keep their values")
    void existingRowsSurvive() throws SQLException {
        H2EnumColumns.relaxed(dataSource, BUILD);

        assertEquals(1, countByProtocol("JIRA"));
        assertEquals(1, countByProtocol("MICROSOFT_365"));
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT status FROM connector_connections WHERE name = 'M365'")) {
            rs.next();
            assertEquals("CONNECTED", rs.getString(1));
        }
    }

    @Test
    @DisplayName("a NOT NULL column stays NOT NULL, and a unique constraint still holds")
    void constraintsSurvive() {
        H2EnumColumns.relaxed(dataSource, BUILD);

        assertThrows(SQLException.class, () -> insert("No protocol", null));
        assertThrows(SQLException.class, () -> insert("Work Jira", "JIRA"));
    }

    @Test
    @DisplayName("the same build does not convert again, so a start does not rebuild every table")
    void oncePerBuild() throws SQLException {
        H2EnumColumns.relaxed(dataSource, BUILD);
        assertEquals(0, enumColumns());
        // What Hibernate's schema update does next: it finds a VARCHAR where it maps an enum and
        // declares the column an ENUM again, with the constants of the build that is running.
        hibernateRedeclares("'BITBUCKET','CONFLUENCE','JIRA','MICROSOFT_365','NETWORK_SHARE','SVN'");
        assertEquals(1, enumColumns());

        H2EnumColumns.relaxed(dataSource, BUILD);

        assertEquals(1, enumColumns(), "left as this build's own schema update declared it");
        assertEquals(0, countByProtocol("NETWORK_SHARE"));
    }

    @Test
    @DisplayName("a newer build converts again, because it may have added constants of its own")
    void aNewBuildConvertsAgain() throws SQLException {
        H2EnumColumns.relaxed(dataSource, BUILD);
        hibernateRedeclares("'BITBUCKET','CONFLUENCE','JIRA','MICROSOFT_365','NETWORK_SHARE','SVN'");

        H2EnumColumns.relaxed(dataSource, "0.23.0 2026-11-01T00:00:00Z");

        assertEquals(0, enumColumns());
        assertEquals(2, countByProtocol("JIRA") + countByProtocol("MICROSOFT_365"));
    }

    @Test
    @DisplayName("with no build to go by, every start converts - slower, never wrong")
    void anUnknownBuildAlwaysConverts() throws SQLException {
        H2EnumColumns.relaxed(dataSource, null);
        hibernateRedeclares("'BITBUCKET','CONFLUENCE','JIRA','MICROSOFT_365','SVN'");

        H2EnumColumns.relaxed(dataSource, null);

        assertEquals(0, enumColumns());
    }

    @Test
    @DisplayName("a database that cannot be reached does not stop the application here")
    void neverThrows() {
        DataSource unreachable = new DriverManagerDataSource("jdbc:h2:tcp://127.0.0.1:1/none", "sa", "");
        assertSame(unreachable, assertDoesNotThrow(() -> H2EnumColumns.relaxed(unreachable, BUILD)));
    }

    private int enumColumns() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                     + "WHERE TABLE_SCHEMA = 'PUBLIC' AND DATA_TYPE = 'ENUM'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void hibernateRedeclares(String constants) throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE connector_connections ALTER COLUMN protocol SET DATA TYPE ENUM(" + constants + ")");
        }
    }

    private int countByProtocol(String protocol) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM connector_connections WHERE owner_id = ? AND protocol = ?")) {
            ps.setString(1, "_local");
            ps.setString(2, protocol);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void insert(String name, String protocol) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO connector_connections VALUES (RANDOM_UUID(), '_local', ?, ?, NULL)")) {
            ps.setString(1, name);
            ps.setString(2, protocol);
            ps.executeUpdate();
        }
    }
}
