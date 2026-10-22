package ai.proxyma.core.registry.service;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.common.net.OutboundAddressPolicy;
import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.spi.ConfigValidator;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.Edition;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.StoredConfig;
import ai.proxyma.core.connector.networkshare.UncPath;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.connector.files.UploadSandbox;
import ai.proxyma.core.connector.svn.SvnUrls;
import ai.proxyma.core.connector.webpage.WebCrawlConnector;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.event.ConnectorOperationEvent;
import ai.proxyma.core.registry.event.ConnectorRegisteredEvent;
import ai.proxyma.core.registry.event.ConnectorRemovedEvent;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.quota.UserQuotaPort;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.settings.event.ProviderSettingsChangedEvent;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.contracts.telemetry.TelemetryEventType;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);

    private final RegisteredConnectorRepository connectorRepository;
    private final TrackedDocumentRepository documentRepository;
    private final SyncOrchestratorService syncOrchestratorService;
    private final VectorStoreService vectorStoreService;
    private final BM25IndexService bm25IndexService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final ApplicationEventPublisher eventPublisher;
    private final AppPaths appPaths;
    private final SettingsService settingsService;
    private final ConnectorEndpointResolver endpointResolver;
    private final ConnectorConnectionService connections;
    private final UserQuotaPort quotaPort;
    private final FeatureGateService featureGateService;
    private final RestClient externalRestClient;
    // Injected rather than driven off ConnectorRemovedEvent/ConnectorSyncedEvent. Those two do
    // fire unconditionally, but between them they carry a connectorId and nothing else, so a
    // listener would have to re-read the connector row to learn even its type - and on the
    // removal path that row is already deleted by the time a listener runs, which is the reason
    // ConnectorRemovedEvent copies fields out in the first place. Recording here also means
    // registration, removal and sync are measured at one layer instead of two.
    private final TelemetryShipper telemetryShipper;
    // Which of the two file sources this deployment offers, and the ONLY thing that decides it -
    // see FileSourceMode, which is also what UploadedFilesConnector and FileController ask.
    private final FileSourceMode fileSourceMode;

    // ONE REGISTRATION OF A SOURCE AT A TIME (the 0.27.0 feedback: a Jira project listed twice).
    // The "already registered?" lookup and the save are minutes apart at worst - the access probe
    // between them reaches the remote server and waits behind a running sync of the same type - and
    // nothing else stopped two overlapping registrations from both finding nothing: parallel tool
    // calls, a background sub-agent beside the main agent, the agent and a dialog at once. A lock
    // per source identity, held from the lookup to the save, makes the second one find the first.
    // Reentrant, because registerSvnRepo and registerWebPage take it before their own checks and
    // then reach registerConnector. In memory: every edition runs as one process.
    private final Map<String, ReentrantLock> registrationLocks = new ConcurrentHashMap<>();

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    // /data, matching LocalDiskStorageService and UploadedFilesConnector, which have always
    // defaulted to it. This defaulted to BLANK, and blank is the one value that turns
    // requireAllowedLocalPath() into a no-op - so a deployment that had uploads but no configured
    // root would write bytes to /data while accepting a registration pointing anywhere on the host.
    // Only the server edition's own properties file kept that from being reachable, which is a
    // guard held up by a line in a different module.
    @Value("${proxyma.storage.local.root:/data}")
    private String storageRoot;

    public ConnectorService(RegisteredConnectorRepository connectorRepository,
                           TrackedDocumentRepository documentRepository,
                           @org.springframework.context.annotation.Lazy SyncOrchestratorService syncOrchestratorService,
                           VectorStoreService vectorStoreService,
                           BM25IndexService bm25IndexService,
                           KnowledgeGraphService knowledgeGraphService,
                           ApplicationEventPublisher eventPublisher,
                           AppPaths appPaths,
                           SettingsService settingsService,
                           ConnectorEndpointResolver endpointResolver,
                           ConnectorConnectionService connections,
                           // @Lazy breaks a real circular dependency: ConnectorService -> UserQuotaPort
                           // (UserQuotaService) -> StorageStatsService -> ConnectorService (the last edge
                           // is StorageStatsService's own pre-existing constructor dependency). Same
                           // pattern already used above for syncOrchestratorService.
                           @Autowired(required = false) @org.springframework.context.annotation.Lazy UserQuotaPort quotaPort,
                           FeatureGateService featureGateService,
                           @Qualifier("externalRestClientBuilder") RestClient.Builder externalRestClientBuilder,
                           TelemetryShipper telemetryShipper,
                           FileSourceMode fileSourceMode) {
        this.connectorRepository = connectorRepository;
        this.documentRepository = documentRepository;
        this.syncOrchestratorService = syncOrchestratorService;
        this.vectorStoreService = vectorStoreService;
        this.bm25IndexService = bm25IndexService;
        this.knowledgeGraphService = knowledgeGraphService;
        this.eventPublisher = eventPublisher;
        this.appPaths = appPaths;
        this.settingsService = settingsService;
        this.endpointResolver = endpointResolver;
        this.connections = connections;
        this.quotaPort = quotaPort;
        this.featureGateService = featureGateService;
        this.externalRestClient = externalRestClientBuilder.build();
        this.telemetryShipper = telemetryShipper;
        this.fileSourceMode = fileSourceMode;
    }

    // Every per-type question - shareable, file-type filter, subfolders, edition, the service a type
    // reads through - is its descriptor's. Setter-injected, so the constructor tests build this with
    // is unchanged; a test gets every pre-framework descriptor and no plugins.
    private ConnectorCatalog catalog = ConnectorCatalog.descriptorsOnly();

    @Autowired(required = false)
    void setCatalog(ConnectorCatalog catalog) {
        this.catalog = catalog;
    }

    public List<RegisteredConnector> listAll() {
        return connectorRepository.findAll();
    }

    public List<RegisteredConnector> listByUser(String userId) {
        if (userId == null) return connectorRepository.findAll();
        return connectorRepository.findByUserId(userId);
    }

    public List<RegisteredConnector> listByType(ConnectorType type) {
        return connectorRepository.findByConnectorType(type);
    }

    public record UserConnectorStats(long connectorCount, long documentCount) {}

    // Bulk connector/document counts for a page of users (Admin Users table) - one query instead
    // of one listByUser(uid) call per user. Users absent from the returned map have no connectors.
    public Map<String, UserConnectorStats> getConnectorStatsForUsers(List<String> userIds) {
        if (userIds.isEmpty()) return Map.of();
        Map<String, UserConnectorStats> result = new HashMap<>();
        for (Object[] row : connectorRepository.countAndSumItemCountGroupedByUserId(userIds)) {
            result.put((String) row[0], new UserConnectorStats((long) row[1], (long) row[2]));
        }
        return result;
    }

    // Bulk userId -> owned collection ids (ChromaDB/BM25 collection naming: connectorId with
    // hyphens stripped) for a page of users - one query instead of one
    // getCollectionIdsForUser(uid) call per user. Users absent from the returned map own no
    // connectors. Shared (userId=null) connectors are intentionally excluded - this powers each
    // user's own storage total, not what they can search.
    public Map<String, List<String>> getOwnedCollectionIdsForUsers(List<String> userIds) {
        if (userIds.isEmpty()) return Map.of();
        Map<String, List<String>> result = new HashMap<>();
        for (Object[] row : connectorRepository.findIdAndUserIdByUserIdIn(userIds)) {
            String collectionId = ((UUID) row[0]).toString().replace("-", "");
            result.computeIfAbsent((String) row[1], k -> new ArrayList<>()).add(collectionId);
        }
        return result;
    }

    // What the sources' index takes on disk - their vectors and their keyword (BM25) index - for
    // the Sources page's header (0.28.0 feedback: "show DB sizes"). With no user (desktop, one
    // user) it is the whole store, measured the way Administration's storage breakdown measures
    // it; with one, the collections that user OWNS, as their storage quota counts them - a shared
    // source someone else owns takes none of their disk.
    public long getIndexSizeBytes(String userId) {
        if (userId == null) return vectorStoreService.getTotalSizeBytes() + bm25IndexService.getTotalSizeBytes();
        List<String> owned = getOwnedCollectionIdsForUsers(List.of(userId)).getOrDefault(userId, List.of());
        if (owned.isEmpty()) return 0;
        long total = vectorStoreService.getSizeBytesForCollections(owned);
        for (String collectionId : owned) total += bm25IndexService.getSizeBytes(collectionId);
        return total;
    }

    // Paginated per-type listing for the Sources page tabs - a single connector type can run
    // into the thousands of registered connectors, so the "type" tab lazy-loads pages instead of
    // fetching everything up front like listByUser()/listAll() do for the (much smaller) "All" tab.
    // A blank/null query returns the unfiltered listing (all types when type is also null - used
    // by the chat input's @mention dropdown, which lists every connector the instant "@" is typed,
    // before any filter text follows it); a non-blank query filters by connector title (name), not
    // document content - this is the Sources page search box. includeShared surfaces the "show
    // shared connectors from other users" toggle - only meaningful when userId is non-null (server
    // mode with an authenticated caller); desktop's null-userId path already returns everything,
    // so it's untouched either way.
    public Page<RegisteredConnector> listByTypePaged(ConnectorType type, String userId, String query, int page, int size, boolean includeShared) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        boolean hasQuery = query != null && !query.isBlank();
        boolean withShared = userId != null && includeShared;
        if (type == null) {
            if (!hasQuery) {
                if (userId == null) return connectorRepository.findAll(pageable);
                return withShared
                        ? connectorRepository.findByUserIdOrShared(userId, pageable)
                        : connectorRepository.findByUserId(userId, pageable);
            }
            if (userId == null) return connectorRepository.findByNameContainingIgnoreCase(query.trim(), pageable);
            return withShared
                    ? connectorRepository.findByUserIdOrSharedAndNameContainingIgnoreCase(userId, query.trim(), pageable)
                    : connectorRepository.findByUserIdAndNameContainingIgnoreCase(userId, query.trim(), pageable);
        }
        if (hasQuery) {
            if (userId == null) return connectorRepository.findByConnectorTypeAndNameContainingIgnoreCase(type, query.trim(), pageable);
            return withShared
                    ? connectorRepository.findByConnectorTypeAndUserIdOrSharedAndNameContainingIgnoreCase(type, userId, query.trim(), pageable)
                    : connectorRepository.findByUserIdAndConnectorTypeAndNameContainingIgnoreCase(userId, type, query.trim(), pageable);
        }
        if (userId == null) return connectorRepository.findByConnectorType(type, pageable);
        return withShared
                ? connectorRepository.findByConnectorTypeAndUserIdOrShared(type, userId, pageable)
                : connectorRepository.findByUserIdAndConnectorType(userId, type, pageable);
    }

    // "All" tab summary: aggregate source/document counts plus a capped preview of each
    // connector type's most recent connectors. One combined count+sum query and one top-5 query
    // per connector type (rather than the naive count + sum + full paged-with-its-own-count
    // query this replaced) - still bounded by the fixed number of connector types, never by how
    // many connectors are registered.
    public ConnectorSummary getConnectorSummary(String userId) {
        return getConnectorSummary(userId, false);
    }

    // includeShared: see listByTypePaged's overload. activeIndexingCount deliberately stays
    // own-only regardless of the toggle - a live "shared docs indexing" counter would need
    // joining TrackedDocument back to connector ownership for a secondary, non-essential metric.
    public ConnectorSummary getConnectorSummary(String userId, boolean includeShared) {
        boolean withShared = userId != null && includeShared;
        long totalSources = 0;
        long totalDocs = 0;
        Map<ConnectorType, TypeSummary> byType = new LinkedHashMap<>();
        for (ConnectorType type : ConnectorType.values()) {
            List<Object[]> rows = userId == null
                    ? connectorRepository.countAndSumItemCountByConnectorType(type)
                    : withShared
                        ? connectorRepository.countAndSumItemCountByUserIdOrSharedAndConnectorType(userId, type)
                        : connectorRepository.countAndSumItemCountByUserIdAndConnectorType(userId, type);
            Object[] row = rows.get(0);
            long count = (long) row[0];
            long itemSum = (long) row[1];
            totalSources += count;
            totalDocs += itemSum;
            if (count == 0) continue;
            List<RegisteredConnector> preview = userId == null
                    ? connectorRepository.findTop5ByConnectorTypeOrderByCreatedAtDesc(type)
                    : withShared
                        ? connectorRepository.findByUserIdOrSharedAndConnectorTypeOrderByCreatedAtDesc(userId, type, PageRequest.of(0, 5))
                        : connectorRepository.findTop5ByUserIdAndConnectorTypeOrderByCreatedAtDesc(userId, type);
            byType.put(type, new TypeSummary(count, preview));
        }
        long activeIndexingCount = userId == null
                ? documentRepository.countByStatusIn(ACTIVE_ITEM_STATUSES)
                : documentRepository.countByStatusInForUser(ACTIVE_ITEM_STATUSES, userId);
        return new ConnectorSummary(totalSources, totalDocs, byType, activeIndexingCount);
    }

    public record TypeSummary(long total, List<RegisteredConnector> preview) {}

    // activeIndexingCount is a real-time DB snapshot (COUNT of DOWNLOADING/CONVERTING/EMBEDDING
    // items), not accumulated from SSE events - SSE-driven accumulation under-reports until every
    // currently-active item happens to emit its next status event after the client subscribes,
    // since the shared SSE sink only replays the single latest event to new subscribers, not the
    // full history of every connector's in-flight items.
    public record ConnectorSummary(long totalSources, long totalDocs, Map<ConnectorType, TypeSummary> byType,
                                   long activeIndexingCount) {}

    public List<RegisteredConnector> listVisibleToUser(String userId) {
        Set<UUID> seen = new LinkedHashSet<>();
        List<RegisteredConnector> result = new ArrayList<>();
        for (RegisteredConnector r : connectorRepository.findByUserId(userId)) {
            if (seen.add(r.getId())) result.add(r);
        }
        for (RegisteredConnector r : connectorRepository.findBySharedTrue()) {
            if (seen.add(r.getId())) result.add(r);
        }
        return result;
    }

    public List<RegisteredConnector> listShared() {
        return connectorRepository.findBySharedTrue();
    }

    // THE place that answers "which connectors may this caller see", for every caller that reaches
    // the search/tool surface rather than the owner-scoped REST list.
    //
    // A null userId means two completely different things depending on deployment mode, and
    // conflating them was a real bug: in server mode it is an unauthenticated caller, who may see
    // only what someone deliberately shared; in desktop mode there is no auth at all, every
    // connector is stored with userId=null, and that single local user owns all of them. Callers
    // used to hardcode the server-mode reading (`userId != null ? listVisibleToUser : listShared`),
    // so on desktop - where `shared` is false on every connector, and the UI doesn't even offer the
    // toggle, sharing being a multi-user concept - getConnectorStatus reported zero connectors and
    // search ran against zero collections. Every local folder was invisible to the agent.
    public List<RegisteredConnector> listVisibleTo(String userId) {
        if (userId != null) return listVisibleToUser(userId);
        return multiUser ? listShared() : listAll();
    }

    // Collection-id counterpart of listVisibleTo(), for the search paths that need ids rather
    // than entities. Same null-userId reasoning - see above.
    public List<String> visibleCollectionIds(String userId) {
        if (userId != null) return getCollectionIdsForUser(userId);
        return multiUser ? getSharedCollectionIds() : toCollectionIds(listAll());
    }

    private static List<String> toCollectionIds(List<RegisteredConnector> connectors) {
        return connectors.stream().map(r -> r.getId().toString().replace("-", "")).toList();
    }

    /**
     * Document titles for a batch of search hits, keyed by the document each came from.
     *
     * <p>A chunk carries the {@code sourceId} of the document it came from and nothing else that
     * names it - {@code EmbeddingService} writes {@code source}, {@code url} and
     * {@code connectorType} into the metadata, and a title is not among them. It lives on
     * {@link TrackedDocument}, which this module owns, so this is the one place that can put a
     * retrieval hit and its title back together. The search API's {@code title} was null on every
     * result for exactly as long as nothing did (found 2026-09-23).
     *
     * <p>BY (SOURCE, SOURCEID), never by sourceId alone ({@link DocumentKey}). This was keyed by the
     * sourceId, and the first visible row to answer won - right for a UNC path registered twice,
     * and wrong for everything a SaaS connector indexes: {@code ticket:1} is a Zendesk ticket in
     * one source and a Freshdesk ticket in the next, and a Freshdesk result came back under the
     * Zendesk ticket's title.
     *
     * <p>Scoped to what {@code userId} can see, deliberately and not just for tidiness: a key whose
     * source the caller cannot see names nothing, whatever the hit claims - so no title is lent by
     * a source somebody else owns. The visibility rule is {@link #listVisibleTo}, the same one the
     * search itself ran under.
     *
     * <p>Missing entries are normal rather than an error - a deleted document, or a chunk written
     * by something that keeps no registry row - so callers leave the title unset for those.
     */
    public Map<DocumentKey, String> resolveDocumentTitles(String userId, Collection<DocumentKey> documents) {
        if (documents == null || documents.isEmpty()) return Map.of();
        Set<DocumentKey> wanted = new HashSet<>();
        for (DocumentKey k : documents) if (k != null) wanted.add(k);
        Set<UUID> visible = listVisibleTo(userId).stream().map(RegisteredConnector::getId)
                .collect(Collectors.toSet());
        Set<UUID> connectorIds = new HashSet<>();
        Set<String> sourceIds = new HashSet<>();
        for (DocumentKey k : wanted) {
            if (!visible.contains(k.connectorId())) continue;
            connectorIds.add(k.connectorId());
            sourceIds.add(k.sourceId());
        }
        if (connectorIds.isEmpty()) return Map.of();
        Map<DocumentKey, String> titles = new HashMap<>();
        for (Object[] row : documentRepository.findTitlesBySourceIds(connectorIds, sourceIds)) {
            // The two IN lists match across each other - source A's id under source B - so only
            // the pairs that were asked for are kept.
            DocumentKey key = new DocumentKey((UUID) row[0], (String) row[1]);
            if (wanted.contains(key) && row[2] != null) titles.put(key, (String) row[2]);
        }
        return titles;
    }

    // Resolves "item key/id -> owning RegisteredConnector" for MCP tools that fetch a single
    // live item (e.g. a Jira issue, a Confluence page) by name/key rather than searching.
    // Scoped to connectors the caller can actually see - see listVisibleTo() for what that means
    // per deployment mode.
    public Optional<RegisteredConnector> findVisibleByTypeAndName(String userId, ConnectorType type, String name) {
        List<RegisteredConnector> visible = listVisibleTo(userId);
        return visible.stream()
                .filter(r -> r.getConnectorType() == type && r.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    // Resolves @mentioned connector names (from the chat UI) to the actual connectors, scoped to
    // what the caller can see - same convention as findVisibleByTypeAndName, see listVisibleTo().
    // Used by SearchTool to restrict a search to only the mentioned connectors' collections.
    public List<RegisteredConnector> resolveVisibleByNames(String userId, List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        Set<String> wanted = new LinkedHashSet<>();
        for (String n : names) {
            if (n != null && !n.isBlank()) wanted.add(n.trim().toLowerCase());
        }
        List<RegisteredConnector> visible = listVisibleTo(userId);
        return visible.stream()
                .filter(r -> wanted.contains(r.getName().toLowerCase()))
                .toList();
    }

    // Owner/shared snapshot for a still-existing connector - used by EventStreamController to
    // decide whether a given SSE subscriber may see events about this connector, without needing
    // to duplicate ownership fields onto every connector-scoped event class. Empty when the
    // connector no longer exists (e.g. a race between an action and its own trailing event).
    public record OwnerScope(String userId, boolean shared) {}

    public Optional<OwnerScope> getOwnerScope(UUID connectorId) {
        return connectorRepository.findById(connectorId)
                .map(r -> new OwnerScope(r.getUserId(), r.isShared()));
    }

    /**
     * A source somebody ELSE shares that is the one this registration would add - the dialog's
     * pre-flight check, which offers the shared copy instead (409 duplicate). A pure read, never part
     * of registration itself.
     *
     * <p>THE SAME THING TO READ, not merely the same name: the same type, the same stored fields and
     * name, AND the same server - the one the other source's connection reaches, against the one this
     * registration would read through. A connection is the account, and the other person's is always
     * another account, so it cannot be compared; the server can, and it is what makes their project
     * PROJ the same project as yours. Project PROJ on another company's Jira is not, though its
     * fields are identical. Only a shareable type has the check at all (storedForm), which is what
     * keeps a personal mailbox or calendar - the same "INBOX" on the same address for everyone - out of
     * it. Where the registration has no connection to say which server, the fields alone decide.
     */
    public Optional<RegisteredConnector> findSharedDuplicate(SourceRequest request, String requesterId) {
        Optional<Map.Entry<String, String>> stored = storedForm(request, requesterId);
        if (stored.isEmpty()) return Optional.empty();
        String server = normalizeServer(registrationServer(request.type(), requesterId, request.connectionId()));
        return connectorRepository.findSharedOrOwnByConnectorTypeAndConfigAndName(request.type(),
                        stored.get().getKey(), stored.get().getValue(), requesterId).stream()
                .filter(r -> !Objects.equals(r.getUserId(), requesterId))
                .filter(r -> server == null || server.equals(normalizeServer(endpointResolver.resolve(r).baseUrl())))
                .findFirst();
    }

    // The server a registration would read from: its chosen connection's, else the default's.
    private String registrationServer(ConnectorType type, String userId, UUID connectionId) {
        if (catalog.protocolFor(type) == null) return null;
        Optional<ResolvedConnection> connection = connectionId != null
                ? connections.resolveOwned(userId, connectionId)
                : defaultConnection(type, userId);
        return connection.map(ResolvedConnection::baseUrl).orElse(null);
    }

    // The connection a source of this type reads through when none is named: the owner's default of
    // the service it reads through, else another service's connection whose sign-in reaches it too
    // (ConnectorEndpointResolver.defaultConnection - an Atlassian Cloud site for Jira and Confluence).
    private Optional<ResolvedConnection> defaultConnection(ConnectorType type, String userId) {
        ConnectionProtocol own = catalog.protocolFor(type);
        if (own == null) return Optional.empty();
        Optional<ResolvedConnection> mine = connections.defaultFor(userId, own);
        return mine.isPresent() ? mine : endpointResolver.defaultConnection(type, userId);
    }

    // ── Files ─────────────────────────────────────────────────────────────────

    /**
     * The one file-backed connector type THIS DEPLOYMENT owns:
     * {@link ConnectorType#UPLOADED_FILES} where bytes are uploaded, {@link ConnectorType#LOCAL_FOLDER}
     * where a folder is pointed at.
     *
     * Every registration and every "how many file connectors does this user have" lookup goes
     * through here rather than naming a constant, because naming one would be wrong in the other
     * mode - a deployment that registered UPLOADED_FILES over a folder it had never copied would be
     * claiming Proxyma owns the user's own documents, and would put them within reach of the cleanup
     * path that reclaims storage. The two types exist precisely so that cannot be expressed; this
     * method is where that guarantee is made, once.
     *
     * <p>IT ASKS {@link FileSourceMode}, NOT {@code multiUser}, since 0.8.0. Those were the same
     * question for as long as "has accounts" and "takes uploads" were the same deployments, and the
     * home server is the case where they came apart: one user, no accounts, and no filesystem worth
     * pointing at either, because "pick a folder" inside a container means a bind mount the operator
     * has to get right before anything can be indexed at all.
     */
    public ConnectorType nativeFilesType() {
        return fileSourceMode.uploadsEnabled() ? ConnectorType.UPLOADED_FILES : ConnectorType.LOCAL_FOLDER;
    }

    // The agent's registerFiles tool. No subfolders unless asked for, like every other way of
    // adding a folder - Community's offer is the default everywhere (2026-09-25).
    //
    // Named by the folder's own name, as the Sources dialog names it (ConnectorController
    // .registerFiles). Named by its whole path, the agent's source was a different name from the
    // dialog's for the same folder, so neither registration found the other and the folder was
    // listed twice (found reviewing 0.27.1). A source the agent registered under its path before
    // is found through registerConnector's path lookup and renamed.
    public UUID registerFiles(String folderPath, String userId) {
        return registerFiles(folderNameOf(folderPath), folderPath, userId, false, null);
    }

    public static String folderNameOf(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) return folderPath;
        try {
            Path name = Path.of(folderPath.strip()).getFileName();
            return name != null ? name.toString() : folderPath;
        } catch (java.nio.file.InvalidPathException e) {
            return folderPath;
        }
    }

    public UUID registerFiles(String folderName, String folderPath, String userId) {
        requireUsableLocalPath(folderPath);
        requireAllowedLocalPath(folderPath, userId);
        return registerConnector(nativeFilesType(), folderName, folderPath, userId);
    }

    /**
     * A folder is registered by its FULL path, and a path that names a folder.
     *
     * <p>A relative one ("docs", from the agent) was resolved against the backend's working
     * directory - the install directory - and indexed whatever was there; a drive root or a missing
     * path reached Paths.get in the controller and came back as a 500.
     */
    static void requireUsableLocalPath(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            throw new IllegalArgumentException("Choose the folder to index.");
        }
        Path path;
        try {
            path = Path.of(folderPath.strip());
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException(folderPath + " is not a valid folder path on this machine.");
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Give the folder's full path - \"" + folderPath + "\" is not one.");
        }
        if (path.getFileName() == null) {
            throw new IllegalArgumentException("A whole drive cannot be added as one folder - choose a folder on it.");
        }
    }

    // requestedRecursive is what the caller asked for, and what is stored: subfolders are in every
    // tier since the two-tier model.
    public UUID registerFiles(String folderName, String folderPath, String userId, boolean requestedRecursive) {
        return registerFiles(folderName, folderPath, userId, requestedRecursive, null);
    }

    // fileTypes is a comma-separated extension list, or null for "every supported type" - see
    // RegisteredConnector.fileTypes. Normalized (and intersected with what the ingest pipeline can
    // actually convert) by FileTypeSupport.parseFileTypes before it is stored, so an unsupported
    // or misspelled extension can never reach the walk.
    public UUID registerFiles(String folderName, String folderPath, String userId,
                              boolean requestedRecursive, String fileTypes) {
        requireUsableLocalPath(folderPath);
        requireAllowedLocalPath(folderPath, userId);
        return registerConnector(nativeFilesType(), folderName, folderPath, userId,
                requestedRecursive, fileTypes);
    }

    // Canonical stored form: the parsed/validated set joined back into a comma-separated string,
    // or null when the input named nothing usable. Storing the normalized form (rather than the
    // user's raw text) means every reader - sync, the inspect preview, the connector modal - sees
    // the same list, and a connector can never carry an extension the walk would ignore.
    private static String normalizeFileTypes(String raw) {
        var parsed = ai.proxyma.core.connector.files.FileTypeSupport.parseFileTypes(raw);
        return parsed == null ? null : String.join(",", parsed);
    }

    /**
     * Where a file source is allowed to point, in a deployment that takes uploads.
     *
     * <p>A registered path is walked and indexed, so an unconstrained one could walk the storage
     * root itself - every other user's uploads, and the server's own key material. The sandbox is
     * what stops that, and this is where it is enforced at REGISTRATION time;
     * {@code UploadedFilesSession} re-checks the same thing at every sync, so a row that predates
     * this guard or was edited in the database still cannot be used that way.
     *
     * <p>Both of those now ask {@link UploadSandbox} rather than each building the path themselves,
     * which is the point of that class: a second line of defence is only a defence while it agrees
     * with the first.
     *
     * <p><b>A null userId is the single local user, not "unauthenticated".</b> The home server has
     * no accounts, so there is no id to demand and its sandbox has no {@code users/{id}} segment.
     * Demanding one there - which this did, with "Files registration requires an authenticated
     * user" - would refuse every upload on a deployment that is working exactly as designed. Server
     * mode still demands it, because there it genuinely separates tenants: see
     * {@link FileSourceMode#uploadsAreAttributed()}.
     */
    private void requireAllowedLocalPath(String folderPath, String userId) {
        if (!fileSourceMode.uploadsEnabled() || storageRoot == null || storageRoot.isBlank()) return;
        if (folderPath == null || folderPath.isBlank()) {
            throw new IllegalArgumentException("Folder path must not be empty");
        }
        if (fileSourceMode.uploadsAreAttributed() && (userId == null || userId.isBlank())) {
            throw new IllegalArgumentException("Files registration requires an authenticated user");
        }
        if (!UploadSandbox.contains(storageRoot, userId, Path.of(folderPath))) {
            throw new IllegalArgumentException("Folder path must be inside the upload directory");
        }
    }

    public void removeFiles(UUID folderId, String userId) {
        removeConnector(folderId, userId);
    }

    // ── Jira ──────────────────────────────────────────────────────────────────

    // The server and the credential come from a CONNECTION - the one chosen in the dialog, else the
    // owner's default for the service (see registrationConnection); the source stores its descriptor's
    // fields as JSON, as every type on the connector framework does, and sync reads the connection,
    // so editing a connection's URL moves every source on it.
    public UUID registerJiraProject(String projectKey, String userId) {
        return registerJiraProject(projectKey, userId, null);
    }

    public UUID registerJiraProject(String projectKey, String userId, UUID connectionId) {
        return registerKeyed(ConnectorType.JIRA, "projectKey", projectKey, userId, connectionId);
    }

    // A Jira project or a Confluence space by its key: validated against the type's descriptor, named
    // by its plugin, stored as JSON - the generic registration's shape, for the callers that predate
    // it (the agent's register tools and the old per-type endpoints).
    private UUID registerKeyed(ConnectorType type, String field, String key, String userId, UUID connectionId) {
        ConnectorDescriptor descriptor = catalog.descriptor(type);
        Map<String, String> config = ConfigValidator.validate(descriptor.fields(),
                key == null ? Map.of() : Map.of(field, key));
        String name = catalog.plugin(type).map(p -> p.displayName(config)).orElse(config.get(field));
        ResolvedConnection connection = registrationConnection(type, userId, connectionId);
        return registerConnector(type, name, ConfigJson.write(config), userId, true, null, connection.id());
    }

    public void removeJiraProject(UUID projectId, String userId) {
        removeConnector(projectId, userId);
    }

    // ── Confluence ────────────────────────────────────────────────────────────

    public UUID registerConfluenceSpace(String spaceKey, String userId) {
        return registerConfluenceSpace(spaceKey, userId, null);
    }

    public UUID registerConfluenceSpace(String spaceKey, String userId, UUID connectionId) {
        return registerKeyed(ConnectorType.CONFLUENCE, "spaceKey", spaceKey, userId, connectionId);
    }

    public void removeConfluenceSpace(UUID spaceId, String userId) {
        removeConnector(spaceId, userId);
    }

    // ── Web Page (crawl) ──────────────────────────────────────────────────────

    // Registers a website for crawling + indexing from a seed URL. Crawl parameters are stored in the
    // source's config as its descriptor's fields, JSON (read back by WebCrawlConnector when a session
    // opens, and clamped there again). name is a human label shown on the
    // connector card - when not supplied, defaults to the seed page's own <title>, falling back to
    // just the host if that page can't be fetched/parsed in time (see fetchPageTitle()).
    public UUID registerWebPage(String seedUrl, String name, Integer maxDepth, Integer maxPages,
                                Boolean sameDomainOnly, Boolean renderJs, String includePattern, String userId) {
        if (seedUrl == null || !seedUrl.matches("(?i)^https?://.+")) {
            throw new IllegalArgumentException("seedUrl must be a full http:// or https:// address");
        }
        // Bounded by the product-wide ceiling, the same on every tier since the two-tier model.
        int depth = requireInRange("Crawl depth", maxDepth, WebCrawlConnector.DEFAULT_MAX_DEPTH,
                0, WebCrawlConnector.MAX_CRAWL_DEPTH);
        int pages = requireInRange("Max pages", maxPages, WebCrawlConnector.DEFAULT_MAX_PAGES,
                1, WebCrawlConnector.MAX_CRAWL_PAGES);
        String trimmedSeed = singleLine("The start address", seedUrl);
        String pattern = validIncludePattern(includePattern);
        // The seed only; every page the crawl finds is checked per hop by the shared client and,
        // when rendered, by WebRenderService. Off except on the public sandbox.
        OutboundAddressPolicy.check(trimmedSeed);

        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("seedUrl", trimmedSeed);
        cfg.put("maxDepth", String.valueOf(depth));
        cfg.put("maxPages", String.valueOf(pages));
        cfg.put("sameDomainOnly", String.valueOf(sameDomainOnly == null || sameDomainOnly));
        cfg.put("renderJs", String.valueOf(renderJs != null && renderJs));
        if (pattern != null) cfg.put("includePattern", pattern);

        // A website's identity is its SEED URL, never its display name. registerConnector()'s own
        // dedup matches on name alone, which is wrong here in both directions: two unrelated sites
        // that happen to share a <title> collapse into one connector, and - the reported bug -
        // re-registering the SAME site with different crawl bounds found the existing connector by
        // its unchanged title, resynced it with its ORIGINAL config and discarded the new one
        // entirely. The UI reported "added - indexing started" and the crawl then re-walked the
        // same pages it already had, so raising depth/maxPages appeared to do nothing at all.
        // Matching on the seed URL here, and applying the newly requested bounds to the connector
        // that is found, makes a re-registration mean what it says.
        // Under the seed URL's lock, so two registrations of one site cannot both miss each other.
        String config = ConfigJson.write(cfg);
        return underRegistrationLock(ConnectorType.WEB_PAGE, userId, trimmedSeed,
                () -> registerWebPageLocked(trimmedSeed, config, name, userId));
    }

    private UUID registerWebPageLocked(String trimmedSeed, String config, String name, String userId) {
        var existingSite = findWebPageBySeedUrl(trimmedSeed, userId);
        if (existingSite.isPresent()) {
            RegisteredConnector r = existingSite.get();
            boolean configChanged = !config.equals(r.getConfig());
            if (configChanged) r.setConfig(config);
            if (name != null && !name.isBlank()) r.setName(name.trim());
            connectorRepository.save(r);
            log.info("[Registry] Web page already registered: id={} seedUrl={} configChanged={}",
                    r.getId(), trimmedSeed, configChanged);
            UUID existingId = r.getId();
            // A changed scope only takes effect through a FULL pass - an incremental one would
            // never notice pages the widened bounds newly reach, nor drop the ones a narrowed
            // scope now excludes. Same reasoning as updateConnectorConfig().
            Thread.ofVirtual().name("resync-" + existingId).start(() -> {
                try {
                    syncOrchestratorService.syncConnectorReportStarted(existingId, userId, configChanged);
                } catch (Exception e) {
                    log.warn("[Registry] Re-sync failed for connector={}: {}", existingId, e.getMessage());
                }
            });
            return existingId;
        }

        String label = (name != null && !name.isBlank())
                ? name.trim()
                : firstNonBlank(fetchPageTitle(trimmedSeed), hostOf(trimmedSeed));
        return registerConnector(ConnectorType.WEB_PAGE, label, config, userId);
    }

    // Existing WEB_PAGE connector for this owner whose config carries exactly this seed URL. Scans
    // the owner's own web-page connectors rather than adding a repository query, because the seed
    // URL is one field inside the config and not a column of its own - the set being scanned is a
    // single user's websites, which is small.
    private Optional<RegisteredConnector> findWebPageBySeedUrl(String seedUrl, String userId) {
        List<RegisteredConnector> candidates = userId != null
                ? connectorRepository.findByUserIdAndConnectorType(userId, ConnectorType.WEB_PAGE)
                : connectorRepository.findByConnectorType(ConnectorType.WEB_PAGE).stream()
                        .filter(r -> r.getUserId() == null).toList();
        // Never one being DELETED. Remove a website and add it straight back, and this found the
        // source still being torn down, re-used its id - and the teardown then took the "new"
        // registration with it, so it never indexed. Found by the functional suite
        // (WebAndRecursiveIndexingIT.aWebsiteCanBeAddedBackAfterRemoval).
        return candidates.stream()
                .filter(r -> !r.isDeleting())
                .filter(r -> seedUrl.equalsIgnoreCase(StoredConfig.fields(ConnectorType.WEB_PAGE, r.getConfig()).get("seedUrl")))
                .findFirst();
    }

    // Rejects an out-of-range crawl bound instead of silently clamping it. Silent clamping is what
    // made "I asked for depth 300 and got depth 20" invisible - nothing in the UI, the config or
    // the logs said the number had been changed out from under the request.
    private static int requireInRange(String label, Integer value, int fallback, int min, int max) {
        if (value == null) return fallback;
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    label + " must be between " + min + " and " + max + " (got " + value + ").");
        }
        return value;
    }

    public void removeWebPage(UUID connectorId, String userId) {
        removeConnector(connectorId, userId);
    }

    private static String hostOf(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h != null ? h : url;
        } catch (Exception e) {
            return url;
        }
    }

    private static final Pattern TITLE_TAG_PATTERN =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Duration TITLE_FETCH_TIMEOUT = Duration.ofSeconds(4);

    // Best-effort <title> peek at the seed URL, used only as the connector name default (never
    // blocks registration on a slow/unreachable site - see the bounded future.get() below).
    // externalRestClient's shared ClientHttpRequestFactory has a 300s read timeout sized for real
    // crawl/embed work, far too long to hold up an interactive "Register" click, so this bounds the
    // WAIT rather than the request itself; an abandoned slow fetch just keeps running in the
    // background and its result is discarded (registration has already fallen back to the host by
    // then). On BlockingWork's threads, like every other call that waits on a remote server.
    private String fetchPageTitle(String seedUrl) {
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                String html = externalRestClient.get().uri(URI.create(seedUrl)).retrieve().body(String.class);
                if (html == null) return null;
                Matcher m = TITLE_TAG_PATTERN.matcher(html);
                return m.find() ? m.group(1) : null;
            }, BlockingWork.executor());
            String raw = future.get(TITLE_FETCH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (raw == null || raw.isBlank()) return null;
            String title = HtmlUtils.htmlUnescape(raw.trim()).replaceAll("\\s+", " ").trim();
            return title.isBlank() ? null : title;
        } catch (Exception e) {
            log.debug("[Registry] Could not fetch page title for {}: {}", seedUrl, e.getMessage());
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    // ── Bitbucket ─────────────────────────────────────────────────────────────

    // The probe that used to live here moved into registerConnector(), which every registration
    // path reaches - so Jira, Confluence, SharePoint and the rest are now checked too, not just the
    // two connectors that happened to be wired up for it, and the check runs under the same lock
    // the sync path takes instead of against a shared bean from a request thread.
    public UUID registerBitbucketRepo(String projectKey, String repoSlug, String userId) {
        return registerBitbucketRepo(projectKey, repoSlug, userId, null);
    }

    // Stored as its descriptor's fields, JSON, and named "<projectKey>/<repoSlug>" - the generic
    // registration's shape, which this keeps for the callers that predate it.
    public UUID registerBitbucketRepo(String projectKey, String repoSlug, String userId, UUID connectionId) {
        Map<String, Object> given = new LinkedHashMap<>();
        if (projectKey != null) given.put("projectKey", projectKey);
        if (repoSlug != null) given.put("repoSlug", repoSlug);
        ConnectorType type = ConnectorType.BITBUCKET;
        Map<String, String> config = ConfigValidator.validate(catalog.descriptor(type).fields(), given);
        String name = catalog.plugin(type).map(p -> p.displayName(config))
                .orElse(config.get("projectKey") + "/" + config.get("repoSlug"));
        ResolvedConnection connection = registrationConnection(type, userId, connectionId);
        return registerConnector(type, name, ConfigJson.write(config), userId, true, null, connection.id());
    }

    public void removeBitbucketRepo(UUID repoId, String userId) {
        removeConnector(repoId, userId);
    }

    // ── SVN ───────────────────────────────────────────────────────────────────

    // `repoPath` is relative to the configured SVN root, e.g. "projects/my-repo". A whole URL is
    // still accepted, as long as it lies under the connection's server - people paste the URL they
    // have - and refused on any other server, where the connection's credential must not go.
    //
    // THREE CHECKS BEFORE ANYTHING IS SAVED, since 2026-09-14, after a source registered from a
    // VisualSVN web-UI address indexed zero items and could not be corrected:
    //  1. A pasted browser address or web-UI path is turned into the repository URL svn needs
    //     (SvnConnector.normalizeRepoUrl / normalizeRepoPath).
    //  2. A source with the same NAME but a different URL is refused. The name is the last path
    //     segment, so a corrected URL, or another repository whose last folder is also "trunk",
    //     matched the old source by name and silently re-synced its old URL.
    //  3. The URL is probed with the user's credential (svn info), so a wrong URL or password is an
    //     error now rather than a source that reports 0 items. In the service, not the controller,
    //     so the agent's registerSvnRepo tool and MCP clients get the same checks.
    public UUID registerSvnRepo(String repoPath, String repoName, String userId) {
        return registerSvnRepo(repoPath, repoName, userId, null, true, null);
    }

    // `repoPath` is joined onto the connection's server URL, which is a repository ROOT. What is
    // stored as the source's config is the whole repository URL that join produces, as its field
    // repoPath, and that is what sync checks out - the connection supplies only the credential.
    //
    // recursive and fileTypes are the source's scope, as for a folder: whether the listing descends
    // below the repository URL, and which extensions it keeps (null = the type's default - the svn
    // indexed-extensions setting, else the built-in list).
    public UUID registerSvnRepo(String repoPath, String repoName, String userId, UUID connectionId,
                                boolean recursive, String fileTypes) {
        ResolvedConnection connection = registrationConnection(ConnectorType.SVN, userId, connectionId);
        String url = SvnUrls.isUrl(repoPath)
                ? ai.proxyma.core.connector.svn.SvnConnector.normalizeRepoUrl(repoPath)
                : joinUrl(connection.baseUrl(),
                        ai.proxyma.core.connector.svn.SvnConnector.normalizeRepoPath(repoPath));
        // A FULL URL MUST LIE UNDER THE CONNECTION'S SERVER. The probe below and every sync send the
        // connection's credential to this URL - Basic, or an NTLM exchange - and a URL on any other
        // host (one the agent was handed in a document, say) would send it there. The live tools
        // refuse such a URL for that reason; registration did not. SvnUrls is the one rule for both,
        // and the connector checks it again before each command it runs.
        if (!SvnUrls.isUnder(connection.baseUrl(), url)) {
            throw new IllegalArgumentException("That repository URL is not on the server of the SVN connection \""
                    + connection.name() + "\" (" + SvnUrls.canonical(connection.baseUrl()) + "), and its saved "
                    + "credential is only sent there. Use a path below that server, or add a connection to the "
                    + "repository's own server in Settings > Connections and register it through that.");
        }
        // svn runs as its own process, outside the shared client's per-hop check, so on the public
        // sandbox this is the application's only look at where it will go - and there it also refuses
        // svn://, svn+ssh:// and file://.
        OutboundAddressPolicy.check(url);
        String name = (repoName == null || repoName.isBlank() || repoName.equals("!"))
                ? lastPathSegment(url) : repoName;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("repoPath", url);
        if (!name.equals(lastPathSegment(url))) fields.put("repoName", name);
        String config = ConfigJson.write(fields);

        // The name check and the save under one lock - the same one registerConnector takes for this
        // name - so a second registration of the same repository waits and then finds the first.
        return underRegistrationLock(ConnectorType.SVN, userId, name, () -> {
            (userId != null
                    ? connectorRepository.findByUserIdAndConnectorTypeAndNameIgnoreCase(userId, ConnectorType.SVN, name)
                    : connectorRepository.findByConnectorTypeAndNameIgnoreCase(ConnectorType.SVN, name))
                    .filter(existing -> !sameUrl(repositoryOf(existing), url))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("An SVN source named \"" + name + "\" already exists for "
                                + repositoryOf(existing) + ". Remove it first, or register this repository under a different name.");
                    });

            // Check 3 is registerConnector()'s own probe (SvnConnector.verifyAccess), which reports an
            // svn error code as the sentence that says what to do about it.
            return registerConnector(ConnectorType.SVN, name, config, userId, recursive, fileTypes, connection.id());
        });
    }

    // The repository URL an SVN source reads.
    private static String repositoryOf(RegisteredConnector source) {
        return StoredConfig.fields(ConnectorType.SVN, source.getConfig()).get("repoPath");
    }

    private static boolean sameUrl(String a, String b) {
        return a != null && b != null && a.replaceAll("/+$", "").equalsIgnoreCase(b.replaceAll("/+$", ""));
    }

    private static String lastPathSegment(String url) {
        String[] segments = url.replaceAll("/+$", "").split("/");
        return segments.length == 0 ? url : java.net.URLDecoder.decode(segments[segments.length - 1],
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String joinUrl(String base, String path) {
        String trimmedBase = base.replaceAll("/+$", "");
        String trimmedPath = path == null ? "" : path.replaceAll("^/+", "");
        return trimmedPath.isEmpty() ? trimmedBase : trimmedBase + "/" + trimmedPath;
    }

    /**
     * The connection a NEW source is registered through: the one the caller chose, which must be
     * theirs and able to serve the type (ConnectorCatalog.serves - its own service's, or one whose
     * sign-in reaches it too, as an Atlassian Cloud site serves Jira and Confluence), else their
     * default for it. Registration cannot proceed without one - the message names where to add it,
     * because the person reading it can do that themselves.
     */
    public ResolvedConnection registrationConnection(ConnectorType type, String userId, UUID connectionId) {
        ConnectionProtocol protocol = catalog.protocolFor(type);
        if (protocol == null) throw new IllegalArgumentException(type + " sources do not use a connection.");
        String service = catalog.displayName(protocol);
        // The license FIRST - since the two tiers, only an ENTERPRISE type (Microsoft 365) can fail
        // it. Without the license no connection can even be created, so asking for one first would
        // answer "add a connection" to somebody who cannot; the license is the reason, and it has to
        // be the one they are told (not_licensed, 403).
        checkLicensed(type);
        if (connectionId != null) {
            return connections.resolveOwned(userId, connectionId)
                    .filter(c -> catalog.serves(c.protocol(), c.authKind(), type))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "That " + service + " connection does not exist, or is not yours."));
        }
        return defaultConnection(type, userId)
                .orElseThrow(() -> new IllegalArgumentException("You have no " + service
                        + " connection yet. Add one in Settings > Connections, then add this source."));
    }

    public void removeSvnRepo(UUID repoId, String userId) {
        removeConnector(repoId, userId);
    }

    // ── SharePoint ────────────────────────────────────────────────────────────

    public void removeSharePointSite(UUID siteId, String userId) {
        removeConnector(siteId, userId);
    }

    // ── Network share ─────────────────────────────────────────────────────────

    /**
     * Registers a folder on a Windows file server.
     *
     * <p>THE NAME IS THE NORMALIZED UNC PATH, and so is the config's one field ({@code uncPath}).
     * There is no separate display name to take: a share folder has no title of its own, and the
     * path is what its owner recognizes it by. Normalizing before this point is what lets duplicate detection see
     * {@code \\HOST\Share\a} and {@code //host/Share/a} as one source.
     *
     * <p>No cross-user duplicate check, because this type is not shareable - two users indexing the
     * same path are reading it with different accounts and may legitimately see different files.
     *
     * @param uncPath  any form a person might paste; parsed and normalized here so a malformed one
     *                 is refused with a message naming the shape wanted.
     * @param recursive whether to descend into subfolders.
     */
    public UUID registerNetworkShare(String uncPath, boolean recursive, String userId) {
        return registerNetworkShare(uncPath, recursive, userId, null, null);
    }

    // fileTypes as for a folder: null = every supported type.
    public UUID registerNetworkShare(String uncPath, boolean recursive, String userId, String fileTypes) {
        return registerNetworkShare(uncPath, recursive, userId, fileTypes, null);
    }

    /**
     * Through a Network share CONNECTION since 2026-10-01, and only one for the path's own server:
     * the connection's server is the one host its sources may reach. {@code connectionId} is the
     * caller's choice, which must be theirs and name that server; null picks the caller's
     * connection for that server (their default first), so the agent's tool, which names a path and
     * nothing else, reaches the right account without being told which.
     */
    public UUID registerNetworkShare(String uncPath, boolean recursive, String userId, String fileTypes,
                                     UUID connectionId) {
        UncPath path = UncPath.parse(uncPath);
        String normalized = path.toUnc();
        ResolvedConnection connection = networkShareConnection(path.host(), userId, connectionId);
        return registerConnector(ConnectorType.NETWORK_SHARE, normalized, ConfigJson.write(Map.of("uncPath", normalized)),
                userId, recursive, fileTypes, connection.id());
    }

    /** The caller's Network share connection that may read a path on {@code host}. */
    ResolvedConnection networkShareConnection(String host, String userId, UUID connectionId) {
        if (connectionId != null) {
            ResolvedConnection chosen = registrationConnection(ConnectorType.NETWORK_SHARE, userId, connectionId);
            if (!host.equalsIgnoreCase(chosen.baseUrl())) {
                throw new IllegalArgumentException("The connection '" + chosen.name() + "' is for the file server "
                        + chosen.baseUrl() + ", not " + host + ". Choose or add a connection for " + host + ".");
            }
            return chosen;
        }
        return connections.resolveAll(userId, ConnectionProtocol.NETWORK_SHARE).stream()
                .filter(c -> host.equalsIgnoreCase(c.baseUrl()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("You have no Network share connection for the file server "
                        + host + " yet. Add one in Settings > Connections, then add this folder."));
    }

    public void removeNetworkShare(UUID shareId, String userId) {
        removeConnector(shareId, userId);
    }

    // -- Types identified by their configuration -------------------------------------------

    /**
     * A source of a type whose identity is its configuration and never its name
     * ({@link ConnectorPlugin#identifiedByConfig}) - an Outlook mail folder or calendar, whose
     * identity is (kind, mailbox, folder or calendar). registerConnector treats a matching NAME as the
     * same source and re-syncs it; two calendars both called "Calendar" (the owner's and a shared
     * mailbox's) would then be one. So the same-source test runs here first, by
     * {@link ConnectorPlugin#storedIdentity}, and a different source whose name happens to clash gets
     * a number. The probe, the license and the edition refusal are registerConnector's, as for every
     * type.
     */
    private UUID registerByIdentity(ConnectorType type, ConnectorPlugin plugin, Map<String, String> validated,
                                    SourceRequest request, String userId) {
        Map<String, String> fields = plugin.prepare(validated);
        String identity = plugin.identity(fields);
        String name = plugin.displayName(fields);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Fill in what identifies the source.");
        String cfg = ConfigJson.write(fields);
        if (cfg != null && cfg.length() > MAX_CONFIG_CHARS) {
            throw new IllegalArgumentException("This source's settings are too long to keep.");
        }
        UUID connectionId = request.connectionId();
        List<RegisteredConnector> mine = userId != null
                ? connectorRepository.findByUserIdAndConnectorType(userId, type)
                : connectorRepository.findByConnectorType(type);
        // The same configuration is the same source only through the same account - the connection:
        // an Outlook "me" is a different mailbox for every Microsoft account (see
        // findExistingThroughConnection). A source with no usable connection is matched too, to be
        // repaired.
        UUID chosen = chosenConnection(type, userId, connectionId);
        for (RegisteredConnector existing : mine) {
            boolean sameAccount = chosen == null || chosen.equals(existing.getConnectionId())
                    || lacksUsableConnection(existing, type);
            if (sameAccount && identity.equals(plugin.storedIdentity(existing.getConfig()))) {
                return registerConnector(type, existing.getName(), existing.getConfig(), userId,
                        true, null, connectionId);
            }
        }
        String unique = name.strip();
        for (int n = 2; hasName(mine, unique); n++) unique = name.strip() + " (" + n + ")";
        boolean recursive = request.recursive() == null || request.recursive();
        return registerConnector(type, unique, cfg, userId, recursive, request.fileTypes(), connectionId);
    }

    private static boolean hasName(List<RegisteredConnector> connectors, String name) {
        return connectors.stream().anyMatch(r -> name.equalsIgnoreCase(r.getName()));
    }

    // ── Local folders, identified by path ───────────────────────────────────

    private List<RegisteredConnector> ownLocalFolders(String userId) {
        return userId != null
                ? connectorRepository.findByUserIdAndConnectorType(userId, ConnectorType.LOCAL_FOLDER)
                : connectorRepository.findByConnectorType(ConnectorType.LOCAL_FOLDER);
    }

    /** This owner's local folder source over {@code path}, other than {@code except}, if any. */
    private Optional<RegisteredConnector> findLocalFolderAt(String userId, String path, UUID except) {
        if (path == null || path.isBlank()) return Optional.empty();
        return ownLocalFolders(userId).stream()
                .filter(r -> except == null || !except.equals(r.getId()))
                .filter(r -> !r.isDeleting() && samePath(r.getConfig(), path))
                .findFirst();
    }

    // The folder's name, or "name (2)", "name (3)"... when another of this owner's folders has it.
    private String uniqueLocalFolderName(String userId, String name, UUID except) {
        List<RegisteredConnector> others = ownLocalFolders(userId).stream()
                .filter(r -> except == null || !except.equals(r.getId()))
                .toList();
        String unique = name;
        for (int n = 2; hasName(others, unique); n++) unique = name + " (" + n + ")";
        return unique;
    }

    // Two spellings of one folder: normalized, and compared without case where the file system
    // ignores it (Windows).
    static boolean samePath(String a, String b) {
        if (a == null || b == null) return false;
        try {
            String x = Path.of(a.strip()).toAbsolutePath().normalize().toString();
            String y = Path.of(b.strip()).toAbsolutePath().normalize().toString();
            return java.io.File.separatorChar == '\\' ? x.equalsIgnoreCase(y) : x.equals(y);
        } catch (java.nio.file.InvalidPathException e) {
            return a.strip().equals(b.strip());
        }
    }

    // ── Teams ─────────────────────────────────────────────────────────────────

    public void removeTeamsChannel(UUID channelId, String userId) {
        removeConnector(channelId, userId);
    }

    public void reindexConnector(UUID id, String userId) {
        RegisteredConnector r = connectorRepository.findById(id).orElse(null);
        if (r == null) return;
        if (userId != null && !userId.equals(r.getUserId())) {
            throw new IllegalArgumentException("Connector does not belong to caller");
        }

        eventPublisher.publishEvent(new ConnectorOperationEvent(
                this, id, "reindex", ConnectorOperationEvent.Phase.STOPPING_SYNC));

        Thread.ofVirtual().name("reindex-prepare-" + id).start(() -> {
            try {
                performReindex(id, userId);
            } catch (Exception e) {
                log.error("[Registry] Reindex failed for connector={}: {}", id, e.getMessage(), e);
            }
        });
    }

    // Same split as removeConnector()/performRemoval(): the caller's request returns as soon as the
    // reindex is accepted, and everything from the (up to 30s) wait for an in-flight sync to stop
    // onward runs in the background, reported through ConnectorOperationEvent and the connector's own
    // reindexInProgress flag.
    private void performReindex(UUID id, String userId) {
        syncOrchestratorService.waitForSyncToStop(id);
        // Read again after the wait, which can be thirty seconds: the row the request read is
        // stale by then - the sync it waited for wrote its counters, status and checkpoint, and
        // the owner may have edited the source - and saving it below put all of that back.
        RegisteredConnector r = connectorRepository.findById(id).orElse(null);
        if (r == null || r.isDeleting()) {
            log.info("[Registry] Reindex of connector={} dropped - removed while it waited", id);
            return;
        }

        String collectionId = id.toString().replace("-", "");
        String currentModel = settingsService.getEmbeddingModel();
        String currentProvider = settingsService.getEmbeddingProvider();
        String previousModel = r.getIndexedWithEmbeddingModel();
        String previousProvider = r.getIndexedWithEmbeddingProvider();
        // previousProvider is only null for connectors indexed before this field existed - treat
        // that as "unknown, don't force a reindex on provider alone" (mirrors previousModel's own
        // null-means-never-indexed guard below), so legacy connectors aren't spuriously flagged as
        // changed the first time this code runs.
        boolean modelChanged = previousModel != null && (!previousModel.equals(currentModel)
                || (previousProvider != null && !previousProvider.equals(currentProvider)));
        // ChromaDB collections have a fixed dimension set at creation and cannot hold old- and
        // new-model rows together, so a model change there still needs the disposable shadow
        // collection + atomic swap. Postgres can mix dimensions safely under one collection id
        // (see PgVectorStoreService's per-dimension partial indexes), so it skips the shadow
        // entirely and replaces documents in place as each one completes - old vector deleted, new
        // one live, immediately, no waiting for the whole connector to finish.
        boolean useShadow = modelChanged && !vectorStoreService.supportsMixedDimensionCollection();

        if (modelChanged) {
            r.setReindexInProgress(true);
        }

        if (useShadow) {
            // No eager delete of the live collection - it stays fully intact and searchable
            // (under the OLD model) for the entire duration of the reindex. Instead, the new
            // embeddings are built into a disposable "shadow" collection; SyncService swaps it in
            // atomically once every item completes (see SyncService.process()). See
            // RegisteredConnector.reindexShadowCollectionId for the crash-safety rationale.
            String shadowCollectionId = SourceCollections.shadowOf(collectionId);
            log.info("[Registry] Embedding model changed ({} -> {}), building shadow collection={} for connector={} (live collection stays searchable until swap)",
                    previousModel, currentModel, shadowCollectionId, id);
            eventPublisher.publishEvent(new ConnectorOperationEvent(
                    this, id, "reindex", ConnectorOperationEvent.Phase.CLEARING_INDEX));
            // Only the disposable shadow is cleared here (never the live collection) - guards
            // against a leftover shadow from a previously failed/aborted model-change reindex
            // accumulating alongside this fresh pass's output.
            try { vectorStoreService.deleteCollection(shadowCollectionId); }
            catch (Exception e) { log.warn("[Registry] Failed to clear stale shadow vectors during reindex for connector={}: {}", id, e.getMessage()); }
            try { bm25IndexService.deleteCollection(shadowCollectionId); }
            catch (Exception e) { log.warn("[Registry] Failed to clear stale shadow BM25 index during reindex for connector={}: {}", id, e.getMessage()); }
            r.setReindexShadowCollectionId(shadowCollectionId);
        } else if (modelChanged) {
            log.info("[Registry] Embedding model changed ({} -> {}), replacing documents in place for connector={} (live collection stays searchable, old vectors removed per-document as each completes)",
                    previousModel, currentModel, id);
        } else {
            log.info("[Registry] Same embedding model ({}), incremental reindex for connector={}",
                    currentModel, id);
        }

        eventPublisher.publishEvent(new ConnectorOperationEvent(
                this, id, "reindex", ConnectorOperationEvent.Phase.RESETTING));

        // The source's LIVE documents, in one statement - DELETED rows are kept for good, and
        // queuing them made the drain fetch items gone upstream and inflated the live count the
        // purge sanity floor reads (TrackedDocumentRepository.requeueLiveForReindex).
        // reindexReplace is true whenever writes target the live collection directly (same-model
        // incremental, or model-changed-but-mixed-dimension-capable) - there's an old row at this
        // source_id to delete first. False only for the shadow-collection path, which starts empty.
        List<UUID> live = documentRepository.findIdsByConnectorIdAndStatusNot(id, TrackedDocument.Status.DELETED);
        documentRepository.requeueLiveForReindex(id, !useShadow,
                TrackedDocument.Status.QUEUED, TrackedDocument.Status.DELETED);
        Path cacheDir = appPaths.tempDir().resolve("markdown-cache");
        for (UUID docId : live) {
            try { Files.deleteIfExists(cacheDir.resolve(docId + ".md")); }
            catch (Exception ignored) {}
        }

        r.setProcessedCount(0);
        r.setFailedCount(0);
        r.setAllItemsFetched(false);
        connectorRepository.save(r);
        log.info("[Registry] Reset {} items for reindex of connector={} (incremental={})",
                live.size(), id, !useShadow);

        Thread.ofVirtual().name("reindex-" + id).start(() -> {
            try {
                syncOrchestratorService.syncConnector(id, userId);
            } catch (Exception e) {
                log.warn("[Registry] Reindex sync failed for connector={}: {}", id, e.getMessage());
            }
        });
    }

    public void retryFailed(UUID id, String userId) {
        RegisteredConnector r = connectorRepository.findById(id).orElse(null);
        if (r == null) return;
        if (userId != null && !userId.equals(r.getUserId())) {
            throw new IllegalArgumentException("Connector does not belong to caller");
        }
        // One statement for the source's FAILED documents. Somebody asked for these to be tried
        // again, so the retry budget starts over: without the reset, an item that had already
        // exhausted MAX_AUTO_RETRIES was requeued here and then left alone again by
        // reconcileItem's exhausted-item guard - "Retry failed" appeared to do something and
        // changed nothing.
        int failed = documentRepository.requeueFailed(id, TrackedDocument.Status.QUEUED, TrackedDocument.Status.FAILED);
        // Re-read after the requeue rather than saving the row this request read before it.
        r = connectorRepository.findById(id).orElse(null);
        if (r == null) return;
        r.setFailedCount(0);
        // Force the next sync to go through the FULL enumeration path (connector.sync()), not
        // the incremental one (connector.syncSince()) - incremental only returns items whose
        // remote content changed since lastSyncedAt, so the freshly-reset QUEUED rows here (whose
        // remote content is unchanged, they just failed to embed) would never be re-enumerated
        // and would stay QUEUED forever. See SyncService.syncConnector()'s `incremental` check,
        // which is gated on isAllItemsFetched(). Mirrors reindexConnector()'s identical reset.
        r.setAllItemsFetched(false);
        connectorRepository.save(r);
        log.info("[Registry] Reset {} failed items to QUEUED for connector={}", failed, id);

        Thread.ofVirtual().name("retry-" + id).start(() -> {
            try {
                syncOrchestratorService.syncConnector(id, userId);
            } catch (Exception e) {
                log.warn("[Registry] Retry sync failed for connector={}: {}", id, e.getMessage());
            }
        });
    }

    /**
     * Re-indexes ONE document: queue it again, replacing the chunks it already has.
     *
     * <p>Owner only, unlike the item list beside it - a shared source is readable by everyone it is
     * shared with, and spending the owner's embedding budget is not part of reading.
     *
     * <p>Refuses (IllegalStateException -> 409) while the document is mid-run. The drain loop holds
     * this row in DOWNLOADING/CONVERTING/EMBEDDING and writes its status, chunk counts and
     * reindexReplace flag back as it goes, so resetting them underneath it does not cancel that run
     * - it loses the reset, and can leave reindexReplace cleared with the old chunks still in the
     * index. Waiting the way reindexConnector() does is not the answer either: that blocks up to
     * 30s and stops the whole connector's drain, which is far too much for one document.
     */
    public void reindexDocument(UUID connectorId, UUID documentId, String userId) {
        RegisteredConnector r = connectorRepository.findById(connectorId).orElse(null);
        if (r == null) throw new IllegalArgumentException("Source not found");
        if (userId != null && !userId.equals(r.getUserId())) {
            throw new IllegalArgumentException("Connector does not belong to caller");
        }
        TrackedDocument doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || !connectorId.equals(doc.getConnectorId())) {
            throw new IllegalArgumentException("Document not found in this connector");
        }
        if (ACTIVE_ITEM_STATUSES.contains(doc.getStatus())) {
            throw new IllegalStateException("This document is being indexed right now");
        }

        doc.setStatus(TrackedDocument.Status.QUEUED);
        // The existing chunks are deleted by the embed call itself (chunkAndEmbed), not here -
        // deleting them now would leave the document unsearchable for however long the queue is,
        // and a re-index the user asked for on a document that is fine today must not make it
        // worse until it is better.
        doc.setReindexReplace(true);
        doc.setTotalChunks(0);
        doc.setCurrentChunks(0);
        doc.setLastIndexedAt(null);
        doc.setFailureMessage(null);
        // Somebody asked for this one, so it gets a fresh retry budget - the same reasoning as
        // retryFailed() above, and without it an exhausted FAILED row is requeued here and then
        // left alone again by reconcileItem's exhausted-item guard.
        doc.setRetryCount(0);
        doc.setNextRetryAt(null);
        // An explicit re-index request is the second of the two things lastTouchedAt tracks, so the
        // row moves to the top of the list the user is looking at while they are looking at it.
        doc.touch(Instant.now());
        documentRepository.save(doc);

        // The crash-resume cache would otherwise let processItem() resume from the old markdown
        // instead of re-fetching - mirrors performReindex()'s per-document cache delete.
        try {
            Files.deleteIfExists(appPaths.tempDir().resolve("markdown-cache")
                    .resolve(doc.getId().toString() + ".md"));
        } catch (Exception ignored) {}

        // processedCount is what the drain loop's "no backlog" check reads (itemCount <=
        // processedCount + failedCount). Without recomputing it here, requeuing one COMPLETED
        // document out of a fully-drained source leaves that check true and nothing ever picks the
        // row up - the request would look accepted and do nothing.
        r.setProcessedCount((int) documentRepository.countByConnectorIdAndStatus(
                connectorId, TrackedDocument.Status.COMPLETED));
        connectorRepository.save(r);
        log.info("[Registry] Queued document={} of connector={} for re-index", documentId, connectorId);
    }

    // Post-registration edits to what a connector actually covers - the crawl bounds of a web page
    // (depth/page budget/scope) and the extension filter + recursion of a local folder. Everything
    // here was previously fixed at registration time, which meant "index one more level" or "also
    // pick up .md" could only be done by deleting the connector and adding it again, losing its
    // whole indexed history in the process.
    //
    // Deliberately does NOT accept seedUrl (WEB_PAGE): that IS the connector's identity - every
    // TrackedDocument under it is keyed to that origin, and registerConnector()'s dedup lookups
    // match on it. Pointing at a different site is still a new connector.
    //
    // A LOCAL FOLDER'S `path` IS ACCEPTED, since 2026-09-29, and only to RELINK: a folder moved or
    // renamed on disk left its source failing every sync with no way back but deleting it and
    // adding it again - losing its priority, sharing, graph setting and scope with it. The source
    // keeps its id and settings and takes the new folder's name; the forced full re-sync below
    // then lists the files under their new paths, so reconcile() drops every item still keyed to
    // the old one and indexes what it finds. That re-indexes the folder - the index is keyed by
    // absolute path - which is the honest cost of a move, and the same one deleting and re-adding
    // would have paid.
    //
    // A change here forces a FULL re-sync rather than the normal incremental pass: narrowing the
    // filter or the crawl bounds only takes effect by way of reconcile() no longer seeing the
    // excluded items and marking them DELETED, which an incremental pass would never do.
    public void updateConnectorConfig(UUID id, Map<String, Object> updates, String userId) {
        RegisteredConnector r = connectorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found"));
        if (userId != null && !userId.equals(r.getUserId())) {
            throw new IllegalArgumentException("Connector does not belong to caller");
        }

        // Applied FIRST, because it is the one part of this payload that is not about the type: any
        // source read through a connection can be moved to another of the owner's connections for
        // the same service.
        boolean endpointChanged = applyConnection(r, updates);
        ConnectorDescriptor descriptor = catalog.descriptor(r.getConnectorType());

        // The scope every file-based type carries - what its descriptor declares, never a list of
        // types kept here (a list that once named only the legacy FILES constant left every folder
        // uneditable). A local folder that moved can also be relinked.
        boolean scopeChanged = false;
        if (updates.containsKey("path")) {
            relinkFolder(r, updates.get("path"), userId);
            scopeChanged = true;
        }
        if (updates.containsKey("recursive")) {
            if (descriptor == null || !descriptor.capabilities().recursiveScope()) {
                throw new IllegalArgumentException(r.getConnectorType() + " sources have no subfolder setting");
            }
            r.setRecursive(toBool(updates.get("recursive"), r.isRecursive()));
            scopeChanged = true;
        }
        if (updates.containsKey("fileTypes")) {
            if (descriptor == null || !descriptor.capabilities().fileTypeFilter()) {
                throw new IllegalArgumentException(r.getConnectorType() + " sources have no file-type filter");
            }
            Object v = updates.get("fileTypes");
            r.setFileTypes(normalizeFileTypes(v == null ? null : v.toString()));
            scopeChanged = true;
        }

        boolean configChanged = false;
        if (r.getConnectorType() == ConnectorType.WEB_PAGE) {
            configChanged = applyWebPageConfig(r, updates);
        } else if (descriptor != null && !r.getConnectorType().isFileBacked()) {
            // A folder's config is its path, changed by a relink above and never as a field.
            configChanged = applyNativeConfig(r, descriptor, updates);
        }

        // A source whose scope IS its identity in the remote system (a Jira project, a Confluence
        // space) has nothing else to edit; the refusal names the type rather than ignoring the keys.
        if (!endpointChanged && !scopeChanged && !configChanged) {
            throw new IllegalArgumentException(
                    "Configuration for " + r.getConnectorType() + " connectors is not editable");
        }

        connectorRepository.save(r);
        log.info("[Registry] Updated config for connector id={} type={} keys={}",
                id, r.getConnectorType(), updates.keySet());
        auditChanged(r, userId, "settings", String.join(", ", updates.keySet()));

        Thread.ofVirtual().name("reconfig-sync-" + id).start(() -> {
            try {
                syncOrchestratorService.syncConnectorReportStarted(id, userId, true);
            } catch (Exception e) {
                log.warn("[Registry] Re-sync after config change failed for connector={}: {}", id, e.getMessage());
            }
        });
    }

    // A website's crawl bounds - checked here with the registration's own messages (a bound out of
    // range names the real maximum) rather than the generic field check. True when the payload named
    // any of them.
    private boolean applyWebPageConfig(RegisteredConnector r, Map<String, Object> updates) {
        java.util.Set<String> keys = java.util.Set.of("maxDepth", "maxPages", "sameDomainOnly", "renderJs", "includePattern");
        if (updates.keySet().stream().noneMatch(keys::contains)) return false;
        Map<String, String> cfg = new LinkedHashMap<>(StoredConfig.fields(ConnectorType.WEB_PAGE, r.getConfig()));
        // Same bounds the register path applies - an edit must not be able to reach a crawl shape
        // registration would refuse, and vice versa.
        requireInRange("Crawl depth", asIntOrNull(updates.get("maxDepth")),
                WebCrawlConnector.DEFAULT_MAX_DEPTH, 0, WebCrawlConnector.MAX_CRAWL_DEPTH);
        requireInRange("Max pages", asIntOrNull(updates.get("maxPages")),
                WebCrawlConnector.DEFAULT_MAX_PAGES, 1, WebCrawlConnector.MAX_CRAWL_PAGES);
        // Every value parsed to what it means before it is stored, never copied as typed: when the
        // config was newline key=value lines, a value carrying a line break wrote keys of its own -
        // maxPages past the product's cap, another seedUrl - since the LAST occurrence of a key won.
        if (updates.containsKey("maxDepth")) cfg.put("maxDepth", String.valueOf(asIntOrNull(updates.get("maxDepth"))));
        if (updates.containsKey("maxPages")) cfg.put("maxPages", String.valueOf(asIntOrNull(updates.get("maxPages"))));
        if (updates.containsKey("sameDomainOnly")) cfg.put("sameDomainOnly", String.valueOf(toBool(updates.get("sameDomainOnly"), true)));
        if (updates.containsKey("renderJs")) cfg.put("renderJs", String.valueOf(toBool(updates.get("renderJs"), false)));
        if (updates.containsKey("includePattern")) {
            Object v = updates.get("includePattern");
            String pattern = validIncludePattern(v == null ? null : v.toString());
            cfg.put("includePattern", pattern == null ? "" : pattern);
        }
        cfg.values().removeIf("null"::equals);
        // An includePattern cleared in the UI arrives as an empty string; drop the key entirely
        // rather than storing an empty pattern, which the crawler would read as a real one.
        cfg.values().removeIf(v -> v == null || v.isBlank());
        r.setConfig(ConfigJson.write(cfg));
        return true;
    }

    /**
     * A source of a type built on the connector framework: the payload's fields validated against
     * its descriptor, on top of what is stored, exactly as registration validates them. What makes
     * the source WHICH source (its identity - a repository, a project) cannot change here: that is a
     * different source, added as one. True when the payload named any of its fields.
     */
    private boolean applyNativeConfig(RegisteredConnector r, ConnectorDescriptor descriptor, Map<String, Object> updates) {
        boolean named = descriptor.fields().stream().anyMatch(f -> updates.containsKey(f.key()));
        if (!named) return false;
        // Through the one reader, with the name: a Jira project an older build registered stored its
        // server's URL here, and read as JSON it had no key - so the key the dialog sent was "a
        // different source". Saved, the row then holds its key as JSON like any other.
        Map<String, String> stored = StoredConfig.fields(r.getConnectorType(), r.getConfig(), r.getName());
        Map<String, Object> given = new LinkedHashMap<>();
        descriptor.fields().forEach(f -> {
            if (updates.containsKey(f.key())) given.put(f.key(), updates.get(f.key()));
        });
        Map<String, String> next = ConfigValidator.validate(descriptor.fields(), given, stored);
        ConnectorPlugin plugin = catalog.plugin(r.getConnectorType()).orElse(null);
        if (plugin != null && !Objects.equals(plugin.identity(stored), plugin.identity(next))) {
            throw new IllegalArgumentException("That would make it a different source. Add it as a new source instead.");
        }
        String json = ConfigJson.write(next);
        if (json != null && json.length() > MAX_CONFIG_CHARS) {
            throw new IllegalArgumentException("This source's settings are too long to keep.");
        }
        r.setConfig(json);
        return true;
    }

    // The config column's length.
    static final int MAX_CONFIG_CHARS = 4096;

    /**
     * Points a local folder source at the folder it moved to - see updateConnectorConfig's note.
     *
     * <p>Refused for every other type: an uploaded folder's path is the server's own sandbox, and a
     * remote source's location is its connection. The new folder has to pass the same probe a
     * registration does, and must not be one this user already has as a source - two sources
     * over one folder would index every file twice.
     */
    private void relinkFolder(RegisteredConnector r, Object rawPath, String userId) {
        if (r.getConnectorType() != ConnectorType.LOCAL_FOLDER) {
            throw new IllegalArgumentException("Only a local folder can be relinked");
        }
        String path = rawPath == null ? "" : rawPath.toString().trim();
        if (path.isEmpty()) throw new IllegalArgumentException("Choose the folder to relink to.");
        if (path.equals(r.getConfig())) return;
        requireUsableLocalPath(path);
        String folderName = folderNameOf(path);

        // Refused only for the SAME folder - another source with the same folder NAME is a
        // different folder, and the relinked source simply takes a name of its own.
        findLocalFolderAt(r.getUserId(), path, r.getId()).ifPresent(other -> {
            throw new IllegalArgumentException("\"" + other.getName()
                    + "\" is already one of your sources, so this one cannot be relinked to "
                    + path + ". Remove one of the two first.");
        });
        String name = uniqueLocalFolderName(r.getUserId(), folderName, r.getId());

        RegisteredConnector candidate = new RegisteredConnector();
        candidate.setConnectorType(r.getConnectorType());
        candidate.setName(name);
        candidate.setConfig(path);
        candidate.setUserId(r.getUserId());
        AccessCheck check = syncOrchestratorService.verifyAccess(candidate);
        if (check.rejected()) throw new IllegalArgumentException(check.detail());

        log.info("[Registry] Relinking connector id={} from {} to {}", r.getId(), r.getConfig(), path);
        r.setConfig(path);
        r.setName(name);
    }

    /**
     * Moves a source to another connection, if the payload carries {@code connectionId}.
     *
     * <p>Only to one of the SOURCE OWNER's own connections, and only one that can serve its type - a
     * Jira project moved onto a Confluence Data Center connection would be read through the wrong
     * API, while an Atlassian Cloud connection made as Confluence reaches the same site's Jira. Both conditions are the same lookup registration makes, so a move can reach
     * nothing registration could not.
     */
    private boolean applyConnection(RegisteredConnector r, Map<String, Object> updates) {
        if (!updates.containsKey("connectionId")) return false;
        ConnectionProtocol protocol = catalog.protocolFor(r.getConnectorType());
        if (protocol == null) {
            throw new IllegalArgumentException(r.getConnectorType() + " sources do not use a connection.");
        }
        Object raw = updates.get("connectionId");
        UUID wanted;
        try {
            wanted = raw == null || raw.toString().isBlank() ? null : UUID.fromString(raw.toString().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That is not a connection id.");
        }
        if (wanted == null) throw new IllegalArgumentException("Choose a connection for this source.");
        if (wanted.equals(r.getConnectionId())) return false;
        if (r.getConnectorType() == ConnectorType.NETWORK_SHARE) {
            // A share may only move to a connection for ITS OWN server - the one host it may reach.
            String host;
            try {
                host = UncPath.parse(StoredConfig.fields(r.getConnectorType(), r.getConfig()).get("uncPath")).host();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("This source's network path cannot be read: " + e.getMessage());
            }
            r.setConnectionId(networkShareConnection(host, r.getUserId(), wanted).id());
            return true;
        }
        if (r.getConnectorType() == ConnectorType.SVN) {
            // A repository may only move to a connection for ITS OWN server, as registration checks:
            // the connection's credential is sent to the repository's URL, and a move to another
            // server was accepted and then failed every sync (registerSvnRepo's note).
            ResolvedConnection target = registrationConnection(ConnectorType.SVN, r.getUserId(), wanted);
            String repo = repositoryOf(r);
            if (repo == null || !SvnUrls.isUnder(target.baseUrl(), repo)) {
                throw new IllegalArgumentException("This repository is not on the server of the SVN connection \""
                        + target.name() + "\" (" + SvnUrls.canonical(target.baseUrl()) + "), and its saved "
                        + "credential is only sent there. Choose a connection to the repository's own server.");
            }
            r.setConnectionId(target.id());
            return true;
        }
        // The config is left alone: it is the source's fields, never its server's URL - moving a source
        // re-points nothing but its connection. (Bitbucket Data Center's config WAS its server's URL
        // until its session; writing a connection's URL over any other type's config - null, for an
        // OAuth one - broke the source.)
        r.setConnectionId(registrationConnection(r.getConnectorType(), r.getUserId(), wanted).id());
        return true;
    }

    /**
     * The per-connector knowledge-graph opt-in.
     *
     * <p>Its own method rather than a key in updateConnectorConfig, because that one is a
     * per-TYPE switch whose default branch refuses every connector kind it does not name. This
     * setting applies to all of them - a Jira project is as extractable as a folder - and
     * routing it through there would have made it silently unavailable on four types.
     *
     * <p><b>The graph is a plug-in: this touches the graph and nothing else.</b> Neither direction
     * re-syncs, re-embeds or alters a single vector, keyword-index entry or document status.
     *
     * <p>SWITCHING IT ON extracts what the source ALREADY holds, in the background:
     * GraphExtractionOrchestrator picks the source up on its next scan and reads each indexed
     * document's chunks out of the keyword index. It used to take effect only as documents were
     * next re-embedded, so on a source that was up to date it never took effect at all. The
     * per-chunk cost is real, and the toggle's caption states it before it is switched on - that
     * caption, not a re-index, is where the consent now lives.
     *
     * <p>SWITCHING IT OFF DELETES what this connector contributed to the graph, and its extraction
     * progress. The alternative - leave the rows and stop adding to them - keeps the graph
     * answering from a corpus the operator has just excluded, with citations that still resolve,
     * which is a worse answer than either of the two states they can choose between. Switching it
     * back on extracts again, at the usual per-chunk cost.
     */
    public void setGraphEnabled(UUID id, boolean enabled, String userId) {
        RegisteredConnector r = connectorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found"));
        if (userId != null && !userId.equals(r.getUserId())) {
            throw new IllegalArgumentException("Connector does not belong to caller");
        }
        if (r.isGraphEnabled() == enabled) return;

        r.setGraphEnabled(enabled);
        connectorRepository.save(r);
        if (!enabled) knowledgeGraphService.forgetConnector(id);
        log.info("[Registry] Knowledge-graph extraction {} for connector id={}",
                enabled ? "enabled (already-indexed items are extracted in the background)"
                        : "disabled (graph data removed; the index is untouched)", id);
        auditChanged(r, userId, "graph", enabled);
    }

    // One action for every edit of a source; the detail says which setting and what it became.
    private static void auditChanged(RegisteredConnector r, String userId, String setting, Object value) {
        AuditEntry.of(AuditCategory.SOURCES, "source.changed")
                .user(userId)
                .target(r.getName())
                .summary("Source changed: " + r.getName())
                .detail("type", r.getConnectorType() == null ? null : r.getConnectorType().name())
                .detail(setting, value)
                .record();
    }

    /** What this connector has put into the graph - the number an operator judges extraction
     *  quality by before anything is allowed to depend on it. */
    public KnowledgeGraphService.ConnectorGraphCounts graphCounts(UUID id) {
        return knowledgeGraphService.countsFor(id);
    }

    /**
     * How far the graph has got through this connector's indexed items. Extraction runs in the
     * background now, so without these a source that has just been switched on shows nothing for
     * minutes and looks exactly like one that is not working.
     *
     * <p>{@code pending} is derived rather than stored: indexed items the graph has neither read
     * nor given up on. An item re-indexed since it was read still counts as extracted until the
     * worker re-reads it, so this can briefly under-report - never over-report - what is left.
     */
    public GraphProgress graphProgress(UUID id) {
        long indexed = documentRepository.countByConnectorIdAndStatus(id, TrackedDocument.Status.COMPLETED);
        KnowledgeGraphService.DocumentProgress p = knowledgeGraphService.progressFor(id);
        long pending = Math.max(0, indexed - p.extracted() - p.failed());

        // ONLY WHAT THE WORKER HAS OPEN. A progress row stays EXTRACTING if the process stopped
        // mid-document, and reads as "being extracted" until the worker reaches it again - which
        // with sync paused or the model unreachable can be never. Since 2026-09-24 a source card
        // shows a spinner off this list, so a stale row would be a spinner that never stops.
        // Without a worker (a test building this by hand) the rows are taken as they stand.
        List<KnowledgeGraphService.Reading> live = graphWorker == null ? p.reading()
                : p.reading().stream().filter(r -> graphWorker.isExtracting(r.documentId())).toList();
        long chunksDone = graphWorker == null ? p.chunksDone()
                : live.stream().mapToLong(KnowledgeGraphService.Reading::chunksDone).sum();
        long chunkTotal = graphWorker == null ? p.chunkTotal()
                : live.stream().mapToLong(KnowledgeGraphService.Reading::chunkCount).sum();

        // The graph module knows document IDS and nothing else - a title belongs to the registry,
        // and this is the one place that can put the two together. One lookup for a list the worker
        // keeps to a handful of entries.
        List<UUID> open = live.stream().map(KnowledgeGraphService.Reading::documentId).toList();
        Map<UUID, String> titles = new HashMap<>();
        if (!open.isEmpty()) {
            for (TrackedDocument d : documentRepository.findAllById(open)) {
                titles.put(d.getId(), d.getTitle());
            }
        }
        List<ReadingItem> reading = live.stream()
                .map(r -> new ReadingItem(r.documentId(), titles.get(r.documentId()),
                        r.chunksDone(), r.chunkCount()))
                .toList();

        return new GraphProgress(indexed, p.extracted(), p.failed(), pending,
                chunksDone, chunkTotal, reading);
    }

    /**
     * Which of these items the knowledge graph has finished reading - in the version indexed now,
     * since a re-indexed item is a new version the graph has not seen. Empty where the graph is off.
     */
    public java.util.Set<UUID> graphDoneIds(UUID connectorId, List<TrackedDocument> docs) {
        if (docs.isEmpty()) return java.util.Set.of();
        RegisteredConnector r = connectorRepository.findById(connectorId).orElse(null);
        if (r == null || !r.isGraphEnabled()) return java.util.Set.of();
        var states = knowledgeGraphService.documentStates(connectorId);
        java.util.Set<UUID> out = new java.util.HashSet<>();
        for (TrackedDocument d : docs) {
            var state = states.get(d.getId());
            if (d.getStatus() == TrackedDocument.Status.COMPLETED && state != null
                    && state.status() == ai.proxyma.core.graph.model.KgDocumentState.Status.DONE
                    && ai.proxyma.core.graph.service.DocumentGraphState.sameInstant(
                            state.sourceIndexedAt(), d.getLastIndexedAt())) {
                out.add(d.getId());
            }
        }
        return out;
    }

    // Setter-injected, lazily, and optional: the worker is a scheduled bean that tests building
    // this service by hand have no reason to create, and graphProgress only uses it to tell a live
    // extraction from a stale progress row.
    private GraphExtractionOrchestrator graphWorker;

    @Autowired(required = false)
    void setGraphWorker(@org.springframework.context.annotation.Lazy GraphExtractionOrchestrator graphWorker) {
        this.graphWorker = graphWorker;
    }

    /** The knowledge-graph model's outage, while the graph worker stands down for one. */
    public java.util.Optional<GraphExtractionOrchestrator.ModelOutage> graphModelOutage() {
        return graphWorker == null ? java.util.Optional.empty() : graphWorker.modelOutage();
    }

    /**
     * The COMPLETED items the knowledge graph is reading right now, for the item list to pin at the
     * top beside the ones being embedded. Owner-checked like every other item read.
     */
    public List<TrackedDocument> listGraphReadingItems(UUID connectorId, String userId) {
        checkConnectorOwnership(connectorId, userId);
        RegisteredConnector r = connectorRepository.findById(connectorId).orElse(null);
        if (r == null || !r.isGraphEnabled()) return List.of();
        List<UUID> ids = graphProgress(connectorId).reading().stream().map(ReadingItem::documentId).toList();
        if (ids.isEmpty()) return List.of();
        List<TrackedDocument> out = new ArrayList<>();
        for (TrackedDocument d : documentRepository.findAllById(ids)) {
            if (connectorId.equals(d.getConnectorId())) out.add(d);
        }
        return out;
    }

    /**
     * @param chunksDone  passages read so far in the items being read right now, and
     * @param chunkTotal  what those items hold - the only figure that moves while a large document
     *                    is being extracted, since an item counts as read only once every passage
     *                    in it has been
     * @param reading     which items those are, so a list of them can mark the row being read
     *                    instead of leaving the reader to match a total against twenty titles
     */
    public record GraphProgress(long indexed, long extracted, long failed, long pending,
                                long chunksDone, long chunkTotal, List<ReadingItem> reading) {}

    /** One item the graph is reading right now. The title may be null if the document went away
     *  between the two reads - the row simply does not match anything on screen then. */
    public record ReadingItem(UUID documentId, String title, int chunksDone, int chunkCount) {}

    /**
     * The graph one document put into the corpus, for the item row's graph button.
     *
     * <p>Gated by {@link #checkConnectorOwnership} exactly like {@link #getDocumentChunks}, and for
     * the same reason: this is a READ of a shared connector's contents, which sharing is meant to
     * grant. It is deliberately NOT gated on the graph opt-in - a connector switched off after
     * extraction still holds what it extracted until the rows are deleted, and showing an empty
     * picture for a graph that exists would be a lie about the data.
     */
    public ai.proxyma.core.graph.service.GraphView graphViewForDocument(
            UUID connectorId, UUID documentId, String userId,
            ai.proxyma.core.graph.service.KnowledgeGraphService.Window window) {
        checkConnectorOwnership(connectorId, userId);
        TrackedDocument doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || !connectorId.equals(doc.getConnectorId())) {
            throw new IllegalArgumentException("Document not found in this connector");
        }
        return knowledgeGraphService.viewForDocument(connectorId, documentId, window);
    }

    /** Every item in the source, in one graph. Same read gate as the per-item view above. */
    public ai.proxyma.core.graph.service.GraphView graphViewForConnector(
            UUID connectorId, String userId,
            ai.proxyma.core.graph.service.KnowledgeGraphService.Window window) {
        checkConnectorOwnership(connectorId, userId);
        return knowledgeGraphService.viewForConnector(connectorId, window);
    }

    // Null for an absent key, so requireInRange() falls through to its default and leaves the
    // stored value alone. A present-but-unparseable value is rejected rather than ignored - it
    // would otherwise be written into the config verbatim and silently read back as the default.
    private static Integer asIntOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        String s = value.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Crawl bounds must be whole numbers (got '" + s + "').");
        }
    }

    /**
     * A value that goes into a config, refused if it holds a line break - one line is what every
     * field takes (ConfigValidator).
     *
     * <p>When configs were newline key=value lines, split on line breaks with the LAST occurrence of
     * a key winning, a value with a break in it wrote keys of its own: an include pattern of ".*" + LF + "maxPages=20000" crawled
     * twenty thousand pages on Community, whose cap is a hundred, and a "seedUrl=" line moved the
     * source's identity past the seed checks; an Outlook folder id did the same for the mailbox.
     */
    static String singleLine(String label, String value) {
        if (value == null) return null;
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " must be on one line.");
        }
        return value.trim();
    }

    // Longer than any include pattern anybody writes by hand, and short enough that matching it
    // against every link of a crawl stays cheap.
    static final int MAX_INCLUDE_PATTERN = 500;

    /**
     * A crawl's include pattern, checked before it is stored: one line, of a sane length, and a
     * regular expression that compiles. An invalid one used to be stored as typed and silently read
     * as "no filter", so a crawl asked to stay inside one manual walked the whole site.
     *
     * @return the trimmed pattern, or null for none
     */
    static String validIncludePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) return null;
        String p = singleLine("The include pattern", pattern);
        if (p.length() > MAX_INCLUDE_PATTERN) {
            throw new IllegalArgumentException("The include pattern is longer than " + MAX_INCLUDE_PATTERN + " characters.");
        }
        try {
            Pattern.compile(p);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("The include pattern is not a valid regular expression: " + e.getDescription());
        }
        return p;
    }

    private static boolean toBool(Object o, boolean fallback) {
        if (o instanceof Boolean b) return b;
        if (o == null) return fallback;
        return Boolean.parseBoolean(o.toString().trim());
    }

    public void setShared(UUID id, boolean shared, String userId) {
        connectorRepository.findById(id).ifPresent(r -> {
            if (userId != null && !userId.equals(r.getUserId())) {
                throw new IllegalArgumentException("Connector does not belong to caller");
            }
            // Some sources were read with an access check nobody else passed - a SharePoint site
            // reachable through one person's Microsoft account. Sharing the source would hand its
            // indexed text, and so that reach, to users the site itself would refuse. The type
            // knows; asking it here is the only place that can refuse before the flag is stored.
            if (shared && !catalog.isShareable(r.getConnectorType())) {
                throw new IllegalArgumentException(
                        "This source was indexed with your own account's access rights and cannot be shared");
            }
            r.setShared(shared);
            connectorRepository.save(r);
            log.info("[Registry] Set shared={} for connector id={}", shared, id);
            auditChanged(r, userId, "shared", shared);
        });
    }

    // Single-holder PER OWNER: prioritizing a connector takes the flag away from whichever of that
    // same owner's connectors held it before, so prioritizing B while A is being priority-processed
    // simply moves the priority to B - the drain scheduler reacts within one tick (see
    // SyncOrchestratorService). Another user's priority flag is left alone: priority means "before
    // my other connectors", never "before everyone else's", so one user cannot stall another user's
    // indexing by clicking Process first (see activePriorityByOwner in SyncOrchestratorService).
    public void setPriority(UUID id, boolean priority, String userId) {
        RegisteredConnector target = connectorRepository.findById(id).orElse(null);
        if (target == null) return;
        if (userId != null && !userId.equals(target.getUserId())) {
            throw new IllegalArgumentException("Connector does not belong to caller");
        }
        if (priority) {
            for (RegisteredConnector holder : connectorRepository.findByPriorityTrue()) {
                if (!holder.getId().equals(id) && sameOwner(holder, target)) {
                    holder.setPriority(false);
                    connectorRepository.save(holder);
                }
            }
        }
        target.setPriority(priority);
        connectorRepository.save(target);
        log.info("[Registry] Set priority={} for connector id={} ({})", priority, id, target.getName());
        auditChanged(target, userId, "priority", priority);
    }

    // Desktop mode leaves userId null on every connector, which is one owner, not "no owner" - so
    // null == null must compare equal here for the single-holder rule to still apply there.
    private static boolean sameOwner(RegisteredConnector a, RegisteredConnector b) {
        return java.util.Objects.equals(a.getUserId(), b.getUserId());
    }

    private static final List<TrackedDocument.Status> ACTIVE_ITEM_STATUSES = List.of(
            TrackedDocument.Status.DOWNLOADING,
            TrackedDocument.Status.CONVERTING,
            TrackedDocument.Status.EMBEDDING);

    // Items actively downloading/converting/embedding right now - always small (bounded by
    // in-flight concurrency, not connector size), so it's never paginated. The modal pins these
    // at the top instead of auto-scrolling to find them within a large paginated list.
    public List<TrackedDocument> listActiveItems(UUID connectorId, String userId) {
        checkConnectorOwnership(connectorId, userId);
        return documentRepository.findByConnectorIdAndStatusIn(connectorId, ACTIVE_ITEM_STATUSES);
    }

    // Everything else (queued/completed/failed/detected/modified/deleted) - a connector can have
    // thousands of tracked documents, so the modal lazy-loads this list page by page instead of
    // fetching it all at once. Keyset (cursor) pagination instead of offset: items constantly
    // transition into/out of the excluded "active" statuses while a connector is being indexed,
    // and offset pagination over a set that mutates between page fetches skips or duplicates
    // rows (every active->completed transition shifts subsequent rows' offsets). Keyset
    // pagination only cares about rows after the last-seen one, so it's unaffected by rows
    // changing elsewhere in the set.
    //
    // Ordered failed-first, then by last touched, newest first - see the repository query for why
    // that replaced the alphabetical ordering. The cursor is therefore three values, all of which
    // the caller echoes back from the previous page: whether that row was failed, its touched
    // time, and its id as the tie-break.
    public ItemPage listInactiveItemsPaged(UUID connectorId, String userId, Integer cursorFailed,
                                           Instant cursorTouched, UUID cursorId, int size) {
        checkConnectorOwnership(connectorId, userId);
        Pageable pageable = PageRequest.of(0, size + 1);
        List<TrackedDocument> rows = documentRepository.findByConnectorIdAndStatusNotInAfterCursor(
                connectorId, ACTIVE_ITEM_STATUSES, TrackedDocument.Status.FAILED,
                cursorFailed, cursorTouched, cursorId, pageable);
        boolean hasMore = rows.size() > size;
        List<TrackedDocument> page = hasMore ? rows.subList(0, size) : rows;
        return new ItemPage(page, hasMore);
    }

    public record ItemPage(List<TrackedDocument> items, boolean hasMore) {}

    /**
     * The cursor values a caller must echo to get the page after {@code doc}.
     *
     * <p>Here rather than in the controller because they have to be derived the same way the
     * repository's ORDER BY derives them - the failed rank from the status, the touched time
     * falling back to createdAt for rows that predate lastTouchedAt. Two copies of that rule is how
     * a page boundary comes to skip a row.
     */
    public static int cursorFailedRank(TrackedDocument doc) {
        return doc.getStatus() == TrackedDocument.Status.FAILED ? 0 : 1;
    }

    public static Instant cursorTouchedAt(TrackedDocument doc) {
        return doc.getLastTouchedAt() != null ? doc.getLastTouchedAt() : doc.getCreatedAt();
    }

    // Chunks currently stored for one indexed document. The converted markdown itself is never
    // durably kept for anything but FILES uploads (see purgedMarkdownSnapshotPath below) - it's
    // only a transient crash-resume cache, deleted the moment a document reaches COMPLETED (see
    // processItem() in SyncService) - so "preview what got indexed" can only show the chunked text
    // actually still stored in the BM25 index, not a reconstruction of the original document.
    // Chunks overlap by design (ChunkingService), so callers should render them as separate
    // blocks, not concatenate them.
    public List<String> getDocumentChunks(UUID connectorId, UUID documentId, String userId) {
        checkConnectorOwnership(connectorId, userId);
        TrackedDocument doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || !connectorId.equals(doc.getConnectorId())) {
            throw new IllegalArgumentException("Document not found in this connector");
        }
        String collectionId = connectorId.toString().replace("-", "");
        return bm25IndexService.getChunkTexts(collectionId, doc.getSourceId());
    }

    /**
     * The documents of one source that a reader could mean by {@code name}: its title, its whole
     * sourceId, or the file name a search result shows for it - the last segment of the path, which
     * is all SearchTool lets a file's location say. Case aside, exactly; DELETED rows never.
     *
     * <p>For a caller that has only a NAME to go on - the key-account agent citing an indexed file
     * it found by search, where a file has no web address to cite (spec 11, ka_evidence). Gated like
     * the item list: the owner, or anyone when the source is shared. Every match is returned, so the
     * caller can refuse an ambiguous name rather than pick one.
     */
    public List<TrackedDocument> findDocumentsNamed(UUID connectorId, String userId, String name) {
        checkConnectorOwnership(connectorId, userId);
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return List.of();
        NamePatterns q = NamePatterns.of(n);
        return documentRepository.findNameCandidates(connectorId, q.name(), q.slashSuffix(), q.backslashSuffix()).stream()
                .filter(d -> n.equalsIgnoreCase(d.getTitle()) || n.equalsIgnoreCase(d.getSourceId())
                        || n.equalsIgnoreCase(fileNameOf(d.getSourceId())))
                .toList();
    }

    /**
     * The parameters of {@code TrackedDocumentRepository.findNameCandidates}: the name lower-cased,
     * and the two "ends with /name" patterns LIKE-escaped with '!' - a file name's '_' and '%' are
     * literal characters, not wildcards.
     */
    record NamePatterns(String name, String slashSuffix, String backslashSuffix) {
        static NamePatterns of(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            String escaped = lower.replace("!", "!!").replace("%", "!%").replace("_", "!_");
            return new NamePatterns(lower, "%/" + escaped, "%\\" + escaped);
        }
    }

    /** One document of one source, gated like {@link #findDocumentsNamed}; empty when absent or DELETED. */
    public Optional<TrackedDocument> findDocument(UUID connectorId, UUID documentId, String userId) {
        checkConnectorOwnership(connectorId, userId);
        return documentRepository.findById(documentId)
                .filter(d -> connectorId.equals(d.getConnectorId()) && d.getStatus() != TrackedDocument.Status.DELETED);
    }

    /** The last segment of a path, after a forward or a back slash; the whole string when it has neither. */
    public static String fileNameOf(String sourceId) {
        if (sourceId == null) return null;
        int slash = Math.max(sourceId.lastIndexOf('/'), sourceId.lastIndexOf('\\'));
        return slash < 0 ? sourceId : sourceId.substring(slash + 1);
    }

    // Read-only gate for the item list/modal - unlike the mutation checks above (reindex, retry,
    // rename, setShared, setPriority, remove), a shared connector's items are meant to be viewable
    // by every user, not just the owner (see ../proxyma-docs/architecture/roles-and-data-isolation.md, "What
    // sharing does NOT grant": sharing grants read access to the connector's card/modal in the UI).
    public void checkConnectorOwnership(UUID connectorId, String userId) {
        connectorRepository.findById(connectorId).ifPresent(r -> {
            if (userId != null && !userId.equals(r.getUserId()) && !r.isShared()) {
                throw new IllegalArgumentException("Connector does not belong to caller");
            }
        });
    }

    /**
     * Marks the connector as deleting and returns immediately - the actual teardown (waiting for an
     * in-flight sync/drain to stop, then deleting tracked documents, vectors, BM25 index and the
     * registry row) runs on a virtual thread. Triggering a delete must feel instant: the caller's
     * HTTP DELETE used to block for the whole teardown, which begins with a poll of up to 30s in
     * waitForSyncToStop() alone, so the confirmation modal sat spinning on a connector whose fate
     * was already decided the moment `deleting` was persisted.
     * <p>
     * The deleting flag is the durable half of that contract - it is persisted before this returns,
     * is exposed to the UI (so a card still renders as deleting after a page refresh, not only via
     * the transient ConnectorOperationEvent SSE stream), and is what every sync/drain path already
     * checks to cooperatively bail out. A crash mid-teardown is recovered by
     * resumeInterruptedDeletions() below rather than stranding the connector in that state.
     */
    public void removeConnector(UUID id, String userId) {
        RegisteredConnector r = connectorRepository.findById(id).orElse(null);
        if (r == null) return;
        if (userId != null && !userId.equals(r.getUserId())) {
            throw new IllegalArgumentException("Connector does not belong to caller");
        }

        r.setDeleting(true);
        connectorRepository.save(r);
        log.info("[Registry] Marked connector as deleting: id={}", id);

        eventPublisher.publishEvent(new ConnectorOperationEvent(
                this, id, "delete", ConnectorOperationEvent.Phase.STOPPING_SYNC));

        Thread.ofVirtual().name("delete-" + id).start(() -> {
            try {
                performRemoval(id, r);
                AuditEntry.of(AuditCategory.SOURCES, "source.removed")
                        .user(userId)
                        .target(r.getName())
                        .summary("Source removed: " + r.getName())
                        .detail("type", r.getConnectorType().name())
                        .record();
            } catch (Exception e) {
                log.error("[Registry] Deletion failed for connector={}: {}", id, e.getMessage(), e);
                AuditEntry.of(AuditCategory.SOURCES, "source.removed")
                        .user(userId)
                        .outcome(AuditEntry.FAILED)
                        .target(r.getName())
                        .summary("Removing " + r.getName() + " failed")
                        .detail("type", r.getConnectorType().name())
                        .detail("error", e.getMessage())
                        .record();
            }
        });
    }

    // The blocking teardown itself. Kept synchronous and separately callable so deleteAllByUserId()
    // (account deletion) can await real completion rather than returning while a user's connectors
    // are still being dismantled, and so resumeInterruptedDeletions() can re-drive it after a crash.
    private void performRemoval(UUID id, RegisteredConnector r) {
        syncOrchestratorService.waitForSyncToStop(id);

        eventPublisher.publishEvent(new ConnectorOperationEvent(
                this, id, "delete", ConnectorOperationEvent.Phase.DELETING_DATA));

        String collectionId = id.toString().replace("-", "");
        List<TrackedDocument> docs = documentRepository.findByConnectorId(id);
        documentRepository.deleteAll(docs);
        try {
            vectorStoreService.deleteCollection(collectionId);
        } catch (Exception e) {
            log.warn("[Registry] Failed to delete vectors for connector={}: {}", id, e.getMessage());
        }
        try {
            bm25IndexService.deleteCollection(collectionId);
        } catch (Exception e) {
            log.warn("[Registry] Failed to delete BM25 index for connector={}: {}", id, e.getMessage());
        }
        // The half-built shadow of a re-index this removal just interrupted. It lives under its own
        // collection id, so deleting the live one above does not touch it, and the row that names
        // it is about to be deleted - after which nothing knows the shadow exists and nothing ever
        // will. performReindex() clears a stale shadow before starting a new one, but only for a
        // source that is still there to be re-indexed; a removed one leaves its vectors and its
        // keyword index behind forever, taking real storage in the one store both editions share.
        //
        // Re-read rather than trusted from `r`: that instance was loaded when the delete was
        // requested, and this runs after a wait of up to 30s for the in-flight work to stop - which
        // is exactly the window in which a re-index would have named its shadow. The stale copy
        // would say there is none.
        String shadowCollectionId = connectorRepository.findById(id).orElse(r)
                .getReindexShadowCollectionId();
        if (shadowCollectionId != null && !shadowCollectionId.isBlank()) {
            try {
                vectorStoreService.deleteCollection(shadowCollectionId);
            } catch (Exception e) {
                log.warn("[Registry] Failed to delete shadow vectors for connector={}: {}", id, e.getMessage());
            }
            try {
                bm25IndexService.deleteCollection(shadowCollectionId);
            } catch (Exception e) {
                log.warn("[Registry] Failed to delete shadow BM25 index for connector={}: {}", id, e.getMessage());
            }
            log.info("[Registry] Deleted in-flight reindex shadow collection={} for connector={}",
                    shadowCollectionId, id);
        }
        try {
            // Every entity, mention and edge this corpus contributed. Its own try/catch, like
            // the two above: a graph that will not clean up must not strand a connector row in
            // `deleting` forever, and orphaned graph rows are recoverable where a connector
            // stuck mid-teardown is not.
            knowledgeGraphService.forgetConnector(id);
        } catch (Exception e) {
            log.warn("[Registry] Failed to delete graph data for connector={}: {}", id, e.getMessage());
        }
        connectorRepository.deleteById(id);
        log.info("[Registry] Removed connector: id={} (tracked docs={}, vectors+BM25 cleaned)", id, docs.size());
        if (quotaPort != null && r.getUserId() != null) {
            // Vectors/BM25 for this connector are gone now - reconcile the owner's usedStorageBytes
            // down from real measured totals rather than leaving it stuck at the pre-deletion value.
            quotaPort.reconcile(r.getUserId());
        }
        eventPublisher.publishEvent(new ConnectorRemovedEvent(
                this, id, r.getConnectorType(), r.getConfig(), r.getUserId(), r.isShared()));
        // Type and document count. Note the deliberate asymmetry with the event published on the
        // line above: that one carries getConfig() and getUserId() because in-process listeners
        // need them, and neither may ever be copied into a telemetry payload.
        telemetryShipper.record(TelemetryEventType.SOURCE_REMOVED, Map.of(
                "connectorType", r.getConnectorType().name(),
                "documentCount", docs.size()));
    }

    // Deliberately calls performRemoval() directly rather than removeConnector(): account deletion
    // must not return while this user's connectors are still being torn down on background threads -
    // the caller goes on to delete the user row itself, and a half-dismantled connector pointing at
    // a userId that no longer exists is exactly the orphan state the fast-return path is safe to
    // create for an interactive single delete but not here.
    public void deleteAllByUserId(String userId) {
        List<RegisteredConnector> connectors = connectorRepository.findByUserId(userId);
        for (RegisteredConnector r : connectors) {
            try {
                r.setDeleting(true);
                connectorRepository.save(r);
                performRemoval(r.getId(), r);
            } catch (Exception e) {
                log.warn("[Registry] Failed to remove connector={} during user deletion: {}",
                        r.getId(), e.getMessage());
            }
        }
        log.info("[Registry] Deleted {} connectors for userId={}", connectors.size(), userId);
    }

    // A connector whose teardown thread died with the JVM (crash/restart mid-delete) is left
    // deleting=true forever otherwise: every sync and drain path skips it cooperatively, so it
    // never processes anything again, yet its row, vectors and BM25 index all survive and it keeps
    // occupying storage/quota. It was safe to leave this unhandled while removeConnector() ran the
    // whole teardown inside the request thread (the window was a single request); now that the
    // teardown outlives the request, this is the counterpart that closes it.
    @jakarta.annotation.PostConstruct
    public void resumeInterruptedDeletions() {
        List<RegisteredConnector> stuck = connectorRepository.findAll().stream()
                .filter(RegisteredConnector::isDeleting)
                .toList();
        for (RegisteredConnector r : stuck) {
            log.info("[Registry] Resuming interrupted deletion for connector={}", r.getId());
            Thread.ofVirtual().name("delete-resume-" + r.getId()).start(() -> {
                try {
                    performRemoval(r.getId(), r);
                } catch (Exception e) {
                    log.error("[Registry] Resumed deletion failed for connector={}: {}",
                            r.getId(), e.getMessage(), e);
                }
            });
        }
        if (!stuck.isEmpty()) {
            log.info("[Registry] Resumed {} interrupted connector deletion(s) on startup", stuck.size());
        }
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    public List<UUID> getConnectorIdsForUser(String userId) {
        return listByUser(userId).stream().map(RegisteredConnector::getId).toList();
    }

    public List<String> getCollectionIdsForUser(String userId) {
        Set<String> ids = new LinkedHashSet<>(getConnectorIdsForUser(userId).stream()
                .map(id -> id.toString().replace("-", ""))
                .toList());
        ids.addAll(getSharedCollectionIds());
        return new ArrayList<>(ids);
    }

    public List<String> getSharedCollectionIds() {
        return listShared().stream()
                .map(r -> r.getId().toString().replace("-", ""))
                .toList();
    }

    // One group per distinct (provider, model) pair among the requested collections' connectors
    // that are still mid model-changed reindex - SearchService runs one extra old-model search
    // pass per group so not-yet-replaced documents (still under their original model/dimension)
    // stay searchable until their connector's backlog fully drains. Connectors not in
    // reindexInProgress, or with no old model recorded yet, contribute nothing here.
    public record InFlightReindex(String provider, String model, List<String> collectionIds) {}

    public List<InFlightReindex> getInFlightReindexInfo(List<String> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) return List.of();
        Set<String> requested = new HashSet<>(collectionIds);
        Map<String, List<String>> groupedIds = new LinkedHashMap<>();
        Map<String, String[]> providerModelByKey = new HashMap<>();
        for (RegisteredConnector r : connectorRepository.findByReindexInProgressTrue()) {
            String cid = r.getId().toString().replace("-", "");
            if (!requested.contains(cid)) continue;
            String model = r.getIndexedWithEmbeddingModel();
            if (model == null) continue;
            String provider = r.getIndexedWithEmbeddingProvider();
            String key = provider + "|" + model;
            groupedIds.computeIfAbsent(key, k -> new ArrayList<>()).add(cid);
            providerModelByKey.putIfAbsent(key, new String[]{provider, model});
        }
        List<InFlightReindex> result = new ArrayList<>();
        groupedIds.forEach((key, ids) -> {
            String[] pm = providerModelByKey.get(key);
            result.add(new InFlightReindex(pm[0], pm[1], ids));
        });
        return result;
    }

    // If the embedding model/provider changes again while a connector is already mid-reindex from a
    // previous change, in-flight writes would otherwise silently pick up the newest model (
    // chunkAndEmbed always embeds with "whatever is current"), producing a third dimension the
    // search merge above won't know to query until the backlog fully re-drains. Re-running
    // reindexConnector() re-anchors the connector to the latest model/provider immediately - brute
    // force (re-queues everything, including documents already replaced under the previous new
    // model) but correct, and needs no extra multi-generation tracking anywhere else. userId=null
    // bypasses the ownership check (this is a system-triggered call, not a user request).
    @Async
    @EventListener
    public void onEmbeddingProviderSettingsChanged(ProviderSettingsChangedEvent event) {
        if (!"embedding".equals(event.role())) return;
        for (RegisteredConnector r : connectorRepository.findByReindexInProgressTrue()) {
            log.info("[Registry] Embedding settings changed again while connector={} was mid-reindex - re-triggering reindex against the newest model/provider", r.getId());
            try {
                reindexConnector(r.getId(), null);
            } catch (Exception e) {
                log.warn("[Registry] Re-trigger reindex failed for connector={}: {}", r.getId(), e.getMessage());
            }
        }
    }

    public void updateItemCount(UUID connectorId, int itemCount) {
        connectorRepository.findById(connectorId).ifPresent(r -> {
            r.setItemCount(itemCount);
            connectorRepository.save(r);
        });
    }

    public java.util.Optional<RegisteredConnector> findByConfigAndUserId(String config, String userId) {
        if (userId == null || config == null) return java.util.Optional.empty();
        return connectorRepository.findByUserIdAndConnectorType(userId, nativeFilesType())
                .stream()
                .filter(r -> config.equals(r.getConfig()) || config.equals(r.getName()))
                .findFirst();
    }

    // -- Adding a source of any type --------------------------------------------------------------

    /**
     * A registration as the generic endpoint, the agent's addSource tool and the dialogs send it:
     * the type, the connection to read through (null for the owner's default), the descriptor's
     * fields, and the scope where the type has one.
     */
    public record SourceRequest(ConnectorType type, UUID connectionId, Map<String, Object> config,
                                Boolean recursive, String fileTypes) {}

    /**
     * Adds a source of ANY type - the one way in since the connector framework v2.
     *
     * <p>A type built on the framework is validated against its descriptor (ConfigValidator: what
     * the dialog draws is what the server checks), named by its plugin's identity, and registered
     * through the same path as every other source - the license, the identity lock, the access
     * probe, the first sync. A type that predates the framework is handed to its own registration,
     * whose checks are its own (an SVN URL under its server, a web page's crawl bounds, a share
     * path's host), after the same field check.
     */
    public UUID register(SourceRequest request, String userId) {
        ConnectorType type = request.type();
        ConnectorDescriptor descriptor = type == null ? null : catalog.descriptor(type);
        if (descriptor == null || !descriptor.listed()) {
            throw new IllegalArgumentException("Proxyma cannot index that type of source here.");
        }
        Map<String, Object> raw = request.config() == null ? Map.of() : request.config();
        Map<String, String> config = ConfigValidator.validate(descriptor.fields(), raw);
        UUID own = registerWithOwnRules(type, config, request, userId);
        if (own != null) return own;

        ConnectorPlugin plugin = catalog.plugin(type)
                .orElseThrow(() -> new IllegalArgumentException("Proxyma cannot index that type of source here."));
        if (plugin.identifiedByConfig()) return registerByIdentity(type, plugin, config, request, userId);
        if (descriptor.capabilities().edition() == Edition.ENTERPRISE && !multiUser) {
            throw new IllegalArgumentException(serviceName(type) + " sources are available on Enterprise Server only");
        }
        String name = plugin.displayName(config);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Fill in what identifies the source.");
        String json = ConfigJson.write(config);
        if (json != null && json.length() > MAX_CONFIG_CHARS) {
            throw new IllegalArgumentException("This source's settings are too long to keep.");
        }
        boolean recursive = request.recursive() == null || request.recursive();
        return registerConnector(type, name.strip(), json, userId, recursive, request.fileTypes(), request.connectionId());
    }

    /**
     * What a registration would store as the source's config and name - for the cross-user duplicate
     * check the dialog makes before it registers (a source somebody already shares). Empty for a type
     * that has no such check.
     */
    public Optional<Map.Entry<String, String>> storedForm(SourceRequest request, String userId) {
        ConnectorType type = request.type();
        ConnectorDescriptor descriptor = type == null ? null : catalog.descriptor(type);
        // A folder is nobody else's: an upload folder is in its owner's own sandbox, a local folder on
        // a machine with one user. A website is matched by its start address, by registerWebPage. A
        // source things are pushed to holds only what its owner pushed: another user's shared one of
        // the same name is a different source, never a copy this one could use instead.
        if (descriptor == null || !descriptor.capabilities().shareable() || descriptor.capabilities().push()
                || type.isFileBacked() || type == ConnectorType.WEB_PAGE) {
            return Optional.empty();
        }
        Map<String, String> config = ConfigValidator.validate(descriptor.fields(),
                request.config() == null ? Map.of() : request.config());
        // A source with every optional field left empty ("every board") stores no config at all,
        // and Map.entry refuses a null key - registering one was a 500. With nothing stored there
        // is nothing to compare either: it would match any other "everything" source of the type,
        // whichever account it reads.
        String json = ConfigJson.write(config);
        if (json == null) return Optional.empty();
        return catalog.plugin(type).map(p -> Map.entry(json, p.displayName(config)));
    }

    // The types whose registration has rules of its own, each to its own method: a folder's path
    // (and an upload's sandbox), a website's seed address as its identity and its crawl bounds, an
    // SVN URL that must lie under its connection's server, a share's connection chosen by its host.
    // Every other type is registered the generic way below, and this answers null for it.
    private UUID registerWithOwnRules(ConnectorType type, Map<String, String> c, SourceRequest request, String userId) {
        UUID connectionId = request.connectionId();
        boolean recursive = request.recursive() == null || request.recursive();
        return switch (type) {
            case SVN -> registerSvnRepo(c.get("repoPath"), c.get("repoName"), userId, connectionId, recursive,
                    request.fileTypes());
            case NETWORK_SHARE -> registerNetworkShare(c.get("uncPath"), recursive, userId, request.fileTypes(),
                    connectionId);
            case WEB_PAGE -> registerWebPage(c.get("seedUrl"), c.get("name"),
                    c.get("maxDepth") == null ? null : Integer.valueOf(c.get("maxDepth")),
                    c.get("maxPages") == null ? null : Integer.valueOf(c.get("maxPages")),
                    c.get("sameDomainOnly") == null ? null : Boolean.valueOf(c.get("sameDomainOnly")),
                    c.get("renderJs") == null ? null : Boolean.valueOf(c.get("renderJs")),
                    c.get("includePattern"), userId);
            case LOCAL_FOLDER, UPLOADED_FILES, FILES -> registerFiles(folderNameOf(c.get("path")), c.get("path"), userId,
                    request.recursive() != null && request.recursive(), request.fileTypes());
            default -> null;
        };
    }

    // ── Adding a source from a dialog ─────────────────────────────────────────

    // Set only while asNewSource runs its registration, on the thread that runs it.
    private static final ThreadLocal<Boolean> REJECT_EXISTING = ThreadLocal.withInitial(() -> false);

    /**
     * Runs a registration the USER asked for in a dialog, refusing it with
     * {@link SourceAlreadyRegisteredException} when the source is already registered.
     *
     * <p>Every other caller keeps "already registered means re-sync it": an upload re-registers
     * its folder after each file, and the agent's register tools use it to refresh a source. Only a person filling in a form is told plainly
     * that the source exists - a quiet re-sync threw away the server, token and file types they had
     * just typed (the maintainer's call, 2026-09-27).
     *
     * <p>A flag for the one call rather than a parameter on every register overload, because those
     * overloads are shared with the callers above. The registration runs synchronously on this
     * thread; the re-sync it would otherwise start is the only part that leaves it.
     */
    public UUID asNewSource(java.util.function.Supplier<UUID> registration) {
        REJECT_EXISTING.set(true);
        try {
            return registration.get();
        } finally {
            REJECT_EXISTING.remove();
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private UUID registerConnector(ConnectorType type, String name, String config, String userId) {
        return registerConnector(type, name, config, userId, true);
    }

    private UUID registerConnector(ConnectorType type, String name, String config, String userId,
                                  boolean requestedRecursive) {
        return registerConnector(type, name, config, userId, requestedRecursive, null);
    }

    private UUID registerConnector(ConnectorType type, String name, String config, String userId,
                                  boolean requestedRecursive, String fileTypes) {
        return registerConnector(type, name, config, userId, requestedRecursive, fileTypes, null);
    }

    /**
     * Finds an already-registered source of a type that reads through a connection: one of the
     * owner's sources with this identity (its name) READ THROUGH THE SAME CONNECTION. The connection
     * is part of a source's identity, since 2026-10-14. It used to be the connection's server address,
     * which told project PROJ on jira.a from PROJ on jira.b - but a service with no per-account
     * address (IMAP, Zoom, Gmail, Slack, any cloud API at one URL) gave a second account's "INBOX" or
     * "my meetings" the same name on the same address as the first's, and it was refused as already
     * one of the person's sources. The connection is the account: through a different one it is a
     * different source, through the same one the same source.
     *
     * <p>A source with no connection it can use - registered before connections existed, or whose
     * connection was deleted - is the one exception: registering it again is how it is repaired
     * (adoptConnectionIfMissing), so it is matched whatever connection is chosen, after an exact match.
     *
     * @param chosen the connection the registration reads through; null when the person has none,
     *               which matches the first source of the name - registering then fails on the missing
     *               connection either way
     */
    private Optional<RegisteredConnector> findExistingThroughConnection(ConnectorType type, String name,
                                                                       UUID chosen, String userId) {
        List<RegisteredConnector> candidates = userId != null
                ? connectorRepository.findAllByUserIdAndConnectorTypeAndNameIgnoreCase(userId, type, name)
                : connectorRepository.findAllByConnectorTypeAndNameIgnoreCase(type, name);
        if (candidates.isEmpty()) return Optional.empty();
        if (chosen == null) return Optional.of(candidates.get(0));
        return candidates.stream()
                .filter(c -> chosen.equals(c.getConnectionId()))
                .findFirst()
                .or(() -> candidates.stream().filter(c -> lacksUsableConnection(c, type)).findFirst());
    }

    /**
     * The connection a registration reads through, for telling sources apart: the one named, else
     * the owner's default for the service - without the checks registrationConnection makes, which
     * run when a new source is saved. Null for a type that needs none, or a person who has none.
     */
    private UUID chosenConnection(ConnectorType type, String userId, UUID connectionId) {
        if (connectionId != null) return connectionId;
        if (catalog.protocolFor(type) == null) return null;
        return defaultConnection(type, userId).map(ResolvedConnection::id).orElse(null);
    }

    /**
     * Gives an existing source the registration's connection when it has none it can use - one
     * registered before connections existed (0.18.1), or whose connection was deleted. Registering
     * it again is exactly what a person, or the agent, does to repair it, and leaving it without a
     * connection re-synced a source that could only fail: every Jira, Confluence and Bitbucket source
     * carried over from 0.18.0 stayed in ERROR through any number of re-registrations. Adopting is
     * not moving - a source whose connection still resolves keeps it.
     */
    private void adoptConnectionIfMissing(RegisteredConnector source, ConnectorType type, String userId,
                                          UUID connectionId) {
        if (!lacksUsableConnection(source, type)) return;
        source.setConnectionId(registrationConnection(type, userId, connectionId).id());
        connectorRepository.save(source);
        log.info("[Registry] Connector id={} had no usable connection; now reads through connection={}",
                source.getId(), source.getConnectionId());
    }

    private boolean lacksUsableConnection(RegisteredConnector source, ConnectorType type) {
        return catalog.protocolFor(type) != null && !endpointResolver.resolve(source).exists();
    }

    // Trailing slashes and case in the host are not a different server; the path is left alone,
    // since a context path (/jira) genuinely is part of which server a source lives on.
    private static String normalizeServer(String url) {
        if (url == null || url.isBlank()) return null;
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.toLowerCase();
    }

    // recursive and fileTypes are the scope of the FILE-BASED types (ConnectorType.hasRecursiveScope
    // / hasFileTypeFilter - a folder, an SVN repository, a network share, a SharePoint site); every
    // other connector type passes recursive through untouched and ignores fileTypes entirely.
    //
    // `connectionId` is the connection the source reads through - null for a type that needs none,
    // or to take the owner's default for a type that does. A registration that finds the source
    // already there re-syncs it and leaves a WORKING connection alone, since moving an existing
    // source is the edit dialog's job - but one with no usable connection gets this one (see
    // adoptConnectionIfMissing).
    private UUID registerConnector(ConnectorType type, String name, String config, String userId,
                                  boolean requestedRecursive, String fileTypes, UUID connectionId) {
        return underRegistrationLock(type, userId, name, () ->
                registerConnectorLocked(type, name, config, userId, requestedRecursive, fileTypes, connectionId));
    }

    // The license a type needs: its descriptor's edition, named by the service it reads through.
    private void checkLicensed(ConnectorType type) {
        featureGateService.checkConnector(serviceName(type), catalog.edition(type));
    }

    // What a refusal calls a type: the service it reads through ("Microsoft 365"), else its own name.
    private String serviceName(ConnectorType type) {
        ConnectionProtocol protocol = catalog.protocolFor(type);
        return protocol != null ? catalog.displayName(protocol) : catalog.displayName(type);
    }

    /** Runs {@code work} holding the lock for one source identity - see registrationLocks. */
    <T> T underRegistrationLock(ConnectorType type, String userId, String identity,
                                Supplier<T> work) {
        String key = type.name() + '|' + userId + '|' + (identity == null ? "" : identity.strip().toLowerCase(Locale.ROOT));
        ReentrantLock lock = registrationLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    private UUID registerConnectorLocked(ConnectorType type, String name, String config, String userId,
                                         boolean requestedRecursive, String fileTypes, UUID connectionId) {
        // An ENTERPRISE type (Microsoft 365) is Enterprise Server only. Before the existing-connector
        // lookup, so a source left over from an older Desktop build cannot be re-synced through a
        // re-registration either.
        if (catalog.requiresEnterprise(type) && !multiUser) {
            throw new IllegalArgumentException(serviceName(type) + " sources are available on Enterprise Server only");
        }
        // regardless of case upstream, so a same-scope re-registration differing only in case
        // must be recognized as the same connector - otherwise both are synced independently and
        // every item's sourceId ends up duplicated under two different connectorIds.
        // WEB_PAGE is deliberately excluded from the name-based lookup. Its display name defaults to
        // the seed page's <title>, which is neither unique across sites nor stable over time, so
        // matching on it merged unrelated websites into one connector. registerWebPage() has already
        // done the correct seed-URL-based lookup before reaching here, so anything that arrives is
        // known to be a genuinely new site.
        //
        // A LOCAL FOLDER is identified by its PATH, never by its name. The name is the folder's last
        // segment, so C:/A/docs and D:/B/docs were one source: the dialog refused the second as
        // "docs is already one of your sources", and the agent silently re-synced the first one.
        // Names stay the identity of an upload folder, which is unique inside its sandbox.
        var existing = type == ConnectorType.WEB_PAGE
                ? Optional.<RegisteredConnector>empty()
                : type == ConnectorType.LOCAL_FOLDER
                    ? findLocalFolderAt(userId, config, null)
                    : catalog.protocolFor(type) != null
                        ? findExistingThroughConnection(type, name, chosenConnection(type, userId, connectionId), userId)
                        : userId != null
                            ? connectorRepository.findByUserIdAndConnectorTypeAndNameIgnoreCase(userId, type, name)
                            : connectorRepository.findByConnectorTypeAndNameIgnoreCase(type, name);
        if (type == ConnectorType.LOCAL_FOLDER && existing.isPresent() && config != null
                && config.equals(existing.get().getName()) && !config.equals(name)) {
            // Named by its whole path by an older agent registration: it takes the folder's name.
            RegisteredConnector r = existing.get();
            r.setName(uniqueLocalFolderName(userId, name, r.getId()));
            connectorRepository.save(r);
            log.info("[Registry] Migrated connector name from path to folder name: id={} name={}", r.getId(), r.getName());
        }
        if (existing.isEmpty() && type == ConnectorType.UPLOADED_FILES && config != null && !config.equals(name)) {
            existing = userId != null
                    ? connectorRepository.findByUserIdAndConnectorTypeAndName(userId, type, config)
                    : connectorRepository.findByConnectorTypeAndName(type, config);
            if (existing.isPresent()) {
                RegisteredConnector r = existing.get();
                r.setName(name);
                connectorRepository.save(r);
                log.info("[Registry] Migrated connector name from path to folder name: id={} name={}", r.getId(), name);
            }
        }
        // A source being deleted is not "already registered" - it is about to vanish, and handing
        // back its id made the new registration disappear with it. See findWebPageBySeedUrl.
        existing = existing.filter(r -> !r.isDeleting());
        // Adding a source that exists is refused from a dialog - EXCEPT one with no usable connection,
        // where adding it again is exactly how a person repairs it (a source from before 0.18.1, or
        // one whose connection was deleted). That falls through to the branch below, which gives it
        // the connection and re-syncs it; refusing left the Sources page with no way to do what the
        // 0.19.0 notes tell people to do.
        if (existing.isPresent() && REJECT_EXISTING.get() && !lacksUsableConnection(existing.get(), type)) {
            throw new SourceAlreadyRegisteredException(existing.get().getName());
        }
        if (existing.isPresent()) {
            UUID existingId = existing.get().getId();
            log.info("[Registry] Connector already registered: type={} name={} id={}", type, name, existingId);
            adoptConnectionIfMissing(existing.get(), type, userId, connectionId);
            Thread.ofVirtual().name("resync-" + existingId).start(() -> {
                try {
                    syncOrchestratorService.syncConnector(existingId, userId);
                } catch (Exception e) {
                    log.warn("[Registry] Re-sync failed for connector={}: {}", existingId, e.getMessage());
                }
            });
            return existingId;
        }

        // The license - new-connector branch only, never on the resync-existing path above. Since
        // the two tiers only Microsoft 365 can fail it; there is no count or subfolder limit.
        checkLicensed(type);
        boolean effectiveRecursive = requestedRecursive;

        // A second folder with the same name as another gets a name of its own - the path is what
        // tells them apart, and two sources listed under one name could not be told apart at all.
        if (type == ConnectorType.LOCAL_FOLDER) name = uniqueLocalFolderName(userId, name, null);

        // One authenticated read against the resource itself, BEFORE the row exists. A wrong token
        // or a misspelled project key is refused while the person who typed it is still looking at
        // the form; without this a rejected credential produced a source that sat at zero items
        // and reported itself healthy. Every registration path reaches here - REST and the
        // agent tools - which is why the check lives at this depth and not in the controller.
        RegisteredConnector candidate = new RegisteredConnector();
        candidate.setConnectorType(type);
        candidate.setName(name);
        candidate.setConfig(config);
        candidate.setUserId(userId);
        // The connection goes onto the CANDIDATE first, so the probe below - and with it the first
        // sync - already reaches the chosen server with its credential, through the resolver like
        // every later sync.
        if (catalog.protocolFor(type) != null) {
            candidate.setConnectionId(registrationConnection(type, userId, connectionId).id());
        }
        candidate.setRecursive(effectiveRecursive);
        if (catalog.hasFileTypeFilter(type)) candidate.setFileTypes(normalizeFileTypes(fileTypes));
        AccessCheck check = syncOrchestratorService.verifyAccess(candidate);
        if (check.rejected()) {
            log.info("[Registry] Refusing registration: type={} name={} reason={}", type, name, check.detail());
            throw new IllegalArgumentException(check.detail());
        }

        RegisteredConnector connector = new RegisteredConnector();
        connector.setConnectorType(type);
        connector.setName(name);
        connector.setConfig(config);
        connector.setUserId(userId);
        connector.setRecursive(effectiveRecursive);
        if (catalog.hasFileTypeFilter(type)) connector.setFileTypes(normalizeFileTypes(fileTypes));
        connector.setConnectionId(candidate.getConnectionId());
        connectorRepository.save(connector);
        log.info("[Registry] Registered connector: type={} name={} userId={} id={}", type, name, userId, connector.getId());
        // After the save, which commits on its own (this method has no transaction), so a listener
        // that refetches the list cannot run ahead of the row it is meant to find.
        eventPublisher.publishEvent(new ConnectorRegisteredEvent(this, connector.getId(), userId));

        // The connector TYPE and nothing else. Not the name, not the config, not the id: `config`
        // holds a folder path or a Jira/Confluence/SharePoint base URL, `name` is a folder name
        // or a page title, and the id identifies one connector inside this installation. Which
        // integrations people actually connect is the whole question here, and the type answers
        // it on its own. Placed on the new-connector branch only - the resync-existing path above
        // returns before this, so re-registering an existing source is not counted as a new one.
        telemetryShipper.record(TelemetryEventType.SOURCE_REGISTERED, Map.of(
                "connectorType", type.name(),
                "recursive", effectiveRecursive));
        // Here rather than in the controller: the agent's register tools reach this too. The name,
        // never the config, which is a local folder's path for the file types.
        AuditEntry.of(AuditCategory.SOURCES, "source.added")
                .user(userId)
                .target(name)
                .summary("Source added: " + name)
                .detail("type", type.name())
                .detail("recursive", catalog.hasRecursiveScope(type) ? effectiveRecursive : null)
                .detail("fileTypes", connector.getFileTypes())
                .record();

        Thread.ofVirtual().name("sync-" + connector.getId()).start(() -> {
            try {
                syncOrchestratorService.syncConnector(connector.getId(), userId);
            } catch (Exception e) {
                log.warn("[Registry] Initial sync failed for connector={}: {}", connector.getId(), e.getMessage());
            }
        });

        return connector.getId();
    }

}
