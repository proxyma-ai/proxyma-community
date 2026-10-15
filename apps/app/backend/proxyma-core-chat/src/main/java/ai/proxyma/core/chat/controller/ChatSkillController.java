package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.exception.SkillAccessDeniedException;
import ai.proxyma.core.chat.service.ChatSkillService;
import ai.proxyma.core.chat.service.WorkflowService;
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

// CRUD for chat Skills (Settings > Skills, and the chat Attach menu's skill picker/creator),
// backed by ChatSkillService's one-markdown-file-per-skill storage - id is that file's basename
// (a slug derived from the skill's name at creation), not a database identifier.
//
// Ownership/visibility model (see ChatSkillService's class javadoc for the full rule): every user
// can create and own skills now, not just admins, so reading (list/get) has NO role gate at all -
// visibility is enforced inside the service via the resolved requesterId, and an invisible skill
// on GET /{id} comes back 404 (never leaking existence). Creating requires only a non-anonymous
// caller in multi-user mode. Editing/deleting requires ownership (or Super Admin) - enforced
// inside the service, mapped to 403 here via SkillAccessDeniedException.
//
// Deliberately NOT under /api/v1/settings/ - SettingsController's generic
// /{prefix}/{field} PATCH/DELETE mapping structurally overlaps any 2-segment path under that
// prefix ("chat/skills" == prefix "chat" + field "skills"), which produced a 405 on skill save.
// Skills are a connector collection, not a settings scalar field, so this gets its own top-level
// path instead, matching ChatController's /api/v1/chat/* convention.
@RestController
@RequestMapping("/api/v1/chat/skills")
public class ChatSkillController {

    private final ChatSkillService chatSkillService;

    // Desktop mode has no SecurityWebFilterChain at all (proxyma-core-auth, the only module that
    // defines one, is server-profile-only) - Spring Boot's reactive security auto-configuration
    // only ever registers a fallback deny-all ReactiveAuthenticationManager bean when none is
    // present, never a filter that populates ReactiveSecurityContextHolder, so
    // isSuperAdmin() below would always resolve false there with no bypass. Desktop is
    // single-user/no-auth by design, so role checks are meaningless in that mode - this mirrors
    // the same proxyma.multi-user split already used by ReadTextFileTool/ToolUserContext for the
    // identical desktop-vs-server distinction.
    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    // Held only to reject a name a WORKFLOW already has - the mirror of the check
    // WorkflowController makes against skills, and there for the same reason: "/" is one namespace
    // to the person typing into it, so the two sets must not overlap. See that class for why the
    // check lives in the controllers rather than in either service.
    private final WorkflowService workflowService;

    public ChatSkillController(ChatSkillService chatSkillService, WorkflowService workflowService) {
        this.chatSkillService = chatSkillService;
        this.workflowService = workflowService;
    }

    private void rejectIfWorkflowNameTaken(String name, String requesterId) {
        if (name == null || name.isBlank()) return;
        if (workflowService.visibleNamesLowercase(requesterId).contains(name.trim().toLowerCase(Locale.ROOT))) {
            throw new NameCollisionException(WorkflowController.nameCollision(name.trim(), "workflow"));
        }
    }

    /** Carries the collision message to the 400 below, which is where the author will read it. */
    private static class NameCollisionException extends RuntimeException {
        NameCollisionException(String message) { super(message); }
    }

    private static <T> Mono<ResponseEntity<T>> collision(NameCollisionException e) {
        @SuppressWarnings("unchecked")
        ResponseEntity<T> body = (ResponseEntity<T>) ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        return Mono.just(body);
    }

    public record SkillRequest(String name, String shortDescription, String detail,
            List<String> tags, List<String> tools, List<String> connectorNames, Boolean shared) {}

