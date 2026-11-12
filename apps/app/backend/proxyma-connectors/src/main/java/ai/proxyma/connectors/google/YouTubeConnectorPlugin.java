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
import ai.proxyma.core.connector.spi.SourceLabel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * A YouTube channel as a source: the signed-in channel's newest videos (title, description, when,
 * link) and the newest comment threads on them, one item each - a thread being the comment, its
 * replies and whether the channel has answered. What the agent answers comments from is the owner's
 * own sources; this is what it answers, and what wakes a Constellations SOURCE trigger when someone
 * writes.
 *
 * <p><b>Bounded.</b> A pass reads at most {@code maxVideos} videos (200) and {@code maxThreads} comment
 * threads (500), newest first, and says so: a pass that reached a bound is PARTIAL, so what it did not
 * reach this time is neither purged nor re-read - an older thread stays as it was last read, and one
 * deleted on YouTube leaves when it is next fetched. A video is read again only when the text it indexes
 * as - its description included - has changed. Every request is one quota unit: a pass is one for the
 * channel, two per 50 videos (the uploads playlist's page and one {@code videos.list} for its videos)
 * and one per 100 threads - 1 + 8 + 5 = 14 by default, one or two more when a bound is reached - plus
 * one for each new or changed video or thread fetched (two for a thread with more than five replies):
 * far inside YouTube's 10,000 a day at a sync every 15 minutes.
 *
 * <p><b>Identity</b>: the channel - one per connection, the one it signs in as - so the identity is the
 * same word for every source of the type, and the connection tells two channels apart. Its name is
 * the channel's title, and its link the channel's page, once a session has read the channel.
 *
 * <p><b>Never shareable</b>: the listing is read as the channel's owner, who sees private and unlisted
 * videos and their descriptions; a copy shared with other people would show them what YouTube does not.
 */
@Component
public class YouTubeConnectorPlugin implements ConnectorPlugin {

    static final String MAX_VIDEOS = "maxVideos";
    static final String MAX_THREADS = "maxThreads";
    static final int DEFAULT_MAX_VIDEOS = 200;
    static final int DEFAULT_MAX_THREADS = 500;
    static final String IDENTITY = "channel";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.YOUTUBE_CHANNEL)
            .protocol(ConnectionProtocol.YOUTUBE)
            .displayName("YouTube channel")
            .description("Your newest videos and their comments, and whether you answered.")
            .noun("YouTube channel", "YouTube channels")
            .addTitle("Add your YouTube channel")
            .order(45)
            .category(Category.CHAT)
            .mark("youtube")
            .fields(
                    FieldSpec.number(MAX_VIDEOS, "Newest videos").defaultValue(String.valueOf(DEFAULT_MAX_VIDEOS)).range(0, 1000)
                            .help("How many of the channel's newest videos are read each time."),
                    FieldSpec.number(MAX_THREADS, "Newest comment threads").defaultValue(String.valueOf(DEFAULT_MAX_THREADS))
                            .range(0, 5000)
                            .help("How many of the newest comment threads to read."))
            .capabilities(Capabilities.builder().liveRead().build())
            // Every six hours (2026-11-11, was 15 minutes): a pass re-reads the newest videos and threads whole, about 14
            // quota units, and 96 a day were most of what a channel spent of the built-in sign-in's daily share. New
            // comments reach the YouTube team through its own comment queue, which reads only what is new; this index
            // is what the team searches - descriptions and the owner's past replies - and is fine hours old.
            .syncEvery(Duration.ofHours(6))
            .docsAnchor("youtube")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new YouTubeSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        return IDENTITY;
    }

    /**
     * Told apart by its identity and connection alone, so its NAME is a label: the session names the
     * source after the channel's own title ({@link YouTubeSession#label()}) and renames it when the
     * channel is renamed, and adding the channel again still finds it under its new name.
     */
    @Override
    public boolean identifiedByConfig() {
        return true;
    }

    /** Until the channel has been read: the session's label then gives it the channel's title. */
    @Override
    public String displayName(Map<String, String> config) {
        return "YouTube channel";
    }

    /** The channel's own page once a session has read it ({@link SourceLabel#WEB_URL}), else YouTube Studio. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String channel = config.get(SourceLabel.WEB_URL);
        return channel != null && channel.startsWith("https://www.youtube.com/") ? channel : "https://studio.youtube.com/";
    }

    static int bound(Map<String, String> config, String key, int fallback, int max) {
        try {
            int n = Integer.parseInt(config.getOrDefault(key, "").strip());
            return n < 0 ? fallback : Math.min(n, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
