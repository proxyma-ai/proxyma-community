package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.system.util.Utf16;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A YOUTUBE VIDEO IS NAMED BY ITS TITLE, WHEREVER A PERSON READS IT (2026-11-06, the maintainer, on the Questions page
 * with YouTube's daily quota spent: every comment's video read "Open video" - "It must always the video title, not
 * generic title like this. Check all and every other locations."). One place every YouTube lookup asks for a video's
 * title - the comment queue's sync (YouTubeCommentSync), a dashboard's comments (YouTubeCommentFacts), an approval card
 * (YouTubeApprovalContext) and the Constellations pages through ChannelCommentsProvider.videoTitles - in this order:
 * <ol>
 *   <li><b>the channel SOURCE's index</b>, with no call: the YouTube connector indexes each upload as the document
 *   {@code video:<id>} titled with the video's title (YouTubeSession), so any YouTube source reading through the
 *   connection - or registered by its owner - holds it. A live row first; a deleted one (the video taken down since)
 *   only when there is none, since the title it had still names it better than nothing;</li>
 *   <li><b>what was read before</b>, in memory per connection: a title YouTube gave is kept - refreshed after
 *   {@link #TTL} when YouTube is asked anyway, and NEVER dropped because a later ask failed;</li>
 *   <li><b>YouTube</b> ({@code videos.list}, {@link #BATCH} ids a call, one quota unit each) - only through a client the
 *   caller hands in, so a caller that may not spend quota now never does. A video YouTube does not return is remembered
 *   as gone for {@link #GONE_TTL}, not asked again meanwhile.</li>
 * </ol>
 * Never thrown from: an ask that fails leaves those videos with what was held, and asks again next time.
 */
@Component
public class YouTubeVideoTitles {

    private static final Logger log = LoggerFactory.getLogger(YouTubeVideoTitles.class);

    static final int BATCH = 50;
    static final Duration TTL = Duration.ofHours(6);
    static final Duration GONE_TTL = Duration.ofHours(6);
    static final int KEPT_MAX = 20_000;
    static final int ASKED_MAX = 500;
    static final int NAME_MAX = 300;
    /** What the connector titles an upload with no title: not a title. */
    static final String UNTITLED = "(untitled video)";

    /** A video id as the API takes one (YouTube's are 11 characters; a looser shape costs nothing here). */
    static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    private final RegisteredConnectorRepository sources;
    private final TrackedDocumentRepository documents;
    /** connection:videoId -> what YouTube said; a null title is a video it did not return. */
    private final Map<String, Known> kept = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    private record Known(String title, Instant at) {}

    @Autowired
    public YouTubeVideoTitles(RegisteredConnectorRepository sources, TrackedDocumentRepository documents) {
        this.sources = sources;
        this.documents = documents;
    }

    /** No index to read: memory and YouTube only (a test, or a caller built without the registry). */
    static YouTubeVideoTitles memoryOnly() {
        return new YouTubeVideoTitles(null, null);
    }

    /** Tests only. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * The titles Proxyma already holds for these videos - the channel source's index, then what YouTube said before -
     * with NO call to YouTube. A video with no title held is absent.
     */
    public Map<String, String> held(String ownerUserId, UUID connectionId, Collection<String> videoIds) {
        Set<String> wanted = valid(videoIds);
        Map<String, String> out = new LinkedHashMap<>();
        if (wanted.isEmpty()) return out;
        Map<String, String> indexed = indexed(ownerUserId, connectionId, wanted);
        String scope = scope(connectionId);
        for (String v : wanted) {
            String title = indexed.get(v);
            if (title == null) {
                Known k = kept.get(scope + v);
                if (k != null) title = k.title();
            }
            if (title != null) out.put(v, title);
        }
        return out;
    }

    /**
     * The titles of these videos: what is held ({@link #held}), then - for the ones with none, or whose remembered title
     * is older than {@link #TTL} and not in the index - {@code videos.list} through {@code api}, {@link #BATCH} ids a
     * call. {@code api} null asks nothing. {@code calls}, when given, counts the calls made.
     */
    public Map<String, String> titles(ApiClient api, String ownerUserId, UUID connectionId, Collection<String> videoIds,
                                      int[] calls) {
        Set<String> wanted = valid(videoIds);
        Map<String, String> out = new LinkedHashMap<>();
        if (wanted.isEmpty()) return out;
        Map<String, String> indexed = indexed(ownerUserId, connectionId, wanted);
        String scope = scope(connectionId);
        Instant now = clock.instant();
        List<String> ask = new ArrayList<>();
        for (String v : wanted) {
            String title = indexed.get(v);
            if (title != null) {
                out.put(v, title);
                continue;
            }
            Known k = kept.get(scope + v);
            if (k != null && k.title() != null) out.put(v, k.title());
            boolean fresh = k != null && k.at().plus(k.title() == null ? GONE_TTL : TTL).isAfter(now);
            if (!fresh && ask.size() < ASKED_MAX) ask.add(v);
        }
        if (api == null || ask.isEmpty()) return out;
        trim(now);
        for (int i = 0; i < ask.size(); i += BATCH) {
            List<String> batch = ask.subList(i, Math.min(ask.size(), i + BATCH));
            Map<String, String> found = new LinkedHashMap<>();
            try {
                for (JsonNode v : api.getJson(YouTubeTools.V3 + "videos", Map.of("part", "snippet",
                        "id", String.join(",", batch))).path("items")) {
                    String title = cut(v.path("snippet").path("title").asText(""));
                    if (title != null) found.put(v.path("id").asText(""), title);
                }
                if (calls != null) calls[0]++;
            } catch (RuntimeException e) {
                // Left with what was held; asked again next time.
                log.debug("[YouTube] video titles could not be read: {}", e.getMessage());
                continue;
            }
            for (String v : batch) {
                String title = found.get(v);
                Known before = kept.get(scope + v);
                // A video YouTube no longer returns keeps the title it had: never dropped, only not asked for a while.
                kept.put(scope + v, new Known(title != null ? title : before == null ? null : before.title(), now));
                if (title != null) out.put(v, title);
            }
        }
        return out;
    }

    /** Whether YouTube, asked within {@link #GONE_TTL}, did not return this video and no title of it is held. */
    public boolean gone(UUID connectionId, String videoId) {
        Known k = videoId == null ? null : kept.get(scope(connectionId) + videoId.strip());
        return k != null && k.title() == null && k.at().plus(GONE_TTL).isAfter(clock.instant());
    }

    /** A title read elsewhere (an approval card's videos.list): remembered as if asked here. */
    public void remember(UUID connectionId, String videoId, String title) {
        String t = cut(title);
        if (videoId == null || t == null || !VIDEO_ID.matcher(videoId).matches()) return;
        trim(clock.instant());
        kept.put(scope(connectionId) + videoId, new Known(t, clock.instant()));
    }

    // The channel source's index: video:<id> documents of the YouTube sources on this connection or this owner's.
    private Map<String, String> indexed(String ownerUserId, UUID connectionId, Set<String> videoIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (sources == null || documents == null || (connectionId == null && ownerUserId == null)) return out;
        try {
            List<UUID> ids = sources.findIdsByTypeAndConnectionOrOwner(ConnectorType.YOUTUBE_CHANNEL, connectionId, ownerUserId);
            if (ids.isEmpty()) return out;
            List<String> sourceIds = new ArrayList<>();
            for (String v : videoIds) sourceIds.add("video:" + v);
            Map<String, String> deleted = new LinkedHashMap<>();
            for (int i = 0; i < sourceIds.size(); i += ASKED_MAX) {
                for (Object[] row : documents.findTitlesAnyStatus(ids, sourceIds.subList(i, Math.min(sourceIds.size(), i + ASKED_MAX)))) {
                    String sourceId = String.valueOf(row[0]);
                    String title = row[1] == null ? null : cut(String.valueOf(row[1]));
                    if (title == null || UNTITLED.equals(title) || !sourceId.startsWith("video:")) continue;
                    String v = sourceId.substring("video:".length());
                    if (row[2] == TrackedDocument.Status.DELETED) deleted.putIfAbsent(v, title);
                    else out.putIfAbsent(v, title);
                }
            }
            deleted.forEach(out::putIfAbsent);
        } catch (RuntimeException e) {
            log.debug("[YouTube] the index's video titles could not be read: {}", e.getMessage());
        }
        return out;
    }

    // Bounded: past the bound, what is gone goes first, then everything older than a day, then all.
    private void trim(Instant now) {
        if (kept.size() < KEPT_MAX) return;
        kept.values().removeIf(k -> k.title() == null);
        if (kept.size() >= KEPT_MAX) kept.values().removeIf(k -> k.at().plus(Duration.ofDays(1)).isBefore(now));
        if (kept.size() >= KEPT_MAX) kept.clear();
    }

    private static String scope(UUID connectionId) {
        return (connectionId == null ? "-" : connectionId.toString()) + ":";
    }

    static Set<String> valid(Collection<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        if (ids == null) return out;
        for (String id : ids) {
            String s = id == null ? "" : id.strip();
            if (VIDEO_ID.matcher(s).matches()) out.add(s);
        }
        return out;
    }

    static String cut(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        return t.length() > NAME_MAX ? Utf16.cut(t, NAME_MAX - 3) + "..." : t;
    }
}
