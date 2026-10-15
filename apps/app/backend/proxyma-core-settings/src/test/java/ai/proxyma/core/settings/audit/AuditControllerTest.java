package ai.proxyma.core.settings.audit;

import ai.proxyma.core.common.audit.Audit;
import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who reads the Audit Logs page and who may empty it.
 *
 * <p>On Enterprise Server the entries are every user's tool calls and every sign-in, so an
 * administrator reads them and nobody else - a tool call's arguments and result only a Super admin
 * (settings-core-11); and nobody clears them, since a
 * log its readers can empty records nothing they would not want it to. On Desktop and Home Server
 * the one local user does both, and a clear is itself recorded.
 */
class AuditControllerTest {

    private AuditEventRepository repository;
    private AuditController controller;

    @BeforeEach
    void setUp() {
        Audit.drain(Integer.MAX_VALUE);
        repository = mock(AuditEventRepository.class);
        Page<AuditEvent> empty = new PageImpl<>(List.of());
        when(repository.search(any(), any(), anyCollection(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(empty);
        when(repository.deleteByCategories(anyCollection())).thenReturn(7);
        AuditWriter writer = mock(AuditWriter.class);
        controller = new AuditController(repository, writer, new ObjectMapper());
    }

    // A supplier, so the edition is set BEFORE the controller builds its answer - it reads the flag
    // as the call is made, not as the Mono is subscribed.
    private <T> T as(boolean multiUser, String role, Supplier<Mono<T>> request) {
        ReflectionTestUtils.setField(controller, "multiUser", multiUser);
        Mono<T> call = request.get();
        if (role == null) return call.block();
        var auth = new UsernamePasswordAuthenticationToken("someone", null, List.of(new SimpleGrantedAuthority(role)));
        return call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
    }

    private Mono<ResponseEntity<Map<String, Object>>> events() {
        return controller.events("TOOL", null, 30, 0, 50, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("on Enterprise Server an Admin and a Super admin read it, a user and a visitor do not")
    void onlyAdministratorsRead() {
        assertEquals(200, as(true, "ROLE_ADMIN", this::events).getStatusCode().value());
        assertEquals(200, as(true, "ROLE_SUPER_ADMIN", this::events).getStatusCode().value());
        assertEquals(403, as(true, "ROLE_USER", this::events).getStatusCode().value());
        assertEquals(403, as(true, null, this::events).getStatusCode().value());
    }

    private AuditEvent stored(String category) {
        AuditEvent e = new AuditEvent();
        e.setId(java.util.UUID.randomUUID());
        e.setOccurredAt(java.time.Instant.now());
        e.setCategory(category);
        e.setAction("tool.called");
        e.setOutcome("SUCCESS");
        e.setDetail("{\"result\":\"a passage from alice's private source\"}");
        when(repository.findById(e.getId())).thenReturn(java.util.Optional.of(e));
        return e;
    }

    @Test
    @DisplayName("a tool call's arguments and result are a Super admin's; an Admin sees the row without them")
    void toolDetailIsSuperAdminOnly() {
        AuditEvent tool = stored(AuditCategory.TOOL);

        Map<String, Object> admin = as(true, "ROLE_ADMIN", () -> controller.event(tool.getId().toString())).getBody();
        Map<String, Object> superAdmin = as(true, "ROLE_SUPER_ADMIN", () -> controller.event(tool.getId().toString())).getBody();

        assertNull(admin.get("detail"));
        assertEquals(true, admin.get("detailWithheld"));
        assertEquals(Map.of("result", "a passage from alice's private source"), superAdmin.get("detail"));
    }

    @Test
    @DisplayName("other categories' detail stays an Admin's read, and the local user reads everything")
    void otherDetailIsAnAdminsRead() {
        AuditEvent signIn = stored(AuditCategory.SIGN_IN);
        AuditEvent tool = stored(AuditCategory.TOOL);

        assertEquals(Map.of("result", "a passage from alice's private source"),
                as(true, "ROLE_ADMIN", () -> controller.event(signIn.getId().toString())).getBody().get("detail"));
        assertEquals(Map.of("result", "a passage from alice's private source"),
                as(false, null, () -> controller.event(tool.getId().toString())).getBody().get("detail"));
    }

    @Test
    @DisplayName("Desktop and Home Server read it with no account at all")
    void localUserReads() {
        assertEquals(200, as(false, null, this::events).getStatusCode().value());
    }

    @Test
    @DisplayName("nobody clears it on Enterprise Server - not even a Super admin")
    void noClearOnEnterprise() {
        ResponseEntity<Map<String, Object>> r = as(true, "ROLE_SUPER_ADMIN", () -> controller.clear("TOOL"));
        assertEquals(403, r.getStatusCode().value());
        verify(repository, never()).deleteByCategories(anyCollection());
    }

    @Test
    @DisplayName("the local user clears a tab, and the clear is the next entry recorded")
    void localClearIsRecorded() {
        ResponseEntity<Map<String, Object>> r = as(false, null, () -> controller.clear("GUARDRAIL"));

        assertEquals(200, r.getStatusCode().value());
        verify(repository).deleteByCategories(List.of(AuditCategory.GUARDRAIL));
        List<AuditEntry> recorded = Audit.drain(10);
        assertEquals(1, recorded.size());
        assertEquals(AuditCategory.DATA, recorded.get(0).category());
        assertEquals("audit.cleared", recorded.get(0).action());
        assertEquals(7, recorded.get(0).detail().get("entries"));
    }

    @Test
    @DisplayName("a tab is its own categories: an unknown tab is refused, and a category from another tab is ignored")
    void tabsAreClosedSets() {
        assertNull(AuditController.categories("NOPE", null));
        assertEquals(List.of(AuditCategory.SIGN_IN), AuditController.categories("activity", "sign_in"));
        // TOOL is not an Activity category; asking for it there returns the whole tab, not tool calls.
        assertEquals(AuditCategory.ACTIVITY, AuditController.categories("ACTIVITY", "TOOL"));
        assertEquals(400, controller.events("BOGUS", null, 30, 0, 50, null, null, null, null, null, null, null, null)
                .block().getStatusCode().value());
    }

    @Test
    @DisplayName("only whitelisted columns sort, and ties fall back to newest first")
    void sortIsWhitelisted() {
        assertEquals(Sort.by(Sort.Direction.DESC, "occurredAt"), AuditController.order("password", "asc"));
        assertEquals(Sort.by(Sort.Direction.ASC, "target").and(Sort.by(Sort.Direction.DESC, "occurredAt")),
                AuditController.order("target", "asc"));
    }

    @Test
    @DisplayName("the search box's own wildcards are escaped")
    void searchIsEscaped() {
        assertNull(AuditController.likePattern("  "));
        assertEquals("%gpt\\_4\\%%", AuditController.likePattern("GPT_4%"));
    }

    @Test
    @DisplayName("percentiles are taken from the durations in the window")
    void latency() {
        Map<String, Object> l = AuditController.latency(List.of(5L, 1L, 3L, 2L, 4L, 100L, 6L, 7L, 8L, 9L));
        assertEquals(10, l.get("samples"));
        assertEquals(5L, l.get("p50"));
        assertEquals(9L, l.get("p95"));
        assertEquals(100L, l.get("max"));
        assertEquals(Map.of("samples", 0), AuditController.latency(List.of()));
    }
}
