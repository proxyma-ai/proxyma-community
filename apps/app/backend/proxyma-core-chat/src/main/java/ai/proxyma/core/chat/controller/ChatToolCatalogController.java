package ai.proxyma.core.chat.controller;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

// Read-only catalog of every registered platform tool (name + description). Used by the Settings
// > Skills tool-picker checklist (and the chat Attach menu's "create new skill" flow) so a user
// building a custom skill can see what tools exist to grant it - see ToolSkillGating for how a
// skill's `tools` list is later merged into the model's advertised tool set once that skill is
// read via readSkill.
//
// Constructed straight from ToolCallbackProvider - the exact same source ChatService itself
// resolves its own toolCallbacks/coreToolCallbacks from (ToolCallbackProvider.getToolCallbacks()),
// so this controller isn't inventing a second way to enumerate tools and has no dependency on
// ChatService.
//
// Deliberately open (no role/auth gate): ToolSkillGating already documents itself as a
// visibility-only gate, not an authorization boundary - listing tool names/descriptions here
// doesn't expose credentials or bypass the real write-tool approval flow
// (WriteToolRegistry/McpGuardService). Worth a mention in the next security review since it makes
// the tool catalog (including write-tool names) enumerable by anonymous callers in multi-user
// mode, but it isn't itself a vulnerability.
@RestController
@RequestMapping("/api/v1/chat/tools")
class ChatToolCatalogController {

    // The one meta-tool that lets the agent load a skill's own detail - excluded because it's not
    // something a skill's `tools` list should ever "unlock" (every skill already has implicit
    // access to it via the system prompt).
    private static final String READ_SKILL_TOOL_NAME = "readSkill";

    private final ToolCallbackProvider toolCallbackProvider;

    ChatToolCatalogController(ToolCallbackProvider toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;
    }

    record ToolSummary(String name, String description) {}

    @GetMapping
    Mono<ResponseEntity<List<ToolSummary>>> list() {
        return Mono.fromCallable(() -> ResponseEntity.ok(
                Arrays.stream(toolCallbackProvider.getToolCallbacks())
                        .map(ChatToolCatalogController::toSummary)
                        .filter(t -> !READ_SKILL_TOOL_NAME.equals(t.name()))
                        .sorted(Comparator.comparing(ToolSummary::name, String.CASE_INSENSITIVE_ORDER))
                        .toList()));
    }

    private static ToolSummary toSummary(ToolCallback cb) {
        var def = cb.getToolDefinition();
        return new ToolSummary(def.name(), def.description());
    }
}
