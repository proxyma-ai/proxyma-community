package ai.proxyma.core.connector.jira;

import ai.proxyma.core.connector.jira.JiraConnector.JiraIssueDto;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Jira issue as the markdown document that is indexed and that the agent's getJiraIssue reads -
 * ONE renderer for both, since SourceFactsReader parses its header back for the knowledge graph and
 * two copies would drift. Stateless: the custom fields to leave out are an argument, never a field
 * some other caller set (the bean field it replaced handed one user's deny list to another's call).
 *
 * <p>Takes the issue as either REST version returns it: Server and Data Center's v2 (descriptions
 * and comments as wiki-markup strings) and Cloud's v3 (the same fields as Atlassian Document
 * Format, converted to markdown by {@link #adfToMarkdown}).
 */
public final class JiraIssueMarkdown {

    /** Jira timestamp format: 2026-03-26T14:08:31.000+0000 */
    static final DateTimeFormatter JIRA_DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private static final DateTimeFormatter DISPLAY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Custom field display names (lowercased) always skipped, whatever the deployment configures.
     *
     * <p>Only the two that are generic to every Jira: Rank, which is board bookkeeping, and the
     * development panel, whose value is also caught by {@link #isNoisyCustomFieldValue}. This list
     * used to hold ~70 display names and two {@code customfield_NNNNN} ids from one customer's
     * server - an id is per instance ({@code customfield_10000} is Rank on one and something
     * meaningful on the next), so matching on it denied a field nobody asked to deny. Everything
     * instance-specific now comes from {@code jira.skip-custom-fields} (spec 01 section 5.4).
     */
    public static final Set<String> BUILTIN_SKIP_CUSTOM_DISPLAY_NAMES = Set.of("rank", "development");

    private JiraIssueMarkdown() {}

    /** An issue as {@code GET /issue/{key}?fields=*all&expand=names,changelog} returns it, rendered. */
    public static String render(JsonNode issueNode, Set<String> skipCustomFields) {
        return issueToMarkdown(parseIssueWithDetails(issueNode,
                skipCustomFields == null ? BUILTIN_SKIP_CUSTOM_DISPLAY_NAMES : skipCustomFields));
    }

    /**
     * The custom-field deny list: the always-skipped names of
     * {@link #BUILTIN_SKIP_CUSTOM_DISPLAY_NAMES} plus whatever {@code jira.skip-custom-fields}
     * adds, all lowercased for a case-insensitive match on the field's DISPLAY NAME.
     *
     * <p>Name and not {@code customfield_NNNNN}: an id means a different field on every Jira
     * instance, so an id-keyed deny list configured for one server hides an arbitrary field on the
     * next. Spec 01 section 5.4.
     */
    public static Set<String> parseSkipCustomFields(String commaSeparated) {
        Set<String> names = new LinkedHashSet<>(BUILTIN_SKIP_CUSTOM_DISPLAY_NAMES);
        if (commaSeparated != null) {
            for (String name : commaSeparated.split(",")) {
                String trimmed = name.strip().toLowerCase();
                if (!trimmed.isEmpty()) names.add(trimmed);
            }
        }
        return names;
    }

    public static LocalDateTime parseJiraDateTime(String timestamp) {
        if (timestamp == null || timestamp.isEmpty())
            return null;
        return OffsetDateTime.parse(timestamp, JIRA_DATETIME_FORMATTER).toLocalDateTime();
    }

    // =========================================================================
    //  MARKDOWN COMPOSER
    // =========================================================================

    /**
     * Convert a fully-populated JiraIssueDto to structured markdown.
     * Produces a document with zero information loss for embedding.
     */
    public static String issueToMarkdown(JiraIssueDto issue) {
        StringBuilder md = new StringBuilder();

        md.append("# [").append(issue.getKey()).append("] ").append(nvl(issue.getSummary())).append("\n\n");

        md.append("## Metadata\n\n| Field | Value |\n|-------|-------|\n");
        appendMetaRow(md, "Type", issue.getIssueType());
        appendMetaRow(md, "Status", issue.getStatus());
        appendMetaRow(md, "Priority", issue.getPriority());
        appendMetaRow(md, "Assignee", issue.getAssignee());
        appendMetaRow(md, "Reporter", issue.getReporter());
        appendMetaRow(md, "Created", formatJiraTimestamp(issue.getCreated()));
        appendMetaRow(md, "Updated", formatJiraTimestamp(issue.getUpdated()));
        appendMetaRow(md, "Due Date", issue.getDueDate());
        if (issue.getLabels() != null && !issue.getLabels().isEmpty())
            appendMetaRow(md, "Labels", String.join(", ", issue.getLabels()));
        if (issue.getComponents() != null && !issue.getComponents().isEmpty())
            appendMetaRow(md, "Components", String.join(", ", issue.getComponents()));
        if (issue.getFixVersions() != null && !issue.getFixVersions().isEmpty())
            appendMetaRow(md, "Fix Versions", String.join(", ", issue.getFixVersions()));
        if (issue.getAffectsVersions() != null && !issue.getAffectsVersions().isEmpty())
            appendMetaRow(md, "Affects", String.join(", ", issue.getAffectsVersions()));
        appendMetaRow(md, "Environment", issue.getEnvironment());
        appendMetaRow(md, "Time Estimate", issue.getOriginalEstimate());
        appendMetaRow(md, "Remaining", issue.getRemainingEstimate());
        appendMetaRow(md, "Time Spent", issue.getTimeSpent());
        if (issue.getVotes() > 0)
            appendMetaRow(md, "Votes", String.valueOf(issue.getVotes()));
        if (issue.getWatches() > 0)
            appendMetaRow(md, "Watches", String.valueOf(issue.getWatches()));
        if (issue.getCustomFields() != null)
            issue.getCustomFields().forEach((name, value) -> {
                if (value != null && !value.isBlank())
                    appendMetaRow(md, name, value);
            });
        md.append("\n");

        if (issue.getParentKey() != null) {
            md.append("## Parent\n\n[").append(issue.getParentKey()).append("] ")
                    .append(nvl(issue.getParentSummary())).append("\n\n");
        }

        if (issue.getSubtasks() != null && !issue.getSubtasks().isEmpty()) {
            md.append("## Subtasks\n\n");
            issue.getSubtasks().forEach(s -> md.append("- ").append(s).append("\n"));
            md.append("\n");
        }

        if (issue.getIssueLinks() != null && !issue.getIssueLinks().isEmpty()) {
            md.append("## Linked Issues\n\n");
            issue.getIssueLinks().forEach(l -> md.append("- ").append(l).append("\n"));
            md.append("\n");
        }

        if (issue.getDescription() != null && !issue.getDescription().isBlank()) {
            md.append("## Description\n\n").append(issue.getDescription()).append("\n\n");
        }

        if (issue.getAttachments() != null && !issue.getAttachments().isEmpty()) {
            md.append("## Attachments\n\n");
            issue.getAttachments().forEach(a -> md.append("- ").append(a).append("\n"));
            md.append("\n");
        }

        if (issue.getCommentList() != null && !issue.getCommentList().isEmpty()) {
            md.append("## Comments\n\n");
            for (JiraIssueDto.CommentEntry comment : issue.getCommentList()) {
                md.append("### ").append(nvl(comment.getAuthor()))
                        .append(" - ").append(formatJiraTimestamp(comment.getCreated())).append("\n\n");
                if (comment.getBody() != null && !comment.getBody().isBlank())
                    md.append(comment.getBody()).append("\n\n");
            }
        }

        if (issue.getChangelog() != null && !issue.getChangelog().isEmpty()) {
            md.append("## Change History\n\n");
            md.append("| Date | Author | Field | From | To |\n");
            // No trailing spaces after the separator row: two of them are Markdown's hard line
            // break, which put a <br> between the header and the body and broke the table in every
            // renderer that honors it.
            md.append("|------|--------|-------|------|----|\n");
            for (JiraIssueDto.ChangelogEntry entry : issue.getChangelog()) {
                // Escaped like every other cell in this document: a changelog From/To is a former
                // field value, so it carries whatever a user typed - including the pipe that ends a
                // cell and the newline that ends a row.
                md.append("| ").append(cell(formatJiraTimestamp(entry.getCreated())))
                        .append(" | ").append(cell(entry.getAuthor()))
                        .append(" | ").append(cell(entry.getField()))
                        .append(" | ").append(cell(entry.getFromString()))
                        .append(" | ").append(cell(entry.getToString()))
                        .append(" |\n");
            }
            md.append("\n");
        }

        return md.toString().trim();
    }

    // =========================================================================
    //  ADF -> MARKDOWN CONVERTER
    // =========================================================================

    /**
     * Convert Atlassian Document Format (ADF) JsonNode to Markdown.
     * Handles both ADF objects and plain-text strings (Jira Server v2 fallback).
     */
    public static String adfToMarkdown(JsonNode adfNode) {
        if (adfNode == null || adfNode.isNull())
            return "";
        if (adfNode.isTextual())
            return adfNode.asText();
        if (!adfNode.isObject())
            return "";
        StringBuilder sb = new StringBuilder();
        adfNodeToMarkdown(adfNode, sb, 0);
        return sb.toString().trim();
    }

    private static void adfNodeToMarkdown(JsonNode node, StringBuilder sb, int listDepth) {
        String type = node.path("type").asText();
        JsonNode content = node.path("content");
        JsonNode attrs = node.path("attrs");

        switch (type) {
            case "doc":
                appendChildren(content, sb, listDepth);
                break;
            case "paragraph":
                appendChildren(content, sb, listDepth);
                sb.append("\n\n");
                break;
            case "heading":
                int level = Math.max(1, Math.min(6, attrs.path("level").asInt(1)));
                sb.append("#".repeat(level)).append(" ");
                appendChildren(content, sb, listDepth);
                sb.append("\n\n");
                break;
            case "bulletList":
                appendListItems(content, sb, listDepth, false);
                break;
            case "orderedList":
                appendListItems(content, sb, listDepth, true);
                break;
            case "listItem":
                appendListItemContent(content, sb, listDepth);
                break;
            case "codeBlock":
                String lang = attrs.path("language").asText("");
                sb.append("```").append(lang).append("\n");
                appendChildren(content, sb, listDepth);
                sb.append("```\n\n");
                break;
            case "blockquote":
                StringBuilder quoteSb = new StringBuilder();
                appendChildren(content, quoteSb, listDepth);
                for (String line : quoteSb.toString().split("\n", -1))
                    sb.append("> ").append(line).append("\n");
                sb.append("\n");
                break;
            case "rule":
                sb.append("\n---\n\n");
                break;
            case "hardBreak":
                sb.append("  \n");
                break;
            case "text":
                sb.append(applyMarks(node.path("text").asText(), node.path("marks")));
                break;
            case "mention": {
                // Cloud writes the mention's text with its "@" already on it.
                String who = attrs.path("text").asText(attrs.path("id").asText("mention"));
                sb.append(who.startsWith("@") ? who : "@" + who);
                break;
            }
            case "emoji":
                sb.append(attrs.path("shortName").asText(":emoji:"));
                break;
            case "inlineCard":
            case "blockCard":
            case "embedCard": {
                String url = attrs.path("url").asText();
                if (!url.isEmpty())
                    sb.append("[").append(url).append("](").append(url).append(")");
                break;
            }
            case "mediaSingle":
            case "mediaGroup":
                appendChildren(content, sb, listDepth);
                sb.append("\n");
                break;
            case "media": {
                String alt = attrs.path("alt").asText(attrs.path("id").asText("attachment"));
                String mediaUrl = attrs.path("url").asText("");
                if (!mediaUrl.isEmpty())
                    sb.append("![").append(alt).append("](").append(mediaUrl).append(")\n");
                else
                    sb.append("[attachment: ").append(alt).append("]\n");
                break;
            }
            case "table":
                appendTable(content, sb, listDepth);
                break;
            case "panel": {
                String panelType = attrs.path("panelType").asText("info").toUpperCase();
                sb.append("> **[").append(panelType).append("]**\n");
                StringBuilder panelSb = new StringBuilder();
                appendChildren(content, panelSb, listDepth);
                for (String line : panelSb.toString().split("\n", -1))
                    sb.append("> ").append(line).append("\n");
                sb.append("\n");
                break;
            }
            default:
                if (content.isArray())
                    appendChildren(content, sb, listDepth);
                break;
        }
    }

    private static void appendChildren(JsonNode contentArray, StringBuilder sb, int listDepth) {
        if (contentArray == null || !contentArray.isArray())
            return;
        for (JsonNode child : contentArray)
            adfNodeToMarkdown(child, sb, listDepth);
    }

    private static void appendListItems(JsonNode items, StringBuilder sb, int depth, boolean ordered) {
        if (!items.isArray())
            return;
        String indent = "  ".repeat(depth);
        int[] index = { 1 };
        for (JsonNode item : items) {
            sb.append(indent).append(ordered ? (index[0]++ + ". ") : "- ");
            appendListItemContent(item.path("content"), sb, depth);
            sb.append("\n");
        }
    }

    private static void appendListItemContent(JsonNode content, StringBuilder sb, int depth) {
        if (!content.isArray())
            return;
        for (JsonNode child : content) {
            String childType = child.path("type").asText();
            if ("bulletList".equals(childType)) {
                sb.append("\n");
                appendListItems(child.path("content"), sb, depth + 1, false);
            } else if ("orderedList".equals(childType)) {
                sb.append("\n");
                appendListItems(child.path("content"), sb, depth + 1, true);
            } else {
                StringBuilder inlineSb = new StringBuilder();
                adfNodeToMarkdown(child, inlineSb, depth);
                sb.append(inlineSb.toString().stripTrailing());
            }
        }
    }

    private static void appendTable(JsonNode rows, StringBuilder sb, int listDepth) {
        if (!rows.isArray() || rows.isEmpty())
            return;
        boolean separatorWritten = false;
        for (JsonNode row : rows) {
            if (!"tableRow".equals(row.path("type").asText()))
                continue;
            sb.append("|");
            JsonNode cells = row.path("content");
            int cellCount = 0;
            for (JsonNode cell : cells) {
                StringBuilder cellSb = new StringBuilder();
                appendChildren(cell.path("content"), cellSb, listDepth);
                sb.append(" ").append(cellSb.toString().strip().replace("\n", " ").replace("|", "\\|")).append(" |");
                cellCount++;
            }
            sb.append("\n");
            if (!separatorWritten && cellCount > 0) {
                sb.append("|");
                for (int i = 0; i < cellCount; i++)
                    sb.append("---|");
                sb.append("\n");
                separatorWritten = true;
            }
        }
        sb.append("\n");
    }

    private static String applyMarks(String text, JsonNode marks) {
        if (marks == null || !marks.isArray() || marks.isEmpty())
            return text;
        for (JsonNode mark : marks) {
            switch (mark.path("type").asText()) {
                case "strong":
                    text = "**" + text + "**";
                    break;
                case "em":
                    text = "*" + text + "*";
                    break;
                case "code":
                    text = "`" + text + "`";
                    break;
                case "strike":
                    text = "~~" + text + "~~";
                    break;
                case "link":
                    String href = mark.path("attrs").path("href").asText();
                    if (!href.isEmpty())
                        text = "[" + text + "](" + href + ")";
                    break;
                default:
                    break;
            }
        }
        return text;
    }

    // =========================================================================
    //  PARSE HELPERS
    // =========================================================================

    public static JiraIssueDto parseIssue(JsonNode issueNode) {
        JiraIssueDto issue = new JiraIssueDto();
        issue.setKey(issueNode.path("key").asText());
        issue.setSelf(issueNode.path("self").asText());
        JsonNode fields = issueNode.path("fields");
        if (fields.isObject()) {
            issue.setSummary(fields.path("summary").asText(null));
            JsonNode issuetype = fields.path("issuetype");
            if (issuetype.isObject())
                issue.setIssueType(issuetype.path("name").asText(null));
            String updated = fields.path("updated").asText(null);
            if (updated != null && !updated.isEmpty())
                issue.setUpdated(updated);
        }
        return issue;
    }

    public static JiraIssueDto parseIssueWithDetails(JsonNode issueNode, Set<String> denied) {
        JiraIssueDto issue = parseIssue(issueNode);
        JsonNode fields = issueNode.path("fields");
        JsonNode namesMap = issueNode.path("names");

        if (!fields.isObject())
            return issue;

        JsonNode statusNode = fields.path("status");
        if (statusNode.isObject())
            issue.setStatus(statusNode.path("name").asText(null));

        JsonNode priorityNode = fields.path("priority");
        if (priorityNode.isObject())
            issue.setPriority(priorityNode.path("name").asText(null));

        issue.setCreated(fields.path("created").asText(null));
        String dueDate = fields.path("duedate").asText(null);
        if (dueDate != null && !dueDate.isEmpty())
            issue.setDueDate(dueDate);

        JsonNode assigneeNode = fields.path("assignee");
        if (assigneeNode.isObject()) {
            String displayName = assigneeNode.path("displayName").asText("");
            String email = assigneeNode.path("emailAddress").asText("");
            issue.setAssignee(email.isEmpty() ? displayName : displayName + " (" + email + ")");
        }
        JsonNode reporterNode = fields.path("reporter");
        if (reporterNode.isObject())
            issue.setReporter(reporterNode.path("displayName").asText(null));

        JsonNode descNode = fields.path("description");
        if (!descNode.isMissingNode() && !descNode.isNull())
            issue.setDescription(adfToMarkdown(descNode));

        JsonNode labelsNode = fields.path("labels");
        if (labelsNode.isArray()) {
            List<String> labels = new ArrayList<>();
            for (JsonNode l : labelsNode)
                labels.add(l.asText());
            issue.setLabels(labels);
        }

        JsonNode componentsNode = fields.path("components");
        if (componentsNode.isArray()) {
            List<String> components = new ArrayList<>();
            for (JsonNode c : componentsNode)
                components.add(c.path("name").asText());
            issue.setComponents(components);
        }

        JsonNode fixVersionsNode = fields.path("fixVersions");
        if (fixVersionsNode.isArray()) {
            List<String> versions = new ArrayList<>();
            for (JsonNode v : fixVersionsNode)
                versions.add(v.path("name").asText());
            issue.setFixVersions(versions);
        }

        JsonNode versionsNode = fields.path("versions");
        if (versionsNode.isArray()) {
            List<String> versions = new ArrayList<>();
            for (JsonNode v : versionsNode)
                versions.add(v.path("name").asText());
            issue.setAffectsVersions(versions);
        }

        JsonNode envNode = fields.path("environment");
        if (!envNode.isMissingNode() && !envNode.isNull())
            issue.setEnvironment(adfToMarkdown(envNode));

        JsonNode parentNode = fields.path("parent");
        if (parentNode.isObject()) {
            issue.setParentKey(parentNode.path("key").asText(null));
            issue.setParentSummary(parentNode.path("fields").path("summary").asText(null));
        }

        JsonNode subtasksNode = fields.path("subtasks");
        if (subtasksNode.isArray()) {
            List<String> subtasks = new ArrayList<>();
            for (JsonNode s : subtasksNode) {
                String key = s.path("key").asText();
                String summary = s.path("fields").path("summary").asText();
                String status = s.path("fields").path("status").path("name").asText();
                subtasks.add("[" + key + "] " + summary + (status.isEmpty() ? "" : " (" + status + ")"));
            }
            issue.setSubtasks(subtasks);
        }

        JsonNode linksNode = fields.path("issuelinks");
        if (linksNode.isArray()) {
            List<String> links = new ArrayList<>();
            for (JsonNode link : linksNode) {
                JsonNode outward = link.path("outwardIssue");
                JsonNode inward = link.path("inwardIssue");
                String linkId = link.path("id").asText("");
                if (!outward.isMissingNode()) {
                    String linkType = link.path("type").path("outward").asText("relates to");
                    links.add(formatLinkedIssue(linkType, outward, linkId));
                } else if (!inward.isMissingNode()) {
                    String linkType = link.path("type").path("inward").asText("relates to");
                    links.add(formatLinkedIssue(linkType, inward, linkId));
                }
            }
            issue.setIssueLinks(links);
        }

        JsonNode attachmentsNode = fields.path("attachment");
        if (attachmentsNode.isArray()) {
            List<String> attachments = new ArrayList<>();
            for (JsonNode att : attachmentsNode) {
                String filename = att.path("filename").asText();
                long size = att.path("size").asLong(0);
                String author = att.path("author").path("displayName").asText("");
                String created = att.path("created").asText("");
                String sizeStr = size > 1024 * 1024
                        ? String.format("%.1f MB", size / (1024.0 * 1024))
                        : String.format("%.0f KB", size / 1024.0);
                String entry = filename + " (" + sizeStr + ")";
                if (!author.isEmpty())
                    entry += " - " + author;
                if (!created.isEmpty())
                    entry += " on " + formatJiraTimestamp(created);
                attachments.add(entry);
            }
            issue.setAttachments(attachments);
        }

        JsonNode timeNode = fields.path("timetracking");
        if (timeNode.isObject()) {
            issue.setOriginalEstimate(timeNode.path("originalEstimate").asText(null));
            issue.setRemainingEstimate(timeNode.path("remainingEstimate").asText(null));
            issue.setTimeSpent(timeNode.path("timeSpent").asText(null));
        }

        issue.setVotes(fields.path("votes").path("votes").asInt(0));
        issue.setWatches(fields.path("watches").path("watchCount").asInt(0));

        JsonNode commentsNode = fields.path("comment").path("comments");
        if (commentsNode.isArray()) {
            List<JiraIssueDto.CommentEntry> commentList = new ArrayList<>();
            for (JsonNode c : commentsNode) {
                JiraIssueDto.CommentEntry entry = new JiraIssueDto.CommentEntry();
                entry.setAuthor(c.path("author").path("displayName").asText(null));
                entry.setCreated(c.path("created").asText(null));
                JsonNode bodyNode = c.path("body");
                entry.setBody(!bodyNode.isMissingNode() ? adfToMarkdown(bodyNode) : "");
                commentList.add(entry);
            }
            issue.setCommentList(commentList);
            StringBuilder legacyComments = new StringBuilder();
            for (JiraIssueDto.CommentEntry ce : commentList) {
                if (ce.getBody() != null && !ce.getBody().isBlank())
                    legacyComments.append(ce.getBody()).append("\n\n");
            }
            issue.setComments(legacyComments.toString().trim());
        }

        JsonNode changelogHistories = issueNode.path("changelog").path("histories");
        if (changelogHistories.isArray()) {
            List<JiraIssueDto.ChangelogEntry> changelog = new ArrayList<>();
            for (JsonNode history : changelogHistories) {
                String author = history.path("author").path("displayName").asText("");
                String created = history.path("created").asText("");
                JsonNode items = history.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        JiraIssueDto.ChangelogEntry entry = new JiraIssueDto.ChangelogEntry();
                        entry.setCreated(created);
                        entry.setAuthor(author);
                        entry.setField(item.path("field").asText());
                        entry.setFromString(item.path("fromString").asText(null));
                        entry.setToString(item.path("toString").asText(null));
                        changelog.add(entry);
                    }
                }
            }
            issue.setChangelog(changelog);
        }

        if (namesMap.isObject()) {
            Map<String, String> customFields = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fieldIter = fields.properties().iterator();
            while (fieldIter.hasNext()) {
                Map.Entry<String, JsonNode> fieldEntry = fieldIter.next();
                String fieldId = fieldEntry.getKey();
                if (!fieldId.startsWith("customfield_"))
                    continue;
                JsonNode fieldValue = fieldEntry.getValue();
                if (fieldValue.isNull() || fieldValue.isMissingNode())
                    continue;
                String displayName = namesMap.path(fieldId).asText(fieldId);
                if (isDeniedCustomField(displayName, denied))
                    continue;
                String value = extractCustomFieldValue(fieldValue);
                if (value == null || value.isBlank())
                    continue;
                if (isNoisyCustomFieldValue(value))
                    continue;
                customFields.put(displayName, value);
            }
            if (!customFields.isEmpty())
                issue.setCustomFields(customFields);
        }

        return issue;
    }

    // linkId is rendered into the line because deleteJiraIssueLink takes one and this is the only
    // read path that exposes it - an agent asked to remove a link would otherwise have to guess at
    // an id it has never been shown. Spec 01 section 5.1.
    private static String formatLinkedIssue(String linkType, JsonNode linkedIssue, String linkId) {
        String key = linkedIssue.path("key").asText();
        String summary = linkedIssue.path("fields").path("summary").asText("");
        String status = linkedIssue.path("fields").path("status").path("name").asText("");
        return "**" + linkType + "** [" + key + "] " + summary
                + (status.isEmpty() ? "" : " (" + status + ")")
                + (linkId == null || linkId.isEmpty() ? "" : " [link " + linkId + "]");
    }

    /** Case-insensitive match on the DISPLAY NAME - see {@link #parseSkipCustomFields}. */
    static boolean isDeniedCustomField(String displayName, Set<String> denied) {
        return displayName != null && denied.contains(displayName.strip().toLowerCase());
    }

    private static boolean isNoisyCustomFieldValue(String value) {
        if (value.contains("_*:*_"))
            return true;
        if (value.startsWith("{summaryBean=") || value.contains("com.atlassian.jira.plugin.devstatus"))
            return true;
        return false;
    }

    private static String extractCustomFieldValue(JsonNode node) {
        if (node.isTextual())
            return node.asText();
        if (node.isNumber())
            return node.asText();
        if (node.isBoolean())
            return node.asText();
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : node) {
                String val = extractCustomFieldValue(item);
                if (val != null)
                    parts.add(val);
            }
            return parts.isEmpty() ? null : String.join(", ", parts);
        }
        if (node.isObject()) {
            if (node.has("value"))
                return node.path("value").asText();
            if (node.has("name"))
                return node.path("name").asText();
            if (node.has("displayName"))
                return node.path("displayName").asText();
            if (node.has("content"))
                return adfToMarkdown(node);
        }
        return null;
    }

    // ---- Formatting helpers ---------------------------------------------------

    private static void appendMetaRow(StringBuilder md, String field, String value) {
        if (value == null || value.isBlank())
            return;
        md.append("| ").append(field).append(" | ").append(cell(value)).append(" |\n");
    }

    /** One Markdown table cell: a pipe would end it early and a newline would end the row. */
    private static String cell(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    private static String formatJiraTimestamp(String ts) {
        if (ts == null || ts.isEmpty())
            return "";
        try {
            LocalDateTime dt = parseJiraDateTime(ts);
            return dt != null ? dt.format(DISPLAY_FORMATTER) : ts;
        } catch (Exception e) {
            return ts;
        }
    }
}
