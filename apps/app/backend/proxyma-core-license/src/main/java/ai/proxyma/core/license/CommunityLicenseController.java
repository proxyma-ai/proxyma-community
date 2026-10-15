package ai.proxyma.core.license;

import ai.proxyma.contracts.license.LicenseTier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * /status on Proxyma Desktop and the single-user server: COMMUNITY, always.
 *
 * <p>Since the two-tier model (2026-10-14) these editions are Community - free, every connector, no
 * limits - and carry no licensing code path at all: no license is read, activated or re-checked, no
 * trial is asked for, and the license server is never contacted. A license file left on disk by an
 * earlier build is neither read nor deleted. This answers the one question the page still asks.
 */
@RestController
@RequestMapping("/api/v1/license")
@ConditionalOnProperty(name = "proxyma.multi-user", havingValue = "false", matchIfMissing = true)
public class CommunityLicenseController {

    @GetMapping("/status")
    public Mono<ResponseEntity<LicenseStatusResponse>> status() {
        return Mono.just(ResponseEntity.ok(LicenseStatusResponse.of(LicenseTier.COMMUNITY, null, false)));
    }
}
