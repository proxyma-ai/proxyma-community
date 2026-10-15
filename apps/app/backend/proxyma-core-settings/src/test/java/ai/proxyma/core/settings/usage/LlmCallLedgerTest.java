package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-call ledger: what gets written, and who is allowed to read whose rows.
 *
 * <p>The scope half is the one that matters. An ordinary user on Enterprise Server may see their
 * own calls and nobody else's, and the only thing standing between those two answers is that the
 * controller derives the subject from the authenticated principal rather than from a request
 * parameter. A filter taken from the client would be an access-control bug wearing a query
 * string.
 */
class LlmCallLedgerTest {

    private static final SpendSubject USER = SpendSubject.user("u-1");

    @Nested
    @DisplayName("what is written")
    class Writing {

        private LlmCallRepository repository;
        private LlmCallLogService service;

        @BeforeEach
        void setUp() {
            repository = mock(LlmCallRepository.class);
            // Enterprise Server: subjects are filed as they arrive. The single-user editions are
            // the test below.
            service = new LlmCallLogService(repository, true);
        }

        @Test
        @DisplayName("on Desktop and Home Server every call is filed under the one local user")
        void singleUserEditionsFileEverythingUnderOneId() {
            LlmCallLogService desktop = new LlmCallLogService(repository, false);
            TokenUsage usage = new TokenUsage(10, 2, 0, 0, 10);

            // A chat turn arrives as ANONYMOUS there and ingest as SYSTEM - one person either way.
            desktop.record(SpendSubject.ANONYMOUS, "CHAT", "Anthropic", "claude-sonnet-5", usage, 0.1);
            desktop.record(SpendSubject.SYSTEM, "EMBEDDING", "Ollama", "bge-m3", usage, 0.0);

            ArgumentCaptor<LlmCall> saved = ArgumentCaptor.forClass(LlmCall.class);
            verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
            for (LlmCall row : saved.getAllValues()) {
                assertEquals(SpendSubject.Kind.USER, row.getSubjectKind());
                assertEquals(SpendSubject.LOCAL_ID, row.getSubjectId());
            }
        }

        @Test
        @DisplayName("a call with tokens is written with its subject, service, names and cost")
        void writesTheCall() {
            TokenUsage usage = new TokenUsage(100, 20, 5, 1, 100);

            service.record(USER, "CHAT", "Anthropic", "claude-sonnet-5", usage, 0.42);

            ArgumentCaptor<LlmCall> saved = ArgumentCaptor.forClass(LlmCall.class);
            verify(repository).save(saved.capture());
            LlmCall row = saved.getValue();
            assertEquals(SpendSubject.Kind.USER, row.getSubjectKind());
            assertEquals("u-1", row.getSubjectId());
            assertEquals("CHAT", row.getRole());
            assertEquals("Anthropic", row.getProvider());
            assertEquals("claude-sonnet-5", row.getModel());
            assertEquals(100, row.getInputTokens());
            assertEquals(20, row.getOutputTokens());
            assertEquals(0.42, row.getCostUsd());
        }

        @Test
        @DisplayName("a call whose provider reported NO tokens is still written - it still happened")
        void writesZeroTokenCalls() {
            service.record(USER, "RERANK", "Local vLLM", "bge-reranker", TokenUsage.EMPTY, 0.0);

            // The rollup drops these; this table is the one place that can still answer "how many
            // rerank calls did we make", which is the question a bill raises.
            verify(repository).save(any(LlmCall.class));
        }

        @Test
        @DisplayName("a call with no provider or model is dropped rather than filed under a blank")
        void dropsUnlabelledCalls() {
            service.record(USER, "CHAT", "", "gpt-5", TokenUsage.EMPTY, 0.0);
            service.record(USER, "CHAT", "OpenAI", null, TokenUsage.EMPTY, 0.0);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a repository failure never escapes - the tokens were spent either way")
        void swallowsFailures() {
            when(repository.save(any())).thenThrow(new RuntimeException("db down"));

            service.record(USER, "CHAT", "OpenAI", "gpt-5", new TokenUsage(1, 1, 0, 0, 1), 0.01);
        }
    }

    @Nested
    @DisplayName("who may read whose")
    class Scope {

        private LlmCallRepository repository;
        private LlmCallsController controller;

        @BeforeEach
        void setUp() {
            repository = mock(LlmCallRepository.class);
            controller = new LlmCallsController(repository, mock(ModelPricingService.class));
            when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(Page.empty());
            when(repository.totalsByProvider(any(), any(), any())).thenReturn(List.of());
            when(repository.totalsByModel(any(), any(), any())).thenReturn(List.of());
            when(repository.totalsByRole(any(), any(), any())).thenReturn(List.of());
            when(repository.totalsBySubject(any(), any())).thenReturn(List.of());
            when(repository.costPoints(any(), any(), any())).thenReturn(List.of());
        }

        private Map<String, Object> callAs(boolean multiUser, String user, String role) {
            ReflectionTestUtils.setField(controller, "multiUser", multiUser);
            var call = controller.calls(30, 0, 50, null, null, null, null, null, null, null, null);
            if (user != null) {
                var auth = new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority(role)));
                call = call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
            }
            ResponseEntity<Map<String, Object>> response = call.block();
            return response == null ? Map.of() : response.getBody();
        }

