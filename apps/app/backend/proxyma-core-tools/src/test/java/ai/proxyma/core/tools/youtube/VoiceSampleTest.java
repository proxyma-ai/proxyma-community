package ai.proxyma.core.tools.youtube;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WHAT THE CHANNEL'S VOICE IS LEARNED FROM (2026-11-23, the maintainer: "the voice is not learned as good as I expected").
 * His channel's newest 100 replies were 60 emoji alone and, of the 25 with words, 24 the same thanks to one viewer's
 * poems - so the call had nothing to learn the forms of address from. Of everything read: an identical reply once with
 * how often it was sent, at most a few replies with words a viewer, the replies with words first, a sample of emoji-only
 * ones - and how many of each there were.
 */
class VoiceSampleTest {

    private static Map<String, Object> pair(String viewer, String reply, String at) {
        return Map.of("author", "@" + viewer, "authorChannelId", "UC" + viewer, "comment", "a comment", "reply", reply,
                "repliedAt", at);
    }

    @Test
    @DisplayName("an identical reply once with how often it was sent; replies with words first, a few a viewer; emoji alone a sample; and how many of each were read")
    void sampled() {
        List<Map<String, Object>> replies = new ArrayList<>();
        // Newest first, as channelReplies returns them.
        for (int i = 0; i < 24; i++) replies.add(pair("ThaoThu", "@ThaoThu Cam on ban nhieu hen \uD83D\uDE0A", "2026-11-10T12:" + (59 - i) + ":00Z"));
        for (int i = 0; i < 8; i++) replies.add(pair("Chatty", "Reply number " + i + " hen", "2026-11-09T10:0" + i + ":00Z"));
        replies.add(pair("Tuan", "Theo a thi key F pho bien hon e hen", "2026-11-08T10:00:00Z"));
        for (int i = 0; i < 40; i++) replies.add(pair("Fan" + i, i % 2 == 0 ? "\u2764\uFE0F" : "\uD83E\uDD70",
                "2026-11-07T10:" + (10 + i) + ":00Z"));
        Map<String, Object> read = Map.of("success", true, "replies", replies, "scanned", 2000);

        Map<String, Object> sample = YouTubeCommentSync.voiceSample(read, 10);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shown = (List<Map<String, Object>>) sample.get("replies");
        List<Map<String, Object>> words = shown.stream().filter(p -> YouTubeCommentSync.hasWords((String) p.get("reply"))).toList();
        assertThat(words).as("the 24 identical thanks are one, with its count")
                .filteredOn(p -> String.valueOf(p.get("reply")).contains("Cam on ban")).singleElement()
                .satisfies(p -> assertThat(p).containsEntry("sameReplyTimes", 24));
        assertThat(words).as("at most five from one viewer").filteredOn(p -> "UCChatty".equals(p.get("authorChannelId")))
                .hasSize(YouTubeCommentSync.VOICE_PER_VIEWER);
        assertThat(words).extracting(p -> p.get("reply")).contains("Theo a thi key F pho bien hon e hen");
        assertThat(shown).as("seven with words (one thanks, five of Chatty's, Tuan's), then the two emoji replies, once each")
                .hasSize(9);
        assertThat(YouTubeCommentSync.voiceSample(read, 6).get("replies")).as("words first, up to the limit; no room, no emoji")
                .asList().hasSize(6).allSatisfy(p -> assertThat(YouTubeCommentSync.hasWords(
                        (String) ((Map<?, ?>) p).get("reply"))).isTrue());
        assertThat(shown).extracting(p -> String.valueOf(p.get("repliedAt"))).isSortedAccordingTo((a, b) -> b.compareTo(a));

        @SuppressWarnings("unchecked")
        Map<String, Object> about = (Map<String, Object>) sample.get("about");
        assertThat(about).containsEntry("repliesRead", 73).containsEntry("threadsScanned", 2000).containsEntry("withWords", 33)
                .containsEntry("emojiOnly", 40).containsEntry("shown", 9).containsEntry("identicalRepliesShownOnce", 23 + 38)
                .containsEntry("from", "2026-11-07T10:49:00Z").containsEntry("to", "2026-11-10T12:59:00Z");

        Map<String, Object> roomy = YouTubeCommentSync.voiceSample(read, 150);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) roomy.get("replies");
        assertThat(all.stream().filter(p -> !YouTubeCommentSync.hasWords((String) p.get("reply"))).count())
                .as("emoji alone: identical ones once, at most a sample").isLessThanOrEqualTo(YouTubeCommentSync.VOICE_EMOJI_MAX);
        assertThat(YouTubeCommentSync.voiceSample(null, 10)).isNull();
    }

    @Test
    @DisplayName("a reply is words once @names are left out; the same reply whoever it was sent to")
    void wordsAndSameness() {
        assertThat(YouTubeCommentSync.hasWords("@Lan \u2764\uFE0F")).isFalse();
        assertThat(YouTubeCommentSync.hasWords("\uD83D\uDE0A\uD83D\uDE0A")).isFalse();
        assertThat(YouTubeCommentSync.hasWords("Cam on e hen \uD83D\uDE0A")).isTrue();
        assertThat(YouTubeCommentSync.sameReplyKey("@Lan  Cam on ban  hen")).isEqualTo(YouTubeCommentSync.sameReplyKey("@Minh Cam on ban hen"));
        assertThat(YouTubeCommentSync.sameReplyKey("\u2764\uFE0F")).isEqualTo(YouTubeCommentSync.sameReplyKey("\u2764"));
    }
}
