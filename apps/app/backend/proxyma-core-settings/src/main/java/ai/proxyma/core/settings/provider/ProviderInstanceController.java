package ai.proxyma.core.settings.provider;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.settings.access.CallerRoles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * CRUD for provider instances, and the protocol catalogue the Add-provider form renders.
 *
 * Mounted under /api/v1/providers/, which SecurityConfig restricts to ADMIN/SUPER_ADMIN. An ADMIN
 * READS; only a SUPER_ADMIN adds, changes or removes one (settings-connections-usage-09), as for
 * every other credential-bearing surface: an edit keeps the stored key when none is sent, so whoever
 * may change the base URL may send that key to a server of their choosing. Desktop and Home Server
 * have no filter chain at all and one user, who is both - see CallerRoles.
 *
 * Every handler hops to boundedElastic: the service is blocking JPA, and a repository call made
 * directly on the Netty event loop stalls every concurrent request rather than only its own.
 */
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderInstanceController {

    private final ProviderInstanceService service;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public ProviderInstanceController(ProviderInstanceService service) {
        this.service = service;
    }

    /**
     * `apiKey` is write-only. It is absent from every response, so a client that echoes back what
     * it was given sends null - which means "unchanged". Clearing a stored key is therefore an
     * explicit `clearApiKey`, not an empty string, which is only ever an accident.
     */
    public record ProviderRequest(String name, String protocol, String baseUrl, String apiKey,
            Boolean clearApiKey, Map<String, String> headers, Boolean nativeRerank, String api,
            Boolean blockingCalls) {}

    @GetMapping("/protocols")
    public Mono<ResponseEntity<List<ProviderInstanceView.ProviderProtocolView>>> protocols() {
        return Mono.just(ResponseEntity.ok(ProviderInstanceView.protocols()));
    }

    @GetMapping("/instances")
    public Mono<ResponseEntity<List<ProviderInstanceView>>> list() {
        return Mono.fromCallable(() -> ResponseEntity.ok(service.list()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/instances/{id}")
    public Mono<ResponseEntity<ProviderInstanceView>> get(@PathVariable UUID id) {
        return Mono.fromCallable(() -> ResponseEntity.ok(service.get(id)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(NoSuchElementException.class, e -> Mono.just(ResponseEntity.notFound().build()));
    }

    @PostMapping("/instances")
    public Mono<ResponseEntity<?>> create(@RequestBody ProviderRequest request, ServerWebExchange exchange) {
        return superAdminOnly(() -> userId(exchange).flatMap(uid -> Mono.<ResponseEntity<?>>fromCallable(() -> {
                    ProviderInstanceView created = service.create(toInput(request));
                    audit("provider.added", "Provider added: ", created.name(), uid, request, null);
                    return ResponseEntity.ok(created);
                })
                .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(ProviderInstanceService.InvalidProviderException.class,
                        ProviderInstanceController::badRequest));
    }

    @PatchMapping("/instances/{id}")
    public Mono<ResponseEntity<?>> update(@PathVariable UUID id, @RequestBody ProviderRequest request,
                                          ServerWebExchange exchange) {
        return superAdminOnly(() -> userId(exchange).flatMap(uid -> Mono.<ResponseEntity<?>>fromCallable(() -> {
                    String before = service.get(id).name();
                    ProviderInstanceView updated = service.update(id, toInput(request));
                    audit("provider.changed", "Provider changed: ", updated.name(), uid, request,
                            before.equals(updated.name()) ? null : before);
                    return ResponseEntity.ok(updated);
                })
                .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(NoSuchElementException.class, e -> Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(ProviderInstanceService.InvalidProviderException.class,
                        ProviderInstanceController::badRequest));
    }

    @DeleteMapping("/instances/{id}")
    public Mono<ResponseEntity<?>> delete(@PathVariable UUID id, ServerWebExchange exchange) {
        return superAdminOnly(() -> userId(exchange).flatMap(uid -> Mono.<ResponseEntity<?>>fromCallable(() -> {
                    String name = service.get(id).name();
                    service.delete(id);
                    audit("provider.removed", "Provider removed: ", name, uid, null, null);
                    return ResponseEntity.noContent().build();
                })
                .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(NoSuchElementException.class, e -> Mono.just(ResponseEntity.notFound().build())));
    }

    // Refused before anything is read or written, with the reason the page shows.
    private Mono<ResponseEntity<?>> superAdminOnly(java.util.function.Supplier<Mono<ResponseEntity<?>>> write) {
        return CallerRoles.isSuperAdmin(multiUser).flatMap(allowed -> allowed ? write.get()
                : Mono.just(ResponseEntity.status(403).body(
                        Map.of("error", "Only a super administrator can change AI providers."))));
    }

    // Recorded here rather than in the service: the service is also the sandbox seeder's and the
    // configuration import's path, which are not somebody editing a provider (the import records
    // itself). Never the key or a header's value - a header can carry a credential - only whether
    // the key was set or cleared and which headers there are.
    private static void audit(String action, String summary, String name, String uid,
                              ProviderRequest request, String previousName) {
        AuditEntry.Builder entry = AuditEntry.of(AuditCategory.CONFIG, action)
                .user(uid)
                .target(name)
                .summary(summary + name)
                .detail("renamedFrom", previousName);
        if (request != null) {
            entry.detail("protocol", request.protocol())
                    .detail("baseUrl", request.baseUrl())
                    .detail("apiKey", Boolean.TRUE.equals(request.clearApiKey()) ? "cleared"
                            : request.apiKey() != null && !request.apiKey().isBlank() ? "set" : null)
                    .detail("headers", request.headers() == null || request.headers().isEmpty() ? null
                            : String.join(", ", request.headers().keySet()))
                    .detail("nativeRerank", request.nativeRerank())
                    .detail("api", request.api())
                    .detail("blockingCalls", request.blockingCalls());
        }
        entry.record();
    }

    // "" when there is no principal (Desktop, Home Server), which the audit log files as the local user.
    private static Mono<String> userId(ServerWebExchange exchange) {
        return CallerRoles.userId(exchange);
    }

    // The validation messages are written to be shown to the user verbatim - they explain what to
    // do (Azure needs a base URL, that name is taken), which a bare 400 would not.
    private static Mono<ResponseEntity<?>> badRequest(ProviderInstanceService.InvalidProviderException e) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", e.getMessage())));
    }

    private static ProviderInstanceService.ProviderInstanceInput toInput(ProviderRequest r) {
        ProviderProtocol protocol = ProviderProtocol.fromValue(r.protocol());
        return new ProviderInstanceService.ProviderInstanceInput(
                r.name(), protocol, r.baseUrl(), r.apiKey(),
                Boolean.TRUE.equals(r.clearApiKey()), r.headers(), r.nativeRerank(),
                GatewayApi.fromValue(r.api()), r.blockingCalls());
    }
}
