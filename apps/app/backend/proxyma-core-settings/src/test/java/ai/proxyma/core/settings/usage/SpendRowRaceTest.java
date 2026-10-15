package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two calls opening the same month's spend row at once both land, and neither breaks its caller
 * (2026-10-04: two sub-agents' first calls of the month on one model - the second insert broke the
 * unique key, and the sub-agent failed with it).
 */
class SpendRowRaceTest {

    private final List<TransactionDefinition> opened = new ArrayList<>();
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SpendRowWriter writer;

    SpendRowRaceTest() {
        when(manager.getTransaction(any())).thenAnswer(inv -> {
            opened.add(inv.getArgument(0));
            return new SimpleTransactionStatus();
        });
        writer = new SpendRowWriter(manager);
    }

    @Test
    @DisplayName("a write that lost the race to create the row runs again, in a transaction of its own")
    void theLoserWritesAgain() {
        AtomicInteger runs = new AtomicInteger();
        writer.write(() -> {
            if (runs.incrementAndGet() == 1) throw new DataIntegrityViolationException("duplicate key");
        });

        assertThat(runs.get()).isEqualTo(2);
        assertThat(opened).hasSize(2).allMatch(d -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("a spend row that cannot be written never reaches the model call that spent it")
    void aFailedWriteStaysInside() {
        ChatSpendMonthRepository repository = mock(ChatSpendMonthRepository.class);
        when(repository.findByYearMonthAndProviderAndModel(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
        ChatSpendService service = new ChatSpendService(repository, mock(PlatformSpendService.class),
                mock(ModelPricingService.class), writer);

        assertThatCode(() -> service.record(new TokenUsage(1200, 300, 0, 0, 1200), "Fake", "fake-mini", 0.25))
                .doesNotThrowAnyException();
        // Tried, and tried once more after the conflict.
        verify(repository, times(2)).saveAndFlush(any());
    }
}
