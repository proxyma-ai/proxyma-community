package ai.proxyma.core.tools;

import ai.proxyma.core.common.conversation.ConversationPaths;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.search.domain.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

// Shared by WebFetchTool and WebSearchTool: downloads media discovered on a fetched/searched
// page and saves it under the CURRENT conversation's folder (SearchContext.getConversationId(),
// set by ChatService for every tool call), so it can later be served back to the browser via
// GET /api/v1/chat/conversations/{id}/media/{fileName} (proxyma-core-chat). Deliberately kept out
// of proxyma-core-chat's SmartChatMemory - ReadTextFileTool/SearchTextFileTool already establish
// the precedent of proxyma-core-tools resolving conversation paths itself via
// proxyma.conversations-dir/proxyma.multi-user rather than depending on chat's internal service -
// and the folder comes from the same ConversationPaths SmartChatMemory builds it with.
@Component
class WebMediaStorage {

    private static final Logger log = LoggerFactory.getLogger(WebMediaStorage.class);

    // Mirrors ChatAttachmentController.MAX_ATTACHMENT_SIZE_BYTES (25MB) as the combined cap for
    // one tool call's worth of downloaded media; MAX_ITEM_BYTES bounds any single file so one
    // huge asset can't consume the whole budget alone.
    private static final int MAX_MEDIA_ITEMS = 5;
    private static final long MAX_ITEM_BYTES = 10L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024;

    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif");

    // WHAT MAY BE SAVED, by the type the RESPONSE declares - and the extension it is saved under,
    // which is this type's, never the URL's. The saved file is served back from the application's
    // own origin, by its name: a page's <img src="https://evil/x.html"> answered as anything was
    // saved as x.html, and an SVG kept its scripts - stored cross-site scripting with the reader's
    // session. Raster images, two video and four audio formats; nothing that can carry markup.
    private static final java.util.Map<String, String> SAVED_TYPES = java.util.Map.ofEntries(
            java.util.Map.entry("image/png", "png"), java.util.Map.entry("image/jpeg", "jpg"),
            java.util.Map.entry("image/gif", "gif"), java.util.Map.entry("image/webp", "webp"),
            java.util.Map.entry("image/avif", "avif"), java.util.Map.entry("image/bmp", "bmp"),
            java.util.Map.entry("video/mp4", "mp4"), java.util.Map.entry("video/webm", "webm"),
            java.util.Map.entry("audio/mpeg", "mp3"), java.util.Map.entry("audio/ogg", "ogg"),
            java.util.Map.entry("audio/wav", "wav"), java.util.Map.entry("audio/x-wav", "wav"),
            java.util.Map.entry("audio/mp4", "m4a"));

    /** The extension a response of this type is saved under, or null when it is not saved at all. */
    static String savedExtension(String contentType) {
        if (contentType == null) return null;
        int semi = contentType.indexOf(';');
        String mime = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
        return SAVED_TYPES.get(mime);
    }
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm", "mov", "m4v");
    private static final Set<String> AUDIO_EXT = Set.of("mp3", "wav", "ogg", "m4a", "flac");

    // A default java.net.http.HttpClient identifies itself as "Java-http-client/21..." - a
    // meaningful share of real sites reject or degrade responses for non-browser User-Agents
    // outright (no bot-detection service needed, just a plain header check), independent of any
    // TLS-fingerprinting-level blocking that headers alone can never defeat. Mirrors a current
    // desktop Chrome's actual request headers - not scoped to any real Chrome version on purpose,
    // just needs to look like a normal browser, not track one exactly.
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";
    private static final String BROWSER_ACCEPT_LANGUAGE = "en-US,en;q=0.9";

    private final RestClient externalRestClient;
    private final ConversationPaths paths;

