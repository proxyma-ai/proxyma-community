package ai.proxyma.core.network;

import ai.proxyma.core.common.net.OutboundAddressPolicy;
import ai.proxyma.core.settings.dto.ProxySettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.OsTrustStore;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import ai.proxyma.core.common.net.RetryAfter;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.TreeSet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// HTTP client configuration.
// Every client validates certificates against Java's CAs, the operating system's and any CA set in
// Settings > Network (buildTrustManagers, SEC-29) - none trusts all of them.
// Local clients (ChromaDB) bypass the proxy.
// External clients (Jira, Confluence, LLM APIs, Ollama) route through the proxy
// configured in Settings > Network (mode: none / system / custom),
// with nonProxyHosts bypass for local addresses.
//
// The RestClient.Builder beans are PROTOTYPES, like Spring Boot's own: every injection point gets a
// builder of its own. They were singletons, and a consumer that called defaultHeader() on one - the
// web media fetcher's browser User-Agent, the crawler's - handed that header to every bean built
// after it: license activation, telemetry, the connectors, the update check.
@Configuration
public class RestClientConfig implements ai.proxyma.core.webcontent.WebProxyProvider {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    // Bounds every blocking RestClient call (ChromaDB, Jira/Confluence, vLLM embedding/reranking,
    // etc.) once the TCP connection is up - connectTimeout above only covers the handshake, so
    // without this a downstream service that accepts the connection but never responds (e.g. an
    // overloaded vLLM instance - see CLAUDE.md's GPU headroom note) hangs the calling thread
    // forever. That's fatal for chat tool calls specifically: ChatService.callTools() joins on
    // each tool's future, so one stuck call blocks the whole react() loop, which means chat()
    // never reaches its finally block and the conversation's abortFlags entry never clears -
    // rewind() then 409s indefinitely, even after the user refreshes (the frontend losing the
    // SSE subscription does not unstick the backend thread). Matches the 300s
    // responseTimeout() already used on the reactive externalWebClientBuilder below for the
    // same class of stuck-downstream-service problem.
    private static final Duration BLOCKING_READ_TIMEOUT = HttpTimeouts.INFRA_READ;

    // Generating an answer is not the same kind of wait as fetching a Jira issue, and it must not
    // be bounded by the same number.
    //
    // The timeout above is a guard against a service that has stopped responding. A blocking LLM
    // call has no such tell: the provider accepts the request, says nothing at all for as long as
    // generation takes, and then returns the whole body at once. Five minutes of silence is a
    // stuck ChromaDB and a perfectly healthy local model working through a large prompt, and the
    // socket cannot tell them apart.
    //
    // Streamed calls keep tokens arriving, so no single read waits long. A BLOCKING model call has
    // no such keep-alive traffic: a LiteLLM instance set to "Wait for whole replies", condensation
    // summaries, graph extraction, OCR and the guardrail classifier all wait in silence for the
    // whole answer. Sharing a bound tuned for callers that have keep-alive traffic killed a long
    // delegated step at exactly 300s, with a read timeout that read like a provider fault.
    //
    // Applied ONLY to the provider adapters, via llmClientHttpRequestFactory below. Connectors,
    // ChromaDB, telemetry and the update check keep the 300s bound - for those a long silence
    // really is a stuck downstream, which is what the comment above is about. Until 2026-10-14 the
    // factory ignored the argument and applied 300s to both, so this bound existed only on paper.
    private static final Duration LLM_READ_TIMEOUT = HttpTimeouts.LLM_READ;

    // Corporate proxies/middleboxes silently close idle TCP connections after their own idle
    // timeout - the same failure class the Reactor Netty pool below defends against with
    // maxIdleTime(30s) + background eviction. The blocking java.net.http.HttpClient used for
    // every RestClient in this class has no equivalent per-instance idle-time/eviction API
    // (unlike Reactor Netty's ConnectionProvider), so the only lever available is this JVM-wide
    // system property, which ages pooled connections out before a middlebox's own timeout can
    // kill them first. Must be set before any java.net.http.HttpClient is constructed (see
    // createClientHttpRequestFactory() below), so a static initializer is the only place that
    // guarantees ordering regardless of Spring's bean-instantiation order.
    static {
        System.setProperty("jdk.httpclient.keepalive.timeout", "25");
    }

    private final SettingsService settingsService;

