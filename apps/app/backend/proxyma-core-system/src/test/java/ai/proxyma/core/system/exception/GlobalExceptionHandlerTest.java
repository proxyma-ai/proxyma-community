package ai.proxyma.core.system.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-09: what an exception's message tells a client. A message is written for a log; an
 * absolute server path in one reached the browser as a 400, and any unexpected exception verbatim as
 * a 500 - SQL included.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("an absolute path in a 400's message leaves as its last segment only")
    void badRequestIsScrubbed() {
        ProblemDetail d = handler.handleBadRequest(new IllegalArgumentException(
                "File does not exist or is not readable: /data/users/u-17/uploads/merger-plan.pdf")).block();

        assertThat(d.getStatus()).isEqualTo(400);
        assertThat(d.getDetail()).doesNotContain("/data/users", "u-17").contains("merger-plan.pdf");
    }

    @Test
    @DisplayName("Windows paths, 404s, 503s and status exceptions are screened the same way")
    void everyDoorIsScrubbed() {
        assertThat(handler.handleNotFound(new NoSuchElementException("No file C:\\Users\\alice\\Documents\\x.docx"))
                .block().getDetail()).doesNotContain("alice");
        assertThat(handler.handleServiceUnavailable(new IllegalStateException("cannot open /home/alice/db.mv.db"))
                .block().getDetail()).doesNotContain("alice");
        ResponseEntity<ProblemDetail> r = handler.handleErrorResponse(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "denied for /var/lib/proxyma/keys/k.pem")).block();
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(r.getBody().getDetail()).doesNotContain("/var/lib").contains("k.pem");
    }

    @Test
    @DisplayName("a 500 says only that something failed, with the id that finds it in the log")
    void internalErrorIsOpaque() {
        ProblemDetail d = handler.handleGeneric(new RuntimeException(
                "ERROR: relation \"users\" does not exist at /opt/proxyma/app.jar")).block();

        assertThat(d.getStatus()).isEqualTo(500);
        assertThat(d.getDetail()).doesNotContain("relation", "users", "/opt");
        assertThat(d.getProperties()).containsKey("errorId");
        assertThat(d.getDetail()).contains((String) d.getProperties().get("errorId"));
    }
}
