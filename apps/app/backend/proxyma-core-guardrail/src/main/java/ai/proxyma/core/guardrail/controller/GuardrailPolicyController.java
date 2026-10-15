package ai.proxyma.core.guardrail.controller;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.guardrail.policy.PolicyDocument;
import ai.proxyma.core.guardrail.policy.PolicyLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * Managing the guardrail's policy corpus: what is in it, and adding or removing a document.
 *
 * <p>WebFlux, and every call into the library is offloaded to {@code boundedElastic} - the library
 * reads and writes files and holds a lock while it does. A blocking call left on the event loop
 * stalls every concurrent request rather than just its own, which is the rule the whole backend
 * follows and the reason 124 other call sites look like this.
 *
 * <p>Indexing itself is NOT awaited. Converting a PDF and embedding it takes as long as it takes,
 * and a request that blocked on it would time out on exactly the documents most worth adding. A
 * document is stored, answered as {@code PENDING}, and moves to {@code INDEXED} or {@code FAILED}
 * on the library's own thread - which is why the list carries a status at all.
 */
@RestController
@RequestMapping("/api/v1/guardrail/policies")
public class GuardrailPolicyController {

    private static final Logger log = LoggerFactory.getLogger(GuardrailPolicyController.class);

    private final PolicyLibrary library;
    private final boolean multiUser;

    public GuardrailPolicyController(PolicyLibrary library,
                                     @org.springframework.beans.factory.annotation.Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.library = library;
        this.multiUser = multiUser;
    }

    private static <T> Mono<ResponseEntity<T>> forbidden() {
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }

    private static void audit(GuardrailWriter writer, String action, String target, String summary) {
        AuditEntry.of(AuditCategory.CONFIG, action)
                .user(writer.userId())
                .target(target)
                .summary(summary)
                .record();
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list() {
        return Mono.fromCallable(() -> {
            List<PolicyDocument> docs = library.list();
            return ResponseEntity.ok(Map.<String, Object>of(
                    "documents", docs,
                    "maxBytes", PolicyLibrary.MAX_DOCUMENT_BYTES));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Object>> upload(@RequestPart("file") FilePart file,
                                               @RequestParam(defaultValue = "RULES") String kind) {
        return GuardrailWriter.of(multiUser).flatMap(writer -> !writer.allowed() ? forbidden() : file.content()
                // DataBuffer must be released. Read into memory because the library needs the whole
                // document to store it - and REFUSED the moment it passes MAX_DOCUMENT_BYTES: the
                // check after the whole upload was buffered let a multi-gigabyte body exhaust the
                // heap before it ever ran.
                .reduce(new ByteArrayOutputStream(), (out, buffer) -> {
                    try {
                        if (out.size() + (long) buffer.readableByteCount() > PolicyLibrary.MAX_DOCUMENT_BYTES) {
                            throw new IllegalArgumentException("A policy document may be at most "
                                    + (PolicyLibrary.MAX_DOCUMENT_BYTES / (1024 * 1024))
                                    + " MB. This corpus is searched on every turn.");
                        }
                        byte[] chunk = new byte[buffer.readableByteCount()];
                        buffer.read(chunk);
                        out.write(chunk);
                    } catch (IllegalArgumentException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException("Could not read the upload.", e);
                    } finally {
                        org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                    }
                    return out;
                })
                .flatMap(out -> Mono.fromCallable(() -> {
                            PolicyDocument doc = library.addFile(parseKind(kind), file.filename(), out.toByteArray());
                            audit(writer, "guardrail-policy.uploaded", doc.id(),
                                    "Guardrail " + doc.kindOrDefault().name().toLowerCase(java.util.Locale.ROOT)
                                            + " document added: " + doc.displayName());
                            return ResponseEntity.<Object>ok(doc);
                        })
                        .subscribeOn(Schedulers.boundedElastic())))
                .onErrorResume(GuardrailPolicyController::toResponse);
    }

    // THE "ADD A POLICY FROM A URL" ENDPOINT IS GONE, removed 2026-09-09. Its UI field was taken
    // out earlier, which closed the hole at the surface and left a POST here that would fetch any
    // address an authenticated caller named - the shape of a server-side request forgery, aimed by
    // whoever could reach this API rather than by the operator who owns the policy corpus. An
    // endpoint nothing calls is not a feature, and one that makes outbound requests on request is
    // worth deleting rather than documenting.
    //
    // Uploading the file is what remains, and it was always the better answer: a policy is a
    // document somebody wrote, and PolicyLibrary's own note says a page that needs JavaScript to
    // render is better saved and uploaded than scraped.

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String id) {
        return GuardrailWriter.of(multiUser).flatMap(writer -> !writer.allowed() ? GuardrailPolicyController.<Void>forbidden()
                : Mono.fromCallable(() -> {
                            if (!library.delete(id)) return ResponseEntity.notFound().<Void>build();
                            audit(writer, "guardrail-policy.deleted", id, "Guardrail policy document removed");
                            return ResponseEntity.noContent().<Void>build();
                        })
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /** Re-converts and re-embeds the whole corpus - for after an embedding-model change. */
    @PostMapping("/reindex")
    public Mono<ResponseEntity<Void>> reindex() {
        return GuardrailWriter.of(multiUser).flatMap(writer -> !writer.allowed() ? GuardrailPolicyController.<Void>forbidden()
                : Mono.fromCallable(() -> {
                    library.reindexAll();
                    audit(writer, "guardrail-policy.reindexed", null, "Guardrail policy corpus re-indexed");
                    return ResponseEntity.accepted().<Void>build();
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Which corpus an upload joins. Unrecognized or absent means RULES.
     *
     * <p>Defaulting rather than rejecting, because RULES is what every caller meant before the
     * examples corpus existed - and because the wrong default here is the safe one: a document
     * filed as a rule is judged by a model, while one filed as an example is matched on raw
     * similarity and blocks on its own.
     */
    private static PolicyDocument.Kind parseKind(String raw) {
        if (raw == null) return PolicyDocument.Kind.RULES;
        try {
            return PolicyDocument.Kind.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PolicyDocument.Kind.RULES;
        }
    }

    /**
     * Turns a failure into something a person can act on.
     *
     * <p>An {@code IllegalArgumentException} here is always a message written for the operator -
     * the file is empty, the size cap - so it is passed through as a 400 verbatim. Anything else is
     * a 502 with its own message: these are conversions of the operator's OWN document, so the
     * detail is theirs to see and never a user's content.
     */
    private static Mono<ResponseEntity<Object>> toResponse(Throwable e) {
        if (e instanceof IllegalArgumentException) {
            return Mono.just(ResponseEntity.badRequest().body((Object) Map.of("error", String.valueOf(e.getMessage()))));
        }
        log.warn("[GuardrailPolicy] request failed: {}", e.toString());
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body((Object) Map.of("error", message)));
    }
}
