package ai.proxyma.it.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The way every test talks to every app: plain HTTP, exactly like the SPA does.
 *
 * <h2>It keeps cookies, and that is not a convenience</h2>
 *
 * Proxyma server mode authenticates with a JWT in an HttpOnly cookie, and desktop mode mints a
 * guest-session cookie on the first chat turn and meters against it. A client that dropped cookies
 * would be a different client from the one the product is written for - it would silently start a
 * new session on every call, and a whole class of bug (a session that does not survive, a guest
 * allowance that never decrements) would be untestable. One {@link CookieManager} per instance, so
 * two clients are two browsers and a test can have both.
 *
 * <h2>It never throws on a status</h2>
 *
 * A 400 or a 403 is frequently the assertion - "an anonymous caller may not read another user's
 * calls" is a test about a 403. So responses carry their status and the test decides. {@link
 * Response#ok()} is there for the calls that are setup rather than subject, and it puts the body
 * in the failure message, because a bare "expected 200 but was 400" from a JSON API wastes a
 * debugging session.
 */
public final class Http {

    public static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient client;
    private final List<String> bearer = new ArrayList<>();

    public Http(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10))
                // Never follow redirects automatically. An OAuth2 or a setup redirect is itself
                // the thing under test in several journeys, and a client that quietly followed one
                // would assert against the destination while claiming to assert the hop.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String baseUrl() { return baseUrl; }

    /**
     * A value for a query string, percent-encoded.
     *
     * <p>Every call here takes a path with its query already on it, and {@link URI#create} refuses
     * what a URI may not carry. A Windows path does: its backslashes are illegal in a query, so a
     * test that concatenated a scratch folder into {@code ?path=} passed on the Linux runner and
     * threw before sending anything on the PC. Encode every value that is not a literal.
     */
    public static String query(Object value) {
        return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
    }

    /** Sends {@code Authorization: Bearer <token>} on every subsequent call. */
    private String forwardedFor;
    private boolean patient;

    /**
     * Makes this client a different MACHINE: every request carries {@code X-Forwarded-For: ip},
     * which is what a reverse proxy in front of a server install adds for each visitor.
     *
     * <p>Server mode rate-limits per address, and every client in this suite is 127.0.0.1 - so
     * without this, six people and a visitor share one allowance and the organisation journeys
     * spend it in seconds. With it, each person is limited as they would be in production.
     * Clients that are ABOUT the limiter (the lockout test) do not set it.
     */
    public Http forwardedFor(String ip) {
        this.forwardedFor = ip;
        return this;
    }

    /**
     * Waits out a 429 once, for as long as {@code Retry-After} says, then repeats the request.
     *
     * <p>For clients that are not testing the limiter: a busy person tripping the limit is the
     * product being right, and failing their unrelated assertion for it would be the suite being
     * wrong.
     */
    public Http patient() {
        this.patient = true;
        return this;
    }

    public Http bearer(String token) {
        bearer.clear();
        if (token != null && !token.isBlank()) bearer.add(token);
        return this;
    }

    // -- Verbs -----------------------------------------------------------------

    public Response get(String path) {
        return send(request(path).GET());
    }

    public Response delete(String path) {
        return send(request(path).DELETE());
    }

    public Response post(String path, Object body) {
        return send(request(path)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8)));
    }

    public Response patch(String path, Object body) {
        return send(request(path)
                .header("content-type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8)));
    }

    /**
     * PATCH with a bare string body and {@code text/plain}.
     *
     * <p>{@code SettingsController.saveField} is declared {@code @RequestBody String value}, so
     * the whole body IS the value - {@code PATCH /api/v1/settings/chat/model} carries
     * {@code fake-chat}, not {@code {"value":"fake-chat"}}. Sending JSON there stores the quotes
     * as part of the setting, which then fails much later and somewhere else: the model name
     * {@code "fake-chat"} with quotes is a 404 from the provider, not a validation error here.
     */
    public Response patchText(String path, String value) {
        return send(request(path)
                .header("content-type", "text/plain; charset=utf-8")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8)));
    }

    public Response put(String path, Object body) {
        return send(request(path)
                .header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8)));
    }

    /**
     * POST of raw bytes with explicit headers.
     *
     * <p>For the telemetry ingest endpoints, which read {@code byte[]} and decode the body
     * themselves because it arrives {@code Content-Encoding: gzip} and Spring MVC does not
     * decompress request bodies. A test that posted plain JSON there would exercise a path no
     * installation in the field ever takes.
     */
    public Response postBytes(String path, byte[] body, Map<String, String> headers) {
        HttpRequest.Builder builder = request(path)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        return send(builder);
    }

    /**
     * A multipart upload, hand-assembled because java.net.http has no multipart body publisher.
     *
     * <p>Needed by the file-upload journeys and by the feedback endpoint, both of which are
     * multipart in the product and would otherwise be reachable only through the browser.
     */
    public Response postMultipart(String path, String fieldName, Path file, Map<String, String> fields) {
        String boundary = "----proxyma-it-" + System.nanoTime();
        var out = new java.io.ByteArrayOutputStream();
        try {
            for (var entry : fields.entrySet()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                        + entry.getKey() + "\"\r\n\r\n" + entry.getValue() + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (file != null) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fieldName
                        + "\"; filename=\"" + file.getFileName() + "\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(Files.readAllBytes(file));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return send(request(path)
                .header("content-type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())));
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(120));
        bearer.forEach(token -> builder.header("authorization", "Bearer " + token));
        if (forwardedFor != null) builder.header("x-forwarded-for", forwardedFor);
        return builder;
    }

    private Response send(HttpRequest.Builder builder) {
        HttpRequest request = builder.build();
        Response response = sendOnce(request);
        if (patient && response.status() == 429) {
            long seconds = 61;
            try {
                seconds = Math.min(61, Long.parseLong(response.header("retry-after").trim()));
            } catch (RuntimeException ignored) {
                // No usable Retry-After: a full window is the only reliable reset.
            }
            ManagedProcess.sleep(seconds * 1000L);
            response = sendOnce(request);
        }
        return response;
    }

    private Response sendOnce(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body(), response.headers().map());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String write(Object body) {
        if (body instanceof String s) return s;
        try {
            return JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True when the app answers its health endpoint. The readiness probe for every boot. */
    public boolean healthy(String path) {
        try {
            return get(path).status() == 200;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // -- Server-sent events ----------------------------------------------------

    /**
     * Reads an SSE stream until {@code until} accepts an event or the timeout expires, and returns
     * everything read.
     *
     * <p>Chat is the product's main surface and it is ENTIRELY server-sent events: POST /api/v1/chat
     * returns a conversation id immediately and every token, tool call, approval request and error
     * arrives later on /api/v1/chat/events/{id}. A harness that could only do request/response
     * could assert that a turn was accepted and nothing whatever about what the model said - which
     * is to say, nothing about the product.
     *
     * <p>Subscribing has to happen BEFORE the turn is posted; {@link #openEvents} returns a handle
     * so a test can do that in the right order rather than racing the first token.
     */
    public EventStream openEvents(String path) {
        try {
            HttpResponse<InputStream> response = client.send(
                    request(path).header("accept", "text/event-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("SSE " + path + " answered " + response.statusCode());
            }
            return new EventStream(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** An open SSE subscription, read on a background thread so the test can post while it runs. */
    public static final class EventStream implements AutoCloseable {
        private final InputStream stream;
        private final List<JsonNode> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> raw = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Thread reader;

        EventStream(InputStream stream) {
            this.stream = stream;
            this.reader = new Thread(this::read, "sse-reader");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        private void read() {
            try (var in = new java.io.BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    raw.add(line);
                    if (line.startsWith("data:")) {
                        // Multi-line data blocks concatenate; a blank line ends the event. Spring's
                        // ServerSentEvent writes one data line per event, but the spec allows more
                        // and a reader that assumed one would silently truncate a long payload.
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).trim());
                    } else if (line.isBlank() && data.length() > 0) {
                        String payload = data.toString();
                        data.setLength(0);
                        try {
                            events.add(JSON.readTree(payload));
                        } catch (IOException ignored) {
                            // A non-JSON frame (a comment, a keep-alive) is not an event.
                        }
                    }
                }
            } catch (IOException ignored) {
                // Closed by close(), or by the server ending the stream. Both are normal.
            }
        }

        public List<JsonNode> events() { return new ArrayList<>(events); }

        public List<String> rawLines() { return new ArrayList<>(raw); }

        /** Every event's {@code type}, in order - the readable summary of a turn. */
        public List<String> types() {
            return events().stream().map(e -> e.path("type").asText("")).toList();
        }

        /** Waits for an event matching the predicate and returns it. */
        public JsonNode await(Predicate<JsonNode> matches, Duration timeout, String what) {
            java.time.Instant deadline = java.time.Instant.now().plus(timeout);
            while (java.time.Instant.now().isBefore(deadline)) {
                for (JsonNode event : events()) {
                    if (matches.test(event)) return event;
                }
                ManagedProcess.sleep(100);
            }
            throw new AssertionError("no event matching " + what + " within " + timeout.toSeconds()
                    + "s. Saw: " + types() + "\nFull: " + events());
        }

        /** Waits for an event of this {@code type}. */
        public JsonNode awaitType(String type, Duration timeout) {
            return await(e -> type.equals(e.path("type").asText()), timeout, "type=" + type);
        }

        /**
         * The whole assistant answer, assembled from the streamed token events.
         *
         * <p>Joins on nothing: the product streams whatever fragments the provider sent, and
         * inserting a separator here would make the assembled text differ from what the user sees
         * in the browser - the one thing this method exists to reproduce.
         */
        public String assembledText(String tokenType, String field) {
            StringBuilder text = new StringBuilder();
            for (JsonNode event : events()) {
                if (tokenType.equals(event.path("type").asText())) {
                    text.append(event.path(field).asText(""));
                }
            }
            return text.toString();
        }

        @Override
        public void close() {
            try { stream.close(); } catch (IOException ignored) { }
        }
    }

    // -- Response --------------------------------------------------------------

    public record Response(int status, String body, Map<String, List<String>> headers) {

        /** The body as JSON, or a missing node when it is not JSON at all. */
        public JsonNode json() {
            if (body == null || body.isBlank()) return MissingNode.getInstance();
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                return MissingNode.getInstance();
            }
        }

        public JsonNode at(String pointer) { return json().at(pointer); }

        public String text(String pointer) { return at(pointer).asText(""); }

        /**
         * Asserts 2xx and returns this, with the body in the message when it is not.
         *
         * <p>For the calls that SET UP a test - registering a provider, saving a setting. A call
         * that is the subject of the test should be asserted on explicitly instead, so the
         * expected status is written down.
         */
        public Response ok() {
            if (status < 200 || status >= 300) {
                throw new AssertionError("expected 2xx but got " + status + ": " + body);
            }
            return this;
        }

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst().orElse(null);
        }
    }
}
