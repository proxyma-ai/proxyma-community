package ai.proxyma.core.provider.adapter;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

/**
 * Raises a vendor SDK's error status the way every other provider's is raised: as a
 * WebClientResponseException carrying the status, and the headers where the SDK kept them.
 *
 * <p>The Google GenAI and Azure OpenAI SDKs build their own HTTP clients and raise their own
 * exception types - Spring AI wraps Gemini's ApiException as {@code RuntimeException("Failed to
 * generate content")}, Azure's is an HttpResponseException - and ModelCall, which decides whether a
 * failure is a rate limit worth waiting out, reads neither. A Gemini 429 or 503, common on the free
 * tier, failed the turn at once; an Azure 429 failed it after the SDK's own three tries. The
 * translation is the adapter's, as AnthropicStreamErrors is for Anthropic's in-stream errors, so
 * ModelCall keeps reading one shape.
 */
final class SdkStatusChatModel implements ChatModel {

    private final ChatModel delegate;

    SdkStatusChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return delegate.call(prompt);
        } catch (RuntimeException e) {
            throw withStatus(e);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt).onErrorMap(SdkStatusChatModel::withStatus);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /** {@code e} itself, or the status its cause chain carries, raised as WebClient raises one. */
    static RuntimeException withStatus(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof WebClientResponseException already) return already;
            if (t instanceof com.google.genai.errors.ApiException api && api.code() >= 400) {
                return raised(e, api.code(), api.status(), HttpHeaders.EMPTY);
            }
            if (t instanceof com.azure.core.exception.HttpResponseException http && http.getResponse() != null) {
                HttpHeaders headers = new HttpHeaders();
                if (http.getResponse().getHeaders() != null) {
                    http.getResponse().getHeaders().stream()
                            .forEach(h -> headers.add(h.getName(), h.getValue()));
                }
                return raised(e, http.getResponse().getStatusCode(), null, headers);
            }
        }
        return e instanceof RuntimeException re ? re : new RuntimeException(e);
    }

    private static RuntimeException raised(Throwable original, int status, String statusText, HttpHeaders headers) {
        HttpStatus known = HttpStatus.resolve(status);
        String text = statusText != null ? statusText : known != null ? known.getReasonPhrase() : "";
        String message = original.getMessage() != null && original.getMessage().startsWith(String.valueOf(status))
                ? original.getMessage()
                : status + " " + text + " - " + messageOf(original);
        WebClientResponseException raised = new WebClientResponseException(message, status, text, headers,
                messageOf(original).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        raised.initCause(original);
        return raised;
    }

    // The innermost message - "Failed to generate content" says nothing; the SDK's own does.
    private static String messageOf(Throwable e) {
        String found = e.getMessage();
        for (Throwable t = e.getCause(); t != null && t != t.getCause(); t = t.getCause()) {
            if (t.getMessage() != null) found = t.getMessage();
        }
        return found == null ? e.getClass().getSimpleName() : found;
    }
}
