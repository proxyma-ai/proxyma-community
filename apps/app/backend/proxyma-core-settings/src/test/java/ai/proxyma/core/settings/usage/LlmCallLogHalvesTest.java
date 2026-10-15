package ai.proxyma.core.settings.usage;

import ai.proxyma.core.settings.pricing.ModelPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The call log and its summary are asked for apart (2026-10-07 feedback: "the table reloads very
 * slowly after changing the filter"). Every filter change, sort and page re-ran four aggregations
 * and read every call in the period for the chart; now the page asks for the summary once per
 * period and for the log alone as the reader filters and scrolls. These pin that each half does
 * only its own work - and that leaving {@code include} out still answers with both, which the
 * functional suite and older pages rely on.
 */
class LlmCallLogHalvesTest {

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

    private Map<String, Object> call(String include) {
        ResponseEntity<Map<String, Object>> response =
                controller.calls(30, 0, 50, "CHAT", null, null, null, null, null, null, include).block();
        return response == null ? Map.of() : response.getBody();
    }

    @Test
    @DisplayName("the log alone runs the one paged query and no aggregation")
    void logAloneSkipsTheSummary() {
        Map<String, Object> body = call("log");

        verify(repository).search(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
        verify(repository, never()).costPoints(any(), any(), any());
        verify(repository, never()).totalsByProvider(any(), any(), any());
        verify(repository, never()).totalsByModel(any(), any(), any());
        verify(repository, never()).totalsByRole(any(), any(), any());
        assertTrue(body.containsKey("items"));
        assertFalse(body.containsKey("series"));
    }

    @Test
    @DisplayName("the summary alone reads no page of calls")
    void summaryAloneSkipsTheLog() {
        Map<String, Object> body = call("summary");

        verify(repository, never()).search(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
        verify(repository).costPoints(any(), any(), any());
        assertFalse(body.containsKey("items"));
        assertTrue(body.containsKey("byModel"));
        assertTrue(body.containsKey("canClear"));
    }

    @Test
    @DisplayName("without include, both halves come back as they always did")
    void bothByDefault() {
        Map<String, Object> body = call(null);

        assertTrue(body.containsKey("items"));
        assertTrue(body.containsKey("series"));
        assertTrue(body.containsKey("byService"));
    }
}
