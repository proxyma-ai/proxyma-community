package ai.proxyma.core.chat.service;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.service.ConnectorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The graph traversal tool is offered to someone who can READ graph data - asked of their own
 * sources, not of the whole graph. It used to be offered whenever the graph held anything at all,
 * so on Enterprise Server a user with no readable graph data spent a call on every conversation to
 * be told there was nothing.
 */
class GraphToolOfferTest {

    private static ToolCallback tool(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private static RegisteredConnector connector(UUID id) {
        RegisteredConnector c = new RegisteredConnector();
        c.setId(id);
        return c;
    }

    @Test
    @DisplayName("offered to a reader of a source with graph data, and not to someone who can read none")
    void offeredPerReader() {
        UUID wiki = UUID.randomUUID();
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        when(graph.hasAnyData()).thenReturn(true);
        when(graph.hasDataIn(List.of(wiki))).thenReturn(true);
        ConnectorService connectors = mock(ConnectorService.class);
        when(connectors.listVisibleTo("ana")).thenReturn(List.of(connector(wiki)));
        when(connectors.listVisibleTo("bo")).thenReturn(List.of());

        FeatureGateService gate = new FeatureGateService();
        gate.refresh(LicenseTier.ENTERPRISE);
        ChatSkillService skills = mock(ChatSkillService.class);
        when(skills.toolsForSkillName(anyString())).thenReturn(Optional.empty());
        ToolSkillGating gating = new ToolSkillGating(skills, graph, new LicenseToolScope(gate), List.of());
        gating.setConnectorService(connectors);

        List<ToolCallback> core = List.of(tool("searchIndexedSources"), tool("searchKnowledgeGraph"));
        assertThat(names(gating.resolveAdvertised(core, core, Set.of(), "c-1", "ana"))).contains("searchKnowledgeGraph");
        assertThat(names(gating.resolveAdvertised(core, core, Set.of(), "c-2", "bo")))
                .containsExactly("searchIndexedSources");
    }

    private static List<String> names(List<ToolCallback> callbacks) {
        return callbacks.stream().map(cb -> cb.getToolDefinition().name()).toList();
    }
}
