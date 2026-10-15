package ai.proxyma.core.network;

import ai.proxyma.core.common.net.OutboundAddressPolicy;
import ai.proxyma.core.settings.dto.ProxySettings;
import ai.proxyma.core.settings.service.SettingsService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the shared outbound clients promise and, until 2026-10-14, did not do.
 *
 * <ul>
 *   <li>The LLM factory's read bound is its own. {@code createClientHttpRequestFactory} took the
 *       bound as an argument and applied 300 s whatever it was given, so a blocking model call
 *       still died at five minutes - the failure LLM_READ_TIMEOUT was written for.</li>
 *   <li>Every injection point gets its own builder. They were singletons, and a consumer adding a
 *       default header handed it to every bean built after it.</li>
 *   <li>The 429 interceptor neither waits for an hour nor sends one more request after a Stop.</li>
 * </ul>
 */
class OutboundClientsTest {

    private HttpServer server;
    private int port;
    private final List<String> userAgents = new CopyOnWriteArrayList<>();
    private final AtomicInteger hits = new AtomicInteger();
    private volatile String retryAfter = "0";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply(ex, 200, "late");
        });
        server.createContext("/headers", ex -> {
            String ua = ex.getRequestHeaders().getFirst("X-Probe");
            userAgents.add(ua == null ? "(none)" : ua);
            reply(ex, 200, "ok");
        });
        server.createContext("/busy", ex -> {
            int n = hits.incrementAndGet();
            if (n == 1) {
                ex.getResponseHeaders().add("Retry-After", retryAfter);
                reply(ex, 429, "busy");
            } else {
                reply(ex, 200, "ok");
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        OutboundAddressPolicy.setEnabled(false);
        OutboundAddressPolicy.setMetadataBlocked(true);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String text) throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static SettingsService settings() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(eq(ProxySettings.PREFIX), eq(ProxySettings.class))).thenReturn(new ProxySettings());
        when(settings.getSettingsFields(anyString())).thenReturn(Map.of());
        return settings;
    }

    private RestClientConfig config() {
        return new RestClientConfig(settings(), false, true, List.of());
    }

    private static boolean inChain(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) return true;
        }
        return false;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    @DisplayName("the read bound a factory is given is the one it applies")
    void readTimeoutArgumentIsApplied() {
        RestClientConfig config = config();
        RestClient tight = RestClient.builder()
                .requestFactory(config.createClientHttpRequestFactory(true, Duration.ofMillis(300))).build();
        RestClient loose = RestClient.builder()
                .requestFactory(config.createClientHttpRequestFactory(true, Duration.ofSeconds(10))).build();

        assertThatThrownBy(() -> tight.get().uri(url("/slow")).retrieve().body(String.class))
                .satisfies(e -> assertThat(inChain(e, java.net.http.HttpTimeoutException.class)).isTrue());
        assertThat(loose.get().uri(url("/slow")).retrieve().body(String.class)).isEqualTo("late");
    }

    @Test
    @DisplayName("the LLM factory waits 1800 s for a reply, the infrastructure one 300 s")
    void llmFactoryHasItsOwnBound() throws Exception {
        RestClientConfig config = config();
        assertThat(readTimeoutOf(config.llmClientHttpRequestFactory())).isEqualTo(HttpTimeouts.LLM_READ);
        assertThat(readTimeoutOf(config.externalClientHttpRequestFactory())).isEqualTo(HttpTimeouts.INFRA_READ);
        assertThat(HttpTimeouts.LLM_READ).isEqualTo(Duration.ofSeconds(1800));
    }

    private static Duration readTimeoutOf(ClientHttpRequestFactory factory) throws Exception {
        if (factory instanceof RedirectCheckingRequestFactory) {
            Field delegate = RedirectCheckingRequestFactory.class.getDeclaredField("delegate");
            delegate.setAccessible(true);
            factory = (ClientHttpRequestFactory) delegate.get(factory);
        }
        Field field = JdkClientHttpRequestFactory.class.getDeclaredField("readTimeout");
        field.setAccessible(true);
        return (Duration) field.get(factory);
    }

    @Test
    @DisplayName("one consumer's default header never reaches another consumer's requests")
    void buildersArePerInjectionPoint() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SettingsService.class, OutboundClientsTest::settings);
            context.register(RestClientConfig.class);
            context.refresh();

            RestClient.Builder first = context.getBean("externalRestClientBuilder", RestClient.Builder.class);
            RestClient.Builder second = context.getBean("externalRestClientBuilder", RestClient.Builder.class);
            assertThat(first).isNotSameAs(second);
            assertThat(context.getBean("restClientBuilder", RestClient.Builder.class))
                    .isNotSameAs(context.getBean("restClientBuilder", RestClient.Builder.class));

            first.defaultHeader("X-Probe", "crawler");
            first.build().get().uri(url("/headers")).retrieve().toBodilessEntity();
            second.build().get().uri(url("/headers")).retrieve().toBodilessEntity();
            assertThat(userAgents).containsExactly("crawler", "(none)");
        }
    }

    @Test
    @DisplayName("a short Retry-After is waited out and the request sent again")
    void shortRetryAfterIsRetried() {
        retryAfter = "0";
        RestClient client = RestClient.builder()
                .requestFactory(config().createClientHttpRequestFactory(true, Duration.ofSeconds(5)))
                .requestInterceptor(RestClientConfig.rateLimitRetryInterceptor()).build();
        assertThat(client.get().uri(url("/busy")).retrieve().body(String.class)).isEqualTo("ok");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("an hour's Retry-After is not believed: the 429 goes back to the caller at once")
    void longRetryAfterIsHandedBack() {
        retryAfter = "3600";
        RestClient client = RestClient.builder()
                .requestFactory(config().createClientHttpRequestFactory(true, Duration.ofSeconds(5)))
                .requestInterceptor(RestClientConfig.rateLimitRetryInterceptor()).build();
        long started = System.nanoTime();
        var response = client.get().uri(url("/busy")).retrieve()
                .onStatus(s -> true, (req, res) -> { })
                .toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an interrupt while waiting out a 429 ends the request - nothing more is sent")
    void interruptEndsTheWait() throws Exception {
        retryAfter = "20";
        RestClient client = RestClient.builder()
                .requestFactory(config().createClientHttpRequestFactory(true, Duration.ofSeconds(5)))
                .requestInterceptor(RestClientConfig.rateLimitRetryInterceptor()).build();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                client.get().uri(url("/busy")).retrieve().body(String.class);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        caller.start();
        while (hits.get() == 0) Thread.sleep(10);
        Thread.sleep(200);
        caller.interrupt();
        caller.join(5000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(inChain(failure.get(), InterruptedIOException.class)).isTrue();
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a public URL answering 302 to the metadata address is not followed, on every edition")
    void redirectToMetadataIsRefused() {
        server.createContext("/hop", ex -> {
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        RestClient client = RestClient.builder()
                .requestFactory(config().externalClientHttpRequestFactory()).build();
        assertThatThrownBy(() -> client.get().uri(url("/hop")).retrieve().body(String.class))
                .satisfies(e -> assertThat(inChain(e, OutboundAddressPolicy.BlockedDestinationException.class)).isTrue());
    }

    @Test
    @DisplayName("the JVM-wide nonProxyHosts carries the same rules as the selector")
    void jvmNonProxyHostsMatchesTheSelector() {
        String jvm = RestClientConfig.jvmNonProxyHosts(" .corp.example, llm.lab ,10.20.*");
        assertThat(jvm.split("\\|")).contains("localhost", "127.*", "[::1]",
                "*.corp.example", "corp.example", "llm.lab", "*.llm.lab", "10.20.*");
        assertThat(jvm).doesNotContain(" ");
        assertThat(RestClientConfig.jvmNonProxyHosts(null).split("\\|")).contains("localhost", "127.*");
    }

    @Test
    @DisplayName("a scheme-less proxy is read as http, and its credentials never reach a log line")
    void proxyUrlParsing() {
        RestClientConfig.ResolvedProxy p = RestClientConfig.parseProxyUrl("proxy.corp:3128", null);
        assertThat(p).isNotNull();
        assertThat(p.host()).isEqualTo("proxy.corp");
        assertThat(p.port()).isEqualTo(3128);
        assertThat(RestClientConfig.masked("http://alice:s3cret@proxy.corp:8080"))
                .isEqualTo("http://***@proxy.corp:8080")
                .doesNotContain("s3cret");
    }

    @Test
    @DisplayName("proxyFor honors the noProxy list, as Azure's single-proxy client needs")
    void proxyForHonorsBypass() {
        SettingsService settings = mock(SettingsService.class);
        ProxySettings custom = new ProxySettings();
        custom.setMode("custom");
        custom.setHttpsProxy("http://proxy.corp:3128");
        custom.setNoProxy("azure.internal.example");
        when(settings.getSettings(eq(ProxySettings.PREFIX), eq(ProxySettings.class))).thenReturn(custom);
        when(settings.getSettingsFields(anyString())).thenReturn(Map.of("mode", "custom"));
        RestClientConfig config = new RestClientConfig(settings, false, true, List.of());

        assertThat(config.proxyFor("my.azure.internal.example")).isEqualTo(java.net.Proxy.NO_PROXY);
        assertThat(config.proxyFor("api.openai.azure.com").type()).isEqualTo(java.net.Proxy.Type.HTTP);
    }
}
