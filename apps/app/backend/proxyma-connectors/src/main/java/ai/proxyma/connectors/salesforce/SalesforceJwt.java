package ai.proxyma.connectors.salesforce;

import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.TokenSupplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Salesforce's OAuth 2.0 JWT bearer flow - the service-account way in, for an integration user with
 * no browser: an assertion signed with the private key whose certificate is uploaded to the org's
 * External Client App, exchanged at the login server for an access token and the org's
 * {@code instance_url}. Nobody signs in and there is no refresh token; a new assertion is the
 * refresh.
 *
 * <ul>
 *   <li>{@code iss} is the app's consumer key, {@code sub} the integration user's user name,
 *       {@code aud} the login server - {@code https://login.salesforce.com}, or
 *       {@code https://test.salesforce.com} for a sandbox, told from the connection's address, or
 *       whatever the connection's Audience says - and {@code exp} three minutes away, as Salesforce
 *       requires;</li>
 *   <li>the token is asked for at the connection's address (the org's My Domain URL, or the login
 *       server itself) and kept in memory for {@link #LIFETIME} - Salesforce does not say how long it
 *       lasts, and the shortest session timeout an org can set is 15 minutes. A refused token is
 *       forgotten and asked for again once;</li>
 *   <li>the API is then spoken at the {@code instance_url} the answer named - but only when that is
 *       a Salesforce host or the connection's own, since the token goes there.</li>
 * </ul>
 */
final class SalesforceJwt implements TokenSupplier {

    static final String GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    static final String TOKEN_PATH = "services/oauth2/token";
    static final String LOGIN = "https://login.salesforce.com";
    static final String TEST = "https://test.salesforce.com";
    static final Duration LIFETIME = Duration.ofMinutes(10);
    static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(3);
    // Where an org's API may live: the hosts a token may be sent to besides the connection's own.
    static final List<String> SALESFORCE_DOMAINS = List.of(".salesforce.com", ".force.com", ".salesforce.mil",
            ".cloudforce.com", ".sfcrmproducts.cn");

    static final String CONSUMER_KEY = "consumerKey";
    static final String PRIVATE_KEY = "privateKey";
    static final String AUDIENCE = "audience";

    private static final ObjectMapper JSON = new ObjectMapper();
    // Tokens by connection and key, shared by the sessions of one connection: a fetch batch opens a
    // session of its own, and should not cost an assertion each.
    private static final Map<String, Grant> GRANTS = new ConcurrentHashMap<>();

    record Grant(String accessToken, String instanceUrl, Instant expires) {}

    private final ConnectorHttp http;
    private final String loginUrl;
    private final String consumerKey;
    private final String username;
    private final String privateKeyPem;
    private final String audience;
    private final String cacheKey;
    private final Clock clock;

    SalesforceJwt(ConnectorHttp http, ResolvedCredentials connection, Clock clock) {
        this.http = http;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.loginUrl = connection.hasBaseUrl() ? connection.baseUrl().strip().replaceAll("/+$", "") : LOGIN;
        this.consumerKey = trim(connection.setting(CONSUMER_KEY));
        this.username = trim(connection.username());
        String pem = connection.secret(PRIVATE_KEY);
        this.privateKeyPem = pem == null ? connection.soleSecret() : pem;
        String aud = trim(connection.setting(AUDIENCE));
        this.audience = aud == null ? audienceFor(loginUrl) : aud.replaceAll("/+$", "");
        this.cacheKey = sha256(loginUrl + "\n" + consumerKey + "\n" + username + "\n" + audience + "\n" + privateKeyPem);
    }

    /** The login server a connection's address signs in at: the sandbox one for a sandbox's address. */
    static String audienceFor(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        boolean sandbox = host.equals("test.salesforce.com") || host.endsWith(".sandbox.my.salesforce.com")
                || host.contains("--");
        return sandbox ? TEST : LOGIN;
    }

    @Override
    public String accessToken() {
        return grant().accessToken();
    }

    @Override
    public void invalidate() {
        GRANTS.remove(cacheKey);
    }

    /** The org's API address, from the token answer. */
    String instanceUrl() {
        return grant().instanceUrl();
    }

    private Grant grant() {
        Grant g = GRANTS.get(cacheKey);
        if (g != null && clock.instant().isBefore(g.expires())) return g;
        synchronized (GRANTS) {
            g = GRANTS.get(cacheKey);
            if (g != null && clock.instant().isBefore(g.expires())) return g;
            g = exchange();
            GRANTS.put(cacheKey, g);
            return g;
        }
    }

    private Grant exchange() {
        if (consumerKey == null) {
            throw new ConnectorAuthException("This connection has no consumer key. Add the External Client App's in Settings > Connections.");
        }
        if (username == null) {
            throw new ConnectorAuthException("This connection has no user name. Add the integration user's in Settings > Connections.");
        }
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            throw new ConnectorAuthException("This connection has no private key. Add it in Settings > Connections.");
        }
        ApiClient login = http.client(loginUrl).service("Salesforce").maxAttempts(2).build();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", GRANT);
        form.put("assertion", assertion());
        ApiResponse response = login.postForm(TOKEN_PATH, form);
        JsonNode body = parse(response);
        if (!response.ok()) {
            if (response.status() == 400 || response.status() == 401) {
                String reason = body == null ? "" : body.path("error_description").asText(body.path("error").asText(""));
                throw new ConnectorAuthException("Salesforce refused the JWT sign-in (" + (reason.isBlank() ? response.status() : reason)
                        + "). Check the consumer key, the user name, the certificate uploaded to the app, and that the "
                        + "user is pre-authorized for it.");
            }
            throw login.failure(response);
        }
        String token = body == null ? null : body.path("access_token").asText(null);
        String instance = body == null ? null : body.path("instance_url").asText(null);
        if (token == null || token.isBlank()) throw new ConnectorAuthException("Salesforce answered the sign-in without a token.");
        return new Grant(token, checkedInstance(instance, loginUrl), clock.instant().plus(LIFETIME));
    }

    /**
     * The instance_url a token is sent to: the connection's own host ({@code loginUrl}), or a
     * Salesforce one over https - whichever flow's answer named it, since the token goes there.
     */
    static String checkedInstance(String instance, String loginUrl) {
        if (instance == null || instance.isBlank()) return loginUrl;
        String url = instance.strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ConnectorAuthException("Salesforce named an org address that is not a URL.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        URI login = URI.create(loginUrl);
        boolean own = host.equals(login.getHost() == null ? "" : login.getHost().toLowerCase(Locale.ROOT))
                && String.valueOf(uri.getScheme()).equalsIgnoreCase(login.getScheme());
        boolean salesforce = "https".equalsIgnoreCase(uri.getScheme())
                && SALESFORCE_DOMAINS.stream().anyMatch(host::endsWith);
        if (!own && !salesforce) {
            throw new ConnectorAuthException("Salesforce named " + host + " as the org's address, which is not a "
                    + "Salesforce server, so the token was not sent there.");
        }
        return url;
    }

    String assertion() {
        try {
            Instant now = clock.instant();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", consumerKey);
            claims.put("sub", username);
            claims.put("aud", audience);
            claims.put("exp", now.plus(ASSERTION_LIFETIME).getEpochSecond());
            String header = b64(JSON.writeValueAsBytes(Map.of("alg", "RS256")));
            String body = b64(JSON.writeValueAsBytes(claims));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey(privateKeyPem));
            signer.update((header + "." + body).getBytes(StandardCharsets.US_ASCII));
            return header + "." + body + "." + b64(signer.sign());
        } catch (ConnectorAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorAuthException("The private key could not be read. Paste the whole unencrypted PEM key, "
                    + "BEGIN and END lines included.", e);
        }
    }

    /**
     * An RSA private key from PEM, its line breaks optional (a password field drops them): PKCS#8
     * ({@code BEGIN PRIVATE KEY}, what {@code openssl genpkey} and OpenSSL 3's {@code genrsa} write) or
     * PKCS#1 ({@code BEGIN RSA PRIVATE KEY}, older {@code genrsa}). An encrypted key is refused.
     */
    static PrivateKey privateKey(String pem) throws Exception {
        if (pem.contains("ENCRYPTED")) {
            throw new ConnectorAuthException("The private key is encrypted. Paste it without a passphrase "
                    + "(openssl pkcs8 -topk8 -nocrypt).");
        }
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        if (pkcs1) der = pkcs1ToPkcs8(der);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    // A PKCS#1 RSAPrivateKey wrapped as PKCS#8's PrivateKeyInfo: version 0, rsaEncryption, the key.
    private static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00};
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octets = tlv(0x04, pkcs1);
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(version);
        inner.writeBytes(algorithm);
        inner.writeBytes(octets);
        return tlv(0x30, inner.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            int bytes = len > 0xffffff ? 4 : len > 0xffff ? 3 : len > 0xff ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) out.write((len >> (8 * i)) & 0xff);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static JsonNode parse(ApiResponse response) {
        try {
            return response.json();
        } catch (ConnectorHttpException e) {
            return null;
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Forgets every token - for tests. */
    static void forgetAll() {
        GRANTS.clear();
    }
}
