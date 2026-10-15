package ai.proxyma.core.tools;

import ai.proxyma.core.indexstore.ChunkMetadata;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.SourceCollections;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.search.service.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which source each of searchIndexedSources' results came from. An item's id is unique only within
 * its source, so two results that both read {@code ticket:1} are told apart by the source beside
 * them - and that source's id is what readSourceItem needs to open either.
 */
class SearchToolSourceTest {

    private final UUID zendesk = UUID.randomUUID();
    private final UUID freshdesk = UUID.randomUUID();
    private SearchService search;
    private SearchTool tool;

    private static RegisteredConnector source(UUID id, String name) {
        RegisteredConnector c = new RegisteredConnector();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private static Document hit(String collection, String text) {
        return Document.builder().id(UUID.randomUUID().toString()).text(text)
                .metadata(Map.of("source", "ticket:1", ChunkMetadata.COLLECTION, collection)).score(0.8).build();
    }

    @BeforeEach
    void setUp() {
        search = mock(SearchService.class);
        ConnectorService connectors = mock(ConnectorService.class);
        when(connectors.listVisibleTo("ana")).thenReturn(List.of(source(zendesk, "Support (Zendesk)"),
                source(freshdesk, "Helpdesk (Freshdesk)")));
        tool = new SearchTool(search, connectors);
        SearchContext.setUserId("ana");
    }

    @AfterEach
    void tearDown() {
        SearchContext.clear();
    }

    @Test
    @DisplayName("two results with one item id each name their own source; a shadow collection's is its base source")
    @SuppressWarnings("unchecked")
    void eachResultNamesItsSource() {
        when(search.search(eq(List.of(SourceCollections.of(zendesk), SourceCollections.of(freshdesk))),
                anyString(), anyInt(), anyDouble())).thenReturn(List.of(
                hit(SourceCollections.of(zendesk), "Refund request"),
                hit(SourceCollections.shadowOf(SourceCollections.of(freshdesk)), "Printer jam"),
                hit("guardrailpolicy", "not a source's")));

        List<Map<String, Object>> results = (List<Map<String, Object>>) tool.searchIndexedSources("ticket", 5, null, null)
                .get("results");

        assertEquals("ticket:1", results.get(0).get("source"));
        assertEquals("Support (Zendesk)", results.get(0).get("sourceName"));
        assertEquals(zendesk.toString(), results.get(0).get("sourceId"));
        assertEquals("ticket:1", results.get(1).get("source"));
        assertEquals("Helpdesk (Freshdesk)", results.get(1).get("sourceName"));
        assertEquals(freshdesk.toString(), results.get(1).get("sourceId"));
        assertFalse(results.get(2).containsKey("sourceId"), "a collection of no searched source names none");
    }
}
