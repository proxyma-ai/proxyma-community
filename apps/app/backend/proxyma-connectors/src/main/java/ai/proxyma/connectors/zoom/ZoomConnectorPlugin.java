package ai.proxyma.connectors.zoom;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.Capabilities;
import ai.proxyma.core.connector.spi.Category;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.SourceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Zoom meetings as a source: every meeting in a window before today that has a cloud-recording
 * transcript or an AI Companion summary, one item per meeting OCCURRENCE (a recurring meeting's
 * every instance is its own), rendered as Markdown with its topic, host, start, duration,
 * participants, summary and transcript - the transcript as "[00:01:02] Ana Lee: ..." lines.
 *
 * <p><b>Whose meetings</b>: the signed-in person's by default; a Server-to-Server app reads the
 * people named by email or user ID, or everyone on the account ({@code *}).
 *
 * <p><b>Identity</b> is the set of people, without case: "ana@acme.com" and "ANA@acme.com" are one
 * source, and an empty list is the person signed in.
 *
 * <p><b>Incremental</b> by date window: Zoom lists recordings a month at a time, by the meeting's
 * date. A CHANGES pass reads the days since the last complete pass, three more before them for
 * transcripts and summaries Zoom finishes late; the cursor is the last day a complete pass read.
 * <b>Deletion</b>: a full pass (daily) lists every month of the window, and a meeting it no longer
 * finds - its recording deleted or in the trash, its summary deleted, or aged past the window - is
 * removed.
 *
 * <p>Never shareable: meetings are what the people in them said.
 */
@Component
public class ZoomConnectorPlugin implements ConnectorPlugin {

    static final String USERS = "users";
    static final String DAYS = "days";
    static final int DEFAULT_DAYS = 365;
    static final String EVERYONE = "*";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.ZOOM)
            .protocol(ConnectionProtocol.ZOOM)
            .displayName("Zoom")
            .description("Transcripts of Zoom cloud recordings and AI Companion meeting summaries.")
            .noun("Zoom meeting list", "Zoom meeting lists")
            .addTitle("Add Zoom meetings")
            .order(80)
            .category(Category.MEETINGS)
            .mark("zoom")
            .fields(
                    FieldSpec.list(USERS, "People").placeholder("ana@example.com")
                            .help("Whose meetings to read, by email address. Leave it empty for your own. With a "
                                    + "Server-to-Server app, name the people, or * for everyone on the account."),
                    FieldSpec.number(DAYS, "Days of meetings").defaultValue(String.valueOf(DEFAULT_DAYS)).range(1, 3650)
                            .help("Only meetings from this many days before today."))
            .capabilities(Capabilities.builder().incremental().cursor().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("zoom")
            .build();

    private final String tokenUrl;

    /** Zoom's own token address, for a connector's tests. */
    public ZoomConnectorPlugin() {
        this(ZoomApi.TOKEN_URL);
    }

    @Autowired
    public ZoomConnectorPlugin(@Value("${proxyma.connectors.zoom.token-url:" + ZoomApi.TOKEN_URL + "}") String tokenUrl) {
        this.tokenUrl = tokenUrl == null || tokenUrl.isBlank() ? ZoomApi.TOKEN_URL : tokenUrl.strip();
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new ZoomSession(context, tokenUrl);
    }

    @Override
    public String identity(Map<String, String> config) {
        List<String> users = users(config);
        if (users.isEmpty()) return "me";
        TreeSet<String> sorted = new TreeSet<>();
        for (String u : users) sorted.add(u.toLowerCase(Locale.ROOT));
        return String.join(",", sorted);
    }

    @Override
    public String displayName(Map<String, String> config) {
        List<String> users = users(config);
        if (users.isEmpty()) return "Zoom: my meetings";
        if (users.contains(EVERYONE)) return "Zoom: everyone";
        return "Zoom: " + String.join(", ", users);
    }

    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        return "https://zoom.us/recording";
    }

    /** The people the source names, as typed, in order, once each; {@code *} alone when it names everyone. */
    static List<String> users(Map<String, String> config) {
        List<String> out = new ArrayList<>();
        String raw = config.get(USERS);
        if (raw == null) return out;
        Set<String> seen = new HashSet<>();
        for (String line : raw.split("[\\r\\n,;]+")) {
            String user = line.strip();
            if (user.isEmpty()) continue;
            if (user.equals(EVERYONE)) return List.of(EVERYONE);
            if (seen.add(user.toLowerCase(Locale.ROOT))) out.add(user);
        }
        return out;
    }

    static int days(Map<String, String> config) {
        try {
            int days = Integer.parseInt(config.getOrDefault(DAYS, "").strip());
            return days > 0 ? Math.min(days, 3650) : DEFAULT_DAYS;
        } catch (NumberFormatException e) {
            return DEFAULT_DAYS;
        }
    }
}
