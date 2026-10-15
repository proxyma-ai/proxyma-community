package ai.proxyma.core.settings.usage;

import ai.proxyma.core.settings.pricing.ModelPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-call log's filters, search and sort (0.28.0 feedback: "add filters and sort").
 *
 * <p>The log is PAGED on the server, fifty calls at a time, so sorting or searching it in the
 * browser would sort one page and look like it sorted the period. These pin that both reach the
 * query: the sort as a whitelisted property on the pageable - never a client string passed through
 * as a property path - and the search as an escaped, lower-cased LIKE pattern. And that neither
 * loosens the scope: a user's search is still a search of their own calls.
 */
class LlmCallLogQueryTest {

    private LlmCallRepository repository;
    private LlmCallsController controller;

    @BeforeEach
    void setUp() {
        repository = mock(LlmCallRepository.class);
        controller = new LlmCallsController(repository, mock(ModelPricingService.class));
        ReflectionTestUtils.setField(controller, "multiUser", false);
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(repository.totalsByProvider(any(), any(), any())).thenReturn(List.of());
        when(repository.totalsByModel(any(), any(), any())).thenReturn(List.of());
        when(repository.totalsByRole(any(), any(), any())).thenReturn(List.of());
        when(repository.totalsBySubject(any(), any())).thenReturn(List.of());
        when(repository.costPoints(any(), any(), any())).thenReturn(List.of());
    }

    private Pageable pageableFor(String sort, String dir) {
        controller.calls(30, 0, 50, null, null, null, null, null, sort, dir, null).block();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(any(), any(), any(), any(), any(), any(), any(), pageable.capture());
        return pageable.getValue();
    }

    @Test
    @DisplayName("with no sort asked for, the log is newest first, as it always was")
    void defaultIsNewestFirst() {
        assertEquals(Sort.by(Sort.Direction.DESC, "recordedAt"), pageableFor(null, null).getSort());
    }

    @Test
    @DisplayName("a column sorts the whole window, ending on the time so ties keep their order")
    void columnSortsOnTheServer() {
        assertEquals(Sort.by(Sort.Direction.DESC, "costUsd").and(Sort.by(Sort.Direction.DESC, "recordedAt")),
                pageableFor("cost", "desc").getSort());
        assertEquals(Sort.by(Sort.Direction.ASC, "inputTokens").and(Sort.by(Sort.Direction.DESC, "recordedAt")),
                LlmCallsController.order("in", "asc"));
        assertEquals(Sort.by(Sort.Direction.DESC, "cacheReadTokens").and(Sort.by(Sort.Direction.DESC, "recordedAt")),
                LlmCallsController.order("cacheRead", null));
    }

    @Test
    @DisplayName("oldest first is the time column ascending")
    void timeAscending() {
        assertEquals(Sort.by(Sort.Direction.ASC, "recordedAt"), LlmCallsController.order("time", "asc"));
    }

    @Test
    @DisplayName("a model sorts with its provider, which tells two same-named models apart")
    void modelSortsWithProvider() {
        assertEquals(Sort.by(Sort.Direction.ASC, "model")
                        .and(Sort.by(Sort.Direction.ASC, "provider"))
                        .and(Sort.by(Sort.Direction.DESC, "recordedAt")),
                LlmCallsController.order("model", "asc"));
    }

    @Test
    @DisplayName("a sort that is not a column is ignored - no client string becomes a property path")
    void unknownSortIsIgnored() {
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "recordedAt");
        assertEquals(newestFirst, pageableFor("subjectId", "asc").getSort());
        assertEquals(newestFirst, LlmCallsController.order("id); drop table x", "desc"));
        assertEquals(newestFirst, LlmCallsController.order("recordedAt", "asc"));
    }

    @Test
    @DisplayName("the search is lower-cased and wrapped, and its own wildcards match only themselves")
    void searchIsAnEscapedPattern() {
        assertNull(LlmCallsController.likePattern(null));
        assertNull(LlmCallsController.likePattern("   "));
        assertEquals("%gpt-4o%", LlmCallsController.likePattern("  GPT-4o "));
        assertEquals("%gpt\\_4%", LlmCallsController.likePattern("gpt_4"));
        assertEquals("%100\\%%", LlmCallsController.likePattern("100%"));
        assertEquals(202, LlmCallsController.likePattern("x".repeat(500)).length());
    }

    @Test
    @DisplayName("the search and the filters reach the query together")
    void searchAndFiltersReachTheQuery() {
        controller.calls(30, 0, 50, "CHAT", "OpenAI", "gpt-4o", null, "Mini", "cost", "desc", null).block();
        verify(repository).search(any(), any(), any(), eq("CHAT"), eq("OpenAI"), eq("gpt-4o"), eq("%mini%"), any(Pageable.class));
    }

    @Test
    @DisplayName("a user's search is still a search of their own calls")
    void searchKeepsTheScope() {
        ReflectionTestUtils.setField(controller, "multiUser", true);
        var auth = new UsernamePasswordAuthenticationToken("u-7", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        controller.calls(30, 0, 50, null, null, null, null, "u-8", null, null, null)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();
        verify(repository).search(any(), any(), eq("u-7"), any(), any(), any(), eq("%u-8%"), any(Pageable.class));
    }
}
