package ai.proxyma.core.indexstore;

import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.system.util.PythonLayout;
import ai.proxyma.core.system.util.TimeConversion;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

// ChromaDB-backed VectorStoreService.
// Owns the full ChromaDB lifecycle: starts the process on @PostConstruct,
// waits for it to become reachable, sets up tenant/database, and stops it on @PreDestroy.
// When proxyma.chromadb.managed=false (e.g. externally managed instance), the process
// management is skipped and the service connects to the existing instance instead.
@Service
@Primary
@org.springframework.context.annotation.Profile("!server")
public class ChromaStoreService implements VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(ChromaStoreService.class);
    private static final int SEARCH_TIMEOUT_SECONDS = 30;
    private static final int STARTUP_TIMEOUT_SECONDS = 60;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingConcurrency embeddingConcurrency;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final AppPaths appPaths;

    @Value("${proxyma.chromadb.port:0}")
    private int configuredPort;

    @Value("${proxyma.chromadb.managed:true}")
    private boolean managed;

    /**
     * The address ChromaDB binds to and is reached on - a LITERAL, never a hostname.
     *
     * <h2>Why this is not "localhost"</h2>
     *
     * It was, and chroma binds {@code [::1]} by default, so the JVM had to be told
     * {@code -Djava.net.preferIPv6Addresses=true} to resolve {@code localhost} the IPv6 way. That
     * flag is JVM-WIDE. On the Home Server image - a container on Docker's default IPv4-only
     * bridge - it also made every OUTBOUND call prefer a AAAA record, and
     * {@code license.proxyma.ai} is Cloudflare-fronted and dual-stack. Activation therefore failed
     * with {@code Network is unreachable} on every Home Server install there has ever been: a paid
     * edition that could not be licensed, because of a flag added to reach a loopback port.
     * Measured on the demo host 2026-09-24 - {@code curl -4} answered 200 and {@code curl -6}
     * failed instantly, while the JVM only ever tried the AAAA.
     *
     * <p>A literal on both sides removes the resolver from the question entirely: chroma is
     * started with {@code --host} this value and reached at {@code http://<this>:<port>}, so no
     * name is looked up and no JVM-wide preference is needed. The flag is gone from the Dockerfile,
     * the Launch4j options and {@code tools\\dev\\run.bat}.
     *
     * <p>Configurable because {@code managed=false} means somebody else chose the bind address -
     * an externally managed chroma listening on IPv6 only is reached by setting this to
     * {@code [::1]}.
     */
    @Value("${proxyma.chromadb.host:127.0.0.1}")
    private String chromaHost;

    /**
     * A credential sent with EVERY request to ChromaDB - the heartbeat, every {@link ChromaApi}
     * call and the raw rename - or nothing when empty, which is the default.
     *
     * <h2>For an externally managed ChromaDB only</h2>
     *
     * ChromaDB 1.x has no authentication of its own: {@code chroma run} starts a Rust server whose
     * configuration has no auth setting at all, and the {@code CHROMA_SERVER_AUTHN_*} providers
     * belong to the older Python server, which is not bundled. Chroma's own advice for 1.x is a
     * token-checking reverse proxy in front of an instance nothing else can reach, so this is for
     * {@code proxyma.chromadb.managed=false} pointed at such a proxy. The managed child cannot
     * check a token, and a token nobody checks is not a control - so none is invented for it.
     */
    @Value("${proxyma.chromadb.auth-token:}")
    private String authToken;

    /**
     * The header {@link #authToken} travels in, as Chroma's own clients send it: {@code
     * X-Chroma-Token} carries the token as it is, {@code Authorization} carries {@code Bearer
     * <token>}.
     */
    @Value("${proxyma.chromadb.auth-header:X-Chroma-Token}")
    private String authHeader;

    // THE NAMESPACE EVERY VECTOR ACTUALLY LIVES IN, AND IT IS NOT OURS TO CHOOSE.
    //
    // buildStore() below calls ChromaVectorStore.builder() without .tenantName()/.databaseName(),
    // so Spring AI substitutes its own defaults and every embedding this application has ever
    // written went to SpringAiTenant/SpringAiDatabase. These two fields used to read
    // "proxyma-tenant"/"proxyma-database" from spring.application.name, which addressed a
    // namespace the store created at startup and then never wrote a single vector into. Every
    // direct chromaApi call here - deleteEmbeddings, deleteCollection, getCollection, and the raw
    // rename below - was therefore aimed at an empty database, and each of them FAILED QUIETLY:
    // deleteCollection caught its own 404 at debug level, deleteBySourceMetadata returned early on
    // a null uuid, and swapCollection reported the shadow as "never created". A live instance with
    // one embedded document showed SpringAiDatabase holding the collection and proxyma-database
    // holding nothing.
    //
    // Constants rather than properties BECAUSE THEY MUST MATCH SPRING AI, so there is nothing here
    // for a deployment to decide. ChromaNamespaceTest pins them against the builder's own defaults
    // by reflection, so a Spring AI upgrade that moves them fails a test instead of orphaning an
    // index. DO NOT "fix" this the other way round by passing these names to the builder: that
    // moves every future collection to a namespace no existing install's vectors are in, and
    // search returns nothing, silently.
    private static final String TENANT_NAME = "SpringAiTenant";
    private static final String DATABASE_NAME = "SpringAiDatabase";

    private int resolvedPort;
    private ChromaApi chromaApi;
    private Process chromaProcess;

    private final Map<String, String> collectionIdCache = new ConcurrentHashMap<>();

    public ChromaStoreService(EmbeddingModel embeddingModel,
                              RestClient.Builder restClientBuilder,
                              ObjectMapper objectMapper,
                              AppPaths appPaths,
                              EmbeddingConcurrency embeddingConcurrency) {
        this.embeddingConcurrency = embeddingConcurrency;
        this.embeddingModel     = embeddingModel;
        this.restClientBuilder  = restClientBuilder;
        this.objectMapper       = objectMapper;
        this.appPaths           = appPaths;
    }

    // -- Lifecycle -------------------------------------------------------------

    /** Where chroma is, as a literal - see {@link #chromaHost} for why it is never a name. */
    private String chromaBaseUrl() {
        return "http://" + chromaHost + ":" + resolvedPort;
    }

    /**
     * A builder of its own for every Chroma call, carrying {@link #authToken} when one is set. A
     * clone, because ChromaApi sets its base URL on the builder it is handed, and the injected one
     * is shared.
     */
    private RestClient.Builder chromaClient() {
        RestClient.Builder builder = restClientBuilder.clone();
        if (authToken != null && !authToken.isBlank()) {
            String header = authHeader == null || authHeader.isBlank() ? "X-Chroma-Token" : authHeader.trim();
            String value = header.equalsIgnoreCase("Authorization") ? "Bearer " + authToken.trim() : authToken.trim();
            builder.defaultHeader(header, value);
        }
        return builder;
    }

    @PostConstruct
    public void startInfrastructure() throws Exception {
        // A FREE PORT, NOT 8000, unless one is configured (editions-assembly-06). Desktop pinned
        // 8000 - chroma's own default and a common development port - so where a user's own chroma
        // already answered there, the first heartbeat below reached THEIRS before the managed child
        // failed to bind, and every vector was written into it.
        resolvedPort = configuredPort > 0 ? configuredPort : findFreePort();
        String chromaUrl = chromaBaseUrl();

        if (managed) {
            if (authToken != null && !authToken.isBlank()) {
                log.warn("proxyma.chromadb.auth-token is set, but the managed ChromaDB has no authentication to "
                        + "check it - it is sent and ignored. It is meant for managed=false behind a proxy that checks it.");
            }
            startChromaProcess();
        }

        if (!waitForChromaDb(chromaUrl)) {
            log.warn("ChromaDB not reachable at {} (managed={}) - search and indexing will be unavailable", chromaUrl, managed);
            return;
        }
        chromaApi = new ChromaApi(chromaUrl, chromaClient(), objectMapper);
        ensureTenantAndDatabase();
        log.info("ChromaDB ready on port {} (managed={})", resolvedPort, managed);
    }

    @PreDestroy
    public void stopInfrastructure() {
        if (chromaProcess != null && chromaProcess.isAlive()) {
            log.info("Stopping ChromaDB process");
            chromaProcess.destroy();
        }
    }

    private void startChromaProcess() throws IOException {
        Path installRoot = Path.of(System.getProperty("user.dir"));
        Path chromaDbDir = appPaths.chromaDbRoot();
        Files.createDirectories(chromaDbDir);
        Path logPath = appPaths.logsDir().resolve("chromadb.log");
        Files.createDirectories(logPath.getParent());

        Path pythonExe = PythonLayout.resolveInterpreter(appPaths.dataRoot(), installRoot);
        List<String> command;
        if (pythonExe != null) {
            // Invoke chromadb's own CLI entry point (chromadb.cli.cli:app, per chromadb's
            // pyproject.toml `[project.scripts]`) directly through the base Python interpreter,
            // rather than the pip-generated "chroma"/"chroma.exe" console-script shortcut. That
            // shortcut has an absolute interpreter path baked in at install time - only safe
            // because desktop installs Python in-place, and fragile the moment that stops being
            // true. chromadb.cli.cli has no `if __name__ == "__main__"` guard, so `-m
            // chromadb.cli.cli` would import the module and do nothing; `app()` itself just reads
            // sys.argv directly (same as the console script does), so invoking it via `-c` with
            // the real args appended is the exact equivalent, not a re-implementation.
            command = new ArrayList<>(List.of(
                    pythonExe.toString(), "-c", "from chromadb.cli.cli import app; app()",
                    "run",
                    "--path", chromaDbDir.toString(),
                    // --host on BOTH branches. This one is the branch that actually runs in the
                    // Home Server container and on any install with the bundled
                    // interpreter, and it was the one missed when the executable branch
                    // below got its --host first - chroma then still bound [::1] and the
                    // app, now looking at 127.0.0.1, could not see it at all.
                    "--host", chromaHost,
                    "--port", String.valueOf(resolvedPort)));
            killOrphanChromaProcesses(pythonExe, true);
        } else {
            // No bundled interpreter anywhere, so fall back to the pip-generated console script if
            // one happens to exist in either candidate runtime root.
            Path chromaExe = null;
            for (Path root : PythonLayout.roots(appPaths.dataRoot(), installRoot)) {
                Path candidate = PythonLayout.scriptsDir(root)
                        .resolve(System.getProperty("os.name", "").toLowerCase().contains("win")
                                ? "chroma.exe" : "chroma");
                if (Files.exists(candidate)) { chromaExe = candidate; break; }
            }
            if (chromaExe == null) {
                log.warn("Neither a managed python interpreter nor a chroma executable were found in {} - assuming externally managed",
                        PythonLayout.roots(appPaths.dataRoot(), installRoot));
                return;
            }
            killOrphanChromaProcesses(chromaExe, false);
            command = List.of(
                    chromaExe.toString(), "run",
                    "--path", chromaDbDir.toString(),
                    // --host, so it binds where we look for it. Without it chroma picks [::1] and
                    // the JVM had to be bent IPv6-first to find it - see chromaHost.
                    "--host", chromaHost,
                    "--port", String.valueOf(resolvedPort));
        }

        // chromadb's `run` CLI takes only --path/--host/--port (+ an optional config path) since
        // the CLI was rewritten in Rust; the old --log-path flag is gone and passing it makes the
        // process exit immediately with "unexpected argument". Capture chroma's own output by
        // redirecting the process stream to that same log file instead - which also keeps the
        // pipe drained, so a chatty chroma can never block on a full OS pipe buffer.
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
        // Chroma's anonymized product telemetry is ON unless this says otherwise. The vector store
        // runs on the user's machine and the Privacy Policy lists what leaves it; Chroma's own
        // usage events are not on that list (2026-10-09).
        builder.environment().put("ANONYMIZED_TELEMETRY", "False");
        chromaProcess = builder.start();

        log.info("ChromaDB process started (port {}, log {})", resolvedPort, logPath);
    }

    private boolean waitForChromaDb(String chromaUrl) {
        RestClient probe = chromaClient().build();
        String heartbeatUrl = chromaUrl + "/api/v2/heartbeat";
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_SECONDS * 1_000L;

        while (true) {
            try {
                probe.get().uri(heartbeatUrl).retrieve().toBodilessEntity();
                // A heartbeat while our own child is gone came from somebody else's chroma on the
                // port - the child died binding it. Never adopt that one.
                if (chromaProcess != null && !chromaProcess.isAlive()) {
                    log.warn("Something else answers on ChromaDB's port {} and the managed ChromaDB exited "
                            + "with code {} - not using it", resolvedPort, chromaProcess.exitValue());
                    return false;
                }
                return true;
            } catch (Exception e) {
                // A managed chroma that died on startup (bad CLI args, port in use, broken
                // install) will never answer - report the exit code straight away instead of
                // spending the full timeout probing a process that no longer exists.
                if (chromaProcess != null && !chromaProcess.isAlive()) {
                    log.warn("ChromaDB process exited with code {} - see {}",
                            chromaProcess.exitValue(), appPaths.logsDir().resolve("chromadb.log"));
                    return false;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    log.warn("ChromaDB unreachable at {} after {}s", chromaUrl, STARTUP_TIMEOUT_SECONDS);
                    return false;
                }
                log.debug("Waiting for ChromaDB... ({} remaining)", TimeConversion.formatTime(remaining));
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    // ChromaVectorStore.afterPropertiesSet() creates both of these itself when initializeSchema is
    // on, so on any instance that has indexed something this is a no-op. It stays for the instance
    // that has NOT: isHealthy() below answers by asking for the tenant, and a fresh install with no
    // store built yet would report itself unhealthy for no reason. Cheap, idempotent, and the only
    // reason this method is not dead - it was, for as long as it addressed a namespace of its own.
    private void ensureTenantAndDatabase() {
        ChromaApi.Tenant tenant = null;
        try { tenant = chromaApi.getTenant(TENANT_NAME); } catch (Exception ignored) {}
        if (tenant == null) chromaApi.createTenant(TENANT_NAME);

        ChromaApi.Database database = null;
        try { database = chromaApi.getDatabase(TENANT_NAME, DATABASE_NAME); } catch (Exception ignored) {}
        if (database == null) chromaApi.createDatabase(TENANT_NAME, DATABASE_NAME);
    }

    // -- VectorStoreService ----------------------------------------------------

    @Override
    public void upsert(String collectionId, List<Document> documents) {
        if (documents == null || documents.isEmpty()) return;
        // `add` embeds before it stores, so it takes a slot of the embedding limit - the one the
        // setting has always described and, until 2026-10-01, this store never honored.
        embeddingConcurrency.acquire();
        try {
            buildStore(collectionId).add(documents);
            log.debug("[Chroma] upsert: collection={} docs={}", collectionId, documents.size());
        } catch (Exception e) {
            // `add` embeds before it stores, so the usual cause of a failure here is the EMBEDDING
            // PROVIDER refusing the call (billing, a bad key, a retired model), not ChromaDB -
            // hence Failures.describe on the log line, which otherwise named only the operation and
            // sent every such investigation to the wrong component.
            //
            // The THROWN message stays the operation alone, deliberately: the cause travels on the
            // exception, and whoever reports it joins the two (see Failures' own contract, and
            // SyncService.markFailed, which is what puts this in front of a user). Appending the
            // cause here as well would have it printed twice.
            log.error("[Chroma] upsert failed collection={}: {}", collectionId, Failures.describe(e));
            throw new RuntimeException("ChromaDB upsert failed for collection " + collectionId, e);
        } finally {
            embeddingConcurrency.release();
        }
    }

    @Override
    public List<Document> search(List<String> collectionIds, String query, int topK, double minScore) {
        return search(collectionIds, query, topK, minScore, embeddingModel);
    }

    /**
     * The query embedded ONCE and every collection asked with that vector.
     *
     * <p>It went through a Spring AI store per collection, and each store embeds the query itself -
     * so a search over twenty sources made twenty identical embedding calls (eighty with query
     * expansion), each passing the quota gate and each written to the call ledger. And each of
     * those stores was built with schema initialisation on, which CREATED any collection it was
     * asked about that did not exist: searching a source with no vectors left an empty collection
     * behind, and during a model-changed reindex it recreated the live collection between the
     * swap's delete and its rename (see swapCollection). A collection that does not exist is now
     * simply not asked.
     *
     * <p>FAILURES ARE NOT SILENT. A failed embedding throws - the caller decides what a search
     * without its dense half is worth (SearchService answers from the keyword index and says so).
     * One collection that fails is logged and skipped, so one broken collection does not cost the
     * others their answers; when every collection asked failed, that throws too.
     */
    @Override
    public List<Document> search(List<String> collectionIds, String query, int topK, double minScore,
                                  EmbeddingModel embeddingModelOverride) {
        if (collectionIds == null || collectionIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, String> uuids = new LinkedHashMap<>();
        for (String id : collectionIds) {
            String uuid = resolveCollectionUuid(id);
            if (uuid != null) uuids.put(id, uuid);
        }
        if (uuids.isEmpty()) return List.of();
        float[] vector = embeddingModelOverride.embed(query);

        Map<String, CompletableFuture<List<Document>>> futures = new LinkedHashMap<>();
        uuids.forEach((id, uuid) -> futures.put(id, CompletableFuture.supplyAsync(
                () -> queryOne(id, uuid, vector, topK, minScore), BlockingWork.executor())));

        List<Document> all = new ArrayList<>();
        RuntimeException lastFailure = null;
        int failed = 0;
        for (Map.Entry<String, CompletableFuture<List<Document>>> f : futures.entrySet()) {
            try {
                all.addAll(f.getValue().get(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (Exception e) {
                // A collection deleted since its id was cached answers 404 - looked up afresh next time.
                collectionIdCache.remove(f.getKey());
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                failed++;
                lastFailure = new IllegalStateException("ChromaDB search failed for collection " + f.getKey(), cause);
                log.warn("[Chroma] search of collection {} failed: {}", f.getKey(), Failures.describe(cause));
            }
        }
        if (failed == futures.size()) throw lastFailure;
        all.sort((a, b) -> Double.compare(
                b.getScore() != null ? b.getScore() : 0.0,
                a.getScore() != null ? a.getScore() : 0.0));
        return all;
    }

    // What ChromaVectorStore.doSimilaritySearch does with a query it has embedded, minus the
    // embedding: score = 1 - cosine distance, kept when at or above the threshold.
    private List<Document> queryOne(String collectionId, String collectionUuid, float[] vector, int topK,
                                    double minScore) {
        ChromaApi.QueryResponse response = chromaApi.queryCollection(TENANT_NAME, DATABASE_NAME, collectionUuid,
                new ChromaApi.QueryRequest(vector, topK));
        return toDocuments(chromaApi.toEmbeddingResponseList(response), minScore, collectionId);
    }

    // Each hit stamped with the collection it came from (ChunkMetadata): its `source` is unique only
    // within that collection, and the results of every collection asked are merged below.
    static List<Document> toDocuments(List<ChromaApi.Embedding> hits, double minScore, String collectionId) {
        List<Document> out = new ArrayList<>();
        for (ChromaApi.Embedding hit : hits) {
            if (hit.distances() == null) continue;
            double distance = hit.distances();
            if (1 - distance < minScore) continue;
            Map<String, Object> metadata = hit.metadata() != null ? new HashMap<>(hit.metadata()) : new HashMap<>();
            metadata.put(DocumentMetadata.DISTANCE.value(), (float) distance);
            metadata.put(ChunkMetadata.COLLECTION, collectionId);
            out.add(Document.builder().id(hit.id()).text(hit.document()).metadata(metadata)
                    .score(1.0 - distance).build());
        }
        return out;
    }

    @Override
    public void deleteBySourceMetadata(String collectionId, String sourceId) {
        deleteWhere(collectionId, Map.of("source", sourceId));
        log.debug("[Chroma] deleteBySourceMetadata: collection={} source={}", collectionId, sourceId);
    }

    /** Sources per delete request - the where clause carries each one. */
    private static final int DELETE_BATCH = 500;

    @Override
    public void deleteBySourceMetadata(String collectionId, Collection<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) return;
        List<String> all = List.copyOf(sourceIds);
        for (int i = 0; i < all.size(); i += DELETE_BATCH) {
            List<String> batch = all.subList(i, Math.min(i + DELETE_BATCH, all.size()));
            deleteWhere(collectionId, Map.of("source", Map.of("$in", batch)));
        }
        log.debug("[Chroma] deleteBySourceMetadata: collection={} sources={}", collectionId, all.size());
    }

    // A missing collection holds nothing to delete and is not created to find that out. A delete
    // ChromaDB refused THROWS: it used to be logged and dropped, and a document re-indexed after one
    // kept its previous chunks beside the new ones.
    private void deleteWhere(String collectionId, Map<String, Object> where) {
        String uuid = resolveCollectionUuid(collectionId);
        if (uuid == null) return;
        try {
            chromaApi.deleteEmbeddings(TENANT_NAME, DATABASE_NAME, uuid,
                    new ChromaApi.DeleteEmbeddingsRequest(List.of(), where));
        } catch (RuntimeException e) {
            collectionIdCache.remove(collectionId);
            throw new IllegalStateException("ChromaDB delete failed for collection " + collectionId, e);
        }
    }

    @Override
    public void deleteCollection(String collectionId) {
        // Previously a no-op against the actual Chroma server (only evicted the local UUID
        // cache) - a deleted collection's data stayed in ChromaDB forever, it just became
        // unreachable through this cache. chromaApi.deleteCollection() addresses a collection by
        // name (same identifier buildStore()/upsert() etc. already use), so no UUID lookup is
        // needed here.
        try {
            if (chromaApi != null) {
                chromaApi.deleteCollection(TENANT_NAME, DATABASE_NAME, collectionId);
            }
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.debug("[Chroma] deleteCollection: {} did not exist server-side", collectionId);
        } catch (Exception e) {
            log.warn("[Chroma] deleteCollection failed for {}: {}", collectionId, e.getMessage());
        } finally {
            collectionIdCache.remove(collectionId);
        }
        log.info("[Chroma] deleteCollection: {}", collectionId);
    }

    // Swaps a freshly-built "shadow" collection in as the live one by RENAMING, never by deleting
    // first: the live collection is renamed aside, the shadow takes its name, and only then is the
    // retired one deleted. The shadow's already-embedded vectors become the live collection's data
    // in one Chroma-side operation, rather than re-uploading everything under the new name.
    //
    // It deleted the live collection first and renamed the shadow second, and anything that touched
    // the live name in between - a search, an upsert, a delete, each through a store that created a
    // missing collection - recreated it empty, so the rename onto that name failed and the source
    // served no dense results until a later drain retried. Searches and deletes no longer create
    // collections, and with the rename-aside there is no deleted name for anything to recreate.
    //
    // ChromaApi (Spring AI's client) exposes no rename/update call, so renameCollection() below
    // issues the raw REST call Chroma's own HTTP API supports for this.
    @Override
    public void swapCollection(String liveId, String shadowId) {
        String shadowUuid = resolveCollectionUuid(shadowId);
        if (shadowUuid == null) {
            log.warn("[Chroma] swapCollection: shadow collection={} has no data (never created) - nothing to swap into live={}", shadowId, liveId);
            return;
        }
        String liveUuid = resolveCollectionUuid(liveId);
        String retiredName = liveId + "_retired";
        if (liveUuid != null) {
            // A retired copy a crashed swap left behind would hold the name this one needs.
            deleteCollection(retiredName);
            try {
                renameCollection(liveUuid, retiredName);
            } catch (Exception e) {
                log.error("[Chroma] swapCollection: could not rename live={} aside: {}", liveId, e.getMessage(), e);
                throw new VectorStoreException("ChromaDB collection rename failed during swap: " + e.getMessage(), e);
            }
            collectionIdCache.remove(liveId);
        }
        try {
            renameCollection(shadowUuid, liveId);
        } catch (Exception e) {
            log.error("[Chroma] swapCollection: rename failed shadow={} (uuid={}) -> live={}: {}",
                    shadowId, shadowUuid, liveId, e.getMessage(), e);
            // The live collection goes back to its own name, so the source keeps answering from it.
            if (liveUuid != null) {
                try {
                    renameCollection(liveUuid, liveId);
                } catch (Exception restoreFailed) {
                    e.addSuppressed(restoreFailed);
                }
            }
            throw new VectorStoreException("ChromaDB collection rename failed during swap: " + e.getMessage(), e);
        }
        collectionIdCache.remove(shadowId);
        collectionIdCache.put(liveId, shadowUuid);
        if (liveUuid != null) deleteCollection(retiredName);
        log.info("[Chroma] swapCollection: renamed shadow={} (uuid={}) to live={}", shadowId, shadowUuid, liveId);
    }

    // Raw REST call - Chroma's actual HTTP API supports PUT to a collection's UUID endpoint with
    // a "new_name" body field to rename it; ChromaApi doesn't wrap this, so this reuses the same
    // client (credential included)/tenant/database/base-URL this service already builds every other
    // Chroma call from.
    private void renameCollection(String collectionUuid, String newName) {
        String chromaUrl = chromaBaseUrl();
        chromaClient().build().put()
                .uri(chromaUrl + "/api/v2/tenants/{tenant}/databases/{database}/collections/{id}",
                        TENANT_NAME, DATABASE_NAME, collectionUuid)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("new_name", newName))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public String backendName() { return "chromadb"; }

    @Override
    public boolean isHealthy() {
        try {
            chromaApi.getTenant(TENANT_NAME);
            return true;
        } catch (Exception e) {
            log.debug("[Chroma] health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public long getTotalSizeBytes() {
        // ChromaDB has no per-collection disk-size API - its SQLite + HNSW index files aren't
        // segmented in a way that maps cleanly to one collection, so this reports the whole
        // embedded store's directory size rather than a per-collection breakdown.
        return DirectorySize.of(appPaths.chromaDbRoot());
    }

    // -- Helpers ---------------------------------------------------------------

    private String resolveCollectionUuid(String collectionName) {
        String cached = collectionIdCache.get(collectionName);
        if (cached != null) return cached;
        try {
            ChromaApi.Collection col = chromaApi.getCollection(TENANT_NAME, DATABASE_NAME, collectionName);
            if (col != null) {
                collectionIdCache.put(collectionName, col.id());
                return col.id();
            }
        } catch (Exception e) {
            log.debug("[Chroma] Could not resolve collection UUID for {}: {}", collectionName, e.getMessage());
        }
        return null;
    }

    // The WRITE path only: a store built with schema initialisation creates its collection, which is
    // what a first upsert needs and what nothing that only reads or deletes may do.
    private ChromaVectorStore buildStore(String collectionName) {
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(true)
                .initializeImmediately(true)
                .build();
    }

    private void killOrphanChromaProcesses(Path exeOrInterpreter, boolean isPythonInterpreter) {
        try {
            Path exeAbs = exeOrInterpreter.toAbsolutePath();
            // Our own children are never orphans, whatever they run.
            Set<Long> ours = ProcessHandle.current().descendants()
                    .map(ProcessHandle::pid).collect(Collectors.toSet());
            ProcessHandle.allProcesses()
                    .filter(ph -> ph.isAlive() && !ours.contains(ph.pid()))
                    .filter(ph -> isOrphanChroma(ph.info().commandLine(), ph.info().command(),
                            exeAbs, isPythonInterpreter))
                    .forEach(ph -> {
                        log.info("Killing orphan ChromaDB process (pid={}, cmd={})",
                                ph.pid(), ph.info().commandLine().or(() -> ph.info().command()).orElse("?"));
                        ph.destroyForcibly();
                        try { ph.onExit().get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            log.debug("Could not enumerate processes to kill orphan ChromaDB: {}", e.getMessage());
        }
    }

    /**
     * Whether a running process is a ChromaDB left behind by an earlier run of this install.
     *
     * ON WINDOWS THE JDK CANNOT READ ANOTHER PROCESS'S ARGUMENTS - {@code commandLine()} is empty
     * there and only {@code command()}, the executable, is known. This used to match the python
     * branch on the command line alone, so on Windows it matched nothing, ever: an orphaned chroma
     * kept port 8000, the new one exited with "Address 127.0.0.1:8000 is not available", the
     * heartbeat was answered by the orphan, and since this service's own child was dead its
     * {@code @PreDestroy} stopped nothing - so the orphan outlived every later session as well.
     *
     * Without a command line the interpreter's own path decides. That is safe here and only here:
     * it is the bundled runtime in THIS install's data directory, this runs at startup under the
     * launcher's single-instance lock, and the caller excludes this JVM's own children - so any
     * other process running that interpreter is a leftover of an earlier session.
     */
    static boolean isOrphanChroma(Optional<String> commandLine, Optional<String> command,
                                  Path exeAbs, boolean isPythonInterpreter) {
        if (isPythonInterpreter) {
            if (commandLine.isPresent()) {
                // The command alone is just the shared python interpreter - match the
                // chromadb.cli.cli marker unique to our own invocation, so an unrelated job using
                // the same interpreter is never killed where the command line can be read.
                return commandLine.get().contains("chromadb.cli.cli");
            }
            return command.map(cmd -> samePath(cmd, exeAbs)).orElse(false);
        }
        return command
                .map(cmd -> samePath(cmd, exeAbs) || cmd.endsWith("/chroma")
                            || cmd.endsWith("\\chroma") || cmd.endsWith("\\chroma.exe"))
                .orElse(false);
    }

    // Path equality, not string equality: case-insensitive on Windows, as the filesystem is.
    private static boolean samePath(String cmd, Path exeAbs) {
        try {
            return Path.of(cmd).toAbsolutePath().normalize().equals(exeAbs.normalize());
        } catch (InvalidPathException e) {
            return false;
        }
    }

    // Asked of the address chroma binds, so the port is free where it matters.
    private int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName(chromaHost.replace("[", "").replace("]", "")))) {
            return s.getLocalPort();
        }
    }
}
