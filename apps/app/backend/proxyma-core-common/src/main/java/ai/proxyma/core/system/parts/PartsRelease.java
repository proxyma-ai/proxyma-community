package ai.proxyma.core.system.parts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Reads one release's parts manifest and proves it is genuine before anything trusts it.
 *
 * <p>Three files, from the release's download location: SHA256SUMS.txt, its signature, and
 * {@code parts-<platform>.txt}. The signature is checked against the keys built into this jar, the
 * manifest's digest against the signed checksums, and only then is the manifest parsed. Each part
 * then carries its own digest inside the manifest, so a signed checksums file vouches for every byte
 * that is later installed.
 */
public final class PartsRelease {

    /** A release that is not published (HTTP 404) - distinct from one that failed to verify. */
    public static final class NotPublished extends IOException {
        public NotPublished(String message) { super(message); }
    }

    private static final int MAX_BYTES = 1 << 20;

    private PartsRelease() {}

    /**
     * @param releaseUrl the release's download location, ending in "/" - for GitHub,
     *                   {@code https://github.com/<owner>/<repo>/releases/download/<tag>/}
     * @param allowDevKeys whether a dev key's signature counts - see ReleaseSignatures
     * @param expectedVersion the version the manifest must state, a leading "v" ignored, or null
     *                   where the caller asked for no particular one ("the newest release")
     */
    public static PartsManifest fetch(PartsTransport transport, String releaseUrl, String platform,
                                      ReleaseSignatures signatures, boolean allowDevKeys,
                                      String expectedVersion) throws IOException {
        String manifestName = PartsManifest.assetName(platform);
        // Only a missing MANIFEST means the release is not there. A release that has one but no
        // checksums or no signature is published and unsigned, which is a refusal, not a reason
        // to go looking for another release.
        byte[] manifestBytes = bytes(transport, releaseUrl + manifestName, true);
        byte[] sums = bytes(transport, releaseUrl + ReleaseSignatures.CHECKSUMS_ASSET, false);
        byte[] signature = bytes(transport, releaseUrl + ReleaseSignatures.SIGNATURE_ASSET, false);
        PartsManifest manifest = verify(manifestName, manifestBytes, sums, signature, platform, signatures, allowDevKeys);
        requireVersion(manifest, expectedVersion);
        return manifest;
    }

    /**
     * Refuses a manifest that is not the version it was fetched as.
     *
     * <p>THE SIGNATURE SAYS A RELEASE IS GENUINE, NOT WHICH ONE IT IS (platform-02). The tag a
     * release is fetched under is an unsigned name on GitHub; an older release's genuine checksums,
     * signature and manifest published again under a newer tag passed every check, and an install
     * that auto-updates staged and ran the old build - with whatever was fixed since.
     */
    public static void requireVersion(PartsManifest manifest, String expectedVersion) throws IOException {
        if (expectedVersion == null) return;
        if (!Versions.stripV(manifest.version()).equals(Versions.stripV(expectedVersion))) {
            throw new IOException("The release published as " + expectedVersion + " is version "
                    + manifest.version() + " - refusing it");
        }
    }

    /** The checks {@link #fetch} makes, on bytes already in hand. */
    static PartsManifest verify(String manifestName, byte[] manifestBytes, byte[] sums, byte[] signature,
                                String platform, ReleaseSignatures signatures, boolean allowDevKeys) throws IOException {
        if (!signatures.verify(sums, signature, allowDevKeys)) {
            throw new IOException("The release's signature does not verify against the keys built into this installation");
        }
        String expected = expectedDigest(new String(sums, StandardCharsets.UTF_8), manifestName);
        if (expected == null) throw new IOException(ReleaseSignatures.CHECKSUMS_ASSET + " does not list " + manifestName);
        if (!expected.equalsIgnoreCase(PartsFetcher.sha256(manifestBytes))) {
            throw new IOException(manifestName + " failed its SHA-256 check");
        }
        PartsManifest manifest = PartsManifest.parse(new String(manifestBytes, StandardCharsets.UTF_8));
        if (!platform.equals(manifest.platform())) {
            throw new IOException(manifestName + " is for " + manifest.platform() + ", not " + platform);
        }
        return manifest;
    }

    /** `sha256sum` output: one "<hex>  <filename>" per line. Tolerates the * binary marker. */
    public static String expectedDigest(String sums, String assetName) {
        if (sums == null) return null;
        for (String line : sums.split("\\R")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length != 2) continue;
            String name = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
            if (name.trim().equals(assetName)) return parts[0].trim();
        }
        return null;
    }

    private static byte[] bytes(PartsTransport transport, String url, boolean missingMeansNotPublished)
            throws IOException {
        return transport.get(URI.create(url), 0, (status, length, body) -> {
            if (status == 404 && missingMeansNotPublished) throw new NotPublished(url + " is not published");
            if (status == 404) throw new IOException("The release is not signed: " + url + " is missing");
            if (status != 200) throw new IOException("HTTP " + status + " for " + url);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = body.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException(url + " is larger than any release file should be");
            }
            return out.toByteArray();
        });
    }
}
