package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.files.FileTypeSupport;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
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
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Google Drive source, opened for one pass or one batch of fetches.
 *
 * <p>Items are {@code file:<fileId>}. A Google Doc is exported as Markdown (as plain text where
 * Google will not give Markdown), a Sheet as CSV - its first sheet, which is what Google exports to
 * CSV - and Slides as plain text. Any other Google file (a form, a drawing, a shortcut) is left out.
 * Every other file is downloaded as it is, with the content type Drive states, and converted by the
 * same converters a folder's files go through; the source's file-type filter decides which, with a
 * Doc counted as .docx, a Sheet as .xlsx and Slides as .pptx.
 *
 * <p>Corpora: the account's own ("user": My Drive and what is shared with it) and each chosen
 * shared drive - or, for a folder, the corpus the folder lives in. The cursor keeps a change-feed
 * page token for each, so a CHANGES pass reads only what changed in each one.
 */
final class DriveSession implements ConnectorSession {

    static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    static final String GOOGLE_DOC = "application/vnd.google-apps.document";
    static final String GOOGLE_SHEET = "application/vnd.google-apps.spreadsheet";
    static final String GOOGLE_SLIDES = "application/vnd.google-apps.presentation";
    static final String GOOGLE_APPS = "application/vnd.google-apps.";
    // Google exports at most 10 MB of a Doc, Sheet or Slides; a larger one cannot be read this way.
    static final long EXPORT_LIMIT = 10L * 1024 * 1024;
    // An ordinary file larger than this is not downloaded - it is a video or an archive, not a document.
    static final long DOWNLOAD_LIMIT = 100L * 1024 * 1024;
    static final String FILE_FIELDS = "id,name,mimeType,modifiedTime,size,md5Checksum,webViewLink,trashed,"
            + "ownedByMe,sharedWithMeTime,driveId,parents,lastModifyingUser(displayName,emailAddress)";
    // The cursor key of the account's own corpus; a shared drive's is its id.
    static final String USER = "user";

    private record Corpus(String key, String driveId) {}

    private final SourceContext context;
    private final ApiClient api;
    private final boolean myDrive;
    private final boolean sharedWithMe;
    private final Set<String> drives;
    private final String folder;
    private final Set<String> fileTypes;
    // Whether a folder is the source's folder or inside it, learned while walking and while asking.
    private final Map<String, Boolean> insideFolder = new HashMap<>();
    // The page token each corpus resumes from next - handed back as the cursor once listed.
    private final Map<String, String> tokens = new LinkedHashMap<>();
    private JsonNode folderInfo;

