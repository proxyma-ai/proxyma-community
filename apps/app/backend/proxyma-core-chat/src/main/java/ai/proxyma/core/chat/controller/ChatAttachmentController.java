package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.dto.AttachmentResponse;
import ai.proxyma.core.chat.quota.GuestChatQuotaService;
import ai.proxyma.core.chat.service.SmartChatMemory;
import ai.proxyma.core.common.conversation.ConversationPaths;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.Principal;

// Ephemeral per-conversation file attachments for the chat agent - see
// SmartChatMemory.saveAttachment. Uses the same auth-optional userId resolution as
// ChatController (exchange.getPrincipal(), not a JWTClaimsSet request attribute) so this also
// works in desktop's no-auth mode.
@RestController
@RequestMapping("/api/v1/chat")
public class ChatAttachmentController {

    private static final long MAX_ATTACHMENT_SIZE_BYTES = 25L * 1024 * 1024;

    private final SmartChatMemory chatMemory;
    private final GuestChatQuotaService guestQuota;

    public ChatAttachmentController(SmartChatMemory chatMemory, GuestChatQuotaService guestQuota) {
        this.chatMemory = chatMemory;
        this.guestQuota = guestQuota;
    }

    @PostMapping(value = "/conversations/{conversationId}/attachments", consumes = "multipart/form-data")
    public Mono<ResponseEntity<AttachmentResponse>> upload(
            @PathVariable String conversationId,
            @RequestPart("file") FilePart filePart,
            ServerWebExchange exchange) {
        // The id names the folder the file is written to - see ConversationPaths. Refused before a
        // byte of the upload is read.
        if (!ConversationPaths.isValidId(conversationId)) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return resolveUserId(exchange).flatMap(userId -> {
            // The door POST /chat has, here too: /api/v1/chat/** is open to visitors, and an upload
            // is only ever for a message, so where a visitor may not send one they may not store a
            // file either. Desktop and Home Server always open it - see isAnonymousChatAllowed.
            if (userId.isEmpty() && !guestQuota.isAnonymousChatAllowed()) {
                return Mono.just(ResponseEntity.status(401).<AttachmentResponse>build());
            }
            return filePart.content()
                    .reduce(new ByteArrayOutputStream(), (baos, buf) -> {
                        // asInputStream(true) RELEASES the buffer when the stream closes. The plain
                        // form leaves it to the caller, and nobody released it - pooled memory
                        // outside the heap, leaked once per attachment.
                        try (InputStream in = buf.asInputStream(true)) {
                            in.transferTo(baos);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        if (baos.size() > MAX_ATTACHMENT_SIZE_BYTES) {
                            throw new IllegalArgumentException("Attachment exceeds maximum allowed size of "
                                    + (MAX_ATTACHMENT_SIZE_BYTES / (1024 * 1024)) + "MB");
                        }
                        return baos;
                    })
                    // The buffers still in flight when an attachment is refused or its upload dropped.
                    .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                    // Saving writes a file, and the last buffer arrives on whatever thread was
                    // reading the request.
                    .publishOn(Schedulers.boundedElastic())
                    .map(baos -> {
                        String uid = userId.isEmpty() ? null : userId;
                        byte[] bytes = baos.toByteArray();
                        try {
                            SmartChatMemory.AttachmentResult result =
                                    chatMemory.saveAttachment(conversationId, uid, filePart.filename(), bytes);
                            return ResponseEntity.ok(new AttachmentResponse(result.storedFileName(), result.sizeBytes(),
                                    ChatController.attachmentUrl(conversationId, result.storedFileName())));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> resolveUserId(ServerWebExchange exchange) {
        return exchange.getPrincipal().map(Principal::getName).defaultIfEmpty("");
    }
}
