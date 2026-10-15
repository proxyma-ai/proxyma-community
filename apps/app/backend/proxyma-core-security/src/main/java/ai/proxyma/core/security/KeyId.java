package ai.proxyma.core.security;

import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.HexFormat;

public final class KeyId {

    private KeyId() {}

    // Returns first 16 hex chars of SHA-256(DER-encoded public key).
    // Stable across restarts; changes only when the key pair is rotated.
    public static String of(RSAPublicKey key) {
        try {
            byte[] der = key.getEncoded();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(der);
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute key ID", e);
        }
    }
}
