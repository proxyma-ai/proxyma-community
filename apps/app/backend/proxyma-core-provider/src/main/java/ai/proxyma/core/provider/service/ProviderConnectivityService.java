package ai.proxyma.core.provider.service;

import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.network.ProxyRoute;
import ai.proxyma.core.provider.adapter.LlmProviderAdapter;
import ai.proxyma.core.settings.event.ProviderSettingsChangedEvent;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.webcontent.WebProxyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asks every configured provider whether it actually answers - at startup, and again
 * whenever provider settings change.
 *
 * WHY THIS EXISTS. Nothing verified a provider was reachable until the user sent a message and it
 * failed. Building a role's model (LlmProviderFactory) proves nothing about the network - at most it
 * asks for the model's limits, and a failure there is remembered as "unknown" rather than reported -
 * so a dead Ollama still left every role reporting ready. The first honest signal a user got was a
 * failed chat turn.
 *
 * A SUCCESSFUL MODEL LIST IS THE CONNECTION TEST. listModels() is what the Settings page's own Test
 * Connection button uses, it is implemented by every adapter except Azure OpenAI, and for an API-key
 * provider a successful call also proves the key is accepted. Reusing it means the boot check and
 * the button cannot disagree about what "connected" means.
 *
 * THE RESULT IS HELD, NOT ONLY ANNOUNCED. It is read back over HTTP rather than pushed over the SSE
 * bus alone, because EventSinkService is a replay().latest() sink: it keeps exactly ONE message for
 * late subscribers, so a boot warning would be silently displaced by the next event to fire - and in
 * desktop the browser is opened BY ApplicationReadyEvent, so the UI is guaranteed to be a late
 * subscriber. Held state answers correctly whenever the UI gets around to asking.
 */
@Service
public class ProviderConnectivityService {

    private static final Logger log = LoggerFactory.getLogger(ProviderConnectivityService.class);

    /**
     * Deliberately short, and enforced by us rather than by the HTTP client.
     *
     * Adapters build their RestClients on the LLM factory, whose read timeout is 1800s
     * (RestClientConfig) - correct for a long completion, absurd for "is anything there". The
     * underlying request may keep running after this fires; we simply stop waiting on it. Nothing
     * downstream depends on the answer, so an abandoned probe costs a socket, not correctness.
     */
    private static final long PROBE_TIMEOUT_SECONDS = 10;

    private final List<LlmProviderAdapter> adapters;
    private final ProviderInstanceService providerInstances;

    /**
     * Written once by the startup probe, read by the controller on any request thread - volatile so
     * the UI does not read a torn or stale snapshot. Starts as PROBING rather than as an empty
     * result: "we have not looked yet" and "we looked and nothing answered" must not render the
     * same, or a fast-loading UI would accuse a perfectly healthy install of being offline.
     */
    private volatile Connectivity last = Connectivity.probing();

    /** Guards the re-probe so a burst of settings events cannot stack one thread pool each. */
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private final AtomicBoolean dirty = new AtomicBoolean();

    // Optional, for saying which way a failed request went (ProxyRoute). Absent, nothing is said
    // about the route - the classification itself does not depend on it.
    private ObjectProvider<WebProxyProvider> proxy;

    public ProviderConnectivityService(List<LlmProviderAdapter> adapters,
                                       ProviderInstanceService providerInstances) {
        this.adapters = adapters;
        this.providerInstances = providerInstances;
    }

    @Autowired(required = false)
    void setProxy(ObjectProvider<WebProxyProvider> proxy) {
        this.proxy = proxy;
    }

    public Connectivity current() {
        return last;
    }

    /**
     * @Async so the boot thread is never held by a network call - on the pool meant for work that
     * waits on a remote server, since a sweep can hold its thread for the whole probe timeout - and
     * separate from LlmProviderFactory's own ApplicationReadyEvent listener rather than chained onto
     * it: this one does not need the models to have been built, it asks the adapters directly.
     * Catches Throwable for the reason DesktopIntegrationService spells out - a boot listener that
     * throws takes startup with it.
     */
    @Async("blockingWorkExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void probeOnStartup() {
        sweep("startup");
    }

