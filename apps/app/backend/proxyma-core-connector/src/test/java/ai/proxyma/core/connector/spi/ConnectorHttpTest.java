package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.testing.LoopbackApi;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one HTTP client every framework connector uses - against a loopback API, so what is tested is
 * the real request path: pagination, retries and their ceilings, the sentence a failure becomes, and
 * the rule that a credential never leaves the connection's server.
 */
class ConnectorHttpTest {

    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private ConnectorHttp http;

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        http = ConnectorHttp.direct(waits::add);
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ApiClient client() {
        return http.client(api.baseUrl()).service("Fake").auth(ApiAuth.bearer("tok")).build();
    }

    @Test
    @DisplayName("a Link-header API is read page by page, in order, with the credential on every page")
    void linkHeaderPagination() {
        api.on("GET", "/items").json("[{'n':1},{'n':2}]", "Link", "<" + api.url("/items?page=2") + ">; rel=\"next\"");
        api.on("GET", "/items").query("page", "2").json("[{'n':3}]", "Link",
                "<" + api.url("/items?page=3") + ">; rel=\"next\", <" + api.url("/items?page=1") + ">; rel=\"first\"");
        api.on("GET", "/items").query("page", "3").json("[{'n':4}]");

        List<Integer> seen = new ArrayList<>();
        Paginator.linkHeader(client(), "items", Map.of(), page -> page).forEachRemaining(n -> seen.add(n.get("n").asInt()));

        assertThat(seen).containsExactly(1, 2, 3, 4);
        assertThat(api.requests()).hasSize(3).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer tok"));
    }

    @Test
    @DisplayName("an empty page that says there is more does not end the listing; a page pointing back does")
    void emptyPagesAndLoops() {
        Iterator<String> it = Paginator.iterate(next -> next == null ? new Paginator.Page<>(List.of(), "b")
                : next.equals("b") ? new Paginator.Page<>(List.of("x"), "c")
                : new Paginator.Page<>(List.of("y"), "b"));
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        assertThat(out).containsExactly("x", "y");
    }

    @Test
    @DisplayName("a 429 is waited out for as long as Retry-After asks, then the request is sent again")
    void retryAfterIsHonored() {
        api.on("GET", "/thing").status(429).header("Retry-After", "7").then().json("{'ok':true}");

        JsonNode answer = client().getJson("thing");

        assertThat(answer.get("ok").asBoolean()).isTrue();
        assertThat(waits).containsExactly(Duration.ofSeconds(7));
        assertThat(api.requests("/thing")).hasSize(2);
    }

    @Test
    @DisplayName("a 503 with no Retry-After backs off and retries; one that never recovers becomes a sentence")
    void serverErrorsBackOffThenFail() {
        api.on("GET", "/flaky").status(503);

        assertThatThrownBy(() -> client().getJson("flaky"))
                .isInstanceOf(ConnectorHttpException.class)
                .hasMessage("Fake answered with a server error (503). The next sync will try again.");
        assertThat(api.requests("/flaky")).hasSize(4);
        assertThat(waits).hasSize(3).allSatisfy(w -> assertThat(w).isPositive());
    }