        @Test
        @DisplayName("an ordinary user is scoped to their OWN id, whatever they asked for")
        void userSeesOnlyTheirOwn() {
            Map<String, Object> body = callAs(true, "u-42", "ROLE_USER");

            assertEquals("mine", body.get("scope"));
            verify(repository).search(any(), any(), eq("u-42"), any(), any(), any(), any(), any(Pageable.class));
            // The chart is drawn from the same scope as the table - it cannot be everyone's.
            verify(repository).costPoints(any(), any(), eq("u-42"));
            // The by-subject breakdown is the "who spent it" view, and it is not theirs to see.
            assertFalse(body.containsKey("bySubject"));
            // Nor is the cost-center map, which is the Administration page's.
            assertFalse(body.containsKey("costCenters"));
            verify(repository, never()).totalsBySubject(any(), any());
        }

        @Test
        @DisplayName("an admin sees everyone - no subject filter, and the who breakdown")
        void adminSeesEveryone() {
            Map<String, Object> body = callAs(true, "admin-1", "ROLE_ADMIN");

            assertEquals("all", body.get("scope"));
            verify(repository).search(any(), any(), isNull(), any(), any(), any(), any(), any(Pageable.class));
            assertTrue(body.containsKey("bySubject"));
            assertTrue(body.containsKey("costCenters"));
        }

        @Test
        @DisplayName("desktop has one user and no accounts, so it sees everything including SYSTEM")
        void desktopSeesEverything() {
            Map<String, Object> body = callAs(false, null, null);

            assertEquals("all", body.get("scope"));
            verify(repository).search(any(), any(), isNull(), any(), any(), any(), any(), any(Pageable.class));
        }

        @Test
        @DisplayName("the window is clamped, so nobody can ask for an unbounded scan")
        void windowIsClamped() {
            ReflectionTestUtils.setField(controller, "multiUser", false);
            controller.calls(100000, 0, 50, null, null, null, null, null, null, null, null).block();

            ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
            verify(repository).search(from.capture(), any(), isNull(), any(), any(), any(), any(), any(Pageable.class));
            assertTrue(from.getValue().isAfter(Instant.now().minusSeconds(366L * 24 * 3600)));
        }
    }

    @Nested
    @DisplayName("the cost-over-time series")
    class Series {

        private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

        @Test
        @DisplayName("every day of the window is a bucket, including the empty ones")
        void emptyDaysAreZeroColumns() {
            Instant now = Instant.parse("2026-09-24T05:00:00Z");
            var series = LlmCallsController.series(List.of(), now.minusSeconds(6 * 86400L), now, HCM, false);

            assertEquals(7, series.size());
            assertEquals("2026-09-18", series.get(0).get("bucket"));
            assertEquals("2026-09-24", series.get(6).get("bucket"));
            assertEquals(0.0, series.get(3).get("costUsd"));
        }

        @Test
        @DisplayName("a call lands on the READER's calendar day, not UTC's")
        void bucketsInTheReadersZone() {
            Instant now = Instant.parse("2026-09-24T05:00:00Z");
            // 20:00 UTC on the 22nd is 03:00 on the 23rd in Vietnam.
            Instant late = Instant.parse("2026-09-22T20:00:00Z");
            var series = LlmCallsController.series(
                    List.<Object[]>of(new Object[]{late, 1.5}, new Object[]{late, 0.5}),
                    now.minusSeconds(6 * 86400L), now, HCM, false);

            var day = series.stream().filter(m -> "2026-09-23".equals(m.get("bucket"))).findFirst().orElseThrow();
            assertEquals(2L, day.get("calls"));
            assertEquals(2.0, day.get("costUsd"));
        }

        @Test
        @DisplayName("each bucket says what users were charged, by the cost-center rule it is given")
        void splitsUserFromPlatform() {
            Instant now = Instant.parse("2026-09-24T05:00:00Z");
            Instant at = Instant.parse("2026-09-23T05:00:00Z");
            var series = LlmCallsController.series(
                    List.<Object[]>of(
                            new Object[]{at, 1.0, SpendSubject.Kind.USER, "CHAT"},
                            new Object[]{at, 2.0, SpendSubject.Kind.USER, "EMBEDDING"},
                            new Object[]{at, 4.0, SpendSubject.Kind.SYSTEM, "CHAT"}),
                    now.minusSeconds(6 * 86400L), now, HCM, false,
                    (kind, role) -> kind != SpendSubject.Kind.SYSTEM && "CHAT".equals(role));

            var day = series.stream().filter(m -> "2026-09-23".equals(m.get("bucket"))).findFirst().orElseThrow();
            assertEquals(7.0, day.get("costUsd"));
            assertEquals(1.0, day.get("userUsd"));
        }

        @Test
        @DisplayName("a long window is one column per month")
        void monthlyBuckets() {
            Instant now = Instant.parse("2026-09-24T05:00:00Z");
            var series = LlmCallsController.series(
                    List.<Object[]>of(new Object[]{Instant.parse("2026-03-10T00:00:00Z"), 3.0}),
                    now.minusSeconds(365 * 86400L), now, HCM, true);

            assertEquals("2025-09", series.get(0).get("bucket"));
            assertEquals("2026-09", series.get(series.size() - 1).get("bucket"));
            var march = series.stream().filter(m -> "2026-03".equals(m.get("bucket"))).findFirst().orElseThrow();
            assertEquals(3.0, march.get("costUsd"));
        }

        @Test
        @DisplayName("an unknown zone falls back to UTC rather than failing the page")
        void unknownZone() {
            assertEquals(ZoneId.of("Z"), LlmCallsController.zone("Mars/Olympus"));
            assertEquals(ZoneId.of("Z"), LlmCallsController.zone(null));
        }
    }
}
