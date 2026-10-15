package ai.proxyma.core.registry.ingest;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.common.ingest.IngestTokenCheck;
import ai.proxyma.core.registry.model.IngestToken;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.IngestTokenRepository;
import ai.proxyma.core.settings.connection.UserRoles;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The ingest tokens of custom sources: made, listed and revoked by the source's owner, and checked
 * on every ingestion API request.
 *
 * <p><b>The format is the MCP token's</b> - {@code pxm_} and 64 hex characters from secure random
 * bytes, stored as SHA-256, twelve characters kept for display, the token itself returned once - so
 * GuardrailRules and the log tail's redaction, which know that shape, cover this one. What it may do
 * is not the MCP token's at all: one source's ingestion endpoints and nothing else. That is decided
 * by WHERE it is stored, not by its shape - only this class reads {@code ingest_tokens}, so every
 * other door (JwtAuthFilter on Enterprise Server, AccessGate's install token on Desktop and Home
 * Server) finds nothing for it and answers 401.
 *
 * <p><b>Whose it is.</b> The owner of the source when it was made; every use checks the source is
 * still that person's (on the single-user editions both are null, the one local user), and on
 * Enterprise Server that the account may still sign in - an account awaiting deletion is locked out
 * of everything else, so of this too.
 */
@Service
public class IngestTokenService implements IngestTokenCheck {

    /** How many tokens one source may have at once - one per script or service that feeds it, with room to rotate. */
    public static final int MAX_TOKENS_PER_SOURCE = 10;

    static final String PREFIX = "pxm_";
    private static final int TOKEN_BYTES = 32;
    private static final Pattern SHAPE = Pattern.compile("pxm_[0-9a-f]{64}");
    // Once a minute per token, not once a request - a script pushing a batch a second would otherwise
    // write the row every second (JwtAuthFilter's MCP tokens do the same).
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IngestTokenRepository tokens;
    private final IngestService ingest;
    private final ObjectProvider<UserRoles> users;

    public IngestTokenService(IngestTokenRepository tokens, IngestService ingest, ObjectProvider<UserRoles> users) {
        this.tokens = tokens;
        this.ingest = ingest;
        this.users = users;
    }

    /** A request the ingestion API refuses before it reads the body: who is asking is not allowed. */
    public static class IngestAuthException extends RuntimeException {
        private final int status;

        public IngestAuthException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    // -- Checking -----------------------------------------------------------------------------------

    /**
     * The source an ingestion API request may write to, from its Authorization header - or the reason
     * it may not: 401 for no token or one that is not an ingest token, 403 for a token of another
     * source or of somebody who no longer owns this one, 404 for a source that is not a custom source.
     */
    public RegisteredConnector authenticate(UUID sourceId, String authorization) {
        String raw = bearer(authorization);
        if (raw == null) {
            throw new IngestAuthException("This endpoint takes the source's ingest token: send it as "
                    + "\"Authorization: Bearer pxm_...\". Create one on the source's page in Proxyma.", 401);
        }
        IngestToken token = SHAPE.matcher(raw).matches() ? tokens.findByTokenHash(sha256(raw)).orElse(null) : null;
        if (token == null) {
            throw new IngestAuthException("This is not an ingest token of any custom source, or it was revoked. "
                    + "Create one on the source's page in Proxyma.", 401);
        }
        if (!token.getConnectorId().equals(sourceId)) {
            throw new IngestAuthException("This token belongs to another source.", 403);
        }
        RegisteredConnector source = ingest.pushSource(sourceId);
        if (source == null) throw new IngestAuthException("There is no custom source with this id.", 404);
        if (!Objects.equals(source.getUserId(), token.getUserId())) {
            throw new IngestAuthException("This token was made by somebody who no longer owns the source.", 403);
        }
        UserRoles roles = users.getIfAvailable();
        if (roles != null && token.getUserId() != null && !roles.isActive(token.getUserId())) {
            throw new IngestAuthException("The account this token belongs to cannot sign in.", 401);
        }
        Instant now = Instant.now();
        if (token.getLastUsedAt() == null || token.getLastUsedAt().isBefore(now.minus(LAST_USED_RESOLUTION))) {
            tokens.markUsed(token.getId(), now);
        }
        return source;
    }

