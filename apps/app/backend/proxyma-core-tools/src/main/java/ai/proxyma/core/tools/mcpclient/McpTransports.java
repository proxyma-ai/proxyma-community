package ai.proxyma.core.tools.mcpclient;

import ai.proxyma.core.common.net.OutboundAddressPolicy;
import ai.proxyma.core.settings.connection.McpServerConfig;
import ai.proxyma.core.settings.connection.ResolvedMcpServer;
import ai.proxyma.core.system.util.AppPaths;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the MCP SDK transport for one connection - streamable HTTP, SSE or a local command.
 *
 * <p>HTTP AND SSE GO THROUGH THE APP'S OWN WEB CLIENT ({@code externalWebClientBuilder}): the proxy
 * and the custom CA from Settings &gt; Network apply, as they do to every other server Proxyma
 * talks to. The SDK's own JDK-HttpClient transports would ignore both. The URL is checked against
 * the outbound policy here as well as when it was saved - on the public sandbox a saved URL could
 * otherwise reach a private address through a DNS answer that changed since.
 *
 * <p>A LOCAL COMMAND STARTS WITH A CLEAN ENVIRONMENT: only what a program needs to run (PATH, the
 * system root, the home and temp directories, the locale) plus the variables the connection names.
 * Proxyma's own environment can carry provider keys and database passwords, and a server someone
 * downloaded has no business reading them. Its standard error goes to {@code logs/mcp-<name>.log}.
 */
final class McpTransports {

    private static final Logger log = LoggerFactory.getLogger(McpTransports.class);

    /**
     * What a local server inherits from Proxyma's environment - nothing else. It has to include the
     * SDK's own defaults (on Linux HOME, LOGNAME, PATH, SHELL, TERM and USER), because
     * {@link ServerParameters} merges those into every server's variables and the SDK applies them
     * AFTER {@link CleanEnvStdioTransport#getProcessBuilder()} has cleaned the environment.
     */
    static final Set<String> INHERITED_ENV = Set.of(
            "PATH", "PATHEXT", "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "TEMP", "TMP", "TMPDIR",
            "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "APPDATA", "LOCALAPPDATA", "PROGRAMFILES",
            "PROGRAMFILES(X86)", "PROGRAMDATA", "LANG", "LC_ALL", "LC_CTYPE", "USER", "USERNAME", "LOGNAME",
            "SHELL", "TERM", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS");

    /** Each standard-error log is cut back to its tail past this size. */
    private static final long MAX_LOG_BYTES = 2L * 1024 * 1024;

    static final McpJsonMapper JSON = new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper());

    // The largest single message read from a server - a tool result or an event.
    static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private McpTransports() {}

    /**
     * @param webClients the app's external web client builder, cloned here - it is a shared bean
     * @param bearer     the token to send with each HTTP request, asked for per request so an OAuth
     *                   token that was refreshed is the one sent; null for none
     */
    static McpClientTransport build(ResolvedMcpServer server, WebClient.Builder webClients,
                                    Function<Boolean, String> bearer, AppPaths paths) {
        McpServerConfig config = server.config();
        if (config.isStdio()) return stdio(server, paths);

        String url = server.url();
        if (url == null || url.isBlank()) throw new IllegalStateException("This MCP server has no URL.");
        OutboundAddressPolicy.check(url);
        URI uri = URI.create(url);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());

