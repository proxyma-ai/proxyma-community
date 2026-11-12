package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.settings.usage.LlmCallLabels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which agent the per-call log names a conversation's calls after (2026-11-04): a module's own
 * conversations as the module says ("Project / Agent", how the run started), a deployed agent by its
 * title after its root's label and with its root's trigger - so the agents a scheduled run deployed
 * read as that run - a deployed agent of an ordinary chat by its title alone, and an ordinary chat not
 * at all. Batched: one read of the tree for the whole set.
 */
class ConversationCallLabelsTest {

    private AgentTree tree;
    private ChatModule module;
    private ConversationCallLabels labels;

    private static AgentNode node(String conversationId, String root, String title) {
        AgentNode n = new AgentNode();
        n.setConversationId(conversationId);
        n.setRootConversationId(root);
        n.setTitle(title);
        return n;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tree = mock(AgentTree.class);
        module = new ChatModule() {
            @Override public List<Object> toolObjects() { return List.of(); }
            @Override public Map<String, Set<String>> skillToolGroups() { return Map.of(); }
            @Override public Set<String> ownToolNames() { return Set.of(); }
            @Override public boolean offersToolsIn(String conversationId, String userId) { return false; }
            @Override public java.util.Optional<String> systemContext(String conversationId, String userId) {
                return java.util.Optional.empty();
            }
            @Override public boolean licensed() { return true; }
            @Override public Map<String, LlmCallLabels.Label> callLabels(Collection<String> ids) {
                return ids.contains("conv-run")
                        ? Map.of("conv-run", new LlmCallLabels.Label("YouTube / Lead", LlmCallLabels.TRIGGER_SCHEDULE))
                        : Map.of();
            }
        };
        ObjectProvider<ChatModule> modules = mock(ObjectProvider.class);
        when(modules.orderedStream()).thenAnswer(i -> Stream.of(module));
        labels = new ConversationCallLabels(tree, modules);
    }

    @Test
    @DisplayName("a module's conversation, a deployed agent under it, one under an ordinary chat, and an ordinary chat")
    void namesEachKind() {
        when(tree.nodesOf(any())).thenReturn(Map.of(
                "conv-child", node("conv-child", "conv-run", "Reply drafter"),
                "conv-helper", node("conv-helper", "conv-plain", "Researcher")));

        Map<String, LlmCallLabels.Label> out = labels.labels(List.of("conv-run", "conv-child", "conv-helper", "conv-plain"));

        assertThat(out.get("conv-run")).isEqualTo(new LlmCallLabels.Label("YouTube / Lead", "schedule"));
        assertThat(out.get("conv-child")).as("after its root, as that run")
                .isEqualTo(new LlmCallLabels.Label("YouTube / Lead > Reply drafter", "schedule"));
        assertThat(out.get("conv-helper")).isEqualTo(new LlmCallLabels.Label("Researcher", null));
        assertThat(out).doesNotContainKey("conv-plain");
        verify(tree, times(1)).nodesOf(any());
    }

    @Test
    @DisplayName("a module that throws leaves its conversations unnamed and the rest named")
    @SuppressWarnings("unchecked")
    void failingModuleIsSkipped() {
        ChatModule broken = mock(ChatModule.class);
        when(broken.callLabels(any())).thenThrow(new IllegalStateException("down"));
        ObjectProvider<ChatModule> modules = mock(ObjectProvider.class);
        when(modules.orderedStream()).thenAnswer(i -> Stream.of(broken, module));
        when(tree.nodesOf(any())).thenReturn(Map.of());

        Map<String, LlmCallLabels.Label> out = new ConversationCallLabels(tree, modules).labels(List.of("conv-run"));

        assertThat(out.get("conv-run").agent()).isEqualTo("YouTube / Lead");
    }

    @Test
    @DisplayName("nothing asked, nothing read")
    void emptyIsEmpty() {
        assertThat(labels.labels(List.of())).isEmpty();
        verify(tree, times(0)).nodesOf(any());
    }
}
