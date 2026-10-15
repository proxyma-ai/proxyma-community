package ai.proxyma.core.system.event;

import ai.proxyma.core.system.event.EventSinkService.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who receives a page-wide event. The audience form was added for key accounts (2026-10-06), whose
 * changes must reach the account's members and nobody else - the stream itself is permitAll.
 */
class EventScopeAudienceTest {

    @Test
    @DisplayName("an audience reaches exactly its members - not a stranger, not an anonymous subscriber")
    void audienceIsTheWholeAnswer() {
        Scope scope = Scope.audience(List.of("alice", "bob"));
        assertTrue(scope.visibleTo("alice"));
        assertTrue(scope.visibleTo("bob"));
        assertFalse(scope.visibleTo("mallory"));
        assertFalse(scope.visibleTo(null));
    }

    @Test
    @DisplayName("an empty audience reaches nobody")
    void emptyAudienceReachesNobody() {
        Scope scope = Scope.audience(List.of());
        assertFalse(scope.visibleTo("alice"));
        assertFalse(scope.visibleTo(null));
    }

    @Test
    @DisplayName("the owner and shared forms behave as they did before the audience existed")
    void ownerAndSharedUnchanged() {
        assertTrue(Scope.GLOBAL.visibleTo(null));
        assertTrue(Scope.GLOBAL.visibleTo("anyone"));
        Scope owned = new Scope("alice", false);
        assertTrue(owned.visibleTo("alice"));
        assertFalse(owned.visibleTo("bob"));
        assertFalse(owned.visibleTo(null));
        assertTrue(new Scope("alice", true).visibleTo("bob"));
    }
}
