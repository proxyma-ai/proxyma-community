package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.dto.WorkflowRunSnapshot;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.service.SmartChatMemory;
import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.chat.service.WorkflowRunService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.util.List;

/**
 * Reads a conversation's workflow runs back.
 *
 * <p>This is what makes a refresh mid-run show the TRUE state rather than a guess. The design
 * ruled out reconstructing it client-side from replayed events: ChatEventSink does replay, so that
 * would mostly work - until the sink is reaped after 45 idle minutes, or the run outlives the tab,
 * or the browser is opened somewhere else entirely.
 *
 * <p>Returns exactly the object the SSE stream carries, so the client parses one shape either way.
 *
 * <p>Under /api/v1/chat/, which is permitAll in SecurityConfig, so ownership is enforced HERE, by
 * the same rule every other read of a conversation's own content uses (see ConversationAccess -
 * shared with ChatController rather than restated, because two copies of an access rule is how the
 * copies come to disagree). A run snapshot carries step titles and step output for a conversation
 * id, and conversation ids are unguessable but not secret. An unknown or inaccessible conversation
 * gets the same 404, so this never reports whether one exists.
 */
@ConditionalOnProperty(name = "proxyma.features.workflows", havingValue = "true")
@RestController
@RequestMapping("/api/v1/chat")
public class WorkflowRunController {

    private final WorkflowRunService workflowRunService;
    private final ChatService chatService;
    private final SmartChatMemory chatMemory;

    public WorkflowRunController(WorkflowRunService workflowRunService, ChatService chatService,
                                 SmartChatMemory chatMemory) {
        this.workflowRunService = workflowRunService;
        this.chatService = chatService;
        this.chatMemory = chatMemory;
    }

    @GetMapping("/conversations/{conversationId}/workflow-runs")
    public Mono<ResponseEntity<List<WorkflowRunSnapshot>>> forConversation(
            @PathVariable String conversationId, ServerWebExchange exchange) {
        return exchange.getPrincipal().map(Principal::getName).defaultIfEmpty("")
                .flatMap(userId -> Mono.fromCallable(() -> {
                    String uid = userId.isEmpty() ? null : userId;
                    String guestSessionId = ConversationAccess.guestSessionId(exchange, false);
                    Conversation conv;
                    try {
                        conv = chatService.getConversation(conversationId);
                    } catch (Exception e) {
                        // A conversation with no runs and a conversation that does not exist are
                        // the same answer to this endpoint - an empty list - so a client that asks
                        // about one mid-creation is not made to handle an error it cannot act on.
                        return ResponseEntity.ok(List.<WorkflowRunSnapshot>of());
                    }
                    if (!ConversationAccess.canAccess(conv, uid, guestSessionId, chatMemory.isMultiUser())) {
                        return ResponseEntity.status(404).<List<WorkflowRunSnapshot>>build();
                    }
                    return ResponseEntity.ok(workflowRunService.forConversation(conversationId).stream()
                            .map(WorkflowRunSnapshot::scrubbed).toList());
                }).subscribeOn(Schedulers.boundedElastic()));
    }
}
