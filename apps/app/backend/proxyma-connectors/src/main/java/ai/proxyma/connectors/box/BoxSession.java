package ai.proxyma.connectors.box;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.files.FileTypeSupport;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Box source, opened for one pass or one batch of fetches.
 *
 * <p>Items are {@code file:<fileId>}. Every file is downloaded as it is ({@code /files/{id}/content},
 * a redirect to a signed download address) and converted by the same converters a folder's files go
 * through; the source's file-type filter decides which. A Box Note ({@code .boxnote}) is read as
 * text (BoxNoteText) and counted as {@code .md}. Web links are not files and are left out.
 *
 * <p><b>Why the event stream.</b> A full pass walks the folder tree a page at a time
 * ({@code /folders/{id}/items}, marker paged) - one request per folder at least. Comparing folder
 * listings by {@code modified_at} would walk the whole tree on every pass too, since a folder's own
 * date does not reliably say that something deep inside it changed. The user's event stream
 * ({@code GET /events?stream_type=changes}) is what Box documents for keeping a copy in step, it works
 * with a user's own token, and its position resumes exactly where the last pass stopped: a CHANGES
 * pass reads only what changed. It keeps 21 days of events, and the engine lists every source in full
 * at least daily, so a stored position never outlives the stream. A full pass asks for the position
 * ({@code stream_position=now}) BEFORE it walks, so a change made while the walk runs is in the next
 * pass's events rather than in neither.
 *
 * <p>From the stream: a file created, uploaded, changed, moved in, restored or renamed is listed as it
 * is; one trashed, deleted or moved out of the source is listed as changed, and its fetch finds it
 * gone. A folder moved or copied in, or restored, is walked for its files. A folder trashed or moved
 * out names only itself; the files in it leave the index at the next full pass. Box may repeat an
 * event or send one out of order, so each file is listed once per pass, as its last event left it.
 */
final class BoxSession implements ConnectorSession {

    static final String FILE_FIELDS = "type,id,name,size,modified_at,sha1,item_status,extension,modified_by,path_collection";
    static final String BOX_NOTE = "boxnote";
    // An ordinary file larger than this is not downloaded - it is a video or an archive, not a document.
    static final long DOWNLOAD_LIMIT = 100L * 1024 * 1024;
    // A Box Note is read whole to be parsed; one larger than this is not a note anybody wrote.
    static final long NOTE_LIMIT = 20L * 1024 * 1024;
    // The most items Box answers a folder listing with, and the most events per page.
    static final int PAGE = 1000;
    static final int EVENTS_PAGE = 500;
    // The events that bring a folder's files into the source.
    private static final Set<String> FOLDER_ARRIVALS = Set.of("ITEM_MOVE", "ITEM_COPY", "ITEM_UNDELETE_VIA_TRASH",
            "ITEM_UPLOAD", "ITEM_CREATE");

    private final SourceContext context;
    private final ApiClient api;
    private final String folder;
    private final boolean recursive;
    private final Set<String> fileTypes;
    // The stream position the next pass resumes from - handed back as the cursor once listed.
    private String position;

