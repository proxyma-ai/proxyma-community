package ai.proxyma.core.settings.usage;

import ai.proxyma.core.settings.access.CallerRoles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * What the desktop cost view reads - this calendar month, lifetime, and the month by model.
 *
 * Separate from UsagesController next door rather than another field on its payload. That one
 * answers a deployment question ("what is being spent here, on whom, against which quota") and its
 * shape is built around a rolling day window and a user table; this one answers a personal question
 * on a machine that has neither. Folding them together would mean desktop parsing a payload that is
 * mostly nulls, and every future change to the Usages page reaching a screen that has nothing to do
 * with it.
 *
 * On Enterprise Server it is an ADMINISTRATOR's read (authz-01). It exposes no identity and no
 * content, but its figures are the whole deployment's - this month's and all-time spend, by
 * provider and model - and it sat on the blanket authenticated rule, readable by every VIEWER,
 * beside the Usages page SEC-44 locked to administrators. The server's page does not render it;
 * an administrator may still read it. Desktop and Home Server have one user, whose spend it is.
 */
@RestController
@RequestMapping("/api/v1/usage/chat-cost")
public class ChatCostController {

    private final ChatSpendService chatSpendService;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public ChatCostController(ChatSpendService chatSpendService) {
        this.chatSpendService = chatSpendService;
    }

    // boundedElastic: summary() is four blocking JPA reads, and running those on the event loop
    // would stall every other request on this Netty worker - see the module rule in CLAUDE.md.
    @GetMapping
    public Mono<ResponseEntity<ChatCostSummary>> summary() {
        return CallerRoles.isAdmin(multiUser).flatMap(allowed -> allowed
                ? Mono.fromCallable(() -> ResponseEntity.ok(chatSpendService.summary()))
                        .subscribeOn(Schedulers.boundedElastic())
                : Mono.just(ResponseEntity.status(403).<ChatCostSummary>build()));
    }
}
