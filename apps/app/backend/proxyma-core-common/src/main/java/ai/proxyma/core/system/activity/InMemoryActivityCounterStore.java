package ai.proxyma.core.system.activity;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

// Same dependency-free ConcurrentHashMap shape already proven by ApiRateLimitFilter - no DB
// write per request, no external store needed at single-instance scale.
@Component
public class InMemoryActivityCounterStore implements ActivityCounterStore {

    private static final class DayBucket {
        final AtomicLong totalRequests = new AtomicLong();
        final Set<String> uniqueUserIds = ConcurrentHashMap.newKeySet();
    }

    private static final class FeatureBucket {
        final LongAdder requestCount = new LongAdder();
        final LongAdder totalDurationMs = new LongAdder();
    }

    private final ConcurrentHashMap<LocalDate, DayBucket> days = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<FeatureKey, FeatureBucket> features = new ConcurrentHashMap<>();

    @Override
    public void recordRequest(String userId) {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        DayBucket bucket = days.computeIfAbsent(today, d -> new DayBucket());
        bucket.totalRequests.incrementAndGet();
        if (userId != null) bucket.uniqueUserIds.add(userId);
    }

    @Override
    public DailyCounts drain(LocalDate date) {
        DayBucket bucket = days.remove(date);
        if (bucket == null) return new DailyCounts(0, 0);
        return new DailyCounts(bucket.totalRequests.get(), bucket.uniqueUserIds.size());
    }

    @Override
    public void recordFeature(String userId, String feature, long durationMs) {
        if (userId == null || feature == null) return;
        FeatureBucket bucket = features.computeIfAbsent(new FeatureKey(userId, feature), k -> new FeatureBucket());
        bucket.requestCount.increment();
        bucket.totalDurationMs.add(Math.max(0, durationMs));
    }

    @Override
    public Map<FeatureKey, FeatureUsage> drainFeatureUsage() {
        Map<FeatureKey, FeatureUsage> out = new HashMap<>();
        for (FeatureKey key : features.keySet()) {
            FeatureBucket bucket = features.remove(key);
            if (bucket != null) {
                out.put(key, new FeatureUsage(bucket.requestCount.sum(), bucket.totalDurationMs.sum()));
            }
        }
        return out;
    }
}
