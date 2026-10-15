package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.exception.WorkflowAccessDeniedException;
import ai.proxyma.core.chat.service.ChatSkillService;
import ai.proxyma.core.chat.service.WorkflowService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

// CRUD for Workflows (Settings > Workflows, and the chat Attach menu's workflow creator), backed
// by WorkflowService's one-markdown-file-per-workflow storage - id is that file's basename (a slug
// derived from the name at creation), not a database identifier.
//
// Deliberately a sibling of ChatSkillController rather than a generalization of it, and mounted at
// /api/v1/chat/workflows for the same reason skills are at /api/v1/chat/skills and not under
// /api/v1/settings/: SettingsController's generic /{prefix}/{field} PATCH/DELETE mapping
// structurally overlaps any 2-segment path under that prefix, which produced a 405 on skill save.
//
// Permission model is the skill model minus the built-in concept: reading has no role gate
// (visibility is enforced inside the service via the resolved requesterId, and an invisible
// workflow on GET /{id} comes back 404, never leaking existence); creating requires a
// non-anonymous caller in multi-user mode; editing and deleting require ownership or Super Admin,
// mapped to 403 here via WorkflowAccessDeniedException.
@ConditionalOnProperty(name = "proxyma.features.workflows", havingValue = "true")
@RestController
@RequestMapping("/api/v1/chat/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    // Desktop mode has no SecurityWebFilterChain at all (proxyma-core-auth is server-profile-only),
    // so isSuperAdmin() would always resolve false there with no bypass. Desktop is
    // single-user/no-auth by design - same proxyma.multi-user split ChatSkillController uses.
    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    // Held only to reject a name a SKILL already has. "/" is one namespace to the person typing
    // into it, so the two sets must not overlap, and refusing at creation is the only place the
    // collision can be reported to somebody who can do something about it - resolving it later by
    // precedence would produce a silent winner and a bug report nobody can reproduce. The check
    // lives here rather than in either service because it needs both, and two services holding
    // each other is a bean cycle for what is request validation.
    private final ChatSkillService chatSkillService;

    public WorkflowController(WorkflowService workflowService, ChatSkillService chatSkillService) {
        this.workflowService = workflowService;
        this.chatSkillService = chatSkillService;
    }

    // Same wording both ways round (see ChatSkillController), because the two collisions are the
    // same problem seen from either side.
    static String nameCollision(String name, String otherKind) {
        return "The name \"" + name + "\" is already used by a " + otherKind + ". Both are invoked "
                + "by typing \"/\" followed by the name, so the two cannot share one - pick another.";
    }

    private void rejectIfSkillNameTaken(String name, String requesterId) {
        if (name == null || name.isBlank()) return;
        if (chatSkillService.visibleNamesLowercase(requesterId).contains(name.trim().toLowerCase(Locale.ROOT))) {
            throw new WorkflowService.InvalidWorkflowException(nameCollision(name.trim(), "skill"));
        }
    }

    // `steps` carries the ordered step list from the editor. The `index` on each incoming step is
    // ignored and re-derived from list order by the service - position IS the identity, and
    // trusting a client-supplied index would let a malformed request produce a workflow whose steps
    // run in an order the editor never showed.
    //
    // `provider` and `model` are the pair every step runs on, both optional and both meaning
    // "inherit the chat role" when absent. Sent as a pair rather than a model alone because a model
    // name means nothing without an instance to call it on - see WorkflowService.normalizeModelChoice.
    public record WorkflowRequest(String name, String shortDescription, String preamble,
            List<WorkflowService.WorkflowStep> steps, List<String> tags, List<String> tools,
            List<String> connectorNames, Boolean shared, String provider, String model) {}

    @GetMapping
    public Mono<ResponseEntity<List<WorkflowService.WorkflowSummary>>> list(ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            return isSuperAdmin().flatMap(superAdmin ->
                    Mono.fromCallable(() -> ResponseEntity.ok(workflowService.list(uid, superAdmin)))
                            .subscribeOn(Schedulers.boundedElastic()));
        });
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<WorkflowService.WorkflowDetail>> get(@PathVariable String id,
            ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            return isSuperAdmin().flatMap(superAdmin ->
                    Mono.fromCallable(() -> ResponseEntity.ok(workflowService.get(id, uid, superAdmin)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(NoSuchElementException.class,
                                    e -> Mono.just(ResponseEntity.notFound().build())));
        });
    }

    @PostMapping
    public Mono<ResponseEntity<?>> create(@RequestBody WorkflowRequest request, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            if (multiUser && uid == null) {
                return Mono.<ResponseEntity<?>>just(ResponseEntity.status(403).build());
            }
            return Mono.<ResponseEntity<?>>fromCallable(() -> {
                        rejectIfSkillNameTaken(request.name(), uid);
                        return ResponseEntity.ok(
                                workflowService.create(request.name(), request.shortDescription(),
                                        request.preamble(), orEmpty(request.steps()), uid,
                                        orEmpty(request.tags()), orEmpty(request.tools()),
                                        orEmpty(request.connectorNames()),
                                        request.provider(), request.model()));
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(WorkflowService.InvalidWorkflowException.class, WorkflowController::badRequest);
        });
    }

    @PatchMapping("/{id}")
    public Mono<ResponseEntity<?>> update(@PathVariable String id, @RequestBody WorkflowRequest request,
            ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            return isSuperAdmin().flatMap(superAdmin -> Mono.<ResponseEntity<?>>fromCallable(() -> {
                        rejectIfSkillNameTaken(request.name(), uid);
                        return ResponseEntity.ok(workflowService.update(id, request.name(),
                                request.shortDescription(), request.preamble(), orEmpty(request.steps()),
                                uid, superAdmin, orEmpty(request.tags()), orEmpty(request.tools()),
                                orEmpty(request.connectorNames()), request.shared(),
                                request.provider(), request.model()));
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(NoSuchElementException.class,
                            e -> Mono.just(ResponseEntity.notFound().build()))
                    .onErrorResume(WorkflowAccessDeniedException.class,
                            e -> Mono.just(ResponseEntity.status(403).build()))
                    .onErrorResume(WorkflowService.InvalidWorkflowException.class, WorkflowController::badRequest));
        });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String id, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            return isSuperAdmin().flatMap(superAdmin -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
                        workflowService.delete(id, uid, superAdmin);
                        return ResponseEntity.noContent().build();
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(NoSuchElementException.class,
                            e -> Mono.just(ResponseEntity.notFound().build()))
                    .onErrorResume(WorkflowAccessDeniedException.class,
                            e -> Mono.just(ResponseEntity.status(403).<Void>build())));
        });
    }

    // The step-count and empty-step rules are the one place WorkflowService refuses input instead
    // of degrading gracefully, so their messages are written to be shown to the author verbatim -
    // they explain the cost reasoning, which a bare 400 would not.
    private static Mono<ResponseEntity<?>> badRequest(WorkflowService.InvalidWorkflowException e) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", e.getMessage())));
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values != null ? values : List.of();
    }

    // Mirrors ChatSkillController's own resolveUserId exactly: Principal-based, empty/anonymous
    // resolves to "" here (converted to null at each call site above), which is also what every
    // desktop-mode request resolves to.
    private Mono<String> resolveUserId(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("");
    }

    private Mono<Boolean> isSuperAdmin() {
        if (!multiUser) return Mono.just(true);
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch("ROLE_SUPER_ADMIN"::equals))
                .defaultIfEmpty(false);
    }
}
