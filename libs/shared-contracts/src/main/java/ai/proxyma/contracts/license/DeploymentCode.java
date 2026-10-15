package ai.proxyma.contracts.license;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * A deployment id written so a person can carry it from an air-gapped server to a connected
 * browser: the 128 bits of the UUID in Crockford base32 (26 symbols, no I, L, O or U), one check
 * symbol, grouped in fives - {@code XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XX}.
 *
 * <p>The check symbol is Luhn mod 32 over the symbols, so a single mistyped symbol and two adjacent
 * symbols swapped are both caught before the code reaches the license server. Reading is forgiving
 * the way Crockford intends: case, spaces and hyphens do not matter, and I and L read as 1, O as 0.
 *
 * <p>Here rather than on either side because both need the same answer - Enterprise Server shows
 * the code, the license server's trial-key endpoint reads it back - and it has no dependency of its
 * own.
 */
public final class DeploymentCode {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int SYMBOLS = 26;

    private DeploymentCode() {}

    /** The code for a deployment id. */
    public static String encode(String deploymentId) {
        UUID id = UUID.fromString(deploymentId.trim());
        BigInteger value = new BigInteger(1, toBytes(id));
        char[] symbols = new char[SYMBOLS];
        for (int i = SYMBOLS - 1; i >= 0; i--) {
            symbols[i] = ALPHABET.charAt(value.and(BigInteger.valueOf(31)).intValue());
            value = value.shiftRight(5);
        }
        String body = new String(symbols) + check(new String(symbols));
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            if (i > 0 && i % 5 == 0) grouped.append('-');
            grouped.append(body.charAt(i));
        }
        return grouped.toString();
    }

    /** The deployment id a code names, or empty when it is not a well-formed code with a right check. */
    public static Optional<String> decode(String code) {
        if (code == null) return Optional.empty();
        StringBuilder clean = new StringBuilder();
        for (char c : code.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '-' || Character.isWhitespace(c)) continue;
            if (c == 'I' || c == 'L') c = '1';
            if (c == 'O') c = '0';
            if (ALPHABET.indexOf(c) < 0) return Optional.empty();
            clean.append(c);
        }
        if (clean.length() != SYMBOLS + 1) return Optional.empty();
        String body = clean.substring(0, SYMBOLS);
        if (check(body) != clean.charAt(SYMBOLS)) return Optional.empty();
        BigInteger value = BigInteger.ZERO;
        for (char c : body.toCharArray()) value = value.shiftLeft(5).or(BigInteger.valueOf(ALPHABET.indexOf(c)));
        if (value.bitLength() > 128) return Optional.empty();
        byte[] raw = value.toByteArray();
        byte[] bytes = new byte[16];
        int copy = Math.min(raw.length, 16);
        System.arraycopy(raw, raw.length - copy, bytes, 16 - copy, copy);
        long high = 0;
        long low = 0;
        for (int i = 0; i < 8; i++) high = (high << 8) | (bytes[i] & 0xff);
        for (int i = 8; i < 16; i++) low = (low << 8) | (bytes[i] & 0xff);
        return Optional.of(new UUID(high, low).toString());
    }

    // Luhn mod N: from the right, every second symbol's value doubled and its base-N digits summed.
    static char check(String body) {
        int factor = 2;
        int sum = 0;
        for (int i = body.length() - 1; i >= 0; i--) {
            int addend = factor * ALPHABET.indexOf(body.charAt(i));
            factor = factor == 2 ? 1 : 2;
            sum += addend / 32 + addend % 32;
        }
        return ALPHABET.charAt((32 - sum % 32) % 32);
    }

    private static byte[] toBytes(UUID id) {
        byte[] bytes = new byte[16];
        long high = id.getMostSignificantBits();
        long low = id.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (high & 0xff);
            high >>>= 8;
            bytes[8 + i] = (byte) (low & 0xff);
            low >>>= 8;
        }
        return bytes;
    }
}
