package ai.proxyma.connectors.slack;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Slack channels as a source: the messages of the channels chosen - public ones, and private ones
 * the app was invited to - or of every public channel the app is in. One item per thread (its first
 * message and every reply) and one per channel per day for the messages that started no thread,
 * rendered as markdown with people's names, times and links back to Slack.
 *
 * <p><b>Never a direct message.</b> Only channels are listed (users.conversations with the public and
 * private channel types), a channel ID naming a DM or a group DM is never read, and the manifest
 * asks for no {@code im:} or {@code mpim:} scope.
 *
 * <p><b>Identity</b> is the channel selection: the channels, lower case and sorted, or {@code *} for
 * every public channel. Leaving channels out and the history window can be changed later; the
 * channels themselves cannot, since that is another source.
 *
 * <p><b>Incremental</b>: each channel's history from a week before the last complete pass, listing
 * only the threads and days with a message, a reply or an edit since then - a reply does not
 * appear in a channel's history, so a thread is found by its first message's {@code latest_reply}.
 * A full pass, at least daily, catches a reply to an older thread.
 *
 * <p><b>Deletion</b>: only a complete full listing removes anything - a thread or a day with no
 * messages left is absent from it. A chosen channel the app can no longer read makes the listing
 * partial, so nothing is removed for what it did not reach.
 *
 * <p>Owner-only: a private channel's messages were read with an access the owner's colleagues did
 * not pass, and per-channel membership is not synced, so the source is not shareable.
 */
@Component
public class SlackConnectorPlugin implements ConnectorPlugin {

    static final String CHANNELS = "channels";
    static final String EXCLUDE = "exclude";
    static final String DAYS = "days";

    // A channel's ID as Slack writes it: C for a public channel, G for an older private one.
    static final Pattern CHANNEL_ID = Pattern.compile("[CG][A-Z0-9]{6,}");

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.SLACK)
            .protocol(ConnectionProtocol.SLACK)
            .displayName("Slack")
            .description("The messages of Slack channels, one document per thread and one per channel per day, "
                    + "read through your own Slack app. Never direct messages.")
            .noun("Slack channels", "Slack channels")
            .addTitle("Add Slack channels")
            .order(58)
            .category(Category.CHAT)
            .mark("slack")
            .fields(
                    FieldSpec.list(CHANNELS, "Channels").placeholder("#general")
                            .help("One per line, as #name or a channel ID. The app must be in each one. Leave "
                                    + "empty for every public channel the app is in."),
                    FieldSpec.number(DAYS, "Days of history").placeholder("365").range(1, 36500)
                            .help("Messages older than this are left out, and leave the index as they age. Leave "
                                    + "empty for the whole history."),
                    FieldSpec.list(EXCLUDE, "Channels to leave out").placeholder("#random").asAdvanced()
                            .help("One per line. Useful with every public channel."))
            .capabilities(Capabilities.builder().incremental().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("slack")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new SlackSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        List<String> channels = channels(config.get(CHANNELS));
        return channels.isEmpty() ? "*" : String.join(",", channels).toLowerCase(Locale.ROOT);
    }

    /** "#general, #release" - or, with no channel named, every public channel. */
    @Override
    public String displayName(Map<String, String> config) {
        List<String> channels = channels(config.get(CHANNELS));
        if (channels.isEmpty()) return "All public channels";
        List<String> shown = new ArrayList<>();
        for (String c : channels) shown.add(CHANNEL_ID.matcher(c).matches() ? c : "#" + c);
        return String.join(", ", shown);
    }

    /**
     * The channels a LIST value names, normalized and sorted: a name lower case without its {@code #},
     * an ID as Slack writes it. Sorted, so the same channels typed in another order are one source.
     */
    static List<String> channels(String value) {
        TreeSet<String> out = new TreeSet<>();
        if (value == null) return List.of();
        for (String line : value.split("[\\r\\n,]+")) {
            String entry = normalize(line);
            if (!entry.isEmpty()) out.add(entry);
        }
        return List.copyOf(out);
    }

    static String normalize(String entry) {
        String e = entry == null ? "" : entry.strip();
        while (e.startsWith("#")) e = e.substring(1).strip();
        // Slack's channel names are lower case, so an upper-case token of an ID's shape is an ID.
        return CHANNEL_ID.matcher(e).matches() ? e : e.toLowerCase(Locale.ROOT);
    }
}
