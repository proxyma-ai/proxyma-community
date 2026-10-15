package ai.proxyma.core.tools.sources;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.ingest.service.DocumentFetcher;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.ingest.IngestDraft;
import ai.proxyma.core.registry.ingest.IngestService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.tools.ActiveServers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agent's saveToSource: a note goes into one of the CALLER'S OWN custom sources, through the
 * ingestion API's own service, and nowhere else. (That it is a write - Ask pauses it, Read-only
 * refuses it - is PermissionModeTest's and WriteToolReadCallTest's.)
 */
class SaveToSourceTest {

    private static final String ME = "user-1";

    private final IngestService ingest = mock(IngestService.class);
    private final SourceTools tools = new SourceTools(mock(ConnectorService.class), ConnectorCatalog.descriptorsOnly(),
            mock(McpGuardService.class), mock(ActiveServers.class), mock(SourceContextFactory.class),
            mock(DocumentFetcher.class), mock(FeatureGateService.class), false);

    @BeforeEach
    void signIn() {
        SearchContext.setUserId(ME);
        tools.setIngest(ingest);
    }

    @AfterEach
    void signOut() {
        SearchContext.clear();
    }

    private RegisteredConnector custom(String owner) {
        RegisteredConnector r = new RegisteredConnector();
        r.setId(UUID.randomUUID());
        r.setConnectorType(ConnectorType.CUSTOM);
        r.setName("Team notes");
        r.setUserId(owner);
        when(ingest.pushSource(r.getId())).thenReturn(r);
        return r;
    }

    @Test
    @DisplayName("a note saved to the caller's own custom source goes through the ingestion service, named by the assistant")
    void savesToOwnSource() {
        RegisteredConnector mine = custom(ME);
        when(ingest.upsertText(eq(mine), any())).thenReturn(new IngestService.Saved("decision-1", "created"));

        Map<String, Object> out = tools.saveToSource(mine.getId().toString(), "Decision", "We ship on Friday.",
                "decision-1", "https://example.com/plan");

        assertThat(out.get("success")).isEqualTo(true);
        assertThat(out.get("noteId")).isEqualTo("decision-1");
        ArgumentCaptor<IngestDraft> draft = ArgumentCaptor.forClass(IngestDraft.class);
        verify(ingest).upsertText(eq(mine), draft.capture());
        assertThat(draft.getValue().externalId()).isEqualTo("decision-1");
        assertThat(draft.getValue().author()).isEqualTo("Proxyma assistant");
        assertThat(draft.getValue().url()).isEqualTo("https://example.com/plan");
    }

    @Test
    @DisplayName("with no noteId a new note gets an id of its own - a later save under it replaces it")
    void makesAnId() {
        RegisteredConnector mine = custom(ME);
        when(ingest.upsertText(eq(mine), any())).thenAnswer(i -> new IngestService.Saved(
                ((IngestDraft) i.getArgument(1)).externalId(), "created"));

        Map<String, Object> out = tools.saveToSource(mine.getId().toString(), "Notes", "Text", null, null);

        assertThat((String) out.get("noteId")).matches("note-\\d{8}-\\d{6}-[0-9a-f]{6}");
    }

    @Test
    @DisplayName("a custom source somebody else owns - shared with the caller or not - takes no note")
    void notSomebodyElses() {
        RegisteredConnector theirs = custom("user-2");
        theirs.setShared(true);

        Map<String, Object> out = tools.saveToSource(theirs.getId().toString(), "Notes", "Text", null, null);

        assertThat(out.get("success")).isEqualTo(false);
        verify(ingest, never()).upsertText(any(), any());
    }

    @Test
    @DisplayName("a source that is not a custom source, or no source id at all, takes no note")
    void onlyCustomSources() {
        UUID web = UUID.randomUUID();
        when(ingest.pushSource(web)).thenReturn(null);

        assertThat(tools.saveToSource(web.toString(), "Notes", "Text", null, null).get("success")).isEqualTo(false);
        assertThat(tools.saveToSource("Team notes", "Notes", "Text", null, null).get("success")).isEqualTo(false);
        verify(ingest, never()).upsertText(any(), any());
    }
}
