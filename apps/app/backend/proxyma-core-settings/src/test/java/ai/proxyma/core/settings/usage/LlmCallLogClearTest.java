package ai.proxyma.core.settings.usage;

import ai.proxyma.core.settings.pricing.ModelPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Clearing the call log (2026-10-07 feedback: "there is no way to clear/reset the logs").
 *
 * <p>The log is also what the monthly spending limit counts, so WHO may clear it is the point: the
 * one local user on Desktop and Home Server, whose calls these all are; on Enterprise Server a
 * Super admin and nobody else - an Admin reads everyone's calls and may not erase them, and a user
 * clearing their own would be resetting the deployment's count.
 */
class LlmCallLogClearTest {

    private LlmCallRepository repository;
    private LlmCallsController controller;

    @BeforeEach
    void setUp() {
        repository = mock(LlmCallRepository.class);
        controller = new LlmCallsController(repository, mock(ModelPricingService.class));
        when(repository.deleteAllCalls()).thenReturn(12);
    }

    private ResponseEntity<Map<String, Object>> clearAs(boolean multiUser, String role) {
        ReflectionTestUtils.setField(controller, "multiUser", multiUser);
        var call = controller.clear();
        if (role != null) {
            var auth = new UsernamePasswordAuthenticationToken("someone", null, List.of(new SimpleGrantedAuthority(role)));
            call = call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        }
        return call.block();
    }

    @Test
    @DisplayName("Desktop and Home Server clear the whole log")
    void localUserClears() {
        ResponseEntity<Map<String, Object>> response = clearAs(false, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(12, response.getBody().get("deleted"));
        verify(repository).deleteAllCalls();
    }

    @Test
    @DisplayName("on Enterprise Server a Super admin clears it")
    void superAdminClears() {
        assertEquals(200, clearAs(true, "ROLE_SUPER_ADMIN").getStatusCode().value());
        verify(repository).deleteAllCalls();
    }

    @Test
    @DisplayName("an Admin, a user and a signed-out visitor are refused, and nothing is deleted")
    void everyoneElseIsRefused() {
        assertEquals(403, clearAs(true, "ROLE_ADMIN").getStatusCode().value());
        assertEquals(403, clearAs(true, "ROLE_USER").getStatusCode().value());
        ReflectionTestUtils.setField(controller, "multiUser", true);
        assertEquals(403, controller.clear().block().getStatusCode().value());
        verify(repository, never()).deleteAllCalls();
    }
}
