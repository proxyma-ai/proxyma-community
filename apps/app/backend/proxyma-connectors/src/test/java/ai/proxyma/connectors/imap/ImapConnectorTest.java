package ai.proxyma.connectors.imap;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConfigValidator;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import com.icegreen.greenmail.imap.ImapHostManager;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The IMAP connector against GreenMail, an IMAP server in this JVM, over implicit TLS with a
 * certificate made for "localhost" (so the server's name IS checked) and over plain IMAP on the
 * loopback address: the connection Test and its refusals (a wrong password, clear text to another
 * host, a server without STARTTLS), folders listed by UID with attachments as items of their own,
 * the rendered message, a CHANGES pass that asks only for new UIDs, a folder rebuilt under a new
 * UIDVALIDITY, a deleted message gone from the next full pass, the date and domain filters, and
 * that reading a message never marks it read.
 */
class ImapConnectorTest {

    private static final String USER = "ana@example.com";
    private static final String PASSWORD = "app-password-1";

    @TempDir
    static Path dir;
    private static SSLSocketFactory trusted;

    private GreenMail mail;
    private GreenMailUser ana;

    @BeforeAll
    static void certificate() throws Exception {
        // A key pair for "localhost", made by the JDK's keytool, for GreenMail's TLS - and trusted by
        // the client below, which still checks the name.
        Path store = dir.resolve("imap-test.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "greenmail", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        System.setProperty("greenmail.tls.keystore.file", store.toString());
        System.setProperty("greenmail.tls.keystore.password", "changeit");
        System.setProperty("greenmail.tls.key.password", "changeit");

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(store.toFile())) {
            keys.load(in, "changeit".toCharArray());
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("greenmail", keys.getCertificate("greenmail"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        trusted = context.getSocketFactory();
    }

    @BeforeEach
    void start() {
        mail = new GreenMail(new ServerSetup[] {
                new ServerSetup(3143, "127.0.0.1", ServerSetup.PROTOCOL_IMAP).dynamicPort(),
                new ServerSetup(3993, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS).dynamicPort()});
        mail.start();
        ana = mail.setUser(USER, USER, PASSWORD);
    }

    @AfterEach
    void stop() {
        mail.stop();
    }

    // -- helpers -------------------------------------------------------------------------------

    private ResolvedCredentials connection(String security, String password) {
        int port = "tls".equals(security) ? mail.getImaps().getPort() : mail.getImap().getPort();
        Map<String, String> settings = new HashMap<>();
        settings.put(ImapAccount.HOST, "localhost");
        settings.put(ImapAccount.PORT, String.valueOf(port));
        settings.put(ImapAccount.SECURITY, security);
        return new ResolvedCredentials(null, ConnectionProtocol.IMAP, AuthKind.BASIC, null, USER,
                Map.of(ImapAccount.PASSWORD, password), settings, null);
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, connection("tls", PASSWORD));
    }

    private ConnectorSession session(Map<String, Object> fields, ResolvedCredentials connection) {
        Map<String, String> config = ConfigValidator.validate(ImapConnectorPlugin.DESCRIPTOR.fields(), fields);
        return new ImapConnectorPlugin(trusted).open(SourceContext.of(ConnectorType.IMAP, config, connection, ConnectorHttp.direct()));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String read(ConnectorSession s, String id) throws Exception {
        ItemMetadata item = new ItemMetadata();
        item.setId(id);
        try (InputStream in = s.fetch(item)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private MimeMessage message(String from, String to, String subject, String text) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, to);
        m.setSubject(subject, "UTF-8");
        m.setSentDate(Date.from(Instant.parse("2026-10-01T09:00:00Z")));
        if (text != null) m.setText(text, "UTF-8");
        return m;
    }

    private MimeMessage withPdf(String subject, String text, byte[] pdf) throws Exception {
        MimeMessage m = message("Bo Tran <bo@partner.example>", USER, subject, null);
        m.setRecipients(Message.RecipientType.CC, "cy@example.com");
        MimeBodyPart body = new MimeBodyPart();
        body.setText(text, "UTF-8");
        MimeBodyPart file = new MimeBodyPart();
        file.setContent(pdf, "application/pdf");
        file.setFileName("quote-2026.pdf");
        file.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeBodyPart image = new MimeBodyPart();
        image.setContent(new byte[] {1, 2, 3}, "image/png");
        image.setFileName("logo.png");
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(body);
        mixed.addBodyPart(file);
        mixed.addBodyPart(image);
        m.setContent(mixed);
        m.saveChanges();
        return m;
    }

    private MailFolder folder(String name) throws Exception {
        ImapHostManager host = mail.getManagers().getImapHostManager();
        MailFolder f = host.getFolder(ana, name);
        return f != null ? f : host.createMailbox(ana, name);
    }

    private long append(String folder, MimeMessage m, String received) throws Exception {
        return folder(folder).appendMessage(m, new Flags(), Date.from(Instant.parse(received)));
    }

    // -- the descriptor ------------------------------------------------------------------------

    @Test
    @DisplayName("the descriptor: email, incremental with a cursor, never shareable; identity is the folders, INBOX in any case")
    void descriptor() {
        ImapConnectorPlugin plugin = new ImapConnectorPlugin();
        assertThat(plugin.descriptor().capabilities().incremental()).isTrue();
        assertThat(plugin.descriptor().capabilities().shareable()).isFalse();
        assertThat(plugin.identity(Map.of("folders", "inbox\nSent"))).isEqualTo(plugin.identity(Map.of("folders", "Sent\nINBOX")));
        assertThat(plugin.displayName(Map.of("folders", "inbox\nProjects/2026"))).isEqualTo("Mail: INBOX, Projects/2026");
        assertThat(ImapConnectorPlugin.domains(Map.of("domains", "@Partner.example, acme.com."))).containsExactly("partner.example", "acme.com");
        assertThat(ImapConnectionPlugin.DESCRIPTOR.hasBaseUrl()).isFalse();
    }

    // -- the connection ------------------------------------------------------------------------

    @Test
    @DisplayName("the Test signs in over TLS, its certificate checked for the server's name, and names the account")
    void connectionTest() {
        ConnectionTestResult result = new ImapConnectionPlugin(trusted).test(connection("tls", PASSWORD), ConnectorHttp.direct());
        assertThat(result.ok()).as(result.message()).isTrue();
        assertThat(result.message()).isEqualTo("Signed in as ana@example.com on localhost");
        assertThat(result.template()).isEqualTo("Signed in as {user} on {site}");
    }

    @Test
    @DisplayName("a wrong password, an untrusted certificate and a server without STARTTLS each say what to fix")
    void connectionRefusals() {
        ConnectionTestResult wrong = new ImapConnectionPlugin(trusted).test(connection("tls", "nope"), ConnectorHttp.direct());
        assertThat(wrong.ok()).isFalse();
        assertThat(wrong.message()).isEqualTo("localhost did not accept the username or password.");
        assertThat(wrong.template()).isEqualTo("{service} did not accept the username or password.");

        // The JVM's own trust does not know the test certificate: refused, never accepted blindly.
        ConnectionTestResult untrusted = new ImapConnectionPlugin().test(connection("tls", PASSWORD), ConnectorHttp.direct());
        assertThat(untrusted.ok()).isFalse();
        assertThat(untrusted.message()).contains("does not trust the mail server's certificate");

        ConnectionTestResult noStartTls = new ImapConnectionPlugin(trusted).test(connection("starttls", PASSWORD), ConnectorHttp.direct());
        assertThat(noStartTls.ok()).isFalse();
        assertThat(noStartTls.message()).contains("does not offer STARTTLS");
    }

    @Test
    @DisplayName("clear text is allowed to this computer only")
    void clearTextOnlyOnLoopback() {
        ConnectionTestResult local = new ImapConnectionPlugin().test(connection("none", PASSWORD), ConnectorHttp.direct());
        assertThat(local.ok()).as(local.message()).isTrue();

        Map<String, String> settings = Map.of(ImapAccount.HOST, "imap.example.com", ImapAccount.SECURITY, "none");
        ResolvedCredentials remote = new ResolvedCredentials(null, ConnectionProtocol.IMAP, AuthKind.BASIC, null, USER,
                Map.of(ImapAccount.PASSWORD, PASSWORD), settings, null);
        ConnectionTestResult refused = new ImapConnectionPlugin().test(remote, ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).contains("only to a mail server on this computer");

        assertThatThrownBy(() -> ImapAccount.of(new ResolvedCredentials(null, ConnectionProtocol.IMAP, AuthKind.BASIC, null, USER,
                Map.of(ImapAccount.PASSWORD, PASSWORD), Map.of(ImapAccount.HOST, "imaps://imap.example.com:993"), null)))
                .hasMessageContaining("host name");
    }

    // -- listing and fetching ------------------------------------------------------------------

    @Test
    @DisplayName("a full listing: every message of each folder by UIDVALIDITY and UID, and readable attachments as items")
    void fullListing() throws Exception {
        byte[] pdf = "%PDF-1.4 fake".getBytes(StandardCharsets.US_ASCII);
        long first = append("INBOX", withPdf("Quote for the MARBLEWREN order", "Please find the quote attached.", pdf), "2026-10-01T09:00:00Z");
        long second = append("INBOX", message("cy@example.com", USER, "Lunch", "Noon?"), "2026-10-02T09:00:00Z");
        long third = append("Projects", message("dee@example.com", USER, "Plan", "The plan."), "2026-10-03T09:00:00Z");
        long inbox = folder("INBOX").getUidValidity();
        long projects = folder("Projects").getUidValidity();

        try (ConnectorSession s = session(Map.of("folders", "INBOX\nProjects"))) {
            assertThat(s.verifyAccess().ok()).isTrue();
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(listing.hasErrored()).as(listing.getErrorMessage()).isFalse();
            assertThat(items).extracting(ItemMetadata::getId).containsExactly(
                    "message:" + inbox + ":" + first + ":INBOX",
                    "attachment:" + inbox + ":" + first + ":1:INBOX",
                    "message:" + inbox + ":" + second + ":INBOX",
                    "message:" + projects + ":" + third + ":Projects");
            ItemMetadata quote = items.get(0);
            assertThat(quote.getTitle()).isEqualTo("Quote for the MARBLEWREN order");
            assertThat(quote.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Bo Tran");
            assertThat(quote.getLastModified()).isEqualTo(Instant.parse("2026-10-01T09:00:00Z"));
            assertThat(quote.getContentType()).isEqualTo("text/markdown");
            ItemMetadata attachment = items.get(1);
            assertThat(attachment.getTitle()).isEqualTo("quote-2026.pdf");
            assertThat(attachment.getContentType()).isEqualTo("application/pdf");
            assertThat(ImapSession.readCursor(listing.nextCursor()))
                    .containsEntry("INBOX", inbox + ":" + second)
                    .containsEntry("Projects", projects + ":" + third);

            String md = read(s, items.get(0).getId());
            assertThat(md).startsWith("# Quote for the MARBLEWREN order\n")
                    .contains("- From: Bo Tran <bo@partner.example>")
                    .contains("- To: ana@example.com")
                    .contains("- Cc: cy@example.com")
                    .contains("- Date: 2026-10-01T09:00:00Z")
                    .contains("- Folder: INBOX")
                    .contains("- Mailbox: ana@example.com on localhost")
                    .contains("- Attachments: quote-2026.pdf, logo.png")
                    .contains("Please find the quote attached.");
            ItemMetadata file = new ItemMetadata();
            file.setId(attachment.getId());
            try (InputStream in = s.fetch(file)) {
                assertThat(in.readAllBytes()).isEqualTo(pdf);
            }
        }
        // Read-only all the way: nothing was marked read.
        for (StoredMessage m : folder("INBOX").getMessages()) assertThat(m.isSet(Flags.Flag.SEEN)).isFalse();
    }

    @Test
    @DisplayName("an HTML-only message is read as Markdown, its styles and images dropped")
    void htmlMessage() throws Exception {
        MimeMessage m = message("cy@example.com", USER, "Newsletter", null);
        m.setContent("<html><head><style>p{color:red}</style></head><body><h2>October</h2>"
                + "<p>Hello <b>KESTRELMOSS</b> readers.</p><img src=\"cid:x\"><ul><li>One</li><li>Two</li></ul></body></html>",
                "text/html; charset=UTF-8");
        m.saveChanges();
        long uid = append("INBOX", m, "2026-10-04T09:00:00Z");
        try (ConnectorSession s = session(Map.of())) {
            String md = read(s, "message:" + folder("INBOX").getUidValidity() + ":" + uid + ":INBOX");
            assertThat(md).contains("## October").contains("Hello **KESTRELMOSS** readers.").contains("* One")
                    .doesNotContain("color:red").doesNotContain("cid:");
        }
    }

    @Test
    @DisplayName("a CHANGES pass asks only for UIDs past the cursor's, and the cursor moves on")
    void changesPass() throws Exception {
        append("INBOX", message("cy@example.com", USER, "Old", "old"), "2026-10-01T09:00:00Z");
        String cursor;
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).hasSize(1);
            cursor = listing.nextCursor();
        }
        // Nothing new: "n:*" answers the newest message even below n, which is not listed again.
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(Instant.now(), cursor));
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.nextCursor()).isEqualTo(cursor);
        }
        long fresh = append("INBOX", message("cy@example.com", USER, "New", "new"), "2026-10-05T09:00:00Z");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(Instant.now(), cursor));
            assertThat(drain(listing)).extracting(ItemMetadata::getTitle).containsExactly("New");
            assertThat(ImapSession.readCursor(listing.nextCursor())).containsEntry("INBOX", folder("INBOX").getUidValidity() + ":" + fresh);
        }
    }

    @Test
    @DisplayName("a folder rebuilt under a new UIDVALIDITY is read whole, and its old items are gone")
    void uidValidityReset() throws Exception {
        long old = append("Projects", message("dee@example.com", USER, "Plan", "v1"), "2026-10-01T09:00:00Z");
        long oldValidity = folder("Projects").getUidValidity();
        String cursor;
        try (ConnectorSession s = session(Map.of("folders", "Projects"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            cursor = listing.nextCursor();
        }
        mail.getManagers().getImapHostManager().deleteMailbox(ana, "Projects");
        Thread.sleep(1100); // GreenMail's UIDVALIDITY is the clock's second
        long a = append("Projects", message("dee@example.com", USER, "Plan again", "v2"), "2026-10-06T09:00:00Z");
        long b = append("Projects", message("dee@example.com", USER, "Plan, third", "v3"), "2026-10-06T10:00:00Z");
        long validity = folder("Projects").getUidValidity();
        assertThat(validity).isNotEqualTo(oldValidity);

        try (ConnectorSession s = session(Map.of("folders", "Projects"))) {
            Listing listing = s.list(ListRequest.changes(Instant.now(), cursor));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly(
                    "message:" + validity + ":" + a + ":Projects", "message:" + validity + ":" + b + ":Projects");
            assertThatThrownBy(() -> read(s, "message:" + oldValidity + ":" + old + ":Projects"))
                    .isInstanceOf(ItemGoneException.class);
        }
    }

    @Test
    @DisplayName("a deleted message is missing from the next full listing and gone at its fetch")
    void deletedMessage() throws Exception {
        long keep = append("INBOX", message("cy@example.com", USER, "Keep", "k"), "2026-10-01T09:00:00Z");
        long drop = append("INBOX", message("cy@example.com", USER, "Drop", "d"), "2026-10-02T09:00:00Z");
        MailFolder inbox = folder("INBOX");
        long validity = inbox.getUidValidity();
        inbox.setFlags(new Flags(Flags.Flag.DELETED), true, drop, null, false);
        inbox.expunge();
        try (ConnectorSession s = session(Map.of())) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId)
                    .containsExactly("message:" + validity + ":" + keep + ":INBOX");
            assertThatThrownBy(() -> read(s, "message:" + validity + ":" + drop + ":INBOX")).isInstanceOf(ItemGoneException.class);
        }
    }

    @Test
    @DisplayName("received-since and the domains narrow the listing, a domain matched exactly and with its subdomains")
    void filters() throws Exception {
        append("INBOX", message("bo@partner.example", USER, "Partner, new", "x"), "2026-10-05T09:00:00Z");
        append("INBOX", message("bo@partner.example", USER, "Partner, old", "x"), "2026-09-01T09:00:00Z");
        append("INBOX", message("eve@mail.partner.example", USER, "Partner subdomain", "x"), "2026-10-06T09:00:00Z");
        append("INBOX", message("mal@notpartner.example", USER, "Look-alike", "x"), "2026-10-06T09:00:00Z");
        append("INBOX", message("cy@example.com", "team@partner.example", "Sent to partner", "x"), "2026-10-07T09:00:00Z");
        try (ConnectorSession s = session(Map.of("since", "2026-10-01", "domains", "partner.example", "attachments", "false"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getTitle)
                    .containsExactly("Partner, new", "Partner subdomain", "Sent to partner");
            assertThat(listing.hasErrored()).isFalse();
        }
    }

    @Test
    @DisplayName("a refused password stops the listing with a sentence; a folder that is not there is refused at registration")
    void refusals() {
        try (ConnectorSession s = session(Map.of(), connection("tls", "wrong"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).contains("did not accept the user name or password");
        }
        try (ConnectorSession s = session(Map.of("folders", "Archive/2019"))) {
            AccessCheck check = s.verifyAccess();
            assertThat(check.ok()).isFalse();
            assertThat(check.detail()).isEqualTo("The mailbox has no folder called Archive/2019.");
        }
    }
}
