package ai.proxyma.core.network;

import ai.proxyma.core.common.net.OutboundAddressPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Follows redirects itself, checking every hop against {@link OutboundAddressPolicy} - used only
 * where that policy is on (the public sandbox), around a client whose own redirect-following is off.
 *
 * <p><b>WHY THE CLIENT CANNOT DO THIS.</b> The JDK HttpClient asks its ProxySelector about the
 * FIRST request only. Measured on 2026-09-29: a request to a public URL answering 302 with an https
 * address on 127.0.0.1 was followed straight to 127.0.0.1, and the selector - which was checking
 * the first hop correctly - was never asked about the second. A check at the call site sees only
 * the first hop too. So on the sandbox the client stops at every 3xx and this decides.
 *
 * <p>The rules are the JDK's own NORMAL policy, so nothing else changes: up to five hops, never
 * from https down to http, 303 (and 301/302 after a POST) become a body-less GET, 307 and 308 keep
 * the method and body. Headers travel with every hop, as they do with the JDK's own following -
 * a connector's credential goes to wherever its own server redirects it, exactly as before.
 */
final class RedirectCheckingRequestFactory implements ClientHttpRequestFactory {

    private static final int MAX_HOPS = 5;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    private final ClientHttpRequestFactory delegate;

    RedirectCheckingRequestFactory(ClientHttpRequestFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
        return new Request(uri, httpMethod);
    }

    private final class Request extends AbstractClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream(256);

        Request(URI uri, HttpMethod method) {
            this.uri = uri;
            this.method = method;
        }

        @Override public HttpMethod getMethod() { return method; }
        @Override public URI getURI() { return uri; }

        @Override
        protected OutputStream getBodyInternal(HttpHeaders headers) {
            return body;
        }

        @Override
        protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
            URI current = uri;
            HttpMethod currentMethod = method;
            byte[] currentBody = body.toByteArray();
            for (int hop = 0; ; hop++) {
                OutboundAddressPolicy.check(current);
                ClientHttpRequest request = delegate.createRequest(current, currentMethod);
                request.getHeaders().putAll(headers);
                if (currentBody.length > 0) request.getBody().write(currentBody);
                ClientHttpResponse response = request.execute();

                int status = response.getStatusCode().value();
                String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
                if (!REDIRECTS.contains(status) || location == null || hop >= MAX_HOPS) return response;
                URI next = current.resolve(location.trim());
                String from = scheme(current);
                String to = scheme(next);
                if (!to.equals("http") && !to.equals("https")) return response;
                if (from.equals("https") && to.equals("http")) return response;

                response.close();
                if (status == 303 || ((status == 301 || status == 302) && currentMethod == HttpMethod.POST)) {
                    currentMethod = HttpMethod.GET;
                    currentBody = new byte[0];
                }
                current = next;
            }
        }

        private String scheme(URI u) {
            return u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        }
    }
}
