package ai.proxyma.core.connector.confluence;

import ai.proxyma.core.connector.confluence.ConfluenceConnector.CommentDto;
import ai.proxyma.core.connector.confluence.ConfluenceConnector.ConfluencePageDto;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A Confluence page as the markdown document that is indexed and that the agent's
 * getConfluencePage reads - ONE renderer for both, since SourceFactsReader parses its "Page
 * Details" and "Location" back for the knowledge graph and two copies would drift. Stateless.
 *
 * <p>{@link #parsePageWithDetails} reads a page as Server and Data Center's REST API returns it
 * ({@code /rest/api/content/{id}?expand=body.view,...}); Confluence Cloud's v2 API states the same
 * facts in other places, and its session hands them to {@link #pageToMarkdown} directly.
 */
public final class ConfluencePageMarkdown {

    private ConfluencePageMarkdown() {}

    /**
     * One comment as {@code /rest/api/content/{id}/child/comment?expand=body.view,version} returns it,
     * or null for one with no text worth indexing.
     */
    public static CommentDto parseComment(JsonNode commentNode) {
        CommentDto dto = new CommentDto();
        JsonNode version = commentNode.path("version");
        if (version.has("by")) {
            JsonNode by = version.get("by");
            dto.setAuthor(by.has("displayName") ? by.get("displayName").asText() : "Unknown");
        }
        if (version.has("when")) dto.setDate(formatDate(version.get("when").asText()));
        JsonNode view = commentNode.path("body").path("view");
        if (view.has("value")) dto.setContent(htmlToMarkdown(view.get("value").asText()));
        return dto.getContent() == null || dto.getContent().isBlank() ? null : dto;
    }

    // --- JSON parsing ------------------------------------------------------------

    public static ConfluencePageDto parsePage(JsonNode pageNode, String confluenceBaseUrl) {
        ConfluencePageDto page = new ConfluencePageDto();
        page.setId(pageNode.get("id").asText());
        page.setTitle(pageNode.get("title").asText());

        if (pageNode.has("_links") && pageNode.get("_links").has("webui")) {
            page.setWebUrl(joinUrl(confluenceBaseUrl, pageNode.get("_links").get("webui").asText()));
        } else {
            page.setWebUrl(joinUrl(confluenceBaseUrl, "/pages/viewpage.action?pageId=" + page.getId()));
        }

        if (pageNode.has("version") && pageNode.get("version") != null) {
            JsonNode version = pageNode.get("version");
            if (version.has("when")) {
                page.setLastModified(version.get("when").asText());
            }
        }

        return page;
    }

    public static ConfluencePageDto parsePageWithDetails(JsonNode pageNode, List<CommentDto> comments,
                                                    String confluenceBaseUrl) {
        ConfluencePageDto page = parsePage(pageNode, confluenceBaseUrl);

        String spaceKey = null, spaceName = null;
        if (pageNode.has("space")) {
            JsonNode space = pageNode.get("space");
            spaceKey = space.has("key") ? space.get("key").asText() : null;
            spaceName = space.has("name") ? space.get("name").asText() : null;
        }

        String createdBy = null, createdDate = null;
        if (pageNode.has("history")) {
            JsonNode history = pageNode.get("history");
            if (history.has("createdBy")) {
                JsonNode cb = history.get("createdBy");
                createdBy = cb.has("displayName") ? cb.get("displayName").asText() : null;
            }
            if (history.has("createdDate")) {
                createdDate = formatDate(history.get("createdDate").asText());
            }
        }

        String lastUpdatedBy = null, lastUpdatedDate = null;
        if (pageNode.has("version")) {
            JsonNode version = pageNode.get("version");
            if (version.has("by")) {
                JsonNode by = version.get("by");
                lastUpdatedBy = by.has("displayName") ? by.get("displayName").asText() : null;
            }
            if (version.has("when")) {
                lastUpdatedDate = formatDate(version.get("when").asText());
            }
        }

        List<String> labels = new ArrayList<>();
        if (pageNode.has("metadata") && pageNode.get("metadata").has("labels")) {
            JsonNode labelsNode = pageNode.get("metadata").get("labels");
            if (labelsNode.has("results") && labelsNode.get("results").isArray()) {
                for (JsonNode label : labelsNode.get("results")) {
                    if (label.has("name")) labels.add(label.get("name").asText());
                }
            }
        }

        List<String> ancestorPath = new ArrayList<>();
        if (pageNode.has("ancestors") && pageNode.get("ancestors").isArray()) {
            for (JsonNode ancestor : pageNode.get("ancestors")) {
                if (ancestor.has("title")) ancestorPath.add(ancestor.get("title").asText());
            }
        }

        String bodyHtml = "";
        if (pageNode.has("body") && pageNode.get("body").has("view")) {
            JsonNode view = pageNode.get("body").get("view");
            if (view.has("value")) bodyHtml = view.get("value").asText();
        }

        String markdown = pageToMarkdown(page, spaceKey, spaceName, createdBy, createdDate,
                lastUpdatedBy, lastUpdatedDate, labels, ancestorPath, bodyHtml, comments);
        page.setContent(markdown);
        return page;
    }

    // --- Markdown composition ----------------------------------------------------

    public static String pageToMarkdown(ConfluencePageDto page, String spaceKey, String spaceName,
            String createdBy, String createdDate, String lastUpdatedBy, String lastUpdatedDate,
            List<String> labels, List<String> ancestorPath, String bodyHtml, List<CommentDto> comments) {

        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(page.getTitle()).append("\n\n");

        sb.append("## Page Details\n");
        sb.append("| Field | Value |\n");
        sb.append("|-------|-------|\n");
        if (spaceKey != null) {
            String spaceLabel = spaceName != null ? spaceKey + " - " + spaceName : spaceKey;
            sb.append("| Space | ").append(spaceLabel).append(" |\n");
        }
        if (createdDate != null || createdBy != null) {
            String created = Stream.of(createdDate, createdBy != null ? "by " + createdBy : null)
                    .filter(Objects::nonNull).collect(Collectors.joining(" "));
            sb.append("| Created | ").append(created).append(" |\n");
        }
        if (lastUpdatedDate != null || lastUpdatedBy != null) {
            String updated = Stream.of(lastUpdatedDate, lastUpdatedBy != null ? "by " + lastUpdatedBy : null)
                    .filter(Objects::nonNull).collect(Collectors.joining(" "));
            sb.append("| Last Updated | ").append(updated).append(" |\n");
        }
        if (!labels.isEmpty()) {
            sb.append("| Labels | ").append(String.join(", ", labels)).append(" |\n");
        }
        sb.append("| URL | ").append(page.getWebUrl()).append(" |\n");
        sb.append("\n");

        if (!ancestorPath.isEmpty()) {
            sb.append("## Location\n");
            sb.append(String.join(" > ", ancestorPath)).append(" > ").append(page.getTitle()).append("\n\n");
        }

        // The heading only when there is something under it - an empty "## Content" reads as a
        // conversion that lost the page, and it is one more chunk of nothing in the index.
        String bodyMd = htmlToMarkdown(bodyHtml);
        if (!bodyMd.isBlank()) {
            sb.append("## Content\n\n").append(bodyMd).append("\n\n");
        }

        if (!comments.isEmpty()) {
            sb.append("## Comments\n\n");
            for (CommentDto comment : comments) {
                String author = comment.getAuthor() != null ? comment.getAuthor() : "Unknown";
                sb.append("### ").append(author);
                if (comment.getDate() != null && !comment.getDate().isBlank()) {
                    sb.append(" - ").append(comment.getDate());
                }
                sb.append("\n");
                if (comment.getContent() != null && !comment.getContent().isBlank()) {
                    sb.append(comment.getContent()).append("\n\n");
                }
            }
        }

        return sb.toString().trim();
    }

    // --- HTML to Markdown --------------------------------------------------------

    /**
     * Convert rendered HTML (body.view from Confluence) to Markdown.
     *
     * <p>The conversion itself lives in {@link ConfluenceHtmlToMarkdown}, over a parsed DOM. It was
     * a stack of regular expressions here, and a regex cannot see nesting: a nested list lost its
     * outer item, a table inside a cell ended the row early, and the bold/italic/link rewrites ran
     * inside code blocks, so indexed code did not match the code on the page.
     */
    public static String htmlToMarkdown(String html) {
        return ConfluenceHtmlToMarkdown.convert(html);
    }

    // --- Helpers -----------------------------------------------------------------

    public static String formatDate(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) return "";
        try {
            OffsetDateTime odt = OffsetDateTime.parse(isoDate);
            return odt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (DateTimeParseException e1) {
            try {
                return isoDate.substring(0, 16).replace('T', ' ');
            } catch (Exception e2) {
                return isoDate;
            }
        }
    }

    public static String joinUrl(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isEmpty()) return path != null ? path : "";
        if (path == null || path.isEmpty()) return baseUrl;
        String cleanBase = baseUrl;
        while (cleanBase.endsWith("/")) cleanBase = cleanBase.substring(0, cleanBase.length() - 1);
        String cleanPath = path;
        while (cleanPath.startsWith("/")) cleanPath = cleanPath.substring(1);
        return cleanBase + "/" + cleanPath;
    }

    public static Instant parseConfluenceTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return null;
        try {
            return OffsetDateTime.parse(timestamp).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