    /**
     * Re-probes whenever a provider's configuration changes.
     *
     * <p>WITHOUT THIS THE VERDICT WAS FROZEN FOR THE LIFE OF THE PROCESS. The startup sweep wrote
     * {@code last} once and nothing ever wrote it again, so an install that booted with nothing
     * reachable - no key yet, or an Ollama that had not been started - told the user "No AI
     * provider is responding" until the next restart, no matter how many providers they went on to
     * connect successfully. Reported from a real install on 2026-09-08 with two working providers
     * and the warning still on screen.
     *
     * <p>{@link ProviderSettingsChangedEvent} is the existing signal rather than a new one:
     * ProviderInstanceService already publishes it on every create, update and delete, and
     * LlmProviderFactory already rebuilds its models from it. A provider whose credentials just
     * changed is exactly a provider whose reachability we no longer know.
     *
     * <p>The state goes back to PROBING first, and that is what tells the UI. The client polls
     * while it reads PROBING and stops at DONE, so moving back through PROBING is the whole
     * handshake - no push channel, no new endpoint.
     */
    // After the commit, for the same reason as LlmProviderFactory's rebuild: a probe that ran first
    // tested the instance as it was before the save.
    @Async("blockingWorkExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void reprobeOnChange(ProviderSettingsChangedEvent event) {
        // Coalesced rather than one sweep per event. Saving a form can publish several changes in
        // a row, and each sweep holds up to eight sockets for up to ten seconds; a burst would
        // otherwise stack that many pools. Marking it dirty and letting the in-flight sweep run
        // again picks up the last change without running one probe per keystroke.
        dirty.set(true);
        // Looped, not tried once: a change marked dirty just as a sweep finished found the flag
        // still taken and left, the sweep then let it go, and that change was never probed - the
        // page showed the verdict from before it until the next event.
        while (dirty.get() && sweeping.compareAndSet(false, true)) {
            try {
                while (dirty.getAndSet(false)) {
                    last = Connectivity.probing();
                    sweep("settings changed");
                }
            } finally {
                sweeping.set(false);
            }
        }
    }

    private void sweep(String reason) {
        try {
            last = probeAll();
            if (last.shouldWarn()) {
                log.warn("[ProviderCheck] ({}) none of the {} configured provider(s) answered: {}",
                        reason, last.probed(), last.results());
            } else {
                log.info("[ProviderCheck] ({}) {}/{} configured provider(s) reachable",
                        reason, last.reachableCount(), last.probed());
            }
        } catch (Throwable t) {
            log.warn("[ProviderCheck] ({}) probe failed: {}", reason, t.toString());
            // An inconclusive probe must never accuse the install of being offline.
            last = Connectivity.inconclusive();
        }
    }

