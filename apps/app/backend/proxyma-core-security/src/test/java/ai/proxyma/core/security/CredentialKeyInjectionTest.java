package ai.proxyma.core.security;

import ai.proxyma.core.system.util.SystemInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credential key given from outside the data volume - PROXYMA_CREDENTIAL_KEY or
 * PROXYMA_CREDENTIAL_KEY_FILE - for a container whose volume can be replaced while its secrets stay.
 *
 * <p>What these pin: the given key is the key, and it is never written into the volume; a blank
 * value is unset (Compose passes an unset {@code ${VAR:-}} as an empty string); a malformed one is
 * refused rather than padded; and a key already stored on the volume that differs from the given one
 * refuses the start, since going on would leave every saved credential unreadable.
 */
class CredentialKeyInjectionTest {

    private static final SystemInfo LINUX = new SystemInfo(false, false, true, false);

    @TempDir
    Path dir;

    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    @DisplayName("the key in PROXYMA_CREDENTIAL_KEY is used, and nothing is written into the volume")
    void keyFromVariable() {
        byte[] loaded = CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null,
                env(Map.of(CredentialEncryption.KEY_VARIABLE, b64(key(5)))));
        assertArrayEquals(key(5), loaded);
        assertFalse(Files.exists(dir.resolve(".secret")));
    }

    @Test
    @DisplayName("a mounted key file is read as base64 text or as 32 raw bytes")
    void keyFromFile() throws Exception {
        Path text = dir.resolve("key.txt");
        Files.writeString(text, b64(key(6)) + "\n");
        assertArrayEquals(key(6), CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null,
                env(Map.of(CredentialEncryption.KEY_FILE_VARIABLE, text.toString()))));

        Path raw = dir.resolve("key.bin");
        Files.write(raw, key(7));
        assertArrayEquals(key(7), CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null,
                env(Map.of(CredentialEncryption.KEY_FILE_VARIABLE, raw.toString()))));
        assertFalse(Files.exists(dir.resolve(".secret")));
    }

    @Test
    @DisplayName("something encrypted under the given key decrypts after a restart onto an empty volume")
    void survivesAFreshVolume(@TempDir Path freshVolume) {
        Map<String, String> secret = Map.of(CredentialEncryption.KEY_VARIABLE, b64(key(8)));
        byte[] before = CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null, env(secret));
        String stored = CredentialEncryption.encrypt(before, "a-provider-api-key");
        byte[] after = CredentialEncryption.loadOrCreateKey(freshVolume, LINUX, () -> null, env(secret));
        assertEquals("a-provider-api-key", CredentialEncryption.decrypt(after, stored));
    }

    @Test
    @DisplayName("a blank value is unset: the volume's own key is made and kept as before")
    void blankIsUnset() {
        byte[] loaded = CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null,
                env(Map.of(CredentialEncryption.KEY_VARIABLE, "  ", CredentialEncryption.KEY_FILE_VARIABLE, "")));
        assertEquals(32, loaded.length);
        assertTrue(Files.exists(dir.resolve(".secret")));
    }

    @Test
    @DisplayName("a key that is not 32 bytes of base64 is refused, never padded or cut")
    void malformedIsRefused() {
        IllegalStateException shortKey = assertThrows(IllegalStateException.class, () ->
                CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null,
                        env(Map.of(CredentialEncryption.KEY_VARIABLE, b64(new byte[16])))));
        assertTrue(shortKey.getMessage().contains("32 bytes"));
        assertThrows(IllegalStateException.class, () ->
                CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null,
                        env(Map.of(CredentialEncryption.KEY_VARIABLE, "not base64 at all!"))));
        assertFalse(Files.exists(dir.resolve(".secret")));
    }

    @Test
    @DisplayName("both the variable and the file set is refused")
    void bothIsRefused() throws Exception {
        Path file = dir.resolve("key.txt");
        Files.writeString(file, b64(key(1)));
        assertThrows(IllegalStateException.class, () ->
                CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null, env(Map.of(
                        CredentialEncryption.KEY_VARIABLE, b64(key(1)),
                        CredentialEncryption.KEY_FILE_VARIABLE, file.toString()))));
    }

    @Test
    @DisplayName("a different key already stored on the volume refuses the start and is left in place")
    void aDifferentStoredKeyRefuses() throws Exception {
        Files.write(dir.resolve(".secret"), key(3));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
                CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null,
                        env(Map.of(CredentialEncryption.KEY_VARIABLE, b64(key(4))))));
        assertTrue(refused.getMessage().contains("not the key already stored"));
        assertArrayEquals(key(3), Files.readAllBytes(dir.resolve(".secret")));
    }

    @Test
    @DisplayName("the same key stored on the volume and given is accepted - the way to carry an install over")
    void theSameStoredKeyIsAccepted() throws Exception {
        Files.write(dir.resolve(".secret"), key(9));
        assertArrayEquals(key(9), CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null,
                env(Map.of(CredentialEncryption.KEY_VARIABLE, b64(key(9))))));
    }
}
