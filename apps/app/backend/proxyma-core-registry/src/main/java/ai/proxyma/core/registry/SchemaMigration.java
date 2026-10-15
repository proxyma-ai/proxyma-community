package ai.proxyma.core.registry;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Lets a ConnectorType constant added after a PostgreSQL table was created be written to it.
 *
 * <p>Hibernate creates {@code @Enumerated(STRING)} columns on PostgreSQL with an inline CHECK listing
 * the constants that existed at that moment, and {@code ddl-auto=update} never rewrites it - so a new
 * connector type is refused on Enterprise Server until the check goes. PostgreSQL names an unnamed
 * check {@code {table}_{column}_check}, so it is dropped by name. Desktop and Home Server run H2,
 * whose ENUM columns H2EnumColumns repairs once per build; nothing is done here for H2.
 *
 * <p><b>THIS CLASS USED TO REBUILD connector_type ON H2 AT EVERY START</b> - add a VARCHAR(20) copy,
 * fill it, drop the original, rename - for a LOCAL_FILE constant retired long before. After the
 * first swap the copy column no longer existed, so it ran on every start: the whole of both tables
 * rewritten, their connector_type index dropped, and the column left at 20 characters. When the
 * copy failed (a constant longer than 20, or an ENUM column refusing the literal 'LOCAL_FILE') the
 * DROP still ran and every source and document lost its type. Removed on 2026-10-14 with the
 * column additions beside it, which every entity now declares (every install is a first install).
 * Never DROP a column here after a statement whose failure is swallowed.
 */
@Component("connectorSchemaMigration")
class SchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private final DataSource dataSource;

    SchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void migrate() {
        if (!isPostgres()) return;
        for (String table : new String[] {"registered_connectors", "tracked_documents"}) {
            runQuietly("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + table + "_connector_type_check");
        }
    }

    private boolean isPostgres() {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase().contains("postgres");
        } catch (Exception e) {
            return false;
        }
    }

    private void runQuietly(String sql) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            log.debug("[SchemaMigration] Statement skipped ({}): {}", sql, e.getMessage());
        }
    }
}
