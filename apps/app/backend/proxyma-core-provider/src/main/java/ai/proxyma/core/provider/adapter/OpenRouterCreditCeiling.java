package ai.proxyma.core.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A CALL OPENROUTER'S CREDIT CAN PAY FOR IS NOT REFUSED FOR ITS CEILING (2026-11-23, the maintainer's YouTube team:
 * "This request requires more credits, or fewer max_tokens. You requested up to 128000 tokens, but can only afford
 * 79672" - every call of the team and of the chat failed with 402 while the account still held credit).
 *
 * <p>OpenRouter reserves the price of the whole output ceiling before a request runs, and an output ceiling nobody set
 * is the model's own (ModelLimits), so a low balance refuses calls that would cost a fraction of it. Its 402 names the
 * ceiling the balance covers; the request is sent once more with {@link #SHARE} of it - the whole of it lost the race
 * to the call before, which spent some of the balance first ("requested up to 73449 tokens, but can only afford
 * 72181", the same morning). Only when that is at least {@link #FLOOR} tokens - below it the credit is spent for any
 * purpose, and the 402 stands for the page to say so. Anything else - a
 * 402 without that sentence, a body that is not JSON, a ceiling already as low - is passed on as it came.
 *
 * <p>OPENROUTER ONLY (ProviderProtocol, "native protocols first"): the sentence and the reservation are OpenRouter's.
 */
final class OpenRouterCreditCeiling {

    /** Below this many output tokens a call is not worth making: the credit is spent. */
    static final int FLOOR = 1024;
    /** The share of the affordable ceiling asked for, leaving room for what other calls spend meanwhile. */
    static final double SHARE = 0.9;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern AFFORD = Pattern.compile("can only afford (\\d{1,9})");

    private OpenRouterCreditCeiling() {}

    /**
     * The request body with its output ceiling lowered to what the refusal says the credit covers, or null when the
     * refusal names no such ceiling or lowering it would not help.
     */
    static byte[] lowered(byte[] body, byte[] refusal) {
        if (body == null || body.length == 0 || refusal == null) return null;
        Matcher m = AFFORD.matcher(new String(refusal, StandardCharsets.UTF_8));
        if (!m.find()) return null;
        int afford = (int) (Integer.parseInt(m.group(1)) * SHARE);
        if (afford < FLOOR) return null;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException notJson) {
            return null;
        }
        if (!(root instanceof ObjectNode request)) return null;
        // The field the request already uses; a request that set none asked for the model's own, so it gets one.
        String field = request.has("max_completion_tokens") ? "max_completion_tokens" : "max_tokens";
        JsonNode asked = request.get(field);
        if (asked != null && asked.canConvertToInt() && asked.asInt() <= afford) return null;
        request.put(field, afford);
        try {
            return JSON.writeValueAsBytes(request);
        } catch (IOException unwritable) {
            return null;
        }
    }

    /** For the RestClient behind a blocking call. It must be the LAST interceptor, since it executes twice. */
    static ClientHttpRequestInterceptor callInterceptor() {
        return (request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            if (response.getStatusCode().value() != 402) return response;
            byte[] refusal;
            try (InputStream in = response.getBody()) {
                refusal = in.readAllBytes();
            }
            byte[] lowered = lowered(body, refusal);
            if (lowered == null) return new Buffered(response, refusal);
            response.close();
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(request.getHeaders());
            headers.setContentLength(lowered.length);
            HttpRequest retry = new HttpRequestWrapper(request) {
                @Override
                public HttpHeaders getHeaders() {
                    return headers;
                }
            };
            return execution.execute(retry, lowered);
        };
    }

    /** For the WebClient behind a stream: the body is kept as it is written, and sent again lowered on a 402. */
    static ExchangeFilterFunction streamFilter() {
        return (request, next) -> {
            AtomicReference<byte[]> sent = new AtomicReference<>();
            ClientRequest recorded = ClientRequest.from(request)
                    .body((out, context) -> request.body().insert(new Recording(out, sent), context))
                    .build();
            return next.exchange(recorded).flatMap(response -> {
                if (response.statusCode().value() != 402) return Mono.just(response);
                return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(refusal -> {
                    byte[] lowered = lowered(sent.get(), refusal);
                    if (lowered == null) {
                        return Mono.just(response.mutate().body(new String(refusal, StandardCharsets.UTF_8)).build());
                    }
                    ClientRequest retry = ClientRequest.from(request)
                            .body((out, context) -> {
                                out.getHeaders().setContentLength(lowered.length);
                                return out.writeWith(Mono.just(out.bufferFactory().wrap(lowered)));
                            })
                            .build();
                    return next.exchange(retry);
                });
            });
        };
    }

    private static final class Recording extends ClientHttpRequestDecorator {

        private final AtomicReference<byte[]> sent;

        Recording(ClientHttpRequest delegate, AtomicReference<byte[]> sent) {
            super(delegate);
            this.sent = sent;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body).flatMap(joined -> {
                byte[] bytes = new byte[joined.readableByteCount()];
                joined.read(bytes);
                DataBufferUtils.release(joined);
                sent.set(bytes);
                return super.writeWith(Mono.just(bufferFactory().wrap(bytes)));
            });
        }
    }

    /** The refusal passed on with its body, which was read to look for the ceiling. */
    private static final class Buffered implements ClientHttpResponse {

        private final ClientHttpResponse original;
        private final byte[] body;

        Buffered(ClientHttpResponse original, byte[] body) {
            this.original = original;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return original.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return original.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return original.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            original.close();
        }
    }
}
