package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.domain.PermissionMode;
import ai.proxyma.core.chat.event.ConversationsDeletedEvent;
import ai.proxyma.core.chat.repository.AgentAlarmRepository;
import ai.proxyma.core.chat.repository.AgentInboxRepository;
import ai.proxyma.core.chat.repository.AgentNodeRepository;
import ai.proxyma.core.chat.service.ChatEventSink;
import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who a conversation is in the agent tree - its node, its root, its depth - is asked on every step of
 * every turn (the chat modules find a deployed agent's root through it). None of it changes once a node
 * is deployed, so it is read once per conversation: rootOf, isChild and depthOf read no row after that,
 * a conversation that is no agent's included, and a deletion forgets it.
 */
class AgentTreeIdentityTest {

    AgentNodeRepository nodes;
    AgentTree tree;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        nodes = mock(AgentNodeRepository.class, AdditionalAnswers.delegatesTo(InMemoryRepositories.of(AgentNodeRepository.class)));
        ChatService chat = mock(ChatService.class);
        when(chat.permissionMode(anyString())).thenReturn(PermissionMode.ASK);
        ObjectProvider<ChatService> chats = mock(ObjectProvider.class);
        when(chats.getObject()).thenReturn(chat);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(new SubAgentSettings());
        ApplicationEventPublisher events = e -> { };
        AgentInbox inbox = new AgentInbox(InMemoryRepositories.of(AgentInboxRepository.class, Map.of(
                "conversationsWithPending", (store, args) -> List.of(),
                "markDelivered", (store, args) -> 0)), events);
        tree = new AgentTree(nodes, inbox, InMemoryRepositories.of(AgentAlarmRepository.class), chats, settings,
                mock(ChatEventSink.class), events);
        tree.runInline();
    }

    @Test
    @DisplayName("a deployed agent's root and depth, and an ordinary conversation's lack of either, are read once")
    void readOnce() {
        Map<String, Object> out = tree.deploy("conv-root", "u-1", new AgentTree.Deploy("Read the diff", "Do it", "SHORT", null, null));
        assertThat(out).as(String.valueOf(out)).containsEntry("success", true);
        String child = nodes.findById(java.util.UUID.fromString((String) out.get("agentId"))).orElseThrow().getConversationId();
        clearInvocations(nodes);

        for (int step = 0; step < 30; step++) {
            assertThat(tree.rootOf(child)).isEqualTo("conv-root");
            assertThat(tree.isChild(child)).isTrue();
            assertThat(tree.depthOf(child)).isEqualTo(1);
            assertThat(tree.rootOf("conv-plain")).isEqualTo("conv-plain");
            assertThat(tree.isChild("conv-plain")).isFalse();
        }
        verify(nodes, never()).findByConversationId(child);
        verify(nodes, never()).findById(any());
        verify(nodes, times(1)).findByConversationId("conv-plain");

        tree.onConversationsDeleted(new ConversationsDeletedEvent(List.of(child)));
        assertThat(tree.isChild(child)).as("a deleted agent is read again, and is none").isFalse();
    }
}
