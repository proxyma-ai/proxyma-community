package ai.proxyma.core.tools.sources;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.ingest.service.DocumentFetcher;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.ingest.IngestDraft;
import ai.proxyma.core.registry.ingest.IngestService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.system.util.SensitivePaths;
import ai.proxyma.core.tools.ActiveServers;
import ai.proxyma.core.tools.SourceRegistration;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The agent's tools for sources of ANY type - built from the connector catalog, so a new connector
 * is offered to the agent the moment its plugin exists, with no tool of its own to write. They
 * replace the eighteen per-type register and remove tools.
 *
 * <ul>
 *   <li>{@code listSourceTypes} - what can be added here, and what each needs.</li>
 *   <li>{@code addSource} - registers one, checked against its descriptor exactly as the dialog's
 *       form is (ConnectorService.register).</li>
 *   <li>{@code removeSource} - unregisters one of the caller's own sources.</li>
 *   <li>{@code readSourceItem} - reads one item of a source live, where its type allows.</li>
 *   <li>{@code saveToSource} - saves a note as a document of one of the caller's own custom sources,
 *       through the same store and indexing as the ingestion API (IngestService).</li>
 * </ul>
 *
 * <p>Adding and removing are writes: guarded by the caller's connector-management switch here and by
 * the approval prompt (WriteToolRegistry), like the tools they replace. Saving a note is a write too,
 * and takes the same approval and the conversation's permission mode - Read-only refuses it - but no
 * switch: it changes nothing but what the caller's own source holds.
 */
@Component
public class SourceTools {

    // An item read live is handed to the model whole only up to this; the rest is said to be cut.
    static final int MAX_ITEM_CHARS = 30_000;

    // A note's author as its header names it - so a reader can tell a note the assistant wrote from one
    // a script pushed.
    static final String NOTE_AUTHOR = "Proxyma assistant";

    private final ConnectorService connectorService;
    private final ConnectorCatalog catalog;
    private final McpGuardService mcpGuard;
    private final ActiveServers activeServers;
    private final SourceContextFactory contexts;
    private final DocumentFetcher documents;
    private final FeatureGateService featureGate;
    private final boolean multiUser;

    public SourceTools(ConnectorService connectorService, ConnectorCatalog catalog, McpGuardService mcpGuard,
                       ActiveServers activeServers, SourceContextFactory contexts, DocumentFetcher documents,
                       FeatureGateService featureGate, @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.connectorService = connectorService;
        this.catalog = catalog;
        this.mcpGuard = mcpGuard;
        this.activeServers = activeServers;
        this.contexts = contexts;
        this.documents = documents;
        this.featureGate = featureGate;
        this.multiUser = multiUser;
    }

    // Where saveToSource stores - setter-injected, so the constructor the tests build this with is unchanged.
    private IngestService ingest;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setIngest(IngestService ingest) {
        this.ingest = ingest;
    }

