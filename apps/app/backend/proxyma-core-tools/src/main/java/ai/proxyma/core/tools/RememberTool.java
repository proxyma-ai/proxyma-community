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

import java.util.Locale;
import java.util.Map;

@Component
public class RememberTool {

    private final AgentMemoryService agentMemoryService;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public RememberTool(AgentMemoryService agentMemoryService) {
        this.agentMemoryService = agentMemoryService;
    }

    @Tool(description = "Save one fact for future conversations. One per call; a near-duplicate replaces the "
            + "old entry. If success is false, say the save failed.")
    @ToolResult({
            @ToolResultField(name = "id", type = ToolFieldType.STRING, always = false,
                    description = "The stored entry's id - recall reads it back whole with it.")
    })
    public Map<String, Object> remember(
            @ToolParam(required = true, description = "Short self-contained statement") String fact,
            @ToolParam(required = false, description = "\"user\" for the user's preferences, role or "
                    + "corrections (personalizes future replies), else \"knowledge\" (default)") String category) {
        String userId = ToolUserContext.resolveUserId(multiUser);
        if (userId == null) {
            return Map.of("success", false, "error", "No persistent identity for this session.");
        }
        if (fact == null || fact.isBlank()) {
            return Map.of("success", false, "error", "fact must not be blank");
        }
        MemoryCategory cat = "user".equalsIgnoreCase(category == null ? null : category.strip().toLowerCase(Locale.ROOT))
                ? MemoryCategory.USER : MemoryCategory.KNOWLEDGE;
        try {
            // The id lets a later recall read this entry back whole.
            String id = agentMemoryService.remember(userId, fact.strip(), cat);
            return id == null ? Map.of("success", true) : Map.of("success", true, "id", id);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
