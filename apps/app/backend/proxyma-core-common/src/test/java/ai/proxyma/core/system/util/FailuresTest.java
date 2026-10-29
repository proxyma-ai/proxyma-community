package ai.proxyma.core.system.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Failures#describe} produces the sentence a user is shown when something fails, so its own
 * failures are the kind nobody sees until the worst moment: it runs inside error handlers.
 *
 * The walk used to take the outermost message and the ROOT message only, which dropped whatever
 * sat between them - and the reason usually sits between them, because a provider SDK wraps a
 * status error in an operation error and the transport wraps that again. It also only refused a
 * cause pointing at ITSELF, so a two-element cycle spun forever inside a handler.
 */
class FailuresTest {

    @Nested
    @DisplayName("An HTTP error's quoted HTML body is not part of the sentence")
    class HtmlBody {

        @Test
        void aNotFoundPageSaysNotFoundAndNotItsMarkup() {
            Throwable http = new IllegalStateException(
                    "404 Not Found: \"<!DOCTYPE html><html><head><title>Page not found</title></head></html>\"");
            Throwable outer = new RuntimeException(
                    "The page at https://docs.example.com/a.pdf no longer exists (404 Not Found).", http);

            assertEquals("The page at https://docs.example.com/a.pdf no longer exists (404 Not Found).",
                    Failures.describe(outer));
        }

        @Test
        void theStatusBeforeTheBodyIsKept() {
            assertEquals("500 Internal Server Error",
                    Failures.withoutHtmlBody("500 Internal Server Error: \"<html><body>oops</body></html>\""));
            assertEquals("a < b and <b>bold</b>", Failures.withoutHtmlBody("a < b and <b>bold</b>"));
            assertEquals("<html>only</html>", Failures.withoutHtmlBody("<html>only</html>"));
        }
    }

    @Nested
    @DisplayName("The middle of the chain is where the reason lives")
    class MiddleOfChain {

        @Test
        void keepsAMessageThatIsNeitherOutermostNorRoot() {
            Throwable root = new java.net.SocketException("Connection reset");
            Throwable middle = new IllegalStateException("401 Unauthorized - check the access token", root);
            Throwable outer = new RuntimeException("Failed to list Jira projects", middle);

            String described = Failures.describe(outer);

            assertTrue(described.contains("401 Unauthorized"),
                    "the status is the actionable part and it is two levels down: " + described);
            assertTrue(described.contains("Failed to list Jira projects"));
            assertTrue(described.contains("Connection reset"));
        }

        @Test
        void doesNotRepeatAMessageAWrapperQuotedVerbatim() {
            Throwable cause = new IllegalArgumentException("Project key must not be blank");
            Throwable wrapper = new RuntimeException("Project key must not be blank", cause);

            assertEquals("Project key must not be blank", Failures.describe(wrapper));
        }

        @Test
        void fallsBackToTheClassNameWhenNothingCarriesAMessage() {
            assertEquals("NullPointerException", Failures.describe(new NullPointerException()));
        }
    }

    @Nested
    @DisplayName("Bounded walks - an error handler must not hang or flood")
    class Bounded {

        @Test
        @Timeout(5)
        void terminatesOnATwoElementCycle() {
            Throwable a = new RuntimeException("outer");
            Throwable b = new RuntimeException("inner", a);
            a.initCause(b);

            String described = Failures.describe(a);

            assertTrue(described.contains("outer"));
            assertTrue(described.contains("inner"));
        }

        @Test
        @Timeout(5)
        void terminatesOnASelfReferencingCause() {
            // initCause() refuses `this`, so a self-reference has to come from a subclass that
            // answers getCause() with itself - which is exactly how the wild ones arrive.
            Throwable selfCausing = new RuntimeException("loops forever") {
                @Override public synchronized Throwable getCause() { return this; }
            };

            assertTrue(Failures.describe(selfCausing).contains("loops forever"));
        }

