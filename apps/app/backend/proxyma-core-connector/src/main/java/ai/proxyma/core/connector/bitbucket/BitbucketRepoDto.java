package ai.proxyma.core.connector.bitbucket;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.UUID;

public record BitbucketRepoDto(
        @JsonProperty("id") UUID id,
        @JsonProperty("repoSlug") String repoSlug,
        @JsonProperty("repoName") String repoName,
        @JsonProperty("projectKey") String projectKey,
        @JsonProperty("baseUrl") String baseUrl,
        @JsonProperty("single") boolean single,
        @JsonProperty("excluded") boolean excluded,
        @JsonProperty("priority") boolean priority,
        @JsonProperty("allPrsFetched") boolean allPrsFetched,
        @JsonProperty("totalPrs") int totalPrs,
        @JsonProperty("processedPrs") int processedPrs,
        @JsonProperty("failedPrs") int failedPrs,
        @JsonProperty("lastSyncedAt") LocalDateTime lastSyncedAt
) {}
