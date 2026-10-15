package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.LlmSpendRecordedEvent;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.settings.dto.QuotaSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The monthly limit totals the per-call ledger (2026-09-29), which every model call reaches - so on
 * desktop it counts chat, guardrail, workflow and rerank spend, none of which it saw before.
 */
class GlobalQuotaFromLedgerTest {

    @Test
    @DisplayName("the ceiling counts everything in the ledger, user and system alike")
    void countsTheLedger() {
        SettingsService settings = mock(SettingsService.class);
        QuotaSettings q = new QuotaSettings();
        q.setGlobalMonthlyQuotaUsd(10);
        when(settings.getSettings(eq(QuotaSettings.PREFIX), eq(QuotaSettings.class))).thenReturn(q);
        LlmCallRepository calls = mock(LlmCallRepository.class);
        when(calls.costBySubjectKindAndRoleSince(any())).thenReturn(List.of(
                new Object[]{SpendSubject.Kind.USER, "CHAT", 8.0},
                new Object[]{SpendSubject.Kind.USER, "EMBEDDING", 1.5},
                new Object[]{SpendSubject.Kind.SYSTEM, "GRAPH", 1.0}));

        GlobalQuotaService.GlobalQuotaStatus status = new GlobalQuotaService(settings, calls).status();

        // Chat is the user's by default; a user's embedding is the platform's (CostCenterSettings).
        assertEquals(8.0, status.userUsd());
        assertEquals(2.5, status.platformUsd());
        assertTrue(status.exhausted(), "10.5 of 10 spent");
    }

    @Test
    @DisplayName("every priced call publishes the spend event per-user quotas count from; free calls do not")
    void publishesPricedCalls() {
        LlmCallLogService log = new LlmCallLogService(mock(LlmCallRepository.class), true);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        log.setEvents(events);
        SpendSubject user = SpendSubject.user("7f9c1a52-2f3b-4e0a-9d1e-0c5a6b7d8e9f");

        log.record(user, "GUARDRAIL", "Provider", "m", TokenUsage.EMPTY, 0.02);
        verify(events).publishEvent(eq(new LlmSpendRecordedEvent(user, "GUARDRAIL", 0.02)));

        ApplicationEventPublisher none = mock(ApplicationEventPublisher.class);
        log.setEvents(none);
        log.record(user, "GUARDRAIL", "Provider", "m", TokenUsage.EMPTY, 0.0);
        verify(none, never()).publishEvent(any(Object.class));
    }
}
