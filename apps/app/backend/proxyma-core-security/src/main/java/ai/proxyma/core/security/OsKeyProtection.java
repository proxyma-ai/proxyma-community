package ai.proxyma.core.security;

interface OsKeyProtection {
    boolean isAvailable();
    void storeKey(byte[] rawKey) throws Exception;
    byte[] loadKey() throws Exception;
}
