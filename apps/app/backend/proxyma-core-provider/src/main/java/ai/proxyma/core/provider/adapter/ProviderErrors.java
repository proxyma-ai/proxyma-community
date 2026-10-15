package ai.proxyma.core.provider.adapter;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.UnknownHttpStatusCodeException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * How a BLOCKING provider call reports an error status: as Spring's own typed exception, carrying
 * the status, the response headers and the body.
 *
 * <p>Spring AI's default handler (RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER) raises a 4xx as
 * NonTransientAiException and a 5xx as TransientAiException, keeping the status only as text and
 * the headers not at all - so a blocking 429's Retry-After never reached ModelCall, which reads the
 * headers of an HttpStatusCodeException. Every Spring AI API builder here is given this instead
 * (OpenAiApi, AnthropicApi, OllamaApi). Streamed calls already raise WebClientResponseException,
 * which carries the same three things.
 */
final class ProviderErrors {

    private ProviderErrors() {}

    // An error body is read for the message and kept whole on the exception, up to this - a proxy's
    // HTML error page is not worth more.
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_MESSAGE_CHARS = 2000;

    static final ResponseErrorHandler HANDLER = new ResponseErrorHandler() {
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return response.getStatusCode().isError();
        }

        @Override
        public void handleError(ClientHttpResponse response) throws IOException {
            throw toException(response.getStatusCode(), response.getStatusText(), response.getHeaders(),
                    readCapped(response.getBody()));
        }
    };

    static RuntimeException toException(HttpStatusCode status, String statusText, HttpHeaders headers, byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.length() > MAX_MESSAGE_CHARS) text = text.substring(0, MAX_MESSAGE_CHARS) + "...";
        // The same leading "429 TOO_MANY_REQUESTS - {body}" shape Spring AI's handler used, so a
        // sentence built from the message reads as before.
        String message = status.value() + " " + (statusText == null ? "" : statusText) + " - " + text;
        if (status.is4xxClientError()) {
            return HttpClientErrorException.create(message, status, statusText, headers, body, StandardCharsets.UTF_8);
        }
        if (status.is5xxServerError()) {
            return HttpServerErrorException.create(message, status, statusText, headers, body, StandardCharsets.UTF_8);
        }
        return new UnknownHttpStatusCodeException(message, status.value(), statusText, headers, body, StandardCharsets.UTF_8);
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        try (in) {
            return in.readNBytes(MAX_BODY_BYTES);
        }
    }
}
