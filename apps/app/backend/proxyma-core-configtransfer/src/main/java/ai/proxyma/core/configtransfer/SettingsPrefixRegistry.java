package ai.proxyma.core.configtransfer;

import ai.proxyma.core.settings.dto.ConnectorToolSettings;
import ai.proxyma.core.settings.dto.EncryptedField;
import ai.proxyma.core.settings.dto.OnboardingSettings;
import ai.proxyma.core.settings.dto.SettingsSecrets;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which settings prefixes exist, what class describes each, and which of their fields are secret.
 *
 * <p>WHY THIS HAD TO BE WRITTEN. The settings store is a flat {@code prefix.field -> String} map
 * and every other consumer reaches it by naming one prefix and one class at the call site. Nothing
 * anywhere enumerated the set, so "export all of the configuration" had nothing to iterate.
 *
 * <p>WHY IT CANNOT BE SKIPPED BY SNIFFING THE STORED VALUE. Treating a value as secret when it
 * carries CredentialEncryption's {@code ENC(...)} marker would be wrong in the direction that
 * leaks: a value written before its field was annotated, or restored from somewhere that stored
 * it in clear, carries no marker. So the ANNOTATION is the authority, and the stored form is not
 * consulted at all.
 *
 * <p>ADDING A PREFIX. A new settings DTO must be added to {@code SettingsSecrets.PREFIX_CLASSES} or it is silently
 * left out of every export - the failure is a quiet omission rather than an error, which is the
 * argument for keeping this list next to nothing else and reading it top to bottom.
 */
public final class SettingsPrefixRegistry {

    private SettingsPrefixRegistry() {}

    /**
     * Every prefix the generic settings store holds, and the class that describes its fields.
     * Delegated to {@code SettingsSecrets} in proxyma-core-settings, which is where the settings
     * WRITE paths now read it from too (SEC-31).
     *
     * <p>It used to be a second hand-written copy of the same list. Two lists that must agree
     * about which prefixes exist is the drift this class's own header warns about, and the
     * consequence had teeth in both directions: a prefix missing here vanishes from exports, and
     * a prefix missing there keeps writing its credentials in clear text. One list.
     */
    private static Map<String, Class<?>> prefixClasses() {
        return SettingsSecrets.PREFIX_CLASSES;
    }

    /**
     * Prefixes that are never exported and never imported.
     *
     * <ul>
     *   <li>{@code appearance} / {@code branding} are fixed at deploy time by
     *       {@code proxyma.branding.*} and are not readable or writable through the settings API at
     *       all (SettingsController.BLOCKED_PREFIXES). Carrying them would describe a deployment
     *       the target cannot become.
     *   <li>{@code onboarding} and {@code connector-tools} are per-USER, not per-deployment: whether
     *       one person finished the walkthrough, and what that person permits the agent to do on
     *       their behalf. They belong to an account that does not exist on the other side, and
     *       {@code connector-tools} is a consent record - moving one would be granting a permission
     *       on someone else's behalf.
     * </ul>
     */
    public static final Set<String> EXCLUDED_PREFIXES =
            Set.of("appearance", "branding", OnboardingSettings.PREFIX, ConnectorToolSettings.PREFIX);

    /** Every prefix that takes part in a transfer, in the order an exported file lists them. */
    public static Set<String> exportablePrefixes() {
        Set<String> out = new LinkedHashSet<>(prefixClasses().keySet());
        out.removeAll(EXCLUDED_PREFIXES);
        return out;
    }

    /** The DTO describing a prefix, or null for one this registry does not know. */
    public static Class<?> classFor(String prefix) {
        return prefixClasses().get(prefix);
    }

    /**
     * True when this prefix/field pair must not travel in clear text.
     *
     * <p>Covers {@link EncryptedField} - API keys and access tokens.
     */
    public static boolean isSecret(String prefix, String field) {
        Class<?> type = prefixClasses().get(prefix);
        if (type == null) {
            // An unknown prefix is treated as secret rather than as plain. The store may hold keys
            // this registry has not been taught about - a prefix renamed, or a DTO added without
            // the list being updated - and the safe failure for an unknown credential-shaped value
            // is to lock it in the envelope, not to publish it.
            return true;
        }
        Field f = SettingsSecrets.fieldFor(type, field);
        return f != null && f.isAnnotationPresent(EncryptedField.class);
    }

    /** Normalizes a prefix from an untrusted file before it is used to look anything up. */
    public static String normalize(String prefix) {
        return prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
    }
}
