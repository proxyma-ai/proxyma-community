package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.AuthMethod;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.OAuth2Spec;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.RefusalReason;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * YouTube - a connection of its own, though it signs in with Google (2026-10-30, the maintainer: big
 * channels get "so many comments and questions"). One connection is one channel: the one the account
 * signs in as.
 *
 * <p><b>Its own service, not a source type of the Google connection.</b> The framework asks for a
 * connection's scopes as its service declares them, so YouTube under the Google connection would have
 * asked every Drive-only connection for YouTube access - and replying to comments needs YouTube's
 * WRITE scope. Here:
 * <ul>
 *   <li>{@code youtube.readonly} always - the channel, its videos and the comments on them;</li>
 *   <li>{@code yt-analytics.readonly} always (2026-10-31) - the channel's numbers: the YouTube Analytics
 *       API's reports and the YouTube Reporting API's reach CSVs (impressions and click-through rate),
 *       which both take it. A connection signed in before it was asked keeps reading comments; the
 *       analytics tools say to sign in again. The token answer's {@code scope} is kept
 *       ({@link OAuth2Spec.Builder#keepGrantField}) so a tool can tell it was not granted without asking
 *       Google;</li>
 *   <li>{@code youtube.force-ssl} only while the connection's Write switch is on (the framework's
 *       incremental consent, {@link OAuth2Spec#writeScopes()}) - the narrowest scope that can post a
 *       reply. A connection whose switch is turned on later signs in again to be granted it. It READS
 *       as well (every Data API list the tools and the source make accepts it), so while Write is on
 *       {@code youtube.readonly} is NOT asked beside it (2026-11-08, {@link OAuth2Spec#writeReplaces()}):
 *       Google's review counts a scope another one covers as one too many. A connection signed in under
 *       the older set - all three - keeps working as it is.</li>
 * </ul>
 *
 * <p><b>What a person may untick</b> (Google's granular consent, {@link #consentShortfall}): without a
 * scope that reads the channel - or, with Write on, without {@code youtube.force-ssl} - the connection is
 * marked as needing a new sign-in, with a sentence saying what was not allowed. Without
 * {@code yt-analytics.readonly} it stays connected: comments work, and the analytics tools and the Test
 * say the numbers need a new sign-in.
 *
 * <p><b>Removing access</b>: a deleted or signed-out connection's grant is revoked at Google
 * ({@code oauth2.googleapis.com/revoke}, {@link OAuth2Spec#revokeUrl()}).
 *
 * <p><b>Quota</b>: {@link #requestUnits} prices each Data API request as YouTube does, for the built-in
 * client's per-install daily budget (BuiltInQuota); the Analytics and Reporting APIs have quotas of
 * their own and cost nothing here.
 *
 * <p><b>Sign-in</b>: an OAuth2 authorization code with PKCE through an OAuth client the channel's
 * owner registers in Google Cloud - typed on the connection, as for the Google connection. There is no
 * service account: a channel is a person's or a brand's, not a Workspace user's.
 * {@code access_type=offline} and {@code prompt=consent}, so Google hands back a refresh token every
 * time. An app left in Google's "Testing" status has its refresh tokens expire after 7 days; the manual
 * says to publish it.
 */
@Component
public class YouTubeConnectionPlugin implements ConnectionPlugin {

    static final String AUTHORIZE_URL = GoogleConnectionPlugin.AUTHORIZE_URL;
    static final String TOKEN_URL = GoogleConnectionPlugin.TOKEN_URL;
    static final String READ_SCOPE = "https://www.googleapis.com/auth/youtube.readonly";
    static final String ANALYTICS_SCOPE = "https://www.googleapis.com/auth/yt-analytics.readonly";
    static final String WRITE_SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl";
    // youtube (manage the account) reads and writes comments too; never asked, but a grant naming it serves.
    static final String MANAGE_SCOPE = "https://www.googleapis.com/auth/youtube";
    static final List<String> SCOPES = List.of(READ_SCOPE, ANALYTICS_SCOPE);
    static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    // Where the Analytics and Reporting APIs live when the connection's API URL is Google's; a changed
    // URL (a test server) serves all three APIs, under paths that do not overlap.
    static final String ANALYTICS_URL = "https://youtubeanalytics.googleapis.com";
    static final String REPORTING_URL = "https://youtubereporting.googleapis.com";
    static final String REACH_REPORT = "channel_reach_basic_a1";
    static final List<String> WRITE_SCOPES = List.of(WRITE_SCOPE);

    /**
     * Google's account chooser, then consent (2026-11-03, the maintainer manages two channels): a
     * connection serves the channel chosen at sign-in ({@code mine=true}), and with {@code consent}
     * alone Google may sign straight in as the account already in the browser, so a second channel - a
     * brand account under the same Google account - could not be picked. {@code select_account} always
     * shows the chooser; the same OAuth client then serves any channel its owner manages.
     */
    static final String SIGN_IN_PROMPT = "select_account consent";
    static final String CLIENT_ID = "clientId";
    static final String CLIENT_SECRET = "clientSecret";

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.YOUTUBE)
            .displayName("YouTube")
            .mark("youtube")
            .auth(AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Google",
                    FieldSpec.text(CLIENT_ID, "Client ID")
                            .placeholder("1234567890-abc.apps.googleusercontent.com")
                            .help("From your OAuth client in Google Cloud > APIs & Services > Credentials."),
                    FieldSpec.secret(CLIENT_SECRET, "Client secret")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(GoogleApi.DEFAULT_BASE_URL).asAdvanced()
                    .help("Leave it as it is unless you are told otherwise."))
            .sourceTypes(ConnectorType.YOUTUBE_CHANNEL)
            // replyToYouTubeComment: the agent's one write here, behind the connection's Write switch.
            .supportsWrite()
            .docsAnchor("google-cloud-setup")
            .build();

    private final OAuth2Spec oauth2;

    /** Google's own sign-in, for a connector's tests. */
    public YouTubeConnectionPlugin() {
        this(AUTHORIZE_URL, TOKEN_URL, REVOKE_URL);
    }

    // The same switches as the Google connection's: a test server's sign-in moves all three.
    @Autowired
    public YouTubeConnectionPlugin(@Value("${proxyma.connectors.google.authorize-url:" + AUTHORIZE_URL + "}") String authorizeUrl,
                                   @Value("${proxyma.connectors.google.token-url:" + TOKEN_URL + "}") String tokenUrl,
                                   @Value("${proxyma.connectors.google.revoke-url:" + REVOKE_URL + "}") String revokeUrl) {
        this.oauth2 = OAuth2Spec.builder(blankTo(authorizeUrl, AUTHORIZE_URL), blankTo(tokenUrl, TOKEN_URL))
                .scopes(SCOPES).writeScopes(WRITE_SCOPES).writeReplaces(List.of(READ_SCOPE))
                .revokeUrl(blankTo(revokeUrl, REVOKE_URL))
                .authorizeParam("access_type", "offline").authorizeParam("prompt", SIGN_IN_PROMPT)
                // Google's token answer names the scopes the person actually granted - a consent screen
                // lets them untick one.
                .keepGrantField("scope").build();
    }

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public OAuth2Spec oauth2() {
        return oauth2;
    }

    /**
     * Without a scope that reads the channel there is nothing the connection can do; with Write on and no
     * {@code youtube.force-ssl}, the replies the person approves cannot be posted. Analytics left unticked
     * is not a shortfall: comments work, and the tools say what the numbers need.
     */
    @Override
    public Optional<String> consentShortfall(Set<String> granted, boolean write) {
        boolean writes = granted.contains(WRITE_SCOPE) || granted.contains(MANAGE_SCOPE);
        boolean reads = writes || granted.contains(READ_SCOPE);
        if (!reads) return Optional.of(CANNOT_READ);
        if (write && !writes) return Optional.of(CANNOT_POST);
        return Optional.empty();
    }

    // The two shortfalls, as the connection's row shows them beside "Sign in again".
    static final String CANNOT_READ = "Google was not allowed to let Proxyma read the channel's comments and videos. "
            + "Sign in again and leave every permission ticked.";
    static final String CANNOT_POST = "Google was not allowed to let Proxyma post the replies you approve. Sign in again "
            + "and leave every permission ticked, or turn Write off.";

    /**
     * A YouTube Data API request's cost in quota units, as YouTube publishes it
     * (developers.google.com/youtube/v3/determine_quota_cost, read 2026-11-08): every list 1, every insert,
     * update, delete and moderation 50, captions dearer; anything not under {@code /youtube/v3/} - the
     * Analytics and Reporting APIs, metered apart - 0.
     */
    @Override
    public int requestUnits(HttpMethod method, URI uri) {
        String path = uri == null || uri.getRawPath() == null ? "" : uri.getRawPath();
        int at = path.indexOf("/youtube/v3/");
        if (at < 0) return 0;
        String resource = path.substring(at + "/youtube/v3/".length()).split("/")[0];
        boolean read = method == null || HttpMethod.GET.equals(method);
        if ("captions".equals(resource)) {
            if (read) return 50;
            if (HttpMethod.POST.equals(method)) return 400;
            return HttpMethod.PUT.equals(method) ? 450 : 50;
        }
        if (read) return 1;
        if ("liveChatBans".equals(resource) || "liveChatModerators".equals(resource)) return 200;
        if ("liveChatMessages".equals(resource)) return 20;
        return 50;
    }

    /**
     * The channel the sign-in reads as, by its title; a 403 is a project without the YouTube Data API.
     * Then whether the channel's NUMBERS can be read too - said beside the channel, never as a failure,
     * since comments work without them: the sign-in did not allow YouTube Analytics, or the Google Cloud
     * project has not enabled the Analytics or the Reporting API. And, once both answer, the reach job is
     * made if the channel has none, so YouTube starts collecting impressions at connect time - its first
     * day arrives two days after the job is made (getYouTubeReach makes it otherwise).
     */
    @Override
    public boolean identityRequest(HttpMethod method, URI uri) {
        if (uri == null || uri.getRawPath() == null || !(method == null || HttpMethod.GET.equals(method))) return false;
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        return uri.getRawPath().endsWith("/youtube/v3/channels") && ("&" + query + "&").contains("&mine=true&");
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            ApiClient youtube = GoogleApi.client(http, connection, "YouTube");
            JsonNode channel = YouTubeApi.myChannel(youtube);
            if (channel == null) {
                return ConnectionTestResult.failed("This Google account has no YouTube channel. Sign in with the account "
                        + "that owns the channel.");
            }
            return withAnalytics(connection, http, channel.path("snippet").path("title").asText(""));
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return ConnectionTestResult.failed("Google did not accept the connection's sign-in. Sign in again.");
            }
            if (e.status() == 403 && e.getMessage() != null && e.getMessage().contains("quotaExceeded")) {
                // A spent quota is a limit, not a refusal (2026-11-11): the sign-in is fine and the name comes later.
                if (e.getMessage().contains("Proxyma's shared sign-in")) {
                    return ConnectionTestResult.failed("Signed in. YouTube's daily limit for Proxyma's shared sign-in is "
                            + "reached on this computer, so the channel's name is read after midnight Pacific time.");
                }
                return ConnectionTestResult.failed("Signed in. YouTube's daily quota for the connection's Google Cloud "
                        + "project is spent, so the channel's name is read after midnight Pacific time.");
            }
            if (e.status() == 403) {
                return ConnectionTestResult.failed("Google refused access. Check that the YouTube Data API v3 is enabled "
                        + "in the Google Cloud project.");
            }
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }

    private static ConnectionTestResult withAnalytics(ResolvedCredentials connection, ConnectorHttp http, String user) {
        String granted = connection.grant("scope");
        if (granted != null && !granted.isBlank() && !List.of(granted.strip().split("\\s+")).contains(ANALYTICS_SCOPE)) {
            return ConnectionTestResult.ok("Signed in as {user}. YouTube Analytics is not allowed yet: sign in again to "
                    + "allow it.", "user", user);
        }
        try {
            ApiClient analytics = client(http, connection, ANALYTICS_URL, "YouTube Analytics");
            String day = LocalDate.now(ZoneOffset.UTC).minusDays(3).toString();
            ApiResponse probe = analytics.get("v2/reports", Map.of("ids", "channel==MINE", "startDate", day, "endDate", day,
                    "metrics", "views"));
            Set<String> reasons = reasons(probe);
            if (scopeMissing(reasons)) {
                return ConnectionTestResult.ok("Signed in as {user}. YouTube Analytics is not allowed yet: sign in again to "
                        + "allow it.", "user", user);
            }
            if (disabled(reasons)) {
                return ConnectionTestResult.ok("Signed in as {user}. Enable the YouTube Analytics API in the Google Cloud "
                        + "project to read the channel's numbers.", "user", user);
            }
            if (!probe.ok()) return ConnectionTestResult.ok("Signed in as {user}", "user", user);

            ApiClient reporting = client(http, connection, REPORTING_URL, "YouTube Reporting");
            ApiResponse jobs = reporting.get("v1/jobs");
            if (disabled(reasons(jobs))) {
                return ConnectionTestResult.ok("Signed in as {user}. Enable the YouTube Reporting API in the Google Cloud "
                        + "project to read impressions and click-through rate.", "user", user);
            }
            if (jobs.ok() && !hasReachJob(jobs.json())) {
                // Starts the collection; a 409 is a job made meanwhile. Nothing public, read-only scope.
                reporting.send(HttpMethod.POST, "v1/jobs", Map.of(),
                        Map.of("reportTypeId", REACH_REPORT, "name", "Proxyma reach"), Map.of());
            }
            return ConnectionTestResult.ok("Signed in as {user}. YouTube Analytics is allowed.", "user", user);
        } catch (RuntimeException e) {
            // The channel answered: that is the Test. The numbers are a note, never the verdict.
            return ConnectionTestResult.ok("Signed in as {user}", "user", user);
        }
    }

    private static boolean hasReachJob(JsonNode jobs) {
        for (JsonNode j : jobs.path("jobs")) if (REACH_REPORT.equals(j.path("reportTypeId").asText(""))) return true;
        return false;
    }

    // A client for one of YouTube's other APIs: its own host, or the connection's changed API URL.
    private static ApiClient client(ConnectorHttp http, ResolvedCredentials connection, String host, String service) {
        String base = host;
        if (connection.hasBaseUrl()) {
            String own = connection.baseUrl().strip().replaceAll("/+$", "");
            if (!own.equalsIgnoreCase(GoogleApi.DEFAULT_BASE_URL)) base = own;
        }
        return http.client(base).service(service).auth(ApiAuth.bearer(connection)).rateLimit(GoogleApi.RATE_LIMIT)
                .refusalReason(RefusalReason.GOOGLE).maxAttempts(2).maxTotalWait(Duration.ofSeconds(15)).build();
    }

    private static Set<String> reasons(ApiResponse response) {
        Set<String> out = new HashSet<>();
        if (response.ok() || response.bytes() == null || response.bytes().length == 0) return out;
        try {
            JsonNode error = response.json().path("error");
            for (JsonNode e : error.path("errors")) out.add(e.path("reason").asText(""));
            for (JsonNode d : error.path("details")) out.add(d.path("reason").asText(""));
        } catch (RuntimeException unreadable) {
            // no reason
        }
        return out;
    }

    private static boolean scopeMissing(Set<String> reasons) {
        return reasons.contains("insufficientPermissions") || reasons.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT");
    }

    private static boolean disabled(Set<String> reasons) {
        return reasons.contains("accessNotConfigured") || reasons.contains("SERVICE_DISABLED");
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
