package ai.proxyma.core.tools;

import ai.proxyma.core.chat.question.ChatOnlyTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent tree's tools are sent with EVERY model call of every agent - a saved agent of
 * Constellations on a cheap model included - so what each costs is pinned here: the description and
 * the schema the model reads, in characters. Measured 2026-11-02 at 10,938 characters for the twelve
 * (compact JSON) before they were cut to what a model needs to call each one right, and 8,091 after.
 */
class AgentToolsSizeTest {

    private static Map<String, ToolDefinition> definitions() {
        ToolCallback[] callbacks = ToolCallbacks.from(new AgentTools(null, null, null, null, null, null, null));
        Map<String, ToolDefinition> out = new LinkedHashMap<>();
        Arrays.stream(callbacks).forEach(cb -> out.put(cb.getToolDefinition().name(), cb.getToolDefinition()));
        return out;
    }

    // The schema as a provider is sent it: parsed and written compact, as every adapter does - the
    // pretty-printed string Spring AI holds would count its indentation.
    private static int schemaSize(ToolDefinition d) {
        try {
            return JSON.writeValueAsString(JSON.readTree(d.inputSchema())).length();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("every agent-tree tool stays short: deployAgent's words and the whole set's schemas are bounded")
    void theToolsStayShort() {
        Map<String, ToolDefinition> tools = definitions();
        int total = 0;
        int advanced = 0;
        for (ToolDefinition d : tools.values()) {
            int size = d.name().length() + d.description().length() + schemaSize(d);
            total += size;
            if (ChatOnlyTools.ADVANCED_AGENT_TREE.contains(d.name())) advanced += size;
            System.out.printf("[AgentToolsSize] %-16s description=%4d schema=%4d%n", d.name(),
                    d.description().length(), schemaSize(d));
        }
        System.out.printf("[AgentToolsSize] all %d tools: %d characters, the advanced set %d%n", tools.size(), total,
                advanced);

        ToolDefinition deploy = tools.get(AgentTools.DEPLOY);
        assertThat(deploy.description().length()).isLessThanOrEqualTo(300);
        assertThat(deploy.description().length() + schemaSize(deploy)).isLessThanOrEqualTo(1_250);
        assertThat(total).isLessThanOrEqualTo(8_500);
    }

    @Test
    @DisplayName("the advanced set names real agent-tree tools, all chat-only, and leaves a worker the basics")
    void theAdvancedSetIsReal() {
        Set<String> names = definitions().keySet();
        assertThat(names).containsAll(ChatOnlyTools.ADVANCED_AGENT_TREE);
        assertThat(ChatOnlyTools.ALL).containsAll(ChatOnlyTools.ADVANCED_AGENT_TREE);
        assertThat(ChatOnlyTools.ADVANCED_AGENT_TREE)
                .doesNotContain(AgentTools.DEPLOY, AgentTools.TELL, AgentTools.AWAIT, AgentTools.CONTROL);
        Set<String> basics = names.stream().filter(n -> !ChatOnlyTools.ADVANCED_AGENT_TREE.contains(n))
                .collect(Collectors.toSet());
        assertThat(basics).containsExactlyInAnyOrder(AgentTools.DEPLOY, AgentTools.TELL, AgentTools.AWAIT,
                AgentTools.CONTROL);
    }
}
