package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.testing.LoopbackApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A refusal says why (2026-11-03: a YouTube channel's every comment read failed with "refused access"
 * while Google's 403 named commentsDisabled): a client given a RefusalReason ends the sentence with the
 * service's own reason - one short line, bounded, with nothing credential-shaped in it - and a client
 * without one keeps the plain sentence, never a body.
 */
class RefusalReasonTest {

    private LoopbackApi api;

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ApiClient client(RefusalReason reason) {
        ApiClient.Builder b = ConnectorHttp.direct(w -> { }).client(api.baseUrl()).service("YouTube").auth(ApiAuth.bearer("tok"));
        if (reason != null) b.refusalReason(reason);
        return b.build();
    }

    @Test
    @DisplayName("a 403 ends with Google's reason and message, the old sentence first; without a reader it is the old sentence alone")
    void forbiddenSaysWhy() {
        api.on("GET", "/threads").status(403).json("{'error':{'code':403,'message':'The video identified by the "
                + "<code>videoId</code> parameter has disabled comments.','errors':[{'reason':'commentsDisabled',"
                + "'domain':'youtube.commentThread'}]}}");

        assertThatThrownBy(() -> client(RefusalReason.GOOGLE).getJson("threads"))
                .isInstanceOfSatisfying(ConnectorHttpException.class, e -> assertThat(e.status()).isEqualTo(403))
                .hasMessage("YouTube refused access with the connection's credential. Check that its account may read this. "
                        + "YouTube said: commentsDisabled (The video identified by the <code>videoId</code> parameter has "
                        + "disabled comments.)");
        assertThatThrownBy(() -> client(null).getJson("threads")).hasMessage(
                "YouTube refused access with the connection's credential. Check that its account may read this.");
    }

    @Test
    @DisplayName("another 4xx says why too; a sign-in refusal, a missing address and a body with no reason do not")
    void otherRefusals() {
        api.on("GET", "/bad").status(400).json("{'error':{'message':'Invalid value for maxResults',"
                + "'errors':[{'reason':'invalidParameter'}]}}");
        api.on("GET", "/signin").status(401).json("{'error':{'message':'Invalid Credentials','errors':[{'reason':'authError'}]}}");
        api.on("GET", "/none").status(403).json("{'message':'Forbidden'}");
        api.on("GET", "/page").status(403).text("<html>Sign in</html>");
        ApiClient client = client(RefusalReason.GOOGLE);

        assertThatThrownBy(() -> client.getJson("bad")).hasMessage(
                "YouTube refused the request (400). YouTube said: invalidParameter (Invalid value for maxResults)");
        assertThatThrownBy(() -> client.getJson("signin")).hasMessage(
                "YouTube refused the connection's credential. Replace it, or sign in again, in Settings > Connections.");
        assertThatThrownBy(() -> client.getJson("none")).hasMessage(
                "YouTube refused access with the connection's credential. Check that its account may read this.");
        assertThatThrownBy(() -> client.getJson("page")).hasMessage(
                "YouTube refused access with the connection's credential. Check that its account may read this.");
    }

    @Test
    @DisplayName("the reason is one line of at most 200 characters, with tokens, keys and server paths taken out")
    void reasonIsCleaned() {
        api.on("GET", "/leaky").status(403).json("{'error':{'message':'Token ya29.a0AfH6SMBx-secret was refused; "
                + "Authorization: Bearer abc.def; see https://example.com/x?key=AIzaSyD-1234&a=b and "
                + "/home/runner/proxyma/data/x.db\\nnext line','details':[{'reason':'ACCESS_TOKEN_SCOPE_INSUFFICIENT'}]}}");
        String message = catchMessage(() -> client(RefusalReason.GOOGLE).getJson("leaky"));
        assertThat(message).contains("YouTube said: ACCESS_TOKEN_SCOPE_INSUFFICIENT (Token [hidden] was refused;")
                .contains("Bearer [hidden]").contains("key=[hidden]").contains(".../x.db next line")
                .doesNotContain("ya29.").doesNotContain("abc.def").doesNotContain("AIzaSyD").doesNotContain("/home/runner")
                .doesNotContain("\n");

        assertThat(RefusalReason.clean("x".repeat(10) + " " + "word ".repeat(100))).hasSize(RefusalReason.MAX_CHARS).endsWith("...");
        assertThat(RefusalReason.clean("pxm_" + "0123456789abcdef".repeat(4) + " and " + "A1b2".repeat(10)))
                .isEqualTo("[hidden] and [hidden]");
        assertThat(RefusalReason.clean("  \n ")).isNull();
        assertThat(RefusalReason.clean(null)).isNull();
    }

    @Test
    @DisplayName("Google's reasons are read from errors[] and details[] alike")
    void googleReasons() {
        api.on("GET", "/both").status(403).json("{'error':{'errors':[{'reason':'insufficientPermissions'}],"
                + "'details':[{'reason':'ACCESS_TOKEN_SCOPE_INSUFFICIENT'}]}}");
        ApiResponse r = client(null).get("both");
        assertThat(GoogleError.reasons(r)).containsExactly("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT");
        assertThat(GoogleError.has(r, "nope", "ACCESS_TOKEN_SCOPE_INSUFFICIENT")).isTrue();
        assertThat(GoogleError.describe(r)).isEqualTo("insufficientPermissions");
    }

    private static String catchMessage(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        throw new AssertionError("no failure");
    }
}
