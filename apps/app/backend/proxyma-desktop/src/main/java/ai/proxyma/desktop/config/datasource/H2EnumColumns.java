package ai.proxyma.desktop.config.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Makes an H2 database that an earlier build created accept the enum constants this build added.
 *
 * <p>Hibernate maps {@code @Enumerated(EnumType.STRING)} to H2's native {@code ENUM(...)} type,
 * which lists the constants that existed on the day the column was created. Its schema update
 * compares column TYPES and finds an ENUM where it wants an ENUM, so the list is never brought up
 * to date - and H2 refuses a missing constant in every statement that names it, a SELECT included.
 * That is how 0.22.0 could not add a Network share connection on any install upgraded from 0.21:
 * {@code connector_connections.protocol} was created with five protocols, {@code NETWORK_SHARE}
 * was the sixth, and the listing query died with {@code Value not permitted for column} before
 * anything was written.
 *
 * <p>So the ENUM columns are turned into plain {@code VARCHAR}, on each datasource as it is built -
 * BEFORE its EntityManagerFactory exists, and so before any query can name a new constant. The
 * conversion keeps the values, the NOT NULL and the indexes.
 *
 * <p><b>Once per build, not once per start.</b> Hibernate's schema update DOES act on a type
 * mismatch: it finds the VARCHAR and declares the column an ENUM again, with the current
 * constants - measured on a database rehearsed into the 0.21 shape, where the column came back
 * listing all six protocols. That finishes the repair, and it also means a conversion on every
 * start would rebuild every such table twice at every launch, {@code chat_messages} among them.
 * A build's constants cannot change after it is built, so one pass per build is all it takes: the
 * build that last did it is recorded in the database itself ({@link #STAMP_TABLE}), where it stays
 * true of that file wherever the file is copied to.
 *
 * <p>It never throws: a database that will not answer here fails properly a moment later, where
 * the failure is reported.
 */
public final class H2EnumColumns {

    private static final Logger log = LoggerFactory.getLogger(H2EnumColumns.class);

    // Hibernate's own width for a string-mapped enum on a database without a native enum type.
    private static final int WIDTH = 255;

    /** One row: the build whose enum constants this database's columns were last brought up to. */
    static final String STAMP_TABLE = "PROXYMA_ENUM_BUILD";

    // What identifies THIS build: its version and the moment it was built, from the file
    // spring-boot's build-info goal writes into the jar. Null when there is none (classes run from
    // an IDE), and then every start converts - slower, never wrong.
    private static final String BUILD = readBuild();

    private H2EnumColumns() {}

    /** Brings the ENUM columns of {@code dataSource} up to this build if it is H2, and returns it. */
    public static DataSource relaxed(DataSource dataSource) {
        return relaxed(dataSource, BUILD);
    }

    static DataSource relaxed(DataSource dataSource, String build) {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase().contains("h2")) return dataSource;
            if (build != null && build.equals(stampedBuild(conn))) return dataSource;

            List<String[]> columns = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                         + "WHERE TABLE_SCHEMA = 'PUBLIC' AND DATA_TYPE = 'ENUM'")) {
                while (rs.next()) columns.add(new String[] {rs.getString(1), rs.getString(2)});
            }
            int failed = 0;
            for (String[] column : columns) {
                // SET DATA TYPE changes the type and nothing else. The shorter "ALTER COLUMN c
                // VARCHAR(n)" restates the whole definition and would drop the NOT NULL.
                String sql = "ALTER TABLE " + quoted(column[0]) + " ALTER COLUMN " + quoted(column[1])
                        + " SET DATA TYPE VARCHAR(" + WIDTH + ")";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                } catch (Exception e) {
                    failed++;
                    log.warn("[Schema] could not convert enum column {}.{}: {}", column[0], column[1], e.getMessage());
                }
            }
            if (!columns.isEmpty()) {
                log.info("[Schema] converted {} enum column(s) to VARCHAR for this build", columns.size() - failed);
            }
            // Not stamped when a column was left behind, so the next start tries it again.
            if (failed == 0 && build != null) stamp(conn, build);
        } catch (Exception e) {
            log.warn("[Schema] enum column check skipped: {}", e.getMessage());
        }
        return dataSource;
    }

    private static String stampedBuild(Connection conn) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT BUILD FROM " + STAMP_TABLE + " WHERE ID = 1")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            // No table yet: a database no build has stamped.
            return null;
        }
    }

    private static void stamp(Connection conn, String build) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + STAMP_TABLE + " (ID INT PRIMARY KEY, BUILD VARCHAR(255))");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO " + STAMP_TABLE + " (ID, BUILD) KEY (ID) VALUES (1, ?)")) {
            ps.setString(1, build);
            ps.executeUpdate();
        }
    }

    private static String readBuild() {
        try (InputStream in = H2EnumColumns.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in == null) return null;
            Properties info = new Properties();
            info.load(in);
            String time = info.getProperty("build.time");
            return time == null ? null : info.getProperty("build.version", "") + " " + time;
        } catch (Exception e) {
            return null;
        }
    }

    private static String quoted(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
