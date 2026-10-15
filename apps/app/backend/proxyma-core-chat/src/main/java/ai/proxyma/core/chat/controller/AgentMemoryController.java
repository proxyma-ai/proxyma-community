package ai.proxyma.core.chat.controller;

import ai.proxyma.core.search.service.AgentMemoryService;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryCategory;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The agent's long-term memory, for the person it is about: Settings > Memory (maintainer's
 * feedback, 2026-10-03 - the user must be able to see and correct what the agent keeps).
 *
 * <p>SCOPED TO THE CALLER, always, and from the principal - never from a request parameter. On
 * Enterprise Server every signed-in user reads and edits their own memory and nobody else's, an
 * administrator included: what the agent noted about a person is not an operator surface. Desktop
 * and Home Server have one user, filed under {@link AgentMemoryService#LOCAL_USER_ID} exactly as
 * the agent's own tools file it. A server visitor with no account has no memory to show.
 */
@RestController
@RequestMapping("/api/v1/memory")
public class AgentMemoryController {

    // A list is for reading in full; the agent's own entries are short and capped at 2000 characters.
    private static final int MAX_ENTRIES = 5000;
    // One request's worth of ids. The page selects at most what it shows.
    private static final int MAX_IDS = MAX_ENTRIES;

    private final AgentMemoryService memory;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public AgentMemoryController(AgentMemoryService memory) {
        this.memory = memory;
    }

    public record EditRequest(String text, String category) {}
    public record IdsRequest(List<String> ids, String category) {}

    @GetMapping
    public Mono<ResponseEntity<List<MemoryEntry>>> list(ServerWebExchange exchange) {
        return owner(exchange).flatMap(owner -> owner.isEmpty()
                ? Mono.just(ResponseEntity.status(401).<List<MemoryEntry>>build())
                : Mono.fromCallable(() -> ResponseEntity.ok(memory.list(owner.get(), null, MAX_ENTRIES)))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<MemoryEntry>> edit(@PathVariable String id, @RequestBody EditRequest request,
                                                  ServerWebExchange exchange) {
        return owner(exchange).flatMap(owner -> {
            if (owner.isEmpty()) return Mono.just(ResponseEntity.status(401).<MemoryEntry>build());
            Optional<MemoryCategory> category = category(request.category());
            if (request.category() != null && category.isEmpty()) {
                return Mono.just(ResponseEntity.badRequest().<MemoryEntry>build());
            }
            return Mono.fromCallable(() -> memory.edit(owner.get(), id, request.text(), category.orElse(null))
                            .map(ResponseEntity::ok)
                            .orElseGet(() -> ResponseEntity.notFound().build()))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    @PostMapping("/delete")
    public Mono<ResponseEntity<Map<String, Integer>>> delete(@RequestBody IdsRequest request,
                                                            ServerWebExchange exchange) {
        return owner(exchange).flatMap(owner -> {
            if (owner.isEmpty()) return Mono.just(ResponseEntity.status(401).<Map<String, Integer>>build());
            List<String> ids = ids(request);
            if (ids == null) return Mono.just(ResponseEntity.badRequest().<Map<String, Integer>>build());
            return Mono.fromCallable(() -> ResponseEntity.ok(Map.of("deleted", memory.delete(owner.get(), ids))))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    // Several entries moved to one category - the list's "About you" / "Knowledge" actions.
    @PostMapping("/category")
    public Mono<ResponseEntity<Map<String, Integer>>> recategorize(@RequestBody IdsRequest request,
                                                                  ServerWebExchange exchange) {
        return owner(exchange).flatMap(owner -> {
            if (owner.isEmpty()) return Mono.just(ResponseEntity.status(401).<Map<String, Integer>>build());
            List<String> ids = ids(request);
            Optional<MemoryCategory> category = category(request.category());
            if (ids == null || category.isEmpty()) {
                return Mono.just(ResponseEntity.badRequest().<Map<String, Integer>>build());
            }
            return Mono.fromCallable(() -> {
                int changed = 0;
                for (String id : ids) {
                    if (memory.edit(owner.get(), id, null, category.get()).isPresent()) changed++;
                }
                return ResponseEntity.ok(Map.of("changed", changed));
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }

    private static List<String> ids(IdsRequest request) {
        if (request == null || request.ids() == null || request.ids().size() > MAX_IDS) return null;
        return request.ids();
    }

    private static Optional<MemoryCategory> category(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(MemoryCategory.valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // Whose memory this request may touch: the signed-in user's, Desktop's one user, or nobody.
    Mono<Optional<String>> owner(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .filter(name -> !name.isBlank())
                .map(Optional::of)
                .defaultIfEmpty(multiUser ? Optional.empty() : Optional.of(AgentMemoryService.LOCAL_USER_ID));
    }
}
