package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.Capabilities;
import ai.proxyma.core.connector.spi.Category;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.SourceContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Google Calendar as a source: the events of chosen calendars (the account's own by default) in a
 * window around today - a year back and half a year ahead unless the source says otherwise - one
 * item per event, with its time, attendees, place, description and meeting link. A recurring
 * event is one item per occurrence in the window.
 *
 * <p><b>Identity</b> is the set of calendars, without case.
 *
 * <p><b>Incremental</b>: each calendar's sync token, kept as the cursor. A token Google has
 * invalidated (410 Gone) lists that calendar in full.
 *
 * <p><b>Deletion</b>: a cancelled event in the feed, or one moved out of the window, is listed as
 * changed and found gone at its fetch; a complete full listing removes what it did not list.
 *
 * <p>Never shareable: a calendar is read as the connection's account.
 */
@Component
public class CalendarConnectorPlugin implements ConnectorPlugin {

    static final String CALENDARS = "calendars";
    static final String DAYS_BACK = "daysBack";
    static final String DAYS_AHEAD = "daysAhead";
    static final int DEFAULT_DAYS_BACK = 365;
    static final int DEFAULT_DAYS_AHEAD = 180;

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.GOOGLE_CALENDAR)
            .protocol(ConnectionProtocol.GOOGLE)
            .displayName("Google Calendar")
            .description("Events in Google calendars - who, when, where and what - from a year back to half a year ahead.")
            .noun("Google calendar", "Google calendars")
            .addTitle("Add a Google calendar")
            .order(44)
            .category(Category.CALENDAR)
            .mark("googlecalendar")
            .fields(
                    FieldSpec.list(CALENDARS, "Calendars").defaultValue("primary").placeholder("primary")
                            .help("Each calendar to read, by its ID: primary for your own, or the calendar ID from "
                                    + "its settings in Google Calendar."),
                    FieldSpec.number(DAYS_BACK, "Days back").defaultValue(String.valueOf(DEFAULT_DAYS_BACK)).range(0, 3650)
                            .help("Events that ended up to this many days ago."),
                    FieldSpec.number(DAYS_AHEAD, "Days ahead").defaultValue(String.valueOf(DEFAULT_DAYS_AHEAD)).range(0, 3650)
                            .help("Events that start up to this many days from now."))
            .capabilities(Capabilities.builder().incremental().cursor().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("google-calendar")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new CalendarSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String calendar : calendars(config)) sorted.add(calendar.toLowerCase(Locale.ROOT));
        return String.join(",", sorted);
    }

    @Override
    public String displayName(Map<String, String> config) {
        Set<String> calendars = calendars(config);
        return calendars.isEmpty() ? "" : "Google Calendar: " + String.join(", ", calendars);
    }

    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        return "https://calendar.google.com/calendar/r";
    }

    /** The calendars the source names, as typed, in order, once each. */
    static Set<String> calendars(Map<String, String> config) {
        Set<String> out = new LinkedHashSet<>();
        String raw = config.get(CALENDARS);
        if (raw == null) return out;
        Set<String> seen = new java.util.HashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            String calendar = line.strip();
            if (!calendar.isEmpty() && seen.add(calendar.toLowerCase(Locale.ROOT))) out.add(calendar);
        }
        return out;
    }

    static int number(Map<String, String> config, String key, int fallback) {
        try {
            int n = Integer.parseInt(config.getOrDefault(key, "").strip());
            return n >= 0 ? n : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
