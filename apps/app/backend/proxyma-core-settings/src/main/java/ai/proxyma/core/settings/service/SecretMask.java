package ai.proxyma.core.settings.service;

/**
 * How a stored credential is shown back: its first and last few characters around {@code ***},
 * enough to tell two keys apart and not enough to copy one off a screen share.
 *
 * <p>One rule for every credential the API returns - a provider's key, a connection's token, a
 * provider header's value. It was written out twice, identically, in the two view types, beside
 * other spellings of the same idea (settings-connections-usage-04); the page's {@code headerValue.ts}
 * mirrors it for display.
 */
public final class SecretMask {

    private SecretMask() {}

    /** Null for no value; {@code ***} for one short enough that its ends would be most of it. */
    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) return null;
        String s = secret.trim();
        if (s.length() < 12) return "***";
        return s.substring(0, 4) + "***" + s.substring(s.length() - 3);
    }
}
