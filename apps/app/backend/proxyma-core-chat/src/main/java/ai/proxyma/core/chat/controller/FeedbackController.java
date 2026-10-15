package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.domain.FeedbackMood;
import ai.proxyma.core.chat.domain.FeedbackSource;
import ai.proxyma.core.chat.dto.FeedbackRequest;
import ai.proxyma.core.chat.dto.FeedbackResponse;
import ai.proxyma.core.chat.model.Feedback;
import ai.proxyma.core.chat.model.FeedbackAttachment;
import ai.proxyma.core.chat.repository.FeedbackAttachmentRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.contracts.feedback.FeedbackAttachmentKind;
import ai.proxyma.contracts.feedback.FeedbackSubmission;
import ai.proxyma.core.telemetryclient.FeedbackShipper;
import ai.proxyma.core.telemetryclient.LogTailProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.Map;

// POST /api/v1/feedback is permitAll in proxyma-core-auth's SecurityConfig; every other path here
// falls under the "/api/v1/**" -> authenticated() rule. Because the POST is open, submit() decides
// for itself who may send (swx-05: a visitor only where guest chat is on, as for a chat turn) and
// what the caller may name and attach (SEC-50).
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    // Feedback attachments are screenshots/log excerpts, not general-purpose file storage (see
    // proxyma-core-connector's Files connector for that) - kept deliberately small since every byte
    // lives in the "feedback_attachment" row's blob column, not on disk (see FeedbackAttachment's
    // own javadoc for why: Feedback is used from both desktop/H2 and server/Postgres, and the
    // server case can be multi-replica).
    // Ten files and 20 MB between them since 2026-09-30 (maintainer's feedback) - five screenshots
    // was too few for one report. The budget is the TOTAL, so one file may use all of it. The
    // log the server attaches itself is outside both numbers; the telemetry receiver's own
    // ceilings (FeedbackIngestController) leave room for it.
    private static final long MAX_TOTAL_ATTACHMENT_BYTES = 20L * 1024 * 1024;
    private static final int MAX_ATTACHMENTS_PER_FEEDBACK = 10;

    private final FeedbackRepository feedbackRepository;
    private final FeedbackAttachmentRepository feedbackAttachmentRepository;
    private final ObjectMapper objectMapper;
    private final FeedbackShipper feedbackShipper;
    private final LogTailProvider logTailProvider;
    private final ai.proxyma.core.chat.service.ChatService chatService;
    private final ai.proxyma.core.chat.service.SmartChatMemory chatMemory;
    private final ai.proxyma.core.chat.quota.GuestChatQuotaService guestQuota;

    public FeedbackController(FeedbackRepository feedbackRepository,
                               FeedbackAttachmentRepository feedbackAttachmentRepository,
                               ObjectMapper objectMapper,
                               FeedbackShipper feedbackShipper,
                               LogTailProvider logTailProvider,
                               ai.proxyma.core.chat.service.ChatService chatService,
                               ai.proxyma.core.chat.service.SmartChatMemory chatMemory,
                               ai.proxyma.core.chat.quota.GuestChatQuotaService guestQuota) {
        this.feedbackRepository = feedbackRepository;
        this.feedbackAttachmentRepository = feedbackAttachmentRepository;
        this.objectMapper = objectMapper;
        this.feedbackShipper = feedbackShipper;
        this.logTailProvider = logTailProvider;
        this.chatService = chatService;
        this.chatMemory = chatMemory;
        this.guestQuota = guestQuota;
    }

    /**
     * SEC-50: the conversation a feedback row may name - only one the caller can open. The id was
     * taken from the request as given, from anyone (the endpoint is open to visitors), so a row
     * could be attached to someone else's conversation, and a retracted rating or a deleted
     * conversation then acted on it.
     */
    private boolean mayReference(String conversationId, String userId, String guestSessionId) {
        if (conversationId == null || conversationId.isBlank()) return false;
        try {
            return ConversationAccess.canAccess(chatService.getConversation(conversationId), userId,
                    guestSessionId, chatMemory.isMultiUser());
        } catch (Exception unknownConversation) {
            return false;
        }
    }

    /**
     * SEC-50 as well: the server's own log is the DEPLOYMENT's, not the submitter's - it holds every
     * user's activity. On a multi-user server only the super admin may send it; on the single-user
     * editions the log is the one person's own. A request for it from anyone else is not refused,
     * just sent without the log.
     */
    private Mono<Boolean> mayIncludeLogs() {
        if (!chatMemory.isMultiUser()) return Mono.just(true);
        return org.springframework.security.core.context.ReactiveSecurityContextHolder.getContext()
                .map(org.springframework.security.core.context.SecurityContext::getAuthentication)
                .map(auth -> auth.getAuthorities().stream()
                        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                        .anyMatch("ROLE_SUPER_ADMIN"::equals))
                .defaultIfEmpty(false);
    }

    // multipart, not plain JSON - the frontend sends the same FeedbackRequest shape as a "request"
    // part (application/json Blob - see FeedbackModal.tsx) alongside 0+ "files" parts, so
    // attachments are submitted atomically with the feedback itself rather than a separate
    // upload-then-register flow (unlike the Files connector - there is no standalone "feedback
    // attachment" connector for a user to manage afterward, so the extra step would buy nothing).
    @PostMapping(consumes = "multipart/form-data")
    public Mono<ResponseEntity<FeedbackResponse>> submit(
            @RequestPart("request") FeedbackRequest request,
            @RequestPart(value = "files", required = false) Flux<FilePart> files,
            ServerWebExchange exchange) {
        Flux<FilePart> fileParts = (files != null ? files : Flux.<FilePart>empty()).take(MAX_ATTACHMENTS_PER_FEEDBACK);
        String guestSessionId = ConversationAccess.guestSessionId(exchange, false);
        return Mono.zip(resolveUserId(exchange), mayIncludeLogs()).flatMap(ids -> {
            String userId = ids.getT1();
            boolean logsAllowed = ids.getT2();
            // THE SAME DOOR AS A CHAT TURN (swx-05). With guest chat off - the default - anyone who
            // could reach an Enterprise Server stored ten files and 20 MB per request in it, kept
            // for ever, and had each one relayed to Proxyma under the installation's identity,
            // spending its delivery allowance. Always open on the single-user editions.
            boolean guest = userId.isEmpty();
            if (guest && !guestQuota.isAnonymousChatAllowed()) {
                return Mono.just(ResponseEntity.status(401).<FeedbackResponse>build());
            }
            FeedbackMood mood;
            try {
                mood = FeedbackMood.valueOf(request.mood());
            } catch (Exception e) {
                return Mono.just(ResponseEntity.badRequest().<FeedbackResponse>build());
            }
            // Null/blank source (every existing floating-button/dislike call site) defaults to
            // GENERAL - only the QuotaMilestoneFeedbackModal flow ever sends QUOTA_MILESTONE.
            FeedbackSource source;
            try {
                source = request.source() == null || request.source().isBlank()
                        ? FeedbackSource.GENERAL : FeedbackSource.valueOf(request.source());
            } catch (Exception e) {
                return Mono.just(ResponseEntity.badRequest().<FeedbackResponse>build());
            }

            return Mono.fromCallable(() -> {
                Feedback feedback = new Feedback();
                feedback.setMood(mood);
                feedback.setSource(source);
                feedback.setComment(request.comment());
                // ALWAYS anonymous, whatever the request said. Feedback leaves this deployment
                // now - to a service the person filing it does not run - and "anonymous unless
                // you untick the box" is the wrong default once it crosses that boundary. The
                // request's `anonymous` flag is ignored rather than honoured, and userId and
                // submitterName are never populated, so there is no path by which an identity can
                // be attached even by a caller that still sends one. contactEmail is the single
                // exception, and the user typed it into a field that says what it is for.
                feedback.setAnonymous(true);
                feedback.setUserId(null);
                feedback.setSubmitterName(null);
                feedback.setContactEmail(blankToNull(request.contactEmail()));
                boolean ownConversation = mayReference(request.conversationId(),
                        userId == null || userId.isEmpty() ? null : userId, guestSessionId);
                feedback.setConversationId(ownConversation ? request.conversationId() : null);
                feedback.setMessageId(ownConversation ? request.messageId() : null);
                feedback.setConversationTranscriptSnapshot(
                        request.includeConversation() ? request.conversationTranscript() : null);
                feedback.setRatingsJson(toRatingsJson(request.ratings()));
                feedback.setCreatedAt(Instant.now());
                return feedbackRepository.save(feedback);
            }).subscribeOn(Schedulers.boundedElastic())
                    .flatMap(feedback -> saveAttachments(feedback, fileParts).thenReturn(feedback))
                    // Shipped AFTER the local mirror is written, deliberately. If shipping fails -
                    // no telemetry URL, air-gapped host, network down - the submission still
                    // exists here and an admin can still read it, rather than the user's words
                    // being lost because a remote service was unreachable.
                    .flatMap(feedback -> Mono.fromCallable(() -> guest && chatMemory.isMultiUser()
                                    ? keptHere(feedback)
                                    : shipAndRecord(feedback, request, mood, source, logsAllowed))
                            .subscribeOn(Schedulers.boundedElastic()))
                    .map(feedback -> ResponseEntity.ok(toResponse(feedback)));
        });
    }

    // One bad attachment (oversized, unreadable) is skipped and logged rather than failing the
    // whole feedback submission - the feedback itself (mood/comment/etc.) is the part that
    // actually matters and must never be lost over a rejected screenshot.
    private Mono<Void> saveAttachments(Feedback feedback, Flux<FilePart> files) {
        // What the files saved so far used of the shared budget. concatMap runs them one at a time,
        // so a file is measured against exactly what came before it.
        java.util.concurrent.atomic.AtomicLong used = new java.util.concurrent.atomic.AtomicLong();
        return files.concatMap(part -> saveOneAttachment(feedback, part, MAX_TOTAL_ATTACHMENT_BYTES - used.get())
                        .doOnNext(a -> used.addAndGet(a.getSizeBytes()))
                        .onErrorResume(e -> {
                            log.warn("[FeedbackController] Skipping attachment '{}' for feedback={}: {}",
                                    part.filename(), feedback.getId(), e.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<FeedbackAttachment> saveOneAttachment(Feedback feedback, FilePart part, long remainingBytes) {
        return part.content()
                .reduce(new ByteArrayOutputStream(), (baos, buf) -> {
                    // Released when the stream closes - see ChatAttachmentController.
                    try (java.io.InputStream in = buf.asInputStream(true)) {
                        in.transferTo(baos);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    if (baos.size() > remainingBytes) {
                        throw new IllegalArgumentException("Attachments exceed the "
                                + (MAX_TOTAL_ATTACHMENT_BYTES / (1024 * 1024)) + "MB allowed in total");
                    }
                    return baos;
                })
                .doOnDiscard(org.springframework.core.io.buffer.DataBuffer.class,
                        org.springframework.core.io.buffer.DataBufferUtils::release)
                .map(baos -> {
                    FeedbackAttachment attachment = new FeedbackAttachment();
                    attachment.setFeedback(feedback);
                    attachment.setFileName(part.filename());
                    attachment.setContentType(part.headers().getContentType() != null
                            ? part.headers().getContentType().toString() : "application/octet-stream");
                    attachment.setSizeBytes(baos.size());
                    attachment.setData(baos.toByteArray());
                    attachment.setCreatedAt(Instant.now());
                    return attachment;
                })
                .publishOn(Schedulers.boundedElastic())
                .map(feedbackAttachmentRepository::save);
    }

    // A visitor's feedback on a multi-user server stays in this deployment's own list: nothing ties
    // it to anyone the operator answers for, and relayed it would arrive under the installation's
    // identity and spend the allowance its signed-in users' reports need (swx-05).
    private Feedback keptHere(Feedback feedback) {
        feedback.setDeliveredToProxyma(false);
        return feedbackRepository.save(feedback);
    }

    // Builds the wire submission from what was just persisted, ships it, and records whether it
    // landed. Version, tier, edition and instance id are stamped by the shipper - they are facts
    // about this installation, not something a browser may assert.
    private Feedback shipAndRecord(Feedback feedback, FeedbackRequest request,
                                    FeedbackMood mood, FeedbackSource source, boolean logsAllowed) {
        FeedbackSubmission submission = new FeedbackSubmission();
        submission.setMood(mood.name());
        submission.setSource(source.name());
        submission.setComment(feedback.getComment());
        submission.setContactEmail(feedback.getContactEmail());
        submission.setConversationTranscript(feedback.getConversationTranscriptSnapshot());
        submission.setRatings(toRatingStrings(request.ratings()));
        submission.setSubmittedAt(feedback.getCreatedAt());

        List<FeedbackShipper.Attachment> attachments = new ArrayList<>();
        // The log is read HERE rather than uploaded by the browser: the browser has no access to
        // the server's log file, and letting a client post arbitrary bytes labelled "log" would
        // make the LOG/USER_FILE distinction meaningless.
        if (logsAllowed && request.includeLogs()) {
            String tail = logTailProvider.readTail();
            if (tail != null && !tail.isBlank()) {
                attachments.add(new FeedbackShipper.Attachment(
                        logTailProvider.fileName(), "text/plain",
                        tail.getBytes(StandardCharsets.UTF_8), FeedbackAttachmentKind.LOG));
            }
        }
        for (FeedbackAttachment a : feedbackAttachmentRepository.findByFeedbackIdIn(List.of(feedback.getId()))) {
            attachments.add(new FeedbackShipper.Attachment(
                    a.getFileName(), a.getContentType(), a.getData(), FeedbackAttachmentKind.USER_FILE));
        }

        // A rating stays in this deployment's own feedback list unless the person has chosen to share
        // usage counts - see FeedbackShipper.ratingsMayLeave(). The feedback form ships as before.
        //
        // On a multi-user server that choice is the super admin's alone, and Privacy 3.7 names the
        // RATER's consent as the basis - so only the super admin's own ratings leave; everyone
        // else's stay here. logsAllowed is exactly "this caller speaks for the installation".
        boolean isRating = Boolean.TRUE.equals(request.messageRating());
        if (isRating && (!feedbackShipper.ratingsMayLeave() || !logsAllowed)) {
            feedback.setDeliveredToProxyma(false);
            return feedbackRepository.save(feedback);
        }
        boolean delivered = feedbackShipper.ship(submission, attachments);
        feedback.setDeliveredToProxyma(delivered);
        return feedbackRepository.save(feedback);
    }

    private static Map<String, String> toRatingStrings(Map<String, FeedbackMood> ratings) {
        if (ratings == null || ratings.isEmpty()) return null;
        Map<String, String> out = new java.util.LinkedHashMap<>();
        ratings.forEach((k, v) -> out.put(k, v.name()));
        return out;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String toRatingsJson(Map<String, FeedbackMood> ratings) {
        if (ratings == null || ratings.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(ratings);
        } catch (Exception e) {
            log.warn("[FeedbackController] Failed to serialize ratings map: {}", e.getMessage());
            return null;
        }
    }

    private FeedbackResponse toResponse(Feedback f) {
        FeedbackSource source = f.getSource() != null ? f.getSource() : FeedbackSource.GENERAL;
        return new FeedbackResponse(f.getId(), f.getMood().name(), f.getComment(), f.isAnonymous(), source.name());
    }

    private Mono<String> resolveUserId(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("");
    }
}
