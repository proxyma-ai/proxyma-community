package ai.proxyma.core.registry.ingest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A custom source's ingestion API as its OWNER manages it on the source's page - the address to push
 * to, its tokens, a new one, and revoking one:
 *
 * <pre>
 * GET    /api/v1/indexing/{id}/ingest                     the endpoint, the limits and the tokens
 * POST   /api/v1/indexing/{id}/ingest/tokens              {label?} - a new token, shown this once
 * DELETE /api/v1/indexing/{id}/ingest/tokens/{tokenId}    revoke one
 * </pre>
 *
 * <p>Under /api/v1/indexing, so it takes the session every other source action does: a signed-in
 * user on Enterprise Server, the access code's cookie or the install's token on Desktop and Home
 * Server. Never an ingest token - that only reaches the ingestion endpoints themselves.
 */
@RestController
@RequestMapping("/api/v1/indexing/{id}/ingest")
public class IngestTokenController {

    private final IngestTokenService tokens;

    public IngestTokenController(IngestTokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> describe(@PathVariable UUID id, ServerWebExchange exchange) {
        return caller(exchange).flatMap(name -> Mono.fromCallable(() -> {
            String uid = uid(name);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tokens", tokens.list(id, uid));
            // A path, never an absolute URL: the page puts its own origin in front of it, which is the
            // address the person reached it on - a Host header is no witness to that.
            body.put("endpoint", "/api/v1/ingest/sources/" + id + "/documents");
            Map<String, Object> limits = new LinkedHashMap<>();
            limits.put("maxTextChars", IngestDraft.MAX_TEXT_CHARS);
            limits.put("maxFileBytes", IngestService.MAX_FILE_BYTES);
            limits.put("maxBatch", IngestService.MAX_BATCH);
            limits.put("maxDocuments", IngestService.MAX_DOCUMENTS_PER_SOURCE);
            limits.put("maxTokens", IngestTokenService.MAX_TOKENS_PER_SOURCE);
            body.put("limits", limits);
            return ResponseEntity.ok(body);
        }).subscribeOn(Schedulers.boundedElastic())).onErrorResume(IngestController::refusal);
    }

    @PostMapping("/tokens")
    public Mono<ResponseEntity<Map<String, Object>>> create(@PathVariable UUID id,
                                                            @RequestBody(required = false) Map<String, Object> body,
                                                            ServerWebExchange exchange) {
        Object label = body == null ? null : body.get("label");
        return caller(exchange).flatMap(name -> Mono.fromCallable(() -> {
            String uid = uid(name);
            IngestTokenService.Created created = tokens.create(id, uid, label == null ? null : label.toString());
            Map<String, Object> out = new LinkedHashMap<>(created.listed());
            out.put("token", created.token());
            return ResponseEntity.ok(out);
        }).subscribeOn(Schedulers.boundedElastic())).onErrorResume(IngestController::refusal);
    }

    @DeleteMapping("/tokens/{tokenId}")
    public Mono<ResponseEntity<Map<String, Object>>> revoke(@PathVariable UUID id, @PathVariable UUID tokenId,
                                                            ServerWebExchange exchange) {
        return caller(exchange).flatMap(name -> Mono.fromCallable(() -> tokens.revoke(id, tokenId, uid(name))
                ? ResponseEntity.ok(Map.<String, Object>of("id", tokenId.toString(), "revoked", true))
                : ResponseEntity.status(404).body(Map.<String, Object>of("error", "This source has no such token.")))
                .subscribeOn(Schedulers.boundedElastic())).onErrorResume(IngestController::refusal);
    }

    // The signed-in user's id; "" on the single-user editions, which carry no principal - read as null.
    private static Mono<String> caller(ServerWebExchange exchange) {
        return exchange.getPrincipal().map(Principal::getName).defaultIfEmpty("");
    }

    static String uid(String name) {
        return name == null || name.isEmpty() ? null : name;
    }
}
