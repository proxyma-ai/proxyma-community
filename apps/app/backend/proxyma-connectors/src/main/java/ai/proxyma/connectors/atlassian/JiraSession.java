package ai.proxyma.connectors.atlassian;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.jira.JiraIssueMarkdown;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Jira project, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the project key, the connection's server and credential - and nothing it
 * holds is seen by any other source; the shared bean and its per-type lock it replaced are gone.
 *
 * <p>Items are the issues, by key ({@code PROJ-123}) - the id every index already holds - rendered
 * on fetch by {@link JiraIssueMarkdown}, the renderer the agent's getJiraIssue uses too.
 *
 * <p><b>Data Center and Server</b> are read through REST v2: {@code /search} paged by offset.
 * <b>Cloud</b> through REST v3, whose {@code /search} is gone: {@code POST /search/jql}, paged by
 * {@code nextPageToken} with no total - and reported to hand back the first page again instead of
 * ending, so a page with nothing new ends the listing. Cloud states descriptions and comments as
 * Atlassian Document Format, which the renderer turns into markdown.
 */
final class JiraSession implements ConnectorSession {

    private static final Logger log = LoggerFactory.getLogger(JiraSession.class);

    static final int PAGE_SIZE = 100;
    private static final DateTimeFormatter JQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> LISTED_FIELDS = List.of("summary", "issuetype", "updated", "reporter");

    private final SourceContext context;
    private final ResolvedCredentials connection;
    private final boolean cloud;
    private final String projectKey;
    private final Set<String> skipCustomFields;
    private String base;
    private ApiClient api;
    private boolean signedIn;

    JiraSession(SourceContext context, String skipCustomFields) {
        this.context = context;
        this.connection = context.connection();
        this.cloud = AtlassianApi.isCloud(connection);
        // A source registered before Jira was built on the framework kept its key as its name alone.
        String key = context.get(JiraConnectorPlugin.PROJECT_KEY);
        if ((key == null || key.isBlank()) && context.name() != null) key = context.name();
        this.projectKey = key == null ? "" : key.strip().toUpperCase(Locale.ROOT);
        this.skipCustomFields = JiraIssueMarkdown.parseSkipCustomFields(skipCustomFields);
    }

    /** REST v3 on Cloud, v2 on Data Center and Server. */
    static String apiPath(ResolvedCredentials connection) {
        return AtlassianApi.isCloud(connection) ? "rest/api/3" : "rest/api/2";
    }

    // Built on first use: a connection with no server is a sentence, never a constructor that throws.
    private ApiClient api() {
        if (api == null) {
            base = AtlassianApi.productBase(connection);
            api = AtlassianApi.client(context.http(), connection, "Jira", base);
        }
        return api;
    }

    private String base() {
        api();
        return base;
    }

    // -- the probe -----------------------------------------------------------------------------

