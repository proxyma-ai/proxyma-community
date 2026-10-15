package ai.proxyma.core.security;

import com.sun.jna.platform.win32.Crypt32Util;

import java.nio.file.Files;
import java.nio.file.Path;

// Stores the AES-256 key as a DPAPI-encrypted blob on disk.
// Bound to the current Windows user - copying the blob to another machine yields nothing.
class WindowsDpapiKeyProtection implements OsKeyProtection {

    private final Path blobFile;

    WindowsDpapiKeyProtection(Path blobFile) {
        this.blobFile = blobFile;
    }

    @Override
    public boolean isAvailable() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Override
    public void storeKey(byte[] rawKey) throws Exception {
        byte[] blob = Crypt32Util.cryptProtectData(rawKey);
        Files.createDirectories(blobFile.getParent());
        Files.write(blobFile, blob);
    }

    @Override
    public byte[] loadKey() throws Exception {
        if (!Files.exists(blobFile)) return null;
        return Crypt32Util.cryptUnprotectData(Files.readAllBytes(blobFile));
    }
}
