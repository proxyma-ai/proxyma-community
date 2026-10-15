package ai.proxyma.core.telemetryclient;

import ai.proxyma.contracts.feedback.FeedbackAttachmentKind;
import ai.proxyma.contracts.feedback.FeedbackSubmission;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

/**
 * Sends one piece of user feedback to proxyma-telemetry.
 *
 * Separate from {@link TelemetryShipper} rather than another event type on it, and the differences
 * are not cosmetic:
 *
 * <ul>
 *   <li><b>Never batched, never buffered.</b> Telemetry events are counters that can wait a minute
 *       and be dropped on shutdown without anyone caring. A person just pressed Send and is
 *       watching for the result - it goes now, and the caller learns whether it arrived.</li>
 *   <li><b>Not gated on the telemetry toggles.</b> The daily diagnostics report is sent only when
 *       data collection is on and the tier allows external telemetry, which is right for passive
 *       measurement nobody asked for. Feedback is the opposite: an explicit, deliberate act by
 *       someone who typed something and pressed a button, on a form that says where it goes. A
 *       user who has turned analytics off has not thereby asked to be unable to report a bug.</li>
 *   <li><b>Carries content.</b> Only what the user consented to - see FeedbackSubmission - but that
 *       makes an accidental send worse than a missed one, which is why nothing here retries into a
 *       queue that outlives the click.</li>
 * </ul>
 *
 * Fails loudly to the caller rather than silently: FeedbackController mirrors the submission
 * locally regardless, so a failure here means "we could not deliver it to Proxyma", which is
 * something the user should be told rather than something to swallow.
 */
@Component
public class FeedbackShipper {

    private static final Logger log = LoggerFactory.getLogger(FeedbackShipper.class);

    @Value("${proxyma.telemetry.server.url:}")
    private String telemetryServerUrl;

    @Value("${spring.application.version:0.0.0}")
    private String appVersion;

    // "desktop" or "server". Which edition a report came from changes what it even means - a
    // desktop bug and a server bug with the same description are rarely the same bug.
    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    private final RestClient restClient;
    private final FeatureGateService featureGateService;
    private final SettingsService settingsService;
    private final InstanceIdProvider instanceIdProvider;
    private final ObjectMapper objectMapper;

    public FeedbackShipper(@Qualifier("externalRestClientBuilder") RestClient.Builder builder,
                          FeatureGateService featureGateService,
                          SettingsService settingsService,
                          InstanceIdProvider instanceIdProvider,
                          ObjectMapper objectMapper) {
        this.restClient = builder.build();
        this.featureGateService = featureGateService;
        this.settingsService = settingsService;
        this.instanceIdProvider = instanceIdProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * Whether a thumbs-up/down may leave this deployment: a telemetry endpoint is configured, the
     * tier allows external telemetry, and "Send diagnostics to Proxyma" is on.
     *
     * <p>A rating carries the rated reply and the question it answers, and until 0.10.0 it shipped
     * whatever the telemetry setting said and on every tier, Enterprise included - while the Privacy
     * Policy and the DPA said a conversation never reached us unless someone ticked a box. A rating
     * is a click, not a decision to send a transcript, so it follows the setting a person chose for
     * sending things: off unless "Send diagnostics to Proxyma" (Settings > About) is on, and never under a
     * tier that forbids external telemetry. The feedback form is not gated here - writing it and
     * choosing what to include IS that decision.
     */
    public boolean ratingsMayLeave() {
        // Never from Enterprise Server, licensed or not (2026-10-14) - the same edition rule as
        // diagnostics (TelemetryConsent). An unlicensed or lapsed server is COMMUNITY by tier, and the
        // tier alone let one administrator's switch decide for everybody on it.
        return isConfigured()
                && !multiUser
                && featureGateService.getFeatures().isExternalTelemetryAllowed()
                && settingsService.isDataCollectionAllowed();
    }

    /** True when a telemetry endpoint is configured at all - false in a deployment that has none. */
    public boolean isConfigured() {
        return telemetryServerUrl != null && !telemetryServerUrl.isBlank();
    }

    public record Attachment(String fileName, String contentType, byte[] bytes, FeedbackAttachmentKind kind) {}

    /**
     * Ships the submission synchronously.
     *
     * @return true if proxyma-telemetry accepted it.
     */
    public boolean ship(FeedbackSubmission submission, List<Attachment> attachments) {
        if (!isConfigured()) {
            log.debug("[Feedback] No telemetry server configured - not shipping");
            return false;
        }

        // Stamped here rather than trusted from the client: version, tier, edition and instance id
        // are facts about this installation, and the browser has no business asserting them.
        submission.setAppVersion(appVersion);
        submission.setTier(featureGateService.getCurrentTier().name());
        submission.setEdition(multiUser ? "server" : "desktop");
        submission.setInstanceId(instanceIdProvider.getInstanceId());
        if (submission.getSubmittedAt() == null) submission.setSubmittedAt(Instant.now());

        try {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            // An explicit application/json part - a bare String part defaults to text/plain, which
            // the receiving @RequestPart would not bind.
            parts.add("submission", new org.springframework.http.HttpEntity<>(
                    objectMapper.writeValueAsString(submission),
                    jsonHeaders()));

            if (attachments != null) {
                for (Attachment a : attachments) {
                    parts.add("files", new NamedByteArrayResource(a.bytes(), a.fileName()));
                    // Positional, matching the receiving side. Sent as its own part rather than
                    // inferred from the filename, which proves nothing about what a file is.
                    parts.add("kinds", a.kind().name());
                }
            }

            restClient.post()
                    .uri(telemetryServerUrl + "/api/v1/feedback")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            // The endpoint ANSWERED and refused. Status and a short slice of its body, because the
            // previous form logged the whole HTML error page as one unreadable line and still did
            // not say what we had sent - and a 415 against an endpoint that does consume multipart
            // is only diagnosable if you can see both halves. Still never the submission itself: it
            // holds the user's own words, and possibly a transcript and an email address.
            log.warn("[Feedback] {} rejected feedback: HTTP {} (sent {} with {} attachment(s)). Response: {}",
                    telemetryServerUrl, e.getStatusCode().value(), MediaType.MULTIPART_FORM_DATA_VALUE,
                    attachments == null ? 0 : attachments.size(), truncate(e.getResponseBodyAsString()));
            return false;
        } catch (Exception e) {
            // Never the submission, for the same reason - this one is a transport failure, and the
            // user's prose has no place in a log line about a socket.
            log.warn("[Feedback] Could not ship feedback to {}: {}", telemetryServerUrl, e.getMessage());
            return false;
        }
    }

    // Enough of a refused response to name the failure, and no more - an error page is mostly
    // boilerplate, and one of these arrived carrying an entire HTML document plus an analytics
    // beacon on a single log line.
    private static String truncate(String body) {
        if (body == null || body.isBlank()) return "<empty>";
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }

    private static org.springframework.http.HttpHeaders jsonHeaders() {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ByteArrayResource reports no filename, and a multipart file part without one is not treated
    // as a file upload by the receiving side.
    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String fileName;
        NamedByteArrayResource(byte[] bytes, String fileName) {
            super(bytes);
            this.fileName = fileName;
        }
        @Override
        public String getFilename() { return fileName; }
    }
}
