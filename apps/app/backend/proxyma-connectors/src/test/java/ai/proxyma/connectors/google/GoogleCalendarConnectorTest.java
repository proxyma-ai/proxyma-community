package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConfigValidator;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.SourceScope;
import ai.proxyma.core.connector.testing.LoopbackApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Google Calendar against a loopback Calendar API: the window of each calendar with its sync
 * token, the changes since one and a token Google invalidated, an event rendered with who, when
 * and where, and what is gone - cancelled, deleted, moved out of the window.
 */
class GoogleCalendarConnectorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final String TEAM = "team@group.calendar.google.com";
    private static final String TEAM_PATH = "/calendar/v3/calendars/team%40group.calendar.google.com/events";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final CalendarConnectorPlugin plugin = new CalendarConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        Map<String, String> config = ConfigValidator.validate(CalendarConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(new SourceContext(null, ConnectorType.GOOGLE_CALENDAR, null, null, config, null, null,
                ResolvedCredentials.token(api.baseUrl(), "ya29.test"), SourceScope.defaults(),
                ConnectorHttp.direct(waits::add), Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String event(String id, String summary, String start, String end, String extra) {
        return "{'id':'" + id + "','status':'confirmed','summary':'" + summary + "','updated':'2026-10-10T08:00:00Z',"
                + "'htmlLink':'https://www.google.com/calendar/event?eid=" + id + "',"
                + "'organizer':{'email':'ana@example.com','displayName':'Ana Lee'},"
                + "'start':{" + start + "},'end':{" + end + "}" + (extra == null ? "" : "," + extra) + "}";
    }

    private static String at(String iso) {
        return "'dateTime':'" + iso + "'";
    }

    private void primaryInTwoPages() {
        api.on("GET", "/calendar/v3/calendars/primary/events").json("{'items':["
                + event("e1", "Weekly standup", at("2026-10-15T09:00:00+07:00"), at("2026-10-15T09:30:00+07:00"), null) + ","
                + "{'id':'e2','status':'cancelled'}],'nextPageToken':'p2'}");
        api.on("GET", "/calendar/v3/calendars/primary/events").query("pageToken", "p2").json("{'items':["
                + event("e3", "Company offsite", "'date':'2026-11-02'", "'date':'2026-11-04'", null) + "],"
                + "'nextSyncToken':'sync-1='}");
    }

    @Test
    @DisplayName("a full listing reads every page of the window, one item per event, and keeps the last page's sync token")
    void fullListing() {
        primaryInTwoPages();
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);

            assertThat(items).extracting(ItemMetadata::getId).containsExactly("event:primary/e1", "event:primary/e3");
            ItemMetadata e1 = items.get(0);
            assertThat(e1.getTitle()).isEqualTo("Weekly standup");
            assertThat(e1.getLastModified()).isEqualTo(Instant.parse("2026-10-10T08:00:00Z"));
            assertThat(e1.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana Lee <ana@example.com>");
            assertThat(e1.getExtra("url")).isEqualTo("https://www.google.com/calendar/event?eid=e1");
            assertThat(e1.getContentType()).isEqualTo("text/markdown");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("{\"primary\":\"sync-1=\"}");
        }
        LoopbackApi.Recorded first = api.requests("/calendar/v3/calendars/primary/events").get(0);
        assertThat(first.param("timeMin")).isEqualTo(NOW.minus(Duration.ofDays(365)).toString());
        assertThat(first.param("timeMax")).isEqualTo(NOW.plus(Duration.ofDays(180)).toString());
        assertThat(first.param("singleEvents")).isEqualTo("true");
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer ya29.test"));
    }

    @Test
    @DisplayName("each calendar has its own sync token in the cursor, and an address-like calendar ID is one path segment")
    void severalCalendars() {
        primaryInTwoPages();
        api.on("GET", TEAM_PATH).json("{'items':[" + event("t1", "Sprint review", at("2026-10-20T10:00:00Z"),
                at("2026-10-20T11:00:00Z"), null) + "],'nextSyncToken':'team-1'}");
        try (ConnectorSession s = session(Map.of("calendars", List.of("primary", TEAM)))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId)
                    .containsExactly("event:primary/e1", "event:primary/e3", "event:" + TEAM + "/t1");
            assertThat(listing.nextCursor()).isEqualTo("{\"primary\":\"sync-1=\",\"" + TEAM + "\":\"team-1\"}");
        }
    }

    @Test
    @DisplayName("a changes pass reads from each sync token without the window; cancelled and moved-out events are listed to be found gone")
    void changesPass() {
        api.on("GET", "/calendar/v3/calendars/primary/events").query("syncToken", "sync-1=").json("{'items':["
                + event("e1", "Weekly standup (moved)", at("2026-10-16T09:00:00+07:00"), at("2026-10-16T09:30:00+07:00"), null) + ","
                + "{'id':'e3','status':'cancelled','updated':'2026-10-13T08:00:00Z'},"
                + event("e9", "Far future", at("2028-01-01T09:00:00Z"), at("2028-01-01T10:00:00Z"), null)
                + "],'nextSyncToken':'sync-2'}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(NOW.minus(Duration.ofHours(1)), "{\"primary\":\"sync-1=\"}"));
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("event:primary/e1", "event:primary/e3", "event:primary/e9");
            assertThat(items.get(0).getContentType()).isEqualTo("text/markdown");
            assertThat(items.get(1).getContentType()).isNull();
            assertThat(items.get(1).getLastModified()).isEqualTo(Instant.parse("2026-10-13T08:00:00Z"));
            assertThat(items.get(2).getContentType()).isNull();
            assertThat(listing.nextCursor()).isEqualTo("{\"primary\":\"sync-2\"}");
        }
        LoopbackApi.Recorded asked = api.requests("/calendar/v3/calendars/primary/events").get(0);
        assertThat(asked.param("timeMin")).isNull();
        assertThat(asked.param("singleEvents")).isEqualTo("true");
    }

    @Test
    @DisplayName("a sync token Google invalidated (410) lists that calendar in full, with a fresh token")
    void invalidatedToken() {
        api.on("GET", "/calendar/v3/calendars/primary/events").query("syncToken", "old")
                .status(410).json("{'error':{'code':410,'message':'Sync token is no longer valid, a full sync is required.'}}");
        primaryInTwoPages();
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(NOW.minus(Duration.ofHours(1)), "{\"primary\":\"old\"}"));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("event:primary/e1", "event:primary/e3");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("{\"primary\":\"sync-1=\"}");
        }
    }

    @Test
    @DisplayName("an event is fetched as Markdown with its time, people, place, meeting link and description")
    void fetchEvent() throws IOException {
        api.on("GET", "/calendar/v3/calendars/primary/events/e1").json(event("e1", "Weekly standup",
                at("2026-10-15T09:00:00+07:00") + ",'timeZone':'Asia/Ho_Chi_Minh'", at("2026-10-15T09:30:00+07:00"),
                "'location':'Room MARIGOLD','hangoutLink':'https://meet.google.com/abc-defg-hij',"
                        + "'recurringEventId':'e1base','created':'2026-01-05T08:00:00Z',"
                        + "'attendees':[{'email':'bo@example.com','displayName':'Bo Tran','responseStatus':'accepted'},"
                        + "{'email':'carol@example.com','responseStatus':'needsAction','optional':true}],"
                        + "'description':'<p>Agenda:</p><ul><li>Blockers</li><li>Release &amp; demo</li></ul>',"
                        + "'attachments':[{'title':'Notes','fileUrl':'https://drive.google.com/x'}]"));
        api.on("GET", "/calendar/v3/calendars/primary/events/e3").json(event("e3", "Company offsite",
                "'date':'2026-11-02'", "'date':'2026-11-04'", null));

        String md = text(fetch("event:primary/e1"));
        assertThat(md).startsWith("# Weekly standup\n")
                .contains("- Calendar: primary", "- When: 2026-10-15T09:00:00+07:00 to 2026-10-15T09:30:00+07:00",
                        "- Time zone: Asia/Ho_Chi_Minh", "- Organizer: Ana Lee <ana@example.com>",
                        "- Attendees: Bo Tran <bo@example.com> (accepted), carol@example.com (no answer yet, optional)",
                        "- Location: Room MARIGOLD", "- Meeting link: https://meet.google.com/abc-defg-hij",
                        "- Repeats: yes", "- Link: https://www.google.com/calendar/event?eid=e1",
                        "## Description", "Agenda:\nBlockers\nRelease & demo", "Attachments: Notes");
        // An all-day event's end date is the day after it ends.
        assertThat(text(fetch("event:primary/e3"))).contains("- When: 2026-11-02 to 2026-11-03, all day");
    }

    @Test
    @DisplayName("an event deleted, cancelled or moved out of the window is gone at its fetch")
    void goneEvents() {
        api.on("GET", "/calendar/v3/calendars/primary/events/d1").status(404).json("{'error':{'code':404}}");
        api.on("GET", "/calendar/v3/calendars/primary/events/d2").status(410).json("{'error':{'code':410}}");
        api.on("GET", "/calendar/v3/calendars/primary/events/c1").json("{'id':'c1','status':'cancelled'}");
        api.on("GET", "/calendar/v3/calendars/primary/events/old1").json(event("old1", "Long ago",
                at("2024-01-01T09:00:00Z"), at("2024-01-01T10:00:00Z"), null));
        for (String id : List.of("d1", "d2", "c1", "old1")) {
            assertThatThrownBy(() -> fetch("event:primary/" + id)).as(id).isInstanceOf(ItemGoneException.class);
        }
        assertThatThrownBy(() -> fetch("event:primary")).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of a Google calendar.");
    }

    @Test
    @DisplayName("registration probes each calendar, and a missing one or a refused sign-in says what to do")
    void accessProbe() {
        api.on("GET", "/calendar/v3/calendars/primary").json("{'id':'ana@example.com'}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of("calendars", List.of("primary", "nope@group.calendar.google.com")))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Google Calendar has no calendar nope@group.calendar.google.com, "
                    + "or the connection's account cannot see it.");
        }
        api.reset();
        api.on("GET", "/calendar/v3/calendars/primary").status(401).json("{'error':{'code':401}}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).startsWith("Google did not accept the connection's sign-in.");
        }
    }

    @Test
    @DisplayName("Calendar's rate limit is waited out when it is short, and reported when it is not")
    void rateLimits() {
        api.on("GET", "/calendar/v3/calendars/primary/events").status(429).header("Retry-After", "2").then()
                .json("{'items':[],'nextSyncToken':'s'}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(2));
        api.reset();
        api.on("GET", "/calendar/v3/calendars/primary/events").status(429).header("Retry-After", "3600");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage())
                    .isEqualTo("Google Calendar is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("the descriptor: the primary calendar, a year back and half a year ahead by default; identity is the calendars")
    void descriptor() {
        Map<String, String> defaults = ConfigValidator.validate(CalendarConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(defaults).containsEntry("calendars", "primary").containsEntry("daysBack", "365").containsEntry("daysAhead", "180");
        assertThat(plugin.identity(defaults)).isEqualTo("primary");
        assertThat(plugin.displayName(defaults)).isEqualTo("Google Calendar: primary");
        Map<String, String> two = ConfigValidator.validate(CalendarConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("calendars", "Team@Group.Calendar.Google.com\nprimary"));
        assertThat(plugin.identity(two)).isEqualTo("primary,team@group.calendar.google.com");
        assertThat(CalendarConnectorPlugin.DESCRIPTOR.capabilities().shareable()).isFalse();
        assertThat(CalendarConnectorPlugin.DESCRIPTOR.protocol()).isEqualTo(GoogleConnectionPlugin.DESCRIPTOR.protocol());
    }

    private InputStream fetch(String id) {
        ItemMetadata item = new ItemMetadata();
        item.setId(id);
        try (ConnectorSession s = session(Map.of())) {
            return s.fetch(item);
        }
    }

    private static String text(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