    /**
     * Probes every CONFIGURED provider instance concurrently - an instance exists because somebody
     * created it, so every one is probed and nothing else is.
     *
     * Concurrently because these are independent network calls and a serial sweep would make boot
     * feedback take as long as the slowest one plus all the others.
     */
    public Connectivity probeAll() {
        // A short-lived pool rather than the shared @Async executor: this fans out up to ten
        // simultaneous calls that may each sit for the full timeout, and starving the application's
        // general-purpose executor at boot would delay unrelated work.
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(8, adapters.size())));
        try {
            // Probes CONFIGURED INSTANCES, not adapters. Previously it swept every adapter and
            // filtered by "does this one have a key", which was the only way to ask "is this
            // provider in use" when providers were a fixed list. An instance exists because someone
            // created it, so the filter is gone: everything configured gets probed, and a
            // deployment using one provider no longer pays for probing nine it never set up.
            List<CompletableFuture<ProviderResult>> futures = providerInstances.resolveAll().stream()
                    .filter(p -> adapterFor(p) != null)
                    .map(p -> CompletableFuture.supplyAsync(() -> probeOne(p), pool))
                    .toList();
            List<ProviderResult> results = futures.stream()
                    .map(f -> {
                        try {
                            return f.get(PROBE_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            return new ProviderResult("unknown", false, false, "timeout",
                                    "Timed out while probing.");
                        }
                    })
                    .toList();
            return Connectivity.of(results);
        } finally {
            pool.shutdownNow();
        }
    }

    private LlmProviderAdapter adapterFor(ResolvedProvider provider) {
        return adapters.stream()
                .filter(a -> a.protocol() == provider.protocol())
                .findFirst()
                .orElse(null);
    }

    private ProviderResult probeOne(ResolvedProvider provider) {
        // The user's own name for it, so a failure names the thing they configured ("OpenRouter is
        // unreachable") rather than the protocol it happens to speak.
        String name = provider.name();
        LlmProviderAdapter adapter = adapterFor(provider);
        try {
            List<String> models = CompletableFuture
                    .supplyAsync(() -> adapter.listModels(provider), BlockingWork.executor())
                    .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // An EMPTY list is still a reachable provider - Ollama with no models pulled answers
            // /api/tags perfectly well. "Running but empty" is a different problem from "not there",
            // and conflating them would tell the user to check a URL that is already correct.
            return new ProviderResult(name, true, true, null,
                    models == null || models.isEmpty()
                            ? "Reachable, but it reports no models."
                            : "Reachable (" + models.size() + " model(s)).");
        } catch (Exception e) {
            Failure f = classify(e, hostOf(provider));
            // Azure OpenAI has no model-listing API, so it can be configured and correct while being
            // unprobeable. Marked not-probeable rather than unreachable: counting it as a failure
            // would let an install whose ONLY provider is Azure be told nothing connects, which we
            // have no evidence for.
            boolean probeable = !"not_supported".equals(f.code());
            return new ProviderResult(name, probeable, false, f.code(), f.message());
        }
    }

    /**
     * Turns whatever an adapter's listModels() threw into a stable machine-readable code plus a
     * short human-readable message - "wrong API key" has to be distinguishable from "wrong base URL"
     * from "service unreachable", both for the Settings page's Test Connection button and for the
     * startup warning's detail line.
     *
     * Moved here from ProviderModelsController, which now delegates to it: the same classification
     * feeding both the button and the boot check is what keeps the two from disagreeing about why a
     * provider is down. The controller keeps the code -> HTTP status mapping, which is presentation.
     *
     * <p>TAKES THE FAILURE AS THROWN and finds its cause itself. The button unwrapped one cause
     * before calling, which turned a ResourceAccessException into the bare ConnectException under it
     * and reported every network failure as unknown_error; the boot check unwrapped differently, and
     * the two disagreed - the thing this method exists to prevent.
     */
    public Failure classify(Throwable thrown) {
        return classify(thrown, null);
    }

    /**
     * {@link #classify(Throwable)}, for a request to {@code host}: a failure to connect also says
     * which way the request went - directly because the host is on No proxy, or through the proxy
     * (ProxyRoute). The codes do not change with it; only the sentence does.
     *
     * <p>A name that does not resolve is "unknown_host" however the client reports it. The JDK and
     * Netty clients report it as an UnresolvedAddressException under a ConnectException, which was
     * classified "connection_refused" - "is the service running at that address?" about a server
     * the request never found (0.36.0 feedback).
     */
    public Failure classify(Throwable thrown, String host) {
        Throwable e = unwrapped(thrown);
        if (e instanceof IllegalStateException) {
            // Adapters throw this for "required setting not configured" (missing API key/base URL).
            return new Failure("missing_configuration", e.getMessage());
        }
        if (e instanceof UnsupportedOperationException) {
            return new Failure("not_supported", e.getMessage());
        }
        if (e instanceof HttpClientErrorException.Unauthorized) {
            return new Failure("unauthorized", "Authentication failed - check the API key.");
        }
        if (e instanceof HttpClientErrorException.Forbidden) {
            return new Failure("forbidden", "Access denied - check the API key's permissions.");
        }
        if (e instanceof HttpClientErrorException.NotFound) {
            return new Failure("not_found", "Endpoint not found (HTTP 404) - check the base URL.");
        }
        if (e instanceof RestClientResponseException rcre) {
            return new Failure("http_" + rcre.getStatusCode().value(),
                    "Provider returned HTTP " + rcre.getStatusCode().value() + " " + rcre.getStatusText());
        }
        if (e instanceof WebClientResponseException wre) {
            return new Failure("http_" + wre.getStatusCode().value(),
                    "Provider returned HTTP " + wre.getStatusCode().value() + " " + wre.getStatusText());
        }
        // The network, wherever in the chain it surfaced. The JDK client's own timeouts are
        // HttpTimeoutException (and HttpConnectTimeoutException under it), not SocketTimeoutException.
        ProxyRoute route = routeOf(host);
        if (inChain(e, java.util.concurrent.TimeoutException.class)
                || inChain(e, SocketTimeoutException.class)
                || inChain(e, java.net.http.HttpTimeoutException.class)) {
            return new Failure("timeout", route == ProxyRoute.NONE
                    ? "Connection timed out."
                    : "Connection timed out - " + route.note() + ".");
        }
        if (inChain(e, UnknownHostException.class) || inChain(e, UnresolvedAddressException.class)) {
            return new Failure("unknown_host", switch (route) {
                case NONE -> "Could not resolve host - check the base URL.";
                // Through a proxy the proxy resolves the server's name; the one this machine could
                // not resolve was the proxy's own.
                case PROXY -> "Could not resolve the proxy's address - check it in Settings > Network.";
                case BYPASSED -> "Could not resolve host - " + route.note() + ".";
            });
        }
        if (inChain(e, ConnectException.class)) {
            return new Failure("connection_refused", switch (route) {
                case NONE -> "Connection refused - is the service running at that address?";
                // Through a proxy the connection that failed is the one to the proxy.
                case PROXY -> "Could not connect to the proxy - check that it is running, "
                        + "or change it in Settings > Network.";
                case BYPASSED -> "Connection refused - " + route.note() + ".";
            });
        }
        if (e instanceof ResourceAccessException || e instanceof java.io.IOException) {
            return new Failure("connection_failed", e.getMessage());
        }
        return new Failure("unknown_error",
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    private ProxyRoute routeOf(String host) {
        return ProxyRoute.of(proxy != null ? proxy.getIfAvailable() : null, host);
    }

    /** The host a provider's base URL names, or null when it names none. */
    public static String hostOf(ResolvedProvider provider) {
        if (provider == null || provider.baseUrl() == null || provider.baseUrl().isBlank()) return null;
        try {
            return URI.create(provider.baseUrl().trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // A future's or a proxy's wrapper says nothing; what it wraps does.
    private static Throwable unwrapped(Throwable e) {
        Throwable t = e;
        while ((t instanceof java.util.concurrent.ExecutionException
                || t instanceof java.util.concurrent.CompletionException
                || t instanceof java.lang.reflect.UndeclaredThrowableException)
                && t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static boolean inChain(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) return true;
        }
        return false;
    }

    public record Failure(String code, String message) {}

    /** One provider's verdict. `probeable` is false only for a provider with no listing API at all. */
    public record ProviderResult(String provider, boolean probeable, boolean reachable,
                                 String code, String message) {}

    /**
     * The whole startup verdict.
     *
     * `state` exists so "not finished yet" is never mistaken for "everything is down". The UI polls
     * while it reads PROBING and only reacts once it reads DONE.
     */
    public record Connectivity(String state, List<ProviderResult> results) {

        public static Connectivity probing() { return new Connectivity("PROBING", List.of()); }

        /** Used when the sweep itself failed - we know nothing, so we claim nothing. */
        public static Connectivity inconclusive() { return new Connectivity("DONE", List.of()); }

        public static Connectivity of(List<ProviderResult> results) {
            return new Connectivity("DONE", results);
        }

        /** How many providers we could actually form an opinion about. */
        public long probed() { return results.stream().filter(ProviderResult::probeable).count(); }

        public long reachableCount() { return results.stream().filter(ProviderResult::reachable).count(); }

        /**
         * Warn only when we probed something and NOTHING answered. An install with no provider
         * configured at all, or one whose only provider cannot be probed, gets no warning - there is
         * nothing to report and a false alarm on first run would be the worst possible first
         * impression.
         */
        public boolean shouldWarn() {
            return "DONE".equals(state) && probed() > 0 && reachableCount() == 0;
        }

        public Map<String, Object> toPayload() {
            return Map.of(
                    "state", state,
                    "probed", probed(),
                    "reachable", reachableCount(),
                    "warn", shouldWarn(),
                    "providers", results.stream()
                            .map(r -> Map.of(
                                    "provider", r.provider(),
                                    "probeable", r.probeable(),
                                    "reachable", r.reachable(),
                                    "code", r.code() == null ? "" : r.code(),
                                    "message", r.message() == null ? "" : r.message()))
                            .toList());
        }
    }
}
