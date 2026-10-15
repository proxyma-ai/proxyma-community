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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A Gmail mailbox as a source: the conversations under chosen labels (the inbox by default) with a
 * message in a window before today, one item per thread, rendered as Markdown with its subject, its
 * participants, its dates, every message's plain text and its attachments' names. Drafts, spam and
 * trash are never read, whichever labels are chosen.
 *
 * <p><b>Identity</b> is the set of labels, without case: INBOX and SENT is one source, in either order.
 *
 * <p><b>Incremental</b>: Gmail's history from the mailbox's {@code historyId}, kept as the cursor -
 * every thread a message was added to, deleted from or relabelled in. A history Gmail no longer
 * keeps (a 404) lists the mailbox in full.
 *
 * <p><b>Deletion</b>: a changed thread that no longer exists, or no longer carries a chosen label,
 * is found gone at its fetch; a complete full listing removes every thread it did not list - a
 * conversation older than the window included.
 *
 * <p>Never shareable: it is one person's mail.
 */
@Component
public class GmailConnectorPlugin implements ConnectorPlugin {

    static final String LABELS = "labels";
    static final String DAYS = "days";
    static final int DEFAULT_DAYS = 365;

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.GMAIL)
            .protocol(ConnectionProtocol.GOOGLE)
            .displayName("Gmail")
            .description("Conversations in a Gmail mailbox, under the labels you choose - the inbox by default.")
            .noun("Gmail mailbox", "Gmail mailboxes")
            .addTitle("Add a Gmail mailbox")
            .order(42)
            .category(Category.EMAIL)
            .mark("gmail")
            .fields(
                    FieldSpec.list(LABELS, "Labels").defaultValue("INBOX").placeholder("INBOX")
                            .help("Each label to read, by its name: INBOX, SENT, or one of your own. Drafts, spam "
                                    + "and trash are never read."),
                    FieldSpec.number(DAYS, "Days of mail").defaultValue(String.valueOf(DEFAULT_DAYS)).range(1, 3650)
                            .help("Only conversations with a message from this many days before today."))
            .capabilities(Capabilities.builder().incremental().cursor().liveRead().build())
            .syncEvery(Duration.ofMinutes(30))
            .docsAnchor("gmail")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new GmailSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String label : labels(config)) sorted.add(label.toLowerCase(Locale.ROOT));
        return String.join(",", sorted);
    }

    @Override
    public String displayName(Map<String, String> config) {
        List<String> labels = new ArrayList<>(labels(config));
        return labels.isEmpty() ? "" : "Gmail: " + String.join(", ", labels);
    }

    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        Set<String> labels = labels(config);
        if (labels.size() == 1 && labels.iterator().next().equalsIgnoreCase("INBOX")) {
            return "https://mail.google.com/mail/#inbox";
        }
        return "https://mail.google.com/mail/";
    }

    /** The labels the source names, as typed, in order, once each. */
    static Set<String> labels(Map<String, String> config) {
        Set<String> out = new LinkedHashSet<>();
        String raw = config.get(LABELS);
        if (raw == null) return out;
        Set<String> seen = new java.util.HashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            String label = line.strip();
            if (!label.isEmpty() && seen.add(label.toLowerCase(Locale.ROOT))) out.add(label);
        }
        return out;
    }

    static int days(Map<String, String> config) {
        try {
            int days = Integer.parseInt(config.getOrDefault(DAYS, "").strip());
            return days > 0 ? days : DEFAULT_DAYS;
        } catch (NumberFormatException e) {
            return DEFAULT_DAYS;
        }
    }
}
