package ai.proxyma.connectors.box;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ResolvedCredentials;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * How every request to Box is made: the connection's API address ({@code https://api.box.com/2.0},
 * or a test server under More options) and the sign-in's access token as a bearer, on that host
 * alone. A file's content is a 302 to {@code dl.boxcloud.com}, which ApiClient follows WITHOUT the
 * token - the download address is signed for the one file and needs none.
 *
 * <p><b>Rate limits</b>: about 1,000 requests a minute per user; a 429 carries {@code Retry-After},
 * which the standard hint waits out.
 */
final class BoxApi {

    static final String DEFAULT_BASE_URL = "https://api.box.com/2.0";
    // "All Files" - the root every Box account's folders hang from.
    static final String ALL_FILES = "0";

    private BoxApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl() : DEFAULT_BASE_URL;
        return http.client(base)
                .service("Box")
                .auth(ApiAuth.bearer(connection))
                .build();
    }

    static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(iso);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
