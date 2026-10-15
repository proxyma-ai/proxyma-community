package ai.proxyma.core.tools;

import ai.proxyma.core.chat.service.ConversationSearchService;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Earlier conversations, beside recall's memory: memory holds what someone chose to keep, this finds
// what was simply said. Search by words, then read one by its id. See ConversationSearchService for
// who sees which conversations.
@Component
public class SearchConversationsTool {

    private static final int DEFAULT_RESULTS = 5;
    private static final int MAX_RESULTS = 20;

    private final ConversationSearchService search;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public SearchConversationsTool(ConversationSearchService search) {
        this.search = search;
    }

    @Tool(description = "Search the user's earlier conversations by words (not this one), or read one by its id. "
            + "Use alongside recall: memory holds what was saved, this finds what was said.")
    @ToolResult({
            @ToolResultField(name = "conversations", type = ToolFieldType.OBJECT_ARRAY,
                    description = "With query: conversationId, title, updatedAt, link, termsMatched and up to 3 "
                            + "matches (role, at, excerpt). Empty is a success."),
            @ToolResultField(name = "messages", type = ToolFieldType.OBJECT_ARRAY,
                    description = "With conversationId: index, role, at, text."),
            @ToolResultField(name = "nextFromMessage", type = ToolFieldType.INTEGER,
                    description = "Present when the conversation continues - pass it as fromMessage.")
    })
    public Map<String, Object> searchConversations(
            @ToolParam(required = false, description = "Words to find; a \"quoted phrase\" stays together") String query,
            @ToolParam(required = false, description = "A conversationId from a search, to read it") String conversationId,
            @ToolParam(required = false, description = "With conversationId: the message index to start at") Integer fromMessage,
            @ToolParam(required = false, description = "Max conversations (default 5, max 20)") Integer maxResults) {
        try {
            String userId = ToolUserContext.resolveUserId(multiUser);
            if (conversationId != null && !conversationId.isBlank()) {
                return search.read(userId, conversationId, fromMessage == null ? 0 : fromMessage)
                        .<Map<String, Object>>map(t -> {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("success", true);
                            r.put("conversationId", t.conversationId());
                            r.put("title", t.title() == null ? "" : t.title());
                            r.put("link", link(t.conversationId()));
                            r.put("messages", t.messages());
                            if (t.nextFromMessage() != null) r.put("nextFromMessage", t.nextFromMessage());
                            return r;
                        })
                        .orElseGet(() -> Map.of("success", false, "error", "No earlier conversation with id "
                                + conversationId.strip() + " that you can read."));
            }
            if (query == null || query.isBlank()) {
                return Map.of("success", false, "error", "Give a query, or a conversationId to read.");
            }
            int n = maxResults == null ? DEFAULT_RESULTS : Math.min(Math.max(1, maxResults), MAX_RESULTS);
            List<Map<String, Object>> found = search.search(userId, SearchContext.getConversationId(), query, n)
                    .stream().map(h -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("conversationId", h.conversationId());
                        m.put("title", h.title() == null ? "" : h.title());
                        m.put("updatedAt", h.updatedAt());
                        m.put("link", link(h.conversationId()));
                        m.put("termsMatched", h.termsMatched());
                        m.put("matches", h.matches());
                        return m;
                    }).toList();
            return Map.of("success", true, "conversations", found);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }

    // Opens the conversation in the app (MarkdownRenderer handles app://chat/<id>).
    private static String link(String conversationId) {
        return "app://chat/" + conversationId;
    }
}