    @Test
    @DisplayName("GitHub's rate limit - a 403 with nothing remaining - is waited out until its reset, when that is soon")
    void resetHeadersAreWaitedOut() {
        long reset = Instant.now().getEpochSecond() + 5;
        api.on("GET", "/limited").status(403).header("x-ratelimit-remaining", "0")
                .header("x-ratelimit-reset", String.valueOf(reset)).then().json("{'ok':true}");
        ApiClient client = http.client(api.baseUrl()).service("GitHub").rateLimit(RateLimitHint.RESET_HEADERS).build();

        assertThat(client.getJson("limited").get("ok").asBoolean()).isTrue();
        assertThat(waits).hasSize(1);
        assertThat(waits.get(0)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("a rate limit that resets in an hour is not sat out: the request fails as rate limited, for the next pass")
    void longRateLimitsAreNotSatOut() {
        long reset = Instant.now().getEpochSecond() + 3600;
        api.on("GET", "/limited").status(403).header("x-ratelimit-remaining", "0")
                .header("x-ratelimit-reset", String.valueOf(reset));
        ApiClient client = http.client(api.baseUrl()).service("GitHub").rateLimit(RateLimitHint.RESET_HEADERS).build();

        assertThatThrownBy(() -> client.getJson("limited"))
                .hasMessage("GitHub is limiting how often it is asked. The next sync will try again.");
        assertThat(waits).isEmpty();
        assertThat(api.requests("/limited")).hasSize(1);
    }

    @Test
    @DisplayName("each failed status becomes the sentence a person reads, never a status line or a body")
    void failuresAreSentences() {
        api.on("GET", "/a").status(401).json("{'message':'Bad credentials'}");
        api.on("GET", "/b").status(403).json("{'message':'Forbidden'}");
        api.on("GET", "/c").status(404);
        api.on("GET", "/d").status(422).json("{'message':'Validation Failed'}");

        assertThatThrownBy(() -> client().getJson("a")).hasMessage(
                "Fake refused the connection's credential. Replace it, or sign in again, in Settings > Connections.");
        assertThatThrownBy(() -> client().getJson("b")).hasMessage(
                "Fake refused access with the connection's credential. Check that its account may read this.");
        assertThatThrownBy(() -> client().getJson("c"))
                .isInstanceOfSatisfying(ConnectorHttpException.class, e -> assertThat(e.isNotFound()).isTrue());
        assertThatThrownBy(() -> client().getJson("d")).hasMessage("Fake refused the request (422).");
    }

    @Test
    @DisplayName("a web page where JSON was expected names the service and the address; other non-JSON keeps the plain sentence")
    void webPagesAreNamed() {
        api.on("GET", "/html").body("<!DOCTYPE html><html><body>Sign in</body></html>".getBytes(StandardCharsets.UTF_8),
                "text/html; charset=utf-8");
        // Served as text, but a page all the same - a proxy that labels everything text/plain.
        api.on("GET", "/untyped").body("\n  <html><body>Jira</body></html>".getBytes(StandardCharsets.UTF_8), "text/plain");
        api.on("GET", "/text").text("OK");
        String page = "Fake answered with a web page instead of its API. Check the connection's address.";

        assertThatThrownBy(() -> client().getJson("html")).isInstanceOfSatisfying(ConnectorHttpException.class, e -> {
            assertThat(e).hasMessage(page);
            assertThat(e.status()).isEqualTo(200);
        });
        assertThatThrownBy(() -> client().getJson("untyped")).hasMessage(page);
        assertThatThrownBy(() -> client().getJson("text"))
                .hasMessage("The service answered with something that is not JSON.");
        // A client with no service name still says something whole.
        ApiClient unnamed = http.client(api.baseUrl()).build();
        assertThatThrownBy(() -> unnamed.getJson("html"))
                .hasMessage("The service answered with a web page instead of its API. Check the connection's address.");
        assertThat(client().get("html").isWebPage()).isTrue();
        assertThat(client().get("text").isWebPage()).isFalse();
    }

    @Test
    @DisplayName("a URL a response names on another host is refused, and nothing is sent there")
    void otherHostsAreRefused() {
        try (LoopbackApi other = LoopbackApi.start()) {
            String elsewhere = other.url("/steal").replace("127.0.0.1", "localhost");
            api.on("GET", "/items").json("[{'n':1}]", "Link", "<" + elsewhere + ">; rel=\"next\"");
            other.on("GET", "/steal").json("[]");

            Listing listing = Listings.mapped(Paginator.linkHeader(client(), "items", Map.of(), p -> p),
                    n -> item(n.get("n").asText()));
            List<String> ids = new ArrayList<>();
            listing.forEachRemaining(i -> ids.add(i.getId()));

            assertThat(ids).containsExactly("1");
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).contains("pointed to another server (localhost)");
            assertThat(other.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("a redirect to another host is followed WITHOUT the credential")
    void redirectsDropTheCredential() {
        try (LoopbackApi storage = LoopbackApi.start()) {
            String target = storage.url("/blob").replace("127.0.0.1", "localhost");
            api.on("GET", "/download").status(302).header("Location", target);
            storage.on("GET", "/blob").text("bytes");

            try (InputStream in = client().stream("download", Map.of(), Map.of())) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("bytes");
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            assertThat(api.last().header("Authorization")).isEqualTo("Bearer tok");
            assertThat(storage.last().header("Authorization")).isNull();
        }
    }

    @Test
    @DisplayName("a sign-in's token the service refuses is refreshed once, and the request sent again")
    void refusedTokensRefreshOnce() {
        AtomicInteger issued = new AtomicInteger();
        AtomicInteger invalidated = new AtomicInteger();
        TokenSupplier tokens = new TokenSupplier() {
            @Override public String accessToken() { return "t" + issued.incrementAndGet(); }
            @Override public void invalidate() { invalidated.incrementAndGet(); }
        };
        ResolvedCredentials credentials = new ResolvedCredentials(null, null, AuthKind.OAUTH2_CODE, api.baseUrl(),
                null, Map.of(), Map.of(), tokens);
        api.on("GET", "/me").status(401).then().json("{'login':'ana'}");

        ApiClient client = http.client(api.baseUrl()).service("Fake").auth(ApiAuth.bearer(credentials)).build();

        assertThat(client.getJson("me").get("login").asText()).isEqualTo("ana");
        assertThat(invalidated.get()).isEqualTo(1);
        assertThat(api.requests("/me")).extracting(r -> r.header("Authorization")).containsExactly("Bearer t1", "Bearer t2");
    }

    @Test
    @DisplayName("a page that fails mid-listing ends the listing as errored, with the items before it kept")
    void midListingFailuresAreErrors() {
        api.on("GET", "/items").json("[{'n':1}]", "Link", "<" + api.url("/items?page=2") + ">; rel=\"next\"");
        api.on("GET", "/items").query("page", "2").status(500);

        Listing listing = Listings.mapped(Paginator.linkHeader(client(), "items", Map.of(), p -> p),
                n -> item(n.get("n").asText()));
        List<String> ids = new ArrayList<>();
        listing.forEachRemaining(i -> ids.add(i.getId()));

        assertThat(ids).containsExactly("1");
        assertThat(listing.hasErrored()).isTrue();
        assertThat(listing.getErrorMessage()).isEqualTo("Fake answered with a server error (500). The next sync will try again.");
    }

    @Test
    @DisplayName("a path that climbs out of the API is refused before anything is sent")
    void dotSegmentsAreRefused() {
        assertThatThrownBy(() -> client().getJson("repos/a/../../admin"))
                .hasMessageContaining("climbs out of the service's API");
        assertThat(api.requests()).isEmpty();
    }

    @Test
    @DisplayName("a base URL's own path is kept: a path is joined below it, as a GitHub Enterprise server's /api/v3 needs")
    void basePathIsKept() {
        api.on("GET", "/api/v3/user").json("{'login':'ana'}");
        ApiClient client = http.client(api.baseUrl() + "/api/v3").service("GitHub").build();
        assertThat(client.getJson("user").get("login").asText()).isEqualTo("ana");
    }

    // A hint that, like Google's, finds the limit only in the body's error reason.
    private static final RateLimitHint BODY_REASON = (response, now) ->
            (response.status() == 403 && response.text().contains("rateLimitExceeded"))
                    ? java.util.Optional.of(Duration.ZERO) : java.util.Optional.empty();

    @Test
    @DisplayName("a limit stated only in the body (a 403 whose reason is rateLimitExceeded) is backed off and retried; a plain 403 is a refusal at once")
    void hintsSeeTheBody() {
        api.on("GET", "/files").status(403).json("{'error':{'errors':[{'reason':'rateLimitExceeded'}]}}")
                .then().json("{'ok':true}");
        api.on("GET", "/secret").status(403).json("{'error':{'errors':[{'reason':'insufficientPermissions'}]}}");
        ApiClient client = http.client(api.baseUrl()).service("Drive").rateLimit(BODY_REASON).build();

        assertThat(client.getJson("files").get("ok").asBoolean()).isTrue();
        assertThat(waits).hasSize(1);
        assertThat(api.requests("/files")).hasSize(2);

        // Not a limit: answered once, its body still there for the caller.
        ApiResponse refused = client.get("secret");
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.text()).contains("insufficientPermissions");
        assertThat(api.requests("/secret")).hasSize(1);
        assertThatThrownBy(() -> client.requireOk(refused)).hasMessage(
                "Drive refused access with the connection's credential. Check that its account may read this.");
    }

    @Test
    @DisplayName("a vendor's own overload status (Notion's 529) is retried only by a client that asks for it, honoring Retry-After")
    void extraRetryStatus() {
        api.on("GET", "/busy").status(529).header("Retry-After", "2").then().json("{'ok':true}");
        RateLimitHint overload = (response, now) -> response.status() == 529
                ? ai.proxyma.core.common.net.RetryAfter.parse(response.header("Retry-After"), now) : java.util.Optional.empty();

        ApiClient plain = http.client(api.baseUrl()).service("Notion").build();
        assertThat(plain.get("busy").status()).isEqualTo(529);
        assertThat(waits).isEmpty();

        api.reset();
        api.on("GET", "/busy").status(529).header("Retry-After", "2").then().json("{'ok':true}");
        ApiClient optedIn = http.client(api.baseUrl()).service("Notion").rateLimit(overload).retryStatus(529).build();
        assertThat(optedIn.getJson("busy").get("ok").asBoolean()).isTrue();
        assertThat(waits).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("a limit a wait cannot cure (NEXT_PASS) is asked once and reported as a rate limit, not a refusal")
    void nextPassIsReportedNotWaited() {
        api.on("GET", "/blocked").status(429).json("{'code':'public_api_request_blocked'}");
        RateLimitHint blocked = ((RateLimitHint) (response, now) -> response.text().contains("public_api_request_blocked")
                ? java.util.Optional.of(RateLimitHint.NEXT_PASS) : java.util.Optional.empty()).or(RateLimitHint.STANDARD);
        ApiClient client = http.client(api.baseUrl()).service("Notion").rateLimit(blocked).build();

        assertThatThrownBy(() -> client.getJson("blocked"))
                .hasMessage("Notion is limiting how often it is asked. The next sync will try again.");
        assertThat(api.requests("/blocked")).hasSize(1);
        assertThat(waits).isEmpty();
    }

    @Test
    @DisplayName("a budget nearly spent on a 200 slows the next request: what is left of the window is shared out over what is left of the budget")
    void pacingSlowsBeforeA429() {
        long reset = Instant.now().getEpochSecond() + 30;
        api.on("GET", "/plenty").json("{}", "X-RateLimit-Remaining", "80", "X-RateLimit-Reset", String.valueOf(reset));
        api.on("GET", "/low").json("{}", "X-RateLimit-Remaining", "2", "X-RateLimit-Reset", String.valueOf(reset));
        api.on("GET", "/next").json("{}");
        ApiClient client = http.client(api.baseUrl()).service("ClickUp")
                .pacing(RatePacing.budget("X-RateLimit-Remaining", "X-RateLimit-Reset", RatePacing.Reset.EPOCH_SECONDS,
                        Duration.ofMinutes(1), 10))
                .build();

        client.getJson("plenty");
        client.getJson("next");
        assertThat(waits).isEmpty();

        client.getJson("low");
        client.getJson("next");
        // About 30 s left for 2 requests: a third of it before the next.
        assertThat(waits).hasSize(1);
        assertThat(waits.get(0)).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(11));
        // Held once: the request after that one goes at once.
        client.getJson("next");
        assertThat(waits).hasSize(1);
    }

    @Test
    @DisplayName("a budget with no stated reset assumes the whole window is left; seconds-from-now resets are read as such")
    void pacingWindows() {
        Instant now = Instant.parse("2026-10-14T08:00:00Z");
        RatePacing minute = RatePacing.budget("X-Ratelimit-Remaining", null, RatePacing.Reset.NONE, Duration.ofMinutes(1), 10);
        RatePacing delta = RatePacing.budget("ratelimit-remaining", "ratelimit-reset", RatePacing.Reset.DELTA_SECONDS,
                Duration.ofMinutes(1), 20);
        assertThat(minute.holdNext(answer("X-Ratelimit-Remaining", "5"), now)).contains(Duration.ofSeconds(10));
        assertThat(minute.holdNext(answer("X-Ratelimit-Remaining", "50"), now)).isEmpty();
        assertThat(minute.holdNext(answer("Other", "1"), now)).isEmpty();
        assertThat(delta.holdNext(answer("ratelimit-remaining", "3", "ratelimit-reset", "8"), now)).contains(Duration.ofSeconds(2));
        assertThat(delta.holdNext(answer("ratelimit-remaining", "0", "ratelimit-reset", "6"), now)).contains(Duration.ofSeconds(3));
    }

    private static ApiResponse answer(String... headers) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        for (int i = 0; i + 1 < headers.length; i += 2) h.add(headers[i], headers[i + 1]);
        return new ApiResponse(null, 200, h, new byte[0]);
    }

    private static ItemMetadata item(String id) {
        ItemMetadata m = new ItemMetadata();
        m.setId(id);
        return m;
    }
}
