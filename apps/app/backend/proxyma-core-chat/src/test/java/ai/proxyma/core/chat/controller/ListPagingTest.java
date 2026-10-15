package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.model.Feedback;
import ai.proxyma.core.chat.repository.FeedbackAttachmentRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every list of records pages at twenty with numbered pages (2026-10-14), so the conversation
 * history and the Administration > Feedback list answer with the total that numbers them, and a page
 * or size out of range is the nearest valid one rather than a 500 from PageRequest.
 */
class ListPagingTest {

    @Test
    @DisplayName("the history's page is never before the first and holds one to a hundred conversations")
    void conversationPageIsClamped() {
        assertThat(ChatController.conversationPage(0, 20)).isEqualTo(PageRequest.of(0, 20));
        assertThat(ChatController.conversationPage(-3, 20)).isEqualTo(PageRequest.of(0, 20));
        assertThat(ChatController.conversationPage(2, 0)).isEqualTo(PageRequest.of(2, 1));
        assertThat(ChatController.conversationPage(2, -5)).isEqualTo(PageRequest.of(2, 1));
        assertThat(ChatController.conversationPage(1, 10_000)).isEqualTo(PageRequest.of(1, 100));
    }

    @Test
    @DisplayName("the feedback page is clamped the same way")
    void feedbackPageIsClamped() {
        assertThat(AdminFeedbackController.pageRequest(-1, 0)).isEqualTo(PageRequest.of(0, 1));
        assertThat(AdminFeedbackController.pageRequest(4, 20)).isEqualTo(PageRequest.of(4, 20));
        assertThat(AdminFeedbackController.pageRequest(0, 500)).isEqualTo(PageRequest.of(0, 100));
    }

    @Test
    @DisplayName("the feedback list says how many there are and how many pages they fill")
    void feedbackListCarriesItsTotal() {
        FeedbackRepository feedback = mock(FeedbackRepository.class);
        FeedbackAttachmentRepository attachments = mock(FeedbackAttachmentRepository.class);
        when(attachments.findByFeedbackIdIn(anyList())).thenReturn(List.of());
        // Page 3 of a list of 45, twenty a page: nothing on it, but the total still numbers the pages.
        when(feedback.findAllByOrderByCreatedAtDesc(any()))
                .thenAnswer(inv -> new PageImpl<Feedback>(List.of(), inv.getArgument(0), 45));
        AdminFeedbackController controller = new AdminFeedbackController(feedback, attachments, new ObjectMapper());

        Map<String, Object> body = controller.list(-2, 20, null).block().getBody();

        ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
        verify(feedback).findAllByOrderByCreatedAtDesc(asked.capture());
        assertThat(asked.getValue().getPageNumber()).isZero();
        assertThat(asked.getValue().getPageSize()).isEqualTo(20);
        assertThat(body).containsEntry("totalItems", 45L).containsEntry("totalPages", 3).containsEntry("page", 0);
    }
}
