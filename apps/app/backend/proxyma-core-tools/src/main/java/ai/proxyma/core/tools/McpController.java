package ai.proxyma.core.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import ai.proxyma.core.chat.service.LicensedTools;
import ai.proxyma.core.settings.service.WriteToolRegistry;
import ai.proxyma.core.tools.schema.ToolResultSchemaRegistry;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final ToolCallbackProvider toolCallbackProvider;
    private final ToolResultSchemaRegistry schemaRegistry;
    private final LicensedTools licensed;
    private final boolean multiUser;

    public McpController(ToolCallbackProvider toolCallbackProvider, ToolResultSchemaRegistry schemaRegistry,
                         LicensedTools licensed,
                         @org.springframework.beans.factory.annotation.Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.schemaRegistry = schemaRegistry;
        this.licensed = licensed;
        this.multiUser = multiUser;
    }

    // GET /v1/mcp/tools - list all registered MCP tool names, descriptions and schemas.
    //
    // inputSchema comes from Spring AI, which derives it from @ToolParam and the Java parameter
    // types. outputSchema does not: ToolDefinition (Spring AI 1.1.7) has no outputSchema() to read,
    // so it comes from the tool's own @ToolResult declaration via ToolResultSchemaRegistry. Both
    // are strings holding JSON rather than nested objects, because inputSchema is already a string
    // and returning the two halves of the same contract in two different shapes would be a worse
    // API than either choice on its own.
    //
    // outputSchema is omitted for a tool that declares none, rather than sent as null or empty - an
    // absent key says "this tool has not declared one", which is a different statement from "this
    // tool returns nothing".
    //
    // FILTERED to what the caller could use (authz-02): only tools this installation's plan includes -
    // the set the agent itself is offered - and, on Enterprise Server, no write tool for a Viewer.
    // It listed every registered tool, schemas included, to any signed-in account.
    @GetMapping("/tools")
    public Mono<ResponseEntity<List<Map<String, String>>>> listTools() {
        return mayWrite().flatMap(mayWrite -> Mono.fromCallable(() -> {
            List<Map<String, String>> tools = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                    .filter(cb -> !AskUserTool.CHAT_ONLY.contains(cb.getToolDefinition().name()))
                    .filter(cb -> licensed.isLicensed(cb.getToolDefinition().name()))
                    .filter(cb -> mayWrite || !WriteToolRegistry.NAMES.contains(cb.getToolDefinition().name()))
                    .map(cb -> {
                        var def = cb.getToolDefinition();
                        Map<String, String> entry = new LinkedHashMap<>();
                        entry.put("name", def.name());
                        entry.put("description", def.description());
                        entry.put("inputSchema", def.inputSchema());
                        schemaRegistry.schemaFor(def.name())
                                .ifPresent(schema -> entry.put("outputSchema", schema));
                        return entry;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(tools);
        }));
    }

    // Desktop and Home Server have one user, who is everything; on Enterprise Server a Viewer writes
    // nothing.
    private Mono<Boolean> mayWrite() {
        if (!multiUser) return Mono.just(true);
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                        .anyMatch(r -> r.equals("ROLE_SUPER_ADMIN") || r.equals("ROLE_ADMIN")))
                .defaultIfEmpty(false);
    }

    // GET /v1/mcp/health - confirm MCP tool registry is active
    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> health() {
        return Mono.fromCallable(() -> {
            int count = toolCallbackProvider.getToolCallbacks().length;
            log.debug("MCP health check: {} tools registered", count);
            return ResponseEntity.ok(Map.of("status", "UP", "toolCount", count));
        });
    }
}
