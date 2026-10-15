package ai.proxyma.core.tools;

import ai.proxyma.core.chat.service.LicensedTools;
import ai.proxyma.core.tools.schema.ToolResultSchemaRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GET /api/v1/mcp/tools lists what the caller could use (authz-02): only what this installation's
 * plan includes, and on Enterprise Server no write tool for a Viewer. It listed every registered
 * tool, schemas included, to any signed-in account.
 */
class McpToolCatalogTest {

    private static ToolCallback tool(String name) {
        ToolDefinition def = DefaultToolDefinition.builder().name(name).description(name)
                .inputSchema("{\"type\":\"object\"}").build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String input) { return "{}"; }
        };
    }

    private static McpController controller(boolean multiUser) {
        ToolCallbackProvider provider = () -> new ToolCallback[] {
                tool("searchIndexedSources"), tool("createJiraIssue"), tool("listSvnFiles")};
        LicensedTools licensed = mock(LicensedTools.class);
        when(licensed.isLicensed(anyString())).thenAnswer(inv -> !"listSvnFiles".equals(inv.getArgument(0)));
        ToolResultSchemaRegistry schemas = mock(ToolResultSchemaRegistry.class);
        when(schemas.schemaFor(anyString())).thenReturn(Optional.empty());
        return new McpController(provider, schemas, licensed, multiUser);
    }

    private static List<String> names(McpController controller, String role) {
        var call = controller.listTools();
        if (role != null) {
            call = call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                    new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority(role)))));
        }
        return call.block().getBody().stream().map(m -> m.get("name")).toList();
    }

    @Test
    @DisplayName("a tool the plan leaves out is not listed, and a Viewer sees no write tool")
    void filteredByPlanAndRole() {
        assertThat(names(controller(true), "ROLE_VIEWER")).containsExactly("searchIndexedSources");
        assertThat(names(controller(true), "ROLE_ADMIN")).containsExactly("searchIndexedSources", "createJiraIssue");
        // Desktop and Home Server: the one user is everything.
        assertThat(names(controller(false), null)).containsExactly("searchIndexedSources", "createJiraIssue");
    }
}
