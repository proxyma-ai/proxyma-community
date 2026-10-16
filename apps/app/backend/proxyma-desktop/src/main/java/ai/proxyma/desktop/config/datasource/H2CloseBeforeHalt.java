package ai.proxyma.desktop.config.datasource;

import ai.proxyma.core.system.util.ProcessExit;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Closes Desktop's H2 databases when the process is about to be HALTED.
 *
 * <p>WHY, since the 0.36.0 feedback. The databases carry {@code DB_CLOSE_ON_EXIT=FALSE}: H2's own
 * JVM hook closed them under sync work still running, so they now close when Spring closes their
 * pools, after that work has stopped. But ProcessExit halts a shutdown that overruns, and a halt runs
 * no hook at all - the pools are never closed, and neither is any database still open. H2 then
 * loses whatever its last second of commits had not yet written and recovers the store on the next
 * open. Measured on the PC 2026-10-16: a restart halted with two of the four pools still open.
 *
 * <p>So each database registers a close with {@link ProcessExit#beforeHalt}: {@code SHUTDOWN}
 * through a connection of its own - not through the pool, which may be the very thing stuck - which
 * rolls back what is uncommitted, writes the rest and closes the files under every session. A
 * database whose pool has already closed is closed and left alone; IFEXISTS keeps a close from ever
 * creating one. ProcessExit runs each on a thread of its own and bounds them together.
 */
@Component
class H2CloseBeforeHalt {

    private static final Logger log = LoggerFactory.getLogger(H2CloseBeforeHalt.class);

    private final List<HikariDataSource> databases = new ArrayList<>();

    H2CloseBeforeHalt(List<DataSource> dataSources) {
        for (DataSource dataSource : dataSources) {
            HikariDataSource hikari = hikari(dataSource);
            if (hikari != null && isH2File(hikari.getJdbcUrl())) databases.add(hikari);
        }
    }

    @PostConstruct
    void register() {
        // Keyed by the database, so a context started again in the same JVM replaces its own.
        for (HikariDataSource database : databases) {
            ProcessExit.beforeHalt("h2 " + database.getJdbcUrl(), () -> shutdown(database));
        }
    }

    /** Closes the database behind {@code pool} unless the pool is closed already; true when it did. */
    static boolean shutdown(HikariDataSource pool) {
        if (pool.isClosed()) return false;
        try (Connection connection = DriverManager.getConnection(
                pool.getJdbcUrl() + ";IFEXISTS=TRUE", pool.getUsername(), pool.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
            log.info("[H2] {} closed before the halt", pool.getPoolName());
            return true;
        } catch (SQLException e) {
            log.warn("[H2] {} could not be closed before the halt: {}", pool.getPoolName(), e.getMessage());
            return false;
        }
    }

    static boolean isH2File(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:h2:file:");
    }

    private static HikariDataSource hikari(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) return hikari;
        try {
            return dataSource.isWrapperFor(HikariDataSource.class) ? dataSource.unwrap(HikariDataSource.class) : null;
        } catch (SQLException e) {
            return null;
        }
    }
}
