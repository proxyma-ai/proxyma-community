package ai.proxyma.core.connector.svn;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.UUID;

public record SvnRepoDto(
        @JsonProperty("id") UUID id,
        @JsonProperty("repoUrl") String repoUrl,
        @JsonProperty("repoName") String repoName,
        @JsonProperty("single") boolean single,
        @JsonProperty("excluded") boolean excluded,
        @JsonProperty("priority") boolean priority,
        @JsonProperty("allFilesFetched") boolean allFilesFetched,
        @JsonProperty("totalFiles") int totalFiles,
        @JsonProperty("processedFiles") int processedFiles,
        @JsonProperty("failedFiles") int failedFiles,
        @JsonProperty("lastSyncedAt") LocalDateTime lastSyncedAt
) {}
