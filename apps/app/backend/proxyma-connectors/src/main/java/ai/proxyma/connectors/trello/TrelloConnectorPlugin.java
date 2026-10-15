package ai.proxyma.connectors.trello;

import ai.proxyma.connectors.workitems.WorkItems;
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
 * Trello cards as a source - of the boards chosen by link or ID, or of every open board of the
 * connection's member: each card with its list, labels, members, dates, description, checklists,
 * comments and the names of its attachments. Built on the connector framework v2 after the GitHub
 * connector.
 *
 * <p><b>Identity</b> is the set of boards, each as its short link (the part of a board's address
 * after {@code /b/}) or ID, sorted - or {@code all}. A short link keeps its case: Trello's are
 * case-sensitive.
 *
 * <p><b>Incremental</b>: cards whose {@code dateLastActivity} is after the last complete pass. Trello
 * has no filter for it on a board's cards, so a CHANGES pass reads each board's card list - one
 * request per thousand cards - and keeps the ones that moved.
 *
 * <p><b>Deletion</b>: a full pass lists the open cards of open boards, so an archived card, a card
 * of an archived board and a deleted card are absent and removed. A CHANGES pass also lists the
 * cards archived since, whose fetch answers ItemGoneException, so they leave at once.
 */
@Component
public class TrelloConnectorPlugin implements ConnectorPlugin {

    static final String BOARDS = "boards";
    static final String ALL = "all";
    private static final Pattern BOARD_URL = Pattern.compile("(?i)^https?://[^/]+/b/([A-Za-z0-9]+)(?:/.*)?$");

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.TRELLO)
            .protocol(ConnectionProtocol.TRELLO)
            .displayName("Trello")
            .description("Trello cards with their descriptions, checklists and comments, from the boards you choose.")
            .noun("Trello source", "Trello sources")
            .addTitle("Add Trello cards")
            .order(61)
            .category(Category.TICKETS)
            .mark("trello")
            .fields(FieldSpec.text(BOARDS, "Boards").placeholder("https://trello.com/b/AbCd1234/roadmap")
                    .help("Board links or IDs, separated by commas. Every open board of the connection's member when empty."))
            .capabilities(Capabilities.builder().incremental().shareable().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("trello")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new TrelloSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        List<String> boards = boards(config);
        return boards.isEmpty() ? ALL : String.join(",", boards);
    }

    @Override
    public String displayName(Map<String, String> config) {
        List<String> boards = boards(config);
        return boards.isEmpty() ? "Trello" : "Trello " + String.join(", ", boards);
    }

    /** One board links to its page; several, or every board, have no single page. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        List<String> boards = boards(config);
        return boards.size() == 1 ? "https://trello.com/b/" + boards.get(0) : null;
    }

    /** The boards a configuration names, each as its short link or ID, sorted, each once; empty for every board. */
    static List<String> boards(Map<String, String> config) {
        TreeSet<String> out = new TreeSet<>();
        for (String typed : WorkItems.list(config.get(BOARDS))) {
            Matcher m = BOARD_URL.matcher(typed);
            out.add(m.matches() ? m.group(1) : typed);
        }
        return new ArrayList<>(out);
    }
}
