package ai.proxyma.core.chat.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chat-agent-32: a guest's last free message is admitted once. The allowance was checked and then
 * recorded in a second step, so turns sent together at the limit all read the same count and all
 * went through; and reading the count could copy the deque mid-change and throw.
 */
class GuestAdmitTest {

    @Test
    @DisplayName("turns sent at the same moment are admitted no further than the allowance")
    void concurrentTurnsStopAtTheLimit() throws Exception {
        GuestChatQuotaService quota = new GuestChatQuotaService(true, true, 5, 24);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> admitted = new ArrayList<>();
        try {
            for (int i = 0; i < 40; i++) {
                admitted.add(pool.submit(() -> {
                    go.await();
                    // Readers alongside the writers: the banner asks while turns arrive.
                    quota.allowance("guest-1");
                    return quota.admit("guest-1").isEmpty();
                }));
            }
            go.countDown();
            int count = 0;
            for (Future<Boolean> f : admitted) if (f.get(10, TimeUnit.SECONDS)) count++;
            assertThat(count).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(quota.allowance("guest-1").remaining()).isZero();
        assertThat(quota.admit("guest-1")).isPresent();
    }

    @Test
    @DisplayName("a refused turn counts nothing, and another guest is counted apart")
    void refusalCountsNothing() {
        GuestChatQuotaService quota = new GuestChatQuotaService(true, true, 1, 24);
        assertThat(quota.admit("guest-1")).isEmpty();
        assertThat(quota.admit("guest-1")).isPresent();
        assertThat(quota.allowance("guest-1").used()).isEqualTo(1);
        assertThat(quota.admit("guest-2")).isEmpty();
    }
}
