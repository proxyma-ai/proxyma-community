package ai.proxyma.core.connector.spi;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * The one HTTP helper every connector built on this framework uses - see {@link ApiClient} for what
 * it does to every request. This bean only holds the transport: the deployment's proxy, its custom
 * CA certificates and its no-proxy list, from the {@code connectorClientHttpRequestFactory} bean
 * proxyma-core-network builds (the same trust and proxy as every other outbound client, but with
 * redirects left to ApiClient, which drops the credential when one leaves the server).
 *
 * <p>Without that bean - a connector's own test - it falls back to a plain JDK client, with no proxy
 * and redirects not followed by the JDK.
 */
@Component
public class ConnectorHttp {

    /** How a retry waits; a test swaps in one that records the wait and returns at once. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Sleeper THREAD_SLEEP = d -> Thread.sleep(Math.max(0, d.toMillis()));

    private final ClientHttpRequestFactory factory;
    private final Sleeper sleeper;
    private final Clock clock;

    @Autowired
    public ConnectorHttp(@Qualifier("connectorClientHttpRequestFactory") ObjectProvider<ClientHttpRequestFactory> factory) {
        this(factory.getIfAvailable(ConnectorHttp::directFactory), THREAD_SLEEP, Clock.systemUTC());
    }

    public ConnectorHttp(ClientHttpRequestFactory factory, Sleeper sleeper, Clock clock) {
        this.factory = factory;
        this.sleeper = sleeper == null ? THREAD_SLEEP : sleeper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** A plain client - no proxy, no custom CA. For tests and tools that run outside the application. */
    public static ConnectorHttp direct() {
        return new ConnectorHttp(directFactory(), THREAD_SLEEP, Clock.systemUTC());
    }

    /** As {@link #direct()}, with retries that wait through {@code sleeper}. */
    public static ConnectorHttp direct(Sleeper sleeper) {
        return new ConnectorHttp(directFactory(), sleeper, Clock.systemUTC());
    }

    /** A client for one service at {@code baseUrl} - add the auth, the service's name and its quirks. */
    public ApiClient.Builder client(String baseUrl) {
        return ApiClient.builder(this, baseUrl);
    }

    ClientHttpRequestFactory factory() {
        return factory;
    }

    Sleeper sleeper() {
        return sleeper;
    }

    Clock clock() {
        return clock;
    }

    private static ClientHttpRequestFactory directFactory() {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        f.setReadTimeout(Duration.ofSeconds(120));
        return f;
    }
}
