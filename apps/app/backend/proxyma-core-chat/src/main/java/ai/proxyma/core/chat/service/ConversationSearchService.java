package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The assistant's look back at the user's earlier conversations - what memory does not hold
 * because nobody asked it to remember. A WORD search over titles and messages, not a semantic one:
 * embedding every message ever written is a far larger change, and a word search already answers
 * "what did we decide about X last week".
 *
 * <p>WHO SEES WHAT is the history list's own rule (SmartChatMemory.listConversationsPaged): on a
 * single-user edition every conversation on the install; on Enterprise Server only the caller's,
 * and nothing for a caller with no account. The conversation the search runs from is left out -
 * it is already in the model's context.
 */
@Service
public class ConversationSearchService {

    private static final int MAX_TERMS = 6;
    private static final int PER_TERM_ROWS = 200;
    private static final int EXCERPT_CHARS = 240;
    private static final int EXCERPTS_PER_CONVERSATION = 3;
    private static final int READ_MESSAGE_CHARS = 4_000;
    private static final int READ_TOTAL_CHARS = 40_000;

    private final ChatMessageRepository messages;
    private final ConversationRepository conversations;
    private final boolean multiUser;

    public ConversationSearchService(ChatMessageRepository messages, ConversationRepository conversations,
                                     @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.messages = messages;
        this.conversations = conversations;
        this.multiUser = multiUser;
    }

    public record Match(String role, String at, String excerpt) {}

    public record Hit(String conversationId, String title, String updatedAt, int termsMatched, List<Match> matches) {}

    public record Message(int index, String role, String at, String text) {}

    public record Transcript(String conversationId, String title, List<Message> messages, boolean truncated,
                             Integer nextFromMessage) {}

