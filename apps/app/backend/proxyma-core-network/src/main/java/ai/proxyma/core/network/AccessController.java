package ai.proxyma.core.network;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The access code's API on a single-user install - see {@link AccessGate}.
 *
 * <ul>
 *   <li>{@code POST /api/v1/access} {@code {"code": "..."}} - the "Enter access code" screen. Sets
 *       the cookie and answers 204, or 401 {@code access_denied}. The one call here that needs no
 *       credential.</li>
 *   <li>{@code GET /api/v1/access} - 204. The page asks it first: AccessGate answers 401
 *       {@code access_required} before this runs unless the caller is already in.</li>
 *   <li>{@code GET /api/v1/access/token} - the install's {@code pxm_} token, for the MCP client
 *       snippets in Settings > AI Assistants. Only somebody already in can read it.</li>
 * </ul>
 */
@RestController
@RequestMapping(AccessGate.ACCESS_PATH)
@Profile("!server")
public class AccessController {

    private final AccessGate gate;

    public AccessController(AccessGate gate) {
        this.gate = gate;
    }

    @GetMapping
    public ResponseEntity<Void> status() {
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    public Mono<ResponseEntity<Object>> enter(@RequestBody(required = false) Map<String, String> body,
                                              ServerWebExchange exchange) {
        String presented = body == null ? null : body.get("code");
        if (gate.redeem(presented)) {
            exchange.getResponse().addCookie(gate.sessionCookie(exchange.getRequest()));
            return Mono.just(ResponseEntity.noContent().build());
        }
        return Mono.delay(AccessGate.REFUSAL_DELAY)
                .thenReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "access_denied")));
    }

    @GetMapping("/token")
    public Map<String, String> token() {
        return Map.of("token", gate.bearerToken());
    }
}
