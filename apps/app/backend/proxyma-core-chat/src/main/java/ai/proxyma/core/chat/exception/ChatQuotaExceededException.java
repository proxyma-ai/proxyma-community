package ai.proxyma.core.chat.exception;

import ai.proxyma.core.chat.quota.QuotaRejection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

// Thrown from ChatController.chat() when a ChatQuotaGate rejects a turn before any LLM call
// happens. Extends ErrorResponseException so GlobalExceptionHandler's existing
// @ExceptionHandler({ResponseStatusException.class, ErrorResponseException.class}) passthrough
// (proxyma-core-system/.../exception/GlobalExceptionHandler.java) picks this up for free
// as a 429 with no new handler code needed there - same idiomatic status ApiRateLimitFilter
// already uses for HTTP-rate 429s (proxyma-core-network/.../ApiRateLimitFilter.java).
public class ChatQuotaExceededException extends ErrorResponseException {
    public ChatQuotaExceededException(QuotaRejection rejection) {
        super(HttpStatus.TOO_MANY_REQUESTS, problemDetail(rejection), null);
    }

    private static ProblemDetail problemDetail(QuotaRejection rejection) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, rejection.message());
        detail.setTitle("Quota exceeded");
        detail.setProperty("window", rejection.window());
        detail.setProperty("resetsAt", rejection.resetsAt().toString());
        return detail;
    }
}
