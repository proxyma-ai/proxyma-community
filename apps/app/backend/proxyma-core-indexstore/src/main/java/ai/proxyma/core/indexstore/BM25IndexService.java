package ai.proxyma.core.indexstore;

import ai.proxyma.core.system.util.AppPaths;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class BM25IndexService {

    private static final Logger log = LoggerFactory.getLogger(BM25IndexService.class);

    static final String FIELD_SOURCE         = "source";
    static final String FIELD_CONTENT        = "content";
    static final String FIELD_TEXT           = "text";
    static final String FIELD_URL            = "url";
    static final String FIELD_CONNECTOR_TYPE = "connectorType";

    private final Path indexRoot;

    // Every method here opens its own independent FSDirectory/IndexWriter/DirectoryReader against
    // a collection's directory - Lucene's write.lock only stops two IndexWriters overlapping, it
    // does NOT protect an independent DirectoryReader (e.g. a live search) from having its
    // underlying segment files deleted mid-read by a concurrent writer's commit cleanup (the
    // default KeepOnlyLastCommitDeletionPolicy deletes now-superseded segment files as soon as a
    // newer commit lands, with no cross-instance reference counting for readers that weren't
    // opened from that same writer). One lock per collectionId keeps a WRITE apart from everything
    // else on that collection, while leaving different collections free to run fully in parallel.
    // Never evicted - bounded by the number of distinct collections ever seen, same lifetime
    // tradeoff as drainingFlags in SyncOrchestratorService.
    //
    // A READ-WRITE LOCK, since 2026-10-03. It was one exclusive lock, taken by a search as well,
    // so on a source shared across an organization every user's keyword search waited for every
    // other's - and one that waited sixty seconds skipped the collection, which shows up as a
    // worse answer rather than as an error. The hazard above is a reader against a WRITER; two
    // readers do nothing to each other. Searches share the read side, writes take the write side
    // (still reentrant - renameSource calls deleteDocuments and addDocuments under its own).
    // Each search still opens its own reader; a SearcherManager over one long-lived writer would
    // save that too and is the larger change.
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> collectionLocks = new ConcurrentHashMap<>();

    // Default to the AppPaths data root rather than ${user.home}: the JVM's user.home ignores a
    // container's HOME env (it resolves via getpwuid to /root), which silently placed indexes in
    // the ephemeral container layer instead of the mounted data volume - every redeploy wiped all
    // BM25 indexes. All data must live inside the data root.
    public BM25IndexService(@Value("${proxyma.lucene.index-dir:}") String indexDir,
                            AppPaths appPaths) {
        this.indexRoot = (indexDir == null || indexDir.isBlank())
                ? appPaths.luceneRoot()
                : Paths.get(indexDir);
    }

    // Package-private for BM25ConcurrentSearchTest.
    ReentrantReadWriteLock lockFor(String collectionId) {
        return collectionLocks.computeIfAbsent(collectionId, k -> new ReentrantReadWriteLock());
    }

    // Absolute directory holding every collection's Lucene index - exposed so other modules
    // (e.g. server backup) reference the same resolved location instead of re-deriving it.
    public Path getIndexRoot() {
        return indexRoot;
    }

    public void addDocuments(String collectionId, String sourcePath, List<String> chunkTexts, String url) {
        addDocuments(collectionId, sourcePath, chunkTexts, url, null);
    }

    public void addDocuments(String collectionId, String sourcePath, List<String> chunkTexts, String url, String connectorType) {
        if (chunkTexts == null || chunkTexts.isEmpty()) return;
        Lock lock = lockFor(collectionId).writeLock();
        lock.lock();
        try {
            Path dir = indexDir(collectionId);
            Files.createDirectories(dir);
            try (StandardAnalyzer analyzer = new StandardAnalyzer();
                 FSDirectory directory = FSDirectory.open(dir);
                 IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                writer.deleteDocuments(new Term(FIELD_SOURCE, sourcePath));
                for (String text : chunkTexts) {
                    org.apache.lucene.document.Document doc = new org.apache.lucene.document.Document();
                    doc.add(new StringField(FIELD_SOURCE, sourcePath, Field.Store.YES));
                    doc.add(new TextField(FIELD_CONTENT, text, Field.Store.NO));
                    doc.add(new StoredField(FIELD_TEXT, text));
                    if (url != null && !url.isBlank()) doc.add(new StoredField(FIELD_URL, url));
                    if (connectorType != null && !connectorType.isBlank()) doc.add(new StoredField(FIELD_CONNECTOR_TYPE, connectorType));
                    writer.addDocument(doc);
                }
                writer.commit();
            }
            log.debug("[BM25] addDocuments: collection={} source='{}' chunks={}", collectionId, sourcePath, chunkTexts.size());
        } catch (IOException e) {
            // THROWN, not logged (since 2026-10-14). A disk that is full, a segment an antivirus
            // scanner holds open, a corrupt index: logged here, the document still completed with no
            // keyword entries - unfindable by a ticket key or an error code, and never retried.
            // Read paths below stay tolerant; a write that did not happen must say so.
            throw new java.io.UncheckedIOException("Could not write the keyword index of collection "
                    + collectionId, e);
        } finally {
            lock.unlock();
        }
    }

    public void deleteDocuments(String collectionId, String sourcePath) {
        Path dir = indexDir(collectionId);
        if (!Files.exists(dir)) return;
        Lock lock = lockFor(collectionId).writeLock();
        lock.lock();
        try (StandardAnalyzer analyzer = new StandardAnalyzer();
             FSDirectory directory = FSDirectory.open(dir);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            writer.deleteDocuments(new Term(FIELD_SOURCE, sourcePath));
            writer.commit();
            log.debug("[BM25] deleteDocuments: collection={} source='{}'", collectionId, sourcePath);
        } catch (IOException e) {
            // Thrown for the same reason as addDocuments': a delete that silently failed left a
            // replaced document's old keyword entries behind.
            throw new java.io.UncheckedIOException("Could not delete from the keyword index of collection "
                    + collectionId, e);
        } finally {
            lock.unlock();
        }
    }

    public void deleteCollection(String collectionId) {
        Path dir = indexDir(collectionId);
        if (!Files.exists(dir)) return;
        Lock lock = lockFor(collectionId).writeLock();
        lock.lock();
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            log.debug("[BM25] deleteCollection: {}", collectionId);
        } catch (IOException e) {
            log.warn("[BM25] Failed to delete collection {}: {}", collectionId, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // Swaps a freshly-built "shadow" collection directory in as the live one: the live directory is
    // MOVED ASIDE, the shadow moved into its place, and only then is the old one deleted. Under both
    // collections' write locks, and every reader checks for the directory under its read lock, so
    // no search sees the moment between the two moves.
    //
    // It deleted the live directory file by file first, ignoring a file it could not delete, and
    // then moved the shadow onto it - so a file Windows would not let go of (an antivirus scan, a
    // reader in another process) left the live index half deleted, and the move failed onto the
    // remains. A failed move now puts the live directory back.
    public void swapCollection(String liveId, String shadowId) {
        Path liveDir = indexDir(liveId);
        Path shadowDir = indexDir(shadowId);
        // Two distinct collection IDs - lock both, always in the same (sorted) order regardless
        // of which is "live"/"shadow" here, so a hypothetical concurrent swap referencing the same
        // pair in reverse can never deadlock against this one.
        String first = liveId.compareTo(shadowId) <= 0 ? liveId : shadowId;
        String second = liveId.compareTo(shadowId) <= 0 ? shadowId : liveId;
        Lock lock1 = lockFor(first).writeLock();
        Lock lock2 = lockFor(second).writeLock();
        lock1.lock();
        lock2.lock();
        try {
            if (!Files.exists(shadowDir)) {
                log.warn("[BM25] swapCollection: shadow directory for collection={} does not exist - nothing to swap into live={}", shadowId, liveId);
                return;
            }
            Path retired = null;
            if (Files.exists(liveDir)) {
                retired = liveDir.resolveSibling(liveId + ".retired-" + System.nanoTime());
                Files.move(liveDir, retired);
            }
            try {
                Files.move(shadowDir, liveDir);
            } catch (IOException moveFailed) {
                if (retired != null) {
                    try {
                        Files.move(retired, liveDir);
                    } catch (IOException restoreFailed) {
                        moveFailed.addSuppressed(restoreFailed);
                    }
                }
                throw moveFailed;
            }
            if (retired != null) deleteTree(retired);
            log.info("[BM25] swapCollection: {} -> {}", shadowId, liveId);
        } catch (IOException e) {
            log.error("[BM25] swapCollection failed shadow={} -> live={}: {}", shadowId, liveId, e.getMessage(), e);
            throw new VectorStoreException("BM25 collection swap failed: " + e.getMessage(), e);
        } finally {
            lock2.unlock();
            lock1.unlock();
        }
    }

    // Best effort: a retired index nobody reads any more. What cannot be deleted now is logged and
    // left, rather than failing a swap that has already succeeded.
    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("[BM25] could not delete {} of a retired index: {}", p.getFileName(), e.getMessage());
                }
            });
        } catch (IOException | java.io.UncheckedIOException e) {
            log.warn("[BM25] could not delete the retired index {}: {}", dir.getFileName(), e.getMessage());
        }
    }

    public boolean isStale(String collectionId) {
        return !Files.exists(indexDir(collectionId));
    }

    // Total on-disk size in bytes of every collection's Lucene index directory combined -
    // used for the Admin System tab's storage breakdown. Best effort - see DirectorySize.
    public long getTotalSizeBytes() {
        return DirectorySize.of(indexRoot);
    }

    // Approximate on-disk size in bytes for one collection's Lucene index directory - used to
    // compute a single owner's (e.g. one user's) BM25 storage total.
    public long getSizeBytes(String collectionId) {
        return DirectorySize.of(indexDir(collectionId));
    }

    public void renameSource(String collectionId, String oldSource, String newSource) {
        Path dir = indexDir(collectionId);
        if (!Files.exists(dir)) return;
        // Reentrant - deleteDocuments()/addDocuments() below re-acquire this same collectionId's
        // write lock on the same thread, which a reentrant lock permits.
        Lock lock = lockFor(collectionId).writeLock();
        lock.lock();
        try {
            List<String> texts = new ArrayList<>();
            String url = null;
            String connectorType = null;
            try (FSDirectory directory = FSDirectory.open(dir);
                 DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                // FIELD_SOURCE is a StringField (exact-match, untokenized) - a QueryParser +
                // StandardAnalyzer query here would tokenize oldSource (splitting URLs/paths on
                // "/", ":", ".") and never match the single untokenized indexed term. Match the
                // same way deleteDocuments() below does.
                Query q = new TermQuery(new Term(FIELD_SOURCE, oldSource));
                TopDocs hits = searcher.search(q, Integer.MAX_VALUE);
                for (ScoreDoc sd : hits.scoreDocs) {
                    org.apache.lucene.document.Document d = reader.storedFields().document(sd.doc);
                    texts.add(d.get(FIELD_TEXT));
                    if (url == null) url = d.get(FIELD_URL);
                    if (connectorType == null) connectorType = d.get(FIELD_CONNECTOR_TYPE);
                }
            }
            if (!texts.isEmpty()) {
                deleteDocuments(collectionId, oldSource);
                addDocuments(collectionId, newSource, texts, url, connectorType);
            }
        } catch (Exception e) {
            log.warn("[BM25] renameSource failed collection={}: {}", collectionId, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // Read-only counterpart of renameSource()'s own lookup below - every chunk currently stored
    // for one document, for the "Preview" button in the connector modal and for the knowledge
    // graph's extraction worker, which reads its chunks from here. Order isn't a formally
    // guaranteed Lucene contract (no explicit chunk-index field is stored anywhere in this schema -
    // see EmbeddingService.chunkAndEmbed()), but in practice matches original chunk order: all of
    // one document's chunks are added via a single addDocuments() call, in one IndexWriter session,
    // so Lucene assigns them sequential internal doc IDs that a reader iterates in order - the same
    // assumption renameSource() already relies on to carry chunks over to a renamed source.
    public List<String> getChunkTexts(String collectionId, String sourcePath) {
        Path dir = indexDir(collectionId);
        if (!Files.exists(dir)) return List.of();
        Lock lock = lockFor(collectionId).readLock();
        boolean locked;
        try {
            locked = lock.tryLock(SEARCH_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        if (!locked) {
            log.warn("[BM25] getChunkTexts skipped collection {}: busy with a concurrent write after {}s",
                    collectionId, SEARCH_LOCK_WAIT_SECONDS);
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        // Asked again under the lock: a swap holding the write lock moves the directory aside.
        if (!Files.exists(dir)) {
            lock.unlock();
            return texts;
        }
        try (FSDirectory directory = FSDirectory.open(dir);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            // Exact-match Term, not QueryParser - see renameSource()'s identical fix above.
            Query q = new TermQuery(new Term(FIELD_SOURCE, sourcePath));
            TopDocs hits = searcher.search(q, Integer.MAX_VALUE);
            // Sorted by internal doc id explicitly rather than trusting score order to fall back to
            // it. Since 2026-09-15 the knowledge graph reads chunks through here as well as the
            // preview does, and hashes them in order to tell an unchanged document from a changed
            // one - an order that shifted between two reads would re-extract, and bill, a document
            // whose text never changed.
            ScoreDoc[] ordered = hits.scoreDocs.clone();
            java.util.Arrays.sort(ordered, java.util.Comparator.comparingInt(sd -> sd.doc));
            for (ScoreDoc sd : ordered) {
                org.apache.lucene.document.Document d = reader.storedFields().document(sd.doc);
                String text = d.get(FIELD_TEXT);
                if (text != null) texts.add(text);
            }
        } catch (Exception e) {
            log.debug("[BM25] getChunkTexts failed collection={} source='{}': {}", collectionId, sourcePath, e.getMessage());
        } finally {
            lock.unlock();
        }
        return texts;
    }

    /**
     * One keyword hit. {@code collectionId} is the collection it was found in - its {@code source}
     * names an item only within that collection (see ChunkMetadata), and a search spans many.
     */
    public record BM25Result(String source, String text, float score, String url, String connectorType,
                             String collectionId) {}

    // Bounds how long a live chat search waits behind a concurrent write (addDocuments/
    // deleteDocuments/etc. hold this same per-collection lock for as long as a large reindex
    // takes). The write-side lock() calls stay unbounded - they're on the background sync path,
    // not a live chat request - but a search blocking silently for minutes has no visible signal
    // to the user at all, so this collection is skipped (soft-fail, same as the existing
    // per-collection catch below) rather than waited on indefinitely.
    private static final long SEARCH_LOCK_WAIT_SECONDS = 60;

    public List<BM25Result> search(List<String> collectionIds, String queryText, int topK) {
        List<BM25Result> results = new ArrayList<>();
        String escaped = QueryParser.escape(queryText);
        for (String collectionId : collectionIds) {
            Path dir = indexDir(collectionId);
            // Locked per collection (not around the whole method) so different collections'
            // searches never wait for each other - and on the read side, so neither do two
            // searches of the same one. Only a write on that collection holds a search up.
            //
            // The directory is looked for UNDER the lock: a swap moves it aside and back while
            // holding the write side, and asked before the lock a search skipped the collection.
            // Created by no read either way - a lock taken for a collection that never existed is
            // one map entry.
            Lock lock = lockFor(collectionId).readLock();
            boolean locked;
            try {
                locked = lock.tryLock(SEARCH_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!locked) {
                log.warn("[BM25] search skipped collection {}: busy with a concurrent write after {}s",
                        collectionId, SEARCH_LOCK_WAIT_SECONDS);
                continue;
            }
            if (!Files.exists(dir)) {
                lock.unlock();
                continue;
            }
            try (FSDirectory directory = FSDirectory.open(dir);
                 DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                Query query = new QueryParser(FIELD_CONTENT, new StandardAnalyzer()).parse(escaped);
                TopDocs hits = searcher.search(query, topK);
                for (ScoreDoc sd : hits.scoreDocs) {
                    org.apache.lucene.document.Document doc = reader.storedFields().document(sd.doc);
                    String source = doc.get(FIELD_SOURCE);
                    String text   = doc.get(FIELD_TEXT);
                    String url    = doc.get(FIELD_URL);
                    String connectorType = doc.get(FIELD_CONNECTOR_TYPE);
                    if (source != null && text != null) results.add(new BM25Result(source, text, sd.score, url, connectorType, collectionId));
                }
            } catch (Exception e) {
                log.debug("[BM25] search failed for collection {}: {}", collectionId, e.getMessage());
            } finally {
                lock.unlock();
            }
        }
        results.sort(Comparator.comparingDouble(BM25Result::score).reversed());
        return results.size() > topK ? results.subList(0, topK) : results;
    }

    // Lists up to `limit` documents in a collection, by lexicographic DESCENDING order of their
    // stored FIELD_SOURCE, NOT a Lucene Sort (which would need a new SortedDocValuesField written at
    // index time, touching addDocuments()'s schema for every collection type, not just callers that
    // care about ordering). Callers that want "newest first" must give addDocuments() a sourcePath
    // whose lexicographic order matches chronological order (see AgentMemoryService.newFactId()'s
    // zero-padded-epoch-millis scheme) - this method itself is generic ("list everything in a
    // collection"), not specific to that convention. Same tryLock(60s)/soft-fail/if-dir-absent-
    // return-empty pattern as search() above.
    //
    // THE ORDER IS DECIDED OVER EVERY DOCUMENT, and only then is the list bounded, at a fixed
    // ceiling whatever `limit` says. It used to read the first LIST_ALL_CEILING documents Lucene
    // returned for a match-all query - the OLDEST, since equal scores fall back to document order -
    // and sort those, so past 2,000 memory entries nothing newer was ever listed again. Every
    // document's source is read (a small stored field); only the kept ones are read whole.
    private static final int LIST_ALL_CEILING = 2000;

    public List<BM25Result> listAll(String collectionId, int limit) {
        Path dir = indexDir(collectionId);
        if (!Files.exists(dir)) return List.of();
        Lock lock = lockFor(collectionId).readLock();
        boolean locked;
        try {
            locked = lock.tryLock(SEARCH_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        if (!locked) {
            log.warn("[BM25] listAll skipped collection {}: busy with a concurrent write after {}s",
                    collectionId, SEARCH_LOCK_WAIT_SECONDS);
            return List.of();
        }
        int keep = Math.max(0, Math.min(limit, LIST_ALL_CEILING));
        List<BM25Result> results = new ArrayList<>();
        if (keep == 0 || !Files.exists(dir)) {
            lock.unlock();
            return results;
        }
        try (FSDirectory directory = FSDirectory.open(dir);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            record Entry(String source, int doc) {}
            // A min-heap of the `keep` greatest sources seen so far.
            java.util.PriorityQueue<Entry> greatest = new java.util.PriorityQueue<>(
                    Comparator.comparing(Entry::source).thenComparingInt(Entry::doc));
            org.apache.lucene.index.StoredFields stored = reader.storedFields();
            org.apache.lucene.util.Bits live = org.apache.lucene.index.MultiBits.getLiveDocs(reader);
            java.util.Set<String> sourceOnly = java.util.Set.of(FIELD_SOURCE);
            for (int doc = 0; doc < reader.maxDoc(); doc++) {
                if (live != null && !live.get(doc)) continue;
                String source = stored.document(doc, sourceOnly).get(FIELD_SOURCE);
                if (source == null) continue;
                greatest.add(new Entry(source, doc));
                if (greatest.size() > keep) greatest.poll();
            }
            List<Entry> kept = new ArrayList<>(greatest);
            kept.sort(Comparator.comparing(Entry::source).thenComparingInt(Entry::doc).reversed());
            for (Entry e : kept) {
                org.apache.lucene.document.Document doc = stored.document(e.doc());
                String text = doc.get(FIELD_TEXT);
                if (text != null) {
                    results.add(new BM25Result(e.source(), text, 0f, doc.get(FIELD_URL), doc.get(FIELD_CONNECTOR_TYPE),
                            collectionId));
                }
            }
        } catch (Exception e) {
            log.debug("[BM25] listAll failed for collection {}: {}", collectionId, e.getMessage());
        } finally {
            lock.unlock();
        }
        return results;
    }

    private Path indexDir(String collectionId) {
        return indexRoot.resolve(collectionId);
    }
}
