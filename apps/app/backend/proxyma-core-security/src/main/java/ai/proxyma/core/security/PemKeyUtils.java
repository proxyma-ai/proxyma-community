package ai.proxyma.core.security;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;

public final class PemKeyUtils {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private PemKeyUtils() {}

    public static RSAPublicKey readPublicKey(InputStream in) throws Exception {
        String pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return parsePublicKey(pem);
    }

    public static RSAPublicKey readPublicKey(File file) throws Exception {
        String pem = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return parsePublicKey(pem);
    }

    public static RSAPrivateKey readPrivateKey(File file) throws Exception {
        String pem = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String b64 = pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(b64);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static RSAPublicKey parsePublicKey(String pem) throws Exception {
        String b64 = pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(b64);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }

    /** What {@link #generateIfMissing} found and did. */
    public enum KeyPairState { PRESENT, GENERATED, PUBLIC_DERIVED }

    /**
     * Makes sure the RS256 pair the server signs user tokens with exists, generating it on first start.
     *
     * <p>Until this existed the pair had to be made by {@code openssl} on the host before the first
     * start (install.sh), so a plain {@code docker run}, a compose file used without the script and
     * every Kubernetes install failed at startup. Losing the pair signs everyone out and nothing more,
     * so a generated pair kept on the data volume is enough.
     *
     * <ul>
     *   <li>Both files there: nothing is touched - an operator's own pair, mounted read-only, wins.</li>
     *   <li>Neither: a fresh RSA-2048 pair, PKCS#8 and X.509 PEM, the private half created 0600.</li>
     *   <li>The private half alone: the public half is derived from it. That is also what a start
     *       cut short between the two writes leaves behind, since the private half is written first.</li>
     *   <li>The public half alone: refused. A new pair would not match whatever holds the old public
     *       key, and that is a mistake in what was mounted, not something to repair silently.</li>
     * </ul>
     */
    public static synchronized KeyPairState generateIfMissing(Path privateKey, Path publicKey)
            throws IOException, GeneralSecurityException {
        boolean hasPrivate = Files.exists(privateKey);
        boolean hasPublic = Files.exists(publicKey);
        if (hasPrivate && hasPublic) return KeyPairState.PRESENT;
        if (hasPublic) {
            throw new IllegalStateException("The JWT public key " + publicKey + " exists but its private key "
                    + privateKey + " does not. Provide both files, or remove the public one to have a new pair made.");
        }
        if (hasPrivate) {
            RSAPrivateKey existing;
            try {
                existing = readPrivateKey(privateKey.toFile());
            } catch (IOException | GeneralSecurityException e) {
                throw e;
            } catch (Exception e) {
                throw new GeneralSecurityException("Could not read the JWT private key " + privateKey, e);
            }
            if (!(existing instanceof RSAPrivateCrtKey crt)) {
                throw new IllegalStateException("The JWT public key " + publicKey + " is missing and cannot be derived "
                        + "from " + privateKey + ". Provide both files.");
            }
            byte[] der = KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent())).getEncoded();
            write(publicKey, pem("PUBLIC KEY", der), false);
            return KeyPairState.PUBLIC_DERIVED;
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        // Private first: a start cut short after it leaves a state the next start repairs (above).
        write(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), true);
        write(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()), false);
        return KeyPairState.GENERATED;
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    // Written to a temporary file beside the target and moved into place, so a reader never sees half
    // a key. The private half's temporary file is created 0600 rather than narrowed afterwards, so
    // there is no window in which it sits at the umask default.
    private static void write(Path target, String content, boolean ownerOnly) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        Path temp = ownerOnly && posix
                ? Files.createTempFile(dir, ".jwt-key", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                : Files.createTempFile(dir, ".jwt-key", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.US_ASCII);
            if (posix && !ownerOnly) {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"));
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
