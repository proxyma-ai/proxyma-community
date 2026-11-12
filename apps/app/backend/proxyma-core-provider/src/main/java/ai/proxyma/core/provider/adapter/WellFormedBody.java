package ai.proxyma.core.provider.adapter;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;

/**
 * NO REQUEST LEAVES WITH HALF AN EMOJI IN IT (2026-11-06).
 *
 * <p>A string holding a lone UTF-16 surrogate - half an emoji, left by a cut at a character index - is
 * written by Jackson as a single six-character hex escape with no partner. Java's parsers read that back;
 * Anthropic's refuses the whole request: "400 ... The request body is not valid JSON: unexpected end of
 * hex escape at line 1 column N". A Constellations team on a Vietnamese channel failed every scheduled run
 * that way. The cuts that produced it now keep pairs whole ({@code Utf16.cut}); this is the boundary that
 * holds whatever cut a future change adds.
 *
 * <p>It reads the body's strings escape by escape, as a JSON parser does - a backslash always starts a
 * two-character escape or a six-character hex one, so an escaped backslash is never mistaken for the start
 * of a hex escape - and replaces an unpaired surrogate escape with the escape of U+FFFD, which is what any
 * UTF-8 encoder writes for one. A paired one, and every other byte, is copied as it was. A surrogate written
 * raw (three UTF-8 bytes, which is not UTF-8) becomes U+FFFD's bytes too. A parse and a rewrite would not
 * do: Jackson's own parser refuses a lone surrogate in a field name.
 *
 * <p>A body with nothing to repair is passed on as the same bytes.
 */
final class WellFormedBody {

    private static final byte[] REPLACEMENT_ESCAPE = {'\\', 'u', 'F', 'F', 'F', 'D'};
    private static final byte[] REPLACEMENT_UTF8 = {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD};

    private WellFormedBody() {}

    /** The body with every unpaired surrogate replaced by U+FFFD, or the same bytes when there is none. */
    static byte[] repair(byte[] body) {
        if (body == null || !needsRepair(body)) return body;
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 16);
        scan(body, out);
        return out.toByteArray();
    }

    static boolean needsRepair(byte[] body) {
        return scan(body, null);
    }

    // One pass over the body: true when something needed repair; with an output, the repaired body written to
    // it. Malformed JSON is copied as it is from the point it stops making sense.
    private static boolean scan(byte[] b, ByteArrayOutputStream out) {
        boolean repaired = false;
        boolean inString = false;
        int i = 0;
        while (i < b.length) {
            byte c = b[i];
            if (c == (byte) 0xED && i + 2 < b.length && (b[i + 1] & 0xE0) == 0xA0) {
                // A surrogate encoded as UTF-8 bytes (ED A0..BF xx): not UTF-8 at all.
                if (out == null) return true;
                out.write(REPLACEMENT_UTF8, 0, 3);
                repaired = true;
                i += 3;
                continue;
            }
            if (!inString) {
                if (c == '"') inString = true;
                if (out != null) out.write(c);
                i++;
                continue;
            }
            if (c == '"') {
                inString = false;
                if (out != null) out.write(c);
                i++;
                continue;
            }
            if (c != '\\' || i + 1 >= b.length) {
                if (out != null) out.write(c);
                i++;
                continue;
            }
            if (b[i + 1] != 'u') {
                if (out != null) out.write(b, i, 2);
                i += 2;
                continue;
            }
            int unit = hex(b, i + 2);
            if (unit < 0) {
                // Not a hex escape a parser would take; leave the rest alone.
                if (out != null) out.write(b, i, b.length - i);
                return repaired;
            }
            if (unit >= 0xD800 && unit <= 0xDBFF) {
                int next = i + 6 + 1 < b.length && b[i + 6] == '\\' && b[i + 7] == 'u' ? hex(b, i + 8) : -1;
                if (next >= 0xDC00 && next <= 0xDFFF) {
                    if (out != null) out.write(b, i, 12);
                    i += 12;
                    continue;
                }
            }
            if (unit >= 0xD800 && unit <= 0xDFFF) {
                // A leading surrogate with no trailing one after it, or a trailing one with none before.
                if (out == null) return true;
                out.write(REPLACEMENT_ESCAPE, 0, REPLACEMENT_ESCAPE.length);
                repaired = true;
            } else if (out != null) {
                out.write(b, i, 6);
            }
            i += 6;
        }
        return repaired;
    }

    // The value of the four hex digits at this index, or -1 when there are not four.
    private static int hex(byte[] b, int at) {
        if (at + 4 > b.length) return -1;
        int v = 0;
        for (int k = at; k < at + 4; k++) {
            int d = Character.digit((char) b[k], 16);
            if (d < 0 || b[k] < 0) return -1;
            v = v * 16 + d;
        }
        return v;
    }

    /** For the RestClient behind a blocking call. */
    static ClientHttpRequestInterceptor callInterceptor() {
        return (request, body, execution) -> execution.execute(request, repair(body));
    }

    /** For the WebClient behind a stream: the body is joined, repaired and written once. */
    static ExchangeFilterFunction streamFilter() {
        return (request, next) -> next.exchange(ClientRequest.from(request)
                .body((outputMessage, context) -> request.body().insert(new Repairing(outputMessage), context))
                .build());
    }

    private static final class Repairing extends ClientHttpRequestDecorator {

        Repairing(ClientHttpRequest delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body).flatMap(joined -> {
                byte[] bytes = new byte[joined.readableByteCount()];
                joined.read(bytes);
                DataBufferUtils.release(joined);
                byte[] repaired = repair(bytes);
                HttpHeaders headers = getHeaders();
                if (repaired != bytes && headers.getContentLength() >= 0) headers.setContentLength(repaired.length);
                return super.writeWith(Mono.just(bufferFactory().wrap(repaired)));
            });
        }
    }
}
