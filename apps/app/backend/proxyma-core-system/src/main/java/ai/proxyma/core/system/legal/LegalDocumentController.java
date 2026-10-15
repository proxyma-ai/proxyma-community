package ai.proxyma.core.system.legal;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * What the product needs to know about the legal documents - which is now the version in force and
 * where to read them, not the documents themselves.
 *
 * The Terms of Service and Privacy Policy are PUBLISHED on the marketing site and are served from
 * there alone. They used to exist in three places at once: as pages on proxyma.ai, as markdown in
 * this jar rendered at in-app /terms and /privacy routes, and - for the EULA - as a plain link out
 * to the site. Two full copies of a legal text in two formats in two repositories have to be
 * changed together by hand, with nothing anywhere that notices when only one of them is. The EULA
 * already worked the way all three do now, so this applies an existing pattern to its siblings
 * rather than inventing one.
 *
 * The Data Processing Agreement is the exception and stays here: it has no page on the marketing
 * site, so removing it would not consolidate it anywhere - it would delete it.
 */
@RestController
@RequestMapping("/api/v1/legal")
public class LegalDocumentController {

    private static final String LAST_UPDATED = LegalDocuments.CURRENT_VERSION;

    // Where each document is published. Absolute now that they are not this app's own routes, and
    // hardcoded rather than configurable because they are the addresses named inside the documents
    // themselves and recorded against every consent - not a per-deployment detail.
    private static final String TERMS_URL = "https://proxyma.ai/terms";
    private static final String PRIVACY_URL = "https://proxyma.ai/privacy";
    private static final String EULA_URL = "https://proxyma.ai/eula";

    private final String dataProcessingAgreement = read("legal/data-processing-agreement.md");

    // Only meaningful for a deployment we host, where we are a processor acting on the customer's
    // behalf. Served unconditionally anyway: a self-hosting customer whose procurement asks for a
    // DPA needs to be able to read section 0, which explains why they do not need one.
    @GetMapping("/data-processing-agreement")
    public Mono<ResponseEntity<Map<String, Object>>> getDataProcessingAgreement() {
        return Mono.just(ResponseEntity.ok(Map.<String, Object>of(
                "content", dataProcessingAgreement,
                "lastUpdated", LAST_UPDATED,
                "version", LegalDocuments.CURRENT_VERSION)));
    }

    // What a signup form needs in order to render the consent checkbox and echo the right version
    // back: the version in force and where each document can be read. Public - it has to be
    // reachable by someone who does not yet have an account.
    //
    // This endpoint is why the controller survives the documents moving out. The version it reports
    // is written into every LegalConsent row at signup, so it is the record of WHICH text a given
    // user accepted; removing it would take the consent audit trail with it.
    @GetMapping("/consent-requirements")
    public Mono<ResponseEntity<Map<String, Object>>> getConsentRequirements() {
        return Mono.just(ResponseEntity.ok(Map.<String, Object>of(
                "version", LegalDocuments.CURRENT_VERSION,
                "termsUrl", TERMS_URL,
                "privacyUrl", PRIVACY_URL,
                "eulaUrl", EULA_URL)));
    }

    private static String read(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load legal document connector: " + path, e);
        }
    }
}
