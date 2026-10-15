package ai.proxyma.core.registry.ingest;

import ai.proxyma.core.registry.files.upload.controller.UploadSpool;
import ai.proxyma.core.registry.model.RegisteredConnector;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE INGESTION API: documents pushed into one custom source by a script, Zapier, Make or n8n.
 *
 * <pre>
 * POST   /api/v1/ingest/sources/{sourceId}/documents          one document - JSON text, or a multipart file
 * POST   /api/v1/ingest/sources/{sourceId}/documents/batch    up to 100 JSON documents
 * DELETE /api/v1/ingest/sources/{sourceId}/documents/{id}     one document, by the sender's id
 * GET    /api/v1/ingest/sources/{sourceId}/documents          the source's documents, a page at a time
 * </pre>
 *
 * <p><b>Authorized by the source's ingest token and nothing else</b> (IngestTokenService): not a
 * session, not an MCP token, not the install's token - whoever holds those can make an ingest token,
 * and a script that writes to one source should carry a credential that can do nothing more. On
 * Enterprise Server Security lets these paths through to here; on Desktop and Home Server the access
 * gate lets them through only with a token this source's own check admits.
 *
 * <p><b>The token is checked before the body is read</b> - a body is a Mono read only after the
 * check, a multipart form only after it - so nobody without one can make the server parse, buffer or
 * spool anything.
 */
@RestController
@RequestMapping("/api/v1/ingest/sources/{sourceId}/documents")
public class IngestController {

    private final IngestService ingest;
    private final IngestTokenService tokens;

