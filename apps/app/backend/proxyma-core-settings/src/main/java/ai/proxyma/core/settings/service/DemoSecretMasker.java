package ai.proxyma.core.settings.service;

import ai.proxyma.core.settings.dto.SettingsSecrets;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Takes the credentials out of a settings response on the public Enterprise sandbox (and of a
 * write-only secret everywhere - see SettingsController).
 *
 * <p>The sandbox lets a visitor READ every settings screen, signed in as one of its SHARED accounts,
 * because a settings screen nobody can open shows nothing about what the product does.
 * {@code GET /api/v1/settings/{prefix}} returns what {@code SettingsService} has already
 * DECRYPTED - so without this it hands a stranger the last visitor's Jira token in a network tab.
 * The name is from the public demo (removed 2026-10-09), which it was written for.
 *
 * <p>DISPLAY ONLY, and never a substitute for the write filter. Nothing here decides whether a
 * value may be changed; {@code SandboxGuardFilter} does that.
 *
 * <p>TWO NETS, and the second is the one that matters. The first is
 * {@link SettingsSecrets#isEncryptedField} - the annotation, which is the contract the write paths
 * consult. The second is the field NAME, in case a credential-shaped field is ever added without
 * the annotation ({@code guardrail.canary-token} was one until 2026-10-14). Over-masking costs a
 * reader nothing on a sandbox; under-masking is the incident, so the two nets are deliberately not
 * the same net twice.
 *
 * <p>THE NAME TEST MATCHES WHOLE SEGMENTS, not substrings. Settings fields are hyphenated, so
 * {@code max-output-tokens} splits to {@code [max, output, tokens]} and is left alone, while
 * {@code canary-token} splits to {@code [canary, token]} and is masked. A plain
 * {@code contains("token")} would blank a token LIMIT and make the screen look broken, which is
 * how an over-eager mask ends up being switched off.
 */
public final class DemoSecretMasker {

    private DemoSecretMasker() {}

    /** What a masked value renders as. Not empty: a blank field reads as "not configured". */
    public static final String MASK = "********";

    private static final Set<String> SECRET_SEGMENTS = Set.of(
            "key", "keys", "token", "secret", "password", "passphrase", "credential", "credentials");

    /**
     * A copy of {@code fields} with every secret value replaced.
     *
     * <p>A null or blank value is left ALONE. "Not set" and "set, and you may not see it" are
     * different answers, and replacing an empty field with a row of asterisks would tell an
     * operator reading the screen that a credential exists where none does.
     */
    public static Map<String, Object> mask(String prefix, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) return fields;
        Map<String, Object> out = new LinkedHashMap<>(fields.size());
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            Object value = e.getValue();
            boolean present = value != null && !String.valueOf(value).isBlank();
            out.put(e.getKey(), present && isSecret(prefix, e.getKey()) ? MASK : value);
        }
        return out;
    }

    private static boolean isSecret(String prefix, String field) {
        if (SettingsSecrets.isEncryptedField(prefix, field)) return true;
        for (String segment : field.toLowerCase().split("[-_.]")) {
            if (SECRET_SEGMENTS.contains(segment)) return true;
        }
        return false;
    }
}
