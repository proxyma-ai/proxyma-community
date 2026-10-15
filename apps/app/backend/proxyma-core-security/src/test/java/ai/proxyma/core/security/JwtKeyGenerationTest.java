package ai.proxyma.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JWT key pair Enterprise Server makes on its first start (PemKeyUtils.generateIfMissing), so a
 * container needs no {@code openssl} on its host before it can run.
 *
 * <p>What these pin: a fresh pair is a working pair in the format the readers expect, made once and
 * never replaced; an operator's own pair is left alone; a lone private half gets its public half back;
 * a lone public half is refused; and the private half is never readable by other accounts.
 */
class JwtKeyGenerationTest {

    @TempDir
    Path dir;

    private static boolean signs(RSAPrivateKey privateKey, RSAPublicKey publicKey) throws Exception {
        byte[] data = "a token".getBytes();
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(data);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        return verifier.verify(signature);
    }

    @Test
    @DisplayName("a first start makes a matching pair the readers accept, in a directory it creates")
    void generatesAWorkingPair() throws Exception {
        Path privateKey = dir.resolve("keys/server-jwt-private.pem");
        Path publicKey = dir.resolve("keys/server-jwt-public.pem");

        assertEquals(PemKeyUtils.KeyPairState.GENERATED, PemKeyUtils.generateIfMissing(privateKey, publicKey));

        RSAPrivateKey priv = PemKeyUtils.readPrivateKey(privateKey.toFile());
        RSAPublicKey pub = PemKeyUtils.readPublicKey(publicKey.toFile());
        assertEquals(2048, pub.getModulus().bitLength());
        assertTrue(signs(priv, pub));
        assertTrue(Files.readString(privateKey).startsWith("-----BEGIN PRIVATE KEY-----\n"));
        try (var files = Files.list(dir.resolve("keys"))) {
            assertEquals(2, files.count(), "no temporary file is left behind");
        }
    }

    @Test
    @DisplayName("the next start keeps the pair, so nobody is signed out by a restart")
    void keepsThePair() throws Exception {
        Path privateKey = dir.resolve("private.pem");
        Path publicKey = dir.resolve("public.pem");
        PemKeyUtils.generateIfMissing(privateKey, publicKey);
        String before = Files.readString(privateKey);

        assertEquals(PemKeyUtils.KeyPairState.PRESENT, PemKeyUtils.generateIfMissing(privateKey, publicKey));
        assertEquals(before, Files.readString(privateKey));
    }

    @Test
    @DisplayName("a lone private half gets its public half back, matching it")
    void derivesTheMissingPublicHalf() throws Exception {
        Path privateKey = dir.resolve("private.pem");
        Path publicKey = dir.resolve("public.pem");
        PemKeyUtils.generateIfMissing(privateKey, publicKey);
        RSAPublicKey original = PemKeyUtils.readPublicKey(publicKey.toFile());
        Files.delete(publicKey);

        assertEquals(PemKeyUtils.KeyPairState.PUBLIC_DERIVED, PemKeyUtils.generateIfMissing(privateKey, publicKey));
        assertEquals(original, PemKeyUtils.readPublicKey(publicKey.toFile()));
    }

    @Test
    @DisplayName("a lone public half is refused, never paired with a new private key")
    void refusesALonePublicHalf() throws Exception {
        Path privateKey = dir.resolve("private.pem");
        Path publicKey = dir.resolve("public.pem");
        PemKeyUtils.generateIfMissing(privateKey, publicKey);
        Files.delete(privateKey);

        assertThrows(IllegalStateException.class, () -> PemKeyUtils.generateIfMissing(privateKey, publicKey));
        assertFalse(Files.exists(privateKey));
    }

    @Test
    @DisplayName("the private half is readable by its owner alone")
    void privateHalfIsOwnerOnly() throws Exception {
        Path privateKey = dir.resolve("private.pem");
        Path publicKey = dir.resolve("public.pem");
        PemKeyUtils.generateIfMissing(privateKey, publicKey);
        if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) return;  // Windows: no mode to check
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(privateKey)));
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(publicKey)));
    }
}
