package ai.proxyma.core.settings.usage;

import ai.proxyma.core.settings.pricing.ModelPricingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who reads what was spent, by the two doors LlmCallLedgerTest does not walk.
 *
 * <ul>
 *   <li>authz-01: {@code /api/v1/usage/chat-cost} is the whole deployment's spend - this month and
 *       all time, by provider and model - and sat on the blanket authenticated rule, readable by
 *       every Viewer, beside the Usages page SEC-44 locked to administrators.</li>
 *   <li>settings-connections-usage-13: the call log's EXPORT is a second place the "own rows only"
 *       rule is enforced, by the subject the controller passes down.</li>
 * </ul>
 */
class SpendReadScopeTest {

    private static <T> ResponseEntity<T> as(String user, String role, Mono<ResponseEntity<T>> call) {
        if (user == null) return call.block();
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority(role)));
        return call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
    }

    @Test
    @DisplayName("the deployment's chat spend: an administrator's read on Enterprise Server, the local user's elsewhere")
    void chatCostIsAnAdministratorsRead() {
        ChatSpendService spend = mock(ChatSpendService.class);
        when(spend.summary()).thenReturn(new ChatCostSummary("2026-10", 1, 2, 3, 4, List.of(), List.of(), 0));
        ChatCostController controller = new ChatCostController(spend);

        ReflectionTestUtils.setField(controller, "multiUser", true);
        assertEquals(403, as("v-1", "ROLE_VIEWER", controller.summary()).getStatusCode().value());
        assertEquals(403, as("u-1", "ROLE_USER", controller.summary()).getStatusCode().value());
        assertEquals(403, as(null, null, controller.summary()).getStatusCode().value());
        assertEquals(200, as("a-1", "ROLE_ADMIN", controller.summary()).getStatusCode().value());

        ReflectionTestUtils.setField(controller, "multiUser", false);
        assertEquals(200, as(null, null, controller.summary()).getStatusCode().value());
    }

    @Test
    @DisplayName("a user's export of the call log is their own calls; an administrator's is everyone's")
    void exportIsScopedLikeThePage() {
        LlmCallRepository repository = mock(LlmCallRepository.class);
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());
        LlmCallsController controller = new LlmCallsController(repository, mock(ModelPricingService.class));
        ReflectionTestUtils.setField(controller, "multiUser", true);

        as("u-42", "ROLE_USER", controller.export(30, null, null, null, null, null, null, "csv"));
        verify(repository).search(any(), any(), eq("u-42"), any(), any(), any(), any(), any(Pageable.class));
        verify(repository, never()).search(any(), any(), isNull(), any(), any(), any(), any(), any(Pageable.class));

        as("a-1", "ROLE_ADMIN", controller.export(30, null, null, null, null, null, null, "json"));
        verify(repository).search(any(), any(), isNull(), any(), any(), any(), any(), any(Pageable.class));
    }
}
