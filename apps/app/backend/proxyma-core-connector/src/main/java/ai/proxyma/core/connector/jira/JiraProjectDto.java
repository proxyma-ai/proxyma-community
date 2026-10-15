package ai.proxyma.core.connector.jira;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.UUID;

public record JiraProjectDto(
        @JsonProperty("id") UUID id,
        @JsonProperty("projectKey") String projectKey,
        @JsonProperty("projectName") String projectName,
        @JsonProperty("baseUrl") String baseUrl,
        @JsonProperty("single") boolean single,
        @JsonProperty("excluded") boolean excluded,
        @JsonProperty("priority") boolean priority,
        @JsonProperty("allIssuesFetched") boolean allIssuesFetched,
        @JsonProperty("totalIssues") int totalIssues,
        @JsonProperty("processedIssues") int processedIssues,
        @JsonProperty("failedIssues") int failedIssues,
        @JsonProperty("lastSyncedAt") LocalDateTime lastSyncedAt
) {}
