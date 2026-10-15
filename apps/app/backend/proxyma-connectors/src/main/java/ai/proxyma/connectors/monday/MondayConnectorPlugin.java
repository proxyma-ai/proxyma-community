package ai.proxyma.connectors.monday;

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
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * monday.com items as a source - of the boards chosen by ID or link, or of every board the
 * connection can see: each item with its group, its columns as label and value, its updates
 * (comments) and replies, and its subitems. Built on the connector framework v2 after the GitHub
 * connector, over monday.com's GraphQL API.
 *
 * <p><b>Identity</b> is the set of board IDs, sorted by number, or {@code all}.
 *
 * <p><b>Not incremental.</b> monday.com cannot be asked for the items updated after a given time -
 * the last-updated filter of {@code items_page} takes only relative dates (today, this week) - so
 * every pass is a slim complete listing: each board's items with their {@code updated_at} and the
 * time of their newest update, a page of a hundred per request. The sync engine fetches only the
 * items whose time moved. A pass every two hours, because the lower plans allow 1,000 calls a day.
 *
 * <p><b>Deletion</b> by absence: {@code items_page} lists active items only, so an archived or
 * deleted item, or one moved to a board the source does not read, is removed after the next pass;
 * fetched before then, it is ItemGoneException.
 */
@Component
public class MondayConnectorPlugin implements ConnectorPlugin {

    static final String BOARDS = "boards";
    static final String ALL = "all";
    private static final Pattern BOARD_URL = Pattern.compile("(?i)^https?://[^/]+/boards/(\\d+)(?:[/?#].*)?$");

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.MONDAY)
            .protocol(ConnectionProtocol.MONDAY)
            .displayName("monday.com")
            .description("monday.com items with their columns, updates and subitems, from the boards you choose.")
            .noun("monday.com source", "monday.com sources")
            .addTitle("Add monday.com items")
            .order(60)
            .category(Category.TICKETS)
            .mark("monday")
            .fields(FieldSpec.text(BOARDS, "Boards").placeholder("1234567890")
                    .pattern("\\s*(\\d+|https?://\\S+/boards/\\d+\\S*)(\\s*,\\s*(\\d+|https?://\\S+/boards/\\d+\\S*))*\\s*")
                    .help("Board IDs or links, separated by commas - the number after /boards/ in a board's address. "
                            + "Every board the connection can see when empty."))
            .capabilities(Capabilities.builder().shareable().liveRead().build())
            .syncEvery(Duration.ofHours(2))
            .docsAnchor("monday")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new MondaySession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        List<String> boards = boards(config);
        return boards.isEmpty() ? ALL : String.join(",", boards);
    }

    @Override
    public String displayName(Map<String, String> config) {
        List<String> boards = boards(config);
        return boards.isEmpty() ? "monday.com" : "monday.com " + String.join(", ", boards);
    }

    /** The board IDs a configuration names, links read for their ID, sorted by number, each once; empty for every board. */
    static List<String> boards(Map<String, String> config) {
        String raw = config.get(BOARDS);
        TreeSet<Long> ids = new TreeSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String v = part.strip();
                Matcher m = BOARD_URL.matcher(v);
                if (m.matches()) v = m.group(1);
                if (v.matches("\\d{1,18}")) ids.add(Long.parseLong(v));
            }
        }
        List<String> out = new ArrayList<>();
        ids.forEach(id -> out.add(String.valueOf(id)));
        return out;
    }
}
