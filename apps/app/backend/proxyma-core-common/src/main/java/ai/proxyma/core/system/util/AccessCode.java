package ai.proxyma.core.system.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/**
 * The access code of a single-user install - Desktop and the Home Server - and the two credentials
 * derived from it.
 *
 * <p>THESE EDITIONS HAVE NO ACCOUNTS, AND REACHING THE PORT USED TO BE ALL IT TOOK. The listener
 * bound every interface and the only check was the Host header, which any client sets to whatever
 * it likes - so a machine on the same network, or another account on the same computer, could
 * export every stored credential, read any folder and drive the agent's tools (editions-assembly-01,
 * 2026-10-14). Every API call now carries a credential made from this code:
 *
 * <ul>
 *   <li>{@link #sessionValue} - the value of the browser's HttpOnly access cookie, set once the code
 *       has been presented (WebConfig, AccessGate);</li>
 *   <li>{@link #bearerToken} - a {@code pxm_} token for MCP clients and scripts, shaped like an
 *       Enterprise Server API token so the guardrail's secret pattern recognizes it too.</li>
 * </ul>
 *
 * <p>The code itself is 256 random bits, written on the first start to {@value #FILE_NAME} in the
 * data directory, readable by its owner only on POSIX. On Windows the file sits in the user's own
 * %LOCALAPPDATA%, which other standard accounts cannot read; it is a plain file rather than a DPAPI
 * blob because the LAUNCHER reads it too, and the launcher carries no JNA. The Home Server can be
 * given its own code with PROXYMA_ACCESS_CODE instead.
 *
 * <p>No Spring here: the launcher jar shades this class (see the desktop pom's shade includes).
 */
public final class AccessCode {

    /** The file in the data directory that holds a generated code. */
    public static final String FILE_NAME = "access-code";

    /**
     * The shortest code accepted from configuration. A generated one is 43 characters; an operator
     * choosing their own gets to pick something memorable, but not something guessable in an
     * afternoon of requests.
     */
    public static final int MIN_LENGTH = 16;

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final SecureRandom RANDOM = new SecureRandom();

    private AccessCode() {}

    /**
     * The code in force: {@code configured} when it is set, else the one in the data directory,
     * created on first use.
     *
     * @throws IllegalArgumentException a configured code shorter than {@link #MIN_LENGTH}
     */
    public static String resolve(String configured, Path dataRoot) throws IOException {
        if (configured != null && !configured.isBlank()) {
            String code = configured.trim();
            if (code.length() < MIN_LENGTH) {
                throw new IllegalArgumentException("PROXYMA_ACCESS_CODE must be at least " + MIN_LENGTH
                        + " characters long");
            }
            return code;
        }
        return loadOrCreate(dataRoot);
    }

    /** The code stored in {@code dataRoot}, generating and storing a new one when there is none. */
    public static String loadOrCreate(Path dataRoot) throws IOException {
        Path file = dataRoot.resolve(FILE_NAME);
        String existing = read(file);
        if (existing != null) return existing;

        Files.createDirectories(dataRoot);
        String code = generate();
        // Written beside the target and moved into place, so a reader never sees a half-written
        // file. Created owner-only from the start where the filesystem can say so: writing first
        // and narrowing afterwards would publish the code at the umask default for the moment in
        // between - the window FileKeyProtection closes the same way.
        Path tmp = supportsPosix(dataRoot)
                ? Files.createTempFile(dataRoot, ".access-code", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                : Files.createTempFile(dataRoot, ".access-code", ".tmp");
        try {
            Files.writeString(tmp, code + "\n", StandardCharsets.US_ASCII);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return code;
    }

    /** The access cookie's value. Not the code itself, so a cookie read from a profile is not it. */
    public static String sessionValue(String code) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(code, "proxyma-access-session-v1"));
    }

    /** {@code pxm_} and 64 hex characters, for {@code Authorization: Bearer} on /mcp and /api. */
    public static String bearerToken(String code) {
        return "pxm_" + HexFormat.of().formatHex(hmac(code, "proxyma-access-token-v1"));
    }

    /** Constant-time comparison, so a wrong guess does not say how much of it was right. */
    public static boolean matches(String presented, String expected) {
        if (presented == null || expected == null) return false;
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /** 256 random bits, URL-safe so it can travel in a query string untouched. */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // A file too short to be a code is treated as absent and replaced: an empty file is what an
    // interrupted write on a filesystem without atomic moves leaves behind.
    private static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        restrictToOwner(file);
        String code = Files.readString(file, StandardCharsets.US_ASCII).trim();
        return code.length() >= MIN_LENGTH ? code : null;
    }

    // Also on the read path, so a file that was copied in or restored with looser permissions is
    // narrowed the next time Proxyma starts. Best-effort, for the reason FileKeyProtection gives.
    private static void restrictToOwner(Path file) {
        if (!supportsPosix(file)) return;
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (Exception ignored) {
            // Not ours to change, or a filesystem that reports POSIX support and then refuses.
        }
    }

    private static boolean supportsPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static byte[] hmac(String code, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
