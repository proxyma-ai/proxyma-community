package ai.proxyma.core.connector.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake HTTP API on a loopback port, for connector tests - what every connector built on the
 * framework is tested against, so its test is the real request path (ConnectorHttp, host pinning,
 * retries, pagination) and not a mock of it.
 *
 * <pre>{@code
 * try (LoopbackApi api = LoopbackApi.start()) {
 *     api.on("GET", "/repos/o/r/issues").query("page", "2").json("[...]");
 *     api.on("GET", "/repos/o/r/issues").json("[...]", "Link", "<" + api.url("/repos/o/r/issues?page=2") + ">; rel=\"next\"");
 *     api.on("GET", "/user").status(401).json("{\"message\":\"Bad credentials\"}");
 *     ... the connector against api.baseUrl() ...
 *     assertThat(api.last().header("Authorization")).isEqualTo("Bearer tok");
 * }
 * }</pre>
 *
 * <p>A route matches its method and its path exactly, every query parameter it names (others in
 * the request are ignored) and every piece of body it names ({@link Route#bodyContaining}) - so a
 * token endpoint can answer a refresh and a code exchange differently; the most specific route -
 * the most named parameters and pieces - wins. Answers
 * queued on one route are given in order, the last one repeating: {@code .status(429).header(...)
 * .then().json(...)} scripts a rate limit followed by a success. An unmatched request answers 404,
 * the way an API answers a wrong id, so a connector that builds the wrong path fails on the path.
 * Every request is recorded - method, path, query, headers, body.
 */
public final class LoopbackApi implements AutoCloseable {

    private final HttpServer server;
    private final List<Route> routes = new CopyOnWriteArrayList<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    private LoopbackApi(HttpServer server) {
        this.server = server;
    }

    public static LoopbackApi start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            LoopbackApi api = new LoopbackApi(server);
            server.createContext("/", api::handle);
            server.start();
            return api;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start a loopback API", e);
        }
    }

    /** {@code http://127.0.0.1:<port>} - the base URL to point a connection at. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** An absolute URL on this server - for Link headers and next-page URLs a fixture hands back. */
    public String url(String pathAndQuery) {
        return baseUrl() + (pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery);
    }

    /** A route for {@code method} and {@code path}; script its answers on what this returns. */
    public Route on(String method, String path) {
        Route route = new Route(method.toUpperCase(Locale.ROOT), path);
        routes.add(route);
        return route;
    }

    public List<Recorded> requests() {
        return Collections.unmodifiableList(new ArrayList<>(requests));
    }

    /** The requests made to one path, in order. */
    public List<Recorded> requests(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    public Recorded last() {
        if (requests.isEmpty()) throw new IllegalStateException("No request was made");
        return requests.get(requests.size() - 1);
    }

    public void reset() {
        routes.clear();
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // -- serving --------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            Map<String, String> query = parseQuery(uri.getRawQuery());
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.isEmpty() ? "" : v.get(0)));
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = uri.getRawPath();
            requests.add(new Recorded(method, path, query, headers, body));

            Route best = null;
            for (Route r : routes) {
                if (!r.matches(method, path, query, body)) continue;
                if (best == null || r.specificity() > best.specificity()) best = r;
            }
            Answer answer = best == null ? Answer.notFound() : best.next();
            answer.headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            if (answer.contentType != null) exchange.getResponseHeaders().set("Content-Type", answer.contentType);
            byte[] bytes = answer.body == null ? new byte[0] : answer.body;
            exchange.sendResponseHeaders(answer.status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    /** One request as the server saw it. Header names are lower case. */
    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> headers,
                           String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String param(String name) {
            return query.get(name);
        }
    }

    /** One route: what it matches, and its answers in order. */
    public final class Route {
        private final String method;
        private final String path;
        private final Map<String, String> query = new LinkedHashMap<>();
        private final List<String> bodyPieces = new ArrayList<>();
        private final List<Answer> answers = new ArrayList<>();
        private Answer current = new Answer();
        private int served;

        private Route(String method, String path) {
            this.method = method;
            this.path = path;
            answers.add(current);
        }

        /** Matches only a request carrying this parameter with this value. */
        public Route query(String name, String value) {
            query.put(name, value);
            return this;
        }

        /**
         * Matches only a request whose body contains {@code piece} as it was sent - a form's
         * {@code grant_type=refresh_token}, a JSON body's {@code "query":"..."}.
         */
        public Route bodyContaining(String piece) {
            bodyPieces.add(piece);
            return this;
        }

        public Route status(int status) {
            current.status = status;
            return this;
        }

        public Route header(String name, String value) {
            current.headers.put(name, value);
            return this;
        }

        /** A JSON body - single quotes become double quotes, for readable fixtures. Headers alternate name, value. */
        public Route json(String json, String... headers) {
            current.contentType = "application/json";
            current.body = json.replace('\'', '"').getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i + 1 < headers.length; i += 2) current.headers.put(headers[i], headers[i + 1]);
            return this;
        }

        /** A JSON body taken as it is - for fixtures whose text holds single quotes. */
        public Route rawJson(String json) {
            current.contentType = "application/json";
            current.body = json.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        public Route body(byte[] bytes, String contentType) {
            current.body = bytes;
            current.contentType = contentType;
            return this;
        }

        public Route text(String text) {
            return body(text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
        }

        /** The next answer - given after the ones scripted so far; the last answer repeats. */
        public Route then() {
            current = new Answer();
            answers.add(current);
            return this;
        }

        /** How many requests this route has answered. */
        public int served() {
            return served;
        }

        boolean matches(String m, String p, Map<String, String> q, String body) {
            if (!method.equals(m) || !path.equals(p)) return false;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (!e.getValue().equals(q.get(e.getKey()))) return false;
            }
            for (String piece : bodyPieces) {
                if (body == null || !body.contains(piece)) return false;
            }
            return true;
        }

        int specificity() {
            return query.size() + bodyPieces.size();
        }

        synchronized Answer next() {
            Answer a = answers.get(Math.min(served, answers.size() - 1));
            served++;
            return a;
        }
    }

    private static final class Answer {
        int status = 200;
        final Map<String, String> headers = new LinkedHashMap<>();
        String contentType;
        byte[] body;

        static Answer notFound() {
            Answer a = new Answer();
            a.status = 404;
            a.contentType = "application/json";
            a.body = "{\"message\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8);
            return a;
        }
    }
}
