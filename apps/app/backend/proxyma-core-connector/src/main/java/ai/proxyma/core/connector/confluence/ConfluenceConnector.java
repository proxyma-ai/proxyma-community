package ai.proxyma.core.connector.confluence;

import ai.proxyma.core.connector.RestPassthrough;
import ai.proxyma.core.connector.SyncErrorAware;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Confluence's REST API as the agent's tools reach it: spaces, CQL search, a page with its
 * comments, and the writes (pages, comments, labels, moves, attachments), each with the caller's own
 * server and credential passed in explicitly - nothing about one call is kept for the next.
 *
 * <p>INDEXING IS NOT HERE. A Confluence source is synced by its native session (proxyma-connectors,
 * ai.proxyma.connectors.atlassian), which renders through the same {@link ConfluencePageMarkdown}.
 *
 * <p>Auth header: Bearer token by default; Basic auth when the token contains ':'.
 *
 * The baseUrl passed in includes the full path suffix
 * (e.g. "https://confluence.example.com/confluence"). It is NEVER stripped.
 * All API calls append sub-paths directly onto it.
 *
 * All URL construction uses UriComponentsBuilder - never raw string concatenation + URLEncoder.
 */
@Service
public class ConfluenceConnector {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceConnector.class);

    // Confluence's own ceiling for this endpoint; a page with more comments than this keeps the
    // first COMMENT_PAGE_SIZE * MAX_COMMENT_PAGES and logs that it stopped.
    private static final int COMMENT_PAGE_SIZE = 50;
    private static final int MAX_COMMENT_PAGES = 40;

    private final RestClient restClient;

    public ConfluenceConnector(
            @Qualifier("externalRestClientBuilder") RestClient.Builder externalRestClientBuilder) {
        // A clone: the builder is shared, and the guard is this client's alone - see DotSegmentGuard.
        this.restClient = externalRestClientBuilder.clone().requestInterceptor(new ai.proxyma.core.connector.DotSegmentGuard()).build();
    }

    // --- Confluence REST API calls -----------------------------------------------

    public List<ConfluenceSpaceDto> listAllSpaces(String baseUrl, String accessToken) {
        requireConfig(baseUrl, accessToken);
        List<ConfluenceSpaceDto> all = new ArrayList<>();
        int start = 0;
        int limit = 100;
        boolean hasMore = true;

        while (hasMore) {
            try {
                URI uri = UriComponentsBuilder
                        .fromUriString(baseUrl + "/rest/api/space")
                        .queryParam("start", start)
                        .queryParam("limit", limit)
                        .build().encode().toUri();

                JsonNode response = restClient.get()
                        .uri(uri)
                        .headers(h -> addAuthHeader(h, accessToken))
                        .retrieve()
                        .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                            String body = "";
                            try { body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8); } catch (Exception ignored) {}
                            log.error("Confluence API returned HTTP {} for /space: {}", res.getStatusCode(),
                                    body.substring(0, Math.min(500, body.length())));
                            throw new RuntimeException("Confluence API returned HTTP " + res.getStatusCode());
                        })
                        .body(JsonNode.class);

                if (response == null) break;

                JsonNode results = response.get("results");
                if (results == null || !results.isArray() || results.isEmpty()) break;

                for (JsonNode node : results) {
                    ConfluenceSpaceDto space = new ConfluenceSpaceDto();
                    space.setKey(node.get("key").asText());
                    space.setName(node.get("name").asText());
                    if (node.has("_links") && node.get("_links").has("webui")) {
                        space.setWebUrl(ConfluencePageMarkdown.joinUrl(baseUrl, node.get("_links").get("webui").asText()));
                    }
                    all.add(space);
                }

                hasMore = response.has("_links") && response.get("_links").has("next");
                // Advance by what came back, not by the limit: Confluence returns fewer than the
                // limit whenever it filters a space the token cannot see, and stepping by the limit
                // then skips exactly that many real spaces on the next request.
                start += results.size();
            } catch (Exception e) {
                log.error("Failed to list Confluence spaces at start={}: {}", start, e.getMessage());
                throw new RuntimeException("Failed to list Confluence spaces", e);
            }
        }
        log.info("[Confluence] Listed {} spaces from {}", all.size(), baseUrl);
        return all;
    }

    public List<ConfluencePageDto> searchPages(String baseUrl, String accessToken, String cql, int maxResults) {
        requireConfig(baseUrl, accessToken);
        try {
            // The CQL as a URI VARIABLE, strictly encoded: put in as a literal query value, a '+'
            // ("C++", "a+b") went out raw and the server read it as a space.
            URI uri = UriComponentsBuilder
                    .fromUriString(baseUrl + "/rest/api/content/search")
                    .queryParam("cql", "{cql}")
                    .queryParam("limit", Math.min(maxResults, 100))
                    .queryParam("expand", "body.view,version")
                    .encode().buildAndExpand(java.util.Map.of("cql", cql)).toUri();

            JsonNode response = restClient.get()
                    .uri(uri)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        String body = "";
                        try {
                            body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        } catch (Exception ignored) {
                        }
                        log.error("Confluence CQL search returned HTTP {}: {}",
                                res.getStatusCode(), body.substring(0, Math.min(500, body.length())));
                        throw new RuntimeException(
                                "Confluence CQL search failed with HTTP " + res.getStatusCode() + ": " + body);
                    })
                    .body(JsonNode.class);

            if (response == null) return new ArrayList<>();

            List<ConfluencePageDto> results = new ArrayList<>();
            JsonNode resultsNode = response.path("results");
            if (resultsNode.isArray()) {
                for (JsonNode node : resultsNode) {
                    ConfluencePageDto page = ConfluencePageMarkdown.parsePage(node, baseUrl);
                    String bodyHtml = "";
                    if (node.has("body") && node.get("body").has("view")) {
                        JsonNode view = node.get("body").get("view");
                        if (view.has("value")) bodyHtml = view.get("value").asText();
                    }
                    String excerpt = ConfluencePageMarkdown.htmlToMarkdown(bodyHtml);
                    if (excerpt.length() > 500) excerpt = excerpt.substring(0, 500) + "...";
                    page.setContent(excerpt);
                    results.add(page);
                }
            }
            log.debug("Confluence CQL search returned {} results for cql: {}", results.size(), cql);
            return results;
        } catch (Exception e) {
            log.error("Failed to search Confluence pages: {}", e.getMessage());
            throw new RuntimeException("Failed to search Confluence pages: " + SyncErrorAware.summarize(e), e);
        }
    }

    public List<Map<String, Object>> searchPagesSummary(String baseUrl, String accessToken, String cql, int maxResults) {
        return searchPages(baseUrl, accessToken, cql, maxResults).stream()
                .map(dto -> Map.<String, Object>of(
                        "id", dto.getId(),
                        "title", dto.getTitle() != null ? dto.getTitle() : "",
                        "url", dto.getWebUrl() != null ? dto.getWebUrl() : "",
                        "excerpt", dto.getContent() != null ? dto.getContent() : ""))
                .toList();
    }

    public ConfluencePageDto fetchPageDetails(String baseUrl, String accessToken, String pageId) {
        requireConfig(baseUrl, accessToken);
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(baseUrl + "/rest/api/content/" + pageId)
                    .queryParam("expand", "body.view,version,history,ancestors,metadata.labels,space")
                    .build()
                    .encode()
                    .toUri();

            log.debug("Fetching full page details: {}", pageId);
            JsonNode response = restClient.get()
                    .uri(uri)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) throw new RuntimeException("Empty response from Confluence API");

            List<CommentDto> comments = fetchPageComments(baseUrl, accessToken, pageId);
            return ConfluencePageMarkdown.parsePageWithDetails(response, comments, baseUrl);

        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Page {} not found in Confluence (404) - likely deleted", pageId);
            return null;
        } catch (Exception e) {
            log.error("Failed to fetch page details: {}", pageId, e);
            throw new RuntimeException("Failed to fetch page details: " + pageId + " - " + SyncErrorAware.summarize(e), e);
        }
    }

    /**
     * Fetch all comments for a page. depth=all captures nested replies.
     * Returns an empty list on any error - comments are best-effort.
     *
     * <p>PAGED. This asked for the first 50 and stopped, so a discussion of any size was indexed
     * down to its opening exchange and the answer everybody was looking for - which on a busy page
     * is at the bottom - was simply not in the index. Pages while _links.next is present, the same
     * rule the page listing uses.
     */
    private List<CommentDto> fetchPageComments(String baseUrl, String accessToken, String pageId) {
        List<CommentDto> comments = new ArrayList<>();
        int start = 0;
        try {
            for (int page = 0; page < MAX_COMMENT_PAGES; page++) {
                URI uri = UriComponentsBuilder
                        .fromUriString(baseUrl + "/rest/api/content/" + pageId + "/child/comment")
                        .queryParam("expand", "body.view,version")
                        .queryParam("depth", "all")
                        .queryParam("start", start)
                        .queryParam("limit", COMMENT_PAGE_SIZE)
                        .build()
                        .encode()
                        .toUri();

                JsonNode response = restClient.get()
                        .uri(uri)
                        .headers(h -> addAuthHeader(h, accessToken))
                        .retrieve()
                        .body(JsonNode.class);

                if (response == null) return comments;

                JsonNode results = response.get("results");
                if (results == null || !results.isArray()) return comments;

                for (JsonNode commentNode : results) {
                    CommentDto dto = ConfluencePageMarkdown.parseComment(commentNode);
                    if (dto != null) comments.add(dto);
                }

                // Advance by what came back, never by the limit: a comment the server filtered out
                // of this batch would otherwise take a real one's place in the next one.
                start += results.size();
                JsonNode links = response.get("_links");
                boolean hasMore = links != null && links.has("next");
                if (!hasMore || results.isEmpty()) return comments;
            }
            log.warn("Page {} has more than {} comments - indexing the first {}", pageId,
                    MAX_COMMENT_PAGES * COMMENT_PAGE_SIZE, comments.size());
            return comments;

        } catch (Exception e) {
            log.warn("Failed to fetch comments for page {}: {}", pageId, e.getMessage());
            return comments;
        }
    }

    // --- Write operations ----------------------------------------------------------

    public String createPage(String baseUrl, String accessToken, String spaceKey, String title, String bodyHtml, String parentPageId) {
        requireConfig(baseUrl, accessToken);
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(baseUrl + "/rest/api/content")
                    .build().encode().toUri();

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("type", "page");
            body.put("title", title);
            body.put("space", Map.of("key", spaceKey));
            body.put("body", Map.of("storage", Map.of("value", bodyHtml, "representation", "storage")));
            if (parentPageId != null && !parentPageId.isBlank()) {
                body.put("ancestors", List.of(Map.of("id", parentPageId)));
            }

            JsonNode response = restClient.post()
                    .uri(uri)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Confluence createPage failed with HTTP " + res.getStatusCode()
                                + bodySnippet(res));
                    })
                    .body(JsonNode.class);

            if (response == null) throw new RuntimeException("Empty response from Confluence createPage");
            return response.path("id").asText(null);
        } catch (Exception e) {
            log.error("Failed to create Confluence page in space {}: {}", spaceKey, e.getMessage());
            throw new RuntimeException("Failed to create Confluence page in space: " + spaceKey + " - " + writeFailureDetail(e), e);
        }
    }

    public void updatePage(String baseUrl, String accessToken, String pageId, String title, String bodyHtml) {
        requireConfig(baseUrl, accessToken);
        try {
            int currentVersion = fetchPageVersion(baseUrl, accessToken, pageId);

            URI uri = UriComponentsBuilder
                    .fromUriString(baseUrl + "/rest/api/content/" + pageId)
                    .build().encode().toUri();

            Map<String, Object> body = Map.of(
                    "type", "page",
                    "title", title,
                    "body", Map.of("storage", Map.of("value", bodyHtml, "representation", "storage")),
                    "version", Map.of("number", currentVersion + 1));

            restClient.put()
                    .uri(uri)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Confluence updatePage failed with HTTP " + res.getStatusCode()
                                + bodySnippet(res));
                    })
                    .toBodilessEntity();
            log.debug("Updated Confluence page: {}", pageId);
        } catch (Exception e) {
            log.error("Failed to update Confluence page {}: {}", pageId, e.getMessage());
            throw new RuntimeException("Failed to update Confluence page: " + pageId + " - " + writeFailureDetail(e), e);
        }
    }

    public String addComment(String baseUrl, String accessToken, String pageId, String bodyHtml) {
        requireConfig(baseUrl, accessToken);
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(baseUrl + "/rest/api/content")
                    .build().encode().toUri();

            Map<String, Object> body = Map.of(
                    "type", "comment",
                    "container", Map.of("id", pageId, "type", "page"),
                    "body", Map.of("storage", Map.of("value", bodyHtml, "representation", "storage")));

            JsonNode response = restClient.post()
                    .uri(uri)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Confluence addComment failed with HTTP " + res.getStatusCode()
                                + bodySnippet(res));
                    })
                    .body(JsonNode.class);

            if (response == null) throw new RuntimeException("Empty response from Confluence addComment");
            return response.path("id").asText(null);
        } catch (Exception e) {
            log.error("Failed to add comment to Confluence page {}: {}", pageId, e.getMessage());
            throw new RuntimeException("Failed to add comment to Confluence page: " + pageId + " - " + writeFailureDetail(e), e);
        }
    }

    public void deletePage(String baseUrl, String accessToken, String pageId) {
        requireConfig(baseUrl, accessToken);
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(baseUrl + "/rest/api/content/" + pageId)
                    .build().encode().toUri();

            restClient.delete()
                    .uri(uri)
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Confluence deletePage failed with HTTP " + res.getStatusCode()
                                + bodySnippet(res));
                    })
                    .toBodilessEntity();
            log.debug("Deleted Confluence page: {}", pageId);
        } catch (Exception e) {
            log.error("Failed to delete Confluence page {}: {}", pageId, e.getMessage());
            throw new RuntimeException("Failed to delete Confluence page: " + pageId + " - " + writeFailureDetail(e), e);
        }
    }

    /**
     * Edit a comment's text. A comment is content like a page, so it takes the next version number
     * the same way. PUT /rest/api/content/{id}
     */
    public void updateComment(String baseUrl, String accessToken, String commentId, String bodyHtml) {
        requireConfig(baseUrl, accessToken);
        try {
            int currentVersion = fetchPageVersion(baseUrl, accessToken, commentId);
            Map<String, Object> body = Map.of(
                    "type", "comment",
                    "body", Map.of("storage", Map.of("value", bodyHtml, "representation", "storage")),
                    "version", Map.of("number", currentVersion + 1));
            restClient.put()
                    .uri(UriComponentsBuilder.fromUriString(baseUrl + "/rest/api/content/{id}")
                            .buildAndExpand(commentId).encode().toUri())
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Confluence updateComment failed with HTTP " + res.getStatusCode()
                                + bodySnippet(res));
                    })
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to update Confluence comment {}: {}", commentId, e.getMessage());
            throw new RuntimeException("Failed to update Confluence comment: " + commentId + " - " + writeFailureDetail(e), e);
        }
    }

    /** DELETE /rest/api/content/{id} - the same call as a page, named for what the caller meant. */
    public void deleteComment(String baseUrl, String accessToken, String commentId) {
        deletePage(baseUrl, accessToken, commentId);
    }

    /**
     * Move a page under another parent in the same space. Confluence has no move call on Server/DC's
     * REST API, so this is an update that keeps the title and body and changes the ancestors.
     */
    public void movePage(String baseUrl, String accessToken, String pageId, String parentPageId) {
        requireConfig(baseUrl, accessToken);
        try {
            JsonNode current = restClient.get()
                    .uri(UriComponentsBuilder.fromUriString(baseUrl + "/rest/api/content/{id}")
                            .queryParam("expand", "version,body.storage")
                            .buildAndExpand(pageId).encode().toUri())
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Confluence page lookup failed with HTTP " + res.getStatusCode()
                                + bodySnippet(res));
                    })
                    .body(JsonNode.class);
            if (current == null) throw new RuntimeException("Empty response reading Confluence page " + pageId);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("type", current.path("type").asText("page"));
            body.put("title", current.path("title").asText());
            body.put("body", Map.of("storage", Map.of(
                    "value", current.path("body").path("storage").path("value").asText(""),
                    "representation", "storage")));
            body.put("version", Map.of("number", current.path("version").path("number").asInt(1) + 1));
            body.put("ancestors", List.of(Map.of("id", parentPageId)));
            restClient.put()
                    .uri(UriComponentsBuilder.fromUriString(baseUrl + "/rest/api/content/{id}")
                            .buildAndExpand(pageId).encode().toUri())
                    .headers(h -> addAuthHeader(h, accessToken))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Confluence movePage failed with HTTP " + res.getStatusCode()
                                + bodySnippet(res));
                    })
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to move Confluence page {}: {}", pageId, e.getMessage());
            throw new RuntimeException("Failed to move Confluence page: " + pageId + " - " + writeFailureDetail(e), e);
        }
    }

    /**
     * Add and remove a page's labels, then read back the ones it has. Labels are added in one call
     * and removed one at a time, which is how the API offers them.
     */
    public List<String> updateLabels(String baseUrl, String accessToken, String pageId,
                                     List<String> add, List<String> remove) {
        requireConfig(baseUrl, accessToken);
        try {
            if (add != null && !add.isEmpty()) {
                List<Map<String, String>> labels = add.stream()
                        .filter(l -> l != null && !l.isBlank())
                        .map(l -> Map.of("prefix", "global", "name", l.strip()))
                        .toList();
                if (!labels.isEmpty()) {
                    restClient.post()
                            .uri(UriComponentsBuilder.fromUriString(baseUrl + "/rest/api/content/{id}/label")
                                    .buildAndExpand(pageId).encode().toUri())
                            .headers(h -> addAuthHeader(h, accessToken))
                            .body(labels)
                            .retrieve()
                            .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                                throw new RuntimeException("Confluence addLabels failed with HTTP " + res.getStatusCode()
                                        + bodySnippet(res));
                            })
                            .toBodilessEntity();
                }
            }
            if (remove != null) {
                for (String label : remove) {
                    if (label == null || label.isBlank()) continue;
                    restClient.delete()
                            .uri(UriComponentsBuilder.fromUriString(baseUrl + "/rest/api/content/{id}/label")
                                    .queryParam("name", label.strip())
                                    .buildAndExpand(pageId).encode().toUri())
                            .headers(h -> addAuthHeader(h, accessToken))
                            .retrieve()
                            .onStatus(status -> !status.is2xxSuccessful() && status.value() != 404, (req, res) -> {
                                throw new RuntimeException("Confluence removeLabel failed with HTTP " + res.getStatusCode()
                                        + bodySnippet(res));
                            })
                            .toBodilessEntity();
                }
            }
            JsonNode current = restClient.get()
                    .uri(UriComponentsBuilder.fromUriString(baseUrl + "/rest/api/content/{id}/label")
                            .queryParam("limit", 200)
                            .buildAndExpand(pageId).encode().toUri())
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Confluence labels lookup failed with HTTP " + res.getStatusCode()
                                + bodySnippet(res));
                    })
                    .body(JsonNode.class);
            List<String> names = new ArrayList<>();
            if (current != null) current.path("results").forEach(l -> names.add(l.path("name").asText()));
            return names;
        } catch (Exception e) {
            log.error("Failed to update labels on Confluence page {}: {}", pageId, e.getMessage());
            throw new RuntimeException("Failed to update labels on Confluence page: " + pageId + " - " + writeFailureDetail(e), e);
        }
    }

    /**
     * Attach a file to a page, or add a new version of the attachment of the same name.
     * Never PUT /child/attachment: only Confluence Cloud has it, and Server and Data Center answer
     * 405 - which is what every attach on a self-hosted Confluence got until 2026-09-30. Instead,
     * look the name up first and POST to whichever of the two endpoints both products share:
     * POST /rest/api/content/{id}/child/attachment for a new name (it refuses a name the page
     * already has), POST /rest/api/content/{id}/child/attachment/{attachmentId}/data for a new version.
     */
    public Map<String, Object> attachFile(String baseUrl, String accessToken, String pageId, String fileName,
                                          byte[] content, String comment) {
        requireConfig(baseUrl, accessToken);
        try {
            org.springframework.util.LinkedMultiValueMap<String, Object> parts = new org.springframework.util.LinkedMultiValueMap<>();
            parts.add("file", namedResource(fileName, content));
            if (comment != null && !comment.isBlank()) parts.add("comment", comment);
            JsonNode existing = restClient.get()
                    .uri(UriComponentsBuilder.fromUriString(baseUrl + "/rest/api/content/{id}/child/attachment")
                            .queryParam("filename", fileName)
                            .buildAndExpand(pageId).encode().toUri())
                    .headers(h -> addAuthHeader(h, accessToken))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Confluence attachment lookup failed with HTTP " + res.getStatusCode()
                                + bodySnippet(res));
                    })
                    .body(JsonNode.class);
            String existingId = existing == null ? "" : existing.path("results").path(0).path("id").asText("");
            String path = existingId.isBlank()
                    ? "/rest/api/content/{id}/child/attachment"
                    : "/rest/api/content/{id}/child/attachment/{attachmentId}/data";
            JsonNode response = restClient.post()
                    .uri(UriComponentsBuilder.fromUriString(baseUrl + path)
                            .buildAndExpand(pageId, existingId).encode().toUri())
                    .headers(h -> {
                        addAuthHeader(h, accessToken);
                        h.set("X-Atlassian-Token", "no-check");
                    })
                    .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new RuntimeException("Confluence attachFile failed with HTTP " + res.getStatusCode()
                                + bodySnippet(res));
                    })
                    .body(JsonNode.class);
            JsonNode a = response == null ? null
                    : response.has("results") ? response.path("results").path(0) : response;
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("id", a == null ? "" : a.path("id").asText(""));
            out.put("fileName", a == null ? fileName : a.path("title").asText(fileName));
            out.put("sizeBytes", content.length);
            String download = a == null ? "" : a.path("_links").path("download").asText("");
            if (!download.isBlank()) out.put("url", baseUrl + download);
            return out;
        } catch (Exception e) {
            log.error("Failed to attach {} to Confluence page {}: {}", fileName, pageId, e.getMessage());
            throw new RuntimeException("Failed to attach " + fileName + " to Confluence page: " + pageId + " - "
                    + writeFailureDetail(e), e);
        }
    }

    private static org.springframework.core.io.ByteArrayResource namedResource(String fileName, byte[] content) {
        return new org.springframework.core.io.ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private int fetchPageVersion(String baseUrl, String accessToken, String pageId) {
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/rest/api/content/" + pageId)
                .queryParam("expand", "version")
                .build().encode().toUri();

        JsonNode response = restClient.get()
                .uri(uri)
                .headers(h -> addAuthHeader(h, accessToken))
                .retrieve()
                .body(JsonNode.class);

        if (response == null) throw new RuntimeException("Page not found: " + pageId);
        return response.path("version").path("number").asInt(1);
    }

    // --- Authentication ----------------------------------------------------------

    /**
     * Add Authorization header. If the token contains ':' it is treated as user:password
     * and sent as Basic auth (base64-encoded). Otherwise it is sent as a Bearer token.
     *
     * Takes the token as an explicit parameter: this bean is shared by every user's tool calls and
     * holds nothing of any one of them.
     */
    private void addAuthHeader(HttpHeaders headers, String accessToken) {
        String token = accessToken != null ? accessToken : "";
        if (token.contains(":")) {
            String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        } else {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
    }

    /**
     * Up to 500 characters of a failed write's response body, prefixed with ": ", or "" when there
     * is nothing to read. A write failure used to carry the status alone, so the one thing the
     * agent needed - Confluence's own explanation, which for the 409 every concurrent edit produces
     * names the version it expected - was read and thrown away.
     */
    private static String bodySnippet(ClientHttpResponse res) {
        try {
            String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (body.isEmpty()) return "";
            return ": " + body.substring(0, Math.min(500, body.length()));
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * The detail for a failed WRITE. Deliberately not {@link SyncErrorAware#summarize}, which caps
     * at 200 characters and would cut the body snippet the caller needs; summarize() exists to keep
     * a response body off a source's status card, and a tool result is not a status card.
     */
    private static String writeFailureDetail(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private void requireConfig(String baseUrl, String accessToken) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalStateException("The Confluence connection has no server URL. Set it in Settings > Connections.");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IllegalStateException(
                    "Confluence access token is not configured. Please set your personal access token in Settings.");
        }
    }

    /** Rendered HTML as markdown - {@link ConfluencePageMarkdown#htmlToMarkdown}. */
    String htmlToMarkdown(String html) {
        return ConfluencePageMarkdown.htmlToMarkdown(html);
    }

    // --- Inner DTOs --------------------------------------------------------------

    public static class ConfluenceSpaceDto {
        private String key;
        private String name;
        private String webUrl;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getWebUrl() { return webUrl; }
        public void setWebUrl(String webUrl) { this.webUrl = webUrl; }
    }

    public static class ConfluencePageDto {
        private String id;
        private String title;
        private String webUrl;
        private String lastModified;
        private String content;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getWebUrl() { return webUrl; }
        public void setWebUrl(String webUrl) { this.webUrl = webUrl; }
        public String getLastModified() { return lastModified; }
        public void setLastModified(String lastModified) { this.lastModified = lastModified; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class CommentDto {
        private String author;
        private String date;
        private String content;

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    /**
     * Escape hatch: any Confluence REST endpoint the configured credential is allowed to reach.
     *
     * Exists because the hand-written methods above will never be a complete API - see
     * {@link ai.proxyma.core.connector.RestPassthrough} for why that is the design rather than a
     * gap. Everything about the request except the verb, path and body is fixed here: the host and
     * the credential are this instance's, and the path is validated so they stay that way.
     *
     * @param path path relative to the server root, e.g. /rest/api/space?limit=1
     * @param body JSON request text, or null for verbs that take none
     */
    public Map<String, Object> callApi(String baseUrl, String accessToken, String method,
                                       String path, String body) {
        requireConfig(baseUrl, accessToken);
        return RestPassthrough.call(restClient, h -> addAuthHeader(h, accessToken),
                baseUrl, method, path, body);
    }
}
