package ai.proxyma.core.settings.connection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * On Enterprise Server, an MCP server that RUNS ON THE SERVER, and one shared with everyone, are a
 * Super admin's to add (maintainer's decision, 2026-10-13): the first runs a command as the server
 * process, the second puts a tool in every user's agent. Refused before anything is saved, by role.
 *
 * <p>isd-01: the gate is asked in every edition, of the transport a connection WILL have - an edit
 * that changes a local server's command without naming its transport used to pass as a remote one -
 * and Test, which starts the command, takes it too.
 */
class McpServerRoleTest {

    private ConnectorConnectionService service;
    private ConnectorConnectionController controller;

    @BeforeEach
    void setUp() {
        service = mock(ConnectorConnectionService.class);
        when(service.create(anyString(), any())).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<McpServerProbe> probe = mock(ObjectProvider.class);
        controller = new ConnectorConnectionController(service, List.of(), probe);
        ReflectionTestUtils.setField(controller, "multiUser", true);
    }

    private int create(String transport, Boolean shared, String role) {
        ConnectorConnectionController.ConnectionRequest request = new ConnectorConnectionController.ConnectionRequest(
                "Server", "MCP_SERVER", "https://x.example/mcp", null, null, null, null, null, null,
                new ConnectorConnectionController.McpRequest(transport, "npx", List.of(), null, null, null, shared,
                        null, null, null));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/connections/instances"));
        var auth = new UsernamePasswordAuthenticationToken("u-1", null, List.of(new SimpleGrantedAuthority(role)));
        ResponseEntity<?> r = controller.create(request, exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
        return r.getStatusCode().value();
    }

    @Test
    @DisplayName("an Admin or a user cannot add a local server or share one; nothing is saved")
    void othersAreRefused() {
        assertEquals(403, create("STDIO", null, "ROLE_ADMIN"));
        assertEquals(403, create("STREAMABLE_HTTP", true, "ROLE_USER"));
        verify(service, never()).create(anyString(), any());
    }

    private static final UUID LOCAL = UUID.randomUUID();

    private void storedLocalServer() {
        McpServerConfig config = new McpServerConfig(McpServerConfig.Transport.STDIO, "npx", List.of(),
                null, null, null, null);
        when(service.resolveOwnedMcp(anyString(), eq(LOCAL))).thenReturn(Optional.of(new ResolvedMcpServer(
                LOCAL, "u-1", "Local", null, config, McpServerConfig.McpSecrets.parse(null), false, null, "v1")));
    }

    private static UsernamePasswordAuthenticationToken as(String role) {
        return new UsernamePasswordAuthenticationToken("u-1", null, List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    @DisplayName("an edit of a local server's command that does not name the transport is still gated")
    void editingALocalServerIsGated() {
        storedLocalServer();
        ConnectorConnectionController.ConnectionRequest request = new ConnectorConnectionController.ConnectionRequest(
                "Local", null, null, null, null, null, null, null, null,
                new ConnectorConnectionController.McpRequest(null, "/bin/sh", List.of("-c", "id"), null, null, null,
                        null, null, null, null));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.patch("/api/v1/connections/instances/" + LOCAL));

        ResponseEntity<?> r = controller.update(LOCAL, request, exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(as("ROLE_ADMIN"))).block();

        assertEquals(403, r.getStatusCode().value());
        verify(service, never()).update(anyString(), any(), any());
    }

    @Test
    @DisplayName("testing a local server starts it, so an Admin who owns one cannot")
    void testingALocalServerIsGated() {
        storedLocalServer();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/connections/instances/" + LOCAL + "/test"));

        ResponseEntity<?> r = controller.test(LOCAL, exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(as("ROLE_ADMIN"))).block();

        assertEquals(403, r.getStatusCode().value());
        verify(service, never()).resolveOwned(anyString(), any());
    }

    @Test
    @DisplayName("on Desktop and Home Server the one local owner adds a local server")
    void singleUserOwnerMay() {
        ReflectionTestUtils.setField(controller, "multiUser", false);
        ConnectorConnectionController.ConnectionRequest request = new ConnectorConnectionController.ConnectionRequest(
                "Server", "MCP_SERVER", null, null, null, null, null, null, null,
                new ConnectorConnectionController.McpRequest("STDIO", "npx", List.of(), null, null, null, null,
                        null, null, null));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/connections/instances"));

        assertEquals(200, controller.create(request, exchange).block().getStatusCode().value());
    }

    @Test
    @DisplayName("a Super admin can; and anyone can add a remote server for themselves")
    void allowed() {
        assertEquals(200, create("STDIO", null, "ROLE_SUPER_ADMIN"));
        assertEquals(200, create("STREAMABLE_HTTP", true, "ROLE_SUPER_ADMIN"));
        assertEquals(200, create("STREAMABLE_HTTP", null, "ROLE_USER"));
    }
}
