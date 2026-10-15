package ai.proxyma.core.system.parts;

import ai.proxyma.core.system.util.OsTrustStore;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * How parts and release files are fetched: one GET, optionally from a byte offset.
 *
 * <p>An interface because the two callers reach the network differently. The launcher runs before
 * the application and its settings exist, so it uses {@link #jdk()}; the application's updater
 * passes its own client, which carries the proxy the user configured in Settings > Network.
 */
public interface PartsTransport {

    /**
     * GETs {@code url}, from byte {@code offset} when it is above zero (a Range request - a server
     * that ignores it answers 200 with the whole body, and the caller must check).
     */
    <T> T get(URI url, long offset, ResponseHandler<T> handler) throws IOException;

    @FunctionalInterface
    interface ResponseHandler<T> {
        /** {@code contentLength} is -1 when the server did not say. */
        T handle(int status, long contentLength, InputStream body) throws IOException;
    }

    /**
     * The JDK's HTTP client, set up the way a first start on a company laptop needs.
     *
     * <ul>
     *   <li><b>The system proxy.</b> {@code HTTPS_PROXY} when it is set, else the operating
     *       system's own settings ({@code java.net.useSystemProxies}, which the launcher turns on
     *       before anything touches the network).</li>
     *   <li><b>The certificate authorities the operating system trusts</b>, beside Java's own - a
     *       company that inspects TLS re-signs github.com with its own root, installed into the
     *       Windows store by policy, and Java alone would refuse every download with "PKIX path
     *       building failed". See OsTrustStore.</li>
     * </ul>
     */
    static PartsTransport jdk() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20));
        ProxySelector proxy = envProxy();
        builder.proxy(proxy != null ? proxy : ProxySelector.getDefault());
        SSLContext ssl = sslWithOsRoots();
        if (ssl != null) builder.sslContext(ssl);
        HttpClient client = builder.build();
        return new PartsTransport() {
            @Override
            public <T> T get(URI url, long offset, ResponseHandler<T> handler) throws IOException {
                HttpRequest.Builder request = HttpRequest.newBuilder(url)
                        .header("User-Agent", "Proxyma")
                        .header("Accept", "application/octet-stream")
                        .GET();
                if (offset > 0) request.header("Range", "bytes=" + offset + "-");
                try {
                    HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream body = response.body()) {
                        long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                        return handler.handle(response.statusCode(), length, body);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
        };
    }

    private static ProxySelector envProxy() {
        for (String name : new String[] {"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"}) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) continue;
            try {
                URI uri = URI.create(value.contains("://") ? value : "http://" + value);
                if (uri.getHost() == null) continue;
                int port = uri.getPort() > 0 ? uri.getPort() : 80;
                return ProxySelector.of(new InetSocketAddress(uri.getHost(), port));
            } catch (Exception ignored) {
                // A malformed variable is somebody else's problem; the system settings still apply.
            }
        }
        return null;
    }

    private static SSLContext sslWithOsRoots() {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            Path cacerts = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
            if (Files.isRegularFile(cacerts)) {
                try (InputStream in = Files.newInputStream(cacerts)) {
                    store.load(in, null);
                }
            } else {
                store.load(null, null);
            }
            int n = 0;
            for (X509Certificate c : OsTrustStore.certificates()) store.setCertificateEntry("os-" + n++, c);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            return null;
        }
    }
}
