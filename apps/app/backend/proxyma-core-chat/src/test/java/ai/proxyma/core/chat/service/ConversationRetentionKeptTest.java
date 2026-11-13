package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.agents.AgentTree;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.settings.dto.RetentionSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A CONVERSATION A MODULE KEEPS IS NOT EXPIRED BY IDLE TIME (the maintainer, 2026-11-12): a channel team quiet
 * for a week lost its conversations to the chat's retention, while the Privacy Policy says what a team kept
 * stays until the team is removed. A saved agent's conversation, and every agent deployed under one, goes with
 * its project; an ordinary idle conversation still goes as before.
 */
class ConversationRetentionKeptTest {

    private ConversationRepository repo;
    private ChatService chat;
    private ChatModule module;
    private AgentTree tree;
    private ConversationRetentionSweep sweep;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(ConversationRepository.class);
        chat = mock(ChatService.class);
        module = mock(ChatModule.class);
        tree = mock(AgentTree.class);
        SettingsService settings = mock(SettingsService.class);
        RetentionSettings retention = new RetentionSettings();
        retention.setConversationDays(7);
        when(settings.getSettings(eq(RetentionSettings.PREFIX), eq(RetentionSettings.class))).thenReturn(retention);
        ObjectProvider<ChatModule> modules = mock(ObjectProvider.class);
        when(modules.orderedStream()).thenAnswer(i -> Stream.of(module));
        ObjectProvider<AgentTree> trees = mock(ObjectProvider.class);
        when(trees.getIfAvailable()).thenReturn(tree);
        when(tree.rootOf(any())).thenAnswer(i -> i.getArgument(0));
        sweep = new ConversationRetentionSweep(repo, chat, settings, mock(SmartChatMemory.class), modules, trees);
    }

    private static Conversation idle(String id) {
        Conversation c = new Conversation();
        c.setId(id);
        return c;
    }

    @Test
    @DisplayName("an ordinary idle conversation is deleted; a team's, and an agent deployed under it, are kept")
    void teamConversationsAreKept() {
        when(repo.findAllByUpdatedAtBeforeAndPinnedFalse(any(Instant.class)))
                .thenReturn(List.of(idle("plain"), idle("team"), idle("child")));
        when(module.keepsConversation("team")).thenReturn(true);
        when(tree.rootOf("child")).thenReturn("team");

        sweep.sweep();

        verify(chat).deleteConversation("plain");
        verify(chat, never()).deleteConversation("team");
        verify(chat, never()).deleteConversation("child");
    }

    @Test
    @DisplayName("a module that cannot answer keeps the conversation - one wrongly deleted is gone")
    void failingModuleKeeps() {
        when(repo.findAllByUpdatedAtBeforeAndPinnedFalse(any(Instant.class))).thenReturn(List.of(idle("unsure")));
        when(module.keepsConversation("unsure")).thenThrow(new IllegalStateException("database away"));

        sweep.sweep();

        verify(chat, never()).deleteConversation("unsure");
    }
}