    // The proxy mode a *fresh* deployment starts with, before any admin has ever touched
    // Settings > Network - generic and deploy-time-only. Proxyma's own default deployment leaves
    // this unset (stays "none");
    // a deployment whose container network requires a corporate proxy for all outbound
    // internet calls (e.g. one needing the Microsoft Entra SSO token exchange) sets
    // proxyma.network.default-proxy-mode=system in its own application-server.properties.
    // Never overrides an admin's explicit choice - see resolveMode() below.
    @Value("${proxyma.network.default-proxy-mode:none}")
    private String defaultProxyMode;

    public RestClientConfig(SettingsService settingsService,
                            @Value("${proxyma.outbound.block-private:false}") boolean blockPrivate,
                            @Value("${proxyma.outbound.block-metadata:true}") boolean blockMetadata,
                            @Value("${proxyma.outbound.allowed-hosts:}") List<String> allowedHosts) {
        this.settingsService = settingsService;
        // Set here, before any factory below is built, because the factories read it. See
        // OutboundAddressPolicy - the full private-address rule is on only for the public sandbox;
        // the link-local and cloud metadata rule is on everywhere unless the operator turns it off.
        OutboundAddressPolicy.setAllowedHosts(allowedHosts);
        OutboundAddressPolicy.setEnabled(blockPrivate);
        OutboundAddressPolicy.setMetadataBlocked(blockMetadata);
        if (blockPrivate) log.info("[Proxy] Outbound requests to private and local addresses are refused");
        if (!blockMetadata) log.warn("[Proxy] Outbound requests to link-local and cloud metadata addresses are ALLOWED");
    }

    // - Blocking (RestClient) -------------------------------------------------

    // The factory is shared; only the builder is per injection point. See the class comment.
    private volatile ClientHttpRequestFactory localFactory;

