package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.domain.FeedbackMood;
import ai.proxyma.core.chat.domain.FeedbackSource;
import ai.proxyma.core.chat.dto.AdminFeedbackItemResponse;
import ai.proxyma.core.chat.dto.ResolveFeedbackRequest;
import ai.proxyma.core.chat.model.Feedback;
import ai.proxyma.core.chat.repository.FeedbackAttachmentRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Gated by SecurityConfig's "/api/v1/admin/**" -> hasAnyRole(SUPER_ADMIN, ADMIN) rule, no
// security wiring needed here - same pattern as the old AdminChatFeedbackController this
// replaces. Single admin view of ALL feedback (general floating-button + chat like/dislike),
// not two parallel systems.
@RestController
@RequestMapping("/api/v1/admin/feedback")
public class AdminFeedbackController {

    private static final Logger log = LoggerFactory.getLogger(AdminFeedbackController.class);

    private final FeedbackRepository feedbackRepository;
    private final FeedbackAttachmentRepository feedbackAttachmentRepository;
    private final ObjectMapper objectMapper;

    public AdminFeedbackController(FeedbackRepository feedbackRepository,
                                    FeedbackAttachmentRepository feedbackAttachmentRepository,
                                    ObjectMapper objectMapper) {
        this.feedbackRepository = feedbackRepository;
        this.feedbackAttachmentRepository = feedbackAttachmentRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String mood) {
        return Mono.fromCallable(() -> {
            PageRequest pageRequest = pageRequest(page, size);
            Page<Feedback> result;
            if (mood != null && !mood.isBlank()) {
                result = feedbackRepository.findByMoodOrderByCreatedAtDesc(
                        FeedbackMood.valueOf(mood), pageRequest);
            } else {
                result = feedbackRepository.findAllByOrderByCreatedAtDesc(pageRequest);
            }
            Map<Long, List<FeedbackAttachmentRepository.Meta>> attachmentsByFeedbackId = attachmentsByFeedbackId(
                    result.getContent().stream().map(Feedback::getId).toList());
            List<AdminFeedbackItemResponse> items = result.getContent().stream()
                    .map(f -> toItemResponse(f, attachmentsByFeedbackId.getOrDefault(f.getId(), List.of())))
                    .toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", items);
            body.put("page", result.getNumber());
            body.put("hasMore", result.hasNext());
            body.put("totalItems", result.getTotalElements());
            body.put("totalPages", result.getTotalPages());
            return ResponseEntity.ok(body);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * The page asked for, numbered as the Feedback tab shows it (twenty a page): never before the
     * first, one to a hundred rows. A negative page or size was a 500 from PageRequest.
     */
    static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
    }

    @PatchMapping("/{id}/resolve")
    public Mono<ResponseEntity<AdminFeedbackItemResponse>> resolve(
            @PathVariable Long id, @RequestBody(required = false) ResolveFeedbackRequest request) {
        return Mono.fromCallable(() -> {
            return feedbackRepository.findById(id)
                    .map(f -> {
                        f.setResolved(true);
                        f.setResolvedAt(Instant.now());
                        f.setResolutionNote(request != null ? request.note() : null);
                        Feedback saved = feedbackRepository.save(f);
                        return ResponseEntity.ok(toItemResponse(saved, feedbackAttachmentRepository.findMetaByFeedbackIdIn(List.of(id))));
                    })
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/{id}/unresolve")
    public Mono<ResponseEntity<AdminFeedbackItemResponse>> unresolve(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            return feedbackRepository.findById(id)
                    .map(f -> {
                        f.setResolved(false);
                        f.setResolvedAt(null);
                        f.setResolutionNote(null);
                        Feedback saved = feedbackRepository.save(f);
                        return ResponseEntity.ok(toItemResponse(saved, feedbackAttachmentRepository.findMetaByFeedbackIdIn(List.of(id))));
                    })
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        // FeedbackAttachment's @OnDelete(CASCADE) FK (see that entity's javadoc) drops attachment
        // rows automatically once the Feedback row itself is deleted - no manual cleanup needed.
        return Mono.<Void>fromRunnable(() -> feedbackRepository.deleteById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    // Raw bytes, not JSON - FeedbackDetailModal links directly to this URL (browser-native
    // download) rather than fetching through the app's JSON http client. Ownership isn't checked
    // per-attachment since the whole /api/v1/admin/** prefix is already role-gated (see this
    // class's own javadoc) - any admin/super-admin may download any feedback's attachments.
    @GetMapping("/attachments/{attachmentId}")
    public Mono<ResponseEntity<byte[]>> downloadAttachment(@PathVariable Long attachmentId) {
        return Mono.fromCallable(() -> feedbackAttachmentRepository.findById(attachmentId)
                        .map(a -> ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + sanitizeFileName(a.getFileName()) + "\"")
                                .contentType(a.getContentType() != null
                                        ? MediaType.parseMediaType(a.getContentType()) : MediaType.APPLICATION_OCTET_STREAM)
                                .body(a.getData()))
                        .orElseGet(() -> ResponseEntity.notFound().build()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // Strips characters that would break the Content-Disposition header's quoted-string value
    // (a literal '"' or a raw newline from a maliciously-named upload) - the name itself was never
    // validated at upload time (see FeedbackController.saveOneAttachment), just stored verbatim.
    private String sanitizeFileName(String name) {
        return name == null ? "attachment" : name.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }

    private Map<Long, List<FeedbackAttachmentRepository.Meta>> attachmentsByFeedbackId(List<Long> feedbackIds) {
        if (feedbackIds.isEmpty()) return Collections.emptyMap();
        return feedbackAttachmentRepository.findMetaByFeedbackIdIn(feedbackIds).stream()
                .collect(Collectors.groupingBy(FeedbackAttachmentRepository.Meta::feedbackId));
    }

    private AdminFeedbackItemResponse toItemResponse(Feedback f, List<FeedbackAttachmentRepository.Meta> attachments) {
        FeedbackSource source = f.getSource() != null ? f.getSource() : FeedbackSource.GENERAL;
        List<AdminFeedbackItemResponse.AttachmentSummary> attachmentSummaries = attachments.stream()
                .map(a -> new AdminFeedbackItemResponse.AttachmentSummary(
                        a.id(), a.fileName(), a.contentType(), a.sizeBytes()))
                .toList();
        return new AdminFeedbackItemResponse(
                f.getId(), f.getMood().name(), f.getComment(), f.isAnonymous(),
                f.getUserId(), f.getSubmitterName(), f.getConversationId(), f.getMessageId(),
                f.getConversationTranscriptSnapshot(), f.getCreatedAt(),
                f.isResolved(), f.getResolvedAt(), f.getResolutionNote(),
                source.name(), fromRatingsJson(f.getRatingsJson()), attachmentSummaries);
    }

    private Map<String, FeedbackMood> fromRatingsJson(String ratingsJson) {
        if (ratingsJson == null || ratingsJson.isBlank()) return null;
        try {
            return objectMapper.readValue(ratingsJson, new TypeReference<Map<String, FeedbackMood>>() {});
        } catch (Exception e) {
            log.warn("[AdminFeedbackController] Failed to deserialize ratings JSON: {}", e.getMessage());
            return null;
        }
    }
}
