package ai.proxyma.core.registry.ingest;

import ai.proxyma.core.registry.model.IngestedDocument;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ingestion API end to end, through its controllers, with the sync engine, the store and the
 * repositories that ship (IngestHarness): who may push, what a push may be, and that what is pushed
 * is indexed - and deleted - by the same path every source's items take.
 */
class IngestApiTest {

    private IngestHarness h;
    private WebTestClient client;
    private RegisteredConnector source;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        h = new IngestHarness();
        client = WebTestClient.bindToController(new IngestController(h.ingest, h.tokens),
                new IngestTokenController(h.tokens)).build();
        source = h.source("Release notes", null);
        token = h.tokens.create(source.getId(), null, "script").token();
    }

    @AfterEach
    void tearDown() throws Exception {
        h.close();
    }

    private String documents(RegisteredConnector s) {
        return "/api/v1/ingest/sources/" + s.getId() + "/documents";
    }

    private WebTestClient.ResponseSpec push(String bearer, Map<String, Object> body) {
        WebTestClient.RequestBodySpec spec = client.post().uri(documents(source)).contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        return spec.bodyValue(body).exchange();
    }

    private static Map<String, Object> doc(String id, String title, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("text", text);
        return m;
    }

    @Nested
    @DisplayName("who may push")
    class Auth {

        @Test
        @DisplayName("no token is 401, and nothing is stored")
        void noToken() {
            push(null, doc("a", "A", "text")).expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.error").value(e -> assertThat((String) e).contains("ingest token"));
            assertThat(h.documents.count()).isZero();
        }

        @Test
        @DisplayName("a token of another source is 403 here, and works on its own source")
        void wrongSourcesToken() {
            RegisteredConnector other = h.source("Other", null);
            String otherToken = h.tokens.create(other.getId(), null, null).token();

            push(otherToken, doc("a", "A", "text")).expectStatus().isForbidden();
            client.get().uri(documents(source)).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                    .exchange().expectStatus().isForbidden();
            client.delete().uri(documents(source) + "/a").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                    .exchange().expectStatus().isForbidden();
            client.get().uri(documents(other)).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                    .exchange().expectStatus().isOk();
            assertThat(h.documents.count()).isZero();
        }

        @Test
        @DisplayName("a plain pxm_ token - an MCP token, the install's token - is no ingest token: 401")
        void plainTokenRefused() {
            push("pxm_" + "ab".repeat(32), doc("a", "A", "text")).expectStatus().isUnauthorized();
            push("not-even-shaped-like-one", doc("a", "A", "text")).expectStatus().isUnauthorized();
            assertThat(h.documents.count()).isZero();
        }

        @Test
        @DisplayName("a revoked token opens nothing from the next request on")
        void revokedToken() {
            UUID id = UUID.fromString((String) h.tokens.list(source.getId(), null).get(0).get("id"));
            assertThat(h.tokens.revoke(source.getId(), id, null)).isTrue();
            push(token, doc("a", "A", "text")).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("a token names its source: any other id is 403, and an id that is not one is 404")
        void tokenNamesItsSource() {
            client.get().uri("/api/v1/ingest/sources/" + UUID.randomUUID() + "/documents")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange().expectStatus().isForbidden();
            client.get().uri("/api/v1/ingest/sources/not-a-uuid/documents")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange().expectStatus().isNotFound();
        }

        @Test
        @DisplayName("tokens are made, listed and revoked through the source's page, shown whole once")
        void tokenManagement() {
            Map<?, ?> created = client.post().uri("/api/v1/indexing/" + source.getId() + "/ingest/tokens")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("label", "n8n"))
                    .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            String raw = (String) created.get("token");
            assertThat(raw).matches("pxm_[0-9a-f]{64}");
            assertThat(created.get("tokenPrefix")).isEqualTo(raw.substring(0, 12));

            Map<?, ?> page = client.get().uri("/api/v1/indexing/" + source.getId() + "/ingest").exchange()
                    .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            assertThat(page.get("endpoint")).isEqualTo("/api/v1/ingest/sources/" + source.getId() + "/documents");
            assertThat(page.toString()).doesNotContain(raw).contains("n8n");
            // Only its hash is stored.
            assertThat(h.tokenRows.findAll()).noneMatch(t -> t.getTokenHash().contains(raw.substring(4)));

            push(raw, doc("a", "A", "text")).expectStatus().isOk();
        }

        @Test
        @DisplayName("a source holds at most ten tokens")
        void tokenCap() {
            for (int i = 1; i < IngestTokenService.MAX_TOKENS_PER_SOURCE; i++) h.tokens.create(source.getId(), null, null);
            assertThatThrownBy(() -> h.tokens.create(source.getId(), null, null))
                    .isInstanceOf(IngestRefusedException.class).hasMessageContaining("at most 10 tokens");
        }
    }

    @Nested
    @DisplayName("Enterprise Server: a source's owner, and nobody else")
    class Ownership {

        private static final String ANNA = "11111111-1111-1111-1111-111111111111";
        private static final String BAO = "22222222-2222-2222-2222-222222222222";

        @Test
        @DisplayName("only the owner makes, lists or revokes a token - a source shared with you lends no writing")
        void ownerOnly() {
            RegisteredConnector annas = h.source("Anna's notes", ANNA);
            annas.setShared(true);
            h.sources.save(annas);

            assertThatThrownBy(() -> h.tokens.create(annas.getId(), BAO, null))
                    .isInstanceOfSatisfying(IngestTokenService.IngestAuthException.class, e -> assertThat(e.status()).isEqualTo(403));
            assertThatThrownBy(() -> h.tokens.list(annas.getId(), BAO))
                    .isInstanceOfSatisfying(IngestTokenService.IngestAuthException.class, e -> assertThat(e.status()).isEqualTo(403));
            String annasToken = h.tokens.create(annas.getId(), ANNA, null).token();
            assertThat(h.tokenRows.findByConnectorIdOrderByCreatedAtDesc(annas.getId()))
                    .allMatch(t -> ANNA.equals(t.getUserId()));
            UUID tokenId = h.tokenRows.findByConnectorIdOrderByCreatedAtDesc(annas.getId()).get(0).getId();
            assertThatThrownBy(() -> h.tokens.revoke(annas.getId(), tokenId, BAO))
                    .isInstanceOf(IngestTokenService.IngestAuthException.class);

            assertThat(h.tokens.authenticate(annas.getId(), "Bearer " + annasToken).getId()).isEqualTo(annas.getId());
        }

        @Test
        @DisplayName("a token stops working when its maker no longer owns the source, or can no longer sign in")
        void ownerMustStillOwn() {
            RegisteredConnector annas = h.source("Anna's notes", ANNA);
            String annasToken = h.tokens.create(annas.getId(), ANNA, null).token();

            when(h.users.isActive(ANNA)).thenReturn(false);
            assertThatThrownBy(() -> h.tokens.authenticate(annas.getId(), "Bearer " + annasToken))
                    .isInstanceOfSatisfying(IngestTokenService.IngestAuthException.class, e -> assertThat(e.status()).isEqualTo(401));
            when(h.users.isActive(ANNA)).thenReturn(true);

            annas.setUserId(BAO);
            h.sources.save(annas);
            assertThatThrownBy(() -> h.tokens.authenticate(annas.getId(), "Bearer " + annasToken))
                    .isInstanceOfSatisfying(IngestTokenService.IngestAuthException.class, e -> assertThat(e.status()).isEqualTo(403));
        }

        @Test
        @DisplayName("the agent's notes go only to the caller's own custom source - checked by the same pushSource")
        void pushSourceIsCustomOnly() {
            RegisteredConnector web = new RegisteredConnector();
            web.setConnectorType(ai.proxyma.core.connector.ConnectorType.WEB_PAGE);
            web.setName("site");
            web = h.sources.save(web);
            assertThat(h.ingest.pushSource(web.getId())).isNull();
            assertThat(h.ingest.pushSource(source.getId())).isNotNull();
        }
    }

    @Nested
    @DisplayName("pushing a document")
    class Upsert {

        @Test
        @DisplayName("a push creates the document and hands it to the sync engine as a queued item")
        void createsAndQueues() {
            Map<String, Object> d = doc("rn-1", "Release 2.0", "The **Nimbus** release adds offline mode.");
            d.put("url", "https://example.com/releases/2.0");
            d.put("author", "Anna");
            d.put("createdAt", "2026-10-01");
            d.put("metadata", Map.of("team", "Platform", "version", 2));

            push(token, d).expectStatus().isOk().expectBody()
                    .jsonPath("$.id").isEqualTo("rn-1").jsonPath("$.outcome").isEqualTo("created");

            TrackedDocument t = h.trackedItem(source, "rn-1");
            assertThat(t).isNotNull();
            assertThat(t.getStatus()).isEqualTo(TrackedDocument.Status.QUEUED);
            assertThat(t.getTitle()).isEqualTo("Release 2.0");
            assertThat(t.getSourceUrl()).isEqualTo("https://example.com/releases/2.0");
            assertThat(t.getSourceAuthor()).isEqualTo("Anna");
            assertThat(h.sources.findById(source.getId()).orElseThrow().getItemCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the same id again replaces it; the same content again changes nothing")
        void replacesByExternalId() throws Exception {
            push(token, doc("rn-1", "Release 2.0", "First words.")).expectStatus().isOk();
            String firstBody = h.documents.findByConnectorIdAndExternalId(source.getId(), "rn-1").orElseThrow().getStoredAs();

            push(token, doc("rn-1", "Release 2.0", "First words.")).expectBody().jsonPath("$.outcome").isEqualTo("unchanged");
            push(token, doc("rn-1", "Release 2.0.1", "Second words.")).expectBody().jsonPath("$.outcome").isEqualTo("replaced");

            assertThat(h.documents.count()).isEqualTo(1);
            IngestedDocument row = h.documents.findByConnectorIdAndExternalId(source.getId(), "rn-1").orElseThrow();
            assertThat(row.getTitle()).isEqualTo("Release 2.0.1");
            assertThat(h.ingest.readText(source, "rn-1")).contains("Second words.").doesNotContain("First words.");
            // The previous version's body is gone from the store.
            assertThat(Files.exists(h.dataDir.resolve("ingest").resolve(source.getId().toString().replace("-", ""))
                    .resolve(firstBody))).isFalse();
            assertThat(h.trackedItem(source, "rn-1").getTitle()).isEqualTo("Release 2.0.1");
        }

        @Test
        @DisplayName("GET lists the documents a page at a time, with where each one's indexing stands")
        void lists() {
            for (int i = 0; i < 23; i++) push(token, doc("d-" + i, "Doc " + i, "Text " + i)).expectStatus().isOk();
            client.get().uri(documents(source)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                    .expectStatus().isOk().expectBody()
                    .jsonPath("$.items.length()").isEqualTo(20)
                    .jsonPath("$.totalItems").isEqualTo(23)
                    .jsonPath("$.totalPages").isEqualTo(2)
                    .jsonPath("$.items[0].status").isEqualTo("QUEUED");
            client.get().uri(documents(source) + "?page=7").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange().expectBody().jsonPath("$.page").isEqualTo(1).jsonPath("$.items.length()").isEqualTo(3);
        }

        @Test
        @DisplayName("a file goes through the same store and is indexed by its own type")
        void file() {
            MultipartBodyBuilder form = new MultipartBodyBuilder();
            form.part("file", new ByteArrayResource("# Runbook\n\nRestart the **gateway** first.".getBytes(StandardCharsets.UTF_8)) {
                @Override public String getFilename() { return "runbook.md"; }
            });
            form.part("id", "runbook");
            form.part("url", "https://wiki.example.com/runbook");
            client.post().uri(documents(source)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(form.build()))
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.outcome").isEqualTo("created");

            IngestedDocument row = h.documents.findByConnectorIdAndExternalId(source.getId(), "runbook").orElseThrow();
            assertThat(row.getTitle()).isEqualTo("runbook.md");
            assertThat(row.getFileName()).isEqualTo("runbook.md");
            assertThat(row.getContentType()).isEqualTo("text/markdown");
            assertThat(h.trackedItem(source, "runbook").getContentType()).isEqualTo("text/markdown");
        }

        @Test
        @DisplayName("a file of a type nothing reads is refused, and no file at all is refused")
        void fileRefusals() {
            MultipartBodyBuilder exe = new MultipartBodyBuilder();
            exe.part("file", new ByteArrayResource(new byte[]{1, 2, 3}) {
                @Override public String getFilename() { return "tool.exe"; }
            });
            exe.part("id", "tool");
            client.post().uri(documents(source)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(exe.build()))
                    .exchange().expectStatus().isBadRequest();

            MultipartBodyBuilder none = new MultipartBodyBuilder();
            none.part("id", "nothing");
            client.post().uri(documents(source)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(none.build()))
                    .exchange().expectStatus().isBadRequest();
            assertThat(h.documents.count()).isZero();
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {

        @Test
        @DisplayName("text past a million characters is 413; a title, an id, a url or metadata out of bounds is 400")
        void documentLimits() {
            push(token, doc("big", "Big", "x".repeat(IngestDraft.MAX_TEXT_CHARS + 1))).expectStatus().isEqualTo(413);
            push(token, doc("long", "t".repeat(IngestedDocument.TITLE_MAX + 1), "text")).expectStatus().isBadRequest();
            push(token, doc("has/slash", "A", "text")).expectStatus().isBadRequest();
            push(token, doc("", "A", "text")).expectStatus().isBadRequest();
            Map<String, Object> badUrl = doc("u", "A", "text");
            badUrl.put("url", "file:///etc/passwd");
            push(token, badUrl).expectStatus().isBadRequest();
            Map<String, Object> manyMeta = doc("m", "A", "text");
            Map<String, Object> meta = new LinkedHashMap<>();
            for (int i = 0; i <= IngestDraft.MAX_METADATA_ENTRIES; i++) meta.put("k" + i, i);
            manyMeta.put("metadata", meta);
            push(token, manyMeta).expectStatus().isBadRequest();
            Map<String, Object> nested = doc("n", "A", "text");
            nested.put("metadata", Map.of("deep", Map.of("x", 1)));
            push(token, nested).expectStatus().isBadRequest();
            Map<String, Object> badDate = doc("d", "A", "text");
            badDate.put("updatedAt", "yesterday");
            push(token, badDate).expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.error").value(e -> assertThat((String) e).contains("ISO-8601"));
            assertThat(h.documents.count()).isZero();
        }

        @Test
        @DisplayName("a file past the upload limit is refused before it is stored")
        void fileLimit() throws Exception {
            Path spool = Files.createTempFile("ingest-limit-", ".md");
            try {
                Files.writeString(spool, "small");
                assertThatThrownBy(() -> h.ingest.upsertFile(source, new IngestDraft("f", null, null, null, null, null,
                        null, null, null), new IngestService.SpooledFile(spool, "f.md", IngestService.MAX_FILE_BYTES + 1)))
                        .isInstanceOf(IngestRefusedException.TooLarge.class);
            } finally {
                Files.deleteIfExists(spool);
            }
            // A request saying it is larger is refused by its length, before the form is read.
            var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest.post(documents(source))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(IngestService.MAX_FILE_BYTES * 2))
                            .contentType(MediaType.MULTIPART_FORM_DATA).build());
            var response = new IngestController(h.ingest, h.tokens).upsertFile(source.getId().toString(), exchange).block();
            assertThat(response.getStatusCode().value()).isEqualTo(413);
            // And a body that is no form at all is a 400 that says so.
            client.post().uri(documents(source)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data").bodyValue("")
                    .exchange().expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("a batch takes at most a hundred, is checked whole before anything is stored, and names the bad one")
        void batch() {
            List<Map<String, Object>> many = new ArrayList<>();
            for (int i = 0; i <= IngestService.MAX_BATCH; i++) many.add(doc("b-" + i, "B" + i, "text"));
            batch(many).expectStatus().isEqualTo(413);

            List<Map<String, Object>> oneBad = new ArrayList<>(List.of(doc("b-1", "B1", "one"), doc("b-2", "", "two"),
                    doc("b-3", "B3", "three")));
            batch(oneBad).expectStatus().isBadRequest().expectBody().jsonPath("$.index").isEqualTo(1);
            assertThat(h.documents.count()).as("nothing of a refused batch is stored").isZero();

            batch(List.of(doc("b-1", "B1", "one"), doc("b-1", "B1 again", "two"))).expectStatus().isBadRequest();

            batch(List.of(doc("b-1", "B1", "one"), doc("b-2", "B2", "two"))).expectStatus().isOk()
                    .expectBody().jsonPath("$.documents.length()").isEqualTo(2)
                    .jsonPath("$.documents[1].outcome").isEqualTo("created");
            assertThat(h.tracked.findByConnectorId(source.getId())).hasSize(2);
        }

        private WebTestClient.ResponseSpec batch(List<Map<String, Object>> docs) {
            return client.post().uri(documents(source) + "/batch").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("documents", docs)).exchange();
        }

        @Test
        @DisplayName("a source holds at most a hundred thousand documents")
        void sourceCap() {
            assertThat(IngestService.MAX_DOCUMENTS_PER_SOURCE).isEqualTo(100_000);
        }
    }

    @Nested
    @DisplayName("the hand-off: indexed and deleted by the path every source's items take")
    class HandOff {

        @Test
        @DisplayName("the drain indexes a pushed document into the keyword index and the vectors, with its link")
        void indexed() throws Exception {
            Map<String, Object> d = doc("rn-1", "Release 2.0", "The Nimbus release adds offline mode.");
            d.put("url", "https://example.com/releases/2.0");
            d.put("metadata", Map.of("team", "Platform"));
            push(token, d).expectStatus().isOk();

            // The item is saved COMPLETED and the source's counters are written just after it
            // (SyncService.updateConnectorCounts), so wait for both: reading the count the moment the
            // status turned failed the 0.36.2 dev pipeline on a busy runner.
            h.drainUntil(() -> h.trackedItem(source, "rn-1").getStatus() == TrackedDocument.Status.COMPLETED
                    && h.sources.findById(source.getId()).orElseThrow().getProcessedCount() == 1);

            String collection = IngestHarness.collectionOf(source);
            verify(h.keywords).addDocuments(eq(collection), eq("doc:rn-1"),
                    argThat(texts -> String.join(" ", texts).contains("Nimbus") && String.join(" ", texts).contains("team: Platform")),
                    eq("https://example.com/releases/2.0"), eq("custom"));
            verify(h.vectors, atLeastOnce()).upsert(eq(collection), anyList());
            assertThat(h.sources.findById(source.getId()).orElseThrow().getProcessedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a document that converts to nothing is a FAILED item with its reason, listed by the API")
        void failureShown() throws Exception {
            push(token, doc("blank", "Blank", "   \n")).expectStatus().isBadRequest();
            // A text the converter returns nothing for: whitespace-only after the header is impossible,
            // so make the store's body empty instead.
            push(token, doc("x", "X", "words")).expectStatus().isOk();
            IngestedDocument row = h.documents.findByConnectorIdAndExternalId(source.getId(), "x").orElseThrow();
            Files.write(h.dataDir.resolve("ingest").resolve(source.getId().toString().replace("-", ""))
                    .resolve(row.getStoredAs()), new byte[0]);

            h.drainUntil(() -> h.trackedItem(source, "x").getStatus() == TrackedDocument.Status.FAILED);
            client.get().uri(documents(source)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                    .expectBody().jsonPath("$.items[0].status").isEqualTo("FAILED")
                    .jsonPath("$.items[0].failure").value(f -> assertThat((String) f).contains("converted to no text"));
        }

        @Test
        @DisplayName("DELETE removes the document at once - row, body, keyword entries, vectors and graph")
        void deletePurges() throws Exception {
            push(token, doc("rn-1", "Release 2.0", "Nimbus")).expectStatus().isOk();
            h.drainUntil(() -> h.trackedItem(source, "rn-1").getStatus() == TrackedDocument.Status.COMPLETED);
            String body = h.documents.findByConnectorIdAndExternalId(source.getId(), "rn-1").orElseThrow().getStoredAs();

            client.delete().uri(documents(source) + "/rn-1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.deleted").isEqualTo(true);

            String collection = IngestHarness.collectionOf(source);
            verify(h.keywords, atLeastOnce()).deleteDocuments(collection, "doc:rn-1");
            verify(h.vectors, atLeastOnce()).deleteBySourceMetadata(collection, "doc:rn-1");
            verify(h.graph).forgetDocument(h.trackedItem(source, "rn-1").getId());
            assertThat(h.trackedItem(source, "rn-1").getStatus()).isEqualTo(TrackedDocument.Status.DELETED);
            assertThat(h.documents.count()).isZero();
            assertThat(Files.exists(h.dataDir.resolve("ingest").resolve(source.getId().toString().replace("-", ""))
                    .resolve(body))).isFalse();
            assertThat(h.sources.findById(source.getId()).orElseThrow().getItemCount()).isZero();

            client.delete().uri(documents(source) + "/rn-1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange().expectStatus().isNotFound();
        }

        @Test
        @DisplayName("a document deleted, then pushed again, is indexed again")
        void deleteThenPushAgain() throws Exception {
            push(token, doc("rn-1", "Release 2.0", "Nimbus")).expectStatus().isOk();
            client.delete().uri(documents(source) + "/rn-1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange().expectStatus().isOk();
            push(token, doc("rn-1", "Release 2.0", "Nimbus")).expectStatus().isOk();
            h.drainUntil(() -> h.trackedItem(source, "rn-1").getStatus() == TrackedDocument.Status.COMPLETED);
        }

        @Test
        @DisplayName("a full pass lists what was pushed - and removes what is no longer stored")
        void passListsTheStore() throws Exception {
            push(token, doc("keep", "Keep", "kept")).expectStatus().isOk();
            push(token, doc("lost", "Lost", "lost")).expectStatus().isOk();
            // A row gone without its delete reaching the index - what the daily full pass is there for.
            h.documents.delete(h.documents.findByConnectorIdAndExternalId(source.getId(), "lost").orElseThrow());

            assertThat(h.orchestrator.syncConnectorReportStarted(source.getId(), null, true)).isTrue();
            h.drainUntil(() -> h.trackedItem(source, "lost").getStatus() == TrackedDocument.Status.DELETED
                    && !h.orchestrator.isSyncing(source.getId()));
            assertThat(h.trackedItem(source, "keep").getStatus()).isNotEqualTo(TrackedDocument.Status.DELETED);
        }

        @Test
        @DisplayName("a version pushed while the previous one was indexed is indexed again, not lost under the old one")
        void newerVersionDuringIndexing() throws Exception {
            push(token, doc("rn-1", "Release 2.0", "First words.")).expectStatus().isOk();
            h.drainUntil(() -> h.trackedItem(source, "rn-1").getStatus() == TrackedDocument.Status.COMPLETED);
            String firstHash = h.trackedItem(source, "rn-1").getContentHash();

            push(token, doc("rn-1", "Release 2.0", "Second words.")).expectStatus().isOk();
            // What a drain still holding the first version writes when it finishes: its own copy of
            // the row - COMPLETED, with the first version's hash - over the one the push requeued.
            TrackedDocument stale = h.trackedItem(source, "rn-1");
            stale.setStatus(TrackedDocument.Status.COMPLETED);
            stale.setContentHash(firstHash);
            stale.setSourceLastModified(java.time.Instant.now().minusSeconds(60));
            h.tracked.save(stale);

            h.ingest.onDocumentIndexed(new ai.proxyma.core.registry.event.DocumentIndexedEvent(this, source.getId(),
                    stale.getId(), "doc:rn-1", "Release 2.0", IngestHarness.collectionOf(source)));

            assertThat(h.trackedItem(source, "rn-1").getStatus()).isEqualTo(TrackedDocument.Status.QUEUED);
            h.drainUntil(() -> h.trackedItem(source, "rn-1").getStatus() == TrackedDocument.Status.COMPLETED);
            verify(h.keywords, atLeastOnce()).addDocuments(eq(IngestHarness.collectionOf(source)), eq("doc:rn-1"),
                    argThat(texts -> String.join(" ", texts).contains("Second words.")), any(), eq("custom"));
        }

        @Test
        @DisplayName("the session lists only what arrived since the last pass when asked for changes, and a gone document is gone")
        void session() throws Exception {
            push(token, doc("old", "Old", "old")).expectStatus().isOk();
            IngestedDocument old = h.documents.findByConnectorIdAndExternalId(source.getId(), "old").orElseThrow();
            old.setReceivedAt(java.time.Instant.now().minus(java.time.Duration.ofHours(2)));
            h.documents.save(old);
            push(token, doc("new", "New", "new")).expectStatus().isOk();

            var plugin = new CustomSourcePlugin(h.documents, h.store);
            var context = new ai.proxyma.core.connector.spi.SourceContext(source.getId(), source.getConnectorType(), null,
                    source.getName(), Map.of(), null, null, null, null, null, null);
            try (var session = plugin.open(context)) {
                List<String> full = new ArrayList<>();
                session.list(ai.proxyma.core.connector.spi.ListRequest.full()).forEachRemaining(i -> full.add(i.getId()));
                assertThat(full).containsExactlyInAnyOrder("doc:old", "doc:new");
                List<String> changes = new ArrayList<>();
                session.list(ai.proxyma.core.connector.spi.ListRequest.changes(java.time.Instant.now().minusSeconds(600), null))
                        .forEachRemaining(i -> changes.add(i.getId()));
                assertThat(changes).containsExactly("doc:new");

                ai.proxyma.core.connector.ItemMetadata item = CustomSourcePlugin.item(old);
                try (var in = session.fetch(item)) {
                    assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).startsWith("# Old");
                }
                h.ingest.delete(source, "old");
                assertThatThrownBy(() -> session.fetch(item)).isInstanceOf(ai.proxyma.core.connector.ItemGoneException.class);
            }
        }

        @Test
        @DisplayName("removing the source removes its documents, bodies and tokens")
        void sourceRemoved() {
            push(token, doc("rn-1", "Release 2.0", "Nimbus")).expectStatus().isOk();
            h.ingest.onSourceRemoved(new ai.proxyma.core.registry.event.ConnectorRemovedEvent(this, source.getId(),
                    source.getConnectorType(), source.getConfig(), null, false));
            assertThat(h.documents.count()).isZero();
            assertThat(h.tokenRows.count()).isZero();
            assertThat(Files.exists(h.dataDir.resolve("ingest").resolve(source.getId().toString().replace("-", "")))).isFalse();
            verify(h.keywords, never()).addDocuments(anyString(), anyString(), anyList(), any(), any());
        }
    }
}
