package ai.proxyma.core.registry.ingest;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.files.FileTypeSupport;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.registry.event.ConnectorRemovedEvent;
import ai.proxyma.core.registry.event.DocumentIndexedEvent;
import ai.proxyma.core.registry.files.upload.controller.FileController;
import ai.proxyma.core.registry.model.IngestedDocument;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.IngestTokenRepository;
import ai.proxyma.core.registry.repository.IngestedDocumentRepository;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.registry.service.SyncOrchestratorService;
import ai.proxyma.core.registry.service.SyncService;
import ai.proxyma.core.system.util.SensitivePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a custom source holds, changed by the people and tools allowed to: store a document (a text
 * or a file), replace it by its sender's id, delete it, list them. The ingestion API's endpoints and
 * the agent's saveToSource both come here, already authorized - this decides what a document may
 * be, not who may send one.
 *
 * <p>Each change is stored first (the row and its body), then handed to the sync engine as the item
 * a listing would have produced (SyncOrchestratorService.recordPushed); a delete removes the
 * document's index entries at once (SyncService.deleteTrackedDocument). See CustomSourcePlugin for
 * why there is no second indexing path.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** How many documents one batch request may carry. */
    public static final int MAX_BATCH = 100;
    /** How many documents one source may hold - far past what a person browses, short of a table no pass can list. */
    public static final int MAX_DOCUMENTS_PER_SOURCE = 100_000;
    /** The largest file one push may carry - an upload's limit, the one the Sources page's uploads have. */
    public static final long MAX_FILE_BYTES = FileController.MAX_FILE_SIZE_BYTES;
    /** A page of the document list, unless the caller asks for fewer; never more than {@link #MAX_PAGE_SIZE}. */
    public static final int PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final RegisteredConnectorRepository sources;
    private final IngestedDocumentRepository documents;
    private final IngestTokenRepository tokens;
    private final TrackedDocumentRepository tracked;
    private final IngestStore store;
    private final SyncOrchestratorService orchestrator;
    private final SyncService syncService;
    private final ConnectorCatalog catalog;

    // One lock per source around its writes: an id read as absent by two pushes at once would be
    // inserted twice, and the second insert refused by the unique key after its body was stored.
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public IngestService(RegisteredConnectorRepository sources, IngestedDocumentRepository documents,
                         IngestTokenRepository tokens, TrackedDocumentRepository tracked, IngestStore store,
                         SyncOrchestratorService orchestrator, SyncService syncService, ConnectorCatalog catalog) {
        this.sources = sources;
        this.documents = documents;
        this.tokens = tokens;
        this.tracked = tracked;
        this.store = store;
        this.orchestrator = orchestrator;
        this.syncService = syncService;
        this.catalog = catalog;
    }

    /** What happened to one document: created, replaced, or unchanged (the same content again). */
    public record Saved(String id, String outcome) {}

    /** A file a request carried, spooled to {@code path} by the caller, who deletes it afterwards. */
    public record SpooledFile(Path path, String fileName, long size) {}

    /** Whether documents are pushed to sources of this type. */
    public boolean isPushType(ConnectorType type) {
        ConnectorDescriptor d = type == null ? null : catalog.descriptor(type);
        return d != null && d.capabilities().push();
    }

    // -- Storing ------------------------------------------------------------------------------------

    /** Stores one text document, or replaces the one with its id. */
    public Saved upsertText(RegisteredConnector source, IngestDraft draft) {
        Prepared p = prepareText(draft);
        List<Saved> saved = store(source, List.of(p));
        return saved.get(0);
    }

    /**
     * Stores several text documents in one request. Every one is checked before any is stored, so a
     * batch is taken whole or refused whole - a script retrying a refused batch does not store half
     * of it twice. The refusal names the document by its place in the batch.
     */
    public List<Saved> upsertBatch(RegisteredConnector source, List<IngestDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) throw new IngestRefusedException("documents is required - a list of documents.");
        if (drafts.size() > MAX_BATCH) {
            throw new IngestRefusedException.TooLarge("A batch may carry at most " + MAX_BATCH
                    + " documents. Send the rest in another request.");
        }
        List<Prepared> prepared = new ArrayList<>(drafts.size());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < drafts.size(); i++) {
            try {
                Prepared p = prepareText(drafts.get(i));
                if (!ids.add(p.externalId)) {
                    throw new IngestRefusedException("The batch carries this id twice.");
                }
                prepared.add(p);
            } catch (IngestRefusedException e) {
                throw new BatchRefusedException(i, e);
            }
        }
        return store(source, prepared);
    }

    /** Stores one file document - its text extracted when it is indexed, as for an upload. */
    public Saved upsertFile(RegisteredConnector source, IngestDraft draft, SpooledFile file) {
        String externalId = draft.checkedExternalId();
        String fileName = file.fileName() == null ? "" : IngestDraft.oneLine(lastSegment(file.fileName()));
        if (fileName.isEmpty()) throw new IngestRefusedException("The file has no name. Send it with its file name.");
        if (file.size() <= 0) throw new IngestRefusedException("The file is empty.");
        if (file.size() > MAX_FILE_BYTES) {
            throw new IngestRefusedException.TooLarge("The file is larger than " + (MAX_FILE_BYTES / (1024 * 1024)) + " MB.");
        }
        if (!FileTypeSupport.isSupportedFileName(fileName, null)) {
            throw new IngestRefusedException("Proxyma does not index this type of file. Send PDF, Office, text, "
                    + "markdown, HTML or CSV files, or the document's text instead.");
        }
        String title = draft.checkedTitle(fileName);
        String url = draft.checkedUrl();
        String author = draft.checkedAuthor();
        Instant created = draft.checkedCreatedAt();
        Instant updated = draft.checkedUpdatedAt();
        String metadata = draft.checkedMetadataJson();
        String fileHash = FileTypeSupport.computeFileHash(file.path());
        if (fileHash == null) throw new UncheckedIOException(new IOException("The file could not be read."));
        String hash = IngestDraft.sha256(IngestDraft.utf8(fileHash), IngestDraft.utf8(fileName), IngestDraft.utf8(title),
                IngestDraft.utf8(url), IngestDraft.utf8(author), IngestDraft.utf8(String.valueOf(created)),
                IngestDraft.utf8(String.valueOf(updated)), IngestDraft.utf8(metadata));
        Prepared p = new Prepared(externalId, title, url, author, created, updated, metadata,
                FileTypeSupport.resolveMimeType(fileName), fileName, file.size(), hash, null, file.path());
        return store(source, List.of(p)).get(0);
    }

    private static String lastSegment(String name) {
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        return slash >= 0 ? n.substring(slash + 1) : n;
    }

    private Prepared prepareText(IngestDraft draft) {
        String externalId = draft.checkedExternalId();
        String title = draft.checkedTitle(null);
        String text = draft.checkedText();
        boolean plain = draft.plainText();
        String url = draft.checkedUrl();
        String author = draft.checkedAuthor();
        Instant created = draft.checkedCreatedAt();
        Instant updated = draft.checkedUpdatedAt();
        String metadata = draft.checkedMetadataJson();
        byte[] body = IngestDraft.utf8(render(title, url, author, created, updated, metadata, text, plain));
        // Everything that reaches the index is in the rendered body - the header carries the title,
        // the link, the author, the dates and the metadata - so its hash is the content's.
        String hash = IngestDraft.sha256(body);
        return new Prepared(externalId, title, url, author, created, updated, metadata,
                plain ? "text/plain" : "text/markdown", null, body.length, hash, body, null);
    }

    /**
     * The document as it is indexed: a header naming what the sender said about it, then its text. The
     * header is what makes "the release notes Anna wrote in March" findable - search reads text, and
     * the fields beside a text are not text unless they are written into it.
     */
    static String render(String title, String url, String author, Instant created, Instant updated,
                         String metadataJson, String text, boolean plain) {
        StringBuilder out = new StringBuilder();
        out.append(plain ? "" : "# ").append(title).append("\n\n");
        List<String> facts = new ArrayList<>();
        if (author != null) facts.add("Author: " + author);
        if (created != null) facts.add("Created: " + created);
        if (updated != null) facts.add("Updated: " + updated);
        if (url != null) facts.add("Link: " + url);
        IngestDraft.parseMetadata(metadataJson).forEach((k, v) -> facts.add(k + ": " + v));
        if (!facts.isEmpty()) {
            for (String f : facts) out.append(plain ? "" : "- ").append(f).append('\n');
            out.append('\n');
        }
        out.append(text.strip()).append('\n');
        return out.toString();
    }

    // One document, checked and ready to store - with its body in memory (a text) or spooled (a file).
    private record Prepared(String externalId, String title, String url, String author, Instant created,
                            Instant updated, String metadataJson, String contentType, String fileName, long size,
                            String hash, byte[] body, Path spooled) {}

    private List<Saved> store(RegisteredConnector source, List<Prepared> prepared) {
        UUID sourceId = source.getId();
        List<Saved> out = new ArrayList<>(prepared.size());
        List<ItemMetadata> changed = new ArrayList<>();
        synchronized (locks.computeIfAbsent(sourceId, k -> new Object())) {
            long held = -1;
            for (Prepared p : prepared) {
                IngestedDocument row = documents.findByConnectorIdAndExternalId(sourceId, p.externalId).orElse(null);
                if (row != null && p.hash.equals(row.getContentHash())) {
                    out.add(new Saved(p.externalId, "unchanged"));
                    continue;
                }
                if (row == null) {
                    if (held < 0) held = documents.countByConnectorId(sourceId);
                    if (held >= MAX_DOCUMENTS_PER_SOURCE) {
                        throw new IngestRefusedException.TooLarge("This source holds " + MAX_DOCUMENTS_PER_SOURCE
                                + " documents, the most one source may. Delete some, or push to another source.");
                    }
                    held++;
                }
                String previousBody = row == null ? null : row.getStoredAs();
                String storedAs = p.body != null ? store.keep(sourceId, p.body) : store.keep(sourceId, p.spooled);
                boolean created = row == null;
                if (created) {
                    row = new IngestedDocument();
                    row.setConnectorId(sourceId);
                    row.setExternalId(p.externalId);
                }
                row.setTitle(p.title);
                row.setUrl(p.url);
                row.setAuthor(p.author);
                row.setSourceCreatedAt(p.created);
                row.setSourceUpdatedAt(p.updated);
                row.setContentType(p.contentType);
                row.setFileName(p.fileName);
                row.setSizeBytes(p.size);
                row.setContentHash(p.hash);
                row.setMetadataJson(p.metadataJson);
                row.setStoredAs(storedAs);
                row.setReceivedAt(Instant.now());
                try {
                    row = documents.save(row);
                } catch (RuntimeException e) {
                    store.delete(sourceId, storedAs);
                    throw e;
                }
                if (previousBody != null) store.delete(sourceId, previousBody);
                changed.add(CustomSourcePlugin.item(row));
                out.add(new Saved(p.externalId, created ? "created" : "replaced"));
            }
        }
        if (!changed.isEmpty()) orchestrator.recordPushed(sourceId, changed);
        return out;
    }

    // -- Deleting -----------------------------------------------------------------------------------

    /**
     * Deletes one document: its row, its body, and - at once - its passages from the keyword index and
     * the vectors, and what the knowledge graph learned from it. False when the source holds no
     * document with that id.
     */
    public boolean delete(RegisteredConnector source, String externalId) {
        String id = IngestDraft.checkExternalId(externalId);
        UUID sourceId = source.getId();
        IngestedDocument row;
        synchronized (locks.computeIfAbsent(sourceId, k -> new Object())) {
            row = documents.findByConnectorIdAndExternalId(sourceId, id).orElse(null);
            if (row == null) return false;
            documents.delete(row);
            store.delete(sourceId, row.getStoredAs());
        }
        syncService.deleteTrackedDocument(sourceId, CustomSourcePlugin.ITEM_PREFIX + id);
        return true;
    }

    // -- Listing ------------------------------------------------------------------------------------

    /** One page of a source's documents, newest first, each with where its indexing stands. */
    public Map<String, Object> list(RegisteredConnector source, int page, int size) {
        int pageSize = Math.max(1, Math.min(size <= 0 ? PAGE_SIZE : size, MAX_PAGE_SIZE));
        long total = documents.countByConnectorId(source.getId());
        int totalPages = (int) Math.max(1, (total + pageSize - 1) / pageSize);
        // Clamped rather than refused, as every paged list in the product is: past the end is the
        // last page, before the start the first.
        int p = Math.max(0, Math.min(page, totalPages - 1));
        Page<IngestedDocument> rows = documents.findByConnectorIdOrderByReceivedAtDescIdAsc(source.getId(),
                PageRequest.of(p, pageSize));
        List<Map<String, Object>> items = new ArrayList<>();
        for (IngestedDocument d : rows.getContent()) items.add(describe(source.getId(), d));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", p);
        body.put("size", pageSize);
        body.put("totalItems", total);
        body.put("totalPages", totalPages);
        body.put("hasMore", p < totalPages - 1);
        return body;
    }

    private Map<String, Object> describe(UUID sourceId, IngestedDocument d) {
        TrackedDocument t = tracked.findBySourceIdAndConnectorId(CustomSourcePlugin.ITEM_PREFIX + d.getExternalId(),
                sourceId).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getExternalId());
        m.put("title", d.getTitle());
        m.put("url", d.getUrl());
        m.put("author", d.getAuthor());
        m.put("createdAt", d.getSourceCreatedAt() == null ? null : d.getSourceCreatedAt().toString());
        m.put("updatedAt", d.getSourceUpdatedAt() == null ? null : d.getSourceUpdatedAt().toString());
        m.put("receivedAt", d.getReceivedAt().toString());
        m.put("contentType", d.getContentType());
        m.put("fileName", d.getFileName());
        m.put("sizeBytes", d.getSizeBytes());
        m.put("metadata", IngestDraft.parseMetadata(d.getMetadataJson()));
        // Where its indexing stands - the tracked item's own status, so this list and the source's
        // page never disagree. RECEIVED: stored, and not yet picked up by the sync engine.
        String status = t == null || t.getStatus() == TrackedDocument.Status.DELETED ? "RECEIVED" : t.getStatus().name();
        m.put("status", status);
        m.put("failure", t != null && t.getStatus() == TrackedDocument.Status.FAILED
                ? SensitivePaths.scrub(t.getFailureMessage()) : null);
        m.put("indexedAt", t != null && t.getLastIndexedAt() != null ? t.getLastIndexedAt().toString() : null);
        return m;
    }

    /** Reads a stored text document's body - for tests and the agent, never a file's bytes. */
    public String readText(RegisteredConnector source, String externalId) throws IOException {
        IngestedDocument row = documents.findByConnectorIdAndExternalId(source.getId(), externalId).orElse(null);
        if (row == null || row.getFileName() != null) return null;
        try (InputStream in = store.open(source.getId(), row.getStoredAs())) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // -- Keeping it consistent ----------------------------------------------------------------------

    /**
     * A version pushed while the previous one was being indexed: the drain that indexed the previous
     * one saves its row at the end, with the previous content hash, over the new one - so the row
     * reads as indexed and up to date, and no listing would requeue it before the daily full pass.
     * Told by the hashes disagreeing once the drain is done; recorded again, it is indexed again.
     */
    @EventListener
    public void onDocumentIndexed(DocumentIndexedEvent event) {
        String externalId = CustomSourcePlugin.externalIdOf(event.getSourceId());
        if (externalId == null || event.getConnectorId() == null) return;
        try {
            IngestedDocument row = documents.findByConnectorIdAndExternalId(event.getConnectorId(), externalId).orElse(null);
            if (row == null) return;
            TrackedDocument t = tracked.findById(event.getDocumentId()).orElse(null);
            if (t == null || Objects.equals(t.getContentHash(), row.getContentHash())) return;
            log.info("[Ingest] A newer version of id={} arrived while it was indexed - indexing it again", externalId);
            orchestrator.recordPushed(event.getConnectorId(), List.of(CustomSourcePlugin.item(row)));
        } catch (RuntimeException e) {
            log.warn("[Ingest] Could not check id={} for a newer version: {}", externalId, e.getMessage());
        }
    }

    /** A removed source takes its documents' rows, bodies and tokens with it. */
    @EventListener
    public void onSourceRemoved(ConnectorRemovedEvent event) {
        if (!isPushType(event.getConnectorType())) return;
        UUID id = event.getConnectorId();
        try {
            tokens.deleteByConnectorId(id);
            documents.deleteByConnectorId(id);
        } catch (RuntimeException e) {
            log.warn("[Ingest] Could not delete the records of removed source={}: {}", id, e.getMessage());
        }
        store.deleteSource(id);
        locks.remove(id);
    }

    /** A batch refused because one document in it was: which one, and why. */
    public static class BatchRefusedException extends IngestRefusedException {
        private final int index;
        private final IngestRefusedException cause;

        BatchRefusedException(int index, IngestRefusedException cause) {
            super(cause.getMessage());
            this.index = index;
            this.cause = cause;
        }

        /** The refused document's place in the batch, from 0. */
        public int index() {
            return index;
        }

        @Override
        public int status() {
            return cause.status();
        }
    }

    /** The source a caller names, if it is a push source that is not being deleted. */
    public RegisteredConnector pushSource(UUID sourceId) {
        RegisteredConnector r = sourceId == null ? null : sources.findById(sourceId).orElse(null);
        if (r == null || r.isDeleting() || !isPushType(r.getConnectorType())) return null;
        return r;
    }

    static boolean deleteQuietly(Path path) {
        try {
            return path != null && Files.deleteIfExists(path);
        } catch (IOException e) {
            return false;
        }
    }
}
