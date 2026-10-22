package ai.proxyma.core.connector.spi;

import ai.proxyma.core.common.net.OutboundAddressPolicy;
import ai.proxyma.core.connector.DotSegmentGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One connector's client for one service: every request a session makes goes through here, so
 * every connector gets the same answers to the questions that used to be answered fifty times -
 * in fifty slightly different ways - across the connectors that predate it.
 *
 * <ul>
 *   <li><b>Pinned to its server.</b> A path is resolved against the base URL; an absolute URL - a
 *       pagination link, a URL a response named - is followed only when it is on the same scheme,
 *       host and port. The credential is never sent anywhere else: a redirect to another host is
 *       followed without it, a URL on another host is refused.
 *   <li><b>Checked against the outbound policy</b> (OutboundAddressPolicy) at every hop, and refused
 *       when its path climbs with a dot segment (DotSegmentGuard).
 *   <li><b>Retried</b> on 429, 502, 503, 504 (and any status a connector adds, such as Notion's 529)
 *       and a connection that failed, with exponential backoff and jitter, honoring
 *       {@code Retry-After} and the connector's {@link RateLimitHint} - which sees the answer's body,
 *       so a limit stated only there (Google's 403 {@code rateLimitExceeded}, Linear's 400
 *       {@code RATELIMITED}) is waited out like a 429 - up to a per-wait and a total ceiling, past
 *       which the request fails as rate limited and the next pass tries again, instead of parking a
 *       sync for an hour.
 *   <li><b>Paced</b>, where the connector asks: a {@link RatePacing} reads every answer's budget
 *       headers, and a budget nearly spent slows the next request before the service answers 429.
 *   <li><b>A refused sign-in token is refreshed once</b> (a 401 on a connection that signs in).
 *   <li><b>Every failure is a sentence</b>, written here once: {@link ConnectorHttpException}.
 * </ul>
 *
 * <p>Built per session from {@link ConnectorHttp#client(String)}; cheap, and not shared between
 * sources.
 */
public final class ApiClient {

    private static final Logger log = LoggerFactory.getLogger(ApiClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<Integer> RETRYABLE = Set.of(429, 502, 503, 504);
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final int MAX_REDIRECTS = 5;
    // An answer read whole (JSON, a rendered page). A file of any size goes through stream().
    static final int MAX_BUFFERED_BYTES = 64 * 1024 * 1024;

    private final ClientHttpRequestFactory factory;
    private final ConnectorHttp.Sleeper sleeper;
    private final Clock clock;
    private final URI base;
    private final ApiAuth auth;
    private final String service;
    private final Map<String, String> defaultHeaders;
    private final RateLimitHint rateLimit;
    private final int maxAttempts;
    private final Duration maxWait;
    private final Duration maxTotalWait;
    private final Duration minInterval;
    private final Set<String> extraHosts;
    private final Set<Integer> retryable;
    private final RatePacing pacing;
    private long lastRequestNanos;
    // When a budget the service reported nearly spent lets the next request go (System.nanoTime,
    // which may be negative - so a flag says whether there is one).
    private long pacedUntilNanos;
    private boolean paced;

    private ApiClient(Builder b) {
        this.factory = b.http.factory();
        this.sleeper = b.http.sleeper();
        this.clock = b.http.clock();
        this.base = parseBase(b.baseUrl);
        this.auth = b.auth == null ? ApiAuth.none() : b.auth;
        this.service = b.service == null || b.service.isBlank() ? "The service" : b.service;
        this.defaultHeaders = Map.copyOf(b.headers);
        this.rateLimit = b.rateLimit == null ? RateLimitHint.STANDARD : b.rateLimit.or(RateLimitHint.STANDARD);
        this.maxAttempts = Math.max(1, b.maxAttempts);
        this.maxWait = b.maxWait;
        this.maxTotalWait = b.maxTotalWait;
        this.minInterval = b.minInterval;
        Set<String> hosts = new java.util.HashSet<>();
        for (String h : b.extraHosts) hosts.add(h.toLowerCase(Locale.ROOT));
        this.extraHosts = Set.copyOf(hosts);
        Set<Integer> statuses = new java.util.HashSet<>(RETRYABLE);
        statuses.addAll(b.extraRetryable);
        this.retryable = Set.copyOf(statuses);
        this.pacing = b.pacing;
    }

    static Builder builder(ConnectorHttp http, String baseUrl) {
        return new Builder(http, baseUrl);
    }

    public URI base() {
        return base;
    }

    // -- requests ----------------------------------------------------------------------------

    /** GET, read whole. Not checked: the caller looks at the status. */
    public ApiResponse get(String pathOrUrl, Map<String, ?> query) {
        return exchange(HttpMethod.GET, pathOrUrl, query, null, null, Map.of());
    }

    public ApiResponse get(String pathOrUrl) {
        return get(pathOrUrl, Map.of());
    }

    /** GET a JSON answer; anything but 2xx throws the sentence for its status. */
    public JsonNode getJson(String pathOrUrl, Map<String, ?> query) {
        return requireOk(get(pathOrUrl, query)).json();
    }

    public JsonNode getJson(String pathOrUrl) {
        return getJson(pathOrUrl, Map.of());
    }

    /** Any method, with an optional JSON body and headers of its own. Not checked. */
    public ApiResponse send(HttpMethod method, String pathOrUrl, Map<String, ?> query, Object jsonBody,
                            Map<String, String> headers) {
        byte[] body = null;
        if (jsonBody != null) {
            try {
                body = jsonBody instanceof byte[] bytes ? bytes : JSON.writeValueAsBytes(jsonBody);
            } catch (IOException e) {
                throw new IllegalArgumentException("The request body could not be written as JSON", e);
            }
        }
        return exchange(method, pathOrUrl, query, body, jsonBody == null ? null : MediaType.APPLICATION_JSON_VALUE,
                headers == null ? Map.of() : headers);
    }

    /** A form POST - what token endpoints take. Not checked. */
    public ApiResponse postForm(String pathOrUrl, Map<String, String> form) {
        return postForm(pathOrUrl, form, Map.of());
    }

    /** A form POST with headers of its own. Not checked. */
    public ApiResponse postForm(String pathOrUrl, Map<String, String> form, Map<String, String> headers) {
        StringBuilder out = new StringBuilder();
        form.forEach((k, v) -> {
            if (v == null) return;
            if (!out.isEmpty()) out.append('&');
            out.append(encode(k)).append('=').append(encode(v));
        });
        Map<String, String> all = new LinkedHashMap<>();
        all.put(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (headers != null) all.putAll(headers);
        return exchange(HttpMethod.POST, pathOrUrl, Map.of(), out.toString().getBytes(StandardCharsets.UTF_8),
                MediaType.APPLICATION_FORM_URLENCODED_VALUE, all);
    }

    /**
     * A body to read as a stream - a file's bytes, of any size. The caller closes it. Anything but
     * 2xx throws the sentence for its status (a 404 or 410 as {@link ConnectorHttpException#isNotFound}).
     */
    public InputStream stream(String pathOrUrl, Map<String, ?> query, Map<String, String> headers) {
        URI uri = resolve(pathOrUrl, query);
        Raw raw = execute(HttpMethod.GET, uri, null, null, headers == null ? Map.of() : headers);
        if (raw.status >= 200 && raw.status < 300) {
            ClientHttpResponse response = raw.response;
            try {
                return new FilterInputStream(response.getBody()) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            response.close();
                        }
                    }
                };
            } catch (IOException e) {
                response.close();
                throw unreachable(uri, e);
            }
        }
        throw failure(raw.buffer(uri));
    }

    /** {@code response} when it is 2xx; otherwise the sentence for its status, thrown. */
    public ApiResponse requireOk(ApiResponse response) {
        if (response.ok()) return response;
        throw failure(response);
    }

    /**
     * The sentence for a failed answer - written here for every connector, so the page translates a
     * handful of sentences rather than one per service. A connector with a better word for one case
     * (a repository that is not there) catches the exception and says it.
     */
    public ConnectorHttpException failure(ApiResponse response) {
        int status = response.status();
        String path = response.uri() == null ? null : response.uri().getRawPath();
        if (rateLimit.waitFor(response, clock.instant()).isPresent() || status == 429) {
            return new ConnectorHttpException(service + " is limiting how often it is asked. The next sync will try again.",
                    status, path);
        }
        return switch (status) {
            case 401 -> new ConnectorHttpException(service + " refused the connection's credential. "
                    + "Replace it, or sign in again, in Settings > Connections.", status, path);
            case 403 -> new ConnectorHttpException(service + " refused access with the connection's credential. "
                    + "Check that its account may read this.", status, path);
            case 404, 410 -> new ConnectorHttpException(service + " has nothing at that address (" + status + ").",
                    status, path);
            default -> status >= 500
                    ? new ConnectorHttpException(service + " answered with a server error (" + status
                            + "). The next sync will try again.", status, path)
                    : new ConnectorHttpException(service + " refused the request (" + status + ").", status, path);
        };
    }

    private ConnectorHttpException unreachable(URI uri, Exception e) {
        String reason = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        return new ConnectorHttpException(service + " could not be reached: " + reason, 0,
                uri == null ? null : uri.getRawPath(), e);
    }

    // -- the request loop ----------------------------------------------------------------------

    private ApiResponse exchange(HttpMethod method, String pathOrUrl, Map<String, ?> query, byte[] body,
                                 String contentType, Map<String, String> headers) {
        URI uri = resolve(pathOrUrl, query);
        return execute(method, uri, body, contentType, headers).buffer(uri);
    }

    private Raw execute(HttpMethod method, URI uri, byte[] body, String contentType, Map<String, String> headers) {
        Duration waited = Duration.ZERO;
        boolean refreshed = false;
        IOException lastFailure = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            pace();
            Raw raw;
            try {
                raw = sendFollowingRedirects(method, uri, body, contentType, headers);
            } catch (InterruptedIOException e) {
                Thread.currentThread().interrupt();
                throw unreachable(uri, e);
            } catch (IOException e) {
                lastFailure = e;
                Duration backoff = backoff(attempt);
                if (attempt + 1 >= maxAttempts || waited.plus(backoff).compareTo(maxTotalWait) > 0) break;
                log.info("[ConnectorHttp] {} failed ({}), retrying in {}ms", uri.getHost(), e.getMessage(), backoff.toMillis());
                sleep(backoff, uri);
                waited = waited.plus(backoff);
                continue;
            }
            paceAfter(raw);
            if (raw.status == 401 && auth.refreshable() && !refreshed) {
                raw.close();
                auth.invalidate();
                refreshed = true;
                attempt--; // a refresh is not a retry of a failing service
                continue;
            }
            Optional<Duration> wait = retryWait(raw, attempt);
            if (wait.isEmpty() || attempt + 1 >= maxAttempts || waited.plus(wait.get()).compareTo(maxTotalWait) > 0) {
                return raw;
            }
            raw.close();
            log.info("[ConnectorHttp] {} answered {}, retrying in {}ms (attempt {}/{})",
                    uri.getHost(), raw.status, wait.get().toMillis(), attempt + 1, maxAttempts);
            sleep(wait.get(), uri);
            waited = waited.plus(wait.get());
        }
        throw unreachable(uri, lastFailure == null ? new IOException("no answer") : lastFailure);
    }

    // How long to wait before asking again, or empty for an answer that is final. The hint is shown
    // the answer WITH its body: a vendor that says "rate limited" only in the body (a 400 or 403
    // whose error names the limit) is told apart from a refusal here, not after the retries are over.
    // An error body is small, and one read here is the one the caller gets back.
    private Optional<Duration> retryWait(Raw raw, int attempt) {
        if (!retryable.contains(raw.status) && raw.status != 403 && raw.status != 400) return Optional.empty();
        Optional<Duration> hinted = rateLimit.waitFor(raw.buffer(raw.uri), clock.instant());
        if (hinted.isPresent()) {
            Duration asked = hinted.get();
            if (asked.compareTo(maxWait) > 0) return Optional.empty(); // too long to sit here - next pass
            return Optional.of(asked.isZero() ? backoff(attempt) : asked);
        }
        return retryable.contains(raw.status) ? Optional.of(backoff(attempt)) : Optional.empty();
    }

    // What a successful answer says about the budget left: a pacing that finds it nearly spent holds
    // the next request back - never longer than the longest wait a rate limit is believed for. Only a
    // success: a refusal that IS the limit is the hint's to wait out, and both would wait twice.
    private void paceAfter(Raw raw) {
        if (pacing == null || raw.status < 200 || raw.status >= 300) return;
        Optional<Duration> hold = pacing.holdNext(new ApiResponse(raw.uri, raw.status, raw.headers, null), clock.instant());
        if (hold.isEmpty() || hold.get().isNegative() || hold.get().isZero()) return;
        Duration d = hold.get().compareTo(maxWait) > 0 ? maxWait : hold.get();
        synchronized (this) {
            long until = System.nanoTime() + d.toNanos();
            pacedUntilNanos = paced && pacedUntilNanos - until > 0 ? pacedUntilNanos : until;
            paced = true;
        }
    }

    private static Duration backoff(int attempt) {
        long base = 500L << Math.min(attempt, 6);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 4));
        return Duration.ofMillis(base + jitter);
    }

    private void sleep(Duration wait, URI uri) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable(uri, new InterruptedIOException("interrupted while waiting to retry"));
        }
    }

    // Per-client politeness: at most one request per minInterval, and none before a budget the
    // service reported nearly spent lets it go.
    private synchronized void pace() {
        if (minInterval.isZero() && !paced) return;
        long now = System.nanoTime();
        long due = lastRequestNanos == 0 ? now : lastRequestNanos + minInterval.toNanos();
        if (paced) {
            if (pacedUntilNanos - due > 0) due = pacedUntilNanos;
            paced = false;
        }
        if (due - now > 0) {
            try {
                sleeper.sleep(Duration.ofNanos(due - now));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestNanos = System.nanoTime();
    }

    private Raw sendFollowingRedirects(HttpMethod method, URI uri, byte[] body, String contentType,
                                       Map<String, String> headers) throws IOException {
        URI current = uri;
        HttpMethod currentMethod = method;
        byte[] currentBody = body;
        boolean withAuth = true;
        for (int hop = 0; ; hop++) {
            OutboundAddressPolicy.check(current);
            if (DotSegmentGuard.climbs(current)) {
                throw new ConnectorHttpException("That address climbs out of the service's API, so it was not asked for.",
                        0, current.getRawPath());
            }
            ClientHttpRequest request = factory.createRequest(current, currentMethod);
            HttpHeaders h = request.getHeaders();
            h.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            h.set(HttpHeaders.USER_AGENT, "Proxyma");
            defaultHeaders.forEach(h::set);
            headers.forEach(h::set);
            if (withAuth) auth.apply(h);
            if (currentBody != null) {
                if (contentType != null) h.set(HttpHeaders.CONTENT_TYPE, contentType);
                request.getBody().write(currentBody);
            }
            ClientHttpResponse response = request.execute();
            int status = response.getStatusCode().value();
            String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if (!REDIRECTS.contains(status) || location == null || hop >= MAX_REDIRECTS) {
                return new Raw(current, status, response.getHeaders(), response);
            }
            URI next = current.resolve(location.trim());
            String to = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
            if (!to.equals("http") && !to.equals("https")) return new Raw(current, status, response.getHeaders(), response);
            if ("https".equalsIgnoreCase(current.getScheme()) && to.equals("http")) {
                return new Raw(current, status, response.getHeaders(), response);
            }
            response.close();
            // Followed - but the credential stays with the pinned server. A download link that moves
            // to a storage host is fetched as anybody would fetch it; a redirect back is not
            // re-authenticated either, since it was the other host that chose where to send it.
            if (!sameOrigin(next, base)) withAuth = false;
            if (status == 303 || ((status == 301 || status == 302) && currentMethod == HttpMethod.POST)) {
                currentMethod = HttpMethod.GET;
                currentBody = null;
            }
            current = next;
        }
    }

    // -- addresses -----------------------------------------------------------------------------

    /**
     * {@code pathOrUrl} as a URI on this client's server: a path is joined to the base URL (its path
     * kept, so {@code https://host/api/v3} + {@code repos/x} is {@code /api/v3/repos/x}); an absolute
     * URL must be on the same scheme, host and port, or one of the hosts the client was told about.
     */
    public URI resolve(String pathOrUrl, Map<String, ?> query) {
        String target;
        if (pathOrUrl != null && (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://"))) {
            target = pathOrUrl;
        } else {
            String basePath = base.toString().replaceAll("/+$", "");
            String path = pathOrUrl == null ? "" : pathOrUrl.replaceAll("^/+", "");
            target = path.isEmpty() ? basePath : basePath + "/" + path;
        }
        String q = queryString(query);
        if (!q.isEmpty()) target = target + (target.contains("?") ? "&" : "?") + q;
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException e) {
            throw new ConnectorHttpException("That address is not a valid URL.", 0, null, e);
        }
        if (!sameOrigin(uri, base) && !extraHosts.contains(host(uri))) {
            throw new ConnectorHttpException(service + " pointed to another server (" + host(uri)
                    + "), which the connection's credential is not sent to.", 0, uri.getRawPath());
        }
        return uri;
    }

    /** One path segment, encoded - an id or a name a person typed never becomes a path of its own. */
    public static String segment(String value) {
        return encode(value == null ? "" : value);
    }

    private static String queryString(Map<String, ?> query) {
        if (query == null || query.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, ?> e : query.entrySet()) {
            if (e.getValue() == null) continue;
            Iterable<?> values = e.getValue() instanceof Iterable<?> it ? it : List.of(e.getValue());
            for (Object v : values) {
                if (!out.isEmpty()) out.append('&');
                out.append(encode(e.getKey())).append('=').append(encode(String.valueOf(v)));
            }
        }
        return out.toString();
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static boolean sameOrigin(URI a, URI b) {
        if (a == null || b == null) return false;
        return Objects.equals(lower(a.getScheme()), lower(b.getScheme()))
                && Objects.equals(host(a), host(b))
                && port(a) == port(b);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static URI parseBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ConnectorAuthException("This connection has no server address. Add one in Settings > Connections.");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new ConnectorHttpException("The connection's server address is not a valid URL.", 0, null, e);
        }
        String scheme = lower(uri.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme) || uri.getHost() == null) {
            throw new ConnectorHttpException("The connection's server address must start with http:// or https://.",
                    0, null);
        }
        return uri;
    }

    // An answer not yet read: its body is a stream until buffer() reads it whole - once; a second
    // buffer() hands back the same answer. Not static: the answer carries the client's service name,
    // for the sentence json() throws on a web page.
    private final class Raw {
        final URI uri;
        final int status;
        final HttpHeaders headers;
        final ClientHttpResponse response;
        private ApiResponse buffered;

        Raw(URI uri, int status, HttpHeaders headers, ClientHttpResponse response) {
            this.uri = uri;
            this.status = status;
            HttpHeaders copy = new HttpHeaders();
            copy.putAll(headers);
            this.headers = copy;
            this.response = response;
        }

        ApiResponse buffer(URI requested) {
            if (buffered != null) return buffered;
            try (ClientHttpResponse r = response; InputStream in = r.getBody()) {
                byte[] bytes = in.readNBytes(MAX_BUFFERED_BYTES + 1);
                if (bytes.length > MAX_BUFFERED_BYTES) {
                    throw new ConnectorHttpException("The answer was larger than " + (MAX_BUFFERED_BYTES / (1024 * 1024))
                            + " MB, so it was not read.", status, uri.getRawPath());
                }
                buffered = new ApiResponse(uri, status, headers, bytes, service);
            } catch (IOException e) {
                buffered = new ApiResponse(uri, status, headers, new byte[0], service);
            }
            return buffered;
        }

        void close() {
            response.close();
        }
    }

    public static final class Builder {
        private final ConnectorHttp http;
        private final String baseUrl;
        private ApiAuth auth;
        private String service;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private RateLimitHint rateLimit;
        private int maxAttempts = 4;
        private Duration maxWait = Duration.ofSeconds(60);
        private Duration maxTotalWait = Duration.ofMinutes(5);
        private Duration minInterval = Duration.ZERO;
        private final List<String> extraHosts = new ArrayList<>();
        private final Set<Integer> extraRetryable = new java.util.HashSet<>();
        private RatePacing pacing;

        private Builder(ConnectorHttp http, String baseUrl) {
            this.http = http;
            this.baseUrl = baseUrl;
        }

        public Builder auth(ApiAuth value) { this.auth = value; return this; }

        /** The service's name as the sentences say it - "GitHub refused the connection's credential". */
        public Builder service(String value) { this.service = value; return this; }

        public Builder header(String name, String value) { this.headers.put(name, value); return this; }

        public Builder rateLimit(RateLimitHint value) { this.rateLimit = value; return this; }

        /** How many times a request is sent before its failure stands (default 4). */
        public Builder maxAttempts(int value) { this.maxAttempts = value; return this; }

        /** The longest single wait a rate limit is believed for (default 60 s). */
        public Builder maxWait(Duration value) { this.maxWait = value; return this; }

        /** The longest one request spends waiting, all retries together (default 5 min). */
        public Builder maxTotalWait(Duration value) { this.maxTotalWait = value; return this; }

        /** At most one request per interval - a vendor that asks to be asked slowly. */
        public Builder minInterval(Duration value) { this.minInterval = value; return this; }

        /**
         * A status retried like a 429 besides the standard ones - a vendor's own "overloaded, ask
         * again" (Notion's 529). Its wait is the hint's, else a backoff.
         */
        public Builder retryStatus(int status) { this.extraRetryable.add(status); return this; }

        /** Slows the next request when an answer says the budget is nearly spent. */
        public Builder pacing(RatePacing value) { this.pacing = value; return this; }

        /** A host besides the server's own that requests may go to WITH the credential. */
        public Builder allowHost(String host) { this.extraHosts.add(host); return this; }

        public ApiClient build() {
            return new ApiClient(this);
        }
    }
}
