package ai.proxyma.core.connector.jira;

import ai.proxyma.core.connector.RestPassthrough;
import ai.proxyma.core.connector.SyncErrorAware;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Jira's REST API as the agent's tools reach it: issue detail, search, and the write operations
 * (create, update, comment, transition, link, log work, delete), each with the caller's own
 * server and credential passed in explicitly - nothing about one call is kept for the next.
 *
 * <p>INDEXING IS NOT HERE. A Jira source is synced by its native session (proxyma-connectors,
 * ai.proxyma.connectors.atlassian), which renders through the same {@link JiraIssueMarkdown} as
 * {@link #fetchIssueDetailsAsMarkdown}, so what the agent reads live is what was indexed.
 *
 * <p>A credential holding a colon is {@code email:API token} - a Jira Cloud connection - and is
 * sent as Basic; anything else is a personal access token, sent as a bearer.
 *
 * CRITICAL: JQL is NEVER passed to .uri(String) after URLEncoder.encode().
 * A JQL that can carry user text goes in as a URI variable - queryParam("jql", "{jql}")
 * .encode().buildAndExpand(...) - which encodes every reserved character, '+' included; a
 * literal queryParam value left '+' raw, read by the server as a space. Either way a URI, never a
 * String, reaches RestClient, so Spring's template engine cannot encode it twice (%25 corruption).
 *
 * Reference: https://docs.atlassian.com/software/jira/docs/api/REST/9.17.0/
 */
@Service
public class JiraConnector {

    private static final Logger log = LoggerFactory.getLogger(JiraConnector.class);

    private final RestClient restClient;

    public JiraConnector(
            @Qualifier("externalRestClientBuilder") RestClient.Builder externalRestClientBuilder) {
        // A clone: the builder is shared, and the guard is this client's alone - see DotSegmentGuard.
        this.restClient = externalRestClientBuilder.clone().requestInterceptor(new ai.proxyma.core.connector.DotSegmentGuard()).build();
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    // Every HTTP method below takes the server and the credential as explicit parameters - this bean
    // is shared by every user's tool calls and holds nothing of any one of them.
    // requireConfig()/addAuthHeader() are the two shared helpers all of them funnel through.
    private void addAuthHeader(HttpHeaders headers, String accessToken) {
        if (accessToken != null && accessToken.contains(":")) {
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(accessToken.getBytes(StandardCharsets.UTF_8)));
        } else {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
    }

    private void requireConfig(String baseUrl, String accessToken) {
        if (baseUrl == null || baseUrl.isEmpty() ||
                accessToken == null || accessToken.isEmpty()) {
            throw new IllegalStateException(MISSING_TOKEN_MESSAGE);
        }
    }

    static final String MISSING_TOKEN_MESSAGE =
            "Jira access token is not configured. Please set your Jira access token via Preferences";

    /**
     * Up to 500 characters of an error response body, for the exception message.
     *
     * <p>Capped rather than passed whole: an expired single-sign-on session answers a write with an
     * entire HTML login page, and that used to be concatenated verbatim into the message a user
     * reads. Jira's own {@code errorMessages} array - the part that makes a failed write actionable
     * - is at the front of a real error body, so the cap keeps what is worth keeping.
     */
    private static String errorBody(org.springframework.http.client.ClientHttpResponse res) {
        try {
            String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            return body.length() > 500 ? body.substring(0, 500) : body;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The response's status as a real {@link org.springframework.web.client.HttpStatusCodeException}.
     *
     * <p>Used where the status has to survive the throw and not just its text: {@link #verifyAccess}
     * picks 401/403 and 404 out of the cause chain to choose which sentence to show a user, and
     * {@link SyncErrorAware#summarize} renders "404 Not Found" from the same type. A hand-built
     * RuntimeException carrying the status only inside its message would make both of those parse
     * English.
     */
    private static RuntimeException statusException(
            org.springframework.http.client.ClientHttpResponse res, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            return res.getStatusCode().is4xxClientError()
                    ? org.springframework.web.client.HttpClientErrorException.create(
                            res.getStatusCode(), res.getStatusText(), res.getHeaders(), bytes, StandardCharsets.UTF_8)
                    : org.springframework.web.client.HttpServerErrorException.create(
                            res.getStatusCode(), res.getStatusText(), res.getHeaders(), bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new RuntimeException("Jira API call failed: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    //  JIRA REST API CLIENT
    // =========================================================================

    // ---- Project -------------------------------------------------------------

    /**
     * Fetch the username of the token owner - for use as an issue assignee.
     * GET /rest/api/2/myself
     *
     * @return the "name" field (Server/DC login name, e.g. "jsmith") - not "accountId"/"displayName",
     *         which are Cloud-only and not accepted by this connector's assignee field (see createIssue/updateIssue).
     */
    public String fetchCurrentUsername(String baseUrl, String accessToken) {
        requireConfig(baseUrl, accessToken);
        try {
            String url = baseUrl + "/rest/api/2/myself";
            JsonNode response = restClient.get()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Jira myself returned HTTP " + res.getStatusCode());
                    })
                    .body(JsonNode.class);
            if (response == null)
                throw new RuntimeException("Empty response from Jira myself API");
            return response.path("name").asText(null);
        } catch (Exception e) {
            log.error("Failed to fetch current Jira user: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch current Jira user: " + SyncErrorAware.summarize(e), e);
        }
    }

    public List<JiraProjectDto> listAllProjects(String baseUrl, String accessToken) {
        requireConfig(baseUrl, accessToken);
        try {
            String url = baseUrl + "/rest/api/2/project";
            log.debug("Listing all Jira projects from: {}", baseUrl);

            JsonNode response = restClient.get()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.isArray()) return Collections.emptyList();

            List<JiraProjectDto> projects = new ArrayList<>();
            for (JsonNode node : response) {
                JiraProjectDto p = new JiraProjectDto();
                p.setKey(node.get("key").asText());
                p.setName(node.get("name").asText());
                if (node.has("self")) p.setSelf(node.get("self").asText());
                projects.add(p);
            }
            log.info("[Jira] Listed {} projects from {}", projects.size(), baseUrl);
            return projects;
        } catch (Exception e) {
            log.error("Failed to list Jira projects: {}", e.getMessage());
            throw new RuntimeException("Failed to list Jira projects", e);
        }
    }

    // ---- Field discovery -----------------------------------------------------

    // ---- Issue batch fetch ----------------------------------------------------

    // ---- Issue full detail ----------------------------------------------------

    // call pass its own resolved token directly instead of going through the shared
    // authAccessToken field, so no synchronized(connector) lock is needed on that path (see
    // ../proxyma-docs/LESSONS_LEARNED.md's 2026-07-16 entry). The baseUrl-only overload above still backs
    // getContent()/background sync, which sets authAccessToken once via auth() and reads it
    // across many sequential calls - that path is unaffected and still guarded by
    // SyncOrchestratorService's own synchronized(connector).
    public JiraIssueDto fetchIssueDetails(String baseUrl, String accessToken, String issueKey) {
        requireConfig(baseUrl, accessToken);
        try {
            java.net.URI uri = UriComponentsBuilder
                    .fromUriString(baseUrl + "/rest/api/2/issue/" + issueKey)
                    .queryParam("fields", "*all")
                    .queryParam("expand", "names,changelog")
                    .build().encode().toUri();

            log.debug("Fetching full issue details: {}", issueKey);

            JsonNode response = restClient.get()
                    .uri(uri)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .exchange((req, res) -> {
                        if (res.getStatusCode().value() == 404)
                            return null;
                        if (!res.getStatusCode().is2xxSuccessful()) {
                            String body = "";
                            try {
                                body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            } catch (Exception ignored) {
                            }
                            log.error("Jira API returned HTTP {} for issue {}: {}",
                                    res.getStatusCode(), issueKey,
                                    body.substring(0, Math.min(500, body.length())));
                            throw new RuntimeException(
                                    "Jira API returned HTTP " + res.getStatusCode() + " for issue: " + issueKey);
                        }
                        return res.bodyTo(JsonNode.class);
                    });

            if (response == null)
                return null;
            return JiraIssueMarkdown.parseIssueWithDetails(response, JiraIssueMarkdown.BUILTIN_SKIP_CUSTOM_DISPLAY_NAMES);

        } catch (Exception e) {
            log.error("Failed to fetch issue details: {}", issueKey, e);
            throw new RuntimeException("Failed to fetch issue details: " + issueKey + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    public String fetchIssueDetailsAsMarkdown(String baseUrl, String accessToken, String issueKey) {
        JiraIssueDto dto = fetchIssueDetails(baseUrl, accessToken, issueKey);
        if (dto == null) {
            // issues aren't "Jira issues" from the caller's point of view.
            throw new IllegalArgumentException("Issue not found: " + issueKey);
        }
        return JiraIssueMarkdown.issueToMarkdown(dto);
    }

    // ---- Search ---------------------------------------------------------------

    public List<JiraIssueDto> searchIssues(String baseUrl, String accessToken, String jql, int maxResults) {
        return searchIssues(baseUrl, accessToken, jql, maxResults, false);
    }

    // What a search asks each issue for - the summary a tool shows and the parts it is rendered from.
    private static final List<String> SEARCH_FIELDS = List.of("summary", "description", "issuetype", "status",
            "priority", "assignee", "reporter", "updated", "created", "components", "labels", "parent");

    /**
     * Issues matching {@code jql}, one page of at most 100. {@code cloud} is the product line - the
     * connection signs in to an Atlassian Cloud site - and decides the endpoint: Cloud removed
     * {@code /rest/api/2/search} and {@code /rest/api/3/search} (410 Gone) for
     * {@code POST /rest/api/3/search/jql}, which takes the JQL in a JSON body and answers
     * descriptions in Atlassian Document Format (JiraIssueMarkdown reads both); Data Center and
     * Server keep the GET.
     */
    public List<JiraIssueDto> searchIssues(String baseUrl, String accessToken, String jql, int maxResults, boolean cloud) {
        requireConfig(baseUrl, accessToken);
        if (cloud) return searchIssuesCloud(baseUrl, accessToken, jql, maxResults);
        try {
            // The JQL as a URI VARIABLE, strictly encoded: put in as a literal query value, a '+'
            // ("C++", "a+b") went out raw and the server read it as a space.
            java.net.URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/rest/api/2/search")
                    .queryParam("jql", "{jql}")
                    .queryParam("startAt", 0)
                    .queryParam("maxResults", Math.min(maxResults, 100))
                    .queryParam("fields",
                            "summary,description,issuetype,status,priority,assignee,reporter,"
                            + "updated,created,components,labels,parent")
                    .encode().buildAndExpand(java.util.Map.of("jql", jql)).toUri();

            JsonNode response = restClient.get()
                    .uri(uri)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String body = errorBody(res);
                        log.error("Jira search returned HTTP {}: {}", res.getStatusCode(), body);
                        throw new RuntimeException(
                                "Jira search failed with HTTP " + res.getStatusCode() + ": " + body);
                    })
                    .body(JsonNode.class);

            if (response == null)
                return Collections.emptyList();

            List<JiraIssueDto> results = new ArrayList<>();
            JsonNode issuesNode = response.path("issues");
            if (issuesNode.isArray()) {
                for (JsonNode issueNode : issuesNode)
                    results.add(JiraIssueMarkdown.parseIssueWithDetails(issueNode, JiraIssueMarkdown.BUILTIN_SKIP_CUSTOM_DISPLAY_NAMES));
            }
            log.debug("Jira search returned {} results for jql: {}", results.size(), jql);
            return results;
        } catch (Exception e) {
            log.error("Failed to search Jira issues: {}", e.getMessage());
            throw new RuntimeException("Failed to search Jira issues: " + SyncErrorAware.summarize(e), e);
        }
    }

    public List<Map<String, Object>> searchIssuesSummary(String baseUrl, String accessToken, String jql, int maxResults) {
        return searchIssuesSummary(baseUrl, accessToken, jql, maxResults, false);
    }

    public List<Map<String, Object>> searchIssuesSummary(String baseUrl, String accessToken, String jql, int maxResults,
                                                         boolean cloud) {
        return searchIssues(baseUrl, accessToken, jql, maxResults, cloud).stream()
                .map(dto -> Map.<String, Object>of(
                        "key", dto.getKey(),
                        "summary", dto.getSummary() != null ? dto.getSummary() : "",
                        "status", dto.getStatus() != null ? dto.getStatus() : "",
                        "description", dto.getDescription() != null ? dto.getDescription() : "",
                        "url", baseUrl + "/browse/" + dto.getKey()))
                .toList();
    }

    // Cloud's search: the JQL in a JSON body - no URI encoding to get wrong - and the fields named,
    // since search/jql answers ids alone when none are.
    private List<JiraIssueDto> searchIssuesCloud(String baseUrl, String accessToken, String jql, int maxResults) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jql", jql);
            body.put("maxResults", Math.max(1, Math.min(maxResults, 100)));
            body.put("fields", SEARCH_FIELDS);
            JsonNode response = restClient.post()
                    .uri(java.net.URI.create(baseUrl + "/rest/api/3/search/jql"))
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String err = errorBody(res);
                        log.error("Jira search returned HTTP {}: {}", res.getStatusCode(), err);
                        throw new RuntimeException("Jira search failed with HTTP " + res.getStatusCode() + ": " + err);
                    })
                    .body(JsonNode.class);
            if (response == null) return Collections.emptyList();
            List<JiraIssueDto> results = new ArrayList<>();
            for (JsonNode issueNode : response.path("issues")) {
                results.add(JiraIssueMarkdown.parseIssueWithDetails(issueNode, JiraIssueMarkdown.BUILTIN_SKIP_CUSTOM_DISPLAY_NAMES));
            }
            log.debug("Jira Cloud search returned {} results for jql: {}", results.size(), jql);
            return results;
        } catch (Exception e) {
            log.error("Failed to search Jira issues: {}", e.getMessage());
            throw new RuntimeException("Failed to search Jira issues: " + SyncErrorAware.summarize(e), e);
        }
    }

    // ---- Write operations -----------------------------------------------------

    /** allowedValues kept per field - a priority or component list is short, a version list is not. */
    private static final int MAX_ALLOWED_VALUES = 25;

    /**
     * The issue types a project can create, or - with {@code issueTypeName} - the fields that type
     * takes at creation: id, name, required, type and the allowed values where Jira lists them.
     *
     * <p>Asked of {@code /rest/api/2/issue/createmeta/{project}/issuetypes[/{id}]} (Jira 8.4 and
     * later; Jira 9 removed the older form), falling back to the older
     * {@code /rest/api/2/issue/createmeta?projectKeys=} on a 404. It is how a caller learns which
     * type names exist (a scheme can rename or add any of them), whether a Story goes under an Epic
     * through {@code parent} or through an "Epic Link" custom field, and which fields a type
     * requires before a create is refused for lacking one.
     */
    public Map<String, Object> listCreateMeta(String baseUrl, String accessToken, String projectKey,
                                              String issueTypeName) {
        requireConfig(baseUrl, accessToken);
        if (projectKey == null || projectKey.isBlank()) throw new IllegalArgumentException("projectKey is required");
        String project = projectKey.strip();
        try {
            JsonNode types = getJsonOrNull(UriComponentsBuilder
                    .fromUriString(baseUrl + "/rest/api/2/issue/createmeta/{project}/issuetypes")
                    .queryParam("maxResults", 200)
                    .buildAndExpand(project).encode().toUri(), accessToken);
            if (types != null) {
                List<JsonNode> values = new ArrayList<>();
                types.path("values").forEach(values::add);
                if (issueTypeName == null || issueTypeName.isBlank()) return typesResult(project, values);
                JsonNode type = findType(values, issueTypeName);
                if (type == null) throw unknownType(project, issueTypeName, values);
                JsonNode fields = getJsonOrNull(UriComponentsBuilder
                        .fromUriString(baseUrl + "/rest/api/2/issue/createmeta/{project}/issuetypes/{id}")
                        .queryParam("maxResults", 500)
                        .buildAndExpand(project, type.path("id").asText()).encode().toUri(), accessToken);
                List<Map<String, Object>> out = new ArrayList<>();
                if (fields != null) {
                    for (JsonNode f : fields.path("values")) {
                        out.add(describeCreateField(f.path("fieldId").asText(f.path("key").asText("")), f));
                    }
                }
                return fieldsResult(project, type, out);
            }

            // Older servers: one call that carries every type and, expanded, every field.
            JsonNode legacy = getJsonOrNull(UriComponentsBuilder
                    .fromUriString(baseUrl + "/rest/api/2/issue/createmeta")
                    .queryParam("projectKeys", project)
                    .queryParam("expand", "projects.issuetypes.fields")
                    .build().encode().toUri(), accessToken);
            JsonNode projectNode = legacy == null ? null : legacy.path("projects").path(0);
            if (projectNode == null || projectNode.isMissingNode()) {
                throw new IllegalArgumentException("Jira knows no project '" + project
                        + "' that this account can create issues in.");
            }
            List<JsonNode> values = new ArrayList<>();
            projectNode.path("issuetypes").forEach(values::add);
            if (issueTypeName == null || issueTypeName.isBlank()) return typesResult(project, values);
            JsonNode type = findType(values, issueTypeName);
            if (type == null) throw unknownType(project, issueTypeName, values);
            List<Map<String, Object>> out = new ArrayList<>();
            type.path("fields").fields().forEachRemaining(e -> out.add(describeCreateField(e.getKey(), e.getValue())));
            return fieldsResult(project, type, out);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to read Jira create metadata for project {}: {}", project, e.getMessage());
            throw new RuntimeException("Failed to read Jira create metadata: " + SyncErrorAware.summarize(e), e);
        }
    }

    /**
     * Attach a file to an issue. POST /rest/api/2/issue/{key}/attachments, which Jira refuses
     * without the {@code X-Atlassian-Token: no-check} header (its XSRF guard for multipart posts).
     */
    public Map<String, Object> addAttachment(String baseUrl, String accessToken, String issueKey, String fileName,
                                             byte[] content) {
        requireConfig(baseUrl, accessToken);
        try {
            org.springframework.util.LinkedMultiValueMap<String, Object> parts = new org.springframework.util.LinkedMultiValueMap<>();
            parts.add("file", new org.springframework.core.io.ByteArrayResource(content) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            });
            JsonNode response = restClient.post()
                    .uri(UriComponentsBuilder.fromUriString(baseUrl + "/rest/api/2/issue/{key}/attachments")
                            .buildAndExpand(issueKey).encode().toUri())
                    .headers(h -> {
                        addAuthHeader(h, accessToken);
                        h.set("X-Atlassian-Token", "no-check");
                    })
                    .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Jira addAttachment failed with HTTP " + res.getStatusCode()
                                + ": " + errorBody(res));
                    })
                    .body(JsonNode.class);
            JsonNode a = response != null && response.isArray() ? response.path(0) : response;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", a == null ? "" : a.path("id").asText(""));
            out.put("fileName", a == null ? fileName : a.path("filename").asText(fileName));
            out.put("sizeBytes", content.length);
            String url = a == null ? "" : a.path("content").asText("");
            if (!url.isBlank()) out.put("url", url);
            return out;
        } catch (Exception e) {
            log.error("Failed to attach {} to Jira issue {}: {}", fileName, issueKey, e.getMessage());
            throw new RuntimeException("Failed to attach " + fileName + " to " + issueKey + ": "
                    + SyncErrorAware.summarize(e), e);
        }
    }

    // A 404 is an answer here (the endpoint does not exist on this server, so try the older one),
    // not a failure; every other non-2xx is.
    private JsonNode getJsonOrNull(java.net.URI uri, String accessToken) {
        return restClient.get().uri(uri)
                .headers(h -> addAuthHeader(h, accessToken))
                .exchange((req, res) -> {
                    if (res.getStatusCode().value() == 404) return null;
                    if (!res.getStatusCode().is2xxSuccessful()) {
                        throw new RuntimeException("Jira returned HTTP " + res.getStatusCode() + ": " + errorBody(res));
                    }
                    return res.bodyTo(JsonNode.class);
                });
    }

    private static JsonNode findType(List<JsonNode> types, String name) {
        String wanted = name.strip();
        for (JsonNode t : types) {
            if (t.path("name").asText("").equalsIgnoreCase(wanted) || t.path("id").asText("").equals(wanted)) return t;
        }
        return null;
    }

    private static IllegalArgumentException unknownType(String project, String name, List<JsonNode> types) {
        List<String> names = types.stream().map(t -> t.path("name").asText("")).toList();
        return new IllegalArgumentException("Project " + project + " has no issue type '" + name
                + "'. Its types are: " + String.join(", ", names) + ".");
    }

    private static Map<String, Object> typesResult(String project, List<JsonNode> types) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode t : types) {
            Map<String, Object> type = new LinkedHashMap<>();
            type.put("id", t.path("id").asText(""));
            type.put("name", t.path("name").asText(""));
            type.put("subtask", t.path("subtask").asBoolean(false));
            String description = t.path("description").asText("");
            if (!description.isBlank()) type.put("description", description);
            out.add(type);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectKey", project);
        result.put("issueTypes", out);
        return result;
    }

    private static Map<String, Object> fieldsResult(String project, JsonNode type, List<Map<String, Object>> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectKey", project);
        result.put("issueType", type.path("name").asText(""));
        result.put("subtask", type.path("subtask").asBoolean(false));
        result.put("fields", fields);
        return result;
    }

    private static Map<String, Object> describeCreateField(String id, JsonNode f) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", id);
        field.put("name", f.path("name").asText(""));
        field.put("required", f.path("required").asBoolean(false));
        JsonNode schema = f.path("schema");
        String type = schema.path("type").asText("");
        if ("array".equals(type) && schema.hasNonNull("items")) type = "array of " + schema.path("items").asText("");
        field.put("type", type);
        String custom = schema.path("custom").asText("");
        if (!custom.isBlank()) field.put("customType", custom.substring(custom.lastIndexOf(':') + 1));
        JsonNode allowed = f.path("allowedValues");
        if (allowed.isArray() && !allowed.isEmpty()) {
            List<String> values = new ArrayList<>();
            for (JsonNode v : allowed) {
                if (values.size() >= MAX_ALLOWED_VALUES) break;
                String label = v.hasNonNull("name") ? v.path("name").asText()
                        : v.hasNonNull("value") ? v.path("value").asText() : v.path("id").asText("");
                if (!label.isBlank()) values.add(label);
            }
            field.put("allowedValues", values);
            if (allowed.size() > MAX_ALLOWED_VALUES) field.put("allowedValuesTruncated", true);
        }
        return field;
    }

    public String createIssue(String baseUrl, String accessToken, String projectKey, String issueType, String summary,
            String description, String priority, String assignee) {
        return createIssue(baseUrl, accessToken, projectKey, issueType, summary, description, priority, assignee, null);
    }

    // parentKey: fields.parent = {"key": ...}. Required for a sub-task. Also how a Story goes under
    // an Epic where the hierarchy uses parent (Jira Cloud, Data Center 10); an older Server/Data
    // Center refuses it there and wants the "Epic Link" custom field instead - listCreateMeta says
    // which of the two a type takes.
    public String createIssue(String baseUrl, String accessToken, String projectKey, String issueType, String summary,
            String description, String priority, String assignee, String parentKey) {
        return createIssue(baseUrl, accessToken, projectKey, issueType, summary, description, priority, assignee,
                parentKey, null);
    }

    /**
     * @param extraFields arbitrary {@code fields} entries - labels, components, versions, due date,
     *        {@code customfield_NNNNN} - merged LAST so a caller who names a field explicitly wins
     *        over the value the named parameters computed for it. Without this map the only way to
     *        set a label or a custom field at creation was to create the issue and then update it,
     *        which is two audit entries and a window where the issue exists half-configured.
     */
    public String createIssue(String baseUrl, String accessToken, String projectKey, String issueType, String summary,
            String description, String priority, String assignee, String parentKey,
            Map<String, Object> extraFields) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", projectKey));
        fields.put("issuetype", Map.of("name", issueType));
        fields.put("summary", summary);
        if (description != null && !description.isBlank()) fields.put("description", description);
        if (priority != null && !priority.isBlank()) fields.put("priority", Map.of("name", priority));
        if (assignee != null && !assignee.isBlank()) fields.put("assignee", Map.of("name", assignee));
        if (parentKey != null && !parentKey.isBlank()) fields.put("parent", Map.of("key", parentKey));
        if (extraFields != null) fields.putAll(extraFields);
        return createIssue(baseUrl, accessToken, fields);
    }

    public String createIssue(String baseUrl, String accessToken, Map<String, Object> fields) {
        requireConfig(baseUrl, accessToken);
        try {
            String url = baseUrl + "/rest/api/2/issue";
            Map<String, Object> body = Map.of("fields", fields);
            JsonNode response = restClient.post()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        log.error("Jira createIssue returned HTTP {}: {}", res.getStatusCode(), resBody);
                        throw new RuntimeException(
                                "Jira createIssue failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .body(JsonNode.class);
            if (response == null)
                throw new RuntimeException("Empty response from Jira createIssue");
            String key = response.path("key").asText(null);
            log.debug("Created Jira issue: {}", key);
            return key;
        } catch (Exception e) {
            log.error("Failed to create Jira issue: {}", e.getMessage());
            throw new RuntimeException("Failed to create Jira issue: " + SyncErrorAware.summarize(e), e);
        }
    }

    public void updateIssue(String baseUrl, String accessToken, String issueKey, String summary, String description,
            String priority, String assignee) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (summary != null && !summary.isBlank()) fields.put("summary", summary);
        if (description != null && !description.isBlank()) fields.put("description", description);
        if (priority != null && !priority.isBlank()) fields.put("priority", Map.of("name", priority));
        if (assignee != null && !assignee.isBlank()) fields.put("assignee", Map.of("name", assignee));
        updateIssue(baseUrl, accessToken, issueKey, fields, null);
    }

    public void updateIssue(String baseUrl, String accessToken, String issueKey,
            Map<String, Object> fields, Map<String, Object> update) {
        requireConfig(baseUrl, accessToken);
        try {
            String url = baseUrl + "/rest/api/2/issue/" + issueKey;
            Map<String, Object> body = new LinkedHashMap<>();
            if (fields != null && !fields.isEmpty())
                body.put("fields", fields);
            if (update != null && !update.isEmpty())
                body.put("update", update);
            restClient.put()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        log.error("Jira updateIssue returned HTTP {} for {}: {}",
                                res.getStatusCode(), issueKey, resBody);
                        throw new RuntimeException(
                                "Jira updateIssue failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .toBodilessEntity();
            log.debug("Updated Jira issue: {}", issueKey);
        } catch (Exception e) {
            log.error("Failed to update Jira issue {}: {}", issueKey, e.getMessage());
            throw new RuntimeException("Failed to update Jira issue: " + issueKey + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    public String addComment(String baseUrl, String accessToken, String issueKey, String commentBody) {
        requireConfig(baseUrl, accessToken);
        try {
            String url = baseUrl + "/rest/api/2/issue/" + issueKey + "/comment";
            Map<String, Object> body = Map.of("body", commentBody);
            JsonNode response = restClient.post()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        throw new RuntimeException(
                                "Jira addComment failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .body(JsonNode.class);
            log.debug("Added comment to Jira issue: {}", issueKey);
            return response != null ? response.path("id").asText(null) : null;
        } catch (Exception e) {
            log.error("Failed to add comment to Jira issue {}: {}", issueKey, e.getMessage());
            throw new RuntimeException("Failed to add comment to Jira issue: " + issueKey + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    public void updateEstimate(String baseUrl, String accessToken, String issueKey,
            String originalEstimate, String remainingEstimate) {
        Map<String, Object> edit = new LinkedHashMap<>();
        if (originalEstimate != null && !originalEstimate.isBlank()) {
            edit.put("originalEstimate", originalEstimate.strip());
        }
        if (remainingEstimate != null && !remainingEstimate.isBlank()) {
            edit.put("remainingEstimate", remainingEstimate.strip());
        }
        if (edit.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nothing to update - pass originalEstimate, remainingEstimate, or both.");
        }
        updateIssue(baseUrl, accessToken, issueKey, null,
                Map.of("timetracking", List.of(Map.of("edit", edit))));
        log.debug("Updated time estimate on Jira issue: {}", issueKey);
    }

    public String logWork(String baseUrl, String accessToken, String issueKey, String timeSpent, String comment, String started) {
        requireConfig(baseUrl, accessToken);
        try {
            String url = baseUrl + "/rest/api/2/issue/" + issueKey + "/worklog";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timeSpent", timeSpent);
            if (comment != null && !comment.isBlank()) body.put("comment", comment);
            if (started != null && !started.isBlank()) body.put("started", started);
            JsonNode response = restClient.post()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        log.error("Jira logWork returned HTTP {} for {}: {}",
                                res.getStatusCode(), issueKey, resBody);
                        throw new RuntimeException(
                                "Jira logWork failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .body(JsonNode.class);
            log.debug("Logged work on Jira issue: {}", issueKey);
            return response != null ? response.path("id").asText(null) : null;
        } catch (Exception e) {
            log.error("Failed to log work on Jira issue {}: {}", issueKey, e.getMessage());
            throw new RuntimeException("Failed to log work on Jira issue: " + issueKey + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    public void updateComment(String baseUrl, String accessToken, String issueKey, String commentId, String newBody) {
        requireConfig(baseUrl, accessToken);
        try {
            String url = baseUrl + "/rest/api/2/issue/" + issueKey + "/comment/" + commentId;
            Map<String, Object> body = Map.of("body", newBody);
            restClient.put()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        throw new RuntimeException(
                                "Jira updateComment failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .toBodilessEntity();
            log.debug("Updated comment {} on Jira issue: {}", commentId, issueKey);
        } catch (Exception e) {
            log.error("Failed to update comment {} on Jira issue {}: {}", commentId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to update Jira comment: " + commentId + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    public void deleteComment(String baseUrl, String accessToken, String issueKey, String commentId) {
        requireConfig(baseUrl, accessToken);
        try {
            String url = baseUrl + "/rest/api/2/issue/" + issueKey + "/comment/" + commentId;
            restClient.delete()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        throw new RuntimeException(
                                "Jira deleteComment failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .toBodilessEntity();
            log.debug("Deleted comment {} from Jira issue: {}", commentId, issueKey);
        } catch (Exception e) {
            log.error("Failed to delete comment {} on Jira issue {}: {}", commentId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to delete Jira comment: " + commentId + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    public List<TransitionDto> fetchTransitions(String baseUrl, String accessToken, String issueKey) {
        requireConfig(baseUrl, accessToken);
        try {
            String url = baseUrl + "/rest/api/2/issue/" + issueKey + "/transitions";
            JsonNode response = restClient.get()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        throw new RuntimeException(
                                "Jira fetchTransitions failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .body(JsonNode.class);
            if (response == null)
                return Collections.emptyList();
            List<TransitionDto> transitions = new ArrayList<>();
            JsonNode transitionsNode = response.path("transitions");
            if (transitionsNode.isArray()) {
                for (JsonNode t : transitionsNode) {
                    transitions.add(new TransitionDto(
                            t.path("id").asText(),
                            t.path("name").asText(),
                            t.path("to").path("name").asText()));
                }
            }
            return transitions;
        } catch (Exception e) {
            log.error("Failed to fetch transitions for issue {}: {}", issueKey, e.getMessage());
            throw new RuntimeException("Failed to fetch transitions for issue: " + issueKey + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    public void transitionIssue(String baseUrl, String accessToken, String issueKey, String transitionId) {
        transitionIssue(baseUrl, accessToken, issueKey, transitionId, null);
    }

    /**
     * @param fields values set AS PART OF the transition, e.g. {@code {"resolution":{"name":"Done"}}}.
     *        Resolution can only be set this way: most workflows keep it off the edit screen, so a
     *        plain field update either fails or - worse - succeeds and leaves the issue resolved
     *        while its status still says it is not.
     */
    public void transitionIssue(String baseUrl, String accessToken, String issueKey, String transitionId,
            Map<String, Object> fields) {
        requireConfig(baseUrl, accessToken);
        try {
            String url = baseUrl + "/rest/api/2/issue/" + issueKey + "/transitions";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("transition", Map.of("id", transitionId));
            if (fields != null && !fields.isEmpty()) body.put("fields", fields);
            restClient.post()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        throw new RuntimeException(
                                "Jira transitionIssue failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .toBodilessEntity();
            log.debug("Transitioned Jira issue {} with transition {}", issueKey, transitionId);
        } catch (Exception e) {
            log.error("Failed to transition issue {}: {}", issueKey, e.getMessage());
            throw new RuntimeException("Failed to transition Jira issue: " + issueKey + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    public Object transitionIssueByStatus(String baseUrl, String accessToken, String issueKey, String targetStatus) {
        return transitionIssueByStatus(baseUrl, accessToken, issueKey, targetStatus, null);
    }

    /** @param fields set as part of the transition - see {@link #transitionIssue(String, String, String, String, Map)}. */
    public Object transitionIssueByStatus(String baseUrl, String accessToken, String issueKey, String targetStatus,
            Map<String, Object> fields) {
        List<TransitionDto> transitions = fetchTransitions(baseUrl, accessToken, issueKey);

        if (targetStatus == null || targetStatus.isBlank()) {
            return transitions.stream()
                    .map(t -> Map.of("id", t.getId(), "name", t.getName(), "toStatus", t.getToStatus()))
                    .toList();
        }

        TransitionDto match = transitions.stream()
                .filter(t -> t.getName().equalsIgnoreCase(targetStatus) || t.getToStatus().equalsIgnoreCase(targetStatus))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No transition to status '" + targetStatus + "' available for issue: " + issueKey));

        transitionIssue(baseUrl, accessToken, issueKey, match.getId(), fields);
        return "Transitioned to " + match.getToStatus();
    }

    public void deleteIssue(String baseUrl, String accessToken, String issueKey, boolean deleteSubtasks) {
        requireConfig(baseUrl, accessToken);
        try {
            java.net.URI uri = UriComponentsBuilder
                    .fromUriString(baseUrl + "/rest/api/2/issue/" + issueKey)
                    .queryParam("deleteSubtasks", String.valueOf(deleteSubtasks))
                    .build().encode().toUri();
            restClient.delete()
                    .uri(uri)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        log.error("Jira deleteIssue returned HTTP {} for {}: {}",
                                res.getStatusCode(), issueKey, resBody);
                        throw new RuntimeException(
                                "Jira deleteIssue failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .toBodilessEntity();
            log.debug("Deleted Jira issue: {}", issueKey);
        } catch (Exception e) {
            log.error("Failed to delete Jira issue {}: {}", issueKey, e.getMessage());
            throw new RuntimeException("Failed to delete Jira issue: " + issueKey + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    // ---- Issue links ----------------------------------------------------------

    /**
     * Link two issues.
     * POST /rest/api/2/issueLink
     *
     * <p>DIRECTION: {@code inwardIssueKey} is the SUBJECT and takes the link type's OUTWARD wording
     * toward {@code outwardIssueKey}. For type "Blocks" (outward wording "blocks") the inward issue
     * is the one doing the blocking. Reversed arguments produce a dependency graph that reads
     * correctly and points the wrong way, which nothing downstream can detect.
     */
    public void createIssueLink(String baseUrl, String accessToken, String linkType,
            String inwardIssueKey, String outwardIssueKey, String comment) {
        requireConfig(baseUrl, accessToken);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", Map.of("name", linkType));
            body.put("inwardIssue", Map.of("key", inwardIssueKey));
            body.put("outwardIssue", Map.of("key", outwardIssueKey));
            if (comment != null && !comment.isBlank()) body.put("comment", Map.of("body", comment));
            restClient.post()
                    .uri(baseUrl + "/rest/api/2/issueLink")
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        throw new RuntimeException(
                                "Jira createIssueLink failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .toBodilessEntity();
            log.debug("Linked Jira issues: {} {} {}", inwardIssueKey, linkType, outwardIssueKey);
        } catch (Exception e) {
            log.error("Failed to link Jira issues {} -> {}: {}", inwardIssueKey, outwardIssueKey, e.getMessage());
            throw new RuntimeException("Failed to link Jira issues: " + SyncErrorAware.summarize(e), e);
        }
    }

    /**
     * Remove one issue link.
     * DELETE /rest/api/2/issueLink/{linkId}
     *
     * <p>The id is the LINK's, not either issue's. Section 5.1 renders it into the Linked Issues
     * line of an issue's Markdown for that reason - it is the only read path that exposes one.
     */
    public void deleteIssueLink(String baseUrl, String accessToken, String linkId) {
        requireConfig(baseUrl, accessToken);
        try {
            restClient.delete()
                    .uri(baseUrl + "/rest/api/2/issueLink/" + linkId)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        throw new RuntimeException(
                                "Jira deleteIssueLink failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .toBodilessEntity();
            log.debug("Deleted Jira issue link: {}", linkId);
        } catch (Exception e) {
            log.error("Failed to delete Jira issue link {}: {}", linkId, e.getMessage());
            throw new RuntimeException("Failed to delete Jira issue link: " + linkId + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    /**
     * The link types this instance defines, with both wordings.
     * GET /rest/api/2/issueLinkType
     *
     * <p>Both wordings and not just the name, because the name alone does not say which way round
     * the two issues go - see {@link #createIssueLink}.
     */
    public List<Map<String, String>> fetchIssueLinkTypes(String baseUrl, String accessToken) {
        requireConfig(baseUrl, accessToken);
        try {
            JsonNode response = restClient.get()
                    .uri(baseUrl + "/rest/api/2/issueLinkType")
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        throw new RuntimeException(
                                "Jira fetchIssueLinkTypes failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .body(JsonNode.class);
            if (response == null) return Collections.emptyList();
            List<Map<String, String>> types = new ArrayList<>();
            for (JsonNode t : response.path("issueLinkTypes")) {
                types.add(Map.of(
                        "name", t.path("name").asText(""),
                        "inward", t.path("inward").asText(""),
                        "outward", t.path("outward").asText("")));
            }
            return types;
        } catch (Exception e) {
            log.error("Failed to fetch Jira issue link types: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch Jira issue link types: " + SyncErrorAware.summarize(e), e);
        }
    }

    // ---- Versions -------------------------------------------------------------

    /**
     * The project's versions.
     * GET /rest/api/2/project/{key}/versions
     */
    public List<Map<String, Object>> fetchProjectVersions(String baseUrl, String accessToken, String projectKey) {
        requireConfig(baseUrl, accessToken);
        try {
            JsonNode response = restClient.get()
                    .uri(baseUrl + "/rest/api/2/project/" + projectKey + "/versions")
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        throw new RuntimeException(
                                "Jira fetchProjectVersions failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .body(JsonNode.class);
            if (response == null || !response.isArray()) return Collections.emptyList();
            List<Map<String, Object>> versions = new ArrayList<>();
            for (JsonNode v : response) {
                Map<String, Object> version = new LinkedHashMap<>();
                version.put("id", v.path("id").asText(""));
                version.put("name", v.path("name").asText(""));
                version.put("released", v.path("released").asBoolean(false));
                version.put("archived", v.path("archived").asBoolean(false));
                String releaseDate = v.path("releaseDate").asText("");
                if (!releaseDate.isEmpty()) version.put("releaseDate", releaseDate);
                versions.add(version);
            }
            return versions;
        } catch (Exception e) {
            log.error("Failed to fetch versions for Jira project {}: {}", projectKey, e.getMessage());
            throw new RuntimeException("Failed to fetch Jira project versions: " + projectKey + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    /**
     * Create a project version.
     * POST /rest/api/2/version
     *
     * @return the new version's id
     */
    public String createVersion(String baseUrl, String accessToken, String projectKey, String name,
            String description, String releaseDate) {
        requireConfig(baseUrl, accessToken);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("project", projectKey);
            body.put("name", name);
            if (description != null && !description.isBlank()) body.put("description", description);
            if (releaseDate != null && !releaseDate.isBlank()) body.put("releaseDate", releaseDate);
            JsonNode response = restClient.post()
                    .uri(baseUrl + "/rest/api/2/version")
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        throw new RuntimeException(
                                "Jira createVersion failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .body(JsonNode.class);
            return response != null ? response.path("id").asText(null) : null;
        } catch (Exception e) {
            log.error("Failed to create version {} in Jira project {}: {}", name, projectKey, e.getMessage());
            throw new RuntimeException("Failed to create Jira version: " + name + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    // ---- Field listing --------------------------------------------------------

    /**
     * Every field this instance defines, optionally narrowed.
     * GET /rest/api/2/field
     *
     * <p>Separate from {@link #fetchFields}, which answers "what is this id called" for the Markdown
     * renderer and swallows its errors to do it. This one answers "what id do I write" for a caller
     * about to set a custom field, and a caller who cannot be told the ids needs the failure rather
     * than an empty list.
     *
     * @param nameFilter case-insensitive substring of the name or the id; null or blank returns all
     */
    public List<Map<String, Object>> listFields(String baseUrl, String accessToken, String nameFilter) {
        requireConfig(baseUrl, accessToken);
        try {
            JsonNode response = restClient.get()
                    .uri(baseUrl + "/rest/api/2/field")
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String resBody = errorBody(res);
                        throw new RuntimeException(
                                "Jira listFields failed with HTTP " + res.getStatusCode() + ": " + resBody);
                    })
                    .body(JsonNode.class);
            if (response == null || !response.isArray()) return Collections.emptyList();
            String needle = (nameFilter == null || nameFilter.isBlank()) ? null : nameFilter.strip().toLowerCase();
            List<Map<String, Object>> fields = new ArrayList<>();
            for (JsonNode f : response) {
                String id = f.path("id").asText("");
                String name = f.path("name").asText("");
                if (needle != null
                        && !name.toLowerCase().contains(needle)
                        && !id.toLowerCase().contains(needle)) {
                    continue;
                }
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("id", id);
                field.put("name", name);
                field.put("custom", f.path("custom").asBoolean(false));
                field.put("type", f.path("schema").path("type").asText(""));
                fields.add(field);
            }
            return fields;
        } catch (Exception e) {
            log.error("Failed to list Jira fields: {}", e.getMessage());
            throw new RuntimeException("Failed to list Jira fields: " + SyncErrorAware.summarize(e), e);
        }
    }

    /** The issue as markdown - {@link JiraIssueMarkdown#issueToMarkdown}. */
    public String issueToMarkdown(JiraIssueDto issue) {
        return JiraIssueMarkdown.issueToMarkdown(issue);
    }

    /** Atlassian Document Format as markdown - {@link JiraIssueMarkdown#adfToMarkdown}. */
    public String adfToMarkdown(JsonNode adfNode) {
        return JiraIssueMarkdown.adfToMarkdown(adfNode);
    }

    // =========================================================================
    //  DTOs
    // =========================================================================

    public static class JiraProjectDto {
        private String key, name, self;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSelf() { return self; }
        public void setSelf(String self) { this.self = self; }
    }

    public static class JiraIssueDto {
        private String key, summary, issueType, self, updated;
        private String status, priority, assignee, reporter, created, dueDate;
        private List<String> labels, components, fixVersions, affectsVersions;
        private String environment;
        private String parentKey, parentSummary;
        private List<String> subtasks, issueLinks, attachments;
        private String originalEstimate, remainingEstimate, timeSpent;
        private int votes, watches;
        private Map<String, String> customFields;
        private List<CommentEntry> commentList;
        private String comments;
        private String description;
        private List<ChangelogEntry> changelog;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getSummary() { return summary; }
        public void setSummary(String s) { this.summary = s; }
        public String getIssueType() { return issueType; }
        public void setIssueType(String t) { this.issueType = t; }
        public String getSelf() { return self; }
        public void setSelf(String self) { this.self = self; }
        public String getUpdated() { return updated; }
        public void setUpdated(String u) { this.updated = u; }
        public String getStatus() { return status; }
        public void setStatus(String s) { this.status = s; }
        public String getPriority() { return priority; }
        public void setPriority(String p) { this.priority = p; }
        public String getAssignee() { return assignee; }
        public void setAssignee(String a) { this.assignee = a; }
        public String getReporter() { return reporter; }
        public void setReporter(String r) { this.reporter = r; }
        public String getCreated() { return created; }
        public void setCreated(String c) { this.created = c; }
        public String getDueDate() { return dueDate; }
        public void setDueDate(String d) { this.dueDate = d; }
        public List<String> getLabels() { return labels; }
        public void setLabels(List<String> l) { this.labels = l; }
        public List<String> getComponents() { return components; }
        public void setComponents(List<String> c) { this.components = c; }
        public List<String> getFixVersions() { return fixVersions; }
        public void setFixVersions(List<String> v) { this.fixVersions = v; }
        public List<String> getAffectsVersions() { return affectsVersions; }
        public void setAffectsVersions(List<String> v) { this.affectsVersions = v; }
        public String getEnvironment() { return environment; }
        public void setEnvironment(String e) { this.environment = e; }
        public String getParentKey() { return parentKey; }
        public void setParentKey(String k) { this.parentKey = k; }
        public String getParentSummary() { return parentSummary; }
        public void setParentSummary(String s) { this.parentSummary = s; }
        public List<String> getSubtasks() { return subtasks; }
        public void setSubtasks(List<String> s) { this.subtasks = s; }
        public List<String> getIssueLinks() { return issueLinks; }
        public void setIssueLinks(List<String> l) { this.issueLinks = l; }
        public List<String> getAttachments() { return attachments; }
        public void setAttachments(List<String> a) { this.attachments = a; }
        public String getOriginalEstimate() { return originalEstimate; }
        public void setOriginalEstimate(String e) { this.originalEstimate = e; }
        public String getRemainingEstimate() { return remainingEstimate; }
        public void setRemainingEstimate(String e) { this.remainingEstimate = e; }
        public String getTimeSpent() { return timeSpent; }
        public void setTimeSpent(String t) { this.timeSpent = t; }
        public int getVotes() { return votes; }
        public void setVotes(int v) { this.votes = v; }
        public int getWatches() { return watches; }
        public void setWatches(int w) { this.watches = w; }
        public Map<String, String> getCustomFields() { return customFields; }
        public void setCustomFields(Map<String, String> m) { this.customFields = m; }
        public List<CommentEntry> getCommentList() { return commentList; }
        public void setCommentList(List<CommentEntry> l) { this.commentList = l; }
        public String getComments() { return comments; }
        public void setComments(String c) { this.comments = c; }
        public String getDescription() { return description; }
        public void setDescription(String d) { this.description = d; }
        public List<ChangelogEntry> getChangelog() { return changelog; }
        public void setChangelog(List<ChangelogEntry> c) { this.changelog = c; }

        public static class CommentEntry {
            private String author, created, body;

            public String getAuthor() { return author; }
            public void setAuthor(String a) { this.author = a; }
            public String getCreated() { return created; }
            public void setCreated(String c) { this.created = c; }
            public String getBody() { return body; }
            public void setBody(String b) { this.body = b; }
        }

        public static class ChangelogEntry {
            private String created, author, field, fromString, toString;

            public String getCreated() { return created; }
            public void setCreated(String c) { this.created = c; }
            public String getAuthor() { return author; }
            public void setAuthor(String a) { this.author = a; }
            public String getField() { return field; }
            public void setField(String f) { this.field = f; }
            public String getFromString() { return fromString; }
            public void setFromString(String s) { this.fromString = s; }
            public String getToString() { return toString; }
            public void setToString(String s) { this.toString = s; }
        }
    }

    public static class TransitionDto {
        private final String id, name, toStatus;

        public TransitionDto(String id, String name, String toStatus) {
            this.id = id;
            this.name = name;
            this.toStatus = toStatus;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getToStatus() { return toStatus; }
    }

    /**
     * Escape hatch: any Jira REST endpoint the configured credential is allowed to reach.
     *
     * Exists because the hand-written methods above will never be a complete API - see
     * {@link ai.proxyma.core.connector.RestPassthrough} for why that is the design rather than a
     * gap. Everything about the request except the verb, path and body is fixed here: the host and
     * the credential are this instance's, and the path is validated so they stay that way.
     *
     * @param path path relative to the server root, e.g. /rest/api/2/myself
     * @param body JSON request text, or null for verbs that take none
     */
    public Map<String, Object> callApi(String baseUrl, String accessToken, String method,
                                       String path, String body) {
        requireConfig(baseUrl, accessToken);
        return RestPassthrough.call(restClient, h -> addAuthHeader(h, accessToken),
                baseUrl, method, path, body);
    }
}
