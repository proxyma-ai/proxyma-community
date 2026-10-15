package ai.proxyma.core.registry.files.upload.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record FileUploadResponse(
        @JsonProperty("id")           String id,
        @JsonProperty("originalName") String originalName,
        @JsonProperty("mimeType")     String mimeType,
        @JsonProperty("sizeBytes")    long sizeBytes,
        @JsonProperty("uploadedAt")   Instant uploadedAt
) {}
