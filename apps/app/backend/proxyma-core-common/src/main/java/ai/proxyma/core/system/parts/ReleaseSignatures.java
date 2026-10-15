package ai.proxyma.core.system.parts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Who may publish a release this installation will install: the Ed25519 public keys built into it.
 *
 * <p>WHAT THIS ADDS OVER THE CHECKSUMS. SHA256SUMS.txt proves a download arrived intact, but it
 * comes from the same place as the download, so anyone who can publish a release - a stolen GitHub
 * token, a compromised build machine - can publish matching checksums too. A signature over that
 * file, made with a key the publisher does not have, is what closes that: nothing is installed
 * unless the checksums listing it carry a valid signature from a key in this jar.
 *
 * <p>TWO KEY SETS, AND THE SPLIT IS THE POINT.
 * <ul>
 *   <li>{@code update-keys/release/} - the RELEASE keys. The private half lives only on the
 *       maintainer's PC and signs through tools/release/publish.sh. Trusted for everything.</li>
 *   <li>{@code update-keys/dev/} - the DEV keys, used by the dev pipeline on the build host and by
 *       windows-dev.sh. Trusted ONLY for a {@code -devN} build the user asked for - Development
 *       builds turned on, or a dev installer they chose to run. So a stolen dev key, or a
 *       compromised build host, can reach the people who opted into dev builds and nobody else.</li>
 * </ul>
 * More than one key per set is allowed, which is how a key is rotated: ship the new public key in a
 * release signed by the old one, then start signing with the new one.
 *
 * <p>The keys are listed in {@code update-keys/index.txt} rather than discovered, because the
 * launcher reads them too and has no Spring to scan a classpath directory with.
 * ReleaseSignaturesTest fails if the index and the directory disagree.
 *
 * <p>The signature is raw Ed25519 over the exact bytes of SHA256SUMS.txt - what
 * {@code openssl pkeyutl -sign -rawin} produces (tools/release/sign-sums.sh).
 */
public final class ReleaseSignatures {

    private static final Logger log = LoggerFactory.getLogger(ReleaseSignatures.class);

    /** The release asset holding the signature of SHA256SUMS.txt. A contract with sign-sums.sh. */
    public static final String SIGNATURE_ASSET = "SHA256SUMS.txt.sig";

    /** The release asset listing one SHA-256 per published file, in `sha256sum` format. */
    public static final String CHECKSUMS_ASSET = "SHA256SUMS.txt";

    static final String INDEX = "/update-keys/index.txt";

    private final List<PublicKey> releaseKeys;
    private final List<PublicKey> devKeys;

    public ReleaseSignatures(List<PublicKey> releaseKeys, List<PublicKey> devKeys) {
        this.releaseKeys = List.copyOf(releaseKeys);
        this.devKeys = List.copyOf(devKeys);
    }

    /** The keys built into this jar. An installation with no release key refuses every release. */
    public static ReleaseSignatures fromClasspath() {
        List<PublicKey> release = new ArrayList<>();
        List<PublicKey> dev = new ArrayList<>();
        for (String entry : index()) {
            try (InputStream in = ReleaseSignatures.class.getResourceAsStream("/update-keys/" + entry)) {
                if (in == null) throw new IllegalStateException("listed but missing");
                PublicKey key = parsePem(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
                if (entry.startsWith("release/")) release.add(key);
                else if (entry.startsWith("dev/")) dev.add(key);
            } catch (Exception e) {
                log.warn("Ignoring unreadable update key {}: {}", entry, e.getMessage());
            }
        }
        if (release.isEmpty()) log.warn("No release signing key is built in - every release will be refused");
        return new ReleaseSignatures(release, dev);
    }

    /** The entries of update-keys/index.txt, relative to update-keys/. */
    static List<String> index() {
        List<String> entries = new ArrayList<>();
        try (InputStream in = ReleaseSignatures.class.getResourceAsStream(INDEX)) {
            if (in == null) return entries;
            for (String line : new String(in.readAllBytes(), StandardCharsets.US_ASCII).split("\\R")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) entries.add(line);
            }
        } catch (Exception e) {
            log.warn("Could not read {}: {}", INDEX, e.getMessage());
        }
        return entries;
    }

    /**
     * True when {@code signature} is a valid signature of {@code data} by a trusted key.
     *
     * @param allowDevKeys whether a DEV key counts - only for a dev build the user asked for
     */
    public boolean verify(byte[] data, byte[] signature, boolean allowDevKeys) {
        if (data == null || signature == null || signature.length == 0) return false;
        if (matches(releaseKeys, data, signature)) return true;
        return allowDevKeys && matches(devKeys, data, signature);
    }

    int releaseKeyCount() { return releaseKeys.size(); }

    int devKeyCount() { return devKeys.size(); }

    private static boolean matches(List<PublicKey> keys, byte[] data, byte[] signature) {
        for (PublicKey key : keys) {
            try {
                Signature s = Signature.getInstance("Ed25519");
                s.initVerify(key);
                s.update(data);
                if (s.verify(signature)) return true;
            } catch (Exception e) {
                // A malformed signature is a failed verification, never an exception a caller has
                // to reason about.
                log.debug("Signature check with one key failed: {}", e.getMessage());
            }
        }
        return false;
    }

    /** A PEM {@code PUBLIC KEY} (SubjectPublicKeyInfo) for Ed25519, as {@code openssl pkey -pubout} writes it. */
    static PublicKey parsePem(String pem) throws Exception {
        String b64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }
}
