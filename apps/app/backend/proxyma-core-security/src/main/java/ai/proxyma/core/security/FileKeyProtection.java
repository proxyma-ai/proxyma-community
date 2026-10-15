package ai.proxyma.core.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

// Stores the raw key bytes in a plain file.
//
// THE LINUX KEY STORE, every Linux install, since 0.10.0 (CredentialEncryption.selectProtection uses
// DPAPI on Windows). It used to be a fallback for machines without keyctl, while the rest used the
// kernel keyring - which loses the key at reboot. See LinuxKeyringKeyProtection.
//
// The bytes are the AES-256 key that every stored connector credential is encrypted with, so the
// file is exactly as sensitive as the credentials themselves. It is created 0600 and re-tightened
// on every load - see restrictToOwner.
class FileKeyProtection implements OsKeyProtection {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private final Path keyFile;

    FileKeyProtection(Path keyFile) {
        this.keyFile = keyFile;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void storeKey(byte[] rawKey) throws Exception {
        Files.createDirectories(keyFile.getParent());
        // Create the file EMPTY and already 0600, then write into it. Writing first and chmod'ing
        // afterwards would publish the key at the umask default - 0644 on a stock Ubuntu, i.e.
        // readable by every account on the machine - for the window in between. createFile's
        // permissions are applied at creation by the OS, so no such window exists.
        if (supportsPosix() && !Files.exists(keyFile)) {
            Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        Files.write(keyFile, rawKey);
        restrictToOwner();
    }

    @Override
    public byte[] loadKey() throws Exception {
        if (!Files.exists(keyFile)) return null;
        // Also on the read path, so an install that already wrote a world-readable key before this
        // was fixed is repaired the next time Proxyma starts rather than staying exposed forever.
        restrictToOwner();
        byte[] key = Files.readAllBytes(keyFile);
        return key.length == 32 ? key : null;
    }

    // Best-effort: a filesystem with no POSIX permissions (a Windows path, an exFAT stick, some
    // network mounts) cannot express this, and failing to narrow permissions must not stop Proxyma
    // from starting - the alternative is an application that refuses to run rather than one whose
    // key file is as protected as the filesystem allows.
    private void restrictToOwner() {
        if (!supportsPosix()) return;
        try {
            Files.setPosixFilePermissions(keyFile, OWNER_ONLY);
        } catch (Exception ignored) {
            // Not ours to own, or a filesystem that reports posix support and then refuses.
        }
    }

    private boolean supportsPosix() {
        return keyFile.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
