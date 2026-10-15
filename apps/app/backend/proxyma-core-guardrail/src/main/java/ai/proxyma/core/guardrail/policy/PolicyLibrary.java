package ai.proxyma.core.guardrail.policy;

import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.ingest.service.DocumentConversionService;
import ai.proxyma.core.ingest.service.EmbeddingService;
import ai.proxyma.core.system.util.AppPaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The guardrail's policy corpus: the rules an operator writes down, and the index built from them.
 *
 * <p>THE INDEX IS SEPARATE, THE MACHINERY IS NOT. Everything here goes through the same conversion,
 * chunking and embedding path every other document takes - {@link DocumentConversionService} for
 * PDF and Office, then {@link EmbeddingService}, which writes the vector store and the BM25 index
 * together. What makes the corpus separate is the COLLECTION it lands in, {@link #RULES_COLLECTION} or {@link #EXAMPLES_COLLECTION},
 * and that is enough to make the isolation structural rather than a matter of care:
 *
 * <ul>
 *   <li>Chat search reaches collections through {@code ConnectorService.visibleCollectionIds()},
 *       which enumerates REGISTERED CONNECTORS. This collection belongs to no connector, so it can
 *       never appear there - a user cannot read the policy corpus by asking about it.</li>
 *   <li>A connector's collection id is a UUID with its dashes stripped: 32 hex characters. This
 *       one is not hex and is not 32 characters, so no connector can ever collide with it, however
 *       many are registered.</li>
 * </ul>
 *
 * <p>Duplicating the pipeline instead would have made the corpus chunk and embed differently from
 * everything else, for no stated reason, and would have been the thing that drifted.
 */
@Component
public class PolicyLibrary {

    private static final Logger log = LoggerFactory.getLogger(PolicyLibrary.class);

    /**
     * The one collection the policy corpus lives in.
     *
     * <p>Deliberately not hex and not 32 characters - see the class javadoc. Changing this string
     * orphans every already-indexed policy document, because nothing would look in the old
     * collection again; it would need a reindex, not a rename.
     */
    public static final String RULES_COLLECTION = "guardrailpolicy";

    /**
     * The examples corpus, in its own collection beside the rules one.
     *
     * <p>Separate because the two are matched by different mechanisms and must never be mixed: a
     * rule retrieved as if it were an example would be blocked on raw similarity, and an example
     * retrieved as if it were a rule would be handed to the classifier as though it were policy.
     * Same isolation argument as the rules collection - neither name is 32 hex characters, so no
     * connector collection can ever collide with either.
     */
    public static final String EXAMPLES_COLLECTION = "guardrailexamples";

    public static String collectionFor(PolicyDocument.Kind kind) {
        return kind == PolicyDocument.Kind.EXAMPLES ? EXAMPLES_COLLECTION : RULES_COLLECTION;
    }

    /**
     * A ceiling on one policy document.
     *
     * <p>This corpus is searched on EVERY turn. A 500 MB PDF dropped in here would not merely be
     * slow to index once - it would enlarge the thing every message in the product is matched
     * against. Policies are prose that people are expected to read; twenty megabytes is already
     * far past that.
     */
    public static final long MAX_DOCUMENT_BYTES = 20L * 1024 * 1024;

    private final Path root;
    private final Path filesDir;
    private final Path manifestFile;
    private final ObjectMapper objectMapper;
    private final DocumentConversionService conversionService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final BM25IndexService bm25IndexService;

    /**
     * ONE thread, on purpose. Indexing is convert-then-embed, both slow, and two of them running
     * at once would have two writers on the same Lucene index and the same collection. Serializing
     * costs nothing here: adding a policy document is something an operator does a handful of
     * times, not a pipeline under load.
     */
    private final ExecutorService indexer =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "guardrail-policy-indexer");
                t.setDaemon(true);
                return t;
            });

    private final Object manifestLock = new Object();

    public PolicyLibrary(@Value("${proxyma.guardrail.policies-dir:}") String policiesDir,
                         AppPaths appPaths,
                         ObjectMapper objectMapper,
                         DocumentConversionService conversionService,
                         EmbeddingService embeddingService,
                         VectorStoreService vectorStoreService,
                         BM25IndexService bm25IndexService) {
        // The property is set explicitly by both editions' properties files. The AppPaths fallback
        // is for a bare `java -jar` from an IDE, where neither is on the command line - it is NOT
        // correct for server mode, whose data root is a mounted volume rather than the platform's
        // application-data directory, which is exactly why the property exists.
        this.root = (policiesDir == null || policiesDir.isBlank())
                ? appPaths.dataRoot().resolve("guardrail-policies")
                : Path.of(policiesDir);
        this.filesDir = root.resolve("files");
        this.manifestFile = root.resolve("manifest.json");
        this.objectMapper = objectMapper;
        this.conversionService = conversionService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.bm25IndexService = bm25IndexService;
    }

    @PreDestroy
    void shutdown() {
        indexer.shutdownNow();
    }

    public Path root() { return root; }

    // -- Reading ---------------------------------------------------------------

    public List<PolicyDocument> list() {
        synchronized (manifestLock) {
            return loadManifest();
        }
    }

    /**
     * Whether anything is actually retrievable.
     *
     * <p>The semantic guardrail asks this before it searches. An empty corpus is not a violation
     * and not an error - it is a deployment that has not written any policy down, and it must cost
     * nothing per turn.
     */
    public boolean hasIndexedDocuments(PolicyDocument.Kind kind) {
        synchronized (manifestLock) {
            return loadManifest().stream()
                    .anyMatch(d -> d.kindOrDefault() == kind && d.status() == PolicyDocument.Status.INDEXED);
        }
    }

    /** Display name for an indexed sourceId, for a finding that has to name its source. */
    public Optional<String> displayNameForSource(String sourceId) {
        synchronized (manifestLock) {
            // An examples source id is "documentId#n" - one per example - so the document is
            // whatever sits before the separator. A rules source id is the document id itself.
            int hash = sourceId.indexOf(35);
            String documentId = hash < 0 ? sourceId : sourceId.substring(0, hash);
            return loadManifest().stream()
                    .filter(d -> d.id().equals(documentId))
                    .map(PolicyDocument::displayName)
                    .findFirst();
        }
    }

    /**
     * The stored bytes of a document, exactly as uploaded - for a configuration export, which
     * carries the policy corpus so an import can rebuild it. Empty when the document has no stored
     * file or it cannot be read.
     */
    public Optional<byte[]> readContent(String id) {
        PolicyDocument doc;
        synchronized (manifestLock) {
            doc = loadManifest().stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
        }
        if (doc == null || doc.fileName() == null) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(filesDir.resolve(doc.fileName())));
        } catch (IOException e) {
            log.warn("[GuardrailPolicy] could not read stored file for {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    // -- Adding ----------------------------------------------------------------

    /** Stores an uploaded file and queues it for indexing. */
    public PolicyDocument addFile(PolicyDocument.Kind kind, String originalName, byte[] content) throws IOException {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("The file is empty.");
        }
        if (content.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("A policy document may be at most "
                    + (MAX_DOCUMENT_BYTES / (1024 * 1024)) + " MB. This corpus is searched on every turn.");
        }
        String safeName = sanitizeFileName(originalName);
        String id = UUID.randomUUID().toString().replace("-", "");
        String stored = id + extensionOf(safeName);

        Files.createDirectories(filesDir);
        Files.write(filesDir.resolve(stored), content);

        PolicyDocument doc = new PolicyDocument(kind, id, safeName, stored, null, content.length,
                Instant.now(), null, PolicyDocument.Status.PENDING, null, 0);
        upsert(doc);
        queueIndex(id);
        return doc;
    }

    // THIS CLASS NO LONGER MAKES OUTBOUND REQUESTS AT ALL, as of 2026-09-09.
    //
    // There was an addUrl() here that fetched a page with Jsoup and stored the markdown it
    // rendered down to. Its UI field had already been removed, which closed the hole at the
    // surface and left a reachable POST that would fetch any address a caller named - server-side
    // request forgery in shape, pointed by whoever could reach the API rather than by the operator
    // whose policy corpus it is. The endpoint went with it; see GuardrailPolicyController.
    //
    // Deleting the method also took the last use of Jsoup, HtmlContentExtractor and the outbound
    // proxy out of this file, which is the part worth keeping: the class cannot now be talked into
    // a request, rather than being trusted not to make a bad one. Uploading the file was always
    // the better answer anyway - the old note here said as much, that a page needing JavaScript to
    // render is better saved and uploaded than scraped.

    // -- Removing and rebuilding -----------------------------------------------

    /** Removes a document from the corpus and from both halves of the index. */
    public boolean delete(String id) {
        PolicyDocument doc;
        synchronized (manifestLock) {
            List<PolicyDocument> all = loadManifest();
            doc = all.stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
            if (doc == null) return false;
            all.removeIf(d -> d.id().equals(id));
            saveManifest(all);
        }
        // After the manifest, never before: if this throws, the document is already gone from the
        // list and a reindex will not resurrect it, whereas the reverse order can leave a row
        // pointing at chunks that were already deleted. The chunk count travels from the removed
        // row: read back from the manifest, it was gone, and only examples #0-#63 were deleted.
        removeFromIndex(doc.kindOrDefault(), id, doc.chunkCount());
        if (doc.fileName() != null) {
            try {
                Files.deleteIfExists(filesDir.resolve(doc.fileName()));
            } catch (IOException e) {
                log.warn("[GuardrailPolicy] could not delete stored file for {}: {}", id, e.getMessage());
            }
        }
        return true;
    }

    /** Re-converts and re-embeds everything. For after an embedding-model change. */
    public void reindexAll() {
        for (PolicyDocument doc : list()) {
            synchronized (manifestLock) {
                upsert(doc.pending());
            }
            queueIndex(doc.id());
        }
    }

    // -- Indexing --------------------------------------------------------------

    private void queueIndex(String id) {
        indexer.submit(() -> {
            try {
                index(id);
            } catch (Throwable t) {
                // Never let this thread die: it is a single-thread executor, and an uncaught error
                // here would silently stop every later document from ever being indexed.
                log.warn("[GuardrailPolicy] indexing {} failed: {}", id, t.toString());
                markFailed(id, describe(t));
            }
        });
    }

    private void index(String id) throws IOException {
        PolicyDocument doc = list().stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
        if (doc == null || doc.fileName() == null) return;

        Path file = filesDir.resolve(doc.fileName());
        if (!Files.exists(file)) {
            markFailed(id, "The stored file is missing.");
            return;
        }
        if (!conversionService.canConvert(file, DocumentConversionService.Surface.INDEXING)) {
            markFailed(id, "This file type cannot be converted with the current Conversion settings.");
            return;
        }

        DocumentConversionService.Converted converted =
                conversionService.convert(file, DocumentConversionService.Surface.INDEXING);
        String markdown = converted.content();
        if (markdown == null || markdown.isBlank()) {
            markFailed(id, "The document converted to no text at all.");
            return;
        }

        // sourceId is the policy id, NOT the path on disk. Two reasons, and the second is the one
        // that matters: the id is stable across a rename, and no absolute filesystem path ever
        // enters the index - so a retrieved policy excerpt cannot leak the server's directory
        // layout into a classifier prompt or a finding.
        int chunks = doc.kindOrDefault() == PolicyDocument.Kind.EXAMPLES
                ? indexExamples(id, markdown)
                : embeddingService.chunkAndEmbed(
                        collectionFor(PolicyDocument.Kind.RULES), id, markdown, null, 0,
                        doc.sourceUrl(), null, null).size();

        synchronized (manifestLock) {
            List<PolicyDocument> all = loadManifest();
            all.replaceAll(d -> d.id().equals(id) ? d.indexed(chunks) : d);
            saveManifest(all);
        }
        log.info("[GuardrailPolicy] indexed '{}' ({} chunks, via {})",
                doc.displayName(), chunks, converted.method());
    }

    /**
     * Indexes an examples document as ONE VECTOR PER EXAMPLE, not one per chunk of prose.
     *
     * <p>THIS IS THE DIFFERENCE BETWEEN THE FEATURE WORKING AND NOT. Measured before it existed: a
     * file holding five known extraction attempts was chunked as ordinary prose into a SINGLE
     * chunk, and a message quoting one of those attempts VERBATIM scored around 0.5 against it
     * rather than near 1.0 - because it was being compared against a blob containing four other
     * attacks as well. The similarity was diluted by everything the message was not about, and the
     * only way to make it fire was a threshold so low that unrelated messages fired too.
     *
     * <p>So each example is embedded on its own, which is what every implementation of this
     * actually does: Guardrails AI stores individual jailbreak prompts, NeMo Guardrails stores
     * individual sample utterances per canonical form. An example is a MESSAGE, and a message is
     * the unit of comparison.
     *
     * <p>Split on blank lines, falling back to one per line for a file that uses none. Both are
     * how somebody naturally writes a list of prompts, and the UI says so.
     *
     * <p>Each example gets its own sourceId, {@code documentId#n}, so deletion by document prefix
     * still removes all of them and a finding can still name the document it came from - see
     * {@link #displayNameForSource}.
     */
    private int indexExamples(String documentId, String markdown) {
        List<String> examples = splitExamples(markdown);
        if (examples.isEmpty()) return 0;
        // Everything under this document id is replaced, so a re-index does not leave the previous
        // split's vectors behind beside the new one.
        int previous = 0;
        synchronized (manifestLock) {
            List<PolicyDocument> all = loadManifest();
            for (PolicyDocument d : all) {
                if (d.id().equals(documentId)) previous = d.chunkCount();
            }
            // The bound a later delete needs, recorded BEFORE anything is written: a run that fails
            // part way still leaves every example it wrote within reach of a delete.
            all.replaceAll(d -> d.id().equals(documentId) ? d.withChunkBound(examples.size()) : d);
            saveManifest(all);
        }
        removeFromIndex(PolicyDocument.Kind.EXAMPLES, documentId, previous);
        int indexed = 0;
        for (int i = 0; i < examples.size(); i++) {
            embeddingService.chunkAndEmbed(EXAMPLES_COLLECTION, documentId + "#" + i, examples.get(i));
            indexed++;
        }
        return indexed;
    }

    /** Blank-line-separated blocks, or one per line when the file has no blank lines. */
    static List<String> splitExamples(String markdown) {
        List<String> byBlock = new ArrayList<>();
        for (String block : markdown.split("\\r?\\n\\s*\\r?\\n")) {
            String trimmed = block.strip();
            if (!trimmed.isEmpty()) byBlock.add(trimmed);
        }
        if (byBlock.size() > 1) return byBlock;

        List<String> byLine = new ArrayList<>();
        for (String line : markdown.split("\\r?\\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) byLine.add(trimmed);
        }
        // A single unbroken paragraph is one example, not one per line of wrapped text - so the
        // line split only wins when it actually found more than the block split did.
        return byLine.size() > byBlock.size() ? byLine : byBlock;
    }

    /**
     * Removes everything a document put in the index.
     *
     * <p>An EXAMPLES document is many source ids - one per example, "documentId#n" - so removing it
     * means removing each. The bound is the recorded chunk count with a floor, because a delete
     * for a source id that is not there is a no-op and under-deleting would leave orphaned vectors
     * that still block messages after the document they came from was removed.
     */
    private void removeFromIndex(PolicyDocument.Kind kind, String id, int known) {
        String collection = collectionFor(kind);
        List<String> sourceIds = new ArrayList<>();
        if (kind == PolicyDocument.Kind.EXAMPLES) {
            int bound = Math.max(known, 0) + 64;
            for (int i = 0; i < bound; i++) sourceIds.add(id + "#" + i);
        } else {
            sourceIds.add(id);
        }
        for (String sourceId : sourceIds) {
            try {
                vectorStoreService.deleteBySourceMetadata(collection, sourceId);
            } catch (Exception e) {
                log.debug("[GuardrailPolicy] vector delete failed for {}: {}", sourceId, e.getMessage());
            }
            try {
                bm25IndexService.deleteDocuments(collection, sourceId);
            } catch (Exception e) {
                log.debug("[GuardrailPolicy] BM25 delete failed for {}: {}", sourceId, e.getMessage());
            }
        }
    }

    private void markFailed(String id, String message) {
        synchronized (manifestLock) {
            List<PolicyDocument> all = loadManifest();
            all.replaceAll(d -> d.id().equals(id) ? d.failed(message) : d);
            saveManifest(all);
        }
    }

    // -- Manifest --------------------------------------------------------------
    // Both of these are called only with manifestLock held.

    // The manifest as last read or written. Every input check with examples or the semantic layer
    // on asked it whether anything is indexed, and re-read and re-parsed the file to answer. Only
    // this class writes the file, so its own copy is the truth until it writes again.
    private List<PolicyDocument> cachedManifest;

    private List<PolicyDocument> loadManifest() {
        if (cachedManifest != null) return new ArrayList<>(cachedManifest);
        if (!Files.exists(manifestFile)) return new ArrayList<>();
        try {
            List<PolicyDocument> read = objectMapper.readValue(
                    Files.readString(manifestFile, StandardCharsets.UTF_8),
                    new TypeReference<List<PolicyDocument>>() {});
            cachedManifest = List.copyOf(read);
            return new ArrayList<>(read);
        } catch (Exception e) {
            // Reported and treated as empty rather than thrown: a manifest this cannot read must
            // not stop the application booting, and the guardrail degrades to its rule layer.
            log.error("[GuardrailPolicy] manifest at {} is unreadable, treating the corpus as empty: {}",
                    manifestFile, e.getMessage());
            return new ArrayList<>();
        }
    }

    // Written whole to a temporary file and moved over the old one. Written in place, a crash part
    // way left a truncated manifest, which reads as an EMPTY corpus - every policy invisible while
    // its files and vectors stayed.
    private void saveManifest(List<PolicyDocument> documents) {
        try {
            Files.createDirectories(root);
            Path temp = manifestFile.resolveSibling(manifestFile.getFileName() + ".tmp");
            Files.writeString(temp,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(documents),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, manifestFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, manifestFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            cachedManifest = List.copyOf(documents);
        } catch (Exception e) {
            log.error("[GuardrailPolicy] could not write the manifest: {}", e.getMessage());
        }
    }

    private void upsert(PolicyDocument doc) {
        synchronized (manifestLock) {
            List<PolicyDocument> all = loadManifest();
            all.removeIf(d -> d.id().equals(doc.id()));
            all.add(doc);
            saveManifest(all);
        }
    }

    // -- Helpers ---------------------------------------------------------------

    /**
     * Reduces an uploaded name to something that can only ever be a display label.
     *
     * <p>The stored filename is generated from a UUID, so this never decides where anything is
     * written - but the name is echoed back to a client and put in a log line, and a name carrying
     * path separators is the shape of a mistake waiting for the day somebody uses it as a path.
     */
    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "policy";
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) return "policy";
        return base.length() > 200 ? base.substring(0, 200) : base;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,10}") ? ext : "";
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
