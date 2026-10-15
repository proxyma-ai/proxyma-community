package ai.proxyma.core.license;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Something the license does not include - since the two-tier model, Microsoft 365 without an
 * Enterprise license. A 403 with {@code error: not_licensed} and the sentence as the problem's
 * detail, wherever it is thrown from.
 *
 * <p>An ErrorResponseException so the one global handler that already passes those through
 * (GlobalExceptionHandler) answers it: the exception this replaced was mapped by two copy-pasted
 * controller handlers, and thrown anywhere else it became a 500 (guardrail-network-license-32).
 */
public class NotLicensedException extends ErrorResponseException {

    public NotLicensedException(String message) {
        super(HttpStatus.FORBIDDEN, problem(message), null);
    }

    private static ProblemDetail problem(String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, message);
        detail.setTitle("Not licensed");
        detail.setProperty("error", "not_licensed");
        return detail;
    }

    /** The sentence alone - a tool call reports it to the model as it is. */
    @Override
    public String getMessage() {
        return getBody().getDetail();
    }
}
