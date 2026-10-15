package ai.proxyma.it.app;

import ai.proxyma.it.harness.Chat;
import ai.proxyma.it.harness.Corpus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a model call cost, and who it was billed to.
 *
 * <p>The per-call ledger's write rules and its scope are pinned as unit tests. What those cannot
 * reach is whether a call LANDS in it after a real turn through the real gateway - and that is the
 * half that has gone wrong: {@code recordSpend}'s own javadoc claimed for months that it could not
 * be avoided, while native rerank and the embedding-cosine tier both avoided it. A ledger with a
 * hole in it looks exactly like a ledger.
 */
@DisplayName("Usage: what a call cost and who was billed")
class UsageAndCostIT extends DesktopJourney {

    @Test
    @DisplayName("a chat turn is recorded in the per-call ledger")
    void aChatTurnIsLedgered() {
        int before = ledgerSize();
        Chat.send(app, "@@tokens 1000/250 @@text ledgered").succeeded();

        assertThat(ledgerSize())
                .as("the turn produced at least one ledger row")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("the recorded call names the service that made it and the model it used")
    void theLedgerRowNamesItsCaller() {
        Chat.send(app, "@@text attributed").succeeded();
        JsonNode calls = api().get("/api/v1/usage/calls?days=1&size=50").ok().json();

        // Every gateway call is billed to the LlmCaller it was given - a service and a subject,
        // passed explicitly rather than read from the thread. A row that cannot say which service
        // spent the money is a number with no owner, and the whole page is then only a total.
        assertThat(calls.path("byService").toString())
                .as("the ledger groups by service: %s", calls.path("byService"))
                .isNotEqualTo("{}");
        assertThat(calls.path("byModel").toString()).contains("fake-chat");
    }

    @Test
    @DisplayName("on desktop every call is filed under the one local user")
    void desktopFilesEverythingUnderOneSubject() {
        Chat.send(app, "@@text local").succeeded();
        JsonNode calls = api().get("/api/v1/usage/calls?days=1&size=50").ok().json();

        // "all", NOT "mine" - and that is correct rather than a leak. Desktop has one user, so
        // the viewer sees everyone and everyone is them; the ledger files every call under
        // SpanSubject.LOCAL and there is nothing to attribute between. An earlier draft of this
        // test asserted "mine" on the assumption that a single-user edition must be the narrow
        // case, and it is the opposite.
        //
        // The assertion that carries the security weight lives in ServerAuthIT, where there ARE
        // two people: a user reads their own calls and an admin reads everyone's, decided from
        // the authenticated principal and never from a request parameter.
        assertThat(calls.path("scope").asText()).isEqualTo("all");
    }

    @Test
    @DisplayName("indexing is billed too, not only chat")
    void embeddingSpendIsRecorded() {
        Path folder = Corpus.write();
        String id = Corpus.registerFolder(app, folder);
        try {
            Corpus.awaitIndexed(app, id, Corpus.DOCUMENTS.size());

            JsonNode calls = api().get("/api/v1/usage/calls?days=1&size=200").ok().json();
            // Embedding a corpus is frequently the largest single cost a deployment incurs, and
            // it happens in the background where nobody is watching a spinner. A ledger that
            // recorded only interactive chat would under-report exactly the spend an operator
            // most needs to see coming.
            assertThat(calls.path("byModel").toString())
                    .as("the embedding model appears in the ledger: %s", calls.path("byModel"))
                    .contains("fake-embed");
        } finally {
            api().delete("/api/v1/indexing/" + id);
        }
    }

    @Test
    @DisplayName("usage totals and chat cost are readable")
    void theUsagePagesAnswer() {
        Chat.send(app, "@@tokens 4321/1234 @@text priced").succeeded();

        assertThat(api().get("/api/v1/usages").status()).isEqualTo(200);
        assertThat(api().get("/api/v1/usage/chat-cost").status()).isEqualTo(200);
    }

    private static int ledgerSize() {
        return api().get("/api/v1/usage/calls?days=1&size=1").ok()
                .json().path("totalItems").asInt();
    }
}
