package ai.proxyma.core.tools;

import ai.proxyma.core.search.domain.SearchContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An MCP client's tool call runs on BlockingWork and is given up on at the chat's own limit. It ran
 * on the server's boundedElastic scheduler - which every blocking database read shares - with no
 * limit at all.
 */
class McpToolBoundsTest {

    private static McpServerFeatures.SyncToolSpecification slow(AtomicReference<String> sawUser,
                                                                AtomicReference<String> sawThread) {
        McpSchema.Tool tool = McpSchema.Tool.builder().name("slow").description("slow")
                .inputSchema(new McpSchema.JsonSchema("object", null, null, null, null, null)).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            sawUser.set(SearchContext.getUserId());
            sawThread.set(Thread.currentThread().getName());
            try {
                Thread.sleep(Long.parseLong(String.valueOf(request.arguments().get("ms"))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return McpSchema.CallToolResult.builder().addTextContent("done").isError(false).build();
        }).build();
    }

    @Test
    @DisplayName("a call past its limit is answered as failed, on time; one within it runs on a worker as the caller")
    void boundedAndOnAWorker() {
        AtomicReference<String> user = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        var spec = WebFetchMcpToolConfig.bounded(slow(user, thread), Duration.ofMillis(200));

        SearchContext.setUserId("u-7");
        try {
            long started = System.nanoTime();
            McpSchema.CallToolResult late = spec.callHandler().apply(null,
                    new McpSchema.CallToolRequest("slow", Map.of("ms", 5000)));
            assertThat(late.isError()).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));

            McpSchema.CallToolResult quick = spec.callHandler().apply(null,
                    new McpSchema.CallToolRequest("slow", Map.of("ms", 1)));
            assertThat(quick.isError()).isFalse();
            assertThat(user.get()).isEqualTo("u-7");
            assertThat(thread.get()).startsWith("work-");
        } finally {
            SearchContext.setUserId(null);
        }
    }
}