    BoxSession(SourceContext context) {
        this.context = context;
        this.folder = BoxConnectorPlugin.folder(context.get(BoxConnectorPlugin.FOLDER));
        this.recursive = context.scope().recursive();
        this.fileTypes = FileTypeSupport.parseFileTypes(context.scope().fileTypes());
        this.api = BoxApi.client(context.http(), context.connection());
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        if (!BoxConnectorPlugin.isId(folder)) return AccessCheck.failed("That is not a Box folder ID or link.");
        try {
            api.getJson("folders/" + folder, Map.of("fields", "id,name"));
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) return AccessCheck.failed(noFolder());
            if (e.status() == 401) {
                return AccessCheck.failed("Box did not accept the connection's sign-in. Sign in again, or replace the "
                        + "developer token, in Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    private String noFolder() {
        return "Box has no folder " + folder + ", or the connection's account cannot see it.";
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        String stored = request.isFull() ? null : request.cursor();
        Supplier<Iterator<ItemMetadata>> everything;
        if (stored == null || !stored.strip().matches("[0-9]+")) {
            everything = this::full;
        } else {
            Changes changes = new Changes(stored.strip());
            everything = () -> Listings.concat(List.of(
                    changes::items,
                    // A position Box no longer accepts: the source is listed in full instead.
                    () -> changes.expired ? full() : Collections.emptyIterator(),
                    // The files of folders that arrived, less those the events already listed.
                    () -> changes.arrivedFolders.isEmpty() ? Collections.emptyIterator()
                            : Listings.mapped(walk(changes.arrivedFolders, false), f -> {
                                ItemMetadata item = fileItem(f);
                                return item == null || !changes.listed.add(item.getId()) ? null : item;
                            })));
        }
        return Listings.withCursor(Listings.concat(List.of(everything)), () -> position);
    }

    private Iterator<ItemMetadata> full() {
        if (!BoxConnectorPlugin.isId(folder)) throw new ConnectorException("That is not a Box folder ID or link.");
        position = startPosition();
        return Listings.mapped(walk(List.of(folder), true), this::fileItem);
    }

    private String startPosition() {
        JsonNode answer = api.getJson("events", Map.of("stream_type", "changes", "stream_position", "now"));
        String at = answer.path("next_stream_position").asText("");
        if (at.isBlank()) throw new ConnectorException("Box did not say where its events start.");
        return at;
    }

    // Every file in the given folders - and the folders inside them where the source descends - a
    // page at a time, breadth first, each folder once. A folder that went while the walk ran is
    // skipped; the source's own folder going is the listing's failure.
    private Iterator<JsonNode> walk(List<String> start, boolean sourceRoot) {
        Deque<String> queue = new ArrayDeque<>(start);
        Set<String> queued = new HashSet<>(start);
        return Paginator.iterate(next -> {
            String folderId;
            String marker = null;
            if (next == null) {
                folderId = queue.poll();
            } else {
                int bar = next.indexOf('|');
                folderId = next.substring(0, bar);
                marker = bar + 1 < next.length() ? next.substring(bar + 1) : null;
            }
            if (folderId == null) return new Paginator.Page<>(List.of(), null);
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("fields", FILE_FIELDS);
            query.put("usemarker", "true");
            query.put("limit", PAGE);
            if (marker != null) query.put("marker", marker);
            JsonNode page;
            try {
                page = api.getJson("folders/" + ApiClient.segment(folderId) + "/items", query);
            } catch (ConnectorHttpException e) {
                if (!e.isNotFound()) throw e;
                if (sourceRoot && folderId.equals(folder)) throw new ConnectorException(noFolder());
                page = null;
            }
            List<JsonNode> entries = page == null ? List.of() : Paginator.elements(page.path("entries"));
            if (recursive) {
                for (JsonNode e : entries) {
                    if (!"folder".equals(e.path("type").asText())) continue;
                    String id = e.path("id").asText("");
                    if (!id.isEmpty() && queued.add(id)) queue.add(id);
                }
            }
            String more = page == null ? "" : page.path("next_marker").asText("");
            String key = !more.isBlank() ? folderId + "|" + more : queue.isEmpty() ? null : queue.poll() + "|";
            return new Paginator.Page<>(entries, key);
        });
    }

    /**
     * What the event stream says changed since a stored position - read whole, so a file is listed
     * once however many events name it - and whether Box refused that position.
     */
    private final class Changes {
        private final String from;
        boolean expired;
        final List<String> arrivedFolders = new ArrayList<>();
        final Set<String> listed = new HashSet<>();

        Changes(String from) {
            this.from = from;
        }

        Iterator<ItemMetadata> items() {
            Map<String, ItemMetadata> files = new LinkedHashMap<>();
            Set<String> seenEvents = new HashSet<>();
            String at = from;
            boolean first = true;
            for (int pages = 0; pages < 10_000; pages++) {
                JsonNode page;
                try {
                    page = api.getJson("events", Map.of("stream_type", "changes", "stream_position", at,
                            "limit", EVENTS_PAGE));
                } catch (ConnectorHttpException e) {
                    // The stored position is not one Box will resume from any more.
                    if (first && (e.status() == 400 || e.isNotFound())) {
                        expired = true;
                        return Collections.emptyIterator();
                    }
                    throw e;
                }
                first = false;
                String next = page.path("next_stream_position").asText("");
                List<JsonNode> events = Paginator.elements(page.path("entries"));
                for (JsonNode event : events) {
                    String id = event.path("event_id").asText("");
                    if (!id.isEmpty() && !seenEvents.add(id)) continue;
                    event(event, files);
                }
                if (!next.isBlank()) position = next;
                if (events.isEmpty() || next.isBlank() || next.equals(at)) break;
                at = next;
            }
            if (position == null) position = from;
            listed.addAll(files.keySet());
            return files.values().iterator();
        }

        private void event(JsonNode event, Map<String, ItemMetadata> files) {
            JsonNode source = event.path("source");
            String type = source.path("type").asText("");
            String id = source.path("id").asText("");
            if (id.isEmpty()) return;
            String kind = event.path("event_type").asText("");
            if ("folder".equals(type)) {
                if (recursive && FOLDER_ARRIVALS.contains(kind) && active(source) && inScope(source)
                        && !arrivedFolders.contains(id) && !id.equals(folder)) {
                    arrivedFolders.add(id);
                }
                return;
            }
            if (!"file".equals(type)) return;
            files.remove("file:" + id);
            if (kind.equals("ITEM_TRASH") || kind.equals("DELETE") || !active(source)
                    || source.has("path_collection") && !inScope(source)) {
                files.put("file:" + id, gone(id, source.path("name").asText(id)));
                return;
            }
            // An event's file without its place in the tree is asked for once more, in full.
            JsonNode file = source.has("path_collection") ? source : describe(id);
            if (file == null || !active(file) || !inScope(file)) {
                files.put("file:" + id, gone(id, source.path("name").asText(id)));
                return;
            }
            ItemMetadata item = fileItem(file);
            if (item != null) files.put(item.getId(), item);
        }
    }

    // A file gone from the source, dated now, so the engine fetches it and finds it gone.
    private ItemMetadata gone(String id, String name) {
        ItemMetadata gone = new ItemMetadata();
        gone.setId("file:" + id);
        gone.setTitle(name);
        gone.setSourceType(ConnectorType.BOX);
        gone.setLastModified(context.clock().instant());
        return gone;
    }

    private JsonNode describe(String fileId) {
        try {
            return api.getJson("files/" + ApiClient.segment(fileId), Map.of("fields", FILE_FIELDS));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) return null;
            throw e;
        }
    }

    // A listed entry as an item - null for what the source does not index: a folder, a web link, a
    // type the filter leaves out.
    ItemMetadata fileItem(JsonNode f) {
        if (!"file".equals(f.path("type").asText()) || !active(f)) return null;
        String name = f.path("name").asText("");
        boolean note = isNote(f);
        if (!(note ? FileTypeSupport.isSupportedFileName("f.md", fileTypes) : FileTypeSupport.isSupportedFileName(name, fileTypes))) {
            return null;
        }
        String id = f.path("id").asText("");
        ItemMetadata item = new ItemMetadata();
        item.setId("file:" + id);
        item.setTitle(name);
        item.setSourceType(ConnectorType.BOX);
        item.setLastModified(BoxApi.instant(f.path("modified_at").asText(null)));
        item.setContentType(note ? "text/markdown" : FileTypeSupport.resolveMimeType(name));
        if (f.hasNonNull("size")) item.setSizeBytes(f.path("size").asLong());
        item.putExtra("url", "https://app.box.com/" + (note ? "notes/" : "file/") + id);
        String author = f.path("modified_by").path("name").asText("");
        if (!author.isBlank()) item.putExtra(ItemMetadata.AUTHOR, author);
        String sha1 = f.path("sha1").asText("");
        if (!sha1.isBlank()) item.putExtra(ItemMetadata.CONTENT_HASH, sha1);
        return item;
    }

    private static boolean isNote(JsonNode f) {
        String ext = f.path("extension").asText("");
        if (ext.isBlank()) {
            String name = f.path("name").asText("");
            int dot = name.lastIndexOf('.');
            ext = dot < 0 ? "" : name.substring(dot + 1);
        }
        return BOX_NOTE.equals(ext.toLowerCase(Locale.ROOT));
    }

    // Neither in the trash nor deleted - Box's item_status is "active" for both a file and a folder.
    private static boolean active(JsonNode item) {
        String status = item.path("item_status").asText("active");
        return status.isBlank() || "active".equals(status);
    }

    // Whether an item, by the folders above it, belongs to this source: anywhere under its folder, or
    // directly in it when the source does not descend into subfolders.
    private boolean inScope(JsonNode item) {
        List<JsonNode> path = Paginator.elements(item.path("path_collection").path("entries"));
        if (path.isEmpty()) return BoxApi.ALL_FILES.equals(folder) && recursive;
        if (!recursive) return folder.equals(path.get(path.size() - 1).path("id").asText());
        if (BoxApi.ALL_FILES.equals(folder)) return true;
        for (JsonNode parent : path) {
            if (folder.equals(parent.path("id").asText())) return true;
        }
        return false;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        if (!id.startsWith("file:") || !BoxConnectorPlugin.isId(id.substring(5))) {
            throw new ConnectorException("That is not an item of Box.");
        }
        String fileId = id.substring(5);
        JsonNode f = describe(fileId);
        if (f == null) throw new ItemGoneException("Box no longer has " + fileId + ".");
        String name = f.path("name").asText(fileId);
        if (!active(f)) throw new ItemGoneException("Box has moved " + name + " to the trash.");
        if (!inScope(f)) throw new ItemGoneException(name + " is no longer in this Box folder.");
        long size = f.path("size").asLong(0);
        if (isNote(f)) {
            if (size > NOTE_LIMIT) throw new ConnectorException("This Box Note is larger than 20 MB, so it was not indexed.");
            byte[] note;
            try (InputStream in = content(fileId, size)) {
                note = in.readNBytes((int) NOTE_LIMIT + 1);
            } catch (IOException e) {
                throw new ConnectorException("Box stopped sending this file part way. The next sync will try again.", e);
            }
            return new ByteArrayInputStream(BoxNoteText.markdown(note).getBytes(StandardCharsets.UTF_8));
        }
        if (size > DOWNLOAD_LIMIT) throw new ConnectorException("This file is larger than 100 MB, so it was not indexed.");
        return content(fileId, size);
    }

    // A file's bytes. Box answers 202 with no body for a file it has not finished taking in (one
    // uploaded a moment ago); that is said rather than indexed as an empty file.
    private InputStream content(String fileId, long size) {
        InputStream raw;
        try {
            raw = api.stream("files/" + ApiClient.segment(fileId) + "/content", Map.of(), Map.of("Accept", "*/*"));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Box no longer has " + fileId + ".");
            throw e;
        }
        if (size <= 0) return raw;
        PushbackInputStream in = new PushbackInputStream(raw, 1);
        try {
            int first = in.read();
            if (first < 0) {
                in.close();
                throw new ConnectorException("Box is still preparing this file. The next sync will try again.");
            }
            in.unread(first);
            return in;
        } catch (IOException e) {
            try {
                in.close();
            } catch (IOException ignored) {
                // already failing
            }
            throw new ConnectorException("Box stopped sending this file part way. The next sync will try again.", e);
        }
    }
}
