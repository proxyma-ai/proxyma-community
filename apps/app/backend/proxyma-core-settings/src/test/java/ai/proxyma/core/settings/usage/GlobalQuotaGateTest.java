package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.settings.dto.QuotaSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What it costs to ask whether there is budget left. The gate stands before every model call, and
 * it totalled the month's ledger each time - with no ceiling configured, which is the default.
 */
class GlobalQuotaGateTest {

    private final QuotaSettings quota = new QuotaSettings();
    private final LlmCallRepository ledger = mock(LlmCallRepository.class);
    private final AtomicLong now = new AtomicLong(1_000_000);
    private GlobalQuotaGate gate;

    @BeforeEach
    void setUp() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(eq(QuotaSettings.PREFIX), eq(QuotaSettings.class))).thenReturn(quota);
        gate = new GlobalQuotaGate(new GlobalQuotaService(settings, ledger));
        gate.setClock(now::get);
    }

    private void spent(double usd) {
        when(ledger.costBySubjectKindAndRoleSince(any()))
                .thenReturn(List.<Object[]>of(new Object[]{SpendSubject.Kind.USER, "CHAT", usd}));
    }

    @Test
    @DisplayName("with no ceiling set, a check never reads the ledger")
    void noCeilingNoQuery() {
        quota.setGlobalMonthlyQuotaUsd(0);

        for (int i = 0; i < 50; i++) gate.checkOrRefuse("this chat model call");

        assertThat(gate.isExhausted()).isFalse();
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("with a ceiling, many checks in a moment share one reading of the ledger")
    void checksShareOneReading() {
        quota.setGlobalMonthlyQuotaUsd(10);
        spent(4);

        for (int i = 0; i < 50; i++) gate.checkOrRefuse("this chat model call");
        verify(ledger, times(1)).costBySubjectKindAndRoleSince(any());

        now.addAndGet(GlobalQuotaGate.REMEMBER_MS);
        gate.checkOrRefuse("this chat model call");
        verify(ledger, times(2)).costBySubjectKindAndRoleSince(any());
    }

    @Test
    @DisplayName("a spent ceiling refuses, and goes on refusing without asking again")
    void aSpentCeilingRefuses() {
        quota.setGlobalMonthlyQuotaUsd(10);
        spent(10.5);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> gate.checkOrRefuse("this chat model call"))
                    .isInstanceOf(GlobalQuotaExceededException.class);
        }
        assertThat(gate.isExhausted()).isTrue();
        verify(ledger, times(1)).costBySubjectKindAndRoleSince(any());
    }

    @Test
    @DisplayName("raising the ceiling lets work resume at once, not when the reading runs out")
    void raisingTheCeilingIsSeenAtOnce() {
        quota.setGlobalMonthlyQuotaUsd(10);
        spent(10.5);
        assertThatThrownBy(() -> gate.checkOrRefuse("this chat model call"))
                .isInstanceOf(GlobalQuotaExceededException.class);

        quota.setGlobalMonthlyQuotaUsd(50);

        assertThatCode(() -> gate.checkOrRefuse("this chat model call")).doesNotThrowAnyException();
        quota.setGlobalMonthlyQuotaUsd(0);
        assertThat(gate.isExhausted()).isFalse();
    }

    @Test
    @DisplayName("a ledger that cannot be read allows the call")
    void aFailedReadAllows() {
        quota.setGlobalMonthlyQuotaUsd(10);
        when(ledger.costBySubjectKindAndRoleSince(any())).thenThrow(new IllegalStateException("database is down"));

        assertThatCode(() -> gate.checkOrRefuse("this chat model call")).doesNotThrowAnyException();
        assertThat(gate.isExhausted()).isFalse();
    }
}
