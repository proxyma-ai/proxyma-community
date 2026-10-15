package ai.proxyma.core.configtransfer;

import ai.proxyma.core.security.CredentialEncryption;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * The encrypted half of an exported configuration: every secret it carries, under one passphrase.
 *
 * <p>WHAT IS NEW HERE IS ONLY THE KEY. The cipher is
 * {@link CredentialEncryption#encrypt(byte[], String)} - the same AES-256-GCM, the same random
 * 12-byte IV, the same {@code ENC(iv.ciphertext)} framing every stored credential in this product
 * already uses. That method takes a RAW key, which is what makes it reusable here: the app's own
 * credentials are encrypted under a key from the OS key store, and an export is encrypted under a
 * key derived from what the user typed. One cipher implementation, two key sources.
 *
 * <p>WHY ONE ENVELOPE RATHER THAN A BOX PER FIELD. Fewer IVs, one authentication tag to verify, and
 * - the point - the readable half of the file keeps its shape. A field whose value is secret is
 * written as the literal {@code "$secret"} in the plain JSON, so the file still shows WHICH secrets
 * it carries and a reviewer can diff two exports meaningfully. Only the values move in here.
 *
 * <p>A WRONG PASSPHRASE IS DETECTED, NOT GUESSED AT. GCM authenticates; a key derived from the
 * wrong passphrase fails the tag check inside the cipher rather than returning plausible rubbish.
 * {@link #open} turns that into {@link WrongPassphraseException} so the caller can say the one
 * useful thing instead of showing a stack trace about bad padding.
 */
public final class SecretEnvelope {

    private SecretEnvelope() {}

    /** Named in the file so a future format can change it without guessing what an old file used. */
    public static final String KDF = "PBKDF2WithHmacSHA256";

    /**
     * OWASP's 2023 floor for PBKDF2-HMAC-SHA256. Written into the file and read back from it on
     * import, so raising this later does not strand every file exported before the change.
     */
    public static final int ITERATIONS = 600_000;

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    /**
     * Shortest passphrase this will seal under.
     *
     * <p>The whole protection on a file that then lands in a Downloads folder is this string. Twelve
     * is not a strong password, it is the point below which 600k PBKDF2 iterations stop buying
     * anything against an offline attacker who has the file.
     */
    public static final int MIN_PASSPHRASE_CHARS = 12;

    /** The file's {@code secrets} object. {@code payload} is a full {@code ENC(...)} string. */
    public record Sealed(String kdf, int iterations, String salt, String payload) {}

    public static class WrongPassphraseException extends RuntimeException {
        public WrongPassphraseException() {
            super("That passphrase does not open this file.");
        }
    }

    public static class WeakPassphraseException extends RuntimeException {
        public WeakPassphraseException() {
            super("The passphrase must be at least " + MIN_PASSPHRASE_CHARS + " characters. "
                    + "It is the only thing protecting the credentials in this file once it leaves "
                    + "this machine.");
        }
    }

    /** Encrypts {@code secrets} (path -> value) under {@code passphrase}. */
    public static Sealed seal(Map<String, String> secrets, String passphrase, ObjectMapper mapper) {
        if (passphrase == null || passphrase.length() < MIN_PASSPHRASE_CHARS) {
            throw new WeakPassphraseException();
        }
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] key = deriveKey(passphrase, salt, ITERATIONS);
        try {
            String json = mapper.writeValueAsString(secrets);
            return new Sealed(KDF, ITERATIONS, Base64.getEncoder().encodeToString(salt),
                    CredentialEncryption.encrypt(key, json));
        } catch (Exception e) {
            throw new IllegalStateException("Could not seal the exported secrets", e);
        }
    }

    /**
     * Decrypts a sealed envelope back to path -> value.
     *
     * <p>Reads the iteration count and salt FROM THE FILE rather than from the constants above, so a
     * file exported under different parameters still opens. Only the algorithm name is checked, and
     * an unrecognized one is refused rather than silently treated as the default.
     */
    public static Map<String, String> open(Sealed sealed, String passphrase, ObjectMapper mapper) {
        if (sealed == null) return Map.of();
        if (!KDF.equals(sealed.kdf())) {
            throw new IllegalArgumentException("Unsupported key derivation in this file: " + sealed.kdf());
        }
        if (passphrase == null || passphrase.isEmpty()) throw new WrongPassphraseException();
        byte[] salt;
        try {
            salt = Base64.getDecoder().decode(sealed.salt());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("This file's secrets block is malformed.", e);
        }
        byte[] key = deriveKey(passphrase, salt, sealed.iterations());
        String json;
        try {
            json = CredentialEncryption.decrypt(key, sealed.payload());
        } catch (Exception e) {
            // CredentialEncryption wraps every failure - GCM tag mismatch included - as
            // IllegalStateException, and a tag mismatch is overwhelmingly the wrong passphrase
            // rather than a corrupt file. Reported as the former: it is the one the reader can act
            // on, and its message does not quote any part of the ciphertext.
            throw new WrongPassphraseException();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("This file's secrets block is malformed.", e);
        }
    }

    private static byte[] deriveKey(String passphrase, byte[] salt, int iterations) {
        // Bounded on BOTH sides. Too few iterations is a weak file; an absurd count read from an
        // untrusted file is a way to make the server spend a minute of CPU per request.
        int rounds = Math.max(100_000, Math.min(iterations, 5_000_000));
        try {
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, rounds, KEY_BITS);
            return SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive a key from the passphrase", e);
        }
    }
}
