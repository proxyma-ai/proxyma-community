package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which {@code prefix.field} pairs carry {@link EncryptedField}, for the settings WRITE paths.
 *
 * <p>Exists because {@code SettingsService} takes a prefix and a field name as strings and has no
 * class to reflect over, which is the whole reason it encrypted on a name test
 * ({@code equals("api-key")}) instead of on the annotation - see SEC-31. The annotation is the
 * contract the class header advertises, so the annotation is what the write paths now consult.
 *
 * <p><b>This is deliberately STRICTER than
 * {@code SettingsPrefixRegistry.isSecret}, and the difference is load-bearing.</b> That one answers
 * "must this stay out of an export file" and treats an unknown prefix as secret - correct when the
 * cost of a wrong answer is an over-wrapped envelope. Here a wrong answer CORRUPTS a setting:
 *
 * <ul>
 *   <li>{@code SettingsService.getSettings(prefix, class)} decrypts a field only when it carries
 *       {@link EncryptedField}. Encrypting anything else would store {@code ENC(...)} and hand that
 *       literal string back to the caller.
 *   <li>An unknown prefix must answer FALSE. Encrypting a field nothing will decrypt is the same
 *       corruption, and "fail safe" here means leaving the value legible, not making it unreadable.
 * </ul>
 *
 * <p>The read side does not use this class at all - it decrypts on CredentialEncryption's
 * {@code ENC(} marker, which needs no registry and cannot be desynchronized from one.
 *
 * <p>ADDING A PREFIX. A DTO missing from {@link #PREFIX_CLASSES} silently keeps writing its
 * secrets in clear text. {@code SettingsPrefixRegistry} reads this same map, so a prefix added
 * here is also picked up by export/import - one list, not two. A module outside the Community
 * edition adds its own through {@link SettingsPrefixContributor}, into this same map.
 */
public final class SettingsSecrets {

    private SettingsSecrets() {}

    /**
     * Every prefix the generic settings store holds, and the class describing its fields.
     *
     * <p>A hand-written literal rather than a classpath scan, for the reason
     * {@code SettingsPrefixRegistry} gives: a scan silently starts covering a DTO the moment it
     * exists, and both consumers of this map make security decisions from it.
     */
    public static final Map<String, Class<?>> PREFIX_CLASSES = buildPrefixClasses();

    private static Map<String, Class<?>> buildPrefixClasses() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        m.put(ChatSettings.PREFIX, ChatSettings.class);
        m.put(EmbeddingSettings.PREFIX, EmbeddingSettings.class);
        m.put(QueryExpansionSettings.PREFIX, QueryExpansionSettings.class);
        m.put(RerankingSettings.PREFIX, RerankingSettings.class);
        m.put(SubAgentSettings.PREFIX, SubAgentSettings.class);
        m.put(GraphSettings.PREFIX, GraphSettings.class);
        m.put(GuardrailSettings.PREFIX, GuardrailSettings.class);
        m.put(OcrSettings.PREFIX, OcrSettings.class);
        // Connectors. Every one of these carries a credential.
        m.put(JiraSettings.PREFIX, JiraSettings.class);
        m.put(ConfluenceSettings.PREFIX, ConfluenceSettings.class);
        m.put(BitbucketSettings.PREFIX, BitbucketSettings.class);
        m.put(SvnSettings.PREFIX, SvnSettings.class);
        // The deployment's list of allowed file servers. A user's domain account is a Network
        // share CONNECTION since 2026-10-01, encrypted by the connection service.
        m.put(NetworkShareSettings.PREFIX, NetworkShareSettings.class);
        m.put(WebSearchSettings.PREFIX, WebSearchSettings.class);
        // Deployment behaviour.
        m.put(DocumentConversionSettings.PREFIX, DocumentConversionSettings.class);
        m.put(ProxySettings.PREFIX, ProxySettings.class);
        m.put(RetentionSettings.PREFIX, RetentionSettings.class);
        m.put(StorageSettings.PREFIX, StorageSettings.class);
        m.put(QuotaSettings.PREFIX, QuotaSettings.class);
        m.put(CostCenterSettings.PREFIX, CostCenterSettings.class);
        m.put(UpdatesSettings.PREFIX, UpdatesSettings.class);
        m.put(PrivacySettings.PREFIX, PrivacySettings.class);
        m.put(SpaceFactSettings.PREFIX, SpaceFactSettings.class);
        m.put(EvaluationSettings.PREFIX, EvaluationSettings.class);
        m.put(OthersSettings.PREFIX, OthersSettings.class);
        // "org" is gone since 2026-10-14, with org mode itself: a vestigial switch nothing read
        // except the single-user editions' Host check and CORS, which it turned OFF - and a
        // configuration import could set it.
        m.put(BackupScheduleSettings.PREFIX, BackupScheduleSettings.class);
        m.put(MaintenanceAnnouncementSettings.PREFIX, MaintenanceAnnouncementSettings.class);
        m.put(OnboardingSettings.PREFIX, OnboardingSettings.class);
        m.put(ConnectorToolSettings.PREFIX, ConnectorToolSettings.class);
        // The prefixes of the modules this edition has beyond these - on Enterprise Server the
        // Microsoft 365 app registration (m365) and its sources' per-user prefixes. Registered by
        // name in each module's service file (SettingsPrefixContributor), and never allowed to
        // replace a prefix above.
        for (SettingsPrefixContributor c : java.util.ServiceLoader.load(SettingsPrefixContributor.class,
                SettingsSecrets.class.getClassLoader())) {
            for (Map.Entry<String, Class<?>> e : c.prefixes().entrySet()) {
                if (m.putIfAbsent(e.getKey(), e.getValue()) != null) {
                    throw new IllegalStateException("Settings prefix " + e.getKey() + " is already "
                            + m.get(e.getKey()).getName() + "'s; " + c.getClass().getName() + " cannot take it");
                }
            }
        }
        return Map.copyOf(m);
    }

    /** The DTO describing a prefix, or null for one this map does not know. */
    public static Class<?> classFor(String prefix) {
        // Null-checked because PREFIX_CLASSES is an immutable map, and those THROW on get(null)
        // rather than returning null. Both callers here sit on a settings write path that
        // ConfigTransferService drives from a user-supplied import file, so "unknown" has to be
        // an answer rather than an exception.
        return prefix == null ? null : PREFIX_CLASSES.get(prefix);
    }

    /**
     * True only when this exact field carries {@link EncryptedField} on a KNOWN prefix.
     *
     * Unknown prefix, unknown field, or any other annotation: false. See the class note for why
     * that is the safe direction here even though the export registry answers the other way.
     */
    public static boolean isEncryptedField(String prefix, String field) {
        if (field == null) return false;
        Class<?> type = classFor(prefix);
        if (type == null) return false;
        Field f = fieldFor(type, field);
        return f != null && f.isAnnotationPresent(EncryptedField.class);
    }

    /**
     * The field of a settings DTO that a settings key ("api-key") names, or null.
     *
     * <p>THE ONE RESOLVER. There were three - here, in SettingsService and in the export registry -
     * and they disagreed at the edges: one matched {@code @JsonProperty}, the others the camel-cased
     * key. The annotation contract (SEC-31) is decided by WHICH field a key resolves to, so a key
     * that resolved differently on the write path and the read path would be encrypted by one and
     * not decrypted by the other. The {@code @JsonProperty} name is what the store holds, so it is
     * asked first; the camel-cased key and the Java name cover a DTO field with no annotation.
     */
    public static Field fieldFor(Class<?> type, String settingsKey) {
        if (type == null || settingsKey == null) return null;
        String camel = toCamelCase(settingsKey);
        Field byName = null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                JsonProperty jp = f.getAnnotation(JsonProperty.class);
                if (jp != null && settingsKey.equals(jp.value())) return f;
                if (byName == null && (f.getName().equals(camel) || f.getName().equalsIgnoreCase(settingsKey))) {
                    byName = f;
                }
            }
        }
        return byName;
    }

    /** Whether a settings key names a property of this prefix's DTO. False for an unknown prefix. */
    public static boolean isKnownField(String prefix, String field) {
        return fieldFor(classFor(prefix), field) != null;
    }

    private static String toCamelCase(String key) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '-' || c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
