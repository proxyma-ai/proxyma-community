package ai.proxyma.connectors.dropbox;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.files.FileTypeSupport;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Dropbox source, opened for one pass or one batch of fetches.
 *
 * <p>Items are {@code file:<path>}, the file's path in lower case - Dropbox's own key for what its
 * change feed reports, which names a deleted file by its path alone. A file moved or renamed is
 * therefore the old item gone and a new one listed, which is what the feed says happened.
 *
 * <p>Every file is downloaded as it is and converted by the same converters a folder's files go
 * through; the source's file-type filter decides which. A file Dropbox will not hand out as it is
 * (a Paper doc) is exported - as markdown where Dropbox offers it, otherwise in the format it names -
 * and counted by the export's extension, a Paper doc as {@code .md}.
 *
 * <p>One full listing is {@code list_folder} and its {@code continue} pages; the cursor of the last
 * page is the source's cursor, and a CHANGES pass reads {@code list_folder/continue} from it: files
 * added or changed, and the paths deleted since. A cursor Dropbox has reset ({@code reset}) lists
 * the folder in full again. A deleted FOLDER names only itself, not the files that were in it; they
 * leave the index at the next full pass, at most a day later.
 */
final class DropboxSession implements ConnectorSession {

    // An ordinary file larger than this is not downloaded - it is a video or an archive, not a document.
    static final long DOWNLOAD_LIMIT = 100L * 1024 * 1024;
    // The most entries Dropbox is asked for per page (it may answer with a few more or fewer).
    static final int PAGE = 2000;

    private final SourceContext context;
    private final DropboxApi api;
    private final String root;
    private final String rootLower;
    private final boolean recursive;
    private final Set<String> fileTypes;
    // The cursor of the last page listed - handed back as the listing's cursor once it is exhausted.
    private String latestCursor;

    DropboxSession(SourceContext context) {
        this.context = context;
        this.root = DropboxConnectorPlugin.path(context.get(DropboxConnectorPlugin.PATH));
        this.rootLower = root.toLowerCase(Locale.ROOT);
        this.recursive = context.scope().recursive();
        this.fileTypes = FileTypeSupport.parseFileTypes(context.scope().fileTypes());
        this.api = new DropboxApi(context.http(), context.connection());
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            api.account();
            if (root.isEmpty()) return AccessCheck.verified();
            JsonNode folder = api.rpc("2/files/get_metadata", Map.of("path", root));
            if (!"folder".equals(folder.path(".tag").asText())) {
                return AccessCheck.failed("That is a file in Dropbox, not a folder.");
            }
            return AccessCheck.verified();
        } catch (DropboxApi.Refusal e) {
            if (e.notFound()) return AccessCheck.failed("Dropbox has no folder " + root + ", or the connection's account cannot see it.");
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorHttpException e) {
            return AccessCheck.failed(refusal(e));
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        String stored = request.isFull() ? null : request.cursor();
        Supplier<Iterator<ItemMetadata>> everything;
        if (stored == null || stored.isBlank()) {
            everything = this::full;
        } else {
            Changes changes = new Changes(stored);
            everything = () -> Listings.concat(List.of(
                    () -> Listings.mapped(changes.entries(), this::changeItem),
                    // A cursor Dropbox has reset: the folder is listed in full instead.
                    () -> changes.reset ? full() : Collections.emptyIterator()));
        }
        return Listings.withCursor(Listings.concat(List.of(everything)), () -> latestCursor);
    }

