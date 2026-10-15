package ai.proxyma.connectors.imap;

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

import javax.net.ssl.SSLSocketFactory;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A mailbox reached over IMAP as a source: the messages of chosen folders (the inbox by default),
 * optionally only those received since a day or sent from or to chosen domains - ONE ITEM PER
 * MESSAGE, rendered as Markdown with its subject, sender, recipients, date, plain text (an HTML-only
 * message as Markdown) and the names of its attachments; and each attachment whose type Proxyma reads
 * (PDF, Word, Excel, PowerPoint, text and the rest of the file types) as an item of its own,
 * converted like a file.
 *
 * <p><b>Why per message and not per thread</b>, as Gmail is: IMAP has no thread to ask for, only
 * the References and In-Reply-To headers, which mail programs fill unevenly; a thread's messages sit
 * in different folders under different UIDVALIDITYs; and a thread item would change every time a
 * reply arrived, which IMAP's "new UIDs since the last one" cannot say about an OLD item. A message
 * never changes once delivered, so a UID is a complete record of what has been read.
 *
 * <p><b>Identity</b> of a message is its folder, the folder's UIDVALIDITY and its UID. <b>Incremental</b>
 * by UID: a CHANGES pass asks each folder for UIDs above the last one a complete pass saw, kept per
 * folder with its UIDVALIDITY as the cursor; a folder whose UIDVALIDITY changed (rebuilt on the
 * server) is read whole. <b>Deletion</b>: a full pass (daily) lists every UID that matches, so a
 * message deleted, moved out of the folder or no longer matching the filters is removed.
 *
 * <p><b>Identity of the source</b> is its folders, INBOX in any case being one name. Never shareable:
 * it is one person's mail.
 */
@Component
public class ImapConnectorPlugin implements ConnectorPlugin {

    static final String FOLDERS = "folders";
    static final String SINCE = "since";
    static final String DOMAINS = "domains";
    static final String ATTACHMENTS = "attachments";
    static final String INBOX = "INBOX";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.IMAP)
            .protocol(ConnectionProtocol.IMAP)
            .displayName("IMAP mailbox")
            .description("Messages in the folders you choose of a mailbox that accepts a password over IMAP - the inbox by default.")
            .noun("IMAP mailbox", "IMAP mailboxes")
            .addTitle("Add an IMAP mailbox")
            .order(46)
            .category(Category.EMAIL)
            .mark("imap")
            .fields(
                    FieldSpec.list(FOLDERS, "Folders").defaultValue(INBOX).placeholder(INBOX)
                            .help("Each folder to read, by its full name: INBOX, Sent, Projects/2026. A folder's "
                                    + "subfolders are read only when they are named too."),
                    FieldSpec.text(SINCE, "Received since").placeholder("2025-01-01")
                            .pattern("\\d{4}-\\d{2}-\\d{2}")
                            .help("Only messages received on or after this day, as YYYY-MM-DD. Empty reads them all."),
                    FieldSpec.bool(ATTACHMENTS, "Attachments").defaultValue("true")
                            .help("Read attached documents - PDF, Word, Excel, PowerPoint, text - as documents of their own."),
                    FieldSpec.list(DOMAINS, "Only these domains").placeholder("example.com").asAdvanced()
                            .help("Only messages from or to an address at one of these domains. Empty reads every message."))
            .capabilities(Capabilities.builder().incremental().cursor().liveRead().build())
            .syncEvery(Duration.ofMinutes(30))
            .docsAnchor("imap")
            .build();

    private final SSLSocketFactory sockets;

    public ImapConnectorPlugin() {
        this(null);
    }

    /** A plugin whose TLS trusts what {@code sockets} trusts - for a test's own server. */
    ImapConnectorPlugin(SSLSocketFactory sockets) {
        this.sockets = sockets;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new ImapSession(context, sockets);
    }

    @Override
    public String identity(Map<String, String> config) {
        return String.join(",", new TreeSet<>(folders(config)));
    }

    @Override
    public String displayName(Map<String, String> config) {
        List<String> folders = folders(config);
        return folders.isEmpty() ? "" : "Mail: " + String.join(", ", folders);
    }

    /** The folders the source names, once each and in order; INBOX, in any case, as INBOX. */
    static List<String> folders(Map<String, String> config) {
        List<String> out = new ArrayList<>();
        String raw = config.get(FOLDERS);
        if (raw == null) return out;
        Set<String> seen = new HashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            String folder = line.strip();
            if (folder.equalsIgnoreCase(INBOX)) folder = INBOX;
            if (!folder.isEmpty() && seen.add(folder)) out.add(folder);
        }
        return out;
    }

    /** The day the source starts at, or null for every message. */
    static LocalDate since(Map<String, String> config) {
        String raw = config.get(SINCE);
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The domains a message must be from or to, lower case, without "@"; empty for every message. */
    static Set<String> domains(Map<String, String> config) {
        Set<String> out = new LinkedHashSet<>();
        String raw = config.get(DOMAINS);
        if (raw == null) return out;
        for (String line : raw.split("[\\r\\n,;\\s]+")) {
            String d = line.strip().toLowerCase(Locale.ROOT);
            while (d.startsWith("@") || d.startsWith(".")) d = d.substring(1);
            while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
            if (!d.isEmpty()) out.add(d);
        }
        return out;
    }

    static boolean attachments(Map<String, String> config) {
        String raw = config.get(ATTACHMENTS);
        return raw == null || raw.isBlank() || Boolean.parseBoolean(raw.strip());
    }
}