        // A tool's result arrives as one JSON body or SSE event, read whole: WebClient's default
        // buffer of 256 KB failed any larger one ("Exceeded limit on max bytes to buffer").
        WebClient.Builder builder = webClients.clone().baseUrl(origin)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_MESSAGE_BYTES));
        if (config.auth() == McpServerConfig.Auth.HEADERS) {
            server.secrets().headers().forEach((name, value) -> builder.defaultHeader(name, value));
        }
        if (bearer != null) builder.filter(bearerFilter(bearer));

        if (config.transport() == McpServerConfig.Transport.SSE) {
            return WebFluxSseClientTransport.builder(builder).sseEndpoint(endpoint).jsonMapper(JSON).build();
        }
        return WebClientStreamableHttpTransport.builder(builder).endpoint(endpoint).jsonMapper(JSON).build();
    }

    // The token is asked for when each request is made, never captured once: an OAuth access token
    // expires within the hour while a connection can live all day. A 401 asks once more with
    // `true` - "this one was refused" - so a token revoked early is refreshed rather than resent.
    private static ExchangeFilterFunction bearerFilter(Function<Boolean, String> bearer) {
        return (request, next) -> Mono.fromCallable(() -> bearer.apply(false))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(token -> next.exchange(withToken(request, token)))
                .flatMap(response -> {
                    if (response.statusCode().value() != 401) return Mono.just(response);
                    return response.releaseBody()
                            .then(Mono.fromCallable(() -> bearer.apply(true)).subscribeOn(Schedulers.boundedElastic()))
                            .flatMap(token -> next.exchange(withToken(request, token)));
                });
    }

    private static ClientRequest withToken(ClientRequest request, String token) {
        if (token == null || token.isBlank()) return request;
        return ClientRequest.from(request).headers(h -> h.setBearerAuth(token)).build();
    }

    // -- stdio ---------------------------------------------------------------------------------------

    private static McpClientTransport stdio(ResolvedMcpServer server, AppPaths paths) {
        McpServerConfig config = server.config();
        String command = config.command();
        if (command == null || command.isBlank()) throw new IllegalStateException("This MCP server has no command.");
        List<String> args = new ArrayList<>(config.args());
        String program = command.trim();
        // On Windows a launcher such as npx or uvx is a .cmd script, which a process cannot be
        // started from directly: it runs through cmd.exe, as a terminal would run it. cmd.exe reads
        // & | < > ^ % and quotes in its command line as its own syntax - an argument such as a URL
        // with a=1&b=2 ran its second half as a command - so a line that would be read that way is
        // refused rather than passed through.
        if (isWindows() && needsShell(program)) {
            refuseShellSyntax(program, args);
            args.add(0, program);
            args.add(0, "/c");
            program = "cmd.exe";
        }
        ServerParameters params = ServerParameters.builder(program).args(args).env(server.secrets().env()).build();
        Path logFile = paths == null ? null
                : paths.logsDir().resolve("mcp-" + McpToolNames.slug(server.name(), 40) + ".log");
        StdioClientTransport transport = new CleanEnvStdioTransport(params, server.secrets().env());
        transport.setStdErrorHandler(line -> appendLog(logFile, line));
        return transport;
    }

    /** The SDK's stdio transport, started with {@link #INHERITED_ENV} and the connection's own variables only. */
    static final class CleanEnvStdioTransport extends StdioClientTransport {
        private final Map<String, String> env;

        CleanEnvStdioTransport(ServerParameters params, Map<String, String> env) {
            super(params, JSON);
            this.env = env;
        }

        @Override
        protected ProcessBuilder getProcessBuilder() {
            ProcessBuilder builder = super.getProcessBuilder();
            Map<String, String> environment = builder.environment();
            environment.keySet().removeIf(name -> !INHERITED_ENV.contains(name.toUpperCase(Locale.ROOT)));
            environment.putAll(env);
            return builder;
        }
    }

    // The characters cmd.exe gives a meaning of its own.
    private static final String CMD_SYNTAX = "&|<>^%\"!";

    static void refuseShellSyntax(String program, List<String> args) {
        List<String> all = new ArrayList<>(args);
        all.add(0, program);
        for (String a : all) {
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                if (CMD_SYNTAX.indexOf(a.charAt(i)) >= 0) {
                    throw new IllegalStateException("The command or an argument contains '" + a.charAt(i)
                            + "', which Windows would read as part of a command when starting this server "
                            + "through cmd.exe. Use the full path of an .exe, or remove the character.");
                }
            }
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // A bare name with no extension (npx, uvx, docker) or an explicit .cmd/.bat script.
    static boolean needsShell(String program) {
        String lower = program.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cmd") || lower.endsWith(".bat")) return true;
        String file = Path.of(program).getFileName() == null ? program : Path.of(program).getFileName().toString();
        return !file.contains(".");
    }

    private static void appendLog(Path file, String line) {
        if (file == null || line == null) return;
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_LOG_BYTES) {
                byte[] all = Files.readAllBytes(file);
                byte[] tail = java.util.Arrays.copyOfRange(all, all.length / 2, all.length);
                Files.write(file, tail, StandardOpenOption.TRUNCATE_EXISTING);
            }
            Files.writeString(file, java.time.Instant.now() + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("[MCP] could not write {}: {}", file, e.getMessage());
        }
    }
}
