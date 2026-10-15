package ai.proxyma.core.connector.spi;

import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * How an {@link ApiClient} proves who is asking, applied to every request it sends to its own
 * server - and to NO other: a redirect to another host is followed without it, and a URL on another
 * host is refused outright (see ApiClient). Built from the session's {@link ResolvedCredentials}, so
 * a sign-in's token is asked for on every request and refreshed once when the service refuses it.
 */
public abstract class ApiAuth {

    /** The header this auth writes - stripped from any request leaving the pinned host. */
    public abstract String headerName();

    abstract String headerValue();

    /** Whether a 401 can be cured by asking for a fresh token once. */
    boolean refreshable() {
        return false;
    }

    void invalidate() {}

    void apply(HttpHeaders headers) {
        String value = headerValue();
        if (value != null) headers.set(headerName(), value);
    }

    /** No credential at all - a public API. */
    public static ApiAuth none() {
        return new ApiAuth() {
            @Override public String headerName() { return HttpHeaders.AUTHORIZATION; }
            @Override String headerValue() { return null; }
        };
    }

    /** {@code Authorization: Bearer <token>}, the token read from the connection on every request. */
    public static ApiAuth bearer(ResolvedCredentials credentials) {
        return scheme("Bearer", credentials);
    }

    /** {@code Authorization: <scheme> <token>} - "token" for some older APIs, "Bearer" for most. */
    public static ApiAuth scheme(String scheme, ResolvedCredentials credentials) {
        return new ApiAuth() {
            @Override public String headerName() { return HttpHeaders.AUTHORIZATION; }
            @Override String headerValue() { return scheme + " " + credentials.accessToken(); }
            @Override boolean refreshable() { return credentials.signsIn(); }
            @Override void invalidate() { credentials.invalidateToken(); }
        };
    }

    /** A fixed bearer token - tests and token endpoints. */
    public static ApiAuth bearer(String token) {
        return header(HttpHeaders.AUTHORIZATION, () -> "Bearer " + token);
    }

    /** Any header, its value read on every request ({@code X-Api-Key}, {@code PRIVATE-TOKEN}). */
    public static ApiAuth header(String name, Supplier<String> value) {
        return new ApiAuth() {
            @Override public String headerName() { return name; }
            @Override String headerValue() { return value.get(); }
        };
    }

    /** HTTP Basic - a user name and a password, or an email and an API token. */
    public static ApiAuth basic(String username, String password) {
        String encoded = Base64.getEncoder().encodeToString(
                ((username == null ? "" : username) + ":" + (password == null ? "" : password))
                        .getBytes(StandardCharsets.UTF_8));
        return header(HttpHeaders.AUTHORIZATION, () -> "Basic " + encoded);
    }
}
