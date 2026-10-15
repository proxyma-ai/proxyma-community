package ai.proxyma.core.search.service;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.search.domain.SearchContext;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// A per-user, agent-curated memory / knowledge base: short entries the chat agent decides are
// worth remembering (see RememberTool/RecallTool in proxyma-core-tools) - both durable general
// knowledge it learns AND facts about the user - retrieved on demand via the same hybrid search
// pipeline used for document search (SearchService), scoped to one deterministic collection per
// user. Replaces the old always-injected personality-profile system (PersonalityAgentService et
// al., removed) - context is now looked up when relevant instead of baked into every system prompt.
@Service
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);

    /**
     * Desktop's one user. Desktop has no accounts, so memory is filed under this rather than under
     * nobody - which would switch the feature off for the whole edition. The agent's tools and the
     * Settings page both resolve to it, so they read and write the same collection.
     */
    public static final String LOCAL_USER_ID = "_local"; // never collides with a real UUID userId

    // What an entry is about. RememberTool asks the model for it and newFactId encodes it into the
    // entry's id, so the distinction is recorded on every write.
    //
    // Read back since 2026-09-26: list() filters on it, and every entry the agent sees carries it.
    public enum MemoryCategory {
        USER('u'), KNOWLEDGE('k');

        private final char code;
        MemoryCategory(char code) { this.code = code; }
        char code() { return code; }

        static MemoryCategory fromCode(char c) {
            return c == 'u' ? USER : c == 'k' ? KNOWLEDGE : null;
        }
    }

    // Matches SearchTool's own default minScore for a plain (no explicit threshold) search call.
    private static final double DEFAULT_MIN_DENSE_SCORE = 0.3;
    // One self-contained statement per entry, generous but bounded.
    private static final int MAX_FACT_CHARS = 2000;
    // Recall-before-write dedup guard: if a dense (cosine) search for the incoming text already
    // finds an entry at or above this similarity, the new text REPLACES that entry in place rather
    // than being appended - so a re-statement, a rephrasing, or a corrected/contradicting update of
    // the same fact doesn't pile up as a second coexisting entry. Uses raw dense similarity (not
    // the full hybrid+rerank recall() below) so the threshold is a meaningful cosine value:
    // 1 - dist/2, where near-identical statements land ~0.9+.
    private static final double DEDUP_SIMILARITY_THRESHOLD = 0.90;
    // Metadata key both stores key an entry on (BM25 source + vector "source" metadata), so a
    // single entry can be deleted from both when the dedup guard replaces it.
    private static final String SOURCE_METADATA_KEY = "source";

    private final VectorStoreService vectorStoreService;
    private final BM25IndexService bm25IndexService;
    private final SearchService searchService;

    public AgentMemoryService(VectorStoreService vectorStoreService,
                               BM25IndexService bm25IndexService,
                               SearchService searchService) {
        this.vectorStoreService = vectorStoreService;
        this.bm25IndexService = bm25IndexService;
        this.searchService = searchService;
    }

    // Writes one entry to both stores (dense + BM25 - SearchService's RRF fusion needs both kept in
    // sync, same as every other write path in this codebase), under the given category.
    //
    // Recall-before-write dedup guard: a dense search runs first, and if an existing entry is at or
    // above DEDUP_SIMILARITY_THRESHOLD, that entry is deleted from both stores and the new text
    // takes its place (in-place update) instead of appending a near-duplicate. This collapses a
    // re-statement, a rephrasing, and a corrected/contradicting update of the same fact onto a
    // single entry rather than letting duplicates and stale/contradictory versions coexist.
    public String remember(String userId, String fact, MemoryCategory category) {
        if (userId == null || userId.isBlank() || fact == null || fact.isBlank()) return null;
        MemoryCategory cat = category != null ? category : MemoryCategory.KNOWLEDGE;
        String text = fact.strip();
        if (text.length() > MAX_FACT_CHARS) text = text.substring(0, MAX_FACT_CHARS);
        String collectionId = memoryCollectionId(userId);

        // The dedup guard needs an embedding. When the embedding server is slow or down it is skipped
        // rather than allowed to fail the write - a possible duplicate is better than a lost fact.
        try {
            deleteNearDuplicate(collectionId, text);
        } catch (Exception e) {
            log.warn("[Memory] duplicate check skipped: {}", e.getMessage());
        }

        String factId = newFactId(cat);
        write(userId, collectionId, factId, text, cat, SearchContext.getConversationId());
        return factId;
    }

    // One entry into both stores, under the id given. Shared by remember() and edit(); neither the
    // dedup guard nor the length cap is applied here.
    private void write(String userId, String collectionId, String factId, String text, MemoryCategory cat,
                       String conversationId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", userId);
        metadata.put("createdAt", Instant.now().toString());
        metadata.put("category", cat.name());
        // Provenance: which conversation this was learned in (set by ChatService for every tool
        // call; null over MCP or outside a conversation). Kept for later auditing/curation, not
        // used for retrieval.
        if (conversationId != null && !conversationId.isBlank()) metadata.put("conversationId", conversationId);
        // The stores' shared per-entry delete key (see deleteNearDuplicate): BM25 keys on the
        // source arg below; the vector row keys on this "source" metadata (PgVectorStoreService
        // uses metadata.get("source") as its source_id column; deleteBySourceMetadata matches it).
        metadata.put(SOURCE_METADATA_KEY, factId);
        // Deliberately NOT .id(factId): factId's zero-padded-timestamp format is not a valid UUID,
        // but PgVectorStoreService casts Document.id straight to ::uuid (confirmed by a real
        // "invalid input syntax for type uuid" failure in server mode) - every other write path in
        // this codebase leaves the id unset and lets Spring AI's Document assign its own random
        // UUID, which this now matches. factId is still used below as the BM25 source key, where
        // its sortable format is actually wanted (see newFactId()'s own comment).
        Document doc = Document.builder().text(text).metadata(metadata).build();
        // KEYWORD INDEX FIRST, embeddings second (2026-09-26). The vector write needs the embedding
        // server; with it slow, the whole write used to fail and the agent told the user memory was
        // "timing out" three times before a fact stuck (maintainer's feedback). The keyword entry
        // needs nothing remote, so the fact is stored - listable, readable by id, found by keyword -
        // whatever the embedding server does, and semantic recall picks it up once that write lands.
        bm25IndexService.addDocuments(collectionId, factId, List.of(text), null);
        try {
            vectorStoreService.upsert(collectionId, List.of(doc));
        } catch (Exception e) {
            log.warn("[Memory] stored {} by keyword only - the embedding write failed: {}", factId, e.getMessage());
        }
    }

    /**
     * The user's own correction of one entry: its text, its category, or both. The entry keeps its
     * place - the id's timestamp, which is what orders the list and dates it - and takes the new
     * category's code, so the id still says what it is. Returns the entry as it now reads; empty when
     * there is no such entry of this user's.
     *
     * <p>No dedup guard: the user is saying what this entry should be, and replacing a DIFFERENT entry
     * that happens to read alike would delete something they did not touch.
     */
    public java.util.Optional<MemoryEntry> edit(String userId, String id, String text, MemoryCategory category) {
        java.util.Optional<MemoryEntry> current = read(userId, id);
        if (current.isEmpty()) return java.util.Optional.empty();
        String newText = text == null || text.isBlank() ? current.get().text() : text.strip();
        if (newText.length() > MAX_FACT_CHARS) newText = newText.substring(0, MAX_FACT_CHARS);
        MemoryCategory cat = category != null ? category
                : current.get().category() != null ? MemoryCategory.valueOf(current.get().category())
                : MemoryCategory.KNOWLEDGE;
        String oldId = current.get().id();
        String newId = recategorized(oldId, cat);
        String collectionId = memoryCollectionId(userId);
        deleteEntry(collectionId, oldId);
        write(userId, collectionId, newId, newText, cat, null);
        return java.util.Optional.of(entry(newId, newText));
    }

    /** Deletes the given entries of this user's from both stores. Returns how many there were. */
    public int delete(String userId, java.util.Collection<String> ids) {
        if (userId == null || userId.isBlank() || ids == null) return 0;
        String collectionId = memoryCollectionId(userId);
        int deleted = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            // Only an entry this user's collection holds - the collection IS the scope, so an id
            // from somebody else's memory names nothing here.
            if (bm25IndexService.getChunkTexts(collectionId, id.strip()).isEmpty()) continue;
            deleteEntry(collectionId, id.strip());
            deleted++;
        }
        if (deleted > 0) {
            AuditEntry.of(AuditCategory.DATA, "memory.deleted")
                    .user(userId)
                    .summary(deleted + " memory entr" + (deleted == 1 ? "y" : "ies") + " deleted")
                    .detail("entries", deleted)
                    .record();
        }
        return deleted;
    }

    private void deleteEntry(String collectionId, String id) {
        bm25IndexService.deleteDocuments(collectionId, id);
        try {
            vectorStoreService.deleteBySourceMetadata(collectionId, id);
        } catch (Exception e) {
            // The keyword entry is gone, and with it the entry from every list and every read by id.
            // A vector left behind still matches a semantic recall until the next write replaces it.
            log.warn("[Memory] {} removed from the keyword index only - the vector delete failed: {}", id, e.getMessage());
        }
    }

    // The same id under another category: its timestamp and random suffix kept, its code swapped.
    // An id in an older format has no code to swap and is minted afresh.
    static String recategorized(String id, MemoryCategory category) {
        String[] parts = id.split("-");
        if (parts.length >= 3 && parts[0].length() == 19 && parts[1].length() == 1) {
            parts[1] = String.valueOf(category.code());
            return String.join("-", parts);
        }
        return newFactId(category);
    }

    // Deletes the single most-similar existing entry from BOTH stores when it's a near-duplicate of
    // the incoming text, so remember() can write the new text in its place. Raw dense search (not
    // recall()'s hybrid+rerank) so DEDUP_SIMILARITY_THRESHOLD is a real cosine value. A legacy entry
    // written before entries carried a "source" metadata key can't be targeted for deletion - in
    // that case the guard simply no-ops and the new entry is appended (best-effort, self-healing as
    // legacy entries age out).
    private void deleteNearDuplicate(String collectionId, String text) {
        List<Document> hits = vectorStoreService.search(
                List.of(collectionId), text, 1, DEDUP_SIMILARITY_THRESHOLD);
        if (hits.isEmpty()) return;
        String existingId = Objects.toString(hits.get(0).getMetadata().get(SOURCE_METADATA_KEY), null);
        if (existingId == null) return;
        vectorStoreService.deleteBySourceMetadata(collectionId, existingId);
        bm25IndexService.deleteDocuments(collectionId, existingId);
    }

    // Query-driven semantic recall - the full dense+BM25+RRF+rerank+MMR pipeline, scoped to this
    // user's one memory collection. Safe against a not-yet-created collection: both
    // VectorStoreService and BM25IndexService implementations tolerate a missing collection
    // (empty result, not an exception).
    //
    // Falls back to the keyword index alone when the hybrid pipeline fails - which it does when the
    // embedding server is slow or down, the one dependency the keyword index does not have.
    public List<MemoryEntry> recall(String userId, String query, int topK) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank()) return List.of();
        String collectionId = memoryCollectionId(userId);
        try {
            List<Document> results = searchService.search(List.of(collectionId), query, topK, DEFAULT_MIN_DENSE_SCORE);
            return results.stream()
                    .map(d -> entry(Objects.toString(d.getMetadata().get(SOURCE_METADATA_KEY), null), d.getText()))
                    .toList();
        } catch (Exception e) {
            log.warn("[Memory] semantic recall failed, answering from the keyword index: {}", e.getMessage());
            return bm25IndexService.search(List.of(collectionId), query, topK).stream()
                    .map(r -> entry(r.source(), r.text()))
                    .toList();
        }
    }

    /**
     * The most recent entries, newest first, optionally of one category - no query and no embedding
     * call, so it answers even when semantic recall cannot. The id's zero-padded timestamp is what
     * orders it (see newFactId).
     */
    public List<MemoryEntry> list(String userId, MemoryCategory category, int limit) {
        if (userId == null || userId.isBlank()) return List.of();
        return bm25IndexService.listAll(memoryCollectionId(userId), Integer.MAX_VALUE).stream()
                .map(r -> entry(r.source(), r.text()))
                .filter(e -> category == null || category.name().equals(e.category()))
                .sorted(java.util.Comparator.comparing(MemoryEntry::id, java.util.Comparator.nullsLast(
                        java.util.Comparator.reverseOrder())))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** One entry in full, by the id recall or list returned; empty when it is not this user's. */
    public java.util.Optional<MemoryEntry> read(String userId, String id) {
        if (userId == null || userId.isBlank() || id == null || id.isBlank()) return java.util.Optional.empty();
        List<String> chunks = bm25IndexService.getChunkTexts(memoryCollectionId(userId), id.strip());
        if (chunks.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(entry(id.strip(), String.join("\n", chunks)));
    }

    /**
     * One memory entry as the agent sees it. {@code savedAt} and {@code category} are read back out
     * of the id (see newFactId), so an entry written before either was recorded anywhere else still
     * has both - and an id in an older format simply yields nulls.
     */
    public record MemoryEntry(String id, String category, String savedAt, String text) {}

    static MemoryEntry entry(String id, String text) {
        String category = null;
        String savedAt = null;
        if (id != null) {
            String[] parts = id.split("-");
            if (parts.length >= 3 && parts[0].length() == 19 && parts[1].length() == 1) {
                try {
                    savedAt = Instant.ofEpochMilli(Long.parseLong(parts[0])).toString();
                } catch (NumberFormatException ignored) { /* not our format */ }
                MemoryCategory cat = MemoryCategory.fromCode(parts[1].charAt(0));
                category = cat != null ? cat.name() : null;
            }
        }
        return new MemoryEntry(id, category, savedAt, text);
    }

    public void deleteAllForUser(String userId) {
        if (userId == null || userId.isBlank()) return;
        String collectionId = memoryCollectionId(userId);
        vectorStoreService.deleteCollection(collectionId);
        bm25IndexService.deleteCollection(collectionId);
    }

    // Deterministic, salted UUID - distinct namespace from RegisteredConnector.id (a randomly
    // generated v4 UUID from the DB), so collision with a real connector collection is impossible
    // by construction. No new persisted mapping needed, unlike a connector's own stored id.
    private static String memoryCollectionId(String userId) {
        return UUID.nameUUIDFromBytes(("agent-memory:" + userId).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    // Format: "<019-epoch-millis>-<category-code>-<random8>". The zero-padded epoch-millis prefix
    // makes lexicographic order equal chronological order, so BM25IndexService.listAll()'s
    // sort-by-source-descending doubles as "most recent first" with no extra indexed field. The
    // middle segment records USER vs KNOWLEDGE straight in the id, so no separate indexed field is
    // needed to tell them apart (nothing reads it today - see MemoryCategory). Also doubles as the
    // entry's BM25 "source" / vector "source"-metadata identity key (the shared per-entry delete
    // key - see remember()/deleteNearDuplicate()).
    private static String newFactId(MemoryCategory category) {
        return String.format("%019d-%s-%s", Instant.now().toEpochMilli(), category.code(),
                UUID.randomUUID().toString().substring(0, 8));
    }

}
