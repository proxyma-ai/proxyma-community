package ai.proxyma.core.security;

import ai.proxyma.core.system.util.SystemInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the Linux credential key lives, since 0.10.0: a file, never the kernel keyring.
 *
 * <p>The keyring held it in {@code @u}, which the kernel clears at reboot, so the next start made a
 * new key and every saved credential and the activated license stopped decrypting. These pin the
 * replacement: the key survives in a file, a key an earlier version left in the keyring is carried
 * over once, and an existing file always wins.
 */
class CredentialKeyStoreTest {

    private static final SystemInfo LINUX = new SystemInfo(false, false, true, false);

    @TempDir
    Path dir;

    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    @Test
    @DisplayName("a new Linux install writes its key to a file, and the next start reads the same key")
    void fileSurvivesRestart() {
        byte[] first = CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null);
        assertTrue(Files.exists(dir.resolve(".secret")));
        byte[] second = CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null);
        assertArrayEquals(first, second);
        assertEquals(32, first.length);
    }

    @Test
    @DisplayName("a key an earlier version left in the keyring is copied into the file, not replaced")
    void keyringKeyIsCarriedOver() throws Exception {
        byte[] old = key(7);
        byte[] loaded = CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> old);
        assertArrayEquals(old, loaded);
        assertArrayEquals(old, Files.readAllBytes(dir.resolve(".secret")));
        // After the copy the keyring is never consulted again - a reboot that clears it changes nothing.
        AtomicInteger asked = new AtomicInteger();
        byte[] afterReboot = CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> { asked.incrementAndGet(); return null; });
        assertArrayEquals(old, afterReboot);
        assertEquals(0, asked.get());
    }

    @Test
    @DisplayName("an existing key file wins over anything in the keyring")
    void fileWinsOverKeyring() throws Exception {
        Files.write(dir.resolve(".secret"), key(3));
        byte[] loaded = CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> key(9));
        assertArrayEquals(key(3), loaded);
    }

    @Test
    @DisplayName("a key file that holds no key is moved aside, not overwritten, so a backup can still restore it")
    void anUnreadableKeyIsKept() throws Exception {
        byte[] truncated = {1, 2, 3, 4, 5};
        Files.write(dir.resolve(".secret"), truncated);

        byte[] fresh = CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null);

        assertEquals(32, fresh.length);
        try (var files = Files.list(dir)) {
            Path aside = files.filter(p -> p.getFileName().toString().startsWith(".secret.unreadable-"))
                    .findFirst().orElseThrow();
            assertArrayEquals(truncated, Files.readAllBytes(aside));
        }
    }

    @Test
    @DisplayName("something encrypted before a restart still decrypts after it")
    void credentialsSurviveRestart() {
        byte[] before = CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null);
        String stored = CredentialEncryption.encrypt(before, "a-personal-access-token");
        byte[] after = CredentialEncryption.loadOrCreateKey(dir, LINUX, () -> null);
        assertEquals("a-personal-access-token", CredentialEncryption.decrypt(after, stored));
    }
}
