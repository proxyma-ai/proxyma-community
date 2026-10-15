package ai.proxyma.core.settings.provider;

import ai.proxyma.core.settings.service.SecretMask;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which of a provider's custom headers carry a credential, and how one leaves the server.
 *
 * <p>Headers exist for relays and gateways, and the common case is a credential: an
 * {@code Authorization} or {@code X-Api-Key} for a relay, a gateway's tenant key. They used to be
 * stored and returned in clear while the API key beside them was encrypted and masked (isd-02), and
 * a "no secrets" configuration export carried them verbatim (settings-core-04).
 *
 * <p>THE SAME RULE AS THE PAGE. {@code headerValue.ts} decides by the header's NAME which values to
 * show by their ends, and this pattern is a copy of its {@code SECRET_NAME}: a name the page masks
 * is a name the server treats as a credential. The VALUE cannot decide - a key and the value of
 * {@code HTTP-Referer} can look alike.
 */
public final class ProviderHeaders {

    private static final Pattern SECRET_NAME = Pattern.compile(
            "(auth|token|secret|password|passwd|cookie|session|signature|credential|api[-_]?key|(^|[-_])key($|[-_]))",
            Pattern.CASE_INSENSITIVE);

    // "Bearer sk-..." keeps its scheme word: it says what kind of credential it is and nothing about it.
    private static final Pattern SCHEME = Pattern.compile("^(Bearer|Basic|Token|Bot)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);

    private ProviderHeaders() {}

    /** Whether a header of this name carries a credential. */
    public static boolean isSecret(String name) {
        return name != null && SECRET_NAME.matcher(name.trim()).find();
    }

    /** A credential's value as the API returns it - by its ends, around {@code ***}. */
    public static String maskValue(String value) {
        if (value == null || value.isBlank()) return "";
        String v = value.trim();
        Matcher m = SCHEME.matcher(v);
        if (m.matches()) return m.group(1) + " " + SecretMask.mask(m.group(2));
        return SecretMask.mask(v);
    }

    /** The headers as the API returns them: a credential masked, every other value as it is. */
    public static Map<String, String> masked(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((name, value) -> out.put(name, isSecret(name) ? maskValue(value) : value));
        return out;
    }

    /**
     * What to store when an edit sends {@code incoming}: the client never has a stored credential,
     * only its mask, so a credential sent back as its mask - or blank - keeps the stored value
     * rather than replacing a working key with asterisks. Matched by the header's name first, then
     * by the mask alone, so a credential header that was only RENAMED keeps its value too. Every
     * other value is taken as typed, and a header left out is removed.
     */
    public static Map<String, String> merge(Map<String, String> stored, Map<String, String> incoming) {
        Map<String, String> byName = new HashMap<>();
        Map<String, String> byMask = new HashMap<>();
        stored.forEach((name, value) -> {
            if (!isSecret(name) || value == null || value.isBlank()) return;
            byName.put(name.trim().toLowerCase(Locale.ROOT), value);
            byMask.putIfAbsent(maskValue(value), value);
        });
        Map<String, String> out = new LinkedHashMap<>();
        incoming.forEach((name, value) -> {
            if (name == null || name.isBlank()) return;
            String typed = value == null ? "" : value.trim();
            if (isSecret(name)) {
                String same = byName.get(name.trim().toLowerCase(Locale.ROOT));
                if (same != null && (typed.isEmpty() || typed.equals(maskValue(same)))) {
                    typed = same;
                } else if (byMask.containsKey(typed)) {
                    typed = byMask.get(typed);
                }
            }
            out.put(name.trim(), typed);
        });
        return out;
    }
}
