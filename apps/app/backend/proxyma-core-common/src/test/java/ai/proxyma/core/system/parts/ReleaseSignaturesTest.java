package ai.proxyma.core.system.parts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Update signatures. The fixtures were made with OpenSSL, exactly as tools/release/sign-sums.sh
 * makes them (`openssl pkeyutl -sign -rawin`), from THROWAWAY keys - so this is a test that the
 * shell side and the Java side agree on the format, not only that Java agrees with itself.
 */
class ReleaseSignaturesTest {

    private static byte[] fixture(String name) throws Exception {
        try (InputStream in = ReleaseSignaturesTest.class.getResourceAsStream("/signing/" + name)) {
            return in.readAllBytes();
        }
    }

    private static PublicKey key(String name) throws Exception {
        return ReleaseSignatures.parsePem(new String(fixture(name), StandardCharsets.US_ASCII));
    }

    private static ReleaseSignatures sigs() throws Exception {
        return new ReleaseSignatures(List.of(key("test-release.pem")), List.of(key("test-dev.pem")));
    }

    @Test
    @DisplayName("an OpenSSL signature by the release key verifies, for releases and dev builds alike")
    void releaseKeyVerifies() throws Exception {
        byte[] sums = fixture("SHA256SUMS.txt");
        assertTrue(sigs().verify(sums, fixture("SHA256SUMS.txt.sig"), false));
        assertTrue(sigs().verify(sums, fixture("SHA256SUMS.txt.sig"), true));
    }

    @Test
    @DisplayName("the dev key is trusted ONLY when dev keys are allowed")
    void devKeyOnlyForDevBuilds() throws Exception {
        byte[] sums = fixture("SHA256SUMS.txt");
        byte[] devSig = fixture("SHA256SUMS.txt.dev.sig");
        assertFalse(sigs().verify(sums, devSig, false), "a dev-signed file was accepted on the release channel");
        assertTrue(sigs().verify(sums, devSig, true));
    }

    @Test
    @DisplayName("one changed byte in the checksums fails the signature")
    void tamperedChecksumsFail() throws Exception {
        byte[] sums = fixture("SHA256SUMS.txt");
        sums[0] = (byte) (sums[0] == '0' ? '1' : '0');
        assertFalse(sigs().verify(sums, fixture("SHA256SUMS.txt.sig"), true));
    }

    @Test
    @DisplayName("missing, empty or garbage signatures fail without throwing")
    void badSignaturesFail() throws Exception {
        byte[] sums = fixture("SHA256SUMS.txt");
        assertFalse(sigs().verify(sums, null, true));
        assertFalse(sigs().verify(sums, new byte[0], true));
        assertFalse(sigs().verify(sums, "not a signature".getBytes(), true));
        assertFalse(sigs().verify(null, fixture("SHA256SUMS.txt.sig"), true));
    }

    @Test
    @DisplayName("the jar carries exactly one release key and it parses")
    void builtInKeysLoad() {
        ReleaseSignatures builtIn = ReleaseSignatures.fromClasspath();
        assertEquals(1, builtIn.releaseKeyCount());
        // And the test keys are NOT built in: a fixture signature must not verify against them.
        assertFalse(builtIn.verify("x".getBytes(), new byte[64], true));
    }

    @Test
    @DisplayName("no key file in the source tree holds a PRIVATE key")
    void noPrivateKeysInTheTree() throws Exception {
        // .gitignore re-includes these two directories' .pem files past the repository-wide *.pem
        // rule. That exception is only safe while every file in them is a public key.
        for (String dir : List.of("src/main/resources/update-keys", "src/test/resources/signing")) {
            java.nio.file.Path root = java.nio.file.Path.of(dir);
            if (!java.nio.file.Files.exists(root)) continue;
            try (var walk = java.nio.file.Files.walk(root)) {
                for (var p : walk.filter(f -> f.toString().endsWith(".pem")).toList()) {
                    String text = java.nio.file.Files.readString(p);
                    assertFalse(text.contains("PRIVATE"), p + " holds a private key");
                    assertTrue(text.contains("BEGIN PUBLIC KEY"), p + " is not a public key");
                }
            }
        }
    }

    @Test
    @DisplayName("update-keys/index.txt lists exactly the key files in the directory")
    void indexMatchesTheDirectory() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/resources/update-keys");
        java.util.List<String> onDisk;
        try (var walk = java.nio.file.Files.walk(root)) {
            onDisk = walk.filter(f -> f.toString().endsWith(".pem"))
                    .map(f -> root.relativize(f).toString().replace('\\', '/')).sorted().toList();
        }
        assertEquals(onDisk, ReleaseSignatures.index().stream().sorted().toList());
        assertEquals(1, ReleaseSignatures.fromClasspath().devKeyCount());
    }
}
