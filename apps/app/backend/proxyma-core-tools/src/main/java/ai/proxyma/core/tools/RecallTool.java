package ai.proxyma.core.tools;

import ai.proxyma.core.search.service.AgentMemoryService;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryCategory;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryEntry;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Reads the agent's memory three ways, in one tool rather than three (every tool definition is
// re-sent on every call): by id - one entry in full; by query - semantic search, falling back to
// keywords when the embedding server fails; with neither - the newest entries. Until 2026-09-26 it
// was query-only and returned bare text, so an entry could be found but never addressed again, and
// a slow embedding server made memory unreadable altogether (maintainer's feedback).
@Component
public class RecallTool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int DEFAULT_LIST = 20;
    // A listing is for choosing what to read; the entry itself comes back whole by id.
    private static final int LIST_PREVIEW_CHARS = 160;

    private final AgentMemoryService agentMemoryService;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public RecallTool(AgentMemoryService agentMemoryService) {
        this.agentMemoryService = agentMemoryService;
    }

    @Tool(description = "Read saved memory (knowledge and user facts). With id: that entry in full. With query: "
            + "the best matches. With neither: the newest entries, previews only. Empty is normal, not an error.")
    @ToolResult({
            @ToolResultField(name = "entries", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "id, category (USER or KNOWLEDGE), savedAt, text. Empty when none match.")
    })
    public Map<String, Object> recall(
            @ToolParam(required = false, description = "Natural-language query") String query,
            @ToolParam(required = false, description = "An entry id from an earlier recall, to read it in full") String id,
            @ToolParam(required = false, description = "\"user\" or \"knowledge\", to list one kind") String category,
            @ToolParam(required = false, description = "Max entries (default 5 for a query, 20 for a list; at most 50)") Integer topK) {
        String userId = ToolUserContext.resolveUserId(multiUser);
        if (userId == null) {
            return Map.of("success", true, "entries", List.of());
        }
        if (id != null && !id.isBlank()) {
            return agentMemoryService.read(userId, id)
                    .<Map<String, Object>>map(e -> Map.of("success", true, "entries", List.of(asMap(e, false))))
                    .orElseGet(() -> Map.of("success", false, "error", "No memory entry with id " + id.strip()));
        }
        if (query != null && !query.isBlank()) {
            List<MemoryEntry> hits = agentMemoryService.recall(userId, query, clamp(topK, DEFAULT_TOP_K));
            return Map.of("success", true, "entries", hits.stream().map(e -> asMap(e, false)).toList());
        }
        MemoryCategory cat = category == null ? null
                : "user".equalsIgnoreCase(category.strip()) ? MemoryCategory.USER
                : "knowledge".equalsIgnoreCase(category.strip().toLowerCase(Locale.ROOT)) ? MemoryCategory.KNOWLEDGE
                : null;
        List<MemoryEntry> recent = agentMemoryService.list(userId, cat, clamp(topK, DEFAULT_LIST));
        return Map.of("success", true, "entries", recent.stream().map(e -> asMap(e, true)).toList());
    }

    private static Map<String, Object> asMap(MemoryEntry e, boolean preview) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        if (e.category() != null) m.put("category", e.category());
        if (e.savedAt() != null) m.put("savedAt", e.savedAt());
        String text = e.text() == null ? "" : e.text();
        m.put("text", preview && text.length() > LIST_PREVIEW_CHARS ? text.substring(0, LIST_PREVIEW_CHARS) + "..." : text);
        return m;
    }

    // A number the model chose, so bounded - see SearchTool.MAX_TOP_K.
    static int clamp(Integer topK, int fallback) {
        return topK == null ? fallback : Math.max(1, Math.min(50, topK));
    }
}
