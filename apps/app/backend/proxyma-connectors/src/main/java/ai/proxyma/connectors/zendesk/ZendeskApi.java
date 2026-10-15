package ai.proxyma.connectors.zendesk;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.RatePacing;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How every request to Zendesk is made: the account's own address - {@code https://SUBDOMAIN.zendesk.com},
 * from the connection's subdomain, or the connection's Zendesk address where one is typed (a
 * host-mapped domain, a test server) - with {@code /api/v2} after it, and the connection's credential
 * on every request and on no other host:
 * <ul>
 *   <li>an agent's email address and API token as HTTP Basic, {@code email/token:API_TOKEN}, the form
 *       Zendesk takes for a token (an administrator turns token access on);</li>
 *   <li>or a sign-in's access token as a bearer, refreshed once when Zendesk refuses it.</li>
 * </ul>
 *
 * <p><b>Rate limits.</b> Zendesk answers a request over its account limit with a 429 and
 * {@code Retry-After} (seconds), waited out when it is soon. The incremental export endpoints have a
 * limit of their own - 10 requests a minute (30 with the High Volume add-on), shared by everything
 * reading that account's exports - so {@link #exportClient} asks them at most once every six seconds,
 * and a 429 there is still waited out like any other. Every answer also states the account's
 * minute budget - {@code ratelimit-remaining} with {@code ratelimit-reset} in seconds, or the older
 * {@code X-Rate-Limit-Remaining} alone - and {@link #PACING} spreads the last twenty requests of a
 * minute over what is left of it.
 */
final class ZendeskApi {

    static final String SUBDOMAIN = "subdomain";
    static final String TOKEN = "token";
    static final String API_PATH = "/api/v2";
    // The incremental exports' own limit is ten a minute; one request every six seconds keeps a pass
    // inside it by itself.
    static final Duration EXPORT_INTERVAL = Duration.ofSeconds(6);

    // A Zendesk subdomain: one DNS label. A person may paste the whole address; the label is taken.
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern PASTED = Pattern.compile(
            "^(?:https?://)?([A-Za-z0-9-]+)(?:\\.zendesk\\.com)?(?:[/:].*)?$", Pattern.CASE_INSENSITIVE);

    static final RatePacing PACING = RatePacing.budget("ratelimit-remaining", "ratelimit-reset",
                    RatePacing.Reset.DELTA_SECONDS, Duration.ofMinutes(1), 20)
            .or(RatePacing.budget("X-Rate-Limit-Remaining", null, RatePacing.Reset.NONE, Duration.ofMinutes(1), 20));

    private ZendeskApi() {}

    /** The client for every endpoint but the incremental exports. */
    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        return builder(http, connection).build();
    }

    /** The client for the incremental exports: the same, paced to their ten requests a minute. */
    static ApiClient exportClient(ConnectorHttp http, ResolvedCredentials connection) {
        return builder(http, connection).minInterval(EXPORT_INTERVAL).build();
    }

    private static ApiClient.Builder builder(ConnectorHttp http, ResolvedCredentials connection) {
        return http.client(accountUrl(connection) + API_PATH)
                .service("Zendesk")
                .auth(auth(connection))
                .rateLimit(RateLimitHint.STANDARD)
                .pacing(PACING);
    }

    private static ApiAuth auth(ResolvedCredentials connection) {
        if (connection.authKind() != AuthKind.BASIC) return ApiAuth.bearer(connection);
        return ApiAuth.header(HttpHeaders.AUTHORIZATION, () -> {
            String email = connection.username();
            String token = connection.secret(TOKEN) != null ? connection.secret(TOKEN) : connection.soleSecret();
            if (email == null || email.isBlank() || token == null || token.isBlank()) {
                throw new ConnectorAuthException("This connection has no agent email address or API token. "
                        + "Add them in Settings > Connections.");
            }
            String pair = email.strip() + "/token:" + token.strip();
            return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
        });
    }

    /**
     * The account's address, without {@code /api/v2}: the connection's Zendesk address when one is
     * typed, else {@code https://SUBDOMAIN.zendesk.com}.
     */
    static String accountUrl(ResolvedCredentials connection) {
        if (connection.hasBaseUrl()) return trimAddress(connection.baseUrl());
        String subdomain = subdomain(connection.settings());
        if (subdomain == null) {
            throw new ConnectorAuthException("This connection has no Zendesk subdomain. Add it in Settings > Connections.");
        }
        return "https://" + subdomain + ".zendesk.com";
    }

    /** {@code https://acme.zendesk.com/api/v2/} and {@code https://acme.zendesk.com} are one account. */
    static String trimAddress(String address) {
        String base = address.strip().replaceAll("/+$", "");
        if (base.toLowerCase(Locale.ROOT).endsWith(API_PATH)) base = base.substring(0, base.length() - API_PATH.length());
        return base.replaceAll("/+$", "");
    }

    /**
     * The connection's subdomain as one lower-case DNS label - {@code acme} from {@code acme},
     * {@code acme.zendesk.com} or {@code https://acme.zendesk.com/agent} - or null when it has none or
     * it is not one.
     */
    static String subdomain(Map<String, String> settings) {
        String raw = settings == null ? null : settings.get(SUBDOMAIN);
        if (raw == null || raw.isBlank()) return null;
        Matcher m = PASTED.matcher(raw.strip());
        if (!m.matches()) return null;
        String label = m.group(1).toLowerCase(Locale.ROOT);
        return LABEL.matcher(label).matches() ? label : null;
    }

    /** What the Test names the account by: its subdomain, else the host of its address. */
    static String accountName(ResolvedCredentials connection) {
        String subdomain = subdomain(connection.settings());
        if (subdomain != null) return subdomain;
        String url = accountUrl(connection);
        return url.replaceFirst("^https?://", "").replaceFirst("[/:].*$", "");
    }
}
