package ai.proxyma.core.settings.service;

import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.security.CredentialEncryption;
import ai.proxyma.core.system.util.SystemInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// AES-256-GCM encryption for stored credentials: @EncryptedField settings values, provider keys and
// headers, connection secrets. The key lives in the OS key store: DPAPI on Windows, a 0600 file on
// Linux (see CredentialEncryption.loadOrCreateKey).
@Service
public class CredentialEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptionService.class);

    private final byte[] key;

    public CredentialEncryptionService(AppPaths appPaths, SystemInfo systemInfo) {
        this.key = CredentialEncryption.loadOrCreateKey(appPaths.encryptionKeyDir(), systemInfo);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        return CredentialEncryption.encrypt(key, plaintext);
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return ciphertext;
        return CredentialEncryption.decrypt(key, ciphertext);
    }

    /**
     * The stored value decrypted, or null when there is none or it will not decrypt.
     *
     * <p>A value that will not decrypt means the key changed under it - a restored backup, a data
     * directory copied to another machine, replicas with different keys. The useful outcome is
     * "this credential is not set, enter it again", not a page that fails on every read of
     * everything beside it. One copy of that rule; it was written out in every service that stores
     * a credential (settings-connections-usage-03).
     *
     * <p>Static over the service it is given, so a caller's tests can stand in for {@link #decrypt}
     * alone and this rule still runs.
     *
     * @param what names the value in the warning - never the value itself
     */
    public static String decryptOrNull(CredentialEncryptionService encryption, String stored, String what) {
        if (stored == null || stored.isBlank()) return null;
        try {
            return encryption.decrypt(stored);
        } catch (RuntimeException e) {
            log.warn("A stored credential ({}) could not be decrypted - treating it as unset. "
                    + "The encryption key has probably changed under it.", what);
            return null;
        }
    }
}