    @Override
    public boolean admits(UUID sourceId, String rawToken) {
        if (sourceId == null || rawToken == null || !SHAPE.matcher(rawToken.trim()).matches()) return false;
        return tokens.findByTokenHash(sha256(rawToken.trim()))
                .map(t -> t.getConnectorId().equals(sourceId))
                .orElse(false);
    }

    static String bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String raw = authorization.substring(7).trim();
        return raw.isEmpty() ? null : raw;
    }

    // -- Managing, as the source's owner --------------------------------------------------------------

    /** A token just made: the token itself, which is never shown again, and how it is listed. */
    public record Created(String token, Map<String, Object> listed) {}

    public Created create(UUID sourceId, String callerUserId, String label) {
        RegisteredConnector source = ownedPushSource(sourceId, callerUserId);
        if (tokens.countByConnectorId(sourceId) >= MAX_TOKENS_PER_SOURCE) {
            throw new IngestRefusedException("A source may have at most " + MAX_TOKENS_PER_SOURCE
                    + " tokens. Revoke one you no longer use first.");
        }
        String name = label == null ? "" : IngestDraft.oneLine(label);
        if (name.isEmpty()) name = "Ingest token";
        if (name.length() > IngestToken.LABEL_MAX) {
            throw new IngestRefusedException("The token's name is longer than " + IngestToken.LABEL_MAX + " characters.");
        }
        String raw = PREFIX + randomHex();
        IngestToken token = new IngestToken();
        token.setConnectorId(sourceId);
        token.setUserId(source.getUserId());
        token.setLabel(name);
        token.setTokenHash(sha256(raw));
        token.setTokenPrefix(raw.substring(0, 12));
        token = tokens.save(token);
        // Its name and first characters, never the token.
        AuditEntry.of(AuditCategory.ACCESS, "ingest-token.created")
                .user(callerUserId)
                .target(source.getName())
                .summary("Created an ingest token for " + source.getName())
                .detail("label", token.getLabel())
                .detail("tokenPrefix", token.getTokenPrefix())
                .record();
        return new Created(raw, listed(token));
    }

    public List<Map<String, Object>> list(UUID sourceId, String callerUserId) {
        ownedPushSource(sourceId, callerUserId);
        return tokens.findByConnectorIdOrderByCreatedAtDesc(sourceId).stream().map(IngestTokenService::listed).toList();
    }

    /** Revokes one token of the source - deletes it, so it opens nothing from the next request on. */
    public boolean revoke(UUID sourceId, UUID tokenId, String callerUserId) {
        RegisteredConnector source = ownedPushSource(sourceId, callerUserId);
        IngestToken token = tokens.findById(tokenId).filter(t -> t.getConnectorId().equals(sourceId)).orElse(null);
        if (token == null) return false;
        tokens.delete(token);
        AuditEntry.of(AuditCategory.ACCESS, "ingest-token.revoked")
                .user(callerUserId)
                .target(source.getName())
                .summary("Revoked an ingest token of " + source.getName())
                .detail("label", token.getLabel())
                .detail("tokenPrefix", token.getTokenPrefix())
                .record();
        return true;
    }

    /**
     * The source, when the caller owns it and it is a custom source. A source shared with the caller
     * is not theirs to write to, so not theirs to make a token for: sharing lends reading only.
     */
    public RegisteredConnector ownedPushSource(UUID sourceId, String callerUserId) {
        RegisteredConnector source = ingest.pushSource(sourceId);
        if (source == null) throw new IngestAuthException("There is no custom source with this id.", 404);
        if (!Objects.equals(source.getUserId(), callerUserId)) {
            throw new IngestAuthException("Only the source's owner can do this.", 403);
        }
        return source;
    }

    private static Map<String, Object> listed(IngestToken t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId().toString());
        m.put("label", t.getLabel());
        m.put("tokenPrefix", t.getTokenPrefix());
        m.put("createdAt", t.getCreatedAt().toString());
        m.put("lastUsedAt", t.getLastUsedAt() == null ? null : t.getLastUsedAt().toString());
        return m;
    }

    static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String randomHex() {
        byte[] buffer = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
