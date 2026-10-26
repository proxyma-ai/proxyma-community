package ai.proxyma.core.chat.controller;

import ai.proxyma.core.search.service.AgentMemoryService;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryCategory;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /api/v1/memory - the composer's "Remember something" (2026-10-23). It writes through the
 * agent's own remember path, into the caller's memory and nobody else's: Knowledge unless told it is
 * about the user, nothing for a blank text or an unknown kind, and nothing at all for a visitor to a
 * multi-user server.
 */
class AgentMemoryCreateTest {

    private AgentMemoryService memory;
    private AgentMemoryController controller;

    @BeforeEach
    void setUp() {
        memory = mock(AgentMemoryService.class);
        controller = new AgentMemoryController(memory);
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/memory"));
    }

    @Test
    @DisplayName("Desktop's one user: saved as Knowledge by default and read back")
    void savesAsKnowledge() {
        MemoryEntry entry = mock(MemoryEntry.class);
        when(memory.remember(AgentMemoryService.LOCAL_USER_ID, "Release branch is release/next", MemoryCategory.KNOWLEDGE))
                .thenReturn("m-1");
        when(memory.read(AgentMemoryService.LOCAL_USER_ID, "m-1")).thenReturn(Optional.of(entry));

        ResponseEntity<MemoryEntry> response = controller
                .create(new AgentMemoryController.CreateRequest("Release branch is release/next", null), exchange())
                .block();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(entry);
    }

    @Test
    @DisplayName("\"About you\" is filed as USER")
    void aboutYou() {
        when(memory.remember(any(), any(), any())).thenReturn("m-2");
        when(memory.read(any(), any())).thenReturn(Optional.of(mock(MemoryEntry.class)));

        controller.create(new AgentMemoryController.CreateRequest("I prefer short answers", "user"), exchange()).block();

        verify(memory).remember(AgentMemoryService.LOCAL_USER_ID, "I prefer short answers", MemoryCategory.USER);
    }

    @Test
    @DisplayName("a blank text or an unknown kind is refused and nothing is written")
    void refusals() {
        assertThat(controller.create(new AgentMemoryController.CreateRequest("  ", null), exchange())
                .block().getStatusCode().value()).isEqualTo(400);
        assertThat(controller.create(new AgentMemoryController.CreateRequest("fact", "SECRET"), exchange())
                .block().getStatusCode().value()).isEqualTo(400);
        verify(memory, never()).remember(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a visitor to a multi-user server writes nothing")
    void visitorOnServer() {
        ReflectionTestUtils.setField(controller, "multiUser", true);

        assertThat(controller.create(new AgentMemoryController.CreateRequest("fact", null), exchange())
                .block().getStatusCode().value()).isEqualTo(401);
        verify(memory, never()).remember(anyString(), anyString(), any());
    }
}
