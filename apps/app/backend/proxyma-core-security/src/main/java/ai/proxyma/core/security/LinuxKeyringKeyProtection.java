package ai.proxyma.core.security;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

// READ-ONLY, AND ONLY FOR MIGRATION, since 0.10.0. Earlier versions stored the AES-256 key here, in
// the user keyring @u via keyctl - which the kernel clears at reboot, so the key and everything
// encrypted with it were lost on the next start. Nothing writes here any more; CredentialEncryption
// reads a key an earlier version left behind once, copies it into the key file, and never looks
// again. The class name and the OsKeyProtection shape are kept so that read stays exactly the one
// that used to work.
class LinuxKeyringKeyProtection implements OsKeyProtection {

    private static final String KEY_NAME = "proxyma:enc-key";
    private static final int TIMEOUT_SECONDS = 5;

    // The migration read: null whenever keyctl is missing, the key is not there, or anything fails.
    static byte[] loadIfPresent() {
        LinuxKeyringKeyProtection keyring = new LinuxKeyringKeyProtection();
        if (!keyring.isAvailable()) return null;
        try {
            return keyring.loadKey();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("keyctl", "version")
                    .redirectErrorStream(true).start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void storeKey(byte[] rawKey) {
        throw new UnsupportedOperationException("The kernel keyring does not survive a reboot - keys are stored in a file");
    }

    @Override
    public byte[] loadKey() throws Exception {
        Process search = new ProcessBuilder("keyctl", "search", "@u", "user", KEY_NAME)
                .redirectErrorStream(true).start();
        if (!search.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { search.destroyForcibly(); return null; }
        String keyId = new String(search.getInputStream().readAllBytes()).trim();
        if (search.exitValue() != 0 || keyId.isEmpty()) return null;

        Process print = new ProcessBuilder("keyctl", "print", keyId).start();
        if (!print.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { print.destroyForcibly(); return null; }
        String encoded = new String(print.getInputStream().readAllBytes()).trim();
        if (encoded.isEmpty()) return null;
        return Base64.getDecoder().decode(encoded);
    }
}