    private Iterator<ItemMetadata> full() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", root);
        args.put("recursive", recursive);
        args.put("include_deleted", false);
        args.put("include_non_downloadable_files", true);
        args.put("limit", PAGE);
        Iterator<JsonNode> entries = Paginator.iterate(next -> {
            JsonNode page;
            try {
                page = next == null ? api.rpc("2/files/list_folder", args) : more(next);
            } catch (DropboxApi.Refusal e) {
                if (next == null && e.notFound()) {
                    throw new ConnectorException("Dropbox has no folder " + root + ", or the connection's account cannot see it.");
                }
                if (next == null && e.is("path/not_folder")) throw new ConnectorException("That is a file in Dropbox, not a folder.");
                throw e;
            }
            return page(page);
        });
        return Listings.mapped(entries, this::fileItem);
    }

    private JsonNode more(String cursor) {
        return api.rpc("2/files/list_folder/continue", Map.of("cursor", cursor));
    }

    private Paginator.Page<JsonNode> page(JsonNode page) {
        String cursor = page.path("cursor").asText("");
        if (!cursor.isBlank()) latestCursor = cursor;
        boolean more = page.path("has_more").asBoolean(false);
        return new Paginator.Page<>(Paginator.elements(page.path("entries")), more && !cursor.isBlank() ? cursor : null);
    }

    /** What changed since a stored cursor - and whether Dropbox reset that cursor. */
    private final class Changes {
        private final String cursor;
        boolean reset;

        Changes(String cursor) {
            this.cursor = cursor;
        }

        Iterator<JsonNode> entries() {
            return Paginator.iterate(next -> {
                try {
                    return page(more(next == null ? cursor : next));
                } catch (DropboxApi.Refusal e) {
                    // The stored cursor is not one Dropbox will continue from any more.
                    if (next == null && e.is("reset")) {
                        reset = true;
                        return new Paginator.Page<>(List.of(), null);
                    }
                    throw e;
                }
            });
        }
    }

    // A change, as an item: a file added or changed is listed as it is; a deleted path is listed as
    // changed too, dated now, so its fetch finds it gone and it is removed. A folder is no item.
    private ItemMetadata changeItem(JsonNode entry) {
        if (!"deleted".equals(entry.path(".tag").asText())) return fileItem(entry);
        String path = entry.path("path_lower").asText("");
        if (path.isEmpty() || !inScope(path)) return null;
        ItemMetadata gone = new ItemMetadata();
        gone.setId("file:" + path);
        gone.setTitle(entry.path("name").asText(path));
        gone.setSourceType(ConnectorType.DROPBOX);
        gone.setLastModified(context.clock().instant());
        return gone;
    }

    // A listed entry as an item - null for what the source does not index: a folder, a file under a
    // folder the source does not descend into, a type the filter leaves out, a file Dropbox neither
    // hands out nor exports.
    ItemMetadata fileItem(JsonNode f) {
        if (!"file".equals(f.path(".tag").asText())) return null;
        String path = f.path("path_lower").asText("");
        if (path.isEmpty() || !inScope(path)) return null;
        String name = f.path("name").asText("");
        String contentType;
        if (downloadable(f)) {
            if (!FileTypeSupport.isSupportedFileName(name, fileTypes)) return null;
            contentType = FileTypeSupport.resolveMimeType(name);
        } else {
            String format = exportFormat(f);
            if (format == null || !FileTypeSupport.isSupportedFileName("f." + extension(format), fileTypes)) return null;
            contentType = FileTypeSupport.resolveMimeType("f." + extension(format));
        }
        ItemMetadata item = new ItemMetadata();
        item.setId("file:" + path);
        item.setTitle(name);
        item.setSourceType(ConnectorType.DROPBOX);
        item.setLastModified(DropboxApi.instant(f.path("server_modified").asText(null)));
        item.setContentType(contentType);
        if (f.hasNonNull("size")) item.setSizeBytes(f.path("size").asLong());
        String display = f.path("path_display").asText(path);
        item.putExtra("url", fileUrl(display, name));
        String hash = f.path("content_hash").asText("");
        if (!hash.isBlank()) item.putExtra(ItemMetadata.CONTENT_HASH, hash);
        return item;
    }

    private static boolean downloadable(JsonNode f) {
        return f.path("is_downloadable").asBoolean(true);
    }

    /**
     * What a file Dropbox will not hand out is exported as: markdown where Dropbox offers it (a Paper
     * doc), otherwise the format Dropbox names first - null when it names none.
     */
    static String exportFormat(JsonNode f) {
        JsonNode info = f.path("export_info");
        for (JsonNode option : info.path("export_options")) {
            if ("markdown".equals(option.asText())) return "markdown";
        }
        if ("markdown".equals(info.path("export_as").asText())) return "markdown";
        String as = info.path("export_as").asText("");
        return as.isBlank() ? null : as;
    }

    // The extension an export format reads as: markdown is .md, the rest are their own name (html, docx).
    private static String extension(String format) {
        String f = format.toLowerCase(Locale.ROOT);
        return "markdown".equals(f) ? "md" : f;
    }

    // Whether a path (lower case) belongs to this source: under its folder, and directly in it when
    // the source does not descend into subfolders.
    private boolean inScope(String path) {
        if (!rootLower.isEmpty() && !path.startsWith(rootLower + "/")) return false;
        if (recursive) return true;
        return path.lastIndexOf('/') == rootLower.length();
    }

    // The file on dropbox.com: its folder, with the file open in the preview.
    private static String fileUrl(String displayPath, String name) {
        int slash = displayPath.lastIndexOf('/');
        String folder = slash <= 0 ? "" : displayPath.substring(0, slash);
        return DropboxConnectorPlugin.webUrl(folder) + "?preview="
                + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        if (!id.startsWith("file:/")) throw new ConnectorException("That is not an item of Dropbox.");
        String path = id.substring(5);
        JsonNode f;
        try {
            f = api.rpc("2/files/get_metadata", Map.of("path", path));
        } catch (DropboxApi.Refusal e) {
            if (e.notFound()) throw new ItemGoneException("Dropbox no longer has " + path + ".");
            throw e;
        }
        String name = f.path("name").asText(path);
        if (!"file".equals(f.path(".tag").asText())) throw new ItemGoneException("Dropbox no longer has " + path + ".");
        if (!inScope(f.path("path_lower").asText(path))) throw new ItemGoneException(name + " is no longer in this Dropbox folder.");
        String file = f.path("id").asText("");
        String target = file.isBlank() ? path : file;
        if (!downloadable(f)) {
            String format = exportFormat(f);
            if (format == null) throw new ItemGoneException("Dropbox does not export " + name + " as text.");
            return api.download("2/files/export", Map.of("path", target, "export_format", format));
        }
        if (f.path("size").asLong(0) > DOWNLOAD_LIMIT) {
            throw new ConnectorException("This file is larger than 100 MB, so it was not indexed.");
        }
        return api.download("2/files/download", Map.of("path", target));
    }

    // What a registration's probe says when Dropbox refused it.
    private static String refusal(ConnectorHttpException e) {
        if (e.status() == 401) {
            return "Dropbox did not accept the connection's sign-in. Sign in again, or replace the access token, "
                    + "in Settings > Connections.";
        }
        return e.getMessage();
    }
}
