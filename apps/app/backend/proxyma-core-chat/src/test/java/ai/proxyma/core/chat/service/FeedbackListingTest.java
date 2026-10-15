package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.controller.ConversationAccess;
import ai.proxyma.core.chat.domain.FeedbackMood;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.model.Feedback;
import ai.proxyma.core.chat.model.FeedbackAttachment;
import ai.proxyma.core.chat.repository.FeedbackAttachmentRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chat-api-data-07: the admin feedback list showed each attachment's name and size and loaded its
 * bytes to do it - up to 20 MB a feedback, a hundred feedbacks a page. chat-api-data-21: on a
 * multi-user server a conversation with neither an owner nor a visitor was open to anyone.
 */
class FeedbackListingTest {

    @TempDir
    Path data;

    private ChatJpa jpa;

    @AfterEach
    void tearDown() {
        if (jpa != null) jpa.close();
    }

    @Test
    @DisplayName("the list reads an attachment's name, type and size - and not its bytes")
    void listReadsMetadataOnly() {
        jpa = new ChatJpa(data, false);
        Feedback feedback = new Feedback();
        feedback.setMood(FeedbackMood.GOOD);
        feedback.setAnonymous(true);
        feedback.setCreatedAt(Instant.now());
        feedback = jpa.get(FeedbackRepository.class).save(feedback);
        FeedbackAttachment attachment = new FeedbackAttachment();
        attachment.setFeedback(feedback);
        attachment.setFileName("screen.png");
        attachment.setContentType("image/png");
        attachment.setSizeBytes(3);
        attachment.setData(new byte[]{1, 2, 3});
        attachment.setCreatedAt(Instant.now());
        jpa.get(FeedbackAttachmentRepository.class).save(attachment);

        List<FeedbackAttachmentRepository.Meta> meta =
                jpa.get(FeedbackAttachmentRepository.class).findMetaByFeedbackIdIn(List.of(feedback.getId()));

        assertThat(meta).containsExactly(new FeedbackAttachmentRepository.Meta(
                meta.get(0).id(), feedback.getId(), "screen.png", "image/png", 3));
    }

    @Test
    @DisplayName("a conversation that is nobody's opens to nobody on a multi-user server")
    void nobodysConversation() {
        Conversation unbound = new Conversation();
        assertThat(ConversationAccess.canAccess(unbound, "alice", "guest-1", true)).isFalse();
        assertThat(ConversationAccess.canAccess(unbound, null, null, true)).isFalse();
        assertThat(ConversationAccess.canAccess(unbound, null, null, false)).isTrue();
    }
}
