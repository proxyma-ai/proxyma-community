package ai.proxyma.core.registry.controller;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.StoredConfig;
import ai.proxyma.core.connector.webpage.WebCrawlConnector;
import ai.proxyma.core.registry.files.upload.model.FileMetadata;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.SyncOrchestratorService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.SensitivePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/indexing")
public class ConnectorController {

    private static final Logger log = LoggerFactory.getLogger(ConnectorController.class);

    // Where the registration endpoints run: each waits on a remote server - the access probe (an
    // svn info of up to two minutes, a service's API with its retries) and the per-type
    // lock behind a running sync - which boundedElastic is not for (.claude/rules/backend-maven.md).
    private static final reactor.core.scheduler.Scheduler REMOTE =
            Schedulers.fromExecutor(ai.proxyma.core.common.concurrent.BlockingWork.executor());

    private final ConnectorService connectorService;
    private final SettingsService settingsService;
    private final ai.proxyma.core.registry.service.ConnectorEndpointResolver endpointResolver;
    private final SyncOrchestratorService syncOrchestratorService;
    private final FileMetadataRepository fileMetadataRepository;

    // Whether this is a server with accounts. There the graph model's host and the reason it fails
    // are the operator's configuration, not every member's business: a member is told the model is
    // not answering and when it is tried again, and the details stay in the operator's log.
    @org.springframework.beans.factory.annotation.Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public ConnectorController(ConnectorService connectorService,
                              SettingsService settingsService,
                              ai.proxyma.core.registry.service.ConnectorEndpointResolver endpointResolver,
                              SyncOrchestratorService syncOrchestratorService,
                              FileMetadataRepository fileMetadataRepository) {
        this.connectorService = connectorService;
        this.settingsService = settingsService;
        this.endpointResolver = endpointResolver;
        this.syncOrchestratorService = syncOrchestratorService;
        this.fileMetadataRepository = fileMetadataRepository;
    }

    // What every type is - shareable, its scope, its service. Setter-injected, so the constructor
    // tests build this with is unchanged.
    private ConnectorCatalog catalog = ConnectorCatalog.descriptorsOnly();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCatalog(ConnectorCatalog catalog) {
        this.catalog = catalog;
    }