    DriveSession(SourceContext context) {
        this.context = context;
        Map<String, String> config = context.config();
        this.myDrive = DriveConnectorPlugin.flag(config, DriveConnectorPlugin.MY_DRIVE, true);
        this.sharedWithMe = DriveConnectorPlugin.flag(config, DriveConnectorPlugin.SHARED_WITH_ME, false);
        this.drives = DriveConnectorPlugin.sharedDrives(config);
        this.folder = DriveConnectorPlugin.id(config.get(DriveConnectorPlugin.FOLDER));
        this.fileTypes = FileTypeSupport.parseFileTypes(context.scope().fileTypes());
        this.api = GoogleApi.client(context.http(), context.connection(), "Google Drive");
        if (!folder.isEmpty()) insideFolder.put(folder, true);
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        String asking = null;
        try {
            if (!folder.isEmpty()) {
                if (!DriveConnectorPlugin.isId(folder)) return AccessCheck.failed("That is not a Google Drive folder ID or link.");
                asking = "folder";
                if (!FOLDER_MIME.equals(folderInfo().path("mimeType").asText())) {
                    return AccessCheck.failed("That is a file in Google Drive, not a folder.");
                }
                return AccessCheck.verified();
            }
            if (!myDrive && !sharedWithMe && drives.isEmpty()) {
                return AccessCheck.failed("Choose what to read: My Drive, files shared with you, a shared drive or a folder.");
            }
            for (String drive : drives) {
                if (!DriveConnectorPlugin.isId(drive)) return AccessCheck.failed("That is not a shared drive ID or link: " + drive);
                asking = drive;
                api.getJson("drive/v3/drives/" + ApiClient.segment(drive));
            }
            if (drives.isEmpty()) api.getJson("drive/v3/about", Map.of("fields", "user(emailAddress)"));
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.isNotFound() && "folder".equals(asking)) {
                return AccessCheck.failed("Google Drive has no folder " + folder + ", or the connection's account cannot see it.");
            }
            if (e.isNotFound() && asking != null) {
                return AccessCheck.failed("Google Drive has no shared drive " + asking + ", or the connection's account cannot see it.");
            }
            return AccessCheck.failed(GoogleApi.refusal(e, "Google Drive API"));
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        Map<String, String> stored = request.isFull() ? Map.of() : GoogleApi.readCursor(request.cursor());
        Supplier<Iterator<ItemMetadata>> everything = () -> {
            List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
            for (Corpus corpus : corpora()) {
                String token = stored.get(corpus.key());
                if (token == null || token.isBlank()) {
                    parts.add(() -> fullCorpus(corpus));
                } else {
                    ChangeFeed feed = new ChangeFeed(corpus, token);
                    parts.add(() -> Listings.mapped(feed.changes(), this::changeItem));
                    // A token Drive no longer accepts: this corpus is listed in full instead.
                    parts.add(() -> feed.expired ? fullCorpus(corpus) : Collections.emptyIterator());
                }
            }
            return Listings.concat(parts);
        };
        return Listings.withCursor(Listings.concat(List.of(everything)), () -> GoogleApi.writeCursor(tokens));
    }

    private List<Corpus> corpora() {
        if (!folder.isEmpty()) {
            if (!DriveConnectorPlugin.isId(folder)) throw new ConnectorException("That is not a Google Drive folder ID or link.");
            String drive = folderInfo().path("driveId").asText("");
            return List.of(drive.isEmpty() ? new Corpus(USER, null) : new Corpus(drive, drive));
        }
        List<Corpus> out = new ArrayList<>();
        if (myDrive || sharedWithMe) out.add(new Corpus(USER, null));
        for (String drive : drives) out.add(new Corpus(drive, drive));
        return out;
    }

    // Every file of one corpus - the token is asked for FIRST, so a change made while the listing
    // runs is in the next pass's feed rather than in neither.
    private Iterator<ItemMetadata> fullCorpus(Corpus corpus) {
        tokens.put(corpus.key(), startToken(corpus));
        Iterator<JsonNode> files = folder.isEmpty() ? files(corpus) : folderWalk(corpus);
        return Listings.mapped(files, this::fileItem);
    }

