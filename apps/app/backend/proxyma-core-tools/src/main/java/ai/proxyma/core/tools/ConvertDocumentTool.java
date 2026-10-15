package ai.proxyma.core.tools;

import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.ingest.service.DocumentConversionService;
import ai.proxyma.core.ingest.service.OcrService;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

// Agent-facing tool: converts an uploaded file (PDF, Word, Excel, PowerPoint, plain text, or a
// scanned page image) at an absolute path into readable Markdown/text so the chat agent can read and
// reason about it. Which converter runs is not decided here - DocumentConversionService routes it
// through the method configured for the ATTACHMENT surface (Settings > Conversion), which is also
// what gives this tool OCR for scans. This stays a separate, on-demand conversion primitive; the
// indexing pipeline calls the same service with its own surface.
@Component
public class ConvertDocumentTool {

    private final DocumentConversionService conversionService;
    private final OcrService ocrService;
    private final String conversationsDir;
    private final boolean multiUser;

    public ConvertDocumentTool(DocumentConversionService conversionService,
                                OcrService ocrService,
                                @Value("${proxyma.conversations-dir:${user.home}/.proxyma/conversations}") String conversationsDir,
                                @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.conversionService = conversionService;
        this.ocrService = ocrService;
        this.conversationsDir = conversationsDir;
        this.multiUser = multiUser;
    }

    @Tool(description = "Convert an uploaded PDF, Office (not legacy .doc/.ppt), text/code file or scanned image to "
            + "Markdown (PDF tables kept). Scans need OCR: without it a scanned PDF comes back nearly empty - say "
            + "so, don't guess. Only files under the conversations directory - use the exact path from "
            + "'[Attached file(s): ...]'.")
    @ToolResult({
            @ToolResultField(name = "method", type = ToolFieldType.STRING, always = true,
                    description = "Which converter produced the content, e.g. the extractor that handled this "
                            + "file type."),
            @ToolResultField(name = "content", type = ToolFieldType.STRING, always = true,
                    description = "The document as markdown text.")
    })
    public Map<String, Object> convertDocument(
            @ToolParam(description = "Absolute path to the file") String filePath) {
        Path target = Path.of(filePath).normalize();
        Path allowed = OffloadFileAccess.resolveAllowedRoot(conversationsDir, multiUser);
        if (!OffloadFileAccess.isWithinAllowedRoot(target, allowed)) {
            return Map.of("success", false, "error", OffloadFileAccess.denied("convertDocument", target, allowed));
        }
        if (!Files.exists(target)) {
            return Map.of("success", false, "error", "File not found: " + target.getFileName());
        }
        String name = target.getFileName().toString();
        // Images used to be refused outright, because nothing here could read one. OCR can, so the
        // refusal now applies only when OCR is genuinely unavailable - and says why, so the user can
        // act on it instead of being told image understanding does not exist.
        if (OcrService.isImage(name) && !ocrService.isAvailable()) {
            return Map.of("success", false, "error",
                    "This is an image file and OCR is not available - "
                    + ocrService.unavailableReason() + ". Do not guess at its content; tell the user directly.");
        }
        try {
            DocumentConversionService.Converted converted =
                    conversionService.convert(target, DocumentConversionService.Surface.ATTACHMENT);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            // Named so the agent can qualify what it read: OCR output is a transcription of a page
            // image, which is a weaker claim than text lifted from a real text layer, and an answer
            // built on it should be able to say so.
            result.put("method", converted.method());
            result.put("content", converted.content());
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", sanitizeError(Failures.describe(e), target));
        }
    }

    // Converter exceptions (Files.*, markitdown) can embed the full server-side path in their own
    // message - e.g. a NoSuchFileException's message IS the path. Strip it before it reaches the
    // chat agent, since that path is server filesystem layout, never something a user-facing answer
    // should reveal.
    private String sanitizeError(String message, Path target) {
        if (message == null) return "Failed to process " + target.getFileName();
        String name = target.getFileName().toString();
        String full = target.toString();
        return message.replace(full, name).replace(full.replace("\\", "/"), name);
    }
}
