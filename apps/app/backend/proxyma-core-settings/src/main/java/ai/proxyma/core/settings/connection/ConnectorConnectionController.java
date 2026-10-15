package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectionTokenProvider;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.TokenSupplier;

import ai.proxyma.core.connector.ConnectionProtocol;

import ai.proxyma.core.settings.access.CallerRoles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * The caller's own connections. Every handler is scoped to the authenticated principal - there is
 * no parameter naming a user, so nobody can read or edit another person's connection by asking.
 * Desktop and Home Server have no principal and file everything under one local owner.
 *
 * SecurityConfig requires authentication for /api/v1/connections/**: a guest owns no connections.
 */
@RestController
@RequestMapping("/api/v1/connections")
public class ConnectorConnectionController {

    private final ConnectorConnectionService service;
    // The MCP client side lives in proxyma-core-tools, above this module; absent in tests.
    private final ObjectProvider<McpServerProbe> mcpProbe;
    // Every service's Test is its ConnectionPlugin's, found through the catalog.
    private final ConnectorCatalog catalog;
    private final ConnectorHttp http;
    private final ObjectProvider<ConnectionTokenProvider> tokenProviders;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    @org.springframework.beans.factory.annotation.Autowired
    public ConnectorConnectionController(ConnectorConnectionService service, ObjectProvider<McpServerProbe> mcpProbe,
                                         ConnectorCatalog catalog, ConnectorHttp http,
                                         ObjectProvider<ConnectionTokenProvider> tokenProviders) {
        this.service = service;
        this.mcpProbe = mcpProbe;
        this.catalog = catalog;
        this.http = http;
        this.tokenProviders = tokenProviders;
    }

    /** Over these testers alone - what a test that builds the controller by hand needs. */
    public ConnectorConnectionController(ConnectorConnectionService service, List<ConnectionTester> testers,
                                         ObjectProvider<McpServerProbe> mcpProbe) {
        this(service, mcpProbe, ConnectorCatalog.of(List.of(), new LegacyConnectionPlugins(testers).connectionPlugins()),
                ConnectorHttp.direct(), null);
    }

    /**
     * {@code secret} is write-only and absent from every response, so a client echoing back what it
     * was given sends null - "unchanged". Clearing is the explicit {@code clearSecret}.
     */
    public record ConnectionRequest(String name, String protocol, String baseUrl, String username,
            String secret, Boolean clearSecret, Boolean isDefault, Boolean allowRead, Boolean allowWrite,
            McpRequest mcp, String authMethod, Map<String, String> fields) {

        /** The shape before sign-in methods - what older clients send. */
        public ConnectionRequest(String name, String protocol, String baseUrl, String username, String secret,
                                 Boolean clearSecret, Boolean isDefault, Boolean allowRead, Boolean allowWrite,
                                 McpRequest mcp) {
            this(name, protocol, baseUrl, username, secret, clearSecret, isDefault, allowRead, allowWrite, mcp, null, null);
        }
    }

    /** An MCP server's settings - see {@link ConnectorConnectionService.McpInput}. Values are write-only. */
    public record McpRequest(String transport, String command, List<String> args, String auth,
            Map<String, String> headers, Map<String, String> env, Boolean shared,
            String oauthClientId, String oauthClientSecret, String oauthScope) {}

    @GetMapping("/protocols")
    public Mono<ResponseEntity<List<ConnectorConnectionView.ProtocolView>>> protocols() {
        return Mono.just(ResponseEntity.ok(ConnectorConnectionView.protocols(service.offeredProtocolDescriptors())));
    }

