package ai.proxyma.core.tools;

import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.search.service.AgentMemoryService;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryCategory;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

// Saves, corrects and deletes the agent's memory - one tool rather than three, since every tool
// definition is re-sent on every call (RecallTool reads it the same way). Until 2026-10-17 it could
// only add: a correction worded differently from the entry it corrected fell under the dedup guard's
// similarity bar and was stored beside it, contradicting it, and nothing the agent could call took
// either away (maintainer's 0.36.2 feedback). With an id from recall it now replaces or deletes that
// entry - AgentMemoryService.edit and delete, the same calls Settings > Memory makes, scoped to this
// user's own collection.
@Component
public class RememberTool {

    private final AgentMemoryService agentMemoryService;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public RememberTool(AgentMemoryService agentMemoryService) {
        this.agentMemoryService = agentMemoryService;
    }

    // Set by Spring; absent in a test that builds the tool by hand, which then searches as before.
    private ai.proxyma.core.chat.module.AgentProfiles agentProfiles;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAgentProfiles(ai.proxyma.core.chat.module.AgentProfiles agentProfiles) {
        this.agentProfiles = agentProfiles;
    }

    @Tool(description = "Save one fact for future conversations, or change one already saved. One per call. "
            + "Without id: a new entry, and a near-duplicate replaces the old one. With an id from recall: "
            + "fact replaces that entry, or forget=true deletes it. If success is false, say the save failed.")
    @ToolResult({
            @ToolResultField(name = "id", type = ToolFieldType.STRING, always = false,
                    description = "The stored entry's id - recall reads it back whole with it. Absent after a delete.")
    })
    public Map<String, Object> remember(
            @ToolParam(required = true, description = "Short self-contained statement (not needed with forget)") String fact,
            @ToolParam(required = false, description = "\"user\" for the user's preferences, role or "
                    + "corrections (personalizes future replies), else \"knowledge\" (default; with id, "
                    + "the entry's own)") String category,
            @ToolParam(required = false, description = "An entry id from recall, to replace or delete that "
                    + "entry instead of adding one") String id,
            @ToolParam(required = false, description = "true with id: delete that entry") Boolean forget) {
        // The user's own memory, or the saved agent's when this conversation is one of its.
        String userId = ToolUserContext.resolveMemoryOwner(multiUser, agentProfiles);
        if (userId == null) {
            return Map.of("success", false, "error", "No persistent identity for this session.");
        }
        String entryId = id == null || id.isBlank() ? null : id.strip();
        boolean delete = Boolean.TRUE.equals(forget);
        if (delete && entryId == null) {
            return Map.of("success", false, "error", "forget needs the id of the entry to delete - recall gives it");
        }
        if (!delete && (fact == null || fact.isBlank())) {
            return Map.of("success", false, "error", "fact must not be blank");
        }
        try {
            if (delete) {
                return agentMemoryService.delete(userId, List.of(entryId)) > 0
                        ? Map.of("success", true)
                        : Map.of("success", false, "error", "No memory entry with id " + entryId);
            }
            if (entryId != null) {
                return agentMemoryService.edit(userId, entryId, fact.strip(), parse(category))
                        .<Map<String, Object>>map(e -> Map.of("success", true, "id", e.id()))
                        .orElseGet(() -> Map.of("success", false, "error", "No memory entry with id " + entryId));
            }
            MemoryCategory cat = parse(category);
            // The id lets a later recall read this entry back whole.
            String newId = agentMemoryService.remember(userId, fact.strip(), cat == null ? MemoryCategory.KNOWLEDGE : cat);
            return newId == null ? Map.of("success", true) : Map.of("success", true, "id", newId);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }

    // Null when the model named neither, so an edit keeps the entry's own category.
    private static MemoryCategory parse(String category) {
        if (category == null) return null;
        String c = category.strip().toLowerCase(Locale.ROOT);
        return c.equals("user") ? MemoryCategory.USER : c.equals("knowledge") ? MemoryCategory.KNOWLEDGE : null;
    }
}