    private String startToken(Corpus corpus) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("supportsAllDrives", "true");
        if (corpus.driveId() != null) query.put("driveId", corpus.driveId());
        String token = api.getJson("drive/v3/changes/startPageToken", query).path("startPageToken").asText("");
        if (token.isBlank()) throw new ConnectorException("Google Drive did not say where its changes start.");
        return token;
    }

    private Iterator<JsonNode> files(Corpus corpus) {
        Map<String, Object> query = fileQuery();
        if (corpus.driveId() != null) {
            query.put("corpora", "drive");
            query.put("driveId", corpus.driveId());
            query.put("includeItemsFromAllDrives", "true");
            query.put("q", "trashed = false");
        } else {
            query.put("corpora", "user");
            query.put("q", myDrive && sharedWithMe ? "('me' in owners or sharedWithMe = true) and trashed = false"
                    : myDrive ? "'me' in owners and trashed = false"
                    : "sharedWithMe = true and trashed = false");
        }
        return Paginator.cursor(api, "drive/v3/files", query, "pageToken", page -> page.path("files"),
                page -> page.path("nextPageToken").asText(null));
    }

    // The folder and every folder inside it, breadth first, a page at a time. Each folder is walked
    // once, however many parents it has - a page key seen twice would end the whole listing.
    private Iterator<JsonNode> folderWalk(Corpus corpus) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> queued = new HashSet<>();
        queue.add(folder);
        queued.add(folder);
        return Paginator.iterate(next -> {
            String folderId;
            String pageToken = null;
            if (next == null) {
                folderId = queue.poll();
            } else {
                int bar = next.indexOf('|');
                folderId = next.substring(0, bar);
                pageToken = bar + 1 < next.length() ? next.substring(bar + 1) : null;
            }
            Map<String, Object> query = fileQuery();
            query.put("includeItemsFromAllDrives", "true");
            if (corpus.driveId() != null) {
                query.put("corpora", "drive");
                query.put("driveId", corpus.driveId());
            } else {
                query.put("corpora", "allDrives");
            }
            query.put("q", "'" + folderId + "' in parents and trashed = false");
            if (pageToken != null) query.put("pageToken", pageToken);
            JsonNode page = api.getJson("drive/v3/files", query);
            List<JsonNode> files = Paginator.elements(page.path("files"));
            for (JsonNode f : files) {
                if (!FOLDER_MIME.equals(f.path("mimeType").asText())) continue;
                String id = f.path("id").asText("");
                insideFolder.put(id, true);
                if (!id.isEmpty() && queued.add(id)) queue.add(id);
            }
            String more = page.path("nextPageToken").asText("");
            String key = !more.isBlank() ? folderId + "|" + more : queue.isEmpty() ? null : queue.poll() + "|";
            return new Paginator.Page<>(files, key);
        });
    }

    private Map<String, Object> fileQuery() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("fields", "nextPageToken,files(" + FILE_FIELDS + ")");
        query.put("pageSize", 1000);
        query.put("supportsAllDrives", "true");
        return query;
    }

    /** One corpus's change feed from a stored page token - and whether Drive refused that token. */
    private final class ChangeFeed {
        private final Corpus corpus;
        private final String token;
        boolean expired;

        ChangeFeed(Corpus corpus, String token) {
            this.corpus = corpus;
            this.token = token;
        }

        Iterator<JsonNode> changes() {
            return Paginator.iterate(next -> {
                Map<String, Object> query = new LinkedHashMap<>();
                query.put("pageToken", next == null ? token : next);
                query.put("pageSize", 1000);
                query.put("includeRemoved", "true");
                query.put("supportsAllDrives", "true");
                query.put("fields", "nextPageToken,newStartPageToken,changes(changeType,removed,fileId,time,file("
                        + FILE_FIELDS + "))");
                if (corpus.driveId() != null) {
                    query.put("driveId", corpus.driveId());
                    query.put("includeItemsFromAllDrives", "true");
                }
                JsonNode page;
                try {
                    page = api.getJson("drive/v3/changes", query);
                } catch (ConnectorHttpException e) {
                    // The stored token is not one Drive accepts any more - listed in full instead.
                    if (next == null && (e.status() == 400 || e.isNotFound())) {
                        expired = true;
                        return new Paginator.Page<>(List.of(), null);
                    }
                    throw e;
                }
                String fresh = page.path("newStartPageToken").asText("");
                if (!fresh.isBlank()) tokens.put(corpus.key(), fresh);
                return new Paginator.Page<>(Paginator.elements(page.path("changes")),
                        page.path("nextPageToken").asText(null));
            });
        }
    }

    // A change, as an item: a file in the source, changed; a file gone from it - removed, trashed,
    // moved out, no longer shared - listed as changed too, so its fetch finds it gone and it is
    // removed. A folder's own change is not an item.
    private ItemMetadata changeItem(JsonNode change) {
        if ("drive".equals(change.path("changeType").asText())) return null;
        String fileId = change.path("fileId").asText("");
        if (fileId.isEmpty()) return null;
        JsonNode file = change.path("file");
        Instant when = GoogleApi.instant(change.path("time").asText(null));
        if (when == null) when = context.clock().instant();
        if (!file.isMissingNode() && FOLDER_MIME.equals(file.path("mimeType").asText())) return null;
        if (change.path("removed").asBoolean(false) || file.isMissingNode() || file.isNull()
                || file.path("trashed").asBoolean(false) || !inScope(file)) {
            ItemMetadata gone = new ItemMetadata();
            gone.setId("file:" + fileId);
            gone.setTitle(file.path("name").asText(fileId));
            gone.setSourceType(ConnectorType.GOOGLE_DRIVE);
            gone.setLastModified(when);
            return gone;
        }
        return fileItem(file);
    }

    // A listed file as an item - null for what the source does not index: a folder, a Google file
    // that does not export, a type the source's filter leaves out.
    ItemMetadata fileItem(JsonNode f) {
        String mime = f.path("mimeType").asText("");
        if (FOLDER_MIME.equals(mime) || f.path("trashed").asBoolean(false)) return null;
        String name = f.path("name").asText("");
        String contentType = exportType(mime);
        if (contentType == null) {
            if (mime.startsWith(GOOGLE_APPS)) return null;
            contentType = mime.isBlank() || mime.equals("application/octet-stream") ? FileTypeSupport.resolveMimeType(name) : mime;
        }
        if (!wanted(name, mime)) return null;
        ItemMetadata item = new ItemMetadata();
        item.setId("file:" + f.path("id").asText());
        item.setTitle(name);
        item.setSourceType(ConnectorType.GOOGLE_DRIVE);
        item.setLastModified(GoogleApi.instant(f.path("modifiedTime").asText(null)));
        item.setContentType(contentType);
        if (f.hasNonNull("size")) item.setSizeBytes(f.path("size").asLong());
        GoogleApi.putExtra(item, "url", f.path("webViewLink").asText(null));
        JsonNode editor = f.path("lastModifyingUser");
        GoogleApi.putExtra(item, ItemMetadata.AUTHOR, editor.path("displayName").asText(editor.path("emailAddress").asText(null)));
        GoogleApi.putExtra(item, ItemMetadata.CONTENT_HASH, f.path("md5Checksum").asText(null));
        return item;
    }

    /** What a Google file is exported as - null for anything that is not a Doc, a Sheet or Slides. */
    static String exportType(String mime) {
        return switch (mime) {
            case GOOGLE_DOC -> GoogleApi.MARKDOWN;
            case GOOGLE_SHEET -> "text/csv";
            case GOOGLE_SLIDES -> "text/plain";
            default -> null;
        };
    }

    // The file-type filter, by the file's extension or the one its content type implies.
    private boolean wanted(String name, String mime) {
        String implied = switch (mime) {
            case GOOGLE_DOC -> "docx";
            case GOOGLE_SHEET -> "xlsx";
            case GOOGLE_SLIDES -> "pptx";
            case "application/pdf" -> "pdf";
            case "text/plain" -> "txt";
            case "text/markdown", "text/x-markdown" -> "md";
            case "text/csv" -> "csv";
            case "text/html" -> "html";
            case "application/msword" -> "doc";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.ms-powerpoint" -> "ppt";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            default -> null;
        };
        if (mime.startsWith(GOOGLE_APPS)) return FileTypeSupport.isSupportedFileName("f." + implied, fileTypes);
        return FileTypeSupport.isSupportedFileName(name, fileTypes)
                || implied != null && FileTypeSupport.isSupportedFileName("f." + implied, fileTypes);
    }

    // Whether a file Drive described belongs to this source - asked of a changed file and of a file
    // being fetched, so one moved out of the folder or no longer shared leaves the index.
    private boolean inScope(JsonNode f) {
        if (!folder.isEmpty()) {
            for (JsonNode parent : f.path("parents")) {
                if (inside(parent.asText(""), 0)) return true;
            }
            return false;
        }
        String drive = f.path("driveId").asText("");
        if (!drive.isEmpty()) return drives.contains(drive);
        if (f.path("ownedByMe").asBoolean(false)) return myDrive;
        return sharedWithMe && f.hasNonNull("sharedWithMeTime");
    }

    // Whether a folder is the source's folder or inside it, asking Drive for parents up the tree.
    private boolean inside(String folderId, int depth) {
        if (folderId.isEmpty() || depth > 64) return false;
        Boolean known = insideFolder.get(folderId);
        if (known != null) return known;
        insideFolder.put(folderId, false); // a loop in the tree answers no rather than for ever
        boolean result = false;
        try {
            JsonNode node = api.getJson("drive/v3/files/" + ApiClient.segment(folderId),
                    Map.of("fields", "id,parents", "supportsAllDrives", "true"));
            for (JsonNode parent : node.path("parents")) {
                if (inside(parent.asText(""), depth + 1)) {
                    result = true;
                    break;
                }
            }
        } catch (ConnectorHttpException e) {
            if (!e.isNotFound()) throw e;
        }
        insideFolder.put(folderId, result);
        return result;
    }

    private JsonNode folderInfo() {
        if (folderInfo == null) {
            folderInfo = api.getJson("drive/v3/files/" + ApiClient.segment(folder),
                    Map.of("fields", "id,name,mimeType,driveId", "supportsAllDrives", "true"));
        }
        return folderInfo;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        if (!id.startsWith("file:") || !DriveConnectorPlugin.isId(id.substring(5))) {
            throw new ConnectorException("That is not an item of Google Drive.");
        }
        String fileId = id.substring(5);
        JsonNode f;
        try {
            f = api.getJson("drive/v3/files/" + ApiClient.segment(fileId),
                    Map.of("fields", FILE_FIELDS, "supportsAllDrives", "true"));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Google Drive no longer has " + fileId + ".");
            throw e;
        }
        String name = f.path("name").asText(fileId);
        if (f.path("trashed").asBoolean(false)) throw new ItemGoneException("Google Drive has moved " + name + " to the trash.");
        if (!inScope(f)) throw new ItemGoneException(name + " is no longer in this part of Google Drive.");
        String mime = f.path("mimeType").asText("");
        long size = f.path("size").asLong(0);
        String export = exportType(mime);
        if (export != null) return export(fileId, mime, export, size);
        if (mime.startsWith(GOOGLE_APPS)) throw new ItemGoneException("Google Drive does not export " + name + " as text.");
        if (size > DOWNLOAD_LIMIT) throw new ConnectorException("This file is larger than 100 MB, so it was not indexed.");
        try {
            return api.stream("drive/v3/files/" + ApiClient.segment(fileId),
                    Map.of("alt", "media", "supportsAllDrives", "true"), Map.of("Accept", "*/*"));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Google Drive no longer has " + fileId + ".");
            throw e;
        }
    }

    // A Doc, a Sheet or Slides as text. Read whole: an export is at most 10 MB, and a refusal's body
    // is what says the file was too large to export.
    private InputStream export(String fileId, String mime, String type, long size) {
        if (size > EXPORT_LIMIT) throw tooLargeToExport();
        String path = "drive/v3/files/" + ApiClient.segment(fileId) + "/export";
        ApiResponse response = api.get(path, Map.of("mimeType", type));
        if (!response.ok() && response.status() == 400 && GOOGLE_DOC.equals(mime)) {
            response = api.get(path, Map.of("mimeType", "text/plain"));
        }
        if (response.ok()) return new ByteArrayInputStream(response.bytes());
        if (response.status() == 403 && response.text().contains("exportSizeLimitExceeded")) throw tooLargeToExport();
        if (response.status() == 404 || response.status() == 410) {
            throw new ItemGoneException("Google Drive no longer has " + fileId + ".");
        }
        throw api.failure(response);
    }

    private static ConnectorException tooLargeToExport() {
        return new ConnectorException("Google Drive exports files of up to 10 MB, and this one is larger, so it was not indexed.");
    }
}
