package ai.proxyma.core.system.controller;

import ai.proxyma.core.system.util.ProcessExit;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final ConfigurableApplicationContext context;

    public SystemController(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @PostMapping("/restart")
    public Mono<ResponseEntity<Map<String, String>>> restart() {
        ProcessExit.restartShortly(context, "Restart requested via API");
        return Mono.just(ResponseEntity.ok(Map.of("status", "restarting")));
    }
}
