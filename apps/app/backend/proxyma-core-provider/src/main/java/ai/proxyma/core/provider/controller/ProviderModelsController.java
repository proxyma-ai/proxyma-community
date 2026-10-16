package ai.proxyma.core.provider.controller;

import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.provider.adapter.LlmProviderAdapter;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.provider.dto.ModelListResponse;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.provider.service.ProviderConnectivityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// Backs the Settings page's model comboboxes (Chat/Query Expansion/Reranking/Embedding model
// fields): fetches the live model list from whichever provider is currently selected, on demand
// (called only when a combobox dropdown is opened, never eagerly).
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderModelsController {

    private static final Logger log = LoggerFactory.getLogger(ProviderModelsController.class);

    private final LlmProviderFactory factory;
    private final ProviderConnectivityService connectivity;

    public ProviderModelsController(LlmProviderFactory factory, ProviderConnectivityService connectivity) {
        this.factory = factory;
        this.connectivity = connectivity;
    }

    /**
     * The startup connectivity sweep's verdict - see ProviderConnectivityService.
     *
     * Read on demand rather than pushed: the SSE bus keeps only its latest message, and in desktop
     * the browser is opened by the same ApplicationReadyEvent that starts the probe, so the UI is
     * always a late subscriber. Returns state=PROBING until the sweep finishes; the caller polls.
     */
    @GetMapping("/connectivity")
    public Mono<ResponseEntity<Object>> connectivity() {
        return Mono.just(ResponseEntity.ok((Object) connectivity.current().toPayload()));
    }

    // A dropdown waits this long for a provider's answer. The request itself carries the LLM read
    // bound, which is for a model generating; a list of models is not worth holding a thread - or a
    // person - for that long.
    private static final Duration REMOTE_WAIT = Duration.ofSeconds(15);

    // Package-private so a test need not wait the whole of it.
    Duration remoteWait = REMOTE_WAIT;

    // listModels() does blocking HTTP internally, so it runs on BlockingWork - the pool for work that
    // waits on a remote server - and never on a WebFlux event loop or on boundedElastic, which every
    // blocking database read shares.
    // `provider` is a configured instance's ID now, not a protocol name - the model list depends on
    // the base URL and credential, which belong to the instance. Two instances of one protocol
    // legitimately return different lists.
    //
    // A FAILURE IS AN ANSWER, NOT AN ERROR SIGNAL. The call's own failure is turned into the response
    // inside the callable, so nothing it throws can arrive after the dropdown stopped waiting: the
    // timeout cancels the call, cancelling interrupts the thread (which ends the request rather than
    // leaving it to its read bound), and the interrupted request then failed into a subscriber that
    // had gone - Reactor's onErrorDropped, an ERROR and a stack trace in the log for every provider
    // slower than the timeout (0.36.0 feedback, four of them at once). The failure that comes too late
    // is logged at DEBUG and dropped; the timeout already answered.
    @GetMapping("/{provider}/models")
    public Mono<ResponseEntity<Object>> listModels(@PathVariable String provider) {
        log.info("[ProviderModelsController] Listing models for providerId={}", provider);
        AtomicBoolean abandoned = new AtomicBoolean();
        // The base URL's host, once resolved, so a timeout can say which way the request went too.
        AtomicReference<String> host = new AtomicReference<>();
        return Mono.fromCallable(() -> fetch(provider, host, abandoned))
                .subscribeOn(Schedulers.fromExecutor(BlockingWork.executor()))
                // Before the cancel reaches subscribeOn, which is what interrupts the call.
                .doOnCancel(() -> abandoned.set(true))
                .timeout(remoteWait)
                .onErrorResume(e -> Mono.just(failed(provider, host.get(), e)));
    }

    private ResponseEntity<Object> fetch(String provider, AtomicReference<String> host, AtomicBoolean abandoned) {
        try {
            ResolvedProvider resolved = factory.resolveProvider(provider)
                    .orElseThrow(() -> new UnknownProvider("No configured provider with id " + provider));
            host.set(ProviderConnectivityService.hostOf(resolved));
            LlmProviderAdapter adapter = factory.getAdapter(provider);
            if (adapter == null) {
                throw new UnknownProvider("No adapter for protocol " + resolved.protocol());
            }
            List<String> models = adapter.listModels(resolved);
            log.info("[ProviderModelsController] '{}' returned {} model(s)", resolved.name(), models.size());
            return ResponseEntity.ok((Object) new ModelListResponse(models));
        } catch (RuntimeException e) {
            if (abandoned.get()) {
                log.debug("[ProviderModelsController] provider={} failed after the timeout had answered: {}",
                        provider, e.toString());
                // Nobody is subscribed any more; a null completes the callable empty.
                return null;
            }
            return failed(provider, host.get(), e);
        }
    }

    private ResponseEntity<Object> failed(String provider, String host, Throwable e) {
        if (e instanceof java.util.concurrent.TimeoutException) {
            // Our own wait running out: its stack trace is Reactor's timer, which says nothing.
            log.warn("[ProviderModelsController] Failed to list models for provider={}: no answer within {}s",
                    provider, remoteWait.toSeconds());
        } else {
            // Full stack trace (not just getMessage()) so a refused-vs-401-vs-parse-error is still
            // distinguishable from the server log alone even though the client-facing body below is
            // a short classified summary, not the raw exception.
            log.warn("[ProviderModelsController] Failed to list models for provider={}", provider, e);
        }
        // The failure as thrown - classify finds its cause; unwrapping one here turned every network
        // failure into unknown_error.
        var err = e instanceof UnknownProvider
                ? new ProviderConnectivityService.Failure("unknown_provider", e.getMessage())
                : connectivity.classify(e, host);
        return ResponseEntity.status(statusFor(err.code()))
                .body((Object) Map.of("error", err.code(),
                        "message", err.message() == null ? "" : err.message()));
    }

    // This controller's own lookup failing - the one case that is "unknown_provider". An adapter's
    // IllegalArgumentException (a malformed base URL) is classified like any other failure.
    private static final class UnknownProvider extends IllegalArgumentException {
        UnknownProvider(String message) {
            super(message);
        }
    }

    /**
     * What the provider says a model can take in and give back - what Settings > Agents names
     * beside "Model default". `model` is a query parameter because a model's name may hold a
     * slash or a colon. Never an error: a model nobody could be asked about is two nulls, and the
     * page then says "Model default" with no number.
     */
    @GetMapping("/{provider}/model-limits")
    public Mono<ResponseEntity<Object>> modelLimits(@PathVariable String provider,
                                                    @org.springframework.web.bind.annotation.RequestParam String model) {
        return Mono.fromCallable(() -> {
                    // Bounded by the factory itself, and never an error.
                    var limits = factory.modelLimits(provider, model);
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("contextWindow", limits.contextWindow());
                    body.put("maxOutputTokens", limits.maxOutputTokens());
                    return ResponseEntity.ok((Object) body);
                })
                .subscribeOn(Schedulers.fromExecutor(BlockingWork.executor()));
    }

    /**
     * Which HTTP status a classified failure deserves. The classification itself lives in
     * ProviderConnectivityService, which the startup sweep shares - only this mapping is
     * presentation, and only this endpoint needs it.
     *
     * The three 400s are all "you have not finished configuring this", which is the caller's
     * problem to fix; a timeout is a gateway timeout; everything else is the upstream provider
     * misbehaving, which is a 502.
     */
    static HttpStatus statusFor(String code) {
        return switch (code) {
            case "unknown_provider", "missing_configuration", "not_supported" -> HttpStatus.BAD_REQUEST;
            case "timeout" -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }
}