    @Bean
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder() {
        ClientHttpRequestFactory factory = localFactory;
        if (factory == null) {
            synchronized (this) {
                if (localFactory == null) localFactory = createClientHttpRequestFactory(false);
                factory = localFactory;
            }
        }
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder externalRestClientBuilder() {
        return RestClient.builder()
                .requestFactory(externalClientHttpRequestFactory())
                .requestInterceptor(staleConnectionRetryInterceptor())
                .requestInterceptor(rateLimitRetryInterceptor());
    }

    // Large syncs (e.g. an initial full Jira import fetching full issue details for
    // thousands of issues) can trip the remote server's own rate limiter even though our own
    // request volume is legitimate. Transparently retries 429s a few times with backoff so a
    // handful of issues don't end up permanently FAILED (SyncService never re-selects FAILED
    // items on its own).
    //
    // NOT on the LLM builder: ModelCall owns retrying a provider, with its own budget and Stop
    // handling, and a second layer underneath multiplied the waits.
    static final int RATE_LIMIT_MAX_RETRIES = 3;

    // A server's Retry-After is believed up to this long. "Come back in an hour" parked a sync or
    // tool thread for an hour per attempt; past this the 429 goes back to the caller, whose own
    // retry policy (GraphClient caps at 60 s, a sync retries next pass) decides.
    static final Duration RATE_LIMIT_MAX_WAIT = Duration.ofSeconds(30);

    static ClientHttpRequestInterceptor rateLimitRetryInterceptor() {
        return (request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            int attempt = 0;
            while (response.getStatusCode().value() == 429 && attempt < RATE_LIMIT_MAX_RETRIES) {
                Duration asked = RetryAfter.parse(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
                        Instant.now()).orElse(Duration.ZERO);
                if (asked.compareTo(RATE_LIMIT_MAX_WAIT) > 0) return response;
                long delayMs = Math.max(asked.toMillis(), 500L * (attempt + 1));
                response.close();
                log.warn("[RateLimit] 429 from {}, retrying in {}ms (attempt {}/{})",
                        request.getURI(), delayMs, attempt + 1, RATE_LIMIT_MAX_RETRIES);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    // A Stop, or a shutdown: the caller asked for this request to end, so no
                    // further request goes out.
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while waiting out a 429 from "
                            + request.getURI().getHost());
                }
                attempt++;
                response = execution.execute(request, body);
            }
            return response;
        };
    }

    @Bean
    public ClientHttpRequestFactory externalClientHttpRequestFactory() {
        return createClientHttpRequestFactory(true);
    }

    /**
     * The transport of the connector framework's ConnectorHttp: the same proxy, truststore and
     * timeouts as every external client, with NO redirect following of its own - not the JDK's and
     * not RedirectCheckingRequestFactory's, which copies every header to every hop. ConnectorHttp
     * follows redirects itself, checking the outbound policy at each hop and dropping the credential
     * when one leaves the connection's server.
     */
    @Bean
    public ClientHttpRequestFactory connectorClientHttpRequestFactory() {
        return createClientHttpRequestFactory(true, BLOCKING_READ_TIMEOUT, false);
    }

    // Same proxy, truststore and connect timeout as the external factory above - only the read
    // timeout differs. Injected by every provider adapter, so an LLM generation is not killed at a
    // bound meant for a stuck infrastructure call. See LLM_READ_TIMEOUT.
    @Bean
    public ClientHttpRequestFactory llmClientHttpRequestFactory() {
        return createClientHttpRequestFactory(true, LLM_READ_TIMEOUT);
    }

    // The builder form, for adapters that take a RestClient.Builder rather than a factory. Carries
    // the same stale-connection interceptor as externalRestClientBuilder, and NOT the rate-limit
    // one - see RATE_LIMIT_MAX_RETRIES.
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder llmRestClientBuilder() {
        return RestClient.builder()
                .requestFactory(llmClientHttpRequestFactory())
                .requestInterceptor(staleConnectionRetryInterceptor());
    }

    // - Reactive (WebClient) for external LLM APIs ----------------------------

    // Corporate proxies/load-balancers silently close idle TCP connections after a few minutes.
    // Reactor Netty's default HttpClient.create() pools connections indefinitely with no
    // eviction - reusing one whose TLS session was already torn down by a middlebox fails with
    // "javax.net.ssl.SSLException: failure when writing TLS control frames" on the next write,
    // surfacing mid-stream in long-lived chat completions. A dedicated pool with a max idle time
    // shorter than any known middlebox timeout, plus periodic background eviction, prevents a
    // dead connection from ever being handed back out.
    private static final ConnectionProvider EXTERNAL_CONNECTION_PROVIDER = ConnectionProvider.builder("proxyma-external")
            .maxConnections(50)
            .maxIdleTime(Duration.ofSeconds(30))
            .maxLifeTime(Duration.ofMinutes(5))
            .evictInBackground(Duration.ofSeconds(30))
            .build();

    @Bean
    public WebClient.Builder externalWebClientBuilder() {
        try {
            // Same trust material as the blocking path. Passing null to .trustManager() is not
            // valid, so the default builder (system truststore, full validation) is used when no
            // custom CA is configured. This was InsecureTrustManagerFactory.INSTANCE, which trusts
            // every certificate presented by anyone - see buildTrustManagers().
            SslContextBuilder sslBuilder = SslContextBuilder.forClient();
            TrustManager[] trust = buildTrustManagers();
            if (trust != null) {
                for (TrustManager tm : trust) {
                    if (tm instanceof X509TrustManager x509) {
                        sslBuilder = sslBuilder.trustManager(x509);
                        break;
                    }
                }
            }
            io.netty.handler.ssl.SslContext sslCtx = sslBuilder.build();

            reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create(EXTERNAL_CONNECTION_PROVIDER)
                    .secure(spec -> spec.sslContext(sslCtx))
                    .responseTimeout(Duration.ofSeconds(300));

            ResolvedProxy proxy = resolveProxy();
            if (proxy != null) {
                log.info("[Proxy] External WebClient using proxy {}:{}", proxy.host, proxy.port);
                // The SAME bypass decision as the blocking client's selector, not Reactor Netty's
                // nonProxyHosts(String). That one reads its argument as a regex alternation, so the
                // comma list Settings > Network asks for matched nothing, and a "*.corp" entry did
                // not compile at all. Every streamed chat turn then went to the proxy for a host the
                // provider test - blocking, through the selector - reached directly (0.27.1
                // feedback). An unset list also bypasses the same loopback names as that selector.
                httpClient = httpClient.proxy(spec -> spec
                        .type(ProxyProvider.Proxy.HTTP)
                        .host(proxy.host)
                        .port(proxy.port)
                        .nonProxyHostsPredicate(bypassPredicate(proxy.noProxy)));
            } else {
                log.info("[Proxy] External WebClient: no proxy configured");
            }

            WebClient.Builder builder = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .filter(outboundPolicyFilter());
            if (proxy != null) builder = builder.filter(connectFailureRouteFilter(proxy.noProxy));
            return builder;

        } catch (Exception e) {
            log.error("[Proxy] Failed to configure external WebClient.Builder, using default", e);
            return WebClient.builder().filter(outboundPolicyFilter());
        }
    }

    /**
     * OutboundAddressPolicy on the reactive client - every streamed model call and every MCP
     * server transport - as guarded() is on the blocking one. The check may resolve a name, so it
     * runs off the event loop.
     */
    static ExchangeFilterFunction outboundPolicyFilter() {
        return (request, next) -> {
            if (!OutboundAddressPolicy.isActive()) return next.exchange(request);
            return Mono.fromRunnable(() -> OutboundAddressPolicy.check(request.url()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(Mono.defer(() -> next.exchange(request)));
        };
    }

    /**
     * Says which way a request went when it could not connect - directly because the host is on
     * No proxy, or through the proxy - on the streaming client, which is what every chat turn and
     * sub-agent step uses. Given the same list the client's own bypass predicate was built from, so
     * the sentence cannot disagree with the route actually taken. See ProxyRoute.
     *
     * <p>The failure stays a WebClientRequestException (ModelCall retries on that type), and what
     * it wrapped stays under it, so every check that walks the chain still finds it.
     */
    static ExchangeFilterFunction connectFailureRouteFilter(String noProxy) {
        Set<String> bypassHosts = parseNoProxyHosts(noProxy != null ? noProxy : DEFAULT_NO_PROXY);
        return (request, next) -> next.exchange(request).onErrorMap(
                org.springframework.web.reactive.function.client.WebClientRequestException.class,
                e -> withRoute(e, bypassHosts));
    }

    static Throwable withRoute(org.springframework.web.reactive.function.client.WebClientRequestException e,
                               Set<String> bypassHosts) {
        String host = e.getUri() != null ? e.getUri().getHost() : null;
        if (host == null || !isConnectFailure(e)) return e;
        ProxyRoute route = shouldBypass(host, bypassHosts) ? ProxyRoute.BYPASSED : ProxyRoute.PROXY;
        // Built before the constructor, so the page's message list (tools/i18n/server-messages.mjs)
        // does not take it for a template: its second half is a sentence of its own, and it reaches
        // the page in English like the network leads Failures puts before it.
        String sentence = "Could not connect to " + host + " - " + route.note();
        ConnectException annotated = new ConnectException(sentence);
        annotated.initCause(e.getCause());
        return new org.springframework.web.reactive.function.client.WebClientRequestException(
                annotated, e.getMethod(), e.getUri(), e.getHeaders());
    }

    // The connection never came up: refused, timed out connecting, a name that did not resolve, or a
    // proxy that would not tunnel (Netty's, matched by name like Failures does). A failure later in
    // the exchange - a TLS alert, a reset mid-stream - says nothing about the route.
    private static boolean isConnectFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConnectException
                    || t instanceof java.nio.channels.UnresolvedAddressException
                    || t instanceof java.net.UnknownHostException
                    || t.getClass().getSimpleName().equals("ProxyConnectException")) {
                return true;
            }
        }
        return false;
    }

    @Bean
    public WebClient externalWebClient() {
        return externalWebClientBuilder().build();
    }

    // - Private helpers -------------------------------------------------------

    private ClientHttpRequestInterceptor staleConnectionRetryInterceptor() {
        return (request, body, execution) -> {
            try {
                return execution.execute(request, body);
            } catch (IOException e) {
                if (isStaleConnectionError(e)) {
                    log.debug("Retrying {} after stale connection error", request.getURI());
                    return execution.execute(request, body);
                }
                throw e;
            }
        };
    }

    private static boolean isStaleConnectionError(Throwable t) {
        while (t != null) {
            if (t instanceof ClosedChannelException) return true;
            if (t instanceof ConnectException && t.getCause() instanceof ClosedChannelException) return true;
            // JDK HttpClient's internal EOF-on-reused-pooled-connection condition has no
            // dedicated exception subtype - it surfaces as a plain IOException with this exact
            // message when a middlebox has already silently killed the connection before we
            // reuse it. Narrowly scoped to this substring so it can't accidentally swallow
            // unrelated IOExceptions.
            if (t instanceof IOException && t.getMessage() != null
                    && t.getMessage().contains("header parser received no bytes")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private ClientHttpRequestFactory createClientHttpRequestFactory(boolean useProxy) {
        return createClientHttpRequestFactory(useProxy, BLOCKING_READ_TIMEOUT);
    }

    ClientHttpRequestFactory createClientHttpRequestFactory(boolean useProxy, Duration readTimeout) {
        return createClientHttpRequestFactory(useProxy, readTimeout, true);
    }

    // followRedirects=false: the JDK client never follows one and no checking wrapper is added -
    // the caller follows them itself (connectorClientHttpRequestFactory).
    ClientHttpRequestFactory createClientHttpRequestFactory(boolean useProxy, Duration readTimeout,
                                                            boolean followRedirects) {
        try {
            // null == the JDK's own defaults, i.e. full validation against the system truststore.
            // This used to be a trust-all X509TrustManager with an empty checkServerTrusted body;
            // see buildTrustManagers() for what that meant and why it is gone.
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, buildTrustManagers(), new SecureRandom());

            java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(HttpTimeouts.CONNECT)
                    .sslContext(sslContext);

            if (useProxy) {
                ResolvedProxy proxy = resolveProxy();
                if (proxy != null) {
                    builder.proxy(guarded(buildNoProxyAwareSelector(proxy)));
                    log.info("[Proxy] External RestClient using proxy {}:{}, noProxy={}",
                            proxy.host, proxy.port, proxy.noProxy != null ? proxy.noProxy : "(none)");
                } else {
                    builder.proxy(guarded(ProxySelector.of(null)));
                    log.info("[Proxy] External RestClient: no proxy configured (mode resolved to none)");
                }
                // Wherever the policy checks anything - the sandbox's full rule, or the metadata rule
                // every edition carries - the client stops at every redirect and
                // RedirectCheckingRequestFactory follows it, checking each hop: the JDK client asks
                // its ProxySelector about the first request only, so guarded() never sees a hop, and
                // a public URL answering 302 to 169.254.169.254 would otherwise be followed.
                builder.followRedirects(OutboundAddressPolicy.isActive() || !followRedirects
                        ? java.net.http.HttpClient.Redirect.NEVER
                        : java.net.http.HttpClient.Redirect.NORMAL);
                setSystemProxyProperties(proxy);
            } else {
                builder.proxy(ProxySelector.of(null));
                log.info("[Proxy] Local RestClient configured without proxy");
            }

            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
            factory.setReadTimeout(readTimeout);
            return useProxy && followRedirects && OutboundAddressPolicy.isActive()
                    ? new RedirectCheckingRequestFactory(factory)
                    : factory;

        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.error("[Proxy] Failed to configure SSL for RestClient, using default", e);
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                    java.net.http.HttpClient.newBuilder()
                            .connectTimeout(HttpTimeouts.CONNECT)
                            .build());
            factory.setReadTimeout(readTimeout);
            return factory;
        }
    }

    // - Trust material ---------------------------------------------------------

    /**
     * The TrustManagers every outbound HTTPS client uses: Java's own truststore, plus every CA the
     * OPERATING SYSTEM trusts, plus any CA an administrator has pasted into Settings > Network.
     *
     * WHAT THIS REPLACED, and why it is worth spelling out. Both client paths here previously
     * installed a trust-all X509TrustManager - one whose checkServerTrusted() had an empty body -
     * unconditionally, for local and external clients alike. Every outbound HTTPS call in the
     * product accepted any certificate from anyone: every LLM provider, license activation,
     * telemetry and feedback uploads, and every connector sync carrying a customer's Jira,
     * Confluence, SharePoint, Bitbucket and SVN credentials. Anyone on-path could present a
     * self-signed certificate and read or rewrite all of it. For a product sold on "your data
     * never leaves your infrastructure" that was the single sharpest contradiction in the codebase.
     *
     * The case it existed to serve is real: a company CA - an internal host, or a TLS-inspecting
     * proxy re-signing every connection - is not in Java's public list, so handshakes fail. The fix
     * for that is to trust THAT CA, not everybody. The operating system already does, which is why
     * curl works on the same machine; OsTrustStore reads it. proxy.ca-certificate remains for a CA
     * the machine itself does not trust.
     *
     * Returning null means "use the JDK defaults", which is the correct, fully-validating
     * behaviour and the path taken when there is nothing to add.
     */
    private TrustManager[] buildTrustManagers() {
        String pem = resolveCaCertificate();
        List<X509Certificate> os = OsTrustStore.certificates();
        boolean custom = pem != null && !pem.isBlank();
        if (!custom && os.isEmpty()) return null;

        try {
            // Seeded from the platform defaults FIRST, then added to. A CA added to reach one
            // internal host must not stop the rest of the internet from verifying - which is what
            // a truststore containing only that CA would do.
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);

            TrustManagerFactory platform = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            platform.init((KeyStore) null);
            int defaults = 0;
            for (TrustManager tm : platform.getTrustManagers()) {
                if (tm instanceof X509TrustManager x509) {
                    for (X509Certificate issuer : x509.getAcceptedIssuers()) {
                        store.setCertificateEntry("default-" + defaults++, issuer);
                    }
                }
            }

            int fromOs = 0;
            for (X509Certificate c : os) {
                store.setCertificateEntry("os-" + fromOs++, c);
            }

            int added = 0;
            if (custom) {
                // A PEM file may legitimately hold a whole chain, so take every certificate in it
                // rather than only the first.
                Collection<? extends Certificate> extra = CertificateFactory.getInstance("X.509")
                        .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
                if (extra.isEmpty()) {
                    log.warn("[TLS] proxy.ca-certificate is set but contains no readable certificate - "
                            + "ignoring it. Expected PEM (-----BEGIN CERTIFICATE-----).");
                }
                for (Certificate c : extra) {
                    store.setCertificateEntry("custom-" + added++, c);
                }
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            log.info("[TLS] Trusting {} Java CA(s), {} from the operating system and {} from Settings > Network",
                    defaults, fromOs, added);
            return tmf.getTrustManagers();

        } catch (Exception e) {
            // Deliberately NOT falling back to trusting everything. A malformed CA means outbound
            // TLS to the inspected host fails loudly, which is recoverable by fixing the setting;
            // silently disabling validation would be the vulnerability this method exists to close,
            // reintroduced by an error path.
            log.error("[TLS] Could not build the combined truststore ({}). Falling back to Java's own "
                    + "truststore - hosts signed by a company CA will fail until this is corrected.", e.getMessage());
            return null;
        }
    }

    /**
     * Makes the same trust the JVM's DEFAULT, for the clients this class cannot hand a factory to.
     *
     * The Google GenAI and Azure OpenAI SDKs build their own HTTP clients from the JVM defaults -
     * the same reason setSystemProxyProperties exists for the proxy - so without this an internal
     * gateway reached through either kept failing after every other provider had been fixed.
     * Additive, like the rest: the default gains CAs, it loses none.
     */
    @jakarta.annotation.PostConstruct
    void installAsJvmDefault() {
        TrustManager[] trust = buildTrustManagers();
        if (trust == null) return;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust, new SecureRandom());
            SSLContext.setDefault(context);
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        } catch (Exception e) {
            log.warn("[TLS] Could not make the combined truststore the JVM default: {}", e.getMessage());
        }
    }

    private String resolveCaCertificate() {
        try {
            return settingsService.getSettings(ProxySettings.PREFIX, ProxySettings.class).getCaCertificate();
        } catch (Exception e) {
            log.debug("[TLS] Could not read proxy settings for a custom CA: {}", e.getMessage());
            return null;
        }
    }

    private void setSystemProxyProperties(ResolvedProxy proxy) {
        // Cleared, not just skipped, when there is no proxy. These are JVM-global and are what the
        // Google GenAI and Azure SDKs read - they build their own HTTP clients and cannot be handed
        // one of ours - so a value left over from an earlier configuration would keep those two
        // providers pointed at a proxy the administrator has since turned off.
        if (proxy == null) {
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
            System.clearProperty("http.nonProxyHosts");
            return;
        }
        {
            String portStr = String.valueOf(proxy.port);
            System.setProperty("http.proxyHost",  proxy.host);
            System.setProperty("http.proxyPort",  portStr);
            System.setProperty("https.proxyHost", proxy.host);
            System.setProperty("https.proxyPort", portStr);
            System.setProperty("http.nonProxyHosts", jvmNonProxyHosts(proxy.noProxy));
        }
    }

    /**
     * The noProxy list in the JVM's own {@code http.nonProxyHosts} form, carrying the same rules as
     * shouldBypass: a ".corp" or "*.corp" entry covers corp and every name under it, a bare
     * "corp.example" covers itself and every name under it, and loopback is always direct. This
     * was a comma-to-bar replace, which kept leading spaces and lost every suffix rule and the
     * loopback one, so the SDK-built clients (Gemini, Azure) disagreed with every other client.
     * The JVM's wildcard works only at either end of an entry; a glob with a star in the middle is
     * passed through as written.
     */
    static String jvmNonProxyHosts(String noProxy) {
        Set<String> out = new LinkedHashSet<>(List.of("localhost", "*.localhost", "127.*", "[::1]"));
        for (String p : new TreeSet<>(parseNoProxyHosts(noProxy != null ? noProxy : DEFAULT_NO_PROXY))) {
            if (p.equals("*")) {
                out.add("*");
            } else if (p.startsWith("*.")) {
                out.add(p);
                out.add(p.substring(2));
            } else if (p.startsWith(".")) {
                out.add("*" + p);
                out.add(p.substring(1));
            } else if (p.indexOf('*') >= 0) {
                out.add(p);
            } else {
                out.add(p);
                out.add("*." + p);
            }
        }
        return String.join("|", out);
    }

    // - The outbound address check, on every hop ------------------------------

    /**
     * The check on the FIRST hop, for every request through the external and LLM factories -
     * including ones whose caller never checked (the local factory, ChromaDB, never comes here).
     * NOT the check on later hops: the JDK client asks its ProxySelector about the first request
     * only (measured 2026-09-29 - a 302 to 127.0.0.1 was followed without a second select()), so
     * redirects are RedirectCheckingRequestFactory's job. A refusal throws out of select(), which
     * the client surfaces as the request's failure with OutboundAddressPolicy's sentence.
     */
    private static ProxySelector guarded(ProxySelector delegate) {
        if (!OutboundAddressPolicy.isActive()) return delegate;
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                OutboundAddressPolicy.check(uri);
                return delegate.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                delegate.connectFailed(uri, sa, ioe);
            }
        };
    }

    // - ProxySelector that respects nonProxyHosts -----------------------------

    // What an unset noProxy list means, for both clients.
    private static final String DEFAULT_NO_PROXY = "localhost,127.0.0.1,::1";

    /** The streaming client's bypass decision - the selector's below, for a Netty address. */
    static java.util.function.Predicate<SocketAddress> bypassPredicate(String noProxy) {
        Set<String> bypassHosts = parseNoProxyHosts(noProxy != null ? noProxy : DEFAULT_NO_PROXY);
        return address -> address instanceof InetSocketAddress inet
                && inet.getHostString() != null
                && shouldBypass(inet.getHostString(), bypassHosts);
    }

    private ProxySelector buildNoProxyAwareSelector(ResolvedProxy proxy) {
        Set<String> bypassHosts = parseNoProxyHosts(
                proxy.noProxy != null ? proxy.noProxy : DEFAULT_NO_PROXY);
        InetSocketAddress proxyAddr = new InetSocketAddress(proxy.host, proxy.port);

        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String host = uri.getHost();
                if (host != null && shouldBypass(host, bypassHosts)) {
                    log.debug("[Proxy] Bypassing proxy for {} (matched noProxy rule)", host);
                    return List.of(Proxy.NO_PROXY);
                }
                log.debug("[Proxy] Routing {} through {}:{}", host, proxy.host, proxy.port);
                return List.of(new Proxy(Proxy.Type.HTTP, proxyAddr));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                log.warn("[Proxy] Connection to {} via proxy {} failed: {}",
                        uri, sa, ioe.getMessage());
            }
        };
    }

    // The NO_PROXY rules live in common (NoProxyRules), so the blocking selector, the streaming
    // predicate, the SDK clients and the svn subprocess read a list the same way.
    static Set<String> parseNoProxyHosts(String noProxy) {
        return ai.proxyma.core.common.net.NoProxyRules.parse(noProxy);
    }

    static boolean shouldBypass(String host, Set<String> bypassHosts) {
        return ai.proxyma.core.common.net.NoProxyRules.shouldBypass(host, bypassHosts);
    }

    // - Proxy resolution ------------------------------------------------------

    record ResolvedProxy(String host, int port, String noProxy) {}

    private volatile boolean proxyLoggedOnce = false;

    private ResolvedProxy resolveProxy() {
        ProxySettings settings = settingsService.getSettings(ProxySettings.PREFIX, ProxySettings.class);
        ProxySettings.Mode mode = resolveMode(settings);

        if (mode == ProxySettings.Mode.none) return null;

        if (mode == ProxySettings.Mode.system) {
            ResolvedProxy env = parseFromEnv();
            if (!proxyLoggedOnce) {
                proxyLoggedOnce = true;
                if (env != null) {
                    log.info("[Proxy] System proxy resolved from environment: {}:{}, noProxy={}",
                            env.host, env.port, env.noProxy != null ? env.noProxy : "(none)");
                } else {
                    log.warn("[Proxy] System proxy mode selected but no HTTPS_PROXY/HTTP_PROXY env vars found");
                }
            }
            return env;
        }

        // custom mode
        String httpsProxy = settings.getHttpsProxy();
        String httpProxy = settings.getHttpProxy();
        String url = (httpsProxy != null && !httpsProxy.isBlank()) ? httpsProxy
                   : (httpProxy != null && !httpProxy.isBlank()) ? httpProxy : null;
        if (url == null) {
            if (!proxyLoggedOnce) {
                proxyLoggedOnce = true;
                log.warn("[Proxy] Custom proxy mode but no proxy URL configured");
            }
            return null;
        }
        ResolvedProxy custom = parseProxyUrl(url, settings.getNoProxy());
        if (!proxyLoggedOnce && custom != null) {
            proxyLoggedOnce = true;
            log.info("[Proxy] Custom proxy: {}:{}, noProxy={}",
                    custom.host, custom.port, custom.noProxy != null ? custom.noProxy : "(none)");
        }
        return custom;
    }

    // getSettings() can't distinguish "admin never saved a proxy.mode value" from "admin
    // explicitly chose none" - both deserialize to the same ProxySettings.Mode.none default.
    // getSettingsFields() returns only the keys actually present in the settings file, so its
    // absence is the real signal for "never configured" - that's the only case where this
    // deployment's own default-proxy-mode applies. Once an admin saves ANY value (including
    // explicitly picking "None" in the UI), that stored choice always wins from then on.
    private ProxySettings.Mode resolveMode(ProxySettings settings) {
        if (!settingsService.getSettingsFields(ProxySettings.PREFIX).containsKey("mode")) {
            try {
                return ProxySettings.Mode.valueOf(defaultProxyMode);
            } catch (Exception e) {
                return ProxySettings.Mode.none;
            }
        }
        return settings.getResolvedMode();
    }

    private ResolvedProxy parseFromEnv() {
        for (String var : new String[]{"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"}) {
            String val = System.getenv(var);
            if (val != null && !val.isBlank()) {
                log.debug("[Proxy] Found env var {}={}", var, masked(val));
                String noProxy = System.getenv("NO_PROXY");
                if (noProxy == null || noProxy.isBlank()) noProxy = System.getenv("no_proxy");
                return parseProxyUrl(val, noProxy);
            } else {
                log.debug("[Proxy] Env var {} = {}", var,
                        val == null ? "(not set)" : "(empty)");
            }
        }
        return null;
    }

    // A scheme-less "proxy.corp:3128" - which curl accepts - is read as http. It parsed with
    // "proxy.corp" as the scheme and no host, and silently meant no proxy at all.
    static ResolvedProxy parseProxyUrl(String url, String noProxy) {
        if (url == null) return null;
        String trimmed = url.trim();
        String withScheme = trimmed.contains("://") ? trimmed : "http://" + trimmed;
        try {
            URI proxyUri = URI.create(withScheme);
            String host = proxyUri.getHost();
            int port = proxyUri.getPort();
            if (host != null && port > 0) {
                if (proxyUri.getRawUserInfo() != null) {
                    log.warn("[Proxy] Proxy URL {} carries credentials, which are not sent - "
                            + "Proxy-Authorization is not supported", masked(trimmed));
                }
                return new ResolvedProxy(host, port, noProxy);
            }
            log.warn("[Proxy] Could not extract host/port from proxy URL '{}'", masked(trimmed));
        } catch (Exception e) {
            log.warn("[Proxy] Failed to parse proxy URL '{}': {}", masked(trimmed), e.getClass().getSimpleName());
        }
        return null;
    }

    // A proxy URL's user:password part, never written to a log.
    static String masked(String url) {
        return url == null ? null : url.replaceAll("(?<=://)[^/@]*@", "***@");
    }

    // - Public accessor for other components to resolve proxy -----------------

    /**
     * The proxy a client should use for {@code host}: NO_PROXY when none is configured or the
     * noProxy list covers the host - the same decision the blocking selector and the streaming
     * predicate make. For clients built by an SDK (Azure) that take one proxy and no bypass list.
     */
    public java.net.Proxy proxyFor(String host) {
        ResolvedProxy p = resolveProxy();
        if (p == null) return java.net.Proxy.NO_PROXY;
        if (host != null && shouldBypass(host,
                parseNoProxyHosts(p.noProxy != null ? p.noProxy : DEFAULT_NO_PROXY))) {
            return java.net.Proxy.NO_PROXY;
        }
        return new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress(p.host, p.port));
    }

    public java.net.Proxy getJavaProxy() {
        ResolvedProxy p = resolveProxy();
        if (p == null) return java.net.Proxy.NO_PROXY;
        return new java.net.Proxy(java.net.Proxy.Type.HTTP,
                new InetSocketAddress(p.host, p.port));
    }

    public String getNoProxyHosts() {
        ResolvedProxy p = resolveProxy();
        return p != null && p.noProxy != null ? p.noProxy : "";
    }

    // The render engines' bypass decision - the selector's, from the same list and default.
    @Override
    public boolean bypassesProxy(String host) {
        if (host == null || host.isBlank()) return false;
        ResolvedProxy p = resolveProxy();
        return shouldBypass(host, parseNoProxyHosts(p != null && p.noProxy != null ? p.noProxy : DEFAULT_NO_PROXY));
    }
}
