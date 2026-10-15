package ai.proxyma.core.chat.model;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A sub-agent's report and steps are PostgreSQL {@code text}, never {@code oid}.
 *
 * <p>The failure this pins (2026-10-06, Enterprise Server): both were {@code @Lob}, which Hibernate
 * maps to an {@code oid} large object on PostgreSQL - readable only inside the transaction that
 * fetched the row. Nothing that reads a run holds one, so taking a background sub-agent's report
 * threw "Unable to access lob stream", the report stayed owed, and the end of every turn woke the
 * conversation to deliver it again: a loop logging a stack trace several times a second, and a
 * main agent that never heard back. H2 reads a CLOB outside a transaction, so Desktop never showed it.
 *
 * <p>Asked of the column type Hibernate gives PostgreSQL, which is what decides the column on a
 * server install - no database needed.
 */
class SubAgentRunColumnsTest {

    @Test
    @DisplayName("on PostgreSQL the report and the steps are text columns, not large objects")
    void reportAndStepsAreText() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        try {
            Metadata metadata = new MetadataSources(registry).addAnnotatedClass(SubAgentRun.class).buildMetadata();
            PersistentClass run = metadata.getEntityBinding(SubAgentRun.class.getName());
            assertEquals("text", sqlType(metadata, run, "report"));
            assertEquals("text", sqlType(metadata, run, "itemsJson"));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    @DisplayName("the workflow runs' JSON columns are text too - the same class of bug, before it bites")
    void workflowColumnsAreText() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        try {
            Metadata metadata = new MetadataSources(registry)
                    .addAnnotatedClass(WorkflowRun.class)
                    .addAnnotatedClass(WorkflowGraphRun.class)
                    .addAnnotatedClass(WorkflowRunEvent.class)
                    .buildMetadata();
            PersistentClass run = metadata.getEntityBinding(WorkflowRun.class.getName());
            assertEquals("text", sqlType(metadata, run, "stepsJson"));
            PersistentClass graph = metadata.getEntityBinding(WorkflowGraphRun.class.getName());
            assertEquals("text", sqlType(metadata, graph, "nodeStatusJson"));
            assertEquals("text", sqlType(metadata, graph, "inputsJson"));
            assertEquals("text", sqlType(metadata, graph, "answerJson"));
            PersistentClass event = metadata.getEntityBinding(WorkflowRunEvent.class.getName());
            assertEquals("text", sqlType(metadata, event, "payloadJson"));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    @DisplayName("a feedback attachment's bytes are bytea in the row, so deleting the row deletes them")
    void feedbackAttachmentIsBytea() {
        // An oid large object is not deleted with the row that points at it: every screenshot and
        // log tail attached to a feedback outlived its deletion in pg_largeobject.
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        try {
            Metadata metadata = new MetadataSources(registry)
                    .addAnnotatedClass(Feedback.class)
                    .addAnnotatedClass(FeedbackAttachment.class)
                    .buildMetadata();
            PersistentClass attachment = metadata.getEntityBinding(FeedbackAttachment.class.getName());
            assertEquals("bytea", sqlType(metadata, attachment, "data"));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static String sqlType(Metadata metadata, PersistentClass entity, String property) {
        Column column = entity.getProperty(property).getColumns().get(0);
        return column.getSqlType(metadata).toLowerCase(Locale.ROOT);
    }
}
