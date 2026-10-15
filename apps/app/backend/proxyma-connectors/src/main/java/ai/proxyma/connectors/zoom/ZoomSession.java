package ai.proxyma.connectors.zoom;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Zoom source, opened for one pass or one batch of fetches.
 *
 * <p>Items are {@code meeting:<uuid>} - the UUID of one meeting occurrence, as Zoom gives it (it may
 * hold "/", "+" and "="; see {@link ZoomApi#uuidSegment}). A meeting is in the source while it has a
 * finished transcript (or closed captions) in its cloud recording, or an AI Companion summary, and
 * started inside the window.
 *
 * <p>A listing reads, per person, Zoom's recordings a month at a time ({@code from}/{@code to}, the
 * most Zoom allows in one request, 300 a page) and their meeting summaries over the same months,
 * and joins the two by meeting UUID. A meeting's date is its own, so the item's time is what moves
 * when it changes: its newest transcript or summary, a millisecond later for each part beyond the
 * first, so a transcript arriving after the summary (or the other way round) is read again.
 *
 * <p>Meeting summaries need a scope of their own ({@code meeting:read:list_summaries} and
 * {@code meeting:read:summary}, or their {@code :admin} forms). Without it the listing carries on
 * with transcripts alone and says it is partial, so a summary already indexed is not removed for
 * a scope that is only missing.
 */
final class ZoomSession implements ConnectorSession {

    /** Days read again before the last complete pass, for a transcript or summary Zoom finished late. */
    static final int LAG_DAYS = 3;
    /** The days in one recordings request - Zoom's limit is a month. */
    static final int WINDOW_DAYS = 30;
    static final int PAGE_SIZE = 300;
    // A transcript is text; one larger than this is not a transcript.
    static final int MAX_TRANSCRIPT_BYTES = 32 * 1024 * 1024;
    static final String SUMMARIES_REFUSED = "Zoom did not let the connection read meeting summaries, so only "
            + "transcripts were listed. Add the meeting summary scopes to the Zoom app.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourceContext context;
    private final ApiAuth auth;
    private final ApiClient api;
    private final String apiBase;
    private final List<String> users;
    private final int days;
    private final boolean account;
    // User id -> how the person is named, for the items' author.
    private final Map<String, String> people = new HashMap<>();
    // Meetings already listed this pass - one person's recording is another's too, on "*".
    private final Set<String> listed = new HashSet<>();
    private volatile String partialReason;
    private boolean summariesRefused;

    ZoomSession(SourceContext context, String tokenUrl) {
        this.context = context;
        this.users = ZoomConnectorPlugin.users(context.config());
        this.days = ZoomConnectorPlugin.days(context.config());
        this.account = context.connection().authKind() == AuthKind.KEY_PAIR;
        this.apiBase = ZoomApi.baseUrl(context.connection());
        this.auth = ZoomApi.auth(context.http(), context.connection(), tokenUrl, context.clock());
        this.api = context.http().client(apiBase).service("Zoom").auth(auth).rateLimit(ZoomApi.RATE_LIMIT).build();
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        if (account && users.isEmpty()) {
            return AccessCheck.failed("A Server-to-Server app reads the people you name. Add their email addresses "
                    + "under People, or * for everyone on the account.");
        }
        try {
            if (users.isEmpty()) {
                api.getJson("users/me");
            } else if (users.contains(ZoomConnectorPlugin.EVERYONE)) {
                api.getJson("users", Map.of("status", "active", "page_size", 1));
            } else {
                for (String user : users) {
                    try {
                        api.getJson("users/" + ApiClient.segment(user));
                    } catch (ConnectorHttpException e) {
                        if (e.isNotFound()) return AccessCheck.failed("Zoom has no user " + user + " on this account.");
                        throw e;
                    }
                }
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException | ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        LocalDate today = LocalDate.now(context.clock().withZone(ZoneOffset.UTC));
        LocalDate cutoff = today.minusDays(days);
        LocalDate from = cutoff;
        if (!request.isFull()) {
            LocalDate resume = cursorDay(request.cursor());
            if (request.since() != null) {
                LocalDate since = LocalDate.ofInstant(request.since(), ZoneOffset.UTC);
                resume = resume == null || since.isBefore(resume) ? since : resume;
            }
            if (resume != null) {
                LocalDate back = resume.minusDays(LAG_DAYS);
                from = back.isAfter(cutoff) ? back : cutoff;
            }
        }
        LocalDate start = from;
        Supplier<Iterator<ItemMetadata>> pass = () -> {
            List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
            for (String user : targets()) parts.add(() -> meetings(user, start, today));
            return Listings.concat(parts);
        };
        Listing listing = Listings.withCursor(Listings.concat(List.of(pass)), () -> cursor(today));
        return Listings.partialIf(listing, () -> partialReason);
    }

    // Whose meetings a pass reads: "me", the people named, or everyone on the account.
    private List<String> targets() {
        if (users.isEmpty()) {
            JsonNode me = api.getJson("users/me");
            remember(me);
            return List.of("me");
        }
        List<String> out = new ArrayList<>();
        if (!users.contains(ZoomConnectorPlugin.EVERYONE)) {
            // Asked about once each, so the items can name their host.
            for (String user : users) {
                try {
                    remember(api.getJson("users/" + ApiClient.segment(user)));
                } catch (ConnectorHttpException e) {
                    if (e.isNotFound()) throw new ConnectorException("Zoom has no user " + user + " on this account.");
                    throw e;
                }
                out.add(user);
            }
            return out;
        }
        Iterator<JsonNode> all = Paginator.cursor(api, "users", Map.of("status", "active", "page_size", PAGE_SIZE),
                "next_page_token", page -> page.path("users"), page -> page.path("next_page_token").asText(null));
        while (all.hasNext()) {
            JsonNode user = all.next();
            remember(user);
            String id = user.path("id").asText("");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    private void remember(JsonNode user) {
        String id = user.path("id").asText("");
        if (!id.isEmpty()) people.put(id, ZoomConnectionPlugin.who(user));
    }

    /** One meeting as the listing knows it: from its recording, its summary, or both. */
    private static final class Meeting {
        final String uuid;
        String topic = "";
        Instant start;
        int duration;
        String hostId = "";
        String hostEmail = "";
        String shareUrl;
        Instant transcriptAt;
        Instant summaryAt;

        Meeting(String uuid) {
            this.uuid = uuid;
        }
    }

    // A person's meetings with a transcript or a summary, between two days - read whole, then
    // listed oldest first.
    private Iterator<ItemMetadata> meetings(String user, LocalDate from, LocalDate to) {
        Map<String, Meeting> found = new LinkedHashMap<>();
        String who = ApiClient.segment(user);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(WINDOW_DAYS)) {
            LocalDate end = day.plusDays(WINDOW_DAYS - 1);
            if (end.isAfter(to)) end = to;
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("from", day.toString());
            query.put("to", end.toString());
            query.put("page_size", PAGE_SIZE);
            Iterator<JsonNode> recordings = Paginator.cursor(api, "users/" + who + "/recordings", query, "next_page_token",
                    page -> page.path("meetings"), page -> page.path("next_page_token").asText(null));
            while (recordings.hasNext()) recording(found, recordings.next());
            if (!summariesRefused) summaries(found, who, day, end);
        }
        List<Meeting> meetings = new ArrayList<>(found.values());
        meetings.removeIf(m -> m.transcriptAt == null && m.summaryAt == null);
        meetings.sort(Comparator.comparing((Meeting m) -> m.start == null ? Instant.EPOCH : m.start));
        List<ItemMetadata> items = new ArrayList<>();
        for (Meeting m : meetings) {
            if (listed.add(m.uuid)) items.add(item(m));
        }
        return items.iterator();
    }

    private static void recording(Map<String, Meeting> found, JsonNode recording) {
        String uuid = recording.path("uuid").asText("");
        if (uuid.isEmpty()) return;
        Meeting m = found.computeIfAbsent(uuid, Meeting::new);
        m.topic = recording.path("topic").asText(m.topic);
        if (m.start == null) m.start = ZoomApi.instant(recording.path("start_time").asText(null));
        m.duration = recording.path("duration").asInt(m.duration);
        m.hostId = recording.path("host_id").asText(m.hostId);
        String share = recording.path("share_url").asText("");
        if (!share.isBlank()) m.shareUrl = share;
        JsonNode text = transcriptFile(recording);
        if (text != null) {
            Instant at = ZoomApi.instant(text.path("recording_end").asText(null));
            if (at == null) at = m.start;
            if (at != null) m.transcriptAt = m.transcriptAt == null || at.isAfter(m.transcriptAt) ? at : m.transcriptAt;
        }
    }

    // The summaries of a person's meetings that started between two days. A refusal - a missing
    // scope, a plan without AI Companion - leaves summaries out for the rest of the pass.
    private void summaries(Map<String, Meeting> found, String who, LocalDate from, LocalDate to) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("from", from + "T00:00:00Z");
        query.put("to", to + "T23:59:59Z");
        query.put("page_size", PAGE_SIZE);
        Iterator<JsonNode> summaries = Paginator.cursor(api, "users/" + who + "/meeting_summaries", query,
                "next_page_token", page -> page.path("summaries"), page -> page.path("next_page_token").asText(null));
        try {
            while (summaries.hasNext()) {
                JsonNode s = summaries.next();
                String uuid = s.path("meeting_uuid").asText("");
                if (uuid.isEmpty()) continue;
                Meeting m = found.computeIfAbsent(uuid, Meeting::new);
                if (m.topic.isEmpty()) m.topic = s.path("meeting_topic").asText("");
                if (m.start == null) m.start = ZoomApi.instant(s.path("meeting_start_time").asText(null));
                if (m.hostEmail.isEmpty()) m.hostEmail = s.path("meeting_host_email").asText("");
                if (m.hostId.isEmpty()) m.hostId = s.path("meeting_host_id").asText("");
                Instant at = ZoomApi.instant(s.path("summary_last_modified_time").asText(null));
                if (at == null) at = ZoomApi.instant(s.path("summary_created_time").asText(null));
                if (at == null) at = m.start;
                m.summaryAt = at;
            }
        } catch (ConnectorHttpException e) {
            if (!refusal(e)) throw e;
            summariesRefused = true;
            partialReason = SUMMARIES_REFUSED;
        }
    }

    // A 4xx that is not a rate limit or an expired token: a scope, a plan or a setting says no.
    private static boolean refusal(ConnectorHttpException e) {
        int s = e.status();
        return s == 400 || s == 403 || s == 404 || s == 401;
    }

    private ItemMetadata item(Meeting m) {
        ItemMetadata item = new ItemMetadata();
        item.setId("meeting:" + m.uuid);
        item.setTitle(title(m.topic, m.start));
        item.setSourceType(ConnectorType.ZOOM);
        item.setLastModified(changed(m.transcriptAt, m.summaryAt, m.start));
        item.setContentType(ZoomApi.MARKDOWN);
        if (m.shareUrl != null) item.putExtra("url", m.shareUrl);
        String host = people.getOrDefault(m.hostId, m.hostEmail);
        if (host != null && !host.isBlank()) item.putExtra(ItemMetadata.AUTHOR, host);
        return item;
    }

    /**
     * When a meeting last changed, as far as the index is concerned: its newest part, plus a
     * millisecond for each part beyond the first - so gaining a part always moves it forward, even
     * when the new part is dated before the old one.
     */
    static Instant changed(Instant transcript, Instant summary, Instant start) {
        Instant newest = transcript == null ? summary : summary == null ? transcript
                : transcript.isAfter(summary) ? transcript : summary;
        if (newest == null) newest = start == null ? Instant.EPOCH : start;
        return transcript != null && summary != null ? newest.plusMillis(1) : newest;
    }

    static String title(String topic, Instant start) {
        String name = topic == null || topic.isBlank() ? "Zoom meeting" : topic.strip();
        return start == null ? name : name + " (" + LocalDate.ofInstant(start, ZoneOffset.UTC) + ")";
    }

    // The transcript a recording carries: its audio transcript, else its closed captions - finished ones only.
    private static JsonNode transcriptFile(JsonNode recording) {
        JsonNode captions = null;
        for (JsonNode f : recording.path("recording_files")) {
            String status = f.path("status").asText("completed");
            if (!"completed".equalsIgnoreCase(status) || f.path("download_url").asText("").isBlank()) continue;
            String type = f.path("file_type").asText("");
            if ("TRANSCRIPT".equalsIgnoreCase(type)) return f;
            if ("CC".equalsIgnoreCase(type) && captions == null) captions = f;
        }
        return captions;
    }

    // -- cursor --------------------------------------------------------------------------------

    private static String cursor(LocalDate through) {
        try {
            return JSON.writeValueAsString(Map.of("through", through.toString()));
        } catch (IOException e) {
            return null;
        }
    }

    static LocalDate cursorDay(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            return LocalDate.parse(JSON.readTree(cursor).path("through").asText(""));
        } catch (IOException | DateTimeParseException e) {
            return null;
        }
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        if (!id.startsWith("meeting:") || id.length() <= 8) throw new ConnectorException("That is not an item of a Zoom source.");
        String uuid = id.substring(8);
        String path = ZoomApi.uuidSegment(uuid);

        JsonNode recording = null;
        try {
            recording = api.getJson("meetings/" + path + "/recordings");
        } catch (ConnectorHttpException e) {
            if (!e.isNotFound()) throw e;
        }
        JsonNode summary = null;
        try {
            summary = api.getJson("meetings/" + path + "/meeting_summary");
        } catch (ConnectorHttpException e) {
            if (e.status() == 429 || e.status() >= 500 || (e.status() == 401 && recording == null)) throw e;
        }

        String transcript = "";
        JsonNode file = recording == null ? null : transcriptFile(recording);
        if (file != null) transcript = ZoomTranscript.render(download(file.path("download_url").asText()));
        String summaryText = summary == null ? "" : summaryMarkdown(summary);
        if (transcript.isBlank() && summaryText.isBlank()) {
            throw new ItemGoneException("Zoom no longer has a transcript or a summary of the meeting " + uuid + ".");
        }
        return ZoomApi.markdown(render(recording, summary, participants(path), summaryText, transcript));
    }

    String render(JsonNode recording, JsonNode summary, List<String> participants, String summaryText, String transcript) {
        JsonNode r = recording == null ? JSON.createObjectNode() : recording;
        JsonNode s = summary == null ? JSON.createObjectNode() : summary;
        String topic = r.path("topic").asText(s.path("meeting_topic").asText(""));
        Instant start = ZoomApi.instant(r.path("start_time").asText(s.path("meeting_start_time").asText(null)));
        StringBuilder md = new StringBuilder();
        md.append("# ").append(topic.isBlank() ? "Zoom meeting" : topic.strip()).append("\n\n");
        String hostId = r.path("host_id").asText(s.path("meeting_host_id").asText(""));
        String host = host(hostId, s.path("meeting_host_email").asText(""));
        if (!host.isBlank()) md.append("- Host: ").append(host).append('\n');
        if (start != null) md.append("- Started: ").append(start).append('\n');
        int duration = r.path("duration").asInt(0);
        if (duration > 0) md.append("- Duration: ").append(duration).append(duration == 1 ? " minute" : " minutes").append('\n');
        if (!participants.isEmpty()) md.append("- Participants: ").append(String.join(", ", participants)).append('\n');
        String meetingId = r.path("id").asText(s.path("meeting_id").asText(""));
        if (!meetingId.isBlank()) md.append("- Meeting ID: ").append(meetingId).append('\n');
        String share = r.path("share_url").asText("");
        if (!share.isBlank()) md.append("- Recording: ").append(share).append('\n');
        String doc = s.path("summary_doc_url").asText("");
        if (!doc.isBlank()) md.append("- Summary document: ").append(doc).append('\n');
        if (!summaryText.isBlank()) md.append("\n## Summary\n\n").append(summaryText.strip()).append('\n');
        if (!transcript.isBlank()) md.append("\n## Transcript\n\n").append(transcript.strip()).append('\n');
        return md.toString();
    }

    // The host by name: known from the listing, else asked of Zoom once, else the summary's address.
    private String host(String hostId, String fallback) {
        if (hostId == null || hostId.isBlank()) return fallback;
        if (!people.containsKey(hostId)) {
            try {
                remember(api.getJson("users/" + ApiClient.segment(hostId)));
            } catch (ConnectorHttpException e) {
                if (e.status() == 429 || e.status() >= 500) throw e;
                people.put(hostId, fallback);
            }
        }
        String name = people.get(hostId);
        return name == null || name.isBlank() ? fallback : name;
    }

    /**
     * An AI Companion summary as Markdown: {@code summary_content}, which Zoom now writes for every
     * summary, else the older overview, sections and next steps (an edited summary's where it was edited).
     */
    static String summaryMarkdown(JsonNode s) {
        String content = s.path("summary_content").asText("");
        if (!content.isBlank()) return content.strip();
        JsonNode edited = s.path("edited_summary");
        StringBuilder md = new StringBuilder();
        String overview = edited.path("summary_overview").asText(s.path("summary_overview").asText(""));
        if (!overview.isBlank()) md.append(overview.strip()).append("\n\n");
        String editedDetails = edited.path("summary_details").asText("");
        if (!editedDetails.isBlank()) {
            md.append(editedDetails.strip()).append("\n\n");
        } else {
            for (JsonNode d : s.path("summary_details")) {
                String label = d.path("label").asText("");
                String text = d.path("summary").asText("");
                if (text.isBlank()) continue;
                if (!label.isBlank()) md.append("### ").append(label.strip()).append("\n\n");
                md.append(text.strip()).append("\n\n");
            }
        }
        JsonNode steps = edited.path("next_steps").isArray() && !edited.path("next_steps").isEmpty()
                ? edited.path("next_steps") : s.path("next_steps");
        List<String> next = new ArrayList<>();
        for (JsonNode step : steps) {
            if (!step.asText("").isBlank()) next.add(step.asText().strip());
        }
        if (!next.isEmpty()) {
            md.append("### Next steps\n\n");
            for (String step : next) md.append("- ").append(step).append('\n');
        }
        return md.toString().strip();
    }

    // Who was in the meeting, by name, once each. Zoom keeps this for Pro plans and up, for 15
    // months; anything it will not say leaves the line out rather than failing the meeting.
    private List<String> participants(String path) {
        Set<String> names = new LinkedHashSet<>();
        try {
            Iterator<JsonNode> all = Paginator.cursor(api, "past_meetings/" + path + "/participants",
                    Map.of("page_size", PAGE_SIZE), "next_page_token", page -> page.path("participants"),
                    page -> page.path("next_page_token").asText(null));
            while (all.hasNext()) {
                JsonNode p = all.next();
                String name = p.path("name").asText("").strip();
                if (name.isEmpty()) name = p.path("user_email").asText("").strip();
                if (!name.isEmpty()) names.add(name);
            }
        } catch (ConnectorHttpException e) {
            if (e.status() == 429 || e.status() >= 500) throw e;
        }
        return new ArrayList<>(names);
    }

    // A transcript file's text. Asked for with the connection's token only on Zoom's own hosts (or the
    // API's, for a test server); the download redirects to storage, which is followed without it.
    private String download(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Zoom named a transcript address that is not a URL.");
        }
        if (!ZoomApi.mayCarryToken(uri, apiBase)) {
            throw new ConnectorException("Zoom named a transcript address outside Zoom (" + uri.getHost()
                    + "), so the connection's token was not sent there.");
        }
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        ApiClient files = context.http().client(origin).service("Zoom").auth(auth).rateLimit(ZoomApi.RATE_LIMIT).build();
        try (InputStream in = files.stream(url.strip(), Map.of(), Map.of("Accept", "*/*"))) {
            byte[] bytes = in.readNBytes(MAX_TRANSCRIPT_BYTES + 1);
            if (bytes.length > MAX_TRANSCRIPT_BYTES) throw new ConnectorException("The meeting's transcript is too large to read.");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConnectorException("Zoom stopped sending the meeting's transcript: " + e.getMessage(), e);
        }
    }
}