    @GetMapping
    public Mono<ResponseEntity<List<ChatSkillService.SkillSummary>>> list(ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            return isSuperAdmin().flatMap(superAdmin ->
                    Mono.fromCallable(() -> ResponseEntity.ok(chatSkillService.list(uid, superAdmin)))
                            .subscribeOn(Schedulers.boundedElastic()));
        });
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<ChatSkillService.SkillDetail>> get(@PathVariable String id, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            return isSuperAdmin().flatMap(superAdmin ->
                    Mono.fromCallable(() -> ResponseEntity.ok(chatSkillService.get(id, uid, superAdmin)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(NoSuchElementException.class, e -> Mono.just(ResponseEntity.notFound().build())));
        });
    }

    // The seeded original for a built-in skill, so the Skills editor can offer "Restore default" -
    // the same affordance the system prompt has, and for the same reason: seeding is
    // create-if-absent, so nothing ever puts an edited built-in skill back on its own.
    //
    // Returns the text only; it does NOT write. The editor fills its fields with what comes back
    // and the user still presses Save, so restoring stays an explicit, cancellable act (and needs
    // no permission of its own beyond the PATCH that follows it). 404 when this skill has no
    // recoverable default - the UI hides the button in that case via `hasDefault`.
    @GetMapping("/{id}/default")
    public Mono<ResponseEntity<SkillDefaultResponse>> getDefault(@PathVariable String id, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            return Mono.fromCallable(() -> chatSkillService.defaultFor(id, uid)
                            .map(d -> ResponseEntity.ok(new SkillDefaultResponse(d.name(), d.shortDescription(), d.detail())))
                            .orElseGet(() -> ResponseEntity.notFound().build()))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    public record SkillDefaultResponse(String name, String shortDescription, String detail) {}

    @PostMapping
    public Mono<ResponseEntity<ChatSkillService.SkillDetail>> create(@RequestBody SkillRequest request, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            if (multiUser && uid == null) {
                return Mono.just(ResponseEntity.status(403).<ChatSkillService.SkillDetail>build());
            }
            List<String> tags = request.tags() != null ? request.tags() : List.of();
            List<String> tools = request.tools() != null ? request.tools() : List.of();
            List<String> connectorNames = request.connectorNames() != null ? request.connectorNames() : List.of();
            return Mono.fromCallable(() -> {
                        rejectIfWorkflowNameTaken(request.name(), uid);
                        return ResponseEntity.ok(
                                chatSkillService.create(request.name(), request.shortDescription(), request.detail(),
                                        uid, tags, tools, connectorNames));
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(NameCollisionException.class, ChatSkillController::collision);
        });
    }

    @PatchMapping("/{id}")
    public Mono<ResponseEntity<ChatSkillService.SkillDetail>> update(@PathVariable String id,
            @RequestBody SkillRequest request, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            List<String> tags = request.tags() != null ? request.tags() : List.of();
            List<String> tools = request.tools() != null ? request.tools() : List.of();
            List<String> connectorNames = request.connectorNames() != null ? request.connectorNames() : List.of();
            return isSuperAdmin().flatMap(superAdmin -> Mono.fromCallable(() -> {
                        rejectIfWorkflowNameTaken(request.name(), uid);
                        return ResponseEntity.ok(
                                chatSkillService.update(id, request.name(), request.shortDescription(), request.detail(),
                                        uid, superAdmin, tags, tools, connectorNames, request.shared()));
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(NoSuchElementException.class, e -> Mono.just(ResponseEntity.notFound().build()))
                    .onErrorResume(SkillAccessDeniedException.class, e -> Mono.just(ResponseEntity.status(403).build()))
                    .onErrorResume(NameCollisionException.class, ChatSkillController::collision));
        });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String id, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            return isSuperAdmin().flatMap(superAdmin -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
                        chatSkillService.delete(id, uid, superAdmin);
                        return ResponseEntity.noContent().build();
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(NoSuchElementException.class, e -> Mono.just(ResponseEntity.notFound().build()))
                    .onErrorResume(SkillAccessDeniedException.class, e -> Mono.just(ResponseEntity.status(403).<Void>build())));
        });
    }

    // Mirrors ChatController's own resolveUserId(ServerWebExchange) exactly: Principal-based,
    // empty/anonymous resolves to "" here (converted to null at each call site above), which is
    // also what every desktop-mode request resolves to (no SecurityWebFilterChain there at all -
    // see the multiUser field javadoc).
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