        @Test
        void truncatesAnHtmlErrorPageRatherThanPassingItOn() {
            String page = "<html>" + "x".repeat(4000) + "</html>";

            String described = Failures.describe(new RuntimeException(page));

            assertTrue(described.length() < page.length(), "a 4 KB page must not reach a chat bubble whole");
            assertTrue(described.endsWith(" ... [truncated]"), described.substring(described.length() - 40));
        }
    }

    @Test
    void nullIsAnAnswerRatherThanAnException() {
        assertEquals("Unknown error", Failures.describe(null));
    }

    @Test
    void callerFallbackReplacesABareClassName() {
        assertEquals("Delegation failed", Failures.describe(new NullPointerException(), "Delegation failed"));
        assertEquals("real reason", Failures.describe(new RuntimeException("real reason"), "Delegation failed"));
    }

    @Nested
    @DisplayName("A file-system fault says what happened to the file, not just which file")
    class FileSystemFaults {

        // getMessage() of both of these is the bare path - the reason is the TYPE, and a user shown
        // "C:/data/q3.xlsx" alone could not tell a locked file from a deleted one.
        @Test
        void accessDeniedSaysSo() {
            assertEquals("C:/data/q3.xlsx - access denied",
                    Failures.describe(new java.nio.file.AccessDeniedException("C:/data/q3.xlsx")));
        }

        @Test
        void aMissingFileSaysItIsGone() {
            assertEquals("C:/data/q3.xlsx - it no longer exists",
                    Failures.describe(new java.nio.file.NoSuchFileException("C:/data/q3.xlsx")));
        }

        @Test
        void anyOtherCarriesItsReason() {
            assertEquals("C:/data/q3.xlsx - The process cannot access the file",
                    Failures.describe(new java.nio.file.FileSystemException(
                            "C:/data/q3.xlsx", null, "The process cannot access the file")));
        }

        @Test
        void theReasonSurvivesBeingWrapped() {
            Throwable wrapped = new RuntimeException("Reading a folder failed",
                    new java.nio.file.AccessDeniedException("C:/data"));

            assertEquals("Reading a folder failed: C:/data - access denied", Failures.describe(wrapped));
        }
    }

    @Nested
    @DisplayName("A network fault leads with what it probably means")
    class NetworkFaults {

        // Netty's own class is not on this module's path; the check is by simple name.
        static class ProxyConnectException extends java.io.IOException {
            ProxyConnectException(String m) { super(m); }
        }

        @Test
        void aBrokenTlsConnectionPointsAtTheProxy() {
            Throwable tls = new javax.net.ssl.SSLException("failure when writing TLS control frames");
            String described = Failures.describe(new RuntimeException("Request to POST https://api.anthropic.com/v1/messages", tls));

            assertTrue(described.startsWith("The secure connection was cut off"), described);
            assertTrue(described.contains("failure when writing TLS control frames"), "the detail stays: " + described);
        }

        @Test
        void aProxyThatDidNotConnectSaysSo() {
            Throwable proxy = new ProxyConnectException("http, none, /127.0.0.1:3128 => api.anthropic.com:443, timeout");
            Throwable tls = new javax.net.ssl.SSLException("failure when writing TLS control frames", proxy);

            assertTrue(Failures.describe(tls).startsWith("Could not connect through the proxy"), Failures.describe(tls));
        }

        @Test
        void aNameNioCouldNotResolveIsNotAClassName() {
            // As Reactor Netty reports it: no message anywhere in the chain.
            Throwable dns = new RuntimeException(null, new java.nio.channels.UnresolvedAddressException());

            assertEquals("The server's name could not be resolved. Check the address and your network.",
                    Failures.describe(dns));
            // With a message on the way, the lead comes first and the detail stays.
            String described = Failures.describe(new RuntimeException("Could not connect to llm.corp",
                    new java.nio.channels.UnresolvedAddressException()));
            assertTrue(described.startsWith("The server's name could not be resolved"), described);
            assertTrue(described.contains("Could not connect to llm.corp"), described);
        }

