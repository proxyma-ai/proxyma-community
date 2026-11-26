package ai.proxyma.core.tools;

import ai.proxyma.core.search.domain.SearchContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A WRITE OVER MCP ONLY WHERE THE PERSON ALLOWED IT (2026-11-14): over /mcp no approval pause and no permission mode
 * apply, so an assistant's model posted a YouTube reply the moment it asked. A write call is refused unless the caller
 * turned on "Let assistants make changes"; a read, and a write tool called in its read form, always run.
 * <p>Verifies [PROXYMA-AD 008], [PROXYMA-AD 052].
 */
class McpWritesGateTest {

    private static McpServerFeatures.SyncToolSpecification tool(String name, AtomicInteger ran) {
        McpSchema.Tool tool = McpSchema.Tool.builder().name(name).description(name)
                .inputSchema(new McpSchema.JsonSchema("object", null, null, null, null, null)).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            ran.incrementAndGet();
            return McpSchema.CallToolResult.builder().addTextContent("{\"success\":true}").isError(false).build();
        }).build();
    }

    private static McpSchema.CallToolResult call(McpServerFeatures.SyncToolSpecification spec, String name,
                                                 Map<String, Object> args) {
        return spec.callHandler().apply(null, new McpSchema.CallToolRequest(name, args));
    }

    @Test
    @DisplayName("a write is refused, saying where to allow it, until the caller allows assistant writes; then it runs")
    void writeOnlyWhereAllowed() {
        AtomicInteger ran = new AtomicInteger();
        Set<String> allowed = new java.util.HashSet<>();
        List<String> asked = new ArrayList<>();
        var reply = WebFetchMcpToolConfig.gated(tool("replyToYouTubeComment", ran), "replyToYouTubeComment", user -> {
            asked.add(user);
            return allowed.contains(user);
        });

        SearchContext.setUserId("u-1");
        try {
            McpSchema.CallToolResult refused = call(reply, "replyToYouTubeComment", Map.of("commentId", "c1", "text", "Hi"));
            assertThat(refused.isError()).isTrue();
            assertThat(((McpSchema.TextContent) refused.content().get(0)).text())
                    .contains("nothing was changed", "Settings > AI Assistants");
            assertThat(ran).hasValue(0);
            assertThat(asked).as("the caller's own setting").containsExactly("u-1");

            allowed.add("u-1");
            assertThat(call(reply, "replyToYouTubeComment", Map.of("commentId", "c1", "text", "Hi")).isError()).isFalse();
            assertThat(ran).hasValue(1);
        } finally {
            SearchContext.setUserId(null);
        }
    }

    @Test
    @DisplayName("a read, and a write tool called in its read form, run whatever the setting; a setting that cannot be read refuses")
    void readsRun() {
        AtomicInteger ran = new AtomicInteger();
        var list = WebFetchMcpToolConfig.gated(tool("listYouTubeComments", ran), "listYouTubeComments", user -> false);
        assertThat(call(list, "listYouTubeComments", Map.of()).isError()).isFalse();

        var api = WebFetchMcpToolConfig.gated(tool("callJiraApi", ran), "callJiraApi", user -> false);
        assertThat(call(api, "callJiraApi", Map.of("method", "GET", "path", "/rest/api/2/myself")).isError()).isFalse();
        assertThat(call(api, "callJiraApi", Map.of("method", "POST", "path", "/rest/api/2/issue")).isError()).isTrue();
        assertThat(ran).hasValue(2);

        var broken = WebFetchMcpToolConfig.gated(tool("createJiraIssue", ran), "createJiraIssue", user -> {
            throw new IllegalStateException("settings unreadable");
        });
        assertThat(call(broken, "createJiraIssue", Map.of()).isError()).isTrue();
        assertThat(ran).hasValue(2);
    }
}
