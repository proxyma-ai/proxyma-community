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
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gmail against a loopback Gmail API: threads under each label with their headers, the history
 * feed and a history Gmail no longer keeps, a thread rendered with its messages' text and
 * attachments, and what is gone - archived, deleted, spam, out of the window.
 */
class GmailConnectorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final long DAY = 86_400_000L;
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final GmailConnectorPlugin plugin = new GmailConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        api.on("GET", "/gmail/v1/users/me/labels").json("{'labels':[{'id':'INBOX','name':'INBOX','type':'system'},"
                + "{'id':'SENT','name':'SENT','type':'system'},{'id':'SPAM','name':'SPAM','type':'system'},"
                + "{'id':'Label_7','name':'Clients','type':'user'}]}");
        api.on("GET", "/gmail/v1/users/me/profile").json("{'emailAddress':'ana@example.com','historyId':'5000'}");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        Map<String, String> config = ConfigValidator.validate(GmailConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(new SourceContext(null, ConnectorType.GMAIL, null, null, config, null, null,
                ResolvedCredentials.token(api.baseUrl(), "ya29.test"), SourceScope.defaults(),
                ConnectorHttp.direct(waits::add), Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String headers(String from, String subject) {
        return "'headers':[{'name':'From','value':'" + from + "'},{'name':'Subject','value':'" + subject + "'}]";
    }

    private static String meta(String id, long daysAgo, String labels, String from, String subject) {
        return "{'id':'" + id + "','internalDate':'" + (NOW.toEpochMilli() - daysAgo * DAY) + "','labelIds':[" + labels + "],"
                + "'payload':{" + headers(from, subject) + "}}";
    }

    private void thread(String id, String... messages) {
        api.on("GET", "/gmail/v1/users/me/threads/" + id).query("format", "metadata")
                .rawJson(("{'id':'" + id + "','historyId':'4999','messages':[" + String.join(",", messages) + "]}").replace('\'', '"'));
    }

    private void inboxInTwoPages() {
        api.on("GET", "/gmail/v1/users/me/threads").query("labelIds", "INBOX")
                .json("{'threads':[{'id':'t1'},{'id':'t2'}],'nextPageToken':'n2'}");
        api.on("GET", "/gmail/v1/users/me/threads").query("labelIds", "INBOX").query("pageToken", "n2")
                .json("{'threads':[{'id':'t3'}]}");
        thread("t1", meta("m1", 3, "'INBOX'", "Bo Tran <bo@example.com>", "Quarterly numbers"),
                meta("m2", 1, "'SENT'", "Ana Lee <ana@example.com>", "Re: Quarterly numbers"));
        thread("t2", meta("m3", 10, "'INBOX','UNREAD'", "carol@example.com", "Invoice"));
        thread("t3", meta("m4", 2, "'DRAFT'", "Ana Lee <ana@example.com>", "Unsent"));
    }

    @Test
    @DisplayName("a full listing reads every page of each label, one item per thread with its subject, sender and newest date")
    void fullListing() {
        inboxInTwoPages();
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);

            // t3 holds only a draft: nothing the source reads.
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("thread:t1", "thread:t2");
            ItemMetadata t1 = items.get(0);
            assertThat(t1.getTitle()).isEqualTo("Quarterly numbers");
            assertThat(t1.getLastModified()).isEqualTo(NOW.minusMillis(DAY));
            assertThat(t1.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana Lee");
            assertThat(t1.getExtra("url")).isEqualTo("https://mail.google.com/mail/u/ana@example.com/#all/t1");
            assertThat(t1.getContentType()).isEqualTo("text/markdown");
            assertThat(items.get(1).getExtra(ItemMetadata.AUTHOR)).isEqualTo("carol@example.com");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("5000");
        }
        LoopbackApi.Recorded list = api.requests("/gmail/v1/users/me/threads").get(0);
        assertThat(list.param("q")).isEqualTo("after:" + NOW.minus(Duration.ofDays(365)).getEpochSecond());
        assertThat(list.param("includeSpamTrash")).isEqualTo("false");
        assertThat(api.requests("/gmail/v1/users/me/threads/t1").get(0).param("format")).isEqualTo("metadata");
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer ya29.test"));
    }

    @Test
    @DisplayName("several labels list a thread under both once, and a label of your own is found by its name")
    void severalLabels() {
        inboxInTwoPages();
        api.on("GET", "/gmail/v1/users/me/threads").query("labelIds", "Label_7").json("{'threads':[{'id':'t1'},{'id':'t4'}]}");
        thread("t4", meta("m9", 30, "'Label_7'", "Dan <dan@example.com>", "Contract renewal"));
        try (ConnectorSession s = session(Map.of("labels", List.of("INBOX", "clients")))) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId)
                    .containsExactly("thread:t1", "thread:t2", "thread:t4");
        }
        assertThat(api.requests("/gmail/v1/users/me/threads/t1")).hasSize(1);
    }

    @Test
    @DisplayName("a changes pass reads the history since the cursor: each thread it names once, and one gone or relabelled is listed to be found gone")
    void changesPass() {
        api.on("GET", "/gmail/v1/users/me/history").query("startHistoryId", "4000").json("{'history':["
                + "{'id':'4001','messages':[{'id':'m1','threadId':'t1'}],'messagesAdded':[{'message':{'id':'m1','threadId':'t1'}}]}],"
                + "'nextPageToken':'h2','historyId':'4100'}");
        api.on("GET", "/gmail/v1/users/me/history").query("startHistoryId", "4000").query("pageToken", "h2").json("{'history':["
                + "{'id':'4101','labelsRemoved':[{'message':{'id':'m5','threadId':'t5'},'labelIds':['INBOX']}]},"
                + "{'id':'4102','messagesDeleted':[{'message':{'id':'m6','threadId':'t6'}}]},"
                + "{'id':'4103','messages':[{'id':'m7','threadId':'t1'}]}],'historyId':'4200'}");
        thread("t1", meta("m1", 0, "'INBOX'", "Bo Tran <bo@example.com>", "Quarterly numbers"));
        thread("t5", meta("m5", 5, "'SENT'", "Ana Lee <ana@example.com>", "Archived"));
        api.on("GET", "/gmail/v1/users/me/threads/t6").status(404).json("{'error':{'code':404}}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(NOW.minus(Duration.ofHours(1)), "4000"));
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("thread:t1", "thread:t5", "thread:t6");
            // Dated now, so the engine fetches each again - a relabelled thread's date did not move.
            assertThat(items).allSatisfy(i -> assertThat(i.getLastModified()).isEqualTo(NOW));
            assertThat(items.get(0).getTitle()).isEqualTo("Quarterly numbers");
            assertThat(listing.nextCursor()).isEqualTo("4200");
        }
        assertThat(api.requests("/gmail/v1/users/me/threads")).isEmpty();
    }

    @Test
    @DisplayName("a history Gmail no longer keeps (404) lists the mailbox in full, from the profile's history id")
    void expiredHistory() {
        api.on("GET", "/gmail/v1/users/me/history").status(404).json("{'error':{'code':404,'message':'Requested entity was not found.'}}");
        inboxInTwoPages();
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(NOW.minus(Duration.ofHours(1)), "12"));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("thread:t1", "thread:t2");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("5000");
        }
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a thread is fetched as Markdown: its participants and dates, each message's text, attachments by name, drafts left out")
    void fetchThread() throws IOException {
        String plain = "{'id':'m1','internalDate':'" + (NOW.toEpochMilli() - 3 * DAY) + "','labelIds':['INBOX'],"
                + "'payload':{'mimeType':'text/plain','headers':[{'name':'From','value':'Bo Tran <bo@example.com>'},"
                + "{'name':'To','value':'\\\"Lee, Ana\\\" <ana@example.com>, carol@example.com'},"
                + "{'name':'Subject','value':'Quarterly numbers'}],'body':{'data':'" + b64("Revenue grew by PERIWINKLE percent.") + "'}}}";
        String html = "{'id':'m2','internalDate':'" + (NOW.toEpochMilli() - DAY) + "','labelIds':['SENT'],"
                + "'payload':{'mimeType':'multipart/mixed','headers':[{'name':'From','value':'Ana Lee <ana@example.com>'},"
                + "{'name':'Subject','value':'Re: Quarterly numbers'}],'parts':["
                + "{'mimeType':'multipart/alternative','parts':[{'mimeType':'text/html','body':{'data':'"
                + b64("<p>Thanks &amp; well done.</p><div>See the report.</div>") + "'}}]},"
                + "{'mimeType':'application/pdf','filename':'report.pdf','body':{'attachmentId':'att9','size':1200}}]}}";
        String large = "{'id':'m4','internalDate':'" + (NOW.toEpochMilli() - DAY / 2) + "','labelIds':['INBOX'],"
                + "'payload':{'mimeType':'text/plain','headers':[{'name':'From','value':'Bo Tran <bo@example.com>'},"
                + "{'name':'Content-Type','value':'text/plain; charset=\\\"ISO-8859-1\\\"'}],'body':{'attachmentId':'big1'}}}";
        String draft = "{'id':'m3','internalDate':'" + NOW.toEpochMilli() + "','labelIds':['DRAFT'],"
                + "'payload':{'mimeType':'text/plain','headers':[{'name':'From','value':'Ana Lee <ana@example.com>'}],"
                + "'body':{'data':'" + b64("Not sent yet") + "'}}}";
        api.on("GET", "/gmail/v1/users/me/threads/t1").query("format", "full")
                .rawJson(("{'id':'t1','messages':[" + plain + "," + html + "," + large + "," + draft + "]}").replace('\'', '"'));
        api.on("GET", "/gmail/v1/users/me/messages/m4/attachments/big1").json("{'size':5,'data':'"
                + Base64.getUrlEncoder().encodeToString("Café closes at noon.".getBytes(StandardCharsets.ISO_8859_1)) + "'}");

        String md = text(fetch("thread:t1"));

        assertThat(md).startsWith("# Quarterly numbers\n")
                .contains("- Mailbox: ana@example.com", "- Labels: INBOX",
                        "- Participants: Bo Tran <bo@example.com>, \"Lee, Ana\" <ana@example.com>, carol@example.com, Ana Lee <ana@example.com>",
                        "- Messages: 3", "- Link: https://mail.google.com/mail/u/ana@example.com/#all/t1",
                        "## Bo Tran <bo@example.com>, " + NOW.minusMillis(3 * DAY),
                        "Revenue grew by PERIWINKLE percent.",
                        "Subject: Re: Quarterly numbers", "Thanks & well done.\nSee the report.",
                        "Attachments: report.pdf", "Café closes at noon.")
                .doesNotContain("Not sent yet");
    }

    @Test
    @DisplayName("a thread deleted, archived out of the labels, only spam, or older than the window is gone at its fetch")
    void goneThreads() {
        api.on("GET", "/gmail/v1/users/me/threads/t6").status(404).json("{'error':{'code':404}}");
        api.on("GET", "/gmail/v1/users/me/threads/t7").query("format", "full")
                .json("{'id':'t7','messages':[" + meta("m1", 2, "'SENT'", "a@example.com", "Archived") + "]}");
        api.on("GET", "/gmail/v1/users/me/threads/t8").query("format", "full")
                .json("{'id':'t8','messages':[" + meta("m1", 2, "'INBOX','SPAM'", "a@example.com", "Spam") + "]}");
        api.on("GET", "/gmail/v1/users/me/threads/t9").query("format", "full")
                .json("{'id':'t9','messages':[" + meta("m1", 400, "'INBOX'", "a@example.com", "Old") + "]}");
        for (String id : List.of("t6", "t7", "t8", "t9")) {
            assertThatThrownBy(() -> fetch("thread:" + id)).as(id).isInstanceOf(ItemGoneException.class);
        }
        assertThatThrownBy(() -> fetch("event:x")).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of a Gmail mailbox.");
    }

    @Test
    @DisplayName("registration checks the labels: an unknown one, drafts, spam or trash, and a refused sign-in each say what to do")
    void accessProbe() {
        try (ConnectorSession s = session(Map.of("labels", List.of("INBOX", "Clients")))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of("labels", "Nope"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Gmail has no label called Nope.");
        }
        try (ConnectorSession s = session(Map.of("labels", "spam"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Gmail drafts, spam and trash are never read. Choose another label.");
        }
        api.reset();
        api.on("GET", "/gmail/v1/users/me/labels").status(401).json("{'error':{'code':401}}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).startsWith("Google did not accept the connection's sign-in.");
        }
        api.reset();
        api.on("GET", "/gmail/v1/users/me/labels").status(403).json("{'error':{'code':403}}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).contains("Check that the Gmail API is enabled");
        }
    }

    @Test
    @DisplayName("Gmail's rate limit is waited out when it is short, and reported when it is not")
    void rateLimits() {
        api.on("GET", "/gmail/v1/users/me/threads").status(429).header("Retry-After", "1").then().json("{'threads':[]}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(1));
        api.on("GET", "/gmail/v1/users/me/threads").query("labelIds", "INBOX").status(429).header("Retry-After", "7200");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Gmail is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("the descriptor: the inbox and a year by default, identity is the labels without case or order")
    void descriptor() {
        Map<String, String> defaults = ConfigValidator.validate(GmailConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(defaults).containsEntry("labels", "INBOX").containsEntry("days", "365");
        assertThat(plugin.displayName(defaults)).isEqualTo("Gmail: INBOX");
        assertThat(plugin.webUrl(defaults, null)).isEqualTo("https://mail.google.com/mail/#inbox");
        Map<String, String> two = ConfigValidator.validate(GmailConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("labels", "Sent\nINBOX\ninbox"));
        assertThat(plugin.identity(two)).isEqualTo("inbox,sent");
        assertThat(plugin.displayName(two)).isEqualTo("Gmail: Sent, INBOX");
        assertThatThrownBy(() -> ConfigValidator.validate(GmailConnectorPlugin.DESCRIPTOR.fields(), Map.of("days", 0)))
                .hasMessage("Days of mail must be between 1 and 3650.");
        assertThat(GmailConnectorPlugin.DESCRIPTOR.capabilities().shareable()).isFalse();
        assertThat(GmailConnectorPlugin.DESCRIPTOR.protocol()).isEqualTo(GoogleConnectionPlugin.DESCRIPTOR.protocol());
        assertThat(GmailSession.displayName("\"Ana Lee\" <ana@example.com>")).isEqualTo("Ana Lee");
        assertThat(GmailSession.addresses("\"Lee, Ana\" <a@x.com>, b@x.com")).containsExactly("\"Lee, Ana\" <a@x.com>", "b@x.com");
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
