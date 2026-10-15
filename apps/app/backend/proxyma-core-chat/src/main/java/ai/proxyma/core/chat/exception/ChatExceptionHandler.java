package ai.proxyma.core.chat.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.net.URI;
import java.util.NoSuchElementException;

@RestControllerAdvice(basePackages = "ai.proxyma.core.chat")
public class ChatExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatExceptionHandler.class);

    @ExceptionHandler(WebExchangeBindException.class)
    public ProblemDetail handleValidation(WebExchangeBindException ex) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setType(URI.create("about:blank"));
        p.setTitle("Validation failed");
        p.setDetail(ex.getAllErrors().stream().map(e -> e.getDefaultMessage()).findFirst().orElse("Invalid request"));
        return p;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        p.setType(URI.create("about:blank"));
        p.setTitle("Not found");
        p.setDetail(scrub(ex.getMessage()));
        return p;
    }

    // Added alongside the chat attachment upload endpoint (ChatAttachmentController), whose
    // 25MB-cap check throws this - without a handler here, it would fall through to the generic
    // Exception.class catch-all below and come back as a misleading 500 instead of 400. Mirrors
    // GlobalExceptionHandler's (proxyma-core-system) identical IllegalArgumentException -> 400
    // mapping, which this module's own narrower @RestControllerAdvice scope otherwise shadows.
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setType(URI.create("about:blank"));
        p.setTitle("Bad request");
        p.setDetail(scrub(ex.getMessage()));
        return p;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleProviderUnavailable(IllegalStateException ex) {
        log.warn("[Chat] provider unavailable: {}", ex.getMessage());
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        p.setType(URI.create("about:blank"));
        p.setTitle("AI provider unavailable");
        p.setDetail(scrub(ex.getMessage()));
        return p;
    }

    // Without this, ChatQuotaExceededException (also in ai.proxyma.core.chat, so this
    // package-scoped advice - not GlobalExceptionHandler's ErrorResponseException passthrough -
    // is the one Spring resolves it against) fell through to the generic Exception.class handler
    // below and came back as a misleading 500 instead of the real 429 + quota message.
    @ExceptionHandler(ChatQuotaExceededException.class)
    public ProblemDetail handleQuotaExceeded(ChatQuotaExceededException ex) {
        return ex.getBody();
    }

    // The window or tab went away mid-stream (closed, crashed, slept). Not an error, and nothing can
    // be written back: the event stream has already sent its 200, so a ProblemDetail here failed with
    // UnsupportedOperationException and logged ~150 lines per disconnect (2026-09-29 feedback log).
    // Only once the response is COMMITTED: an I/O failure before that is a real error and gets the
    // general handling below.
    @ExceptionHandler(java.io.IOException.class)
    public reactor.core.publisher.Mono<org.springframework.http.ResponseEntity<ProblemDetail>> handleClientGone(
            java.io.IOException ex, org.springframework.web.server.ServerWebExchange exchange) {
        if (exchange.getResponse().isCommitted()) {
            log.debug("[Chat] client disconnected: {}", ex.getMessage());
            return reactor.core.publisher.Mono.empty();
        }
        return reactor.core.publisher.Mono.just(
                org.springframework.http.ResponseEntity.internalServerError().body(handleGeneral(ex)));
    }

    // An exception's message is written for the log, and can name a server path (an
    // InvalidPathException's does); what crosses to the browser is screened (SensitivePaths).
    private static String scrub(String message) {
        return message == null ? null : ai.proxyma.core.system.util.SensitivePaths.scrub(message);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex) {
        log.error("[Chat] unexpected error: {}", ex.getMessage(), ex);
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        p.setType(URI.create("about:blank"));
        p.setTitle("Internal server error");
        p.setDetail("The chat encountered an error. Please try again.");
        return p;
    }
}