    // "All" tab summary: aggregate counts (source/document totals) plus a capped preview of each
    // connector type's most recent connectors. Never loads a full type's connector list - a type
    // with thousands of registered connectors would make this endpoint itself the bottleneck.
    // Business logic (which types to include, aggregate accumulation) lives in
    // ConnectorService.getConnectorSummary(); this method only maps the result to JSON.
    @GetMapping("/summary")
    public Mono<ResponseEntity<Map<String, Object>>> getSummary(@RequestParam(defaultValue = "false") boolean includeShared,
                                                                  ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            ConnectorService.ConnectorSummary summary = connectorService.getConnectorSummary(uid, includeShared);
            Map<String, Object> byType = new LinkedHashMap<>();
            summary.byType().forEach((type, typeSummary) -> {
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("total", typeSummary.total());
                group.put("preview", typeSummary.preview().stream().map(r -> toConnectorMap(r, uid)).toList());
                byType.put(mapConnectorType(type), group);
            });
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalSources", summary.totalSources());
            result.put("totalDocs", summary.totalDocs());
            result.put("byType", byType);
            result.put("activeIndexingCount", summary.activeIndexingCount());
            return ResponseEntity.ok(result);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // What the sources take on disk, for the Sources page's header: the index (vectors plus the
    // keyword index) and the uploaded files. A separate call from /summary, which the page refetches
    // on every sync event - these are directory walks, and the page asks for them once a minute at
    // most. Scoped like everything else here: desktop measures the whole store, a server user their
    // own sources and uploads (ConnectorService.getIndexSizeBytes).
    @GetMapping("/storage")
    public Mono<ResponseEntity<Map<String, Object>>> getStorage(ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            long indexBytes = connectorService.getIndexSizeBytes(uid);
            long uploadsBytes = uid == null ? fileMetadataRepository.sumAllSizeBytes()
                    : fileMetadataRepository.sumSizeBytesByUserId(uid);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("indexBytes", indexBytes);
            result.put("uploadsBytes", uploadsBytes);
            result.put("totalBytes", indexBytes + uploadsBytes);
            return ResponseEntity.ok(result);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Paginated per-type listing backing the Sources page's type tabs (Files/Jira/Confluence/
    // etc) - a single type can have thousands of registered connectors, so the tab lazy-loads
    // pages on scroll instead of fetching everything at once like /summary's capped preview does.
    // The optional "q" param filters by connector title (name), not document content - this backs
    // the Sources page's search box. type="all" (a path sentinel, not a real ConnectorType)
    // searches by title across every connector type when "q" is set (the "All" tab's search box),
    // or returns the unfiltered all-types listing when "q" is blank (the chat input's @mention
    // dropdown, listing every connector the instant "@" is typed).
    @GetMapping("/by-type/{type}")
    public Mono<ResponseEntity<Map<String, Object>>> listByType(@PathVariable String type,
                                                                 @RequestParam(required = false) String q,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size,
                                                                 @RequestParam(defaultValue = "false") boolean includeShared,
                                                                 ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            ConnectorType ct = "all".equals(type) ? null : parseConnectorType(type);
            Page<RegisteredConnector> result = connectorService.listByTypePaged(ct, uid, q, Math.max(0, page), clampSize(size, 100), includeShared);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", result.getContent().stream().map(r -> toConnectorMap(r, uid)).toList());
            body.put("page", result.getNumber());
            body.put("totalPages", result.getTotalPages());
            body.put("totalItems", result.getTotalElements());
            body.put("hasMore", result.hasNext());
            return ResponseEntity.ok(body);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * One to {@code max} rows a page. The Sources page and the source dialog ask for twenty (every
     * list does since 2026-10-14); a size of zero or less was a 500 from PageRequest, or an empty
     * keyset page that said there was more.
     */
    static int clampSize(int size, int max) {
        return Math.max(1, Math.min(size, max));
    }

    private Map<String, Object> toConnectorMap(RegisteredConnector r, String uid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId().toString());
        m.put("type", mapConnectorType(r.getConnectorType()));
        m.put("name", r.getName());
        m.put("status", r.getStatus().name());
        // Scrubbed at this boundary: a file-system failure names the absolute path on the server
        // (Failures.describeFileSystem), and both of these reach every user a source is shared with.
        m.put("lastSyncError", SensitivePaths.scrub(r.getLastSyncError()));
        m.put("itemCount", r.getItemCount());
        m.put("processedCount", r.getProcessedCount());
        m.put("failedCount", r.getFailedCount());
        m.put("shared", r.isShared());
        // Whether sharing is even offered for this type. From the enum, never from a list kept
        // here: the server refuses shared=true on a type that is not shareable, and a UI that does
        // not know which those are can only find out by being refused.
        m.put("shareable", catalog.isShareable(r.getConnectorType()));
        m.put("priority", r.isPriority());
        // Is anything happening right now - a reconcile pass, a drain, or both. The counts beside
        // it cannot answer that: a fully-enumerated source with a backlog looks identical whether
        // a drain is running or the scheduler has not got to it yet.
        m.put("syncing", syncOrchestratorService.isSyncing(r.getId()));
        // uid == null means desktop/anonymous mode, where there's no other-users concept and
        // everything visible is the caller's own by definition.
        m.put("ownedByMe", uid == null || uid.equals(r.getUserId()));
        m.put("allItemsFetched", r.isAllItemsFetched());
        m.put("lastSyncedAt", r.getLastSyncedAt() != null ? r.getLastSyncedAt().toString() : null);
        // Withheld for BOTH file-backed types: `config` is an absolute filesystem path either
        // way - the user's own folder on desktop, the server's storage root on server - and
        // neither may reach a client. isFileBacked() rather than one named type, so adding a
        // third file-backed flavour later cannot leak a path by omission.
        m.put("config", r.getConnectorType().isFileBacked() ? null : r.getConfig());
        // A type built on the connector framework stores its fields as JSON; the page reads them as
        // fields (the source dialog's values), never as a string to parse. Never for a file-backed
        // type, whose config is the folder's path - withheld above, and withheld here.
        ConnectorDescriptor descriptor = catalog.descriptor(r.getConnectorType());
        if (descriptor != null && !r.getConnectorType().isFileBacked()) {
            m.put("fields", StoredConfig.fields(r.getConnectorType(), r.getConfig()));
        }
        // The file-scope knobs, surfaced so the connector modal can show and edit them (see
        // PATCH /{id}/config) - for a folder, and since 2026-09-27 for every file-based type (an
        // SVN repository, a network share, a SharePoint site). Neither reveals a filesystem path -
        // `config` above stays null for FILES precisely because it IS the absolute path, which must
        // never reach a client. `recursive` only where the type has subfolders to skip.
        if (catalog.hasFileTypeFilter(r.getConnectorType())) m.put("fileTypes", r.getFileTypes());
        if (catalog.hasRecursiveScope(r.getConnectorType())) m.put("recursive", r.isRecursive());
        // Durable counterpart to the transient ConnectorOperationEvent SSE stream the card also
        // listens to: deletion is now asynchronous, so a card must still render as deleting after a
        // page refresh (which loses every in-memory operation the store was tracking).
        // Shown on every connector type, unlike the FILES-only knobs above: any corpus can be
        // extracted into the graph. The counts come with it so the card can say what the opt-in
        // has actually produced rather than only that it is on.
        // The connection this source reads through, and the server it is actually reached on -
        // through the resolver, never by reading a column: sync, the chat tools and the MCP tools
        // all consult it, and a second reading of the chain is how they would come to disagree.
        // The connection's NAME is shown only to the source's owner: to anybody else the source
        // lends nothing (see ConnectorEndpointResolver.resolve), and naming the owner's connection
        // would describe an account that is not theirs to use.
        var endpoint = endpointResolver.resolve(r, uid);
        boolean own = r.getUserId() == null || r.getUserId().equals(uid);
        m.put("connectionId", own && r.getConnectionId() != null ? r.getConnectionId().toString() : null);
        m.put("connectionName", own ? endpoint.connectionName() : null);
        m.put("serverUrl", showsServer(r.getConnectorType()) ? endpoint.baseUrl() : null);
        // The source's own page in the service, which its name links to - its plugin's answer. An
        // http(s) address or nothing: a config column can hold a path, which never reaches a client.
        String webUrl = r.getConnectorType().isFileBacked() ? null : catalog.plugin(r.getConnectorType())
                .map(p -> p.webUrl(StoredConfig.fields(r.getConnectorType(), r.getConfig()), endpoint.baseUrl()))
                .orElse(null);
        m.put("webUrl", webUrl != null && (webUrl.startsWith("https://") || webUrl.startsWith("http://")) ? webUrl : null);
        m.put("graphEnabled", r.isGraphEnabled());
        if (r.isGraphEnabled()) {
            var counts = connectorService.graphCounts(r.getId());
            m.put("graphEntities", counts.entities());
            m.put("graphRelations", counts.relations());
            var progress = connectorService.graphProgress(r.getId());
            m.put("graphItemsExtracted", progress.extracted());
            m.put("graphItemsPending", progress.pending());
            m.put("graphItemsFailed", progress.failed());
            // What is happening RIGHT NOW. Without these the card reads "0 of 20 items" for hours
            // on a corpus of large documents and looks broken while it is working perfectly.
            m.put("graphChunksDone", progress.chunksDone());
            m.put("graphChunksTotal", progress.chunkTotal());
            // Whether the worker has one of this source's items open this moment - what the card
            // shows a spinner for. Refreshed by the connector-graph event.
            m.put("graphExtracting", !progress.reading().isEmpty());
            m.put("graphModelUnreachable", connectorService.graphModelOutage().isPresent());
        }
        m.put("deleting", r.isDeleting());
        m.put("reindexing", r.isReindexInProgress());
        if (r.isReindexInProgress()) {
            // indexedWithEmbeddingModel still holds the OLD model until the reindex completes -
            // the UI wants to show what it's reindexing TO, i.e. the current global setting.
            m.put("reindexTargetModel", settingsService.getEmbeddingModel());
        }
        return m;
    }

    // Both file-backed types answer to the SAME wire name, "files".
    //
    // No deployment ever serves both - LOCAL_FOLDER exists only in the desktop edition and
    // UPLOADED_FILES only in the server one - so a client never has to tell them apart, and the
    // frontend keeps one Files tab, one icon and one file-type picker. The split it cares about is
    // "does adding files mean uploading bytes or pointing at a path", which it already answers from
    // FEATURES.fileUpload and always did.
    //
    // This also removes a whole class of mistake rather than adding one: because "files" is the
    // only name a client can say, there is no request a browser can make that causes the DESKTOP
    // edition to create an upload-backed connector. The mapping, not a validation rule, is what
    // makes that impossible.
    private String mapConnectorType(ai.proxyma.core.connector.ConnectorType ct) {
        return ct.wireName();
    }

    // "files" resolves to whichever file-backed type THIS edition owns - see mapConnectorType
    // above. Deliberately never yields the legacy FILES constant: nothing writes it any more, and
    // LegacyFilesTypeMigrator exists to remove the last of it.
    ConnectorType parseConnectorType(String type) {
        // Asked of the service, which asks whether this edition takes UPLOADS - not whether it has
        // accounts. The two differ on the Home Server (uploads, one user), where the Files tab asked
        // for local folders and listed nothing while its uploads indexed happily.
        if ("files".equals(type)) return connectorService.nativeFilesType();
        // Every other wire name is its type's own (ConnectorType's constructor) - a new type needs no
        // line here.
        java.util.List<ConnectorType> named = ConnectorType.forWireName(type);
        if (named.isEmpty()) throw new IllegalArgumentException("Unknown connector type: " + type);
        return named.get(0);
    }

    // Whether a source's card names the server it is read from: every type whose service has a server
    // address.
    private boolean showsServer(ConnectorType type) {
        ConnectorDescriptor d = catalog.descriptor(type);
        if (d == null || d.protocol() == null) return false;
        ProtocolDescriptor p = catalog.protocol(d.protocol());
        return p != null && p.hasBaseUrl();
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> removeConnector(@PathVariable UUID id, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            connectorService.removeConnector(id, uid);
            return ResponseEntity.noContent().build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/{id}/sync")
    public Mono<ResponseEntity<Map<String, Boolean>>> syncConnector(@PathVariable UUID id,
                                                                    @RequestParam(name = "pass", required = false) String pass,
                                                                    ServerWebExchange exchange) {
        boolean changes = "changes".equalsIgnoreCase(pass == null ? "" : pass.strip());
        if (pass != null && !pass.isBlank() && !changes && !"full".equalsIgnoreCase(pass.strip())) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            // By default (pass=full) a manual "Sync now" runs the connector's full enumeration.
            // The incremental path can only ever return items changed since lastSyncedAt, so it
            // cannot surface newly-enumerable items or reflect an upstream deletion - i.e. exactly
            // what someone pressing this button is asking for.
            //
            // pass=changes asks for the pass the scheduler would run instead - a CHANGES pass when
            // the source is eligible, so an incremental path can be exercised on demand. It is not a
            // forced CHANGES pass: the same eligibility decides (SyncService.reconcile) - a source
            // that has not completed a full pass, has no incremental listing, or is due its periodic
            // full pass still gets a full one - and the first-sync and slot rules hold as for any
            // "Sync now", which this is with forceFull off.
            boolean started = syncOrchestratorService.syncConnectorReportStarted(id, uid, !changes);
            return ResponseEntity.ok(Map.of("started", started));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Post-registration edits to what a connector covers: crawl bounds for WEB_PAGE
    // (maxDepth/maxPages/sameDomainOnly/renderJs/includePattern), extension filter + recursion for
    // FILES, and the relink of a local folder that moved (`path`). Deliberately NOT a place to
    // change a seedUrl - see ConnectorService.updateConnectorConfig(), which rejects everything it doesn't recognize and
    // kicks off a forced full re-sync so a narrowed scope actually drops the excluded items.
    // Extension vocabulary for the FILES file-type pickers, served from FileTypeSupport's own
    // constants so the checkbox list can never drift from what sync() actually walks. `custom` is
    // not enumerable here by design - a user may type any extension they like (see
    // FileTypeSupport.parseFileTypes), this is only what the UI offers as ready-made choices.
    @GetMapping("/file-types")
    public Mono<ResponseEntity<Map<String, Object>>> fileTypes() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "groups", ai.proxyma.core.connector.files.FileTypeSupport.extensionGroups(),
                "defaults", ai.proxyma.core.connector.files.FileTypeSupport.defaultExtensions())));
    }

    @PatchMapping("/{id}/config")
    public Mono<ResponseEntity<Map<String, String>>> updateConnectorConfig(@PathVariable UUID id,
                                                                         @RequestBody Map<String, Object> body,
                                                                         ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            try {
                connectorService.updateConnectorConfig(id, body, uid);
                return ResponseEntity.ok(Map.of("status", "ok"));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PatchMapping("/{id}/shared")
    public Mono<ResponseEntity<Void>> toggleShared(@PathVariable UUID id,
                                                    @RequestBody Map<String, Object> body,
                                                    ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            boolean shared = Boolean.TRUE.equals(body.get("shared"));
            connectorService.setShared(id, shared, uid);
            return ResponseEntity.ok().build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // The per-connector knowledge-graph opt-in. Turning it OFF also deletes what this connector
    // put into the graph - see ConnectorService.setGraphEnabled for why that is the only honest
    // meaning of "off".
    /**
     * How far the knowledge graph has got with this source, on its own so a dialog can poll it.
     *
     * <p>Its own endpoint rather than a re-read of the summary: the summary is a page-sized payload
     * built for a list of cards, and the one thing that moves here moves every passage - about once
     * a minute on a local model. Polling the list to watch one number would refetch every
     * connector's counts to do it.
     */
    @GetMapping("/{id}/graph/progress")
    public Mono<ResponseEntity<Map<String, Object>>> graphProgress(@PathVariable UUID id,
                                                                    ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            // SEC-47: the same gate as the item list - the owner, or anyone for a shared source. It
            // had none, and it names the documents being read, so any signed-in user could list the
            // titles of another person's private source by its id.
            connectorService.checkConnectorOwnership(id, userId.isEmpty() ? null : userId);
            var p = connectorService.graphProgress(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("indexed", p.indexed());
            m.put("extracted", p.extracted());
            m.put("pending", p.pending());
            m.put("failed", p.failed());
            m.put("chunksDone", p.chunksDone());
            m.put("chunksTotal", p.chunkTotal());
            // Which items those passages belong to, so an item list can mark the row being read
            // rather than print a total the reader has to match against twenty titles.
            m.put("reading", p.reading().stream().map(r -> {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("documentId", r.documentId().toString());
                row.put("title", r.title());
                row.put("chunksDone", r.chunksDone());
                row.put("chunksTotal", r.chunkCount());
                return row;
            }).toList());
            // WHY NOTHING MOVES, when the knowledge-graph model is not answering: the worker stands
            // down for longer each time, and this was a WARN line in a log nobody reads.
            m.put("modelUnreachable", connectorService.graphModelOutage().map(o -> {
                Map<String, Object> outage = new LinkedHashMap<String, Object>();
                outage.put("since", o.since().toString());
                outage.put("retryAt", o.retryAt().toString());
                outage.put("host", multiUser ? null : o.host());
                outage.put("reason", multiUser ? null : o.reason());
                outage.put("throughProxy", !multiUser && o.throughProxy());
                return outage;
            }).orElse(null));
            return ResponseEntity.ok(m);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PatchMapping("/{id}/graph")
    public Mono<ResponseEntity<Void>> toggleGraph(@PathVariable UUID id,
                                                   @RequestBody Map<String, Object> body,
                                                   ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            boolean enabled = Boolean.TRUE.equals(body.get("graphEnabled"));
            connectorService.setGraphEnabled(id, enabled, uid);
            return ResponseEntity.ok().build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Single-holder priority processing: setting priority=true moves the flag from whichever
    // connector currently holds it (see ConnectorService.setPriority()).
    @PatchMapping("/{id}/priority")
    public Mono<ResponseEntity<Void>> togglePriority(@PathVariable UUID id,
                                                      @RequestBody Map<String, Object> body,
                                                      ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            boolean priority = Boolean.TRUE.equals(body.get("priority"));
            connectorService.setPriority(id, priority, uid);
            return ResponseEntity.ok().build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Items currently downloading/converting/embedding - always small (bounded by in-flight
    // concurrency), never paginated. The modal pins these at the top of the item list so a newly
    // active item is always visible without scrolling, instead of auto-scrolling to find it
    // within a large paginated list.
    @GetMapping("/{id}/items/active")
    public Mono<ResponseEntity<List<Map<String, Object>>>> listActiveItems(@PathVariable UUID id,
                                                                            ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            List<TrackedDocument> docs = new java.util.ArrayList<>(connectorService.listActiveItems(id, uid));
            // AND the items the knowledge graph is reading, since 2026-09-24. They are COMPLETED,
            // so the paged list would otherwise hold them wherever they fall - a row being worked
            // on buried on page four. Pinned here, they sit at the top like an item being embedded.
            java.util.Set<UUID> seen = new java.util.HashSet<>();
            for (TrackedDocument d : docs) seen.add(d.getId());
            for (TrackedDocument d : connectorService.listGraphReadingItems(id, uid)) {
                if (seen.add(d.getId())) docs.add(d);
            }
            Map<String, UUID> fileIdBySourceId = resolveFileIds(docs);
            java.util.Set<UUID> graphDone = connectorService.graphDoneIds(id, docs);
            List<Map<String, Object>> items = docs.stream().map(d -> toItemMap(d, fileIdBySourceId, graphDone)).toList();
            return ResponseEntity.ok(items);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Everything else (queued/completed/failed/detected/modified/deleted), keyset-paginated - a
    // connector can have thousands of tracked documents, so the modal lazy-loads pages on scroll
    // instead of fetching them all at once. See ConnectorService.listInactiveItemsPaged() for why
    // this is keyset rather than offset pagination.
    //
    // The cursor is the three sort values of the last item on the previous page - failed rank,
    // touched time, id - and all three are omitted for the first page. It was (title, id) until
    // 2026-09-18, when the ordering became failed-first: a cursor has to carry every column the
    // ORDER BY uses, so changing the ordering necessarily changes the cursor. A client sending the
    // old cursorTitle gets the FIRST page back for every request, so the frontend has to move with
    // this one rather than degrade gracefully.
    @GetMapping("/{id}/items")
    public Mono<ResponseEntity<Map<String, Object>>> listItems(@PathVariable UUID id,
                                                                @RequestParam(required = false) Integer cursorFailed,
                                                                @RequestParam(required = false) String cursorTouched,
                                                                @RequestParam(required = false) UUID cursorId,
                                                                @RequestParam(defaultValue = "50") int size,
                                                                ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            java.time.Instant touched;
            try {
                touched = cursorTouched == null || cursorTouched.isBlank()
                        ? null : java.time.Instant.parse(cursorTouched);
            } catch (java.time.format.DateTimeParseException e) {
                // Refused rather than treated as "no cursor": silently starting over would hand
                // the caller the first page again, which an infinite-scroll client appends and
                // then asks for once more, forever.
                return ResponseEntity.badRequest().body(Map.<String, Object>of(
                        "error", "cursorTouched must be an ISO-8601 instant"));
            }
            ConnectorService.ItemPage result = connectorService.listInactiveItemsPaged(
                    id, uid, cursorFailed, touched, cursorId, clampSize(size, 200));
            Map<String, UUID> fileIdBySourceId = resolveFileIds(result.items());
            java.util.Set<UUID> graphDone = connectorService.graphDoneIds(id, result.items());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", result.items().stream().map(d -> toItemMap(d, fileIdBySourceId, graphDone)).toList());
            body.put("hasMore", result.hasMore());
            if (!result.items().isEmpty()) {
                TrackedDocument last = result.items().get(result.items().size() - 1);
                body.put("nextCursorFailed", ConnectorService.cursorFailedRank(last));
                java.time.Instant lastTouched = ConnectorService.cursorTouchedAt(last);
                body.put("nextCursorTouched", lastTouched != null ? lastTouched.toString() : null);
                body.put("nextCursorId", last.getId().toString());
            }
            return ResponseEntity.ok(body);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Bulk (server filesystem path -> FileMetadata.id) for every file-backed document in this
    // page - a delete affordance in the UI needs SOME id to call DELETE /api/v1/files/{fileId}
    // with, but sourceId (the raw absolute path) is deliberately withheld from the client below,
    // so this resolves the id server-side instead of ever sending the path itself.
    private Map<String, UUID> resolveFileIds(List<TrackedDocument> docs) {
        List<String> paths = docs.stream()
                .filter(d -> d.getConnectorType().isFileBacked())
                .map(TrackedDocument::getSourceId)
                .toList();
        if (paths.isEmpty()) return Map.of();
        Map<String, UUID> result = new java.util.HashMap<>();
        for (FileMetadata meta : fileMetadataRepository.findByStoragePathIn(paths)) {
            result.put(meta.getStoragePath(), meta.getId());
        }
        return result;
    }

    private Map<String, Object> toItemMap(TrackedDocument d, Map<String, UUID> fileIdBySourceId,
                                          java.util.Set<UUID> graphDone) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId().toString());
        m.put("sourceId", d.getConnectorType().isFileBacked() ? null : d.getSourceId());
        m.put("fileId", fileIdBySourceId.get(d.getSourceId()) != null
                ? fileIdBySourceId.get(d.getSourceId()).toString() : null);
        m.put("title", d.getTitle());
        m.put("status", d.getStatus().name());
        m.put("currentChunks", d.getCurrentChunks());
        m.put("totalChunks", d.getTotalChunks());
        m.put("contentSize", d.getContentSize());
        m.put("sourceSizeBytes", d.getSourceSizeBytes());
        m.put("failureMessage", SensitivePaths.scrub(d.getFailureMessage()));
        m.put("lastIndexedAt", d.getLastIndexedAt() != null ? d.getLastIndexedAt().toString() : null);
        // What the last run cost and when this row last moved. indexMs is null while a run is in
        // flight (see SyncService.processItem), which is how a client tells "still going" from
        // "took no time"; lastTouchedAt is what the list is ordered by, so a client that wants to
        // show why one row is above another needs it.
        m.put("indexStartedAt", d.getIndexStartedAt() != null ? d.getIndexStartedAt().toString() : null);
        m.put("indexMs", d.getIndexMs());
        m.put("lastTouchedAt", d.getLastTouchedAt() != null ? d.getLastTouchedAt().toString() : null);
        m.put("sourceLastModified", d.getSourceLastModified() != null ? d.getSourceLastModified().toString() : null);
        m.put("createdAt", d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
        // The knowledge graph has read THIS version of the item - the row's mark turns from the
        // embedded check to the brand one. False for a source with the graph off.
        m.put("graphDone", graphDone.contains(d.getId()));
        // The item's own web address, as its connector stated it while listing - what the dialog's
        // "open" link follows, so no page has to know how a service builds one. A web address only:
        // anything else a connector might keep there (a share path) is a server path.
        String url = d.getConnectorType().isFileBacked() ? null : d.getSourceUrl();
        m.put("sourceUrl", url != null && (url.startsWith("https://") || url.startsWith("http://")) ? url : null);
        return m;
    }

    // "Preview" button in the connector modal - the chunks currently stored in the BM25 index for
    // one document (never the original markdown, which isn't durably kept for anything but FILES
    // uploads - see ConnectorService.getDocumentChunks()'s own comment). Read-only, so shared
    // connectors are viewable by non-owners same as the item list itself (checkConnectorOwnership).
    @GetMapping("/{id}/items/{documentId}/preview")
    public Mono<ResponseEntity<Map<String, Object>>> previewDocument(@PathVariable UUID id,
                                                                       @PathVariable UUID documentId,
                                                                       ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            List<String> chunks = connectorService.getDocumentChunks(id, documentId, uid);
            return ResponseEntity.ok(Map.<String, Object>of("chunks", chunks));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // The two graph views behind the Waypoints button - one item's, and the whole source's.
    //
    // SHAPED FOR A LAYOUT, NOT FOR A MODEL. The agent's searchKnowledgeGraph tool returns triples
    // with endpoint NAMES substituted so a model can read them without joining; these return node
    // IDS on every edge, because a force layout needs stable identity and two entities that happen
    // to share a display name must not collapse into one circle. See GraphView for the rest of
    // why the two shapes are separate records.
    // PAGED, and the three query parameters are not interchangeable - see
    // KnowledgeGraphService.Window. `cap` chooses which nodes are in the picture at all, `offset`
    // and `limit` only choose how that picture is delivered. Omitting them asks for the whole
    // thing in one response, which is what any client that predates paging already does.
    @GetMapping("/{id}/items/{documentId}/graph")
    public Mono<ResponseEntity<Map<String, Object>>> documentGraph(@PathVariable UUID id,
                                                                   @PathVariable UUID documentId,
                                                                   @RequestParam(required = false) Integer cap,
                                                                   @RequestParam(required = false) Integer offset,
                                                                   @RequestParam(required = false) Integer limit,
                                                                   ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            return ResponseEntity.ok(graphPayload(
                    connectorService.graphViewForDocument(id, documentId, uid, window(cap, offset, limit))));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/{id}/graph")
    public Mono<ResponseEntity<Map<String, Object>>> connectorGraph(@PathVariable UUID id,
                                                                     @RequestParam(required = false) Integer cap,
                                                                     @RequestParam(required = false) Integer offset,
                                                                     @RequestParam(required = false) Integer limit,
                                                                     ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            return ResponseEntity.ok(graphPayload(
                    connectorService.graphViewForConnector(id, uid, window(cap, offset, limit))));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private static ai.proxyma.core.graph.service.KnowledgeGraphService.Window window(
            Integer cap, Integer offset, Integer limit) {
        var all = ai.proxyma.core.graph.service.KnowledgeGraphService.Window.all();
        if (cap == null && offset == null && limit == null) return all;
        return new ai.proxyma.core.graph.service.KnowledgeGraphService.Window(
                cap != null ? cap : all.cap(),
                offset != null ? offset : 0,
                limit != null ? limit : all.limit());
    }

    // Hand-mapped rather than serialised straight off the record, for the same reason every other
    // payload in this controller is: the record is a module's internal shape and the JSON is a
    // contract with a client that ships separately from it.
    private static Map<String, Object> graphPayload(ai.proxyma.core.graph.service.GraphView view) {
        List<Map<String, Object>> nodes = view.nodes().stream().map(n -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", n.id().toString());
            m.put("name", n.name());
            // Null when the extractor could not classify it. Sent as null rather than omitted or
            // defaulted, so the client can render "unclassified" as its own thing.
            m.put("type", n.type());
            m.put("mentions", n.mentions());
            m.put("degree", n.degree());
            return m;
        }).toList();
        List<Map<String, Object>> edges = view.edges().stream().map(e -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("source", e.sourceId().toString());
            m.put("target", e.targetId().toString());
            m.put("predicate", e.predicate());
            m.put("description", e.description());
            m.put("weight", e.weight());
            m.put("documentId", e.documentId() != null ? e.documentId().toString() : null);
            return m;
        }).toList();
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("nodes", nodes);
        payload.put("edges", edges);
        payload.put("truncated", view.truncated());
        // Whether another page of this same view exists - distinct from `truncated`, which says
        // the view will never hold everything the source does however many pages are fetched.
        payload.put("hasMore", view.hasMore());
        payload.put("totalNodes", view.totalNodes());
        payload.put("totalEdges", view.totalEdges());
        return payload;
    }

    @PostMapping("/{id}/retry-failed")
    public Mono<ResponseEntity<Void>> retryFailed(@PathVariable UUID id, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            connectorService.retryFailed(id, uid);
            return ResponseEntity.ok().build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/{id}/reindex")
    public Mono<ResponseEntity<Void>> reindexConnector(@PathVariable UUID id, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            connectorService.reindexConnector(id, uid);
            return ResponseEntity.ok().build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // One document, not the whole source - the affordance beside a single failed or stale item.
    // Owner only (the item list beside it is readable by anyone the source is shared with; spending
    // the owner's embedding budget is not), and 409 while that document is mid-run rather than
    // resetting a row the drain loop is holding. See ConnectorService.reindexDocument().
    @PostMapping("/{id}/items/{documentId}/reindex")
    public Mono<ResponseEntity<Map<String, String>>> reindexDocument(@PathVariable UUID id,
                                                                      @PathVariable UUID documentId,
                                                                      ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            try {
                connectorService.reindexDocument(id, documentId, uid);
                return ResponseEntity.ok(Map.of("status", "queued"));
            } catch (IllegalStateException e) {
                return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/register/files")
    public Mono<ResponseEntity<Map<String, String>>> registerFiles(@RequestBody Map<String, String> body,
                                                                     ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String path = body.get("path");
            // A missing or root path is refused by registerFiles with a sentence (400), not an NPE.
            String folderName = ConnectorService.folderNameOf(path);
            // Absent "recursive" means NO subfolders - Community's offer, which every default now
            // matches (2026-09-25). Asking for them is explicit, and the tier gate inside
            // ConnectorService.registerConnector() still refuses it on COMMUNITY.
            boolean recursive = "true".equalsIgnoreCase(body.get("recursive"));
            // Absent "fileTypes" means "every supported type" - the behavior of every folder
            // registered before the filter existed, and of an older frontend build.
            UUID id = connectorService.asNewSource(() -> connectorService.registerFiles(folderName, path, uid, recursive,
                    body.get("fileTypes")));
            return ResponseEntity.ok(Map.of("id", id.toString()));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // The crawl bounds the UI is allowed to offer, served from the same constants ConnectorService
    // validates against. Both the register and the edit modal read this instead of hardcoding
    // their own - they previously disagreed with each other AND with the backend, so a value the
    // form accepted could still be rejected, or silently clamped, without anything saying so.
    @GetMapping("/webpage/limits")
    public Mono<ResponseEntity<Map<String, Integer>>> webPageCrawlLimits() {
        // The product-wide ceiling - the same on every tier since the two-tier model.
        return Mono.just(ResponseEntity.ok(Map.of(
                "maxDepth", WebCrawlConnector.MAX_CRAWL_DEPTH,
                "maxPages", WebCrawlConnector.MAX_CRAWL_PAGES,
                "defaultDepth", WebCrawlConnector.DEFAULT_MAX_DEPTH,
                "defaultPages", WebCrawlConnector.DEFAULT_MAX_PAGES)));
    }

    @PostMapping("/register/webpage")
    public Mono<ResponseEntity<Map<String, String>>> registerWebPage(@RequestBody Map<String, Object> body,
                                                                     ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String seedUrl = asString(body.get("seedUrl"));
            String name = asString(body.get("name"));
            String includePattern = asString(body.get("includePattern"));
            UUID id = connectorService.registerWebPage(seedUrl, name,
                    asInt(body.get("maxDepth")), asInt(body.get("maxPages")),
                    asBool(body.get("sameDomainOnly")), asBool(body.get("renderJs")),
                    includePattern, uid);
            return ResponseEntity.ok(Map.of("id", id.toString()));
        }).subscribeOn(REMOTE));
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }
    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? null : Integer.valueOf(o.toString().trim()); } catch (Exception e) { return null; }
    }
    private static Boolean asBool(Object o) {
        if (o instanceof Boolean b) return b;
        return o == null ? null : Boolean.valueOf(o.toString().trim());
    }

    // A dialog registration of a source that already exists - see ConnectorService.asNewSource.
    // Its own handler rather than the per-endpoint catch, whose "Cannot connect to ..." prefix
    // would misreport a source that connected fine the first time.
    @ExceptionHandler(ai.proxyma.core.registry.service.SourceAlreadyRegisteredException.class)
    public Mono<ResponseEntity<Map<String, String>>> handleAlreadyRegistered(
            ai.proxyma.core.registry.service.SourceAlreadyRegisteredException ex) {
        // A code and the name, so the dialog can say it in the reader's language; `message` is the
        // English sentence for any other caller.
        return Mono.just(ResponseEntity.status(409).body(Map.of(
                "error", "already_registered", "name", ex.sourceName(), "message", ex.getMessage())));
    }

    /**
     * Adds a source of ANY type - the generic dialog, and every client that is not a bespoke one.
     *
     * <p>Body: {@code {type, connectionId?, config: {field: value}, recursive?, fileTypes?, force?}}.
     * {@code type} is the wire name; {@code config} holds the descriptor's fields, which the server
     * checks exactly as the dialog drew them (ConnectorService.register). The answers are the ones
     * every registration endpoint gives: 200 with the id; 400 with the reason - a field, the access
     * probe, no connection; 403 for a license; 409 {@code already_registered} for a source that
     * exists, and 409 {@code duplicate} for one somebody else already shares, unless {@code force}.
     */
    @PostMapping("/register")
    public Mono<ResponseEntity<Map<String, String>>> register(@RequestBody Map<String, Object> body,
                                                               ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            ConnectorService.SourceRequest request;
            try {
                request = sourceRequest(body);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
            try {
                if (!"true".equalsIgnoreCase(String.valueOf(body.get("force")))) {
                    var duplicate = connectorService.findSharedDuplicate(request, uid);
                    if (duplicate.isPresent()) {
                        return ResponseEntity.status(409).body(Map.of("error", "duplicate",
                                "matchedConnectorName", duplicate.get().getName()));
                    }
                }
                UUID id = connectorService.asNewSource(() -> connectorService.register(request, uid));
                return ResponseEntity.ok(Map.of("id", id.toString()));
            } catch (IllegalArgumentException | IllegalStateException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        }).subscribeOn(REMOTE));
    }

    @SuppressWarnings("unchecked")
    ConnectorService.SourceRequest sourceRequest(Map<String, Object> body) {
        Object rawType = body.get("type");
        if (rawType == null || rawType.toString().isBlank()) throw new IllegalArgumentException("Choose a type of source.");
        ConnectorType type = parseConnectorType(rawType.toString().trim());
        Object rawConfig = body.get("config");
        Map<String, Object> config = rawConfig instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Object recursive = body.get("recursive");
        return new ConnectorService.SourceRequest(type,
                connectionId(body.get("connectionId") == null ? Map.of() : Map.of("connectionId", body.get("connectionId").toString())),
                config, recursive == null ? null : asBool(recursive), asString(body.get("fileTypes")));
    }

    // The connection the dialog chose, or null for the caller's default. A value that is not a
    // uuid is a client bug, and reported as one rather than silently treated as "default".
    private static UUID connectionId(Map<String, String> body) {
        String raw = body.get("connectionId");
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That is not a connection id.");
        }
    }

    /**
     * A folder on a Windows file server.
     *
     * <p>NO DUPLICATE CHECK ACROSS USERS, unlike every other credentialed type here. This one is
     * not shareable: two people registering the same path read it with their own domain accounts
     * and may legitimately see different files, so collapsing them onto one source would show one
     * of them documents the ACLs keep from them.
     *
     * <p>Every refusal - a malformed path, no Network share connection for its server, a folder
     * the owner cannot read - arrives as a 400 carrying the reason, because the person who typed
     * it is still looking at the dialog and every one of those has a different fix.
     */
    @PostMapping("/register/networkshare")
    public Mono<ResponseEntity<Map<String, String>>> registerNetworkShare(@RequestBody Map<String, String> body,
                                                                          ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            boolean recursive = !"false".equalsIgnoreCase(body.get("recursive"));
            try {
                // The Network share connection to read through - must be the caller's and for the
                // path's own server; absent picks their connection for that server.
                UUID connectionId = connectionId(body);
                UUID id = connectorService.asNewSource(() -> connectorService.registerNetworkShare(body.get("uncPath"), recursive, uid,
                        body.get("fileTypes"), connectionId));
                return ResponseEntity.ok(Map.of("id", id.toString()));
            } catch (IllegalArgumentException | IllegalStateException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        }).subscribeOn(REMOTE));
    }

    private Mono<String> resolveUserId(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("");
    }
}
