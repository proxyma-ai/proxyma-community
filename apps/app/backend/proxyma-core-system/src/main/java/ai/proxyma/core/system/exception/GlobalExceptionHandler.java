package ai.proxyma.core.system.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import ai.proxyma.core.system.util.SensitivePaths;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.UUID;

// EVERY DETAIL IS SCREENED ON ITS WAY OUT (platform-09). Each handler copied ex.getMessage() into the
// response, and a message is written for a log, not a browser: FileTypeSupport's "File does not
// exist: <absolute path>" reached the client as a 400, and any unexpected exception - SQL, a missing
// file - verbatim as a 500. A detail now passes SensitivePaths.scrub, the boundary rule for anything
// crossing to a client, and a 500 says only that something failed, with an id to find it in the log.
//
// IllegalArgumentException and IllegalStateException are logged too: they are also what a
// programming error throws, and as an unlogged 400 or 503 one never reached a log or the
// diagnostics counters at all.
//
// NOTHING IS WRITTEN ONTO A RESPONSE THAT IS ALREADY UNDER WAY (feedback on 0.36.0). An error raised
// after the first byte went out - the page's event stream when the server stops (Tomcat times out
// every open async request as it shuts down: "Async operation timeout."), any response whose client
// went away mid-write - still reached these handlers, and each tried to send a problem body: the
// status and content type of a committed response are read-only, so every one ended in
// UnsupportedOperationException, "ServerHttpResponse already committed" and a servlet stack trace,
// and a client that simply closed its tab was logged as an "Unhandled exception". Such a response
// is left as it is: the handler answers with nothing, and the exchange ends.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static String scrub(String message) {
        return SensitivePaths.scrub(message);
    }

    // Whether this exchange can still be answered with a status and a body. Null for a direct call
    // (the unit tests), which always can.
    static boolean committed(ServerWebExchange exchange) {
        return exchange != null && exchange.getResponse().isCommitted();
    }

    // The client went away while its response was being written - an I/O failure on the socket,
    // anywhere in the cause chain. Nothing about the server is wrong.
    static boolean clientGone(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof IOException) return true;
        }
        return false;
    }

    // The body to send, or nothing when the response is already under way - see the class comment.
    private static <T> Mono<T> answer(ServerWebExchange exchange, T body) {
        return committed(exchange) ? Mono.empty() : Mono.just(body);
    }

    // Must come before the generic Exception.class catch-all below: without this, any
    // ResponseStatusException/ErrorResponseException thrown anywhere (e.g. a legitimate 404) was
    // being matched by the generic handler instead (Exception.class matches everything with no
    // more specific handler registered) and flattened into an opaque 500 "Internal error" with the
    // original status buried in the detail string - confusing for any client that branches on the
    // actual HTTP status, including MCP clients. Preserve the real status/body these already carry.
    @ExceptionHandler({ResponseStatusException.class, ErrorResponseException.class})
    public Mono<ResponseEntity<ProblemDetail>> handleErrorResponse(ErrorResponse ex, ServerWebExchange exchange) {
        ProblemDetail body = ex.getBody();
        if (body.getDetail() != null) body.setDetail(scrub(body.getDetail()));
        return answer(exchange, ResponseEntity.status(ex.getStatusCode()).body(body));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ProblemDetail> handleValidation(WebExchangeBindException ex, ServerWebExchange exchange) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Validation failed");
        detail.setDetail(scrub(ex.getAllErrors().stream()
                .map(Object::toString)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "; " + b)));
        return answer(exchange, detail);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public Mono<ProblemDetail> handleNotFound(NoSuchElementException ex, ServerWebExchange exchange) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Not found");
        detail.setDetail(scrub(ex.getMessage()));
        return answer(exchange, detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ProblemDetail> handleBadRequest(IllegalArgumentException ex, ServerWebExchange exchange) {
        log.warn("Bad request: {}", ex.toString());
        log.debug("Bad request", ex);
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Bad request");
        detail.setDetail(scrub(ex.getMessage()));
        return answer(exchange, detail);
    }

    // A spending limit reached - the deployment's monthly limit, or a user's own quota for a service
    // charged to them - on a path outside the chat page (MCP, the REST search API, a workflow run).
    // 429 with the sentence that says which limit and when it resets, instead of the 500 "Internal
    // error" these reached until the limits moved into the gateway on 2026-09-29.
    @ExceptionHandler({ai.proxyma.core.settings.usage.GlobalQuotaExceededException.class,
            ai.proxyma.core.settings.usage.UserBudgetExceededException.class})
    public Mono<ProblemDetail> handleLimitReached(RuntimeException ex, ServerWebExchange exchange) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setTitle("Spending limit reached");
        detail.setDetail(scrub(ex.getMessage()));
        return answer(exchange, detail);
    }

    @ExceptionHandler(IllegalStateException.class)
    public Mono<ProblemDetail> handleServiceUnavailable(IllegalStateException ex, ServerWebExchange exchange) {
        if (committed(exchange)) {
            // An open request ended under way - on a stop, Tomcat times out every async request
            // still open, and the page's event stream always is one.
            log.debug("Response already under way ended: {}", ex.toString());
            return Mono.empty();
        }
        log.warn("Service unavailable: {}", ex.toString());
        log.debug("Service unavailable", ex);
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        detail.setTitle("Service unavailable");
        detail.setDetail(scrub(ex.getMessage()));
        return answer(exchange, detail);
    }

    @ExceptionHandler(Exception.class)
    public Mono<ProblemDetail> handleGeneric(Exception ex, ServerWebExchange exchange) {
        if (committed(exchange) && clientGone(ex)) {
            // The client closed the connection while its response was being written - a tab
            // closed or reloaded, the page reconnecting its event stream. Not a server error.
            log.debug("Client went away mid-response: {}", ex.toString());
            return Mono.empty();
        }
        // Every other handler above maps a known, expected exception type to a specific status -
        // this catch-all is by definition the "something we didn't anticipate" path, so it's the
        // one place server-side logging is non-negotiable. Without this, an unhandled exception
        // left zero trace anywhere (client saw a bare 500, container stdout showed nothing at
        // all) - the only way to diagnose it was reading ex.getMessage() back out of the HTTP
        // response body itself.
        //
        // The client gets an id for the log line, not the message: an unanticipated exception's
        // message is whatever its library wrote, SQL and file names included.
        String errorId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled exception (error {})", errorId, ex);
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        detail.setTitle("Internal error");
        detail.setDetail("Something went wrong on the server (error " + errorId + ").");
        detail.setProperty("errorId", errorId);
        return answer(exchange, detail);
    }
}