    public IngestController(IngestService ingest, IngestTokenService tokens) {
        this.ingest = ingest;
        this.tokens = tokens;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> upsert(@PathVariable String sourceId,
                                                            @RequestBody Mono<Map<String, Object>> body,
                                                            ServerWebExchange exchange) {
        return authorized(sourceId, exchange)
                .flatMap(source -> body.switchIfEmpty(Mono.error(() -> new IngestRefusedException("The request has no document in it.")))
                        .publishOn(Schedulers.boundedElastic()).map(b -> {
                    IngestService.Saved saved = ingest.upsertText(source, IngestDraft.fromJson(b));
                    return ResponseEntity.ok(saved(saved));
                }))
                .onErrorResume(IngestController::refusal);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> upsertFile(@PathVariable String sourceId,
                                                                ServerWebExchange exchange) {
        // Refused by its declared length before a byte is read, where the client says how long it is.
        long declared = exchange.getRequest().getHeaders().getContentLength();
        if (declared > IngestService.MAX_FILE_BYTES + 1024 * 1024) {
            return refusal(new IngestRefusedException.TooLarge("The file is larger than "
                    + (IngestService.MAX_FILE_BYTES / (1024 * 1024)) + " MB."));
        }
        return authorized(sourceId, exchange)
                .flatMap(source -> exchange.getMultipartData().flatMap(form -> withFile(source, form)))
                .onErrorResume(IngestController::refusal);
    }

    private Mono<ResponseEntity<Map<String, Object>>> withFile(RegisteredConnector source,
                                                               MultiValueMap<String, Part> form) {
        Part part = form.getFirst("file");
        if (!(part instanceof FilePart file)) {
            return Mono.error(new IngestRefusedException("Send the document as a form field named file."));
        }
        IngestDraft draft = new IngestDraft(field(form, "id"), field(form, "title"), null, null, field(form, "url"),
                field(form, "author"), field(form, "createdAt"), field(form, "updatedAt"), metadata(field(form, "metadata")));
        // To a file AS IT ARRIVES, bounded as it is counted (UploadSpool, as an upload is), and removed
        // whatever happens - kept by moving it into the store, refused by deleting it here.
        return Mono.fromCallable(() -> Files.createTempFile("proxyma-ingest-", ".part"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(spool -> UploadSpool.toFile(file.content(), spool, IngestService.MAX_FILE_BYTES)
                        .onErrorMap(IllegalArgumentException.class, e -> e instanceof IngestRefusedException ? e
                                : new IngestRefusedException.TooLarge("The file is larger than "
                                        + (IngestService.MAX_FILE_BYTES / (1024 * 1024)) + " MB."))
                        .publishOn(Schedulers.boundedElastic())
                        .map(size -> ResponseEntity.ok(saved(ingest.upsertFile(source, draft,
                                new IngestService.SpooledFile(spool, file.filename(), size)))))
                        .doFinally(signal -> IngestService.deleteQuietly(spool)));
    }

    private static String field(MultiValueMap<String, Part> form, String name) {
        Part part = form.getFirst(name);
        return part instanceof FormFieldPart f ? f.value() : null;
    }

    // A multipart form carries its metadata as one JSON field.
    @SuppressWarnings("unchecked")
    private static Map<String, ?> metadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> m) return (Map<String, ?>) m;
        } catch (Exception e) {
            // refused below
        }
        throw new IngestRefusedException("metadata must be a JSON object of names and values.");
    }

    @PostMapping(path = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> batch(@PathVariable String sourceId,
                                                           @RequestBody Mono<Map<String, Object>> body,
                                                           ServerWebExchange exchange) {
        return authorized(sourceId, exchange)
                .flatMap(source -> body.switchIfEmpty(Mono.error(() -> new IngestRefusedException("The request has no document in it.")))
                        .publishOn(Schedulers.boundedElastic()).map(b -> {
                    Object raw = b.get("documents");
                    if (!(raw instanceof List<?> list)) {
                        throw new IngestRefusedException("documents is required - a list of documents.");
                    }
                    List<IngestDraft> drafts = new ArrayList<>(list.size());
                    for (int i = 0; i < list.size(); i++) {
                        Object entry = list.get(i);
                        if (!(entry instanceof Map<?, ?> m)) {
                            throw new IngestService.BatchRefusedException(i,
                                    new IngestRefusedException("Each entry of documents must be a document object."));
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, ?> doc = (Map<String, ?>) m;
                        try {
                            drafts.add(IngestDraft.fromJson(doc));
                        } catch (IngestRefusedException e) {
                            throw new IngestService.BatchRefusedException(i, e);
                        }
                    }
                    List<Map<String, Object>> out = ingest.upsertBatch(source, drafts).stream()
                            .map(IngestController::saved).toList();
                    return ResponseEntity.ok(Map.<String, Object>of("documents", out));
                }))
                .onErrorResume(IngestController::refusal);
    }

    @DeleteMapping("/{externalId}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String sourceId,
                                                            @PathVariable String externalId,
                                                            ServerWebExchange exchange) {
        return authorized(sourceId, exchange)
                .publishOn(Schedulers.boundedElastic())
                .map(source -> ingest.delete(source, externalId)
                        ? ResponseEntity.ok(Map.<String, Object>of("id", externalId, "deleted", true))
                        : ResponseEntity.status(404).body(Map.<String, Object>of(
                                "error", "This source holds no document with this id.")))
                .onErrorResume(IngestController::refusal);
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String sourceId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          ServerWebExchange exchange) {
        return authorized(sourceId, exchange)
                .publishOn(Schedulers.boundedElastic())
                .map(source -> ResponseEntity.ok(ingest.list(source, page, size)))
                .onErrorResume(IngestController::refusal);
    }

    // The source this request may write to, read off the token alone and off the event loop.
    private Mono<RegisteredConnector> authorized(String sourceId, ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return Mono.fromCallable(() -> {
            UUID id;
            try {
                id = UUID.fromString(sourceId);
            } catch (IllegalArgumentException e) {
                throw new IngestTokenService.IngestAuthException("There is no custom source with this id.", 404);
            }
            return tokens.authenticate(id, authorization);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    static Map<String, Object> saved(IngestService.Saved saved) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", saved.id());
        m.put("outcome", saved.outcome());
        return m;
    }

    // Every refusal as {error} with its status - and, for a batch, which document it was.
    static Mono<ResponseEntity<Map<String, Object>>> refusal(Throwable e) {
        Map<String, Object> body = new LinkedHashMap<>();
        int status;
        if (e instanceof IngestTokenService.IngestAuthException auth) {
            status = auth.status();
            body.put("error", auth.getMessage());
        } else if (e instanceof IngestRefusedException refused) {
            status = refused.status();
            body.put("error", refused.getMessage());
            if (refused instanceof IngestService.BatchRefusedException batch) body.put("index", batch.index());
        } else if (isTooLarge(e)) {
            status = 413;
            body.put("error", "The request is larger than the server takes in one body. Send fewer documents "
                    + "in a batch, or a long document as a file.");
        } else if (e instanceof ServerWebInputException) {
            status = 400;
            body.put("error", "The request body is not a JSON document.");
        } else if (e instanceof org.springframework.core.codec.DecodingException) {
            status = 400;
            body.put("error", "The request is not a multipart form with the document in a field named file.");
        } else {
            return Mono.error(e);
        }
        return Mono.just(ResponseEntity.status(status).body(body));
    }

    private static boolean isTooLarge(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DataBufferLimitException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}
