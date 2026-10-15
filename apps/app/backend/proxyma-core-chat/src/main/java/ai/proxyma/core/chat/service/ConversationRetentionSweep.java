package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.settings.dto.RetentionSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.chat.repository.ConversationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Unpinned conversations idle past RETENTION_DAYS (and everything they own - messages, feedback,
// the per-conversation folder) are deleted automatically. Runs in both desktop and server modes -
// this is chat-data hygiene, not a multi-user-only concern. Deletes via ChatService (not
// SmartChatMemory directly) so an auto-deleted conversation also has any lingering in-flight turn
// aborted and its feedback rows cleaned up, exactly like the DELETE endpoint does.
@Component
class ConversationRetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(ConversationRetentionSweep.class);
    // Was a hardcoded 7 with no setting and nothing in the UI saying so, which meant a user back
    // from a fortnight's leave found their history gone. 7 remains the DEFAULT, so this changes
    // nothing on upgrade - it is simply adjustable now. 0 disables the sweep entirely.

    // How long a folder no conversation owns is left alone: an upload is made a moment before the
    // message that creates its conversation, and a day is far longer than that moment.
    static final java.time.Duration ORPHAN_MIN_AGE = java.time.Duration.ofDays(1);

    private final ConversationRepository conversationRepository;
    private final SettingsService settingsService;
    private final ChatService chatService;
    private final SmartChatMemory chatMemory;

    ConversationRetentionSweep(ConversationRepository conversationRepository, ChatService chatService,
                              SettingsService settingsService, SmartChatMemory chatMemory) {
        this.conversationRepository = conversationRepository;
        this.settingsService = settingsService;
        this.chatService = chatService;
        this.chatMemory = chatMemory;
    }

    // Files no conversation owns: uploads to a conversation whose first message never came, and
    // what deletes left on Enterprise Server before a deleted conversation's folder was found from
    // its owner (SmartChatMemory.deleteConversation). Daily, and whatever the retention setting -
    // keeping conversations for ever is no reason to keep files that belong to none.
    @Scheduled(fixedRate = 86_400_000, initialDelay = 600_000)
    void sweepOrphanFolders() {
        try {
            chatMemory.cleanOrphanFolders(ORPHAN_MIN_AGE);
        } catch (Exception e) {
            log.warn("[ConversationRetentionSweep] could not sweep conversation folders no conversation owns", e);
        }
    }

    @Scheduled(fixedRate = 86_400_000, initialDelay = 300_000)
    void sweep() {
        int retentionDays = settingsService.getSettings(RetentionSettings.PREFIX, RetentionSettings.class)
                .getConversationDays();
        if (retentionDays <= 0) return;   // 0 = keep forever
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        var expired = conversationRepository.findAllByUpdatedAtBeforeAndPinnedFalse(cutoff);
        for (Conversation c : expired) {
            try {
                chatService.deleteConversation(c.getId());
            } catch (Exception e) {
                log.warn("[ConversationRetentionSweep] failed to delete conversation {}", c.getId(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("[ConversationRetentionSweep] deleted {} conversation(s) idle past {} day(s)",
                    expired.size(), retentionDays);
        }
    }
}