    @Tool(description = "List the types of source that can be added here (addSource): each type's id, what it "
            + "indexes, the fields addSource takes for it, and the connection it reads through.")
    @ToolResult({
            @ToolResultField(name = "types", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "type (what addSource takes), name, description, fields (key, label, required, "
                            + "type, choices, help), connection (the service, or null when none is needed), "
                            + "fileTypes and subfolders (whether addSource's fileTypes and recursive apply).")
    })
    public Map<String, Object> listSourceTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConnectorDescriptor d : catalog.visibleTypes(multiUser)) {
            ConnectorPlugin plugin = catalog.plugin(d.type()).orElse(null);
            if (plugin == null || !plugin.offered()) continue;
            if (!featureGate.getFeatures().isConnectorAllowed(d.capabilities().edition())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", d.type().wireName());
            m.put("name", d.noun());
            m.put("description", d.description());
            List<Map<String, Object>> fields = new ArrayList<>();
            for (FieldSpec f : d.fields()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("key", f.key());
                fm.put("label", f.label());
                fm.put("type", f.type().name().toLowerCase());
                fm.put("required", f.required());
                if (!f.options().isEmpty()) fm.put("choices", f.options().stream().map(FieldSpec.Option::value).toList());
                if (f.help() != null) fm.put("help", f.help());
                if (f.placeholder() != null) fm.put("example", f.placeholder());
                if (f.defaultValue() != null) fm.put("default", f.defaultValue());
                fields.add(fm);
            }
            m.put("fields", fields);
            ConnectionProtocol p = d.protocol();
            m.put("connection", p == null ? null : catalog.displayName(p));
            m.put("fileTypes", d.capabilities().fileTypeFilter());
            m.put("subfolders", d.capabilities().recursiveScope());
            out.add(m);
        }
        return Map.of("success", true, "types", out);
    }

    @Tool(description = "Add a source for indexing and search: type is one of listSourceTypes' types, fields its "
            + "fields. Reads through the connection this conversation uses for that service (see useServer), "
            + "else the user's default. Confirm what to add with the user first.")
    @ToolResult({
            @ToolResultField(name = "alreadyRegistered", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True when the source already existed: nothing was added, it was re-synced "
                            + "under its existing id."),
            @ToolResultField(name = "message", type = ToolFieldType.STRING, always = true,
                    description = "What happened - added, or already registered and re-synced."),
            @ToolResultField(name = "sourceId", type = ToolFieldType.STRING, always = true,
                    description = "UUID of the source - the id removeSource and readSourceItem take.")
    })
    public Map<String, Object> addSource(
            @ToolParam(description = "The type of source, as listSourceTypes names it, e.g. jira, webpage") String type,
            @ToolParam(description = "The type's fields by key, e.g. {\"projectKey\": \"PROJ\"}") Map<String, Object> fields,
            @ToolParam(required = false, description = "Index subfolders - only where the type has subfolders")
            Boolean recursive,
            @ToolParam(required = false, description = "Comma-separated file extensions to keep - only where the type "
                    + "has a file-type filter") String fileTypes) {
        try {
            String userId = SearchContext.getUserId();
            mcpGuard.checkConnectorManagement(userId);
            ConnectorType t = type == null ? null : catalog.typeForWireName(type.strip());
            ConnectorDescriptor d = t == null ? null : catalog.descriptor(t);
            if (d == null || !d.listed() || !catalog.hasPlugin(t) || !d.capabilities().edition().offeredOn(multiUser)) {
                return Map.of("success", false, "error", "'" + type + "' is not a type of source that can be added here. "
                        + "listSourceTypes lists them.");
            }
            // The server this conversation switched to with useServer, else the default - the same
            // choice every live tool of the service makes. Over MCP nothing is picked.
            UUID picked = d.protocol() == null ? null : activeServers.active(t).map(Endpoint::connectionId).orElse(null);
            ConnectorService.SourceRequest request = new ConnectorService.SourceRequest(t, picked,
                    fields == null ? Map.of() : fields, recursive, fileTypes);
            return SourceRegistration.outcome(connectorService, d.noun() + " added", "sourceId",
                    () -> connectorService.register(request, userId));
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }

    @Tool(description = "Remove one of the user's own sources by its id (getConnectorStatus lists them): stops "
            + "indexing it and deletes its search data. Confirm with the user first.")
    @ToolResult({
            @ToolResultField(name = "message", type = ToolFieldType.STRING, always = true,
                    description = "That removal started - it finishes in the background."),
            @ToolResultField(name = "sourceId", type = ToolFieldType.STRING, always = true,
                    description = "The id of the source being removed.")
    })
    public Map<String, Object> removeSource(
            @ToolParam(description = "The source's id, as getConnectorStatus or addSource gave it") String sourceId) {
        try {
            mcpGuard.checkConnectorManagement(SearchContext.getUserId());
            return SourceRegistration.removal(connectorService, sourceId, "sourceId", "source",
                    Set.of(ConnectorType.values()));
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "error", "That is not a source id. getConnectorStatus lists them.");
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }

    @Tool(description = "Read one item of a source live, as it is at the source now - an issue, a page, a file. "
            + "itemId is the item's id or title as search results and getConnectorStatus show it. Only for the "
            + "user's own sources of a type that can be read live.")
    @ToolResult({
            @ToolResultField(name = "title", type = ToolFieldType.STRING, always = false,
                    description = "The item's title, where it is indexed."),
            @ToolResultField(name = "text", type = ToolFieldType.STRING, always = true,
                    description = "The item's content as text."),
            @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True when the text was cut to fit.")
    })
    public Map<String, Object> readSourceItem(
            @ToolParam(description = "The source's id") String sourceId,
            @ToolParam(description = "The item's id or title") String itemId) {
        try {
            String userId = SearchContext.getUserId();
            UUID id;
            try {
                id = UUID.fromString(sourceId.strip());
            } catch (IllegalArgumentException | NullPointerException e) {
                return Map.of("success", false, "error", "That is not a source id. getConnectorStatus lists them.");
            }
            RegisteredConnector source = connectorService.listVisibleTo(userId).stream()
                    .filter(c -> c.getId().equals(id)).findFirst().orElse(null);
            if (source == null) {
                return Map.of("success", false, "error", "None of the sources you can see has the id " + id + ".");
            }
            // A source somebody else owns lends the caller nothing - not its credential, so not a live
            // read either. Its indexed text is what searchIndexedSources is for.
            if (!Objects.equals(source.getUserId(), userId)) {
                return Map.of("success", false, "error", "This source is shared with you; read it through "
                        + "searchIndexedSources, which covers what its owner indexed.");
            }
            ConnectorDescriptor d = catalog.descriptor(source.getConnectorType());
            ConnectorPlugin plugin = catalog.plugin(source.getConnectorType()).orElse(null);
            if (d == null || plugin == null || !d.capabilities().liveRead()) {
                return Map.of("success", false, "error", "Sources of this type cannot be read live; "
                        + "searchIndexedSources covers what they hold.");
            }
            ItemMetadata item = item(source, itemId, userId);
            if (item == null) {
                return Map.of("success", false, "error", "This source has no item '" + itemId + "'. "
                        + "Use the id or title a search result shows.");
            }
            String text;
            try (ConnectorSession session = plugin.open(contexts.build(source));
                 InputStream in = session.fetch(item)) {
                String type = item.getContentType();
                text = type == null || type.startsWith("text/") || type.contains("markdown") || type.contains("json")
                        ? new String(in.readNBytes(MAX_ITEM_CHARS * 4 + 1), StandardCharsets.UTF_8)
                        : documents.convertToMarkdown(in, type);
            }
            if (text == null) text = "";
            boolean truncated = text.length() > MAX_ITEM_CHARS;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            if (item.getTitle() != null) out.put("title", item.getTitle());
            out.put("text", SensitivePaths.scrub(truncated ? text.substring(0, MAX_ITEM_CHARS) : text));
            out.put("truncated", truncated);
            return out;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }

    @Tool(description = "Save a note as a document in one of the user's OWN custom sources (type custom), where "
            + "searchIndexedSources finds it once it is indexed - and so does anyone the source is shared with. "
            + "noteId names the note: saving under a noteId used before replaces that note. Only when the user "
            + "asks to save something there.")
    @ToolResult({
            @ToolResultField(name = "noteId", type = ToolFieldType.STRING, always = true,
                    description = "The note's id in the source - save under it again to replace the note."),
            @ToolResultField(name = "outcome", type = ToolFieldType.STRING, always = true,
                    description = "created, replaced, or unchanged when the same note was saved before."),
            @ToolResultField(name = "message", type = ToolFieldType.STRING, always = true,
                    description = "What happened.")
    })
    public Map<String, Object> saveToSource(
            @ToolParam(description = "The custom source's id, as getConnectorStatus lists it") String sourceId,
            @ToolParam(description = "The note's title") String title,
            @ToolParam(description = "The note's text, in markdown") String text,
            @ToolParam(required = false, description = "An id for the note: letters, digits and . _ : - only. "
                    + "The same id replaces the note later; one is made when empty") String noteId,
            @ToolParam(required = false, description = "A web address the note is about - search results link to it")
            String url) {
        try {
            if (ingest == null) {
                return Map.of("success", false, "error", "Notes cannot be saved to sources on this installation.");
            }
            String userId = SearchContext.getUserId();
            UUID id;
            try {
                id = UUID.fromString(sourceId.strip());
            } catch (IllegalArgumentException | NullPointerException e) {
                return Map.of("success", false, "error", "That is not a source id. getConnectorStatus lists them.");
            }
            RegisteredConnector source = ingest.pushSource(id);
            // The caller's OWN custom source and nothing else: a shared one is theirs to read, not to
            // write - the same rule the ingestion API's tokens keep.
            if (source == null || !Objects.equals(source.getUserId(), userId)) {
                return Map.of("success", false, "error", "Notes can be saved only to your own custom sources. "
                        + "getConnectorStatus lists them; addSource with type custom makes one.");
            }
            String note = noteId == null || noteId.isBlank()
                    ? "note-" + NOTE_ID_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 6)
                    : noteId.strip();
            IngestService.Saved saved = ingest.upsertText(source, new IngestDraft(note, title, text, "markdown", url,
                    NOTE_AUTHOR, null, null, null));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("noteId", saved.id());
            out.put("outcome", saved.outcome());
            out.put("message", "unchanged".equals(saved.outcome())
                    ? "The source already holds this note as it is; nothing changed."
                    : "Saved to " + source.getName() + " - searchable once it is indexed, usually within a minute.");
            return out;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }

    private static final DateTimeFormatter NOTE_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    // The item to read: an indexed document of the source by its id or title (a file-based source's
    // only way - its ids are paths that never reach the model), else, for a type whose items are API
    // records, the id the model named.
    private ItemMetadata item(RegisteredConnector source, String itemId, String userId) {
        if (itemId == null || itemId.isBlank()) return null;
        String wanted = itemId.strip();
        TrackedDocument doc = null;
        try {
            doc = connectorService.findDocument(source.getId(), UUID.fromString(wanted), userId).orElse(null);
        } catch (IllegalArgumentException notAUuid) {
            // a title or a source id
        }
        if (doc == null) {
            List<TrackedDocument> named = connectorService.findDocumentsNamed(source.getId(), userId, wanted);
            if (named.size() == 1) doc = named.get(0);
        }
        ItemMetadata meta = new ItemMetadata();
        meta.setSourceType(source.getConnectorType());
        if (doc != null) {
            meta.setId(doc.getSourceId());
            meta.setTitle(doc.getTitle());
            meta.setContentType(doc.getContentType());
            return meta;
        }
        if (source.getConnectorType().isFileBacked()) return null;
        meta.setId(wanted);
        return meta;
    }
}
