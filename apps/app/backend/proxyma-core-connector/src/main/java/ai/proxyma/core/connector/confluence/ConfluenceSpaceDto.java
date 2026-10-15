package ai.proxyma.core.connector.confluence;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.UUID;

public record ConfluenceSpaceDto(
        @JsonProperty("id") UUID id,
        @JsonProperty("spaceKey") String spaceKey,
        @JsonProperty("spaceName") String spaceName,
        @JsonProperty("baseUrl") String baseUrl,
        @JsonProperty("single") boolean single,
        @JsonProperty("excluded") boolean excluded,
        @JsonProperty("priority") boolean priority,
        @JsonProperty("allPagesFetched") boolean allPagesFetched,
        @JsonProperty("totalPages") int totalPages,
        @JsonProperty("processedPages") int processedPages,
        @JsonProperty("failedPages") int failedPages,
        @JsonProperty("lastSyncedAt") LocalDateTime lastSyncedAt
) {}
