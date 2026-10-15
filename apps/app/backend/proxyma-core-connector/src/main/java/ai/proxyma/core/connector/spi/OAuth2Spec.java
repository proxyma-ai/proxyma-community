package ai.proxyma.core.connector.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a service signs in with OAuth2 - the part of an authorization-code or client-credentials
 * flow that differs between vendors. The flow itself (PKCE, state, the token exchange, refresh
 * with rotation) is the framework's, in OAuth2ConnectionService.
 *
 * <p>There is no list of other hosts the access token may go to: a token is sent where the
 * connection's ApiClient is pinned, and a connector whose API lives on a host the answer names
 * (Salesforce's {@code instance_url}) checks that host itself and builds its client there.
 *
 * @param authorizeUrl         where the browser is sent to sign in
 * @param tokenUrl             where the code and the refresh token are exchanged
 * @param scopes               always requested
 * @param writeScopes          requested in addition only while the connection's Write switch is on
 * @param extraAuthorizeParams vendor parameters on the authorize URL ({@code access_type=offline},
 *                             {@code prompt=consent}, {@code audience}, ...)
 * @param clientAuth           how the client authenticates at the token endpoint
 * @param tokenBody            how the token request's parameters are written: a form, as RFC 6749
 *                             says, or a JSON object where the vendor documents only that (Notion)
 * @param tokenHeaders         headers every token request carries - a vendor that versions its
 *                             token endpoint with the rest of its API (Notion-Version)
 * @param clientGrantParams    for a client-credentials grant, token parameters taken from the
 *                             connection's settings: token parameter name to setting key. Box's
 *                             grant names whose token it is ({@code box_subject_type},
 *                             {@code box_subject_id}) and is refused without them
 * @param clientGrantScopes    whether a client-credentials grant asks for {@code scopes}, as RFC 6749
 *                             allows; Box documents no scope on that grant (only on token exchange)
 * @param grantFields          fields of the token answer a session needs later and that are not
 *                             tokens - Salesforce's {@code instance_url}, the org the API is spoken
 *                             at. Kept on the connection beside its grant (OAuth2ConnectionService)
 *                             and handed to a session as {@link ResolvedCredentials#grant(String)};
 *                             a field not named here is never kept
 */
public record OAuth2Spec(String authorizeUrl, String tokenUrl, List<String> scopes, List<String> writeScopes,
                         Map<String, String> extraAuthorizeParams, ClientAuth clientAuth, TokenBody tokenBody,
                         Map<String, String> tokenHeaders, Map<String, String> clientGrantParams,
                         boolean clientGrantScopes, List<String> grantFields) {

    public OAuth2Spec {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        writeScopes = writeScopes == null ? List.of() : List.copyOf(writeScopes);
        extraAuthorizeParams = extraAuthorizeParams == null ? Map.of() : Map.copyOf(extraAuthorizeParams);
        if (clientAuth == null) clientAuth = ClientAuth.POST;
        if (tokenBody == null) tokenBody = TokenBody.FORM;
        tokenHeaders = ordered(tokenHeaders);
        clientGrantParams = ordered(clientGrantParams);
        grantFields = grantFields == null ? List.of() : List.copyOf(grantFields);
    }

    // In the order they were declared, so a request reads as its vendor documents it and a missing
    // setting is named the same way every time.
    private static Map<String, String> ordered(Map<String, String> values) {
        return values == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static OAuth2Spec of(String authorizeUrl, String tokenUrl, List<String> scopes) {
        return builder(authorizeUrl, tokenUrl).scopes(scopes).build();
    }

    public static Builder builder(String authorizeUrl, String tokenUrl) {
        return new Builder(authorizeUrl, tokenUrl);
    }

    /** This spec at other addresses - the same service signing in at an account's or an org's own host. */
    public OAuth2Spec at(String authorize, String token) {
        return new OAuth2Spec(authorize, token, scopes, writeScopes, extraAuthorizeParams, clientAuth, tokenBody,
                tokenHeaders, clientGrantParams, clientGrantScopes, grantFields);
    }

    /** {@code client_id}/{@code client_secret} in the token request's body, or as HTTP Basic. */
    public enum ClientAuth { POST, BASIC }

    /** How the token request's parameters are written. */
    public enum TokenBody {
        /** {@code application/x-www-form-urlencoded}, RFC 6749's own - what nearly every service takes. */
        FORM,
        /** A JSON object, for a service that documents nothing else (Notion). */
        JSON
    }

    public static final class Builder {
        private final String authorizeUrl;
        private final String tokenUrl;
        private List<String> scopes = List.of();
        private List<String> writeScopes = List.of();
        private final Map<String, String> authorizeParams = new LinkedHashMap<>();
        private ClientAuth clientAuth = ClientAuth.POST;
        private TokenBody tokenBody = TokenBody.FORM;
        private final Map<String, String> tokenHeaders = new LinkedHashMap<>();
        private final Map<String, String> clientGrantParams = new LinkedHashMap<>();
        private boolean clientGrantScopes = true;
        private final List<String> grantFields = new ArrayList<>();

        private Builder(String authorizeUrl, String tokenUrl) {
            this.authorizeUrl = authorizeUrl;
            this.tokenUrl = tokenUrl;
        }

        public Builder scopes(List<String> value) { this.scopes = value; return this; }
        public Builder writeScopes(List<String> value) { this.writeScopes = value; return this; }
        public Builder authorizeParam(String name, String value) { this.authorizeParams.put(name, value); return this; }
        public Builder clientAuth(ClientAuth value) { this.clientAuth = value; return this; }
        public Builder tokenBody(TokenBody value) { this.tokenBody = value; return this; }
        public Builder tokenHeader(String name, String value) { this.tokenHeaders.put(name, value); return this; }

        /** A client-credentials token parameter taken from the connection setting {@code settingKey}. */
        public Builder clientGrantParam(String param, String settingKey) {
            this.clientGrantParams.put(param, settingKey);
            return this;
        }

        /** A client-credentials grant that sends no {@code scope} (Box). */
        public Builder noClientGrantScopes() { this.clientGrantScopes = false; return this; }

        /** A field of the token answer kept on the connection for its sessions. */
        public Builder keepGrantField(String field) { this.grantFields.add(field); return this; }

        public OAuth2Spec build() {
            return new OAuth2Spec(authorizeUrl, tokenUrl, scopes, writeScopes, authorizeParams, clientAuth, tokenBody,
                    tokenHeaders, clientGrantParams, clientGrantScopes, grantFields);
        }
    }
}