    WebMediaStorage(@Qualifier("externalRestClientBuilder") RestClient.Builder externalRestClientBuilder,
                     @Value("${proxyma.conversations-dir:${user.home}/.proxyma/conversations}") String conversationsDir,
                     @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.externalRestClient = externalRestClientBuilder
                .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, BROWSER_ACCEPT)
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, BROWSER_ACCEPT_LANGUAGE)
                .build();
        this.paths = new ConversationPaths(conversationsDir, multiUser);
    }

    record SavedMedia(String type, String mediaUrl, String originalUrl, long sizeBytes) {}

    // Downloads up to MAX_MEDIA_ITEMS deduped candidate URLs (bounded per-item and combined
    // size), saves accepted ones under the current conversation's web-media folder. Best-effort:
    // an unreachable/oversized candidate is silently skipped, never fails the whole tool call.
    List<SavedMedia> downloadAndSave(List<String> candidateUrls) {
        String conversationId = SearchContext.getConversationId();
        if (conversationId == null || conversationId.isBlank() || candidateUrls == null || candidateUrls.isEmpty()) {
            return List.of();
        }
        Path dir = resolveWebMediaDir(conversationId);
        if (dir == null) return List.of();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("[WebMediaStorage] could not create {}: {}", dir, e.getMessage());
            return List.of();
        }

        List<SavedMedia> saved = new ArrayList<>();
        long totalBytes = 0;
        Set<String> seen = new HashSet<>();
        for (String url : candidateUrls) {
            if (saved.size() >= MAX_MEDIA_ITEMS || totalBytes >= MAX_TOTAL_BYTES) break;
            if (url == null || url.isBlank() || !seen.add(url)) continue;
            try {
                FetchedBody body = fetchCapped(url, MAX_ITEM_BYTES);
                // A partial media file is corrupt, and a type not on the list is not saved at all.
                String ext = body == null || body.truncated() ? null : savedExtension(body.contentType());
                byte[] bytes = ext == null ? null : body.bytes();
                if (bytes == null || bytes.length == 0 || totalBytes + bytes.length > MAX_TOTAL_BYTES) continue;
                String fileName = uniqueFileName(dir, buildFileName(url, ext));
                Files.write(dir.resolve(fileName), bytes);
                totalBytes += bytes.length;
                saved.add(new SavedMedia(mediaType(fileName), buildMediaUrl(conversationId, fileName), url, bytes.length));
            } catch (Exception e) {
                log.debug("[WebMediaStorage] skipping media {}: {}", url, e.getMessage());
            }
        }
        return saved;
    }

    // For webFetch(url) when the URL itself IS the media resource (Content-Type already sniffed
    // by the caller) - bytes are already in hand, so this only handles naming/saving/URL-building.
    // Null when the type is not one that is saved (see SAVED_TYPES).
    SavedMedia saveDirect(String url, String contentType, byte[] bytes) {
        String conversationId = SearchContext.getConversationId();
        String ext = savedExtension(contentType);
        if (conversationId == null || conversationId.isBlank() || bytes == null || bytes.length == 0 || ext == null) {
            return null;
        }
        Path dir = resolveWebMediaDir(conversationId);
        if (dir == null) return null;
        try {
            Files.createDirectories(dir);
            String fileName = uniqueFileName(dir, buildFileName(url, ext));
            Files.write(dir.resolve(fileName), bytes);
            return new SavedMedia(mediaType(fileName), buildMediaUrl(conversationId, fileName), url, bytes.length);
        } catch (IOException e) {
            log.warn("[WebMediaStorage] failed to save {}: {}", url, e.getMessage());
            return null;
        }
    }

    // truncated=true means the body was cut off at maxBytes rather than the fetch failing outright
    // - callers decide whether partial content is still usable (WebFetchTool keeps/parses a
    // truncated HTML/text page; downloadCapped above discards a truncated media file as corrupt).
    record FetchedBody(String contentType, byte[] bytes, boolean truncated) {}

    // Real fetch failures (network error, non-2xx HTTP status, ...) throw this with as much detail
    // as is available - notably the HTTP status code when the failure was a status response - so
    // WebFetchTool can report *why* a fetch failed instead of a single ambiguous null that also
    // covered "response exceeded the size cap" (which is not a failure - see FetchedBody.truncated).
    static final class FetchException extends RuntimeException {
        private final Integer statusCode;

        FetchException(String message, Integer statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        Integer statusCode() {
            return statusCode;
        }
    }

    // General-purpose bounded GET, shared by media downloads above and WebFetchTool's primary
    // page fetch - stops reading once a response reaches maxBytes rather than downloading a huge
    // resource in full, but returns what was read so far (truncated=true) instead of discarding it.
    //
    // READ AS A STREAM from the response itself. It was toEntity(Resource.class), which Spring's
    // ResourceHttpMessageConverter answers by copying the WHOLE body into a byte array first - so
    // the cap below ran over bytes already on the heap, and a fetch of an endless or
    // multi-gigabyte resource was an OutOfMemoryError for every user of the server.
    FetchedBody fetchCapped(String url, long maxBytes) {
        try {
            return externalRestClient.get()
                    .uri(toRequestUri(url))
                    .exchange((req, res) -> {
                        if (!res.getStatusCode().is2xxSuccessful()) {
                            throw new FetchException("HTTP " + res.getStatusCode().value() + " for " + url,
                                    res.getStatusCode().value());
                        }
                        String contentType = res.getHeaders().getContentType() != null
                                ? res.getHeaders().getContentType().toString() : "";
                        try (InputStream in = res.getBody()) {
                            return readCapped(in, contentType, maxBytes);
                        }
                    });
        } catch (FetchException e) {
            log.debug("[WebMediaStorage] fetch failed for {}: {}", url, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.debug("[WebMediaStorage] fetch failed for {}: {}", url, e.getMessage());
            throw new FetchException(Failures.describe(e), null);
        }
    }

    static FetchedBody readCapped(InputStream in, String contentType, long maxBytes) throws IOException {
        if (in == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        boolean truncated = false;
        int n;
        while ((n = in.read(buf)) != -1) {
            int toWrite = n;
            if (total + n > maxBytes) {
                toWrite = (int) (maxBytes - total);
                truncated = true;
            }
            if (toWrite > 0) out.write(buf, 0, toWrite);
            total += toWrite;
            if (truncated) break;
        }
        return new FetchedBody(contentType, out.toByteArray(), truncated);
    }

    // URI.create()/new URI(String) is the STRICT RFC-3986 parser: it rejects characters a browser
    // tolerates in a real-world link - a second '#' (a fragment can't itself contain '#', hence the
    // "Illegal character in fragment" error on docs whose in-page anchor is another .htm#..., and
    // literal spaces are common in unencoded hrefs. Build a request URI leniently instead:
    //   1. drop the fragment (everything from the first '#') - it is never sent in an HTTP request,
    //      so this both fixes the double-'#' case and avoids encoding work on a part we discard;
    //   2. try the strict parser on the remainder first so already-valid URLs are untouched;
    //   3. only on failure, percent-encode the illegal characters that remain, leaving existing
    //      %xx escapes intact so an already-encoded URL isn't double-encoded (see the double-
    //      encoding trap a connector once fell into).
    static URI toRequestUri(String url) {
        int hash = url.indexOf('#');
        String noFragment = hash >= 0 ? url.substring(0, hash) : url;
        try {
            return URI.create(noFragment);
        } catch (IllegalArgumentException e) {
            return URI.create(encodeIllegalUriChars(noFragment));
        }
    }

    // RFC-3986 characters legal in a URI string (unreserved + reserved), MINUS '%' which is handled
    // separately so a valid existing escape is preserved rather than re-encoded to %25.
    private static final String URI_LEGAL_PUNCT = "-._~:/?#[]@!$&'()*+,;=";

    private static String encodeIllegalUriChars(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                sb.append(s, i, i + 3); // keep an already-valid %xx escape verbatim
                i += 2;
            } else if (c < 0x80 && (Character.isLetterOrDigit(c) || URI_LEGAL_PUNCT.indexOf(c) >= 0)) {
                sb.append(c);
            } else {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    sb.append('%').append(Character.forDigit((b >> 4) & 0xF, 16))
                            .append(Character.forDigit(b & 0xF, 16));
                }
            }
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    // The caller's own folder for this conversation, built by ConversationPaths - which refuses a
    // conversation id that is a path rather than resolving it into somebody else's folder
    // (tools-core-02). Null when refused: nothing is saved.
    private Path resolveWebMediaDir(String conversationId) {
        try {
            return paths.conversationDir(conversationId, SearchContext.getUserId()).resolve("web-media");
        } catch (IllegalArgumentException e) {
            log.warn("[WebMediaStorage] not saving media for a conversation id that names no folder: {}", e.getMessage());
            return null;
        }
    }

    private static String buildMediaUrl(String conversationId, String fileName) {
        return "/api/v1/chat/conversations/" + conversationId + "/media/" + fileName;
    }

    // The URL's last segment as a stem, with the extension of the type the response declared.
    private static String buildFileName(String url, String ext) {
        String stem = buildFileName(url);
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        return stem + "." + ext;
    }

    private static String buildFileName(String url) {
        String last;
        try {
            String path = URI.create(url).getPath();
            last = (path == null || path.isBlank()) ? "" : Path.of(path).getFileName().toString();
        } catch (Exception e) {
            last = "";
        }
        if (last.isBlank()) {
            last = "media-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String sanitized = last.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.length() > 120 ? sanitized.substring(sanitized.length() - 120) : sanitized;
    }

    private static String uniqueFileName(Path dir, String fileName) {
        if (!Files.exists(dir.resolve(fileName))) return fileName;
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        return stem + "-" + UUID.randomUUID().toString().substring(0, 8) + ext;
    }

    static String mediaType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot > 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (IMAGE_EXT.contains(ext)) return "image";
        if (VIDEO_EXT.contains(ext)) return "video";
        if (AUDIO_EXT.contains(ext)) return "audio";
        return "file";
    }
}