    @GetMapping("/instances")
    public Mono<ResponseEntity<List<ConnectorConnectionView>>> list(ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> Mono.fromCallable(() -> ResponseEntity.ok(service.list(uid)))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/instances/{id}")
    public Mono<ResponseEntity<ConnectorConnectionView>> get(@PathVariable UUID id, ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> Mono.fromCallable(() -> ResponseEntity.ok(service.get(uid, id)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(NoSuchElementException.class, e -> Mono.just(ResponseEntity.notFound().build())));
    }

    @PostMapping("/instances")
    public Mono<ResponseEntity<?>> create(@RequestBody ConnectionRequest request, ServerWebExchange exchange) {
        return refusedMcp(request, null, exchange).flatMap(refusal -> refusal.isPresent()
                ? Mono.<ResponseEntity<?>>just(refusal.get())
                : userId(exchange).flatMap(uid -> Mono.<ResponseEntity<?>>fromCallable(
                        () -> ResponseEntity.ok(service.create(uid, toInput(request))))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ConnectorConnectionService.InvalidConnectionException.class,
                        ConnectorConnectionController::badRequest)));
    }

    @PatchMapping("/instances/{id}")
    public Mono<ResponseEntity<?>> update(@PathVariable UUID id, @RequestBody ConnectionRequest request,
                                          ServerWebExchange exchange) {
        return refusedMcp(request, id, exchange).flatMap(refusal -> refusal.isPresent()
                ? Mono.<ResponseEntity<?>>just(refusal.get())
                : userId(exchange).flatMap(uid -> Mono.<ResponseEntity<?>>fromCallable(
                        () -> ResponseEntity.ok(service.update(uid, id, toInput(request))))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(NoSuchElementException.class, e -> Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(ConnectorConnectionService.InvalidConnectionException.class,
                        ConnectorConnectionController::badRequest)));
    }

    /**
     * A LOCAL MCP server - a program this server starts - and a server shared with everyone are a
     * Super admin's to add (maintainer's decision, 2026-10-13): the first runs a command as the
     * server process, the second puts a tool in every user's agent. Checked against the caller's
     * role, before anything is saved.
     *
     * <p>IN EVERY EDITION, since isd-01. It used to return at once off Enterprise Server, so the gate
     * did not exist where there is no role to ask; now it is asked everywhere and the single-user
     * editions answer it with their one local owner (CallerRoles) - the person the edition's own
     * front door admits, which is what decides who may reach this at all there.
     *
     * <p>Asked of the transport the connection WILL have: an edit that changes a local server's
     * command without naming its transport is still a local server, and used to pass as if it were
     * a remote one.
     */
    private Mono<Optional<ResponseEntity<?>>> refusedMcp(ConnectionRequest request, UUID id,
                                                          ServerWebExchange exchange) {
        if (request.mcp() == null) return Mono.just(Optional.empty());
        boolean shares = Boolean.TRUE.equals(request.mcp().shared());
        String named = request.mcp().transport();
        Mono<Boolean> stdio = named != null && !named.isBlank() || id == null
                ? Mono.just("STDIO".equalsIgnoreCase(named == null ? null : named.trim()))
                : storedStdio(exchange, id);
        return stdio.flatMap(local -> {
            if (!local && !shares) return Mono.just(Optional.<ResponseEntity<?>>empty());
            return CallerRoles.isSuperAdmin(multiUser).map(allowed -> allowed ? Optional.<ResponseEntity<?>>empty()
                    : Optional.<ResponseEntity<?>>of(ResponseEntity.status(403).body(Map.of("error", local
                            ? "Only a Super admin can add an MCP server that runs on this server."
                            : "Only a Super admin can share an MCP server with everyone."))));
        });
    }

    // Whether the caller's own connection with this id is a local MCP server. False for anything
    // else, a missing one included - the write that follows answers 404 for that itself.
    private Mono<Boolean> storedStdio(ServerWebExchange exchange, UUID id) {
        return userId(exchange).flatMap(uid -> Mono.fromCallable(() -> service.resolveOwnedMcp(uid, id)
                        .map(m -> m.config().isStdio()).orElse(false))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @DeleteMapping("/instances/{id}")
    public Mono<ResponseEntity<?>> delete(@PathVariable UUID id, ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> Mono.<ResponseEntity<?>>fromCallable(() -> {
                    service.delete(uid, id);
                    return ResponseEntity.noContent().build();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(NoSuchElementException.class, e -> Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(ConnectorConnectionService.InvalidConnectionException.class,
                        ConnectorConnectionController::badRequest));
    }

    /**
     * One authenticated call to the service. Always 200 with {ok, message, template, params}; 404 for
     * a connection that is not the caller's.
     *
     * <p>Testing a LOCAL MCP server starts its command, so it takes the same gate as adding one
     * (isd-01): an owner who is no longer a Super admin keeps the connection and cannot run it.
     */
    @PostMapping("/instances/{id}/test")
    public Mono<ResponseEntity<?>> test(@PathVariable UUID id, ServerWebExchange exchange) {
        return storedStdio(exchange, id).flatMap(local -> local
                ? CallerRoles.isSuperAdmin(multiUser)
                : Mono.just(true)).flatMap(allowed -> allowed ? runTest(id, exchange)
                : Mono.just(ResponseEntity.status(403).body(Map.of("error",
                        "Only a Super admin can run an MCP server on this server."))));
    }

    private Mono<ResponseEntity<?>> runTest(UUID id, ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> Mono.<ResponseEntity<?>>fromCallable(() -> {
                    ResolvedConnection c = service.resolveOwned(uid, id).orElseThrow(NoSuchElementException::new);
                    ConnectionTestResult result = c.protocol() == ConnectionProtocol.MCP_SERVER
                            ? probeMcp(uid, id)
                            : catalog.connectionPlugin(c.protocol())
                                .map(plugin -> runTest(plugin, c))
                                .orElse(ConnectionTestResult.failed("This connection type has no test."));
                    // Only a failure is worth an entry: it is what someone reading the log after a
                    // broken sync looks for.
                    if (!result.ok()) {
                        AuditEntry.of(AuditCategory.SOURCES, "connection.test-failed")
                                .user(uid)
                                .outcome(AuditEntry.FAILED)
                                .target(c.name())
                                .summary("Connection test failed: " + c.name())
                                .detail("server", c.baseUrl())
                                .detail("error", result.message())
                                .record();
                    }
                    // The template and its values are what the page translates (connectionTestMessage);
                    // the message is the English, for anything that does not know the template.
                    return ResponseEntity.ok(Map.of("ok", result.ok(), "message", result.message(),
                            "template", result.template(), "params", result.params()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(NoSuchElementException.class, e -> Mono.just(ResponseEntity.notFound().build())));
    }

    private ConnectionTestResult probeMcp(String uid, UUID id) {
        McpServerProbe probe = mcpProbe.getIfAvailable();
        if (probe == null) return ConnectionTestResult.failed("This connection type has no test.");
        ConnectionTester.Result r = probe.probe(service.resolveOwnedMcp(uid, id).orElseThrow(NoSuchElementException::new));
        return new ConnectionTestResult(r.ok(), r.message(), r.template(), r.params());
    }

    // An older service is tested with the whole connection its tester was written for; a service on
    // the connector framework with what a session would hold - its secrets, its settings and, for a
    // sign-in, a token supplier that refreshes. A plugin that throws is a failed test, never a 500.
    private ConnectionTestResult runTest(ConnectionPlugin plugin, ResolvedConnection c) {
        try {
            if (plugin instanceof LegacyConnectionPlugins.Adapter legacy) return legacy.test(c);
            TokenSupplier tokens = tokenProviders == null ? null : tokenProviders.orderedStream()
                    .filter(p -> p.supports(c.protocol(), c.authKind()))
                    .findFirst().map(p -> p.tokensFor(c.id())).orElse(null);
            return plugin.test(new ResolvedCredentials(c.id(), c.protocol(), c.authKind(), c.baseUrl(), c.username(),
                    c.secrets(), c.settings(), tokens, ai.proxyma.core.connector.spi.ConfigJson.parse(c.accountJson())), http);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getMessage() == null ? "The test could not run." : e.getMessage());
        }
    }

    static ConnectorConnectionService.ConnectionInput toInput(ConnectionRequest r) {
        AuthKind method = null;
        if (r.authMethod() != null && !r.authMethod().isBlank()) {
            try {
                method = AuthKind.valueOf(r.authMethod().trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ConnectorConnectionService.InvalidConnectionException("'" + r.authMethod() + "' is not a way to sign in.");
            }
        }
        return new ConnectorConnectionService.ConnectionInput(r.name(), ConnectionProtocol.fromValue(r.protocol()),
                r.baseUrl(), r.username(), r.secret(), Boolean.TRUE.equals(r.clearSecret()), r.isDefault(),
                r.allowRead(), r.allowWrite(), toMcpInput(r.mcp()), method, r.fields());
    }

    static ConnectorConnectionService.McpInput toMcpInput(McpRequest m) {
        if (m == null) return null;
        return new ConnectorConnectionService.McpInput(enumOf(McpServerConfig.Transport.class, m.transport()),
                m.command(), m.args(), enumOf(McpServerConfig.Auth.class, m.auth()), m.headers(), m.env(),
                m.shared(), m.oauthClientId(), m.oauthClientSecret(), m.oauthScope());
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConnectorConnectionService.InvalidConnectionException("'" + value + "' is not a known option.");
        }
    }

    private static Mono<ResponseEntity<?>> badRequest(Throwable e) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", e.getMessage())));
    }

    // "" when there is no principal (Desktop, Home Server), which the service files under the local owner.
    private static Mono<String> userId(ServerWebExchange exchange) {
        return CallerRoles.userId(exchange);
    }
}
