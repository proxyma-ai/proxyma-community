package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.dto.FeedbackRequest;
import ai.proxyma.core.chat.dto.FeedbackResponse;
import ai.proxyma.core.chat.model.Feedback;
import ai.proxyma.core.chat.repository.FeedbackAttachmentRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.chat.service.SmartChatMemory;
import ai.proxyma.core.telemetryclient.FeedbackShipper;
import ai.proxyma.core.telemetryclient.LogTailProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The response says whether the report reached Proxyma. Every submission is stored here first and
 * answered 200, so the feedback dialog thanked people for reports the telemetry service had refused -
 * a long comment, a long conversation - and nobody learned they were lost.
 */
class FeedbackDeliveryTest {

    private FeedbackShipper shipper;
    private FeedbackController controller;

    @BeforeEach
    void setUp() {
        FeedbackRepository feedback = mock(FeedbackRepository.class);
        when(feedback.save(any())).thenAnswer(inv -> {
            Feedback f = inv.getArgument(0);
            if (f.getId() == null) f.setId(1L);
            return f;
        });
        FeedbackAttachmentRepository attachments = mock(FeedbackAttachmentRepository.class);
        when(attachments.findByFeedbackIdIn(anyList())).thenReturn(List.of());
        shipper = mock(FeedbackShipper.class);
        when(shipper.isConfigured()).thenReturn(true);
        SmartChatMemory memory = mock(SmartChatMemory.class);
        when(memory.isMultiUser()).thenReturn(false);
        // Desktop: one person, no accounts, and the door always open to them.
        var guestQuota = mock(ai.proxyma.core.chat.quota.GuestChatQuotaService.class);
        when(guestQuota.isAnonymousChatAllowed()).thenReturn(true);
        controller = new FeedbackController(feedback, attachments, new ObjectMapper(), shipper,
                mock(LogTailProvider.class), mock(ChatService.class), memory, guestQuota);
    }

    private FeedbackResponse submit() {
        FeedbackRequest request = new FeedbackRequest("BAD", "a long report", true, null, false,
                null, null, false, null, null, null, false);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/feedback"));
        return controller.submit(request, null, exchange).block().getBody();
    }

    @Test
    @DisplayName("a report the telemetry service accepts is SENT")
    void sent() {
        when(shipper.ship(any(), anyList())).thenReturn(true);
        assertThat(submit().delivery()).isEqualTo(FeedbackResponse.Delivery.SENT);
    }

    @Test
    @DisplayName("a report it refuses, or cannot be reached for, is NOT_SENT - still stored, still a 200")
    void notSent() {
        when(shipper.ship(any(), anyList())).thenReturn(false);
        FeedbackResponse response = submit();
        assertThat(response.delivery()).isEqualTo(FeedbackResponse.Delivery.NOT_SENT);
        assertThat(response.id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("with no telemetry address the report is KEPT_HERE, which is no failure, and nothing is tried")
    void keptHere() {
        when(shipper.isConfigured()).thenReturn(false);
        assertThat(submit().delivery()).isEqualTo(FeedbackResponse.Delivery.KEPT_HERE);
        verify(shipper, never()).ship(any(), anyList());
    }
}
