package ai.proxyma.core.settings.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * settings-connections-usage-09: an edit keeps the stored API key when none is sent, so whoever may
 * change a provider's base URL may send that key to a server of their choosing. Adding, changing and
 * removing a provider is a Super admin's on Enterprise Server, like every other credential surface;
 * an Admin still reads them. Refused before the service is touched.
 */
class ProviderWriteRoleTest {

    private static final UUID ID = UUID.randomUUID();

    private ProviderInstanceService service;
    private ProviderInstanceController controller;

    @BeforeEach
    void setUp() {
        service = mock(ProviderInstanceService.class);
        ProviderInstanceView view = new ProviderInstanceView(ID, "OpenAI", ProviderProtocol.OPENAI, "OpenAI",
                null, null, false, Map.of(), false);
        when(service.create(any())).thenReturn(view);
        when(service.update(any(), any())).thenReturn(view);
        when(service.get(ID)).thenReturn(view);
        controller = new ProviderInstanceController(service);
        ReflectionTestUtils.setField(controller, "multiUser", true);
    }

    private static ProviderInstanceController.ProviderRequest request() {
        return new ProviderInstanceController.ProviderRequest("OpenAI", "OPENAI", "https://attacker.example/v1",
                null, null, null, null, null, null);
    }

    private static int as(String role, Mono<? extends ResponseEntity<?>> call) {
        var auth = new UsernamePasswordAuthenticationToken("u-1", null, List.of(new SimpleGrantedAuthority(role)));
        ResponseEntity<?> r = call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
        return r.getStatusCode().value();
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.patch("/api/v1/providers/instances/" + ID));
    }

    @Test
    @DisplayName("an Admin can add, repoint or remove no provider, and nothing reaches the service")
    void adminIsRefused() {
        assertThat(as("ROLE_ADMIN", controller.create(request(), exchange()))).isEqualTo(403);
        assertThat(as("ROLE_ADMIN", controller.update(ID, request(), exchange()))).isEqualTo(403);
        assertThat(as("ROLE_ADMIN", controller.delete(ID, exchange()))).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a Super admin can")
    void superAdminMay() {
        assertThat(as("ROLE_SUPER_ADMIN", controller.create(request(), exchange()))).isEqualTo(200);
        assertThat(as("ROLE_SUPER_ADMIN", controller.update(ID, request(), exchange()))).isEqualTo(200);
        assertThat(as("ROLE_SUPER_ADMIN", controller.delete(ID, exchange()))).isEqualTo(204);
    }

    @Test
    @DisplayName("Desktop and Home Server have one user, who is every role")
    void singleUserMay() {
        ReflectionTestUtils.setField(controller, "multiUser", false);
        assertThat(controller.update(ID, request(), exchange()).block().getStatusCode().value()).isEqualTo(200);
        verify(service, never()).delete(any());
    }
}
