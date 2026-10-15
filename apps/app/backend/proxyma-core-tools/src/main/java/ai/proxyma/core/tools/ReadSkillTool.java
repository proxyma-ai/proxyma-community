package ai.proxyma.core.tools;

import ai.proxyma.core.chat.service.ChatSkillService;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ReadSkillTool {

    // Resolves who's asking (see ToolUserContext) so the lookup can enforce the skill's own
    // visibility rule (owner, shared, or official) - a skillName argument is user-suppliable
    // (typed directly, or via a skill mention forcing this call), not something the model can
    // only ever echo back from what it was already shown, so it must be treated as untrusted
    // input rather than assumed safe (SEC-26).
    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    private final ChatSkillService chatSkillService;

    public ReadSkillTool(ChatSkillService chatSkillService) {
        this.chatSkillService = chatSkillService;
    }

    @Tool(description = "Load a skill's full instructions before acting on a task it matches; don't guess them "
            + "from the name. A connector skill's tools become callable only after this, from your next call.")
    @ToolResult({
            @ToolResultField(name = "content", type = ToolFieldType.STRING, always = true,
                    description = "The skill's full instructions. Present only on success; an unknown or "
                            + "invisible skill name comes back as an error instead.")
    })
    public Map<String, Object> readSkill(
            @ToolParam(required = true, description = "Exact name from 'Available Skills', or one the user named") String skillName) {
        String userId = ToolUserContext.resolveUserId(multiUser);
        return chatSkillService.readDetailByNameVisibleTo(skillName, userId)
                .<Map<String, Object>>map(content -> Map.of("success", true, "content", content))
                .orElseGet(() -> Map.of("success", false, "error", "No such skill: " + skillName));
    }
}