        @Test
        void anOrdinaryErrorGetsNoLead() {
            assertEquals("Bad things", Failures.describe(new IllegalStateException("Bad things")));
        }
    }
    @Nested
    @DisplayName("Reactor's block timeout is worded for a person")
    class BlockTimeout {

        @Test
        void tenMinutesInNanosecondsBecomesMinutes() {
            Throwable root = new java.util.concurrent.TimeoutException(
                    "Timeout on blocking read for 600000000000 NANOSECONDS");
            Throwable outer = new IllegalStateException(
                    "Timeout on blocking read for 600000000000 NANOSECONDS", root);

            String described = Failures.describe(outer);

            assertTrue(described.startsWith("No complete answer arrived within 10 minutes"), described);
            assertTrue(!described.contains("NANOSECONDS"), described);
            assertEquals(described.indexOf("10 minutes"), described.lastIndexOf("10 minutes"),
                    "the wrapper and its cause say the same thing once: " + described);
        }

        @Test
        void shortWaitsAreSeconds() {
            assertTrue(Failures.blockTimeout("Timeout on blocking read for 30000000000 NANOSECONDS")
                    .contains("within 30 seconds"));
        }

        @Test
        void otherMessagesAreUntouched() {
            assertEquals("Read timed out", Failures.blockTimeout("Read timed out"));
        }
    }

    /** Stands in for Spring's WebClientResponseException: its message leaves the body out. */
    static final class StreamedHttpError extends RuntimeException {
        private final String body;
        StreamedHttpError(String message, String body) {
            super(message);
            this.body = body;
        }
        public String getResponseBodyAsString() {
            return body;
        }
    }

    @Nested
    @DisplayName("An HTTP error's response body says why, when its message does not")
    class ResponseBody {

        private static final String STREAMED = "400 Bad Request from POST http://localhost:11434/api/chat";

        @Test
        @DisplayName("an Ollama model without tool support is named, with what to do about it")
        void ollamaWithoutTools() {
            String said = Failures.describe(new RuntimeException("Aggregation Error", new StreamedHttpError(STREAMED,
                    "{\"error\":\"registry.ollama.ai/library/gemma3:4b does not support tools\"}")));
            assertTrue(said.startsWith("This model cannot use tools"), said);
            assertTrue(said.contains("registry.ollama.ai/library/gemma3:4b does not support tools"), said);
            assertTrue(said.contains(STREAMED), said);
        }

        @Test
        @DisplayName("an embedding model picked for chat says so")
        void ollamaEmbeddingModel() {
            String said = Failures.describe(new StreamedHttpError(STREAMED,
                    "{\"error\":\"\\\"nomic-embed-text\\\" does not support chat\"}"));
            assertTrue(said.startsWith("This is an embedding model"), said);
            assertTrue(said.contains("\"nomic-embed-text\" does not support chat"), said);
        }

        @Test
        @DisplayName("an OpenAI-style nested error gives its message, plain text is kept, HTML and blanks are not")
        void shapes() {
            assertEquals(STREAMED + ": The model `gpt-9` does not exist", Failures.describe(new StreamedHttpError(STREAMED,
                    "{\"error\":{\"message\":\"The model `gpt-9` does not exist\",\"type\":\"invalid_request_error\"}}")));
            assertEquals(STREAMED + ": upstream said no", Failures.describe(new StreamedHttpError(STREAMED, "upstream said no")));
            assertEquals(STREAMED, Failures.describe(new StreamedHttpError(STREAMED, "<html><body>Bad</body></html>")));
            assertEquals(STREAMED, Failures.describe(new StreamedHttpError(STREAMED, "  ")));
        }

        @Test
        @DisplayName("a body the message already quotes is not said twice")
        void notTwice() {
            String body = "{\"error\":\"bad thing\"}";
            assertEquals("400 - " + body, Failures.describe(new StreamedHttpError("400 - " + body, body)));
        }
    }
}
