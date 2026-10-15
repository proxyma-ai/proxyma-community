package ai.proxyma.core.tools.mcpclient;

import ai.proxyma.core.settings.connection.ConnectionChangedEvent;
import ai.proxyma.core.settings.connection.McpServerConfig;
import ai.proxyma.core.settings.connection.ResolvedMcpServer;
import ai.proxyma.core.system.util.AppPaths;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One live MCP client per connection, opened on first use and shared by every conversation that
 * uses the server.
 *
 * <ul>
 *   <li><b>Rebuilt when the connection changes.</b> Every save publishes ConnectionChangedEvent,
 *       which closes the client; the next use opens a new one with the new settings. A client also
 *       carries the version of the settings it was built from (the row's update time), so one
 *       opened in the instant before a save is not used after it.</li>
 *   <li><b>Not redialled while unreachable.</b> See BACKOFF_FIRST.</li>
 *   <li><b>Closed when idle.</b> A local server is a process; one nobody has called for
 *       {@link #IDLE} is stopped, and started again when next needed.</li>
 *   <li><b>Retried once, only where that cannot run it twice.</b> A server that restarted and lost
 *       the session, or a connection that never opened, fails the first call on the old client; the
 *       client is thrown away and the call made once more on a new one. See
 *       looksLikeABrokenConnection.</li>
 * </ul>
 *
 * <p>The tool list is kept with the client, and remembered after it closes, so a skill's
 * description and the tools' names can be given without starting a server just to list them.
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    static final Duration IDLE = Duration.ofMinutes(10);
    static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(30);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    /** What a connected server is: its client, what it said about itself, and its tools. */
    record Session(UUID id, String version, McpSyncClient client, String serverName, String instructions,
                   List<McpSchema.Tool> tools, Instant openedAt, java.util.concurrent.atomic.AtomicReference<Instant> lastUsed) {}

    private final WebClient.Builder webClients;
    private final McpOAuthService oauth;
    private final AppPaths paths;
    private final String version;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    // The last tool list each server gave, kept after its session closes.
    private final Map<UUID, List<McpSchema.Tool>> knownTools = new ConcurrentHashMap<>();
    // One opening at a time per server: two conversations asking at once share the first.
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    // A server that could not be reached is not dialled again for a while. The chat asks for a
    // server's tools on every step of every turn, so a black-holed host cost up to thirty seconds
    // per model call; now the first failure is reported and the next tries wait out a delay that
    // doubles, from BACKOFF_FIRST up to BACKOFF_MAX. A save, a Test, or a success clears it.
    static final Duration BACKOFF_FIRST = Duration.ofSeconds(60);
    static final Duration BACKOFF_MAX = Duration.ofMinutes(10);

    private record Backoff(Instant until, Duration delay, String reason) {}

    private final Map<UUID, Backoff> backoffs = new ConcurrentHashMap<>();

    // The version is the build's own (spring.application.version, as every other component reads
    // it): proxyma.version is set nowhere, so every MCP server was told it was talking to version
    // "unknown". The paths are the application's bean, so a local server's log lands under the
    // configured data directory - on a server, the data volume - rather than the process's home.
    @org.springframework.beans.factory.annotation.Autowired
    public McpClientManager(@Qualifier("externalWebClientBuilder") WebClient.Builder webClients,
                            McpOAuthService oauth,
                            @Value("${spring.application.version:unknown}") String version,
                            AppPaths paths) {
        this.webClients = webClients;
        this.oauth = oauth;
        this.paths = paths;
        this.version = version;
    }

    McpClientManager(WebClient.Builder webClients, McpOAuthService oauth, String version) {
        this(webClients, oauth, version, AppPaths.create());
    }

    /** The open session for this server, opening it if need be. Throws a readable failure. */
    Session session(ResolvedMcpServer server) {
        Session existing = sessions.get(server.id());
        if (existing != null && existing.version().equals(server.updatedAt())) {
            existing.lastUsed().set(Instant.now());
            return existing;
        }
        synchronized (locks.computeIfAbsent(server.id(), k -> new Object())) {
            existing = sessions.get(server.id());
            if (existing != null && existing.version().equals(server.updatedAt())) {
                existing.lastUsed().set(Instant.now());
                return existing;
            }
            if (existing != null) close(sessions.remove(server.id()));
            Backoff waiting = backoffs.get(server.id());
            if (waiting != null && Instant.now().isBefore(waiting.until())) {
                throw new McpServerUnavailableException(server.name(),
                        new IllegalStateException(waiting.reason() + " (not tried again until "
                                + waiting.until().truncatedTo(java.time.temporal.ChronoUnit.SECONDS) + ")"));
            }
            Session opened;
            try {
                opened = open(server);
            } catch (RuntimeException e) {
                Duration delay = waiting == null ? BACKOFF_FIRST
                        : (waiting.delay().multipliedBy(2).compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : waiting.delay().multipliedBy(2));
                backoffs.put(server.id(), new Backoff(Instant.now().plus(delay), delay,
                        McpServerUnavailableException.reason(e)));
                throw e;
            }
            backoffs.remove(server.id());
            sessions.put(server.id(), opened);
            knownTools.put(server.id(), opened.tools());
            return opened;
        }
    }

    /** Calls one tool, once more on a fresh client if the first attempt's connection was broken. */
    McpSchema.CallToolResult call(ResolvedMcpServer server, String tool, Map<String, Object> args) {
        try {
            return session(server).client().callTool(new McpSchema.CallToolRequest(tool, args));
        } catch (RuntimeException first) {
            if (!looksLikeABrokenConnection(first)) throw first;
            log.info("[MCP] '{}' call failed on its connection ({}); reconnecting once", server.name(), first.getMessage());
            evict(server.id());
            return session(server).client().callTool(new McpSchema.CallToolRequest(tool, args));
        }
    }

    /** The tools this server listed last, without connecting - empty if it never has. */
    List<McpSchema.Tool> knownTools(UUID id) {
        return knownTools.getOrDefault(id, List.of());
    }

    /**
     * Connects with these settings without keeping the client - the connection's Test. Says how
     * many tools the server offers, or why it could not be reached.
     */
    ProbeResult probe(ResolvedMcpServer server) {
        // The Test button is somebody asking now: it always dials, and a success ends the wait.
        Session s = open(server);
        backoffs.remove(server.id());
        try {
            knownTools.put(server.id(), s.tools());
            return new ProbeResult(s.serverName(), s.tools().size());
        } finally {
            close(s);
        }
    }

    record ProbeResult(String serverName, int tools) {}

    private Session open(ResolvedMcpServer server) {
        McpClientTransport transport = McpTransports.build(server, webClients, bearerFor(server), paths);
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("proxyma", "Proxyma", version))
                .initializationTimeout(INITIALIZE_TIMEOUT)
                .requestTimeout(REQUEST_TIMEOUT)
                .build();
        try {
            McpSchema.InitializeResult init = client.initialize();
            List<McpSchema.Tool> tools = listAll(client);
            String name = init.serverInfo() == null ? server.name() : init.serverInfo().name();
            log.info("[MCP] connected to '{}' ({}): {} tool(s)", server.name(), server.config().transport(), tools.size());
            return new Session(server.id(), server.updatedAt(), client, name, init.instructions(), tools,
                    Instant.now(), new java.util.concurrent.atomic.AtomicReference<>(Instant.now()));
        } catch (RuntimeException e) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // The failure being reported is the one that matters.
            }
            throw new McpServerUnavailableException(server.name(), e);
        }
    }

    // Every page of the list: a server may answer in pages with a cursor.
    private static List<McpSchema.Tool> listAll(McpSyncClient client) {
        List<McpSchema.Tool> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            McpSchema.ListToolsResult result = cursor == null ? client.listTools() : client.listTools(cursor);
            if (result.tools() != null) all.addAll(result.tools());
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) break;
        }
        return List.copyOf(all);
    }

    // The token an HTTP request carries: a fixed bearer token, or an OAuth access token asked for per
    // request (McpOAuthService refreshes it). None for a local command or a server with no auth.
    private Function<Boolean, String> bearerFor(ResolvedMcpServer server) {
        McpServerConfig config = server.config();
        if (config.isStdio()) return null;
        return switch (config.auth()) {
            case BEARER -> refused -> server.secrets().bearerToken();
            case OAUTH -> refused -> oauth.accessToken(server.id(), server.url(), refused);
            default -> null;
        };
    }

    /**
     * Whether a failed call can be sent again WITHOUT the risk of running twice: only a failure that
     * happened before the server could have acted on it - the connection never opened, or the
     * server said the session it was sent on is gone (which, per the protocol, means it was not
     * processed). Anything else may already have created, merged or deleted something on the
     * remote system. This matched any message with "closed" or "session" in it, so a JSON-RPC
     * answer such as "Issue PAY-1 is closed", or a reset after the server had executed the call,
     * ran a write a second time.
     */
    static boolean looksLikeABrokenConnection(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof io.modelcontextprotocol.spec.McpError) return false;
            if (t instanceof io.modelcontextprotocol.spec.McpTransportSessionNotFoundException
                    || t instanceof io.modelcontextprotocol.spec.McpTransportSessionClosedException
                    || t instanceof java.net.ConnectException) {
                return true;
            }
        }
        return false;
    }

    @EventListener
    public void onConnectionChanged(ConnectionChangedEvent event) {
        evict(event.connectionId());
        if (event.connectionId() != null) backoffs.remove(event.connectionId());
        if (event.deleted()) {
            knownTools.remove(event.connectionId());
            oauth.forget(event.connectionId());
        }
    }

    void evict(UUID id) {
        if (id == null) return;
        Session s = sessions.remove(id);
        if (s != null) close(s);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void closeIdle() {
        Instant cutoff = Instant.now().minus(IDLE);
        sessions.values().stream().filter(s -> s.lastUsed().get().isBefore(cutoff)).toList()
                .forEach(s -> {
                    if (sessions.remove(s.id(), s)) {
                        log.debug("[MCP] closing idle connection to '{}'", s.serverName());
                        close(s);
                    }
                });
    }

    @PreDestroy
    public void closeAll() {
        new ArrayList<>(sessions.values()).forEach(this::close);
        sessions.clear();
    }

    Optional<Session> openSession(UUID id) {
        return Optional.ofNullable(sessions.get(id));
    }

    private void close(Session s) {
        if (s == null) return;
        try {
            if (!s.client().closeGracefully()) s.client().close();
        } catch (RuntimeException e) {
            log.debug("[MCP] closing '{}' failed: {}", s.serverName(), e.getMessage());
        }
    }

    /** A server that could not be reached or would not initialize, said so a person can act on it. */
    public static final class McpServerUnavailableException extends RuntimeException {
        McpServerUnavailableException(String server, Throwable cause) {
            super("The MCP server '" + server + "' could not be reached: " + reason(cause), cause);
        }

        static String reason(Throwable e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String m = root.getMessage();
            return m == null || m.isBlank() ? root.getClass().getSimpleName() : m;
        }
    }
}
