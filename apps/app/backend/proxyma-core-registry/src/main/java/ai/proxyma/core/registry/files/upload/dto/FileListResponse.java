package ai.proxyma.core.registry.files.upload.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FileListResponse(
        @JsonProperty("files") List<FileUploadResponse> files
) {}
