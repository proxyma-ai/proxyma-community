package ai.proxyma.core.provider.adapter;

import org.springframework.core.ResolvableType;
import org.springframework.core.codec.StringDecoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Renames the {@code reasoning} field of an OpenAI-compatible stream to {@code reasoning_content},
 * the one name Spring AI 1.1.7 reads.
 *
 * <p>Two names for one thing, and the newer one is the common one: vLLM renamed
 * {@code reasoning_content} to {@code reasoning} ("used to be called reasoning_content"), and
 * Ollama's /v1 endpoint and LM Studio send {@code reasoning} too, while DeepSeek and llama.cpp
 * still send {@code reasoning_content}. Spring AI's message record declares only the old name and
 * ignores unknown properties, so on every server using the new one a reasoning model's thinking
 * was silently discarded - the user watched a spinner for as long as the model thought, with
 * nothing to say it was thinking at all. See ModelThinking for where the thinking goes next.
 *
 * <p>Line by line over the SSE body, and only a KEY: an unescaped {@code "reasoning":} can only be
 * a property name, since a quote inside a JSON string value is escaped. The value must be a string
 * or null - {@code reasoning_details} and any object-valued {@code reasoning} are left alone, so a
 * shape Spring AI cannot bind is never manufactured. A line that already carries
 * {@code reasoning_content} is left alone too, rather than producing a duplicate key.
 */
final class ReasoningFieldAlias {

    private static final Pattern KEY = Pattern.compile("\"reasoning\"(\\s*:\\s*)(?=\"|null)");

    private ReasoningFieldAlias() {}

    /** One SSE line, with a string-valued {@code reasoning} key renamed. */
    static String rewrite(String line) {
        if (line == null || !line.contains("\"reasoning\"") || line.contains("\"reasoning_content\"")) return line;
        return KEY.matcher(line).replaceAll("\"reasoning_content\"$1");
    }

    /** Applies {@link #rewrite} to every line of an event-stream response; anything else passes through. */
    static ExchangeFilterFunction filter() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            boolean eventStream = response.headers().contentType()
                    .map(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                    .orElse(false);
            if (!eventStream) return Mono.just(response);
            return Mono.just(response.mutate().body(ReasoningFieldAlias::rewriteLines).build());
        });
    }

    private static Flux<DataBuffer> rewriteLines(Flux<DataBuffer> body) {
        DefaultDataBufferFactory buffers = DefaultDataBufferFactory.sharedInstance;
        return StringDecoder.allMimeTypes(List.of("\n"), true)
                .decode(body, ResolvableType.forClass(String.class), null, null)
                .map(line -> buffers.wrap((rewrite(line) + "\n").getBytes(StandardCharsets.UTF_8)));
    }
}
