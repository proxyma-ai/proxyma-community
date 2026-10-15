package ai.proxyma.core.registry;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connector schema survives a start.
 *
 * <p>The connector module's startup migration rebuilt connector_type on H2 at EVERY start - a
 * VARCHAR(20) copy, filled, the original dropped, the copy renamed - and dropped the column even
 * when the copy failed. On the ENUM column Hibernate 6.6 creates there, the copy's comparison with a
 * retired constant is refused, so every source and document of a Desktop or Home Server install
 * lost its type on its second start. Run twice here against a populated H2 database built from the
 * entities that ship.
 */
class ConnectorSchemaMigrationTest {

    private String url;
    private SessionFactory sessions;
    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:connector-schema-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        sessions = new Configuration()
                .addAnnotatedClass(RegisteredConnector.class)
                .addAnnotatedClass(TrackedDocument.class)
                .setProperty("hibernate.connection.url", url)
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create")
                // Spring Boot's naming, so the columns are the snake_case ones a real install has.
                .setProperty("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .buildSessionFactory();
        dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        dataSource.setUser("sa");
    }

    @AfterEach
    void tearDown() throws Exception {
        sessions.close();
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SHUTDOWN");
        }
    }

    @Test
    @DisplayName("two starts leave every source's and document's type, and the type index, in place")
    void twoStartsKeepTheTypes() throws Exception {
        UUID sourceId = UUID.randomUUID();
        sessions.inTransaction(s -> {
            RegisteredConnector source = new RegisteredConnector();
            source.setConnectorType(ConnectorType.NETWORK_SHARE);
            source.setName("Contracts");
            source.setStatus(RegisteredConnector.Status.ACTIVE);
            source.setCreatedAt(Instant.now());
            s.persist(source);
            TrackedDocument doc = new TrackedDocument();
            doc.setConnectorId(sourceId);
            doc.setConnectorType(ConnectorType.UPLOADED_FILES);
            doc.setSourceId("/data/uploads/Contracts/msa.pdf");
            doc.setTitle("msa.pdf");
            doc.setStatus(TrackedDocument.Status.COMPLETED);
            doc.setSourceLastModified(Instant.now());
            s.persist(doc);
        });

        SchemaMigration migration = new SchemaMigration(dataSource);
        migration.migrate();
        migration.migrate();

        assertThat(column("select connector_type from registered_connectors")).containsExactly("NETWORK_SHARE");
        assertThat(column("select connector_type from tracked_documents")).containsExactly("UPLOADED_FILES");
        assertThat(column("select index_name from information_schema.indexes "
                + "where lower(table_name) = 'registered_connectors'"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_res_connector"));
    }

    private List<String> column(String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            while (rs.next()) values.add(rs.getString(1));
        }
        return values;
    }
}
