package ai.proxyma.it.harness;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The landing site, served the way Cloudflare serves it.
 *
 * <p>{@code apps/landing} is a Next.js STATIC EXPORT - {@code output: 'export'}, so the build
 * produces a directory of HTML files and there is no Node process in production at all. Running
 * {@code next start} to test it would exercise a server that no visitor ever reaches, and would
 * hide precisely the class of bug a static host produces: a link to {@code /pricing} that works
 * under a dev server and 404s on a host that only has {@code /pricing/index.html}.
 *
 * <p>So this serves the built directory over plain HTTP with the same two rules the real host
 * applies - {@code trailingSlash: true} means a directory resolves to its {@code index.html}, and
 * anything missing is the site's own {@code 404.html}.
 */
public final class StaticSite implements AutoCloseable {

    private final HttpServer server;
    private final Path root;

    private StaticSite(HttpServer server, Path root) {
        this.server = server;
        this.root = root;
    }

    /** The built landing site, or a skip explaining how to build it. */
    public static StaticSite landing() {
        Path out = Env.repoRoot().resolve("apps/landing/out");
        Env.require(Files.isDirectory(out) && Files.exists(out.resolve("index.html")),
                "the landing site has not been built. Build it with:"
                + " cd apps/landing && npm ci && npm run build"
                + " (it needs NEXT_PUBLIC_SITE_ENV set)");
        return serve(out);
    }

    public static StaticSite serve(Path root) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StaticSite site = new StaticSite(server, root);
            server.createContext("/", exchange -> {
                try {
                    site.handle(exchange.getRequestURI().getPath(), exchange);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return site;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handle(String path, com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        Path file = resolve(path);
        if (file == null) {
            // The site's own 404 page, not a bare status. What a visitor sees when a link rots is
            // part of the site, and serving a blank body here would make a test unable to tell a
            // missing page from a broken one.
            Path notFound = root.resolve("404.html");
            byte[] body = Files.exists(notFound)
                    ? Files.readAllBytes(notFound)
                    : "not found".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
            return;
        }
        byte[] body = Files.readAllBytes(file);
        exchange.getResponseHeaders().add("content-type", contentType(file));
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    }

    /**
     * Maps a URL path onto a file, or null.
     *
     * <p>Normalizes against the root and refuses anything that escapes it. That is not defending
     * this test server from attack - nothing reaches it but a local browser - it is making sure a
     * path bug in the SITE shows up as a 404 here rather than quietly reading a file from the
     * repository.
     */
    private Path resolve(String path) {
        Path candidate = root.resolve(path.replaceFirst("^/", "")).normalize();
        if (!candidate.startsWith(root)) return null;
        if (Files.isDirectory(candidate)) {
            Path index = candidate.resolve("index.html");
            return Files.isRegularFile(index) ? index : null;
        }
        if (Files.isRegularFile(candidate)) return candidate;
        // A bare /pricing with no trailing slash: the export writes /pricing/index.html, and the
        // real host redirects. Resolving it here keeps a test about CONTENT from failing over a
        // redirect this server does not implement.
        Path asDirectory = root.resolve(path.replaceFirst("^/", "") + "/index.html").normalize();
        return Files.isRegularFile(asDirectory) && asDirectory.startsWith(root) ? asDirectory : null;
    }

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("xml", "application/xml"));

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        return TYPES.getOrDefault(extension, "application/octet-stream");
    }

    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public Path root() { return root; }

    @Override
    public void close() { server.stop(0); }
}
