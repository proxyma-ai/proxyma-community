package ai.proxyma.core.tools.youtube;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A channel's picture, downloaded by the server and carried inline (2026-11-09): only from YouTube's picture hosts over
 * https, only an image type a page draws safely, never past the size limit.
 */
class YouTubeAvatarTest {

    @Test
    @DisplayName("the 240px picture, else the 88px one; none without")
    void url() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(YouTubeAvatar.url(json.readTree("{\"thumbnails\":{\"default\":{\"url\":\"https://yt3.ggpht.com/a=s88\"},"
                + "\"medium\":{\"url\":\"https://yt3.ggpht.com/a=s240\"}}}"))).isEqualTo("https://yt3.ggpht.com/a=s240");
        assertThat(YouTubeAvatar.url(json.readTree("{\"thumbnails\":{\"default\":{\"url\":\"https://yt3.ggpht.com/a=s88\"}}}")))
                .isEqualTo("https://yt3.ggpht.com/a=s88");
        assertThat(YouTubeAvatar.url(json.readTree("{}"))).isNull();
        assertThat(YouTubeAvatar.url(null)).isNull();
    }

    @Test
    @DisplayName("fetched only from YouTube's picture hosts, over https")
    void hosts() {
        assertThat(YouTubeAvatar.allowed("https://yt3.ggpht.com/a=s240")).isTrue();
        assertThat(YouTubeAvatar.allowed("https://yt3.googleusercontent.com/a=s240")).isTrue();
        assertThat(YouTubeAvatar.allowed("http://yt3.ggpht.com/a")).as("not https").isFalse();
        assertThat(YouTubeAvatar.allowed("https://evil.example/a")).isFalse();
        assertThat(YouTubeAvatar.allowed("https://user@yt3.ggpht.com/a")).isFalse();
        assertThat(YouTubeAvatar.allowed("https://yt3.ggpht.com.evil.example/a")).isFalse();
        assertThat(YouTubeAvatar.allowed("not a url")).isFalse();
        assertThat(YouTubeAvatar.inline(null, "https://evil.example/a")).isNull();
    }

    @Test
    @DisplayName("an image a page draws safely, within the limit, as a data: address")
    void encode() {
        assertThat(YouTubeAvatar.encode("image/jpeg", new byte[] {1, 2, 3})).isEqualTo("data:image/jpeg;base64,AQID");
        assertThat(YouTubeAvatar.encode("image/png; charset=binary", new byte[] {1})).startsWith("data:image/png;base64,");
        assertThat(YouTubeAvatar.encode("image/svg+xml", new byte[] {1})).as("SVG can carry script").isNull();
        assertThat(YouTubeAvatar.encode("text/html", new byte[] {1})).isNull();
        assertThat(YouTubeAvatar.encode("image/jpeg", new byte[YouTubeAvatar.MAX_BYTES + 1])).isNull();
        assertThat(YouTubeAvatar.encode("image/jpeg", new byte[0])).isNull();
        assertThat(YouTubeAvatar.encode(null, new byte[] {1})).isNull();
    }
}
