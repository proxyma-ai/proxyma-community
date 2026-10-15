package ai.proxyma.connectors.slack;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Slack connector against a loopback Slack: channels found through users.conversations and
 * paged by cursor, a channel's history turned into threads and days, the incremental pass and its
 * trailing week, what a pass may and may not remove, each kind of fetch, Slack's refusals in a 200
 * body, its 429 with and without Retry-After, and the connection's Test.
 */
class SlackConnectorTest {

    private static final String BOT = "xoxb-test-bot";
    private static final String USER = "xoxp-test-user";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final SlackConnectorPlugin plugin = new SlackConnectorPlugin();

    // The times the fixtures are written at, as Slack's ts.
    private static final String ROOT = ts("2026-10-02T10:00:00Z");
    private static final String REPLY_1 = ts("2026-10-02T11:00:00Z");
    private static final String REPLY_2 = ts("2026-10-03T08:00:00Z");
    private static final String LUNCH = ts("2026-10-02T15:00:00Z");
    private static final String BROADCAST = ts("2026-10-02T14:00:00Z");
    private static final String STANDUP = ts("2026-10-02T09:00:00Z");
    private static final String JOIN = ts("2026-10-01T12:00:00Z");
    private static final String KICKOFF = ts("2026-10-01T11:00:00Z");
    private static final String RANDOM = ts("2026-10-01T08:00:00Z");

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        basics();
    }

    // auth.test (the workspace, for links) and the two people every fixture names.
    private void basics() {
        api.on("GET", "/auth.test").json("{'ok':true,'url':'https://acme.slack.com/','team':'Acme','user':'proxyma',"
                + "'team_id':'T1','user_id':'U0'}");
        api.on("GET", "/users.info").query("user", "U1").json("{'ok':true,'user':{'id':'U1','name':'ana','real_name':'Ana Lima'}}");
        api.on("GET", "/users.info").query("user", "U2").json("{'ok':true,'user':{'id':'U2','name':'bo','real_name':'Bo Chen'}}");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private static String ts(String iso) {
        return SlackSession.ts(Instant.parse(iso));
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, false, Clock.systemUTC());
    }

    private ConnectorSession session(Map<String, Object> fields, boolean userToken, Clock clock) {
        Map<String, String> config = ConfigValidator.validate(SlackConnectorPlugin.DESCRIPTOR.fields(), fields);
        Map<String, String> secrets = new HashMap<>();
        secrets.put("token", BOT);
        if (userToken) secrets.put("userToken", USER);
        ResolvedCredentials credentials = new ResolvedCredentials(null, ConnectionProtocol.SLACK, AuthKind.TOKEN,
                api.baseUrl(), null, secrets, Map.of(), null);
        return plugin.open(new SourceContext(null, ConnectorType.SLACK, null, null, config, null, null, credentials,
                SourceScope.defaults(), ConnectorHttp.direct(waits::add), clock));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    // #general (C1) and #random (C2), the app in both, over two pages; #secret (G3) is private.
    private void channelsInTwoPages() {
        api.on("GET", "/users.conversations").json("{'ok':true,'channels':["
                + "{'id':'C1','name':'general','is_private':false},"
                + "{'id':'G3','name':'secret','is_private':true}],"
                + "'response_metadata':{'next_cursor':'page2'}}");
        api.on("GET", "/users.conversations").query("cursor", "page2").json("{'ok':true,'channels':["
                + "{'id':'C2','name':'random','is_private':false},"
                + "{'id':'D9','name':'','is_im':true}],'response_metadata':{'next_cursor':''}}");
    }

    // #general's history, newest first, over two pages: a day with an edit, a thread with a reply
    // broadcast to the channel, a join, and the day before.
    private void generalHistory() {
        api.on("GET", "/conversations.history").query("channel", "C1").json("{'ok':true,'messages':["
                        + "{'type':'message','user':'U2','ts':'" + LUNCH + "','text':'Lunch order is in'},"
                        + "{'type':'message','subtype':'thread_broadcast','user':'U2','ts':'" + BROADCAST + "',"
                        + "'thread_ts':'" + ROOT + "','text':'Also sending this to the channel'},"
                        + "{'type':'message','user':'U1','ts':'" + ROOT + "','thread_ts':'" + ROOT + "','reply_count':2,"
                        + "'latest_reply':'" + REPLY_2 + "','text':'Deploy freeze this Friday\\nDetails from <@U1> below'},"
                        + "{'type':'message','user':'U1','ts':'" + STANDUP + "','text':'Standup moved to ten',"
                        + "'edited':{'user':'U1','ts':'" + ts("2026-10-02T16:00:00Z") + "'}}],"
                        + "'has_more':true,'response_metadata':{'next_cursor':'h2'}}");
        api.on("GET", "/conversations.history").query("channel", "C1").query("cursor", "h2").json("{'ok':true,'messages':["
                + "{'type':'message','subtype':'channel_join','user':'U2','ts':'" + JOIN + "','text':'joined'},"
                + "{'type':'message','user':'U2','ts':'" + KICKOFF + "','text':'Kickoff notes are up'}],"
                + "'has_more':false,'response_metadata':{'next_cursor':''}}");
    }

    private void randomHistory() {
        api.on("GET", "/conversations.history").query("channel", "C2").json("{'ok':true,'messages':["
                + "{'type':'message','bot_id':'B1','bot_profile':{'name':'Deploy Bot'},'ts':'" + RANDOM + "',"
                + "'text':'Build 42 is green'}]}");
    }

    @Test
    @DisplayName("a full listing reads every page of every public channel the app is in: threads at once, days once the history moves past them")
    void fullListing() {
        channelsInTwoPages();
        generalHistory();
        randomHistory();
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);

            assertThat(items).extracting(ItemMetadata::getId).containsExactly(
                    "thread:C1:" + ROOT, "day:C1:2026-10-02", "day:C1:2026-10-01", "day:C2:2026-10-01");
            ItemMetadata thread = items.get(0);
            assertThat(thread.getTitle()).isEqualTo("#general: Deploy freeze this Friday");
            assertThat(thread.getLastModified()).isEqualTo(Instant.parse("2026-10-03T08:00:00Z"));
            assertThat(thread.getContentType()).isEqualTo("text/markdown");
            assertThat(thread.getExtra("url")).isEqualTo("https://acme.slack.com/archives/C1/p" + ROOT.replace(".", ""));
            assertThat(thread.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana Lima");
            ItemMetadata day = items.get(1);
            assertThat(day.getTitle()).isEqualTo("#general, 2026-10-02");
            // The edit, after the day's last message, is when the day last changed.
            assertThat(day.getLastModified()).isEqualTo(Instant.parse("2026-10-02T16:00:00Z"));
            assertThat(day.getExtra("url")).isEqualTo("https://acme.slack.com/archives/C1/p" + STANDUP.replace(".", ""));
            // A complete pass: the engine may remove what it did not list.
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
        }
        // Every request carried the bot token; channels only, never a DM - and the private channel,
        // not chosen, is not read in "every public channel".
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + BOT));
        assertThat(api.requests("/users.conversations").get(0).param("types")).isEqualTo("public_channel,private_channel");
        assertThat(api.requests("/conversations.history")).extracting(r -> r.param("channel")).containsOnly("C1", "C2");
        assertThat(api.requests("/conversations.history").get(0).param("oldest")).isNull();
        // A person's name is asked for once per session, however often they appear.
        assertThat(api.requests("/users.info")).extracting(r -> r.param("user")).containsExactly("U1");
    }

    @Test
    @DisplayName("a changes pass re-reads a week of history and lists only the threads and days changed since the last pass")
    void incrementalListing() {
        channelsInTwoPages();
        generalHistory();
        api.on("GET", "/conversations.history").query("channel", "C2").json("{'ok':true,'messages':[]}");
        Instant since = Instant.parse("2026-10-03T07:00:00Z");
        try (ConnectorSession s = session(Map.of("channels", "#general"))) {
            Listing listing = s.list(ListRequest.changes(since, null));
            // The thread's latest reply came after the last pass; nothing else did.
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("thread:C1:" + ROOT);
        }
        assertThat(api.requests("/conversations.history").get(0).param("oldest"))
                .isEqualTo(SlackSession.ts(since.minus(SlackSession.OVERLAP).minus(SlackSession.TRAILING)));
    }

    @Test
    @DisplayName("the history window bounds what is read, measured from the source's clock")
    void historyWindow() {
        channelsInTwoPages();
        api.on("GET", "/conversations.history").json("{'ok':true,'messages':[]}");
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);
        try (ConnectorSession s = session(Map.of("channels", "general", "days", 10), false, clock)) {
            drain(s.list(ListRequest.full()));
        }
        assertThat(api.requests("/conversations.history").get(0).param("oldest")).isEqualTo(ts("2026-10-04T00:00:00Z"));
    }

    @Test
    @DisplayName("chosen channels: a private one is read with the user token, exclusions apply, and one the app cannot read makes the pass partial")
    void chosenChannels() {
        // users.conversations answers the bot first, then the user token.
        api.on("GET", "/users.conversations").json("{'ok':true,'channels':[{'id':'C1','name':'general'},"
                        + "{'id':'C2','name':'random'}]}")
                .then().json("{'ok':true,'channels':[{'id':'G7','name':'leads','is_private':true}]}");
        generalHistory();
        api.on("GET", "/conversations.history").query("channel", "G7").json("{'ok':true,'messages':["
                + "{'type':'message','user':'U1','ts':'" + KICKOFF + "','text':'Hiring plan'}]}");
        Map<String, Object> fields = Map.of("channels", "#General\nleads\n#random\n#gone", "exclude", "random");
        try (ConnectorSession s = session(fields, true, Clock.systemUTC())) {
            assertThat(s.verifyAccess().detail())
                    .isEqualTo("The Slack app cannot read #gone. Invite it to each channel with /invite, or check the names.");
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly(
                    "thread:C1:" + ROOT, "day:C1:2026-10-02", "day:C1:2026-10-01", "day:G7:2026-10-01");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isTrue();
            assertThat(listing.getPartialReason()).contains("#gone").contains("nothing of it was removed");
            assertThat(listing.getGoneItemIds()).isEmpty();
        }
        assertThat(api.requests("/conversations.history")).extracting(r -> r.param("channel")).doesNotContain("C2");
        assertThat(api.requests("/conversations.history").stream().filter(r -> "G7".equals(r.param("channel"))))
                .allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + USER));
    }

    @Test
    @DisplayName("every public channel with the app in none of them is refused at registration")
    void noChannelAtAll() {
        api.on("GET", "/users.conversations").json("{'ok':true,'channels':[]}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).isEqualTo("The Slack app is not in any public channel yet. "
                    + "Invite it to the channels to index with /invite.");
        }
        api.reset();
        basics();
        channelsInTwoPages();
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
    }

    @Test
    @DisplayName("a page Slack fails mid-history is an errored listing - nothing is removed for what it did not reach")
    void failedPageIsNoDeletion() {
        channelsInTwoPages();
        api.on("GET", "/conversations.history").query("channel", "C1").json("{'ok':true,'messages':["
                + "{'type':'message','user':'U1','ts':'" + ROOT + "','reply_count':1,'latest_reply':'" + REPLY_1 + "',"
                + "'text':'First'}],'response_metadata':{'next_cursor':'h2'}}");
        api.on("GET", "/conversations.history").query("channel", "C1").query("cursor", "h2")
                .json("{'ok':false,'error':'internal_error'}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("thread:C1:" + ROOT);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Slack answered with a server error. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("a thread is fetched as one markdown document: names, times, links and Slack's markup made readable")
    void fetchThread() throws IOException {
        channelsInTwoPages();
        api.on("GET", "/conversations.replies").query("channel", "C1").query("ts", ROOT).json("{'ok':true,'messages':["
                + "{'type':'message','user':'U1','ts':'" + ROOT + "','thread_ts':'" + ROOT + "','reply_count':2,"
                + "'text':'Deploy freeze this Friday. Ask <@U2> &amp; see <https://wiki.example/freeze|the plan>.'}],"
                + "'response_metadata':{'next_cursor':'r2'}}");
        api.on("GET", "/conversations.replies").query("channel", "C1").query("ts", ROOT).query("cursor", "r2")
                .json("{'ok':true,'messages':["
                        + "{'type':'message','user':'U2','ts':'" + REPLY_2 + "','thread_ts':'" + ROOT + "','text':'<!here> agreed'},"
                        + "{'type':'message','user':'U3','ts':'" + REPLY_1 + "','thread_ts':'" + ROOT + "',"
                        + "'text':'Moved to <#C2|random>','files':[{'name':'freeze.pdf'}],'edited':{'ts':'" + REPLY_2 + "'}}]}");
        // U3 cannot be named - users.info refuses - and is shown by id rather than failing the item.
        api.on("GET", "/users.info").query("user", "U3").json("{'ok':false,'error':'user_not_found'}");

        String md = text(fetch("thread:C1:" + ROOT));

        assertThat(md).startsWith("# #general: Deploy freeze this Friday.")
                .contains("- Workspace: Acme", "- Channel: #general", "- Started by: Ana Lima",
                        "- Started: 2026-10-02 10:00 UTC", "- Replies: 2",
                        "- Link: https://acme.slack.com/archives/C1/p" + ROOT.replace(".", ""),
                        "Ask @Bo Chen & see [the plan](https://wiki.example/freeze).",
                        "**U3**, 2026-10-02 11:00 UTC (edited) ([link](https://acme.slack.com/archives/C1/p"
                                + REPLY_1.replace(".", "") + "?thread_ts=" + ROOT + "&cid=C1)):",
                        "Moved to #random\n\nAttached: freeze.pdf", "**Bo Chen**, 2026-10-03 08:00 UTC", "@here agreed");
        // Replies in the order they were written, whatever order the pages gave them in.
        assertThat(md.indexOf("Moved to #random")).isLessThan(md.indexOf("@here agreed"));
    }

    @Test
    @DisplayName("a day is fetched with its standalone messages only, oldest first, bounded to that UTC day")
    void fetchDay() throws IOException {
        channelsInTwoPages();
        api.on("GET", "/conversations.history").query("channel", "C1").query("inclusive", "true").json("{'ok':true,'messages':["
                + "{'type':'message','user':'U2','ts':'" + LUNCH + "','text':'Lunch order is in'},"
                + "{'type':'message','user':'U1','ts':'" + ROOT + "','thread_ts':'" + ROOT + "','reply_count':2,'text':'Deploy freeze'},"
                + "{'type':'message','user':'U1','ts':'" + STANDUP + "','text':'Standup moved to ten'}]}");

        String md = text(fetch("day:C1:2026-10-02"));

        assertThat(md).startsWith("# #general, 2026-10-02")
                .contains("- Date: 2026-10-02 (UTC)", "- Messages: 2", "**Ana Lima**, 09:00 UTC", "**Bo Chen**, 15:00 UTC")
                .doesNotContain("Deploy freeze");
        assertThat(md.indexOf("Standup moved to ten")).isLessThan(md.indexOf("Lunch order is in"));
        LoopbackApi.Recorded day = api.requests("/conversations.history").get(0);
        assertThat(day.param("oldest")).isEqualTo(ts("2026-10-02T00:00:00Z"));
        assertThat(day.param("latest")).isEqualTo(Instant.parse("2026-10-02T23:59:59Z").getEpochSecond() + ".999999");
    }

    @Test
    @DisplayName("a thread or a day Slack no longer has is gone; a channel outside the source is not read at all")
    void goneItems() {
        channelsInTwoPages();
        api.on("GET", "/conversations.replies").json("{'ok':false,'error':'thread_not_found'}");
        api.on("GET", "/conversations.history").json("{'ok':true,'messages':[]}");
        assertThatThrownBy(() -> fetch("thread:C1:" + ROOT)).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("day:C1:2026-10-02")).isInstanceOf(ItemGoneException.class);

        api.reset();
        basics();
        channelsInTwoPages();
        // G3 is a channel the app is in, but not one this source reads (every PUBLIC channel).
        assertThatThrownBy(() -> fetch("thread:G3:" + ROOT)).isInstanceOf(ConnectorException.class)
                .isNotInstanceOf(ItemGoneException.class)
                .hasMessage("That conversation is not in a channel this Slack source reads.");
        assertThatThrownBy(() -> fetch("im:D9:" + ROOT)).hasMessage("That is not an item of a Slack source.");
        assertThat(api.requests("/conversations.replies")).isEmpty();
    }

    @Test
    @DisplayName("a 429 is waited out for its Retry-After, or ten requests of the tier's pace without one, and reported when too long")
    void rateLimits() {
        channelsInTwoPages();
        api.on("GET", "/conversations.history").status(429).header("Retry-After", "7")
                .json("{'ok':false,'error':'ratelimited'}").then().json("{'ok':true,'messages':[]}");
        try (ConnectorSession s = session(Map.of("channels", "general"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).contains(Duration.ofSeconds(7));

        api.reset();
        basics();
        waits.clear();
        channelsInTwoPages();
        api.on("GET", "/conversations.history").status(429).json("{'ok':false,'error':'ratelimited'}")
                .then().json("{'ok':true,'messages':[]}");
        try (ConnectorSession s = session(Map.of("channels", "general"))) {
            assertThat(s.list(ListRequest.full()).hasNext()).isFalse();
        }
        assertThat(waits).contains(SlackApi.Tier.TIER_3.pace.multipliedBy(10));

        api.reset();
        basics();
        channelsInTwoPages();
        api.on("GET", "/conversations.history").status(429).header("Retry-After", "3600")
                .json("{'ok':false,'error':'ratelimited'}");
        try (ConnectorSession s = session(Map.of("channels", "general"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Slack is limiting how often it is asked. The next sync will try again.");
        }

        // The same refusal in a 200 body says the same thing.
        api.reset();
        basics();
        channelsInTwoPages();
        api.on("GET", "/conversations.history").json("{'ok':false,'error':'ratelimited'}");
        try (ConnectorSession s = session(Map.of("channels", "general"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Slack is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("Slack's refusals in a 200 body each say what to do")
    void refusals() {
        api.on("GET", "/users.conversations").json("{'ok':false,'error':'invalid_auth'}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail())
                    .isEqualTo("Slack did not accept the token. Replace it, or sign in again, in Settings > Connections.");
        }
        api.reset();
        basics();
        api.on("GET", "/users.conversations").json("{'ok':false,'error':'missing_scope','needed':'groups:read'}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).isEqualTo("The Slack app is missing the groups:read permission. "
                    + "Add it under OAuth & Permissions, reinstall the app, and update the token in Settings > Connections.");
        }
        api.reset();
        basics();
        channelsInTwoPages();
        api.on("GET", "/conversations.history").json("{'ok':false,'error':'not_in_channel'}");
        try (ConnectorSession s = session(Map.of("channels", "general"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).startsWith("The Slack app is not in that channel any more.");
        }
    }

    @Test
    @DisplayName("the connection's Test names the workspace and the app, and checks a user token too")
    void connectionTest() {
        SlackConnectionPlugin connection = new SlackConnectionPlugin();
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), BOT), ConnectorHttp.direct(waits::add));
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in to Acme as proxyma");
        assertThat(ok.template()).isEqualTo("Signed in to {workspace} as {user}");
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer " + BOT);

        api.reset();
        api.on("GET", "/auth.test").json("{'ok':false,'error':'invalid_auth'}");
        ConnectionTestResult refused = connection.test(ResolvedCredentials.token(api.baseUrl(), BOT), ConnectorHttp.direct(waits::add));
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("Slack did not accept the token.");
        assertThat(refused.template()).isEqualTo("{service} did not accept the token.");

        api.reset();
        api.on("GET", "/auth.test").json("{'ok':true,'team':'Acme','user':'proxyma'}")
                .then().json("{'ok':false,'error':'token_revoked'}");
        ResolvedCredentials both = new ResolvedCredentials(null, ConnectionProtocol.SLACK, AuthKind.TOKEN, api.baseUrl(),
                null, Map.of("token", BOT, "userToken", USER), Map.of(), null);
        ConnectionTestResult user = connection.test(both, ConnectorHttp.direct(waits::add));
        assertThat(user.ok()).isFalse();
        assertThat(user.message()).isEqualTo("Slack did not accept the user token.");
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer " + USER);
    }

    @Test
    @DisplayName("Sign in with Slack asks for exactly the read scopes, comma-separated, and never a DM scope")
    void signIn() {
        var spec = new SlackConnectionPlugin().oauth2();
        assertThat(spec.authorizeUrl()).isEqualTo("https://slack.com/oauth/v2/authorize");
        assertThat(spec.tokenUrl()).isEqualTo("https://slack.com/api/oauth.v2.access");
        assertThat(spec.scopes()).isEmpty();
        assertThat(spec.extraAuthorizeParams()).containsEntry("scope",
                "channels:history,channels:read,groups:history,groups:read,users:read");
        assertThat(SlackConnectionPlugin.SCOPES).noneMatch(s -> s.startsWith("im:") || s.startsWith("mpim:"));
        assertThat(SlackConnectionPlugin.DESCRIPTOR.offers(AuthKind.TOKEN)).isTrue();
        assertThat(SlackConnectionPlugin.DESCRIPTOR.offers(AuthKind.OAUTH2_CODE)).isTrue();
    }

    @Test
    @DisplayName("the descriptor: identity is the channels, sorted and without case, or every public channel")
    void descriptor() {
        Map<String, String> chosen = ConfigValidator.validate(SlackConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("channels", " #Release \n#general\nC0123ABCD"));
        assertThat(plugin.identity(chosen)).isEqualTo("c0123abcd,general,release");
        assertThat(plugin.displayName(chosen)).isEqualTo("C0123ABCD, #general, #release");
        Map<String, String> same = ConfigValidator.validate(SlackConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("channels", "general\nC0123ABCD\nrelease", "exclude", "random"));
        assertThat(plugin.identity(same)).isEqualTo(plugin.identity(chosen));

        Map<String, String> all = ConfigValidator.validate(SlackConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(plugin.identity(all)).isEqualTo("*");
        assertThat(plugin.displayName(all)).isEqualTo("All public channels");
        assertThatThrownBy(() -> ConfigValidator.validate(SlackConnectorPlugin.DESCRIPTOR.fields(), Map.of("days", 0)))
                .hasMessage("Days of history must be between 1 and 36500.");
        assertThat(SlackConnectorPlugin.DESCRIPTOR.capabilities().shareable()).isFalse();
        assertThat(SlackConnectorPlugin.DESCRIPTOR.capabilities().incremental()).isTrue();
    }

    @Test
    @DisplayName("Slack's ts reads as an instant to the microsecond, and back")
    void timestamps() {
        Instant at = SlackSession.instant("1696150800.000100");
        assertThat(at).isEqualTo(Instant.ofEpochSecond(1696150800L, 100_000));
        assertThat(SlackSession.ts(at)).isEqualTo("1696150800.000100");
        assertThat(SlackSession.instant("not a ts")).isNull();
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
