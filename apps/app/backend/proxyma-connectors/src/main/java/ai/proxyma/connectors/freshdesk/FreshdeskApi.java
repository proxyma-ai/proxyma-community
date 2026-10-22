package ai.proxyma.connectors.freshdesk;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.RatePacing;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.ServiceAddress;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How every request to Freshdesk is made: the account's address - {@code https://DOMAIN.freshdesk.com},
 * from the connection's domain, or the connection's Freshdesk address where one is typed (a custom
 * portal domain, a test server) - with {@code /api/v2} after it, and the connection's API key on every
 * request and on no other host, as Freshdesk takes it: HTTP Basic with the key as the user name and
 * any password ({@code KEY:X}).
 *
 * <p><b>Rate limits</b> are per account and per minute, by plan, and every answer states the budget in
 * {@code X-Ratelimit-Total}, {@code X-Ratelimit-Remaining} and {@code X-Ratelimit-Used-CurrentRequest}.
 * A request over it is a 429 with {@code Retry-After} (seconds), waited out when it is soon;
 * {@link #RATE_LIMIT} also reads a 429 that states only an exhausted budget as the minute it is.
 */
final class FreshdeskApi {

    static final String DOMAIN = "domain";
    static final String TOKEN = "token";
    static final String API_PATH = "/api/v2";

    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern PASTED = Pattern.compile(
            "^(?:https?://)?([A-Za-z0-9-]+)(?:\\.freshdesk\\.com)?(?:[/:].*)?$", Pattern.CASE_INSENSITIVE);

    /** A 429: its Retry-After, else - the per-minute budget spent - the rest of the minute. */
    static final RateLimitHint RATE_LIMIT = (response, now) -> {
        if (response.status() != 429) return Optional.empty();
        if (response.header("Retry-After") != null) return RateLimitHint.STANDARD.waitFor(response, now);
        String remaining = response.header("X-Ratelimit-Remaining");
        return Optional.of(remaining != null && "0".equals(remaining.strip()) ? Duration.ofMinutes(1) : Duration.ZERO);
    };

    /**
     * Every answer states what is left of the minute's budget, and none says when the minute ends:
     * from ten left, the next requests are spread over a whole minute, which is slower than needed
     * and never runs into the 429 that would cost the rest of it.
     */
    static final RatePacing PACING = RatePacing.budget("X-Ratelimit-Remaining", null, RatePacing.Reset.NONE,
            Duration.ofMinutes(1), 10);

    private FreshdeskApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        return http.client(accountUrl(connection) + API_PATH)
                .service("Freshdesk")
                .auth(ApiAuth.header(HttpHeaders.AUTHORIZATION, () -> {
                    String key = connection.secret(TOKEN) != null ? connection.secret(TOKEN) : connection.soleSecret();
                    if (key == null || key.isBlank()) {
                        throw new ConnectorAuthException("This connection has no API key. Add it in Settings > Connections.");
                    }
                    return "Basic " + Base64.getEncoder().encodeToString((key.strip() + ":X").getBytes(StandardCharsets.UTF_8));
                }))
                .rateLimit(RATE_LIMIT)
                .pacing(PACING)
                .build();
    }

    /**
     * The account's address, without {@code /api/v2}: the connection's Freshdesk address when one is
     * typed, else {@code https://DOMAIN.freshdesk.com}.
     */
    static String accountUrl(ResolvedCredentials connection) {
        if (connection.hasBaseUrl()) return trimAddress(connection.baseUrl());
        String domain = domain(connection.settings());
        if (domain == null) {
            throw new ConnectorAuthException("This connection has no Freshdesk domain. Add it in Settings > Connections, "
                    + "or for your own domain, the Freshdesk address under More options.");
        }
        return "https://" + domain + ".freshdesk.com";
    }

    /**
     * The account's address for a Freshdesk address in any form - a page copied from the browser
     * included, since the API is at the account's root ({@code /api/v2}) and a page's path joined to
     * it answers with the web app. On freshdesk.com that is the scheme and host; on any other host (a
     * custom portal domain, a test server) the address up to Freshdesk's own pages - {@code /api},
     * the agent view's {@code /a}, the portal's {@code /support}, {@code /helpdesk} and {@code /login}.
     */
    static String trimAddress(String address) {
        String host = ServiceAddress.host(address);
        if (host.isEmpty()) return address.strip().replaceAll("/+$", ""); // not a URL: refused in words
        if (host.endsWith(".freshdesk.com")) return ServiceAddress.origin(address);
        return ServiceAddress.cutAt(address, "api", "a", "support", "helpdesk", "login");
    }

    /**
     * The connection's domain as one lower-case DNS label - {@code acme} from {@code acme},
     * {@code acme.freshdesk.com} or {@code https://acme.freshdesk.com/a/tickets} - or null.
     */
    static String domain(Map<String, String> settings) {
        String raw = settings == null ? null : settings.get(DOMAIN);
        if (raw == null || raw.isBlank()) return null;
        Matcher m = PASTED.matcher(raw.strip());
        if (!m.matches()) return null;
        String label = m.group(1).toLowerCase(Locale.ROOT);
        return LABEL.matcher(label).matches() ? label : null;
    }
}
