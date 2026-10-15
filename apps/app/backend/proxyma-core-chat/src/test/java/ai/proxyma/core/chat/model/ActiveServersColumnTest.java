package ai.proxyma.core.chat.model;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Conversation.activeServers is written only by its own JPQL UPDATE (the same statement as
 * ConversationRepository.updateActiveServers), never by saving the entity.
 *
 * <p>The failure this pins: useServer runs as a tool call in parallel with the turn's own saves.
 * A Conversation loaded before useServer's UPDATE and saved after it (setUnlockedSkillNames does a
 * findById and a save) wrote the old active_servers back, so the server the user picked was
 * silently forgotten.
 */
class ActiveServersColumnTest {

    private static final String UPDATE =
            "UPDATE Conversation c SET c.activeServers = :value WHERE c.id = :id";

    private static SessionFactory sessions;

    @BeforeAll
    static void boot() {
        sessions = new Configuration()
                .addAnnotatedClass(Conversation.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:active-servers;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    private static void insert(String id) {
        sessions.inTransaction(s -> {
            Conversation c = new Conversation();
            c.setId(id);
            c.setTitle("t");
            c.setCreatedAt(Instant.now());
            c.setUpdatedAt(Instant.now());
            s.persist(c);
        });
    }

    private static void pick(String id, String value) {
        sessions.inTransaction(s -> s.createMutationQuery(UPDATE)
                .setParameter("value", value).setParameter("id", id).executeUpdate());
    }

    private static Conversation load(String id) {
        return sessions.fromTransaction(s -> s.find(Conversation.class, id));
    }

    @Test
    @DisplayName("the targeted UPDATE writes the column")
    void targetedUpdateWrites() {
        insert("c1");
        assertNull(load("c1").getActiveServers());
        pick("c1", "JIRA=cloud");
        assertEquals("JIRA=cloud", load("c1").getActiveServers());
    }

    @Test
    @DisplayName("saving a Conversation loaded before the UPDATE does not write the old value back")
    void staleWholeRowSaveKeepsThePick() {
        insert("c2");
        Conversation stale = load("c2");

        pick("c2", "JIRA=cloud");

        stale.setUnlockedSkillNames("jira");
        sessions.inTransaction(s -> s.merge(stale));

        Conversation now = load("c2");
        assertEquals("JIRA=cloud", now.getActiveServers());
        assertEquals("jira", now.getUnlockedSkillNames());
    }
}