    /**
     * One cheap authenticated read of the PROJECT - its issue search, capped at a single issue. A
     * server-level call would pass for a key that does not exist or that this token cannot see,
     * which is the mistake registration most needs to catch.
     *
     * <p>On Cloud the search alone proves nothing: Jira Cloud answers a request it could not sign in -
     * a wrong, expired or revoked API token - as an ANONYMOUS one, and an anonymous search is a 200
     * with no issues. Such a source was registered and then listed nothing on every sync, with no
     * error anywhere (telemetry aced67d0). So Cloud also asks who the credential is, and an empty
     * search asks for the project itself, which Jira answers 404 when the account cannot see it.
     */
    @Override
    public AccessCheck verifyAccess() {
        if (projectKey.isEmpty()) return AccessCheck.failed("No Jira project key was given.");
        try {
            JsonNode probe = searchPage("project = \"" + projectKey + "\"", null, 0, 1);
            if (cloud) {
                requireSignedIn();
                if (Paginator.elements(probe.path("issues")).isEmpty()) {
                    api().getJson(apiPath(connection) + "/project/" + ApiClient.segment(projectKey));
                }
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            return AccessCheck.failed(described(e).getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    /**
     * Cloud only: that Jira signed the credential in as an ACCOUNT. A wrong, expired or revoked API
     * token is not a 401 on a search - Cloud answers it as an anonymous visitor, who sees no issues -
     * so without this a source lists nothing on every pass and says nothing. Asked once a session.
     */
    private void requireSignedIn() {
        if (signedIn) return;
        JsonNode me;
        try {
            me = api().getJson(apiPath(connection) + "/myself");
        } catch (ConnectorHttpException e) {
            throw described(e);
        }
        if (me.path("accountId").asText("").isBlank()) {
            throw new ConnectorHttpException(refusedCredential(), 401, apiPath(connection) + "/myself");
        }
        signedIn = true;
    }

    // The sentence for a credential Jira did not accept - a 401, or a Cloud request answered as nobody.
    private String refusedCredential() {
        return cloud
                ? "Jira at " + base + " did not accept the email address and API token - the token may be "
                        + "wrong, expired or revoked. Check it in Settings > Connections > Jira."
                : "Jira at " + base + " did not accept the access token - it may be wrong, expired or"
                        + " revoked. Check it in Settings > Connections > Jira.";
    }

    /**
     * The sentence for a failed search or fetch, said about THIS project - shared by the probe and
     * the listings, so a failed sync tells its reader as much as a refused registration did. A 401
     * is the credential itself, a 403 a working credential whose account cannot browse the project.
     */
    private ConnectorHttpException described(ConnectorHttpException e) {
        String message = switch (e.status()) {
            case 401 -> refusedCredential();
            case 403 -> "Jira accepted the access token, but its account has no permission to browse project "
                    + projectKey + " - ask a Jira administrator to grant it access.";
            // JQL naming a project the account cannot see is a 400 ("does not exist for the field").
            case 400, 404 -> "Project " + projectKey + " was not found on " + base
                    + ", or this account cannot browse it.";
            default -> null;
        };
        return message == null ? e : new ConnectorHttpException(message, e.status(), e.path(), e);
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        if (projectKey.isEmpty()) return Listings.failed("This Jira source has no project key.");
        Supplier<Iterator<ItemMetadata>> issues = () -> {
            if (cloud) requireSignedIn();
            String jql;
            if (request.isFull() || request.since() == null) {
                // BY KEY, which an edit cannot move. A listing paged by offset and ordered by update
                // time lost an issue edited while the walk was past its old position - it jumped to a
                // page already read, and was purged as deleted.
                jql = "project = \"" + projectKey + "\" ORDER BY key ASC";
            } else {
                String cutoff = incrementalCutoff(request.since(), ownerZone()).format(JQL_TIME);
                jql = "project = \"" + projectKey + "\" AND updated >= \"" + cutoff + "\" ORDER BY key ASC";
            }
            return Listings.mapped(cloud ? cloudPages(jql) : dataCenterPages(jql), this::toItem);
        };
        return Listings.concat(List.of(issues));
    }

    // Data Center and Server: GET /search, by offset, until the stated total is reached.
    private Iterator<JsonNode> dataCenterPages(String jql) {
        return Paginator.iterate(next -> {
            int startAt = next == null ? 0 : Integer.parseInt(next);
            JsonNode page = searchPage(jql, null, startAt, PAGE_SIZE);
            List<JsonNode> issues = Paginator.elements(page.path("issues"));
            int reached = startAt + issues.size();
            boolean more = page.has("total") ? reached < page.path("total").asInt() : issues.size() >= PAGE_SIZE;
            return new Paginator.Page<>(issues, more && !issues.isEmpty() ? String.valueOf(reached) : null);
        });
    }

    // Cloud: POST /search/jql, by nextPageToken. Stops at isLast, at a page with no issues, and at a
    // page holding nothing not already listed - the token that leads back to the first page.
    private Iterator<JsonNode> cloudPages(String jql) {
        Set<String> seen = new HashSet<>();
        return Paginator.iterate(next -> {
            JsonNode page = searchPage(jql, next, 0, PAGE_SIZE);
            List<JsonNode> issues = Paginator.elements(page.path("issues"));
            List<JsonNode> fresh = issues.stream().filter(i -> seen.add(i.path("key").asText())).toList();
            String token = page.path("nextPageToken").asText(null);
            boolean last = page.path("isLast").asBoolean(token == null) || fresh.isEmpty();
            return new Paginator.Page<>(fresh, last ? null : token);
        });
    }

    // One page of a JQL search, either way it is asked for - the failure said about this project.
    private JsonNode searchPage(String jql, String nextPageToken, int startAt, int max) {
        try {
            if (cloud) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("jql", jql);
                body.put("fields", LISTED_FIELDS);
                body.put("maxResults", max);
                if (nextPageToken != null) body.put("nextPageToken", nextPageToken);
                return api().requireOk(api().send(HttpMethod.POST, apiPath(connection) + "/search/jql", Map.of(), body,
                        Map.of())).json();
            }
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("jql", jql);
            query.put("startAt", startAt);
            query.put("maxResults", max);
            query.put("fields", String.join(",", LISTED_FIELDS));
            return api().getJson(apiPath(connection) + "/search", query);
        } catch (ConnectorHttpException e) {
            throw described(e);
        }
    }

    private ItemMetadata toItem(JsonNode issue) {
        String key = issue.path("key").asText("");
        if (key.isEmpty()) return null;
        JsonNode fields = issue.path("fields");
        ItemMetadata item = new ItemMetadata();
        item.setId(key);
        item.setTitle(fields.path("summary").asText(key));
        item.setSourceType(ConnectorType.JIRA);
        item.setContentType(AtlassianApi.MARKDOWN);
        Instant updated = AtlassianApi.instant(fields.path("updated").asText(null));
        item.setLastModified(updated != null ? updated : Instant.now());
        item.putExtra("url", base() + "/browse/" + key);
        String reporter = fields.path("reporter").path("displayName").asText("");
        if (!reporter.isBlank()) item.putExtra(ItemMetadata.AUTHOR, reporter);
        return item;
    }

    /**
     * The date-time JQL's {@code updated >=} is given (spec 01 section 4.2). Two corrections to the
     * obvious {@code since} in UTC, and both cost only re-reading issues that did not change, while
     * getting either wrong loses an edit until the next full sync:
     *
     * <ul>
     *   <li>Widened by 15 minutes, because this host's clock and Jira's are not the same clock.</li>
     *   <li>Rendered in the TOKEN OWNER's Jira time zone, because JQL parses a bare date-time
     *       string in that zone rather than in UTC. When the zone cannot be read, fall a full day
     *       back instead - a wider window is the safe direction to be wrong in.</li>
     * </ul>
     */
    static LocalDateTime incrementalCutoff(Instant since, ZoneId serverZone) {
        if (serverZone == null) {
            return LocalDateTime.ofInstant(since.minus(1, ChronoUnit.DAYS), ZoneOffset.UTC);
        }
        return LocalDateTime.ofInstant(since.minus(15, ChronoUnit.MINUTES), serverZone);
    }

    // The token owner's time zone from /myself, once per session. Null when it cannot be read - a
    // probe failing is not a reason to fail the sync, and the cutoff has a wider fallback for it.
    private ZoneId ownerZone() {
        try {
            String zone = api().getJson(apiPath(connection) + "/myself").path("timeZone").asText("");
            return zone.isBlank() ? null : ZoneId.of(zone);
        } catch (RuntimeException e) {
            log.warn("[Jira] Could not read the token owner's time zone from {} - widening the incremental "
                    + "cutoff by a day instead: {}", base, e.getMessage());
            return null;
        }
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String key = item.getId() == null ? "" : item.getId().strip();
        if (key.isEmpty()) throw new ConnectorException("That is not an issue of a Jira project.");
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("fields", "*all");
        query.put("expand", "names,changelog");
        JsonNode issue;
        try {
            issue = api().getJson(apiPath(connection) + "/issue/" + ApiClient.segment(key), query);
        } catch (ConnectorHttpException e) {
            // A gone issue is a distinct outcome, not an empty document: the drain removes it at once.
            if (e.isNotFound()) throw new ItemGoneException("Issue " + key + " no longer exists");
            throw described(e);
        }
        String markdown = JiraIssueMarkdown.render(issue, skipCustomFields);
        return new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8));
    }
}