    /**
     * Conversations containing the query's words, the ones holding MORE of the words first, then
     * the newest. Each carries up to three excerpts around a hit.
     */
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public List<Hit> search(String userId, String currentConversationId, String query, int maxResults) {
        if (!mayRead(userId)) return List.of();
        List<String> terms = terms(query);
        if (terms.isEmpty()) return List.of();
        String exclude = currentConversationId == null ? "" : currentConversationId;
        PageRequest rows = PageRequest.of(0, PER_TERM_ROWS * terms.size());
        // One pattern per term, the first repeated into the slots a short query leaves - so the
        // whole search is one statement over the messages and one over the titles, not one of each
        // per term. Which terms a row holds is worked out here, from what came back.
        String[] p = new String[MAX_TERMS];
        for (int i = 0; i < MAX_TERMS; i++) p[i] = "%" + escape(terms.get(Math.min(i, terms.size() - 1))) + "%";

        Map<String, Set<String>> termsByConversation = new HashMap<>();
        Map<String, List<ChatMessage>> hitsByConversation = new LinkedHashMap<>();
        Map<String, Conversation> titled = new HashMap<>();
        List<ChatMessage> found = multiUser
                ? messages.searchContentOfUser(userId, exclude, p[0], p[1], p[2], p[3], p[4], p[5], rows)
                : messages.searchContent(exclude, p[0], p[1], p[2], p[3], p[4], p[5], rows);
        for (ChatMessage m : found) {
            // The model's own scaffolding - a loaded skill, a workflow's plan, a report - is not
            // something either party said. See ChatService.isScaffolding.
            if (ChatService.isScaffolding(m.getContent())) continue;
            List<String> held = termsIn(m.getContent(), terms);
            if (held.isEmpty()) continue;
            termsByConversation.computeIfAbsent(m.getConversationId(), k -> new LinkedHashSet<>()).addAll(held);
            hitsByConversation.computeIfAbsent(m.getConversationId(), k -> new ArrayList<>()).add(m);
        }
        List<Conversation> byTitle = multiUser
                ? conversations.searchTitlesOfUser(userId, exclude, p[0], p[1], p[2], p[3], p[4], p[5], rows)
                : conversations.searchTitles(exclude, p[0], p[1], p[2], p[3], p[4], p[5], rows);
        for (Conversation c : byTitle) {
            List<String> held = termsIn(c.getTitle(), terms);
            if (held.isEmpty()) continue;
            termsByConversation.computeIfAbsent(c.getId(), k -> new LinkedHashSet<>()).addAll(held);
            titled.put(c.getId(), c);
        }

        Map<String, Conversation> byId = new HashMap<>(titled);
        conversations.findAllById(termsByConversation.keySet().stream().filter(id -> !byId.containsKey(id)).toList())
                .forEach(c -> byId.put(c.getId(), c));

        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : termsByConversation.entrySet()) {
            Conversation c = byId.get(e.getKey());
            if (c == null) continue;
            List<Match> matches = new ArrayList<>();
            for (ChatMessage m : hitsByConversation.getOrDefault(e.getKey(), List.of())) {
                if (matches.size() == EXCERPTS_PER_CONVERSATION) break;
                matches.add(new Match(role(m.getRole()), String.valueOf(m.getCreatedAt()), excerpt(m.getContent(), terms)));
            }
            hits.add(new Hit(c.getId(), c.getTitle(), String.valueOf(c.getUpdatedAt()), e.getValue().size(), matches));
        }
        hits.sort(Comparator.comparingInt(Hit::termsMatched).reversed()
                .thenComparing(Hit::updatedAt, Comparator.reverseOrder()));
        return hits.subList(0, Math.min(Math.max(1, maxResults), hits.size()));
    }

    /**
     * One earlier conversation's own words, from {@code fromMessage} on, capped so a long one
     * comes back in pages.
     */
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public Optional<Transcript> read(String userId, String conversationId, int fromMessage) {
        if (!mayRead(userId) || conversationId == null || conversationId.isBlank()) return Optional.empty();
        Optional<Conversation> found = conversations.findById(conversationId.strip());
        if (found.isEmpty()) return Optional.empty();
        Conversation c = found.get();
        // The same answer for "not yours" as for "not there": the id of someone else's
        // conversation must not be confirmable from here.
        if (multiUser && !userId.equals(c.getUserId())) return Optional.empty();

        List<ChatMessage> all = messages.findDisplayMessagesByConversationId(c.getId());
        List<Message> out = new ArrayList<>();
        int total = 0;
        Integer next = null;
        for (int i = Math.max(0, fromMessage); i < all.size(); i++) {
            ChatMessage m = all.get(i);
            // Condensation summaries, loaded skills, workflow scaffolding, sub-agent reports and the
            // last-step nudge are stored as user and assistant rows, and came back labeled as words
            // the user or the assistant had said (chat-agent-29). The transcript leaves them out;
            // so does this. Skipped, not renumbered: an index still pages through the same list.
            if (ChatService.isScaffolding(m.getContent())) continue;
            String text = m.getContent() == null ? "" : m.getContent();
            if (text.length() > READ_MESSAGE_CHARS) text = text.substring(0, READ_MESSAGE_CHARS) + " ... [cut]";
            if (!out.isEmpty() && total + text.length() > READ_TOTAL_CHARS) {
                next = i;
                break;
            }
            total += text.length();
            out.add(new Message(i, role(m.getRole()), String.valueOf(m.getCreatedAt()), text));
        }
        return Optional.of(new Transcript(c.getId(), c.getTitle(), out, next != null, next));
    }

    private boolean mayRead(String userId) {
        return !multiUser || (userId != null && !userId.isBlank());
    }

    // Words of three letters or more, lower-cased, at most six - a sentence pasted as a query
    // would otherwise be a query per word. A quoted phrase stays one term.
    static List<String> terms(String query) {
        if (query == null) return List.of();
        List<String> terms = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"|(\\S+)").matcher(query);
        while (m.find() && terms.size() < MAX_TERMS) {
            String t = (m.group(1) != null ? m.group(1) : m.group(2)).strip().toLowerCase(Locale.ROOT);
            if (t.length() >= 3 && !terms.contains(t)) terms.add(t);
        }
        return terms;
    }

    // The terms a text holds, the way the query's LIKE '%term%' over LOWER(text) found them.
    static List<String> termsIn(String text, List<String> terms) {
        if (text == null) return List.of();
        String lower = text.toLowerCase(Locale.ROOT);
        return terms.stream().filter(lower::contains).toList();
    }

    static String escape(String term) {
        return term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    // The first hit of any term, with context either side, on word boundaries where it can.
    static String excerpt(String content, List<String> terms) {
        if (content == null) return "";
        String flat = content.replaceAll("\\s+", " ").strip();
        String lower = flat.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String t : terms) {
            int i = lower.indexOf(t);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        if (flat.length() <= EXCERPT_CHARS) return flat;
        if (at < 0) at = 0;
        int start = Math.max(0, at - EXCERPT_CHARS / 3);
        int end = Math.min(flat.length(), start + EXCERPT_CHARS);
        int space = flat.lastIndexOf(' ', start);
        if (start > 0 && space > 0 && start - space < 20) start = space + 1;
        return (start > 0 ? "..." : "") + flat.substring(start, end).strip() + (end < flat.length() ? "..." : "");
    }

    private static String role(MessageRole role) {
        return role == MessageRole.USER ? "user" : "assistant";
    }
}
