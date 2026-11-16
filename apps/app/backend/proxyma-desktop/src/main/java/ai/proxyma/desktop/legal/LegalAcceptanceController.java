package ai.proxyma.desktop.legal;

import ai.proxyma.core.system.legal.LegalDocuments;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The start's legal gate on Desktop and Home Server (LegalAcceptances): whether the documents in force were accepted,
 * and the acceptance. Declining is the existing quit (ShutdownController). Behind the install's access code like every
 * call here (AccessGate); proxyma-server does not build this class.
 */
@RestController
@RequestMapping("/api/v1/desktop/legal")
public class LegalAcceptanceController {

    private final LegalAcceptances acceptances;

    public LegalAcceptanceController(LegalAcceptances acceptances) {
        this.acceptances = acceptances;
    }

    public record AcceptRequest(String version) {}

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> status() {
        return Mono.fromCallable(() -> ResponseEntity.ok(body(acceptances.status())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/accept")
    public Mono<ResponseEntity<Map<String, Object>>> accept(@RequestBody(required = false) AcceptRequest in) {
        return Mono.fromCallable(() -> {
            try {
                return ResponseEntity.ok(body(acceptances.accept(in == null ? null : in.version())));
            } catch (LegalAcceptances.StaleVersionException e) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.<String, Object>of("message", e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    static Map<String, Object> body(LegalAcceptances.Status s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", s.version());
        out.put("accepted", s.accepted());
        out.put("acceptedVersion", s.last() == null ? null : s.last().version());
        out.put("acceptedAt", s.last() == null ? null : s.last().at().toString());
        out.put("termsUrl", LegalDocuments.TERMS_URL);
        out.put("privacyUrl", LegalDocuments.PRIVACY_URL);
        out.put("eulaUrl", LegalDocuments.EULA_URL);
        return out;
    }
}
