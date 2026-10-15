package ai.proxyma.core.registry.files.upload.controller;

import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.registry.files.upload.service.FileService;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.system.activity.RequestUserIdAttribute;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whose uploads a request reads: a session's subject, an API token's account, the local user where
 * there are no accounts - and a 401 where there are accounts and the request names none. An API token
 * was refused every upload endpoint, as a 503, because only a session's claims were read.
 */
class FileOwnerTest {

    private final FileService files = mock(FileService.class);
    private final FileSourceMode mode = mock(FileSourceMode.class);
    private final FileController controller = new FileController(files, mock(ConnectorService.class), null, mode);

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/files"));
    }

    @Test
    @DisplayName("a session reads its own uploads")
    void sessionSubject() {
        when(mode.uploadsAreAttributed()).thenReturn(true);
        MockServerWebExchange ex = exchange();
        ex.getAttributes().put("PROXYMA_USER_CLAIMS", new JWTClaimsSet.Builder().subject("u-session").build());
        when(files.listByUser("u-session")).thenReturn(List.of());

        assertThat(controller.list(ex).block().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(files).listByUser("u-session");
    }

    @Test
    @DisplayName("an API token reads its account's uploads")
    void apiTokenAccount() {
        when(mode.uploadsAreAttributed()).thenReturn(true);
        MockServerWebExchange ex = exchange();
        ex.getAttributes().put(RequestUserIdAttribute.KEY, "u-token");
        when(files.listByUser("u-token")).thenReturn(List.of());

        assertThat(controller.list(ex).block().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(files).listByUser("u-token");
    }

    @Test
    @DisplayName("with accounts, a request naming none is a 401, not a 503")
    void anonymousIs401() {
        when(mode.uploadsAreAttributed()).thenReturn(true);

        assertThatThrownBy(() -> controller.list(exchange()).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("without accounts, the local user is the null owner")
    void localUser() {
        when(mode.uploadsAreAttributed()).thenReturn(false);
        when(files.listByUser(null)).thenReturn(List.of());

        assertThat(controller.list(exchange()).block().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(files).listByUser(null);
    }
}
