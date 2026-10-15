package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.service.SubAgentService;
import ai.proxyma.core.chat.service.SystemPromptService;
import ai.proxyma.core.settings.config.ProductIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

// The Settings > Chat "system prompt" editor. There is one prompt (no smart/light variant): the
// built-in default or an admin's saved custom prompt, managed like a skill - the default lives in
// code (SystemPromptService.DEFAULT), a custom one is a markdown file on disk (not a settings
// field), read/written through these dedicated endpoints. SUPER_ADMIN only on the server edition;
// Desktop proper may READ it (see mayRead), and nothing but the super admin writes. Lives here rather
// than in proxyma-core-settings because the prompt content is owned by SystemPromptService
// (proxyma-core-chat), which proxyma-core-settings must never depend on.
@RestController
@RequestMapping("/api/v1/settings/chat")
public class ChatSystemPromptController {

    private final SystemPromptService systemPromptService;
    private final SubAgentService subAgentService;
    private final ProductIdentity productIdentity;

    // Desktop mode has no SecurityWebFilterChain at all, so there is no role to check there - and
    // as of 0.1.2 that is a refusal rather than a bypass. See allowed() below.
    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    // The single-user deployment that is reachable from OTHER machines - a Home Server on the LAN -
    // so reading the prompt stays refused there.
    @Value("${proxyma.home-server:false}")
    private boolean homeServer;

    public ChatSystemPromptController(SystemPromptService systemPromptService, SubAgentService subAgentService,
                                      ProductIdentity productIdentity) {
        this.systemPromptService = systemPromptService;
        this.subAgentService = subAgentService;
        this.productIdentity = productIdentity;
    }

    // The effective prompt shown in the editor: the admin's saved custom prompt if any, otherwise
    // the built-in default (never blank - see SystemPromptService.read()).
    @GetMapping("/system-prompt")
    public Mono<ResponseEntity<String>> getPrompt() {
        return mayRead().flatMap(allowed -> allowed
                ? Mono.fromCallable(systemPromptService::read).subscribeOn(Schedulers.boundedElastic())
                        .map(ResponseEntity::ok)
                : Mono.just(ResponseEntity.status(403).build()));
    }

    // What a conversation is actually started with, for a reader who cannot edit it: the prompt
    // above under this deployment's name, with the section on delegating and its closing
    // reminder. The raw prompt showed neither, so an install whose agent had been told to delegate
    // displayed a prompt that never mentioned it.
    //
    // Never what the EDITOR loads - saving it back would write the added section into the file.
    @GetMapping("/system-prompt/assembled")
    public Mono<ResponseEntity<String>> getAssembled() {
        return mayRead().flatMap(allowed -> allowed
                ? Mono.fromCallable(this::assembled).subscribeOn(Schedulers.boundedElastic())
                        .map(ResponseEntity::ok)
                : Mono.just(ResponseEntity.status(403).build()));
    }

    private String assembled() {
        boolean canDelegate = subAgentService.isAvailable();
        String instructions = SystemPromptService.instructions(systemPromptService.read(),
                productIdentity.getProductName(), canDelegate);
        return canDelegate ? instructions.stripTrailing() + "\n\n" + SystemPromptService.DELEGATION_REMINDER : instructions;
    }

    // The built-in default itself, for the editor's "Reset to default" button to load into the
    // textarea without saving.
    @GetMapping("/system-prompt/default")
    public Mono<ResponseEntity<String>> getDefault() {
        return isSuperAdmin().map(allowed -> allowed
                ? ResponseEntity.ok(SystemPromptService.DEFAULT)
                : ResponseEntity.status(403).build());
    }

    // Saved verbatim, including blank - an admin clearing the textarea and saving reverts to the
    // built-in default (SystemPromptService.read() treats a blank saved file as "use default").
    @PutMapping("/system-prompt")
    public Mono<ResponseEntity<Void>> savePrompt(@RequestBody String content) {
        return isSuperAdmin().flatMap(allowed -> allowed
                ? Mono.fromRunnable(() -> systemPromptService.write(content))
                        .subscribeOn(Schedulers.boundedElastic())
                        .then(Mono.just(ResponseEntity.ok().<Void>build()))
                : Mono.just(ResponseEntity.status(403).<Void>build()));
    }

    /**
     * SUPER_ADMIN in the server edition; nobody at all on desktop.
     *
     * <p>The desktop branch used to return true - there are no roles there, so "is this user a
     * super admin" had no answer and the permissive one was taken. That made the agent's system
     * prompt readable and editable by every desktop user, and the prompt is precisely the text
     * telling the agent not to reveal its instructions or name the model vendor behind it. A
     * guardrail published beside an editor is not a guardrail.
     *
     * <p>Refused on the SERVER, not merely hidden in the SPA: the endpoints are reachable with a
     * plain HTTP client whatever the UI renders, so hiding the button alone would have changed
     * nothing about who can read the prompt.
     */
    /**
     * Reading, as against editing: the super admin on Enterprise Server, and ALSO Desktop proper,
     * since 2026-10-01 (maintainer's request: show it read-only, like a stock skill). Desktop is one
     * person on their own machine, who is the operator the guardrails serve rather than a user
     * they constrain. Home Server stays refused - it is Desktop's bundle served to whoever can
     * reach the address - and nobody but the super admin may WRITE, anywhere.
     */
    private Mono<Boolean> mayRead() {
        if (!multiUser) return Mono.just(!homeServer);
        return isSuperAdmin();
    }

    private Mono<Boolean> isSuperAdmin() {
        if (!multiUser) return Mono.just(false);
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch("ROLE_SUPER_ADMIN"::equals))
                .defaultIfEmpty(false);
    }
}
