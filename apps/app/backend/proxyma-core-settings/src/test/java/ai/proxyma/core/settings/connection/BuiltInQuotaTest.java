package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.settings.global.GlobalSetting;
import ai.proxyma.core.settings.global.GlobalSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The built-in client's daily share per install (2026-11-08): counted per Pacific-time day, when YouTube's
 * quota resets, stopped before the budget, kept across a restart, and nothing at all without a budget.
 */
class BuiltInQuotaTest {

    private final Map<String, String> table = new HashMap<>();
    private final MockEnvironment env = new MockEnvironment()
            .withProperty("proxyma.oauth.builtin.youtube.daily-units", "100");

    @SuppressWarnings("unchecked")
    private ObjectProvider<GlobalSettingRepository> store() {
        GlobalSettingRepository repo = mock(GlobalSettingRepository.class);
        when(repo.save(any(GlobalSetting.class))).thenAnswer(inv -> {
            GlobalSetting g = inv.getArgument(0);
            table.put(g.getSettingKey(), g.getSettingValue());
            return g;
        });
        when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(table.get(inv.<String>getArgument(0)))
                .map(v -> new GlobalSetting(inv.getArgument(0), v)));
        ObjectProvider<GlobalSettingRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repo);
        return provider;
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("spent up to the budget and refused past it, nothing spent by a refusal; a free request always goes")
    void stopsBeforeTheBudget() {
        BuiltInQuota quota = new BuiltInQuota(env, store(), at("2026-11-08T12:00:00Z"));
        assertThat(quota.spend(ConnectionProtocol.YOUTUBE, 50)).isTrue();
        assertThat(quota.spend(ConnectionProtocol.YOUTUBE, 49)).isTrue();
        assertThat(quota.spend(ConnectionProtocol.YOUTUBE, 50)).isFalse();
        assertThat(quota.spend(ConnectionProtocol.YOUTUBE, 0)).isTrue();
        assertThat(quota.spend(ConnectionProtocol.YOUTUBE, 2)).isFalse();
        assertThat(quota.spentToday(ConnectionProtocol.YOUTUBE)).isEqualTo(100);
    }

    @Test
    @DisplayName("a new Pacific day starts at zero - midnight in Los Angeles, not in UTC - and the count outlives a restart")
    void pacificDayAndRestart() {
        // 07:30 UTC on 9 November is still 8 November in Los Angeles (PST, UTC-8).
        BuiltInQuota before = new BuiltInQuota(env, store(), at("2026-11-09T07:30:00Z"));
        assertThat(before.spend(ConnectionProtocol.YOUTUBE, 90)).isTrue();
        assertThat(table).containsEntry("builtin-oauth-usage.youtube", "2026-11-08 90");

        BuiltInQuota restarted = new BuiltInQuota(env, store(), at("2026-11-09T07:59:00Z"));
        assertThat(restarted.spend(ConnectionProtocol.YOUTUBE, 20)).as("the same Pacific day, after a restart").isFalse();

        BuiltInQuota nextDay = new BuiltInQuota(env, store(), at("2026-11-09T08:00:00Z"));
        assertThat(nextDay.spend(ConnectionProtocol.YOUTUBE, 20)).as("midnight Pacific").isTrue();
        assertThat(nextDay.resetsAt()).isEqualTo(ZonedDateTime.of(2026, 11, 10, 0, 0, 0, 0, ZoneId.of("America/Los_Angeles")));
    }

    @Test
    @DisplayName("no budget set, or none for that service, is no limit; a stored row that does not parse is a new day")
    void noBudgetNoLimit() {
        BuiltInQuota quota = new BuiltInQuota(new MockEnvironment(), store(), at("2026-11-08T12:00:00Z"));
        for (int i = 0; i < 1000; i++) assertThat(quota.spend(ConnectionProtocol.YOUTUBE, 50)).isTrue();
        assertThat(new BuiltInQuota(env, store(), at("2026-11-08T12:00:00Z")).spend(ConnectionProtocol.GOOGLE, 5000)).isTrue();

        table.put("builtin-oauth-usage.youtube", "garbage");
        assertThat(new BuiltInQuota(env, store(), at("2026-11-08T12:00:00Z")).spend(ConnectionProtocol.YOUTUBE, 100)).isTrue();
        // And without a database at all it still holds the line for this run.
        BuiltInQuota memoryOnly = new BuiltInQuota(env, null, at("2026-11-08T12:00:00Z"));
        assertThat(memoryOnly.spend(ConnectionProtocol.YOUTUBE, 100)).isTrue();
        assertThat(memoryOnly.spend(ConnectionProtocol.YOUTUBE, 1)).isFalse();
    }

    @Test
    @DisplayName("each request is tallied by what it asked and what made it, the costliest first (2026-11-11: where the units go)")
    void tallied() {
        BuiltInQuota quota = new BuiltInQuota(env, store(), at("2026-11-11T08:00:00Z"));
        quota.spend(ConnectionProtocol.YOUTUBE, 1, "commentThreads by the source sync");
        quota.spend(ConnectionProtocol.YOUTUBE, 50, "comments.post by background work");
        quota.spend(ConnectionProtocol.YOUTUBE, 1, "commentThreads by the source sync");
        assertThat(quota.tallyLine("youtube")).isEqualTo("comments.post by background work 50, commentThreads by the source sync 2");
    }

    @Test
    @DisplayName("a request is named by its resource, a write by its method too, and by what made it")
    void callNamed() {
        String name = Thread.currentThread().getName();
        try {
            Thread.currentThread().setName("sync-47ffae06");
            assertThat(OAuth2ConnectionService.callOf(org.springframework.http.HttpMethod.GET,
                    java.net.URI.create("https://www.googleapis.com/youtube/v3/commentThreads?part=snippet")))
                    .isEqualTo("commentThreads by the source sync");
            Thread.currentThread().setName("work-12");
            assertThat(OAuth2ConnectionService.callOf(org.springframework.http.HttpMethod.POST,
                    java.net.URI.create("https://www.googleapis.com/youtube/v3/comments")))
                    .isEqualTo("comments.post by background work");
        } finally {
            Thread.currentThread().setName(name);
        }
    }
}
