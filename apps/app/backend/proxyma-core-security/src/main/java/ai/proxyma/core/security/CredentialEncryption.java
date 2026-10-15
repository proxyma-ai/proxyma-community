package ai.proxyma.core.security;

import ai.proxyma.core.system.util.SystemInfo;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Function;
import java.util.logging.Logger;

// Pure Java (no Spring) AES-256-GCM encryption for credential fields.
// Usable from EnvironmentPostProcessor before any Spring beans are created.
public final class CredentialEncryption {

    private static final Logger log = Logger.getLogger(CredentialEncryption.class.getName());

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    static final String ENC_PREFIX = "ENC(";
    static final String ENC_SUFFIX = ")";

    private CredentialEncryption() {}

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }

    public static String encrypt(byte[] key, String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(iv)
                    + "." + Base64.getEncoder().encodeToString(ciphertext);
            return ENC_PREFIX + encoded + ENC_SUFFIX;
        } catch (Exception e) {
            throw new IllegalStateException("Credential encryption failed", e);
        }
    }

    public static String decrypt(byte[] key, String value) {
        if (!isEncrypted(value)) return value;
        try {
            String inner = value.substring(ENC_PREFIX.length(), value.length() - ENC_SUFFIX.length());
            String[] parts = inner.split("\\.", 2);
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Credential decryption failed for value starting with: "
                    + value.substring(0, Math.min(10, value.length())), e);
        }
    }

    // Loads or creates the 32-byte AES-256 key every stored credential and license.dat is encrypted with.
    //   Windows -> DPAPI (.secret.dpapi, bound to the current Windows user)
    //   Linux   -> a 0600 file (.secret) in the key directory
    //
    // THE LINUX KERNEL KEYRING IS NO LONGER WHERE THE KEY LIVES, since 0.10.0. It used to be, via
    // "keyctl add ... @u" - the USER keyring, which the kernel drops at reboot. The next start then
    // found no key, made a new one, and every saved credential and the activated license stopped
    // decrypting, silently. Only machines with keyutils installed took that path, and the Linux manual
    // recommended installing it. The key is a file now, as it already was wherever keyctl was absent.
    //
    // A key an earlier version put in the keyring is copied into the file the first time this runs,
    // as long as the machine has not rebooted since - after a reboot there is nothing left to copy.
    public static byte[] loadOrCreateKey(Path dataDir, SystemInfo systemInfo) {
        return loadOrCreateKey(dataDir, systemInfo, LinuxKeyringKeyProtection::loadIfPresent, System::getenv);
    }

    // The keyring read is a parameter so a test can stand in for keyctl, which this build cannot run.
    static byte[] loadOrCreateKey(Path dataDir, SystemInfo systemInfo, KeySource earlierKeyring) {
        return loadOrCreateKey(dataDir, systemInfo, earlierKeyring, name -> null);
    }

    // The environment is a parameter for the same reason: a test cannot set a variable on its own JVM.
    static byte[] loadOrCreateKey(Path dataDir, SystemInfo systemInfo, KeySource earlierKeyring,
                                  Function<String, String> environment) {
        OsKeyProtection protection = selectProtection(dataDir, systemInfo);
        byte[] injected = injectedKey(environment);
        if (injected != null) return adoptInjected(injected, protection, dataDir);
        try {
            // Migration: if an old plain .secret exists on Windows, move it into DPAPI.
            Path legacyFile = dataDir.resolve(".secret");
            if (Files.exists(legacyFile) && !(protection instanceof FileKeyProtection)) {
                byte[] legacyKey = Files.readAllBytes(legacyFile);
                if (legacyKey.length == 32) {
                    protection.storeKey(legacyKey);
                    Files.delete(legacyFile);
                    log.info("Migrated .secret to OS key store");
                    return legacyKey;
                }
            }

            byte[] existing = protection.loadKey();
            if (existing != null && existing.length == 32) return existing;
            setAsideUnreadableKey(protection instanceof FileKeyProtection ? legacyFile : dataDir.resolve(".secret.dpapi"));

            // The file wins when both exist: a file is only ever written where keyctl was absent,
            // so it is the key that encrypted this installation's data.
            if (protection instanceof FileKeyProtection) {
                byte[] fromKeyring = earlierKeyring.load();
                if (fromKeyring != null && fromKeyring.length == 32) {
                    protection.storeKey(fromKeyring);
                    log.info("Moved the credential key from the kernel keyring into " + legacyFile.getFileName());
                    return fromKeyring;
                }
            }

            byte[] newKey = new byte[32];
            new SecureRandom().nextBytes(newKey);
            protection.storeKey(newKey);
            return newKey;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to manage credential encryption key", e);
        }
    }

    // A key file that is there but holds no 32-byte key - truncated by a full disk, or half of a
    // partial restore. It used to be overwritten by the new key without a word, and every saved
    // credential and the activated license stopped decrypting for good. It is moved aside instead,
    // so restoring it from a backup can still bring them back, and the log says so.
    private static void setAsideUnreadableKey(Path keyFile) throws java.io.IOException {
        if (!Files.exists(keyFile)) return;
        Path aside = keyFile.resolveSibling(keyFile.getFileName() + ".unreadable-" + System.currentTimeMillis());
        Files.move(keyFile, aside);
        log.severe("The credential key file " + keyFile + " does not hold a key; it was moved to " + aside
                + " and a new key made. Credentials and the license saved under the old key will not "
                + "decrypt - put the file back from a backup and restart to recover them.");
    }

    /** The key as base64 text, for a container that keeps it in a secret rather than on its volume. */
    public static final String KEY_VARIABLE = "PROXYMA_CREDENTIAL_KEY";
    /** A file holding the key - 32 raw bytes, or their base64 - such as a mounted Kubernetes Secret. */
    public static final String KEY_FILE_VARIABLE = "PROXYMA_CREDENTIAL_KEY_FILE";

    // THE KEY CAN COME FROM OUTSIDE THE DATA VOLUME, for containers. On its own the key is a file the
    // first start writes into the volume, so a database or volume restored into a fresh one - a new
    // pod, a new host - had every saved provider key, connection secret and the license unreadable.
    // Given here, the key is the operator's: kept in a Secret and backed up apart from the data it
    // protects, never written to the volume. A blank value counts as unset, because Compose hands an
    // unset ${VAR:-} over as an empty string.
    static byte[] injectedKey(Function<String, String> environment) {
        String text = blankToNull(environment.apply(KEY_VARIABLE));
        String file = blankToNull(environment.apply(KEY_FILE_VARIABLE));
        if (text != null && file != null) {
            throw new IllegalStateException("Both " + KEY_VARIABLE + " and " + KEY_FILE_VARIABLE
                    + " are set. Set one of them.");
        }
        if (text != null) return decodeKey(text, KEY_VARIABLE);
        if (file == null) return null;
        try {
            byte[] raw = Files.readAllBytes(Path.of(file));
            if (raw.length == 32) return raw;
            return decodeKey(new String(raw, StandardCharsets.US_ASCII), KEY_FILE_VARIABLE + " (" + file + ")");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(KEY_FILE_VARIABLE + " names " + file + ", which cannot be read", e);
        }
    }

    private static byte[] decodeKey(String text, String source) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(text.trim());
        } catch (IllegalArgumentException e) {
            key = null;
        }
        if (key == null || key.length != 32) {
            throw new IllegalStateException(source + " must be the base64 of exactly 32 bytes "
                    + "(for example the output of: openssl rand -base64 32).");
        }
        return key;
    }

    // A key already stored on the volume that differs from the one given would make every credential
    // saved under it unreadable - silently, one "could not be decrypted" warning at a time. Refused
    // instead, while the stored key is still there to be carried over.
    private static byte[] adoptInjected(byte[] injected, OsKeyProtection protection, Path dataDir) {
        byte[] stored;
        try {
            stored = protection.loadKey();
        } catch (Exception e) {
            stored = null;
        }
        if (stored != null && stored.length == 32 && !MessageDigest.isEqual(stored, injected)) {
            throw new IllegalStateException("The credential key given in " + KEY_VARIABLE + " / " + KEY_FILE_VARIABLE
                    + " is not the key already stored in " + dataDir + ". Starting with it would leave every saved "
                    + "credential unreadable. Give the stored key instead (the base64 of the .secret file), or remove "
                    + "the stored key if nothing was ever saved under it.");
        }
        return injected;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @FunctionalInterface
    interface KeySource {
        byte[] load();
    }

    private static OsKeyProtection selectProtection(Path dataDir, SystemInfo systemInfo) {
        if (systemInfo.isWindows()) {
            return new WindowsDpapiKeyProtection(dataDir.resolve(".secret.dpapi"));
        }
        return new FileKeyProtection(dataDir.resolve(".secret"));
    }
}
