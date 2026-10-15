package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.service.SpaceFactService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

// The astronomy fact under the chat hero heading.
//
// Mounted under /api/v1/chat/ deliberately: that whole prefix is permitAll in SecurityConfig, and
// the hero is the one screen an anonymous visitor sees in server mode, so an endpoint anywhere
// else would 401 for exactly the readers it exists for. Nothing here is user-specific or
// sensitive - one deployment-wide sentence and a public link.
//
// No boundedElastic hop, unlike every other read in this package: current() is a volatile field
// read and a flag write, with no repository, no file and no network behind it. The generation that
// does all three happens in SpaceFactRefreshJob, off any request thread.
@RestController
@RequestMapping("/api/v1/chat/space-fact")
public class SpaceFactController {

    private final SpaceFactService spaceFactService;

    public SpaceFactController(SpaceFactService spaceFactService) {
        this.spaceFactService = spaceFactService;
    }

    // 204, not an empty 200, when nothing has ever been generated here (no chat model, no network,
    // every candidate source rejected). There is no seed fact to serve instead - see
    // SpaceFactService's header - and the page shows its Proxima Centauri default instead. An explicit
    // "no content" says that; a 200 with an empty body would reach the client as an empty string
    // and have to be guessed at.
    @GetMapping
    public Mono<ResponseEntity<SpaceFactService.SpaceFact>> current() {
        return Mono.fromSupplier(() -> {
            SpaceFactService.SpaceFact fact = spaceFactService.current();
            return fact != null ? ResponseEntity.ok(fact) : ResponseEntity.noContent().build();
        });
    }
}
