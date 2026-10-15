package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
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

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Google Calendar source, opened for one pass or one batch of fetches.
 *
 * <p>Items are {@code event:<calendarId>/<eventId>}; a recurring event's occurrences are items of
 * their own ({@code singleEvents}), each with the occurrence's own id. An event is in the source
 * while it is not cancelled and overlaps the window - from {@code daysBack} before now to
 * {@code daysAhead} after it - and the fetch asks that again.
 *
 * <p>A full pass lists each calendar's window; its last page carries the sync token the next
 * CHANGES pass starts from. Google does not take the window with a sync token, so a CHANGES pass
 * reads every change to the calendar and keeps those inside the window - an event moved out of it
 * is listed as changed and found gone.
 */
final class CalendarSession implements ConnectorSession {

    private final SourceContext context;
    private final ApiClient api;
    private final Set<String> calendars;
    private final int daysBack;
    private final int daysAhead;
    // The sync token each calendar resumes from next - handed back as the cursor once listed.
    private final Map<String, String> tokens = new LinkedHashMap<>();

    CalendarSession(SourceContext context) {
        this.context = context;
        Map<String, String> config = context.config();
        this.calendars = CalendarConnectorPlugin.calendars(config);
        this.daysBack = CalendarConnectorPlugin.number(config, CalendarConnectorPlugin.DAYS_BACK,
                CalendarConnectorPlugin.DEFAULT_DAYS_BACK);
        this.daysAhead = CalendarConnectorPlugin.number(config, CalendarConnectorPlugin.DAYS_AHEAD,
                CalendarConnectorPlugin.DEFAULT_DAYS_AHEAD);
        this.api = GoogleApi.client(context.http(), context.connection(), "Google Calendar");
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        if (calendars.isEmpty()) return AccessCheck.failed("Choose at least one calendar to read.");
        String asking = null;
        try {
            for (String calendar : calendars) {
                asking = calendar;
                api.getJson("calendar/v3/calendars/" + ApiClient.segment(calendar));
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) {
                return AccessCheck.failed("Google Calendar has no calendar " + asking + ", or the connection's account cannot see it.");
            }
            return AccessCheck.failed(GoogleApi.refusal(e, "Google Calendar API"));
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        Map<String, String> stored = request.isFull() ? Map.of() : GoogleApi.readCursor(request.cursor());
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        for (String calendar : calendars) {
            String token = stored.get(calendar);
            if (token == null || token.isBlank()) {
                parts.add(() -> full(calendar));
            } else {
                Feed feed = new Feed(calendar, token);
                parts.add(() -> Listings.mapped(feed.events(), e -> changedItem(calendar, e)));
                // A sync token Google has invalidated: this calendar is listed in full instead.
                parts.add(() -> feed.expired ? full(calendar) : Collections.emptyIterator());
            }
        }
        return Listings.withCursor(Listings.concat(parts), () -> GoogleApi.writeCursor(tokens));
    }

    // Every event of one calendar inside the window; the last page's sync token is the cursor.
    private Iterator<ItemMetadata> full(String calendar) {
        tokens.remove(calendar);
        Iterator<JsonNode> events = Paginator.iterate(next -> {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("timeMin", windowStart().toString());
            query.put("timeMax", windowEnd().toString());
            query.put("singleEvents", "true");
            query.put("showDeleted", "false");
            query.put("maxResults", 2500);
            if (next != null) query.put("pageToken", next);
            JsonNode page = api.getJson(eventsPath(calendar), query);
            String sync = page.path("nextSyncToken").asText("");
            if (!sync.isBlank()) tokens.put(calendar, sync);
            return new Paginator.Page<>(Paginator.elements(page.path("items")), page.path("nextPageToken").asText(null));
        });
        return Listings.mapped(events, e -> "cancelled".equals(e.path("status").asText()) ? null : eventItem(calendar, e));
    }

    /** One calendar's changes since a stored sync token - and whether Google refused that token. */
    private final class Feed {
        private final String calendar;
        private final String token;
        boolean expired;

        Feed(String calendar, String token) {
            this.calendar = calendar;
            this.token = token;
        }

        Iterator<JsonNode> events() {
            return Paginator.iterate(next -> {
                Map<String, Object> query = new LinkedHashMap<>();
                query.put("syncToken", token);
                query.put("singleEvents", "true");
                query.put("maxResults", 2500);
                if (next != null) query.put("pageToken", next);
                JsonNode page;
                try {
                    page = api.getJson(eventsPath(calendar), query);
                } catch (ConnectorHttpException e) {
                    // 410: the token was invalidated (it expired, or the calendar's sharing changed).
                    if (next == null && (e.status() == 410 || e.status() == 400)) {
                        expired = true;
                        return new Paginator.Page<>(List.of(), null);
                    }
                    throw e;
                }
                String sync = page.path("nextSyncToken").asText("");
                if (!sync.isBlank()) tokens.put(calendar, sync);
                return new Paginator.Page<>(Paginator.elements(page.path("items")), page.path("nextPageToken").asText(null));
            });
        }
    }

    // A changed event: in the window and not cancelled, an item; otherwise listed as changed, so
    // its fetch finds it gone and it leaves the index.
    private ItemMetadata changedItem(String calendar, JsonNode event) {
        String id = event.path("id").asText("");
        if (id.isEmpty()) return null;
        if (!"cancelled".equals(event.path("status").asText()) && inWindow(event)) return eventItem(calendar, event);
        ItemMetadata gone = new ItemMetadata();
        gone.setId("event:" + calendar + "/" + id);
        gone.setTitle(event.path("summary").asText(id));
        gone.setSourceType(ConnectorType.GOOGLE_CALENDAR);
        Instant updated = GoogleApi.instant(event.path("updated").asText(null));
        gone.setLastModified(updated != null ? updated : context.clock().instant());
        return gone;
    }

    private ItemMetadata eventItem(String calendar, JsonNode event) {
        String id = event.path("id").asText("");
        if (id.isEmpty()) return null;
        ItemMetadata item = new ItemMetadata();
        item.setId("event:" + calendar + "/" + id);
        item.setTitle(title(event));
        item.setSourceType(ConnectorType.GOOGLE_CALENDAR);
        item.setLastModified(GoogleApi.instant(event.path("updated").asText(null)));
        item.setContentType(GoogleApi.MARKDOWN);
        GoogleApi.putExtra(item, "url", event.path("htmlLink").asText(null));
        GoogleApi.putExtra(item, ItemMetadata.AUTHOR, person(event.path("organizer")));
        return item;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        int slash = id.lastIndexOf('/');
        if (!id.startsWith("event:") || slash <= "event:".length() || slash == id.length() - 1) {
            throw new ConnectorException("That is not an item of a Google calendar.");
        }
        String calendar = id.substring("event:".length(), slash);
        String eventId = id.substring(slash + 1);
        JsonNode event;
        try {
            event = api.getJson(eventsPath(calendar) + "/" + ApiClient.segment(eventId));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Google Calendar no longer has the event " + eventId + ".");
            throw e;
        }
        if ("cancelled".equals(event.path("status").asText())) {
            throw new ItemGoneException("The event " + eventId + " was cancelled.");
        }
        if (!inWindow(event)) throw new ItemGoneException("The event " + eventId + " is no longer inside this source's dates.");
        return GoogleApi.markdown(render(calendar, event));
    }

    String render(String calendar, JsonNode event) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title(event)).append("\n\n");
        md.append("- Calendar: ").append(calendar).append('\n');
        md.append("- When: ").append(when(event)).append('\n');
        String zone = event.path("start").path("timeZone").asText("");
        if (!zone.isBlank()) md.append("- Time zone: ").append(zone).append('\n');
        String organizer = person(event.path("organizer"));
        if (organizer != null) md.append("- Organizer: ").append(organizer).append('\n');
        List<String> attendees = new ArrayList<>();
        for (JsonNode a : event.path("attendees")) {
            String who = person(a);
            if (who == null) continue;
            String answer = switch (a.path("responseStatus").asText("")) {
                case "accepted" -> "accepted";
                case "declined" -> "declined";
                case "tentative" -> "maybe";
                default -> "no answer yet";
            };
            attendees.add(who + " (" + answer + (a.path("optional").asBoolean(false) ? ", optional" : "") + ")");
        }
        if (!attendees.isEmpty()) md.append("- Attendees: ").append(String.join(", ", attendees)).append('\n');
        String location = GoogleApi.text(event, "location");
        if (!location.isEmpty()) md.append("- Location: ").append(location).append('\n');
        String meeting = meetingLink(event);
        if (meeting != null) md.append("- Meeting link: ").append(meeting).append('\n');
        if (event.hasNonNull("recurringEventId")) md.append("- Repeats: yes, this is one occurrence\n");
        md.append("- Status: ").append(event.path("status").asText("confirmed")).append('\n');
        if (event.hasNonNull("created")) md.append("- Created: ").append(event.path("created").asText()).append('\n');
        if (event.hasNonNull("updated")) md.append("- Updated: ").append(event.path("updated").asText()).append('\n');
        if (event.hasNonNull("htmlLink")) md.append("- Link: ").append(event.path("htmlLink").asText()).append('\n');
        String description = GoogleApi.plain(event.path("description").asText(""));
        md.append("\n## Description\n\n").append(description.isBlank() ? "(no description)" : description).append('\n');
        List<String> files = new ArrayList<>();
        for (JsonNode f : event.path("attachments")) {
            String name = f.path("title").asText("");
            if (!name.isBlank()) files.add(name);
        }
        if (!files.isEmpty()) md.append("\nAttachments: ").append(String.join(", ", files)).append('\n');
        return md.toString();
    }

    private static String title(JsonNode event) {
        String summary = GoogleApi.text(event, "summary");
        return summary.isEmpty() ? "(no title)" : summary;
    }

    // "2026-10-14T09:00:00+07:00 to 2026-10-14T10:00:00+07:00", or "2026-10-14, all day".
    private static String when(JsonNode event) {
        JsonNode start = event.path("start");
        JsonNode end = event.path("end");
        if (start.hasNonNull("date")) {
            String first = start.path("date").asText();
            String last = end.path("date").asText("");
            // An all-day event's end date is the day AFTER it ends.
            try {
                if (!last.isEmpty()) last = LocalDate.parse(last).minusDays(1).toString();
            } catch (RuntimeException ignored) {
                // left as Google wrote it
            }
            return last.isEmpty() || last.equals(first) ? first + ", all day" : first + " to " + last + ", all day";
        }
        String from = start.path("dateTime").asText("");
        String to = end.path("dateTime").asText("");
        return to.isEmpty() ? from : from + " to " + to;
    }

    private static String meetingLink(JsonNode event) {
        String hangout = event.path("hangoutLink").asText("");
        if (!hangout.isBlank()) return hangout;
        for (JsonNode entry : event.path("conferenceData").path("entryPoints")) {
            if ("video".equals(entry.path("entryPointType").asText())) {
                String uri = entry.path("uri").asText("");
                if (!uri.isBlank()) return uri;
            }
        }
        return null;
    }

    // "Ana Lee <ana@example.com>", the address alone, or null.
    private static String person(JsonNode who) {
        String name = GoogleApi.text(who, "displayName");
        String email = GoogleApi.text(who, "email");
        if (name.isEmpty() && email.isEmpty()) return null;
        if (name.isEmpty()) return email;
        return email.isEmpty() ? name : name + " <" + email + ">";
    }

    // -- the window ----------------------------------------------------------------------------

    private Instant windowStart() {
        return context.clock().instant().minus(Duration.ofDays(daysBack));
    }

    private Instant windowEnd() {
        return context.clock().instant().plus(Duration.ofDays(daysAhead));
    }

    private boolean inWindow(JsonNode event) {
        Instant start = boundary(event.path("start"));
        Instant end = boundary(event.path("end"));
        if (start == null) return false;
        if (end == null) end = start;
        return end.isAfter(windowStart()) && start.isBefore(windowEnd());
    }

    private static Instant boundary(JsonNode point) {
        if (point.hasNonNull("dateTime")) return GoogleApi.instant(point.path("dateTime").asText());
        if (point.hasNonNull("date")) {
            try {
                return LocalDate.parse(point.path("date").asText()).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static String eventsPath(String calendar) {
        return "calendar/v3/calendars/" + ApiClient.segment(calendar) + "/events";
    }
}
