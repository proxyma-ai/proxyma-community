package ai.proxyma.core.settings.service;

import ai.proxyma.core.settings.dto.BitbucketSettings;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.ConfluenceSettings;
import ai.proxyma.core.settings.dto.EmbeddingSettings;
import ai.proxyma.core.settings.dto.EncryptedField;
import ai.proxyma.core.settings.dto.SettingsSecrets;
import ai.proxyma.core.settings.dto.JiraSettings;
import ai.proxyma.core.settings.dto.ConnectorToolSettings;
import ai.proxyma.core.settings.dto.PrivacySettings;
import ai.proxyma.core.settings.dto.GraphSettings;
import ai.proxyma.core.settings.dto.GuardrailSettings;
import ai.proxyma.core.settings.dto.QueryExpansionSettings;
import ai.proxyma.core.settings.dto.OcrSettings;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.dto.NetworkShareSettings;
import ai.proxyma.core.settings.dto.SvnSettings;
import ai.proxyma.core.settings.event.DataCollectionChangedEvent;
import ai.proxyma.core.settings.event.ProviderSettingsChangedEvent;
import ai.proxyma.core.settings.global.GlobalSetting;
import ai.proxyma.core.settings.global.GlobalSettingRepository;
import ai.proxyma.core.settings.model.UserSettings;
import ai.proxyma.core.settings.repository.UserSettingsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

// Manages all global (deployment-wide) settings, persisted to the GLOBAL_SETTINGS database table
// (H2 on desktop, PostgreSQL on server) via GlobalSettingRepository. Per-user overrides live in
// UserSettings.
//
// Encryption contract:
//   @EncryptedField - AES-GCM (reversible): API keys, access tokens.
//                      Encrypted on every write path, decrypted on getSettings().
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    // GlobalSetting.SETTING_KEY's length: a longer key can never be stored.
    static final int MAX_KEY_CHARS = 256;
    // What one user's override blob may grow to. Every read of that user's settings parses all of
    // it (settings-core-08), and nothing a person sets by hand comes near this.
    static final int MAX_USER_BLOB_CHARS = 256 * 1024;

    private final ObjectMapper objectMapper;
    private final CredentialEncryptionService credentialEncryptionService;
    private final UserSettingsRepository userSettingsRepository;
    private final GlobalSettingRepository globalSettingRepository;
    private final ApplicationEventPublisher eventPublisher;
    // Null only in a test built by hand; every edition defines chatTransactionManager, which is the
    // manager the GLOBAL_SETTINGS repository is bound to in both (ChatDataSourceConfig,
    // ServerDatabaseConfig) - so a write's deletes and upserts commit together.
    private final TransactionTemplate tx;

    // Serializes the writers of the global store. Readers take no lock at all - see cachedProps.
    private final ReentrantLock writeLock = new ReentrantLock();
    // One lock per user, around the read-modify-write of their override blob: two saves at once
    // (two tabs, or the chat's tool prompt beside the settings page) each wrote back the blob they
    // had read, and one field was lost (settings-core-28).
    private final Map<String, Object> userLocks = new ConcurrentHashMap<>();

    // Every prefix that maps to a model role LlmProviderFactory builds and caches. A role missing
    // from this set is only ever built at startup: saving its settings writes them but never fires
    // the reinit, so the factory keeps serving the old model (or null) until the app restarts.
    private static final Set<String> MODEL_ROLE_PREFIXES = Set.of(
            ChatSettings.PREFIX, EmbeddingSettings.PREFIX,
            QueryExpansionSettings.PREFIX, RerankingSettings.PREFIX,
            SubAgentSettings.PREFIX, GraphSettings.PREFIX,
            // Same reason as every other role here: without this line, saving a guardrail model
            // writes the setting and never fires the reinit, so the factory keeps serving null and
            // the classifier stays off until the next restart - with the UI showing it as on.
            GuardrailSettings.PREFIX,
            // "ocr" is a model role only for its vision engine; saving ocr.engine=tesseract or a
            // tesseract-only field still publishes the reinit, which just rebuilds nothing (see
            // LlmProviderFactory.initOcr) - harmless, and cheaper than special-casing per field.
            OcrSettings.PREFIX);

    // THE AUTHORITATIVE IN-MEMORY COPY OF GLOBAL_SETTINGS, and it is NEVER MUTATED. A write builds
    // the next copy, persists only what differs, and publishes the copy once the database has it.
    //
    // It used to be one object every writer changed in place and then stored whole (settings-core-02):
    // a write the database refused - a field name over the key's 256 characters - stayed in the
    // cache, so every later save of anything re-sent it and failed too, until a restart; a write that
    // failed on a transient error was live in memory while the page said "Failed to save"; and a
    // reader iterating it could see a two-key change half made. Each save also rewrote every row and
    // deleted every row the cache did not know about, which is how a restore or another replica's
    // keys were silently undone.
    private volatile Properties cachedProps = null;

    public SettingsService(ObjectMapper objectMapper,
                           CredentialEncryptionService credentialEncryptionService,
                           ApplicationEventPublisher eventPublisher,
                           GlobalSettingRepository globalSettingRepository,
                           @Autowired(required = false) UserSettingsRepository userSettingsRepository,
                           @Autowired(required = false) @Qualifier("chatTransactionManager")
                           PlatformTransactionManager transactionManager) {
        this.objectMapper = objectMapper;
        this.credentialEncryptionService = credentialEncryptionService;
        this.eventPublisher = eventPublisher;
        this.globalSettingRepository = globalSettingRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.tx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    // - Core read/write -

    // Reads all keys under prefix and deserializes into targetClass.
    // @EncryptedField values are decrypted before deserialization; one that will not decrypt is
    // read as unset rather than failing the whole prefix (settings-core-07).
    // Returns a default instance when no stored keys exist.
    public <T> T getSettings(String prefix, Class<T> targetClass) {
        Properties props = loadFile();

        Map<String, Object> dataMap = new HashMap<>();
        String prefixDot = prefix + ".";
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(prefixDot)) {
                String shortKey = key.substring(prefixDot.length());
                String rawValue = props.getProperty(key);
                Field field = SettingsSecrets.fieldFor(targetClass, shortKey);
                if (field != null && field.isAnnotationPresent(EncryptedField.class)) {
                    rawValue = CredentialEncryptionService.decryptOrNull(credentialEncryptionService, rawValue, key);
                    if (rawValue == null) continue;
                }
                dataMap.put(shortKey, rawValue);
            }
        }

        try {
            return objectMapper.convertValue(dataMap, targetClass);
        } catch (IllegalArgumentException e) {
            return withoutUnreadableKeys(prefix, targetClass, dataMap, e);
        }
    }

    /**
     * Converts again with only the keys that can be read, rather than throwing the prefix away.
     *
     * <p>THIS USED TO RETURN {@code defaultInstance(targetClass)} ON ANY FAILURE, and the blast
     * radius was the whole prefix. One unreadable value - a hand-edited properties file, a field
     * whose type changed between versions, an enum constant that has since been renamed - and
     * every setting under that prefix silently reverted to its default. For {@code guardrail} that
     * is the mode, both violation actions and every detector at once; the operator sees a screen
     * of defaults, the file on disk still says otherwise, and the only trace is one WARN line that
     * names neither the key nor the value.
     *
     * <p>So the bad keys are found by converting each one ALONE and dropping the ones that throw.
     * That is an O(n) retry on a path that only runs when something is already wrong, over a
     * handful of keys. What comes back keeps every value that could be read, and the log names
     * exactly what was dropped - which is the difference between a fixable report and a mystery.
     *
     * <p>Still falls back to defaults if the surviving map cannot be converted either. That would
     * mean the failure is not about an individual value, and there is nothing better to return.
     */
    private <T> T withoutUnreadableKeys(String prefix, Class<T> targetClass,
                                        Map<String, Object> dataMap, IllegalArgumentException cause) {
        Map<String, Object> readable = new HashMap<>();
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
            try {
                objectMapper.convertValue(Map.of(entry.getKey(), entry.getValue()), targetClass);
                readable.put(entry.getKey(), entry.getValue());
            } catch (IllegalArgumentException single) {
                dropped.add(entry.getKey());
            }
        }

        if (dropped.isEmpty()) {
            // Every key converts on its own but not together, so the fault is not in one value.
            log.warn("Failed to deserialize settings prefix='{}' into {} and no single key explains "
                            + "it, returning defaults: {}",
                    prefix, targetClass.getSimpleName(), cause.getMessage());
            return defaultInstance(targetClass);
        }

        // WARN and not DEBUG: a dropped setting is a deployment behaving differently from its own
        // configuration file, and the key names are what makes that fixable.
        log.warn("Settings prefix='{}': {} unreadable key(s) ignored, the rest kept - {} ({})",
                prefix, dropped.size(), dropped, cause.getMessage());
        try {
            return objectMapper.convertValue(readable, targetClass);
        } catch (IllegalArgumentException stillBad) {
            log.warn("Settings prefix='{}' still unreadable after dropping {}, returning defaults: {}",
                    prefix, dropped, stillBad.getMessage());
            return defaultInstance(targetClass);
        }
    }

    /**
     * Persists a whole settings object under the given prefix - but only what differs from the
     * class's own defaults. @EncryptedField values are AES-GCM encrypted before writing.
     *
     * <p>A field equal to its default is REMOVED from the store rather than written (settings-core-13).
     * Writing every non-null field froze today's defaults into the deployment: the guardrail canary,
     * the typed role savers and a configuration import each wrote all of a DTO, so a default changed
     * in a later release never reached those installs, and the "absent key means the deployment's
     * default" convention (proxy.mode) was defeated for anything written this way. A null field is
     * left as it is.
     */
    public <T> void setSettings(String prefix, T settingsObject) {
        Map<String, Object> propertyMap = objectMapper.convertValue(
                settingsObject, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> defaults = objectMapper.convertValue(
                defaultInstance(settingsObject.getClass()), new TypeReference<Map<String, Object>>() {});
        String prefixDot = prefix + ".";
        write(props -> propertyMap.forEach((key, value) -> {
            if (value == null) return;
            String fullKey = prefixDot + key;
            String strValue = toPropertyString(value);
            Object fallback = defaults.get(key);
            if (fallback != null && strValue.equals(toPropertyString(fallback))) {
                props.remove(fullKey);
                return;
            }
            requireStorableKey(fullKey);
            Field field = SettingsSecrets.fieldFor(settingsObject.getClass(), key);
            if (field != null && field.isAnnotationPresent(EncryptedField.class)) {
                strValue = credentialEncryptionService.encrypt(strValue);
            }
            props.setProperty(fullKey, strValue);
        }));
    }

    // - Per-user settings (server mode) -

    // Reads settings with user-specific overrides layered on top of global defaults.
    // Resolution: global settings -> user JSON overlay.
    // Returns global settings when userId is null or repository is unavailable (desktop mode).
    public <T> T getSettingsForUser(String prefix, Class<T> targetClass, String userId) {
        T global = getSettings(prefix, targetClass);
        if (userId == null || userSettingsRepository == null) return global;

        return userSettingsRepository.findById(userId)
                .map(us -> overlayUserSettings(prefix, targetClass, global, us.getSettingsJson()))
                .orElse(global);
    }

    // Saves a per-user override for a single field. Does not modify global settings.
    public void saveUserField(String prefix, String field, String value, String userId) {
        if (userId == null || userSettingsRepository == null) {
            saveAiProviderField(prefix, field, value);
            return;
        }
        synchronized (userLock(userId)) {
            UserSettings entity = userSettingsRepository.findById(userId).orElseGet(() -> {
                UserSettings us = new UserSettings();
                us.setUserId(userId);
                us.setSettingsJson("{}");
                return us;
            });

            try {
                Map<String, Object> blob = objectMapper.readValue(
                        entity.getSettingsJson(), new TypeReference<Map<String, Object>>() {});
                // SEC-31: this path encrypted NOTHING - not even the api-key name test the global path
                // had - and it is the path that holds every user's connector credentials, because
                // ToolConnectorResolver requires each user to supply their own. Read side decrypts on
                // the marker, so plaintext already stored keeps working and re-encrypts here on its
                // next save.
                String stored = value;
                if (value != null && !value.isEmpty()
                        && SettingsSecrets.isEncryptedField(prefix, field)) {
                    stored = credentialEncryptionService.encrypt(value);
                }
                blob.put(prefix + "." + field, stored);
                String json = objectMapper.writeValueAsString(blob);
                if (json.length() > MAX_USER_BLOB_CHARS) {
                    throw new IllegalArgumentException("Your saved settings are too large to add to.");
                }
                entity.setSettingsJson(json);
                userSettingsRepository.save(entity);
            } catch (IOException e) {
                log.warn("Failed to save user settings for userId={}: {}", userId, e.getMessage());
            }
        }
    }

    // Deletes a per-user override for a single field. Falls back to global value.
    public void deleteUserField(String prefix, String field, String userId) {
        if (userId == null || userSettingsRepository == null) {
            deleteAiProviderField(prefix, field);
            return;
        }
        synchronized (userLock(userId)) {
            userSettingsRepository.findById(userId).ifPresent(entity -> {
                try {
                    Map<String, Object> blob = objectMapper.readValue(
                            entity.getSettingsJson(), new TypeReference<Map<String, Object>>() {});
                    blob.remove(prefix + "." + field);
                    entity.setSettingsJson(objectMapper.writeValueAsString(blob));
                    userSettingsRepository.save(entity);
                } catch (IOException e) {
                    log.warn("Failed to delete user field for userId={}: {}", userId, e.getMessage());
                }
            });
        }
    }

    private Object userLock(String userId) {
        return userLocks.computeIfAbsent(userId, k -> new Object());
    }

    // Returns user-scoped fields for a prefix, with global defaults for non-overridden keys.
    public Map<String, Object> getSettingsFieldsForUser(String prefix, String userId) {
        Map<String, Object> global = getSettingsFields(prefix);
        if (userId == null || userSettingsRepository == null) return global;

        Map<String, Object> result = new LinkedHashMap<>(global);
        result.putAll(loadUserOverrides(prefix, userId));
        return result;
    }

    /**
     * ONE USER'S OWN values for a prefix, with NO global fallback - for settings that must never be
     * inherited from anyone.
     *
     * <p>{@link #getSettingsFieldsForUser} layers the user's overrides on top of the global values,
     * which is right for a default server URL and wrong for an identity. A network share is read as
     * its owner's domain account so that the share's ACLs decide what is indexed; a global value
     * inherited here would read every user's shares as one account. The settings API cannot write
     * a global {@code smb} value any more, and this is the second net under that.
     *
     * <p>Desktop, where {@code userId} is null or there is no per-user store, has one user and the
     * global values ARE that user's own, so they are returned as they are.
     */
    public Map<String, Object> getOwnSettingsFieldsForUser(String prefix, String userId) {
        if (userId == null || userSettingsRepository == null) return getSettingsFields(prefix);
        return loadUserOverrides(prefix, userId);
    }

    private Map<String, Object> loadUserOverrides(String prefix, String userId) {
        if (userSettingsRepository == null) return Map.of();
        return userSettingsRepository.findById(userId).map(entity -> {
            try {
                Map<String, Object> blob = objectMapper.readValue(
                        entity.getSettingsJson(), new TypeReference<Map<String, Object>>() {});
                Map<String, Object> result = new LinkedHashMap<>();
                String prefixDot = prefix + ".";
                blob.forEach((key, value) -> {
                    if (key.startsWith(prefixDot)) {
                        result.put(key.substring(prefixDot.length()), decryptIfMarked(value));
                    }
                });
                return result;
            } catch (IOException e) {
                log.warn("Failed to parse user settings JSON for userId={}: {}", userId, e.getMessage());
                return Map.<String, Object>of();
            }
        }).orElse(Map.of());
    }

    private <T> T overlayUserSettings(String prefix, Class<T> targetClass, T base, String userJson) {
        try {
            Map<String, Object> baseMap = objectMapper.convertValue(base, new TypeReference<>() {});
            Map<String, Object> blob = objectMapper.readValue(
                    userJson, new TypeReference<Map<String, Object>>() {});
            String prefixDot = prefix + ".";
            blob.forEach((key, value) -> {
                if (key.startsWith(prefixDot) && value != null) {
                    baseMap.put(key.substring(prefixDot.length()), decryptIfMarked(value));
                }
            });
            return objectMapper.convertValue(baseMap, targetClass);
        } catch (Exception e) {
            log.warn("Failed to overlay user settings for prefix={}: {}", prefix, e.getMessage());
            return base;
        }
    }

    /**
     * Decrypts a per-user override value if it carries CredentialEncryption's marker, and returns
     * it untouched otherwise (SEC-31).
     *
     * <p>Marker-based rather than annotation-based ON PURPOSE. The read side has no registry to
     * fall out of step with, and - the property the whole fix rests on - it is a NO-OP against
     * every value already in the store. Per-user overrides have always been written in clear text,
     * so this can ship before the write side starts encrypting without touching a single stored
     * row. Doing it the other way round would make every existing credential unreadable.
     *
     * <p>Non-String values (numbers, booleans, nested objects in the settings blob) pass straight
     * through: only a String can carry the marker.
     */
    private Object decryptIfMarked(Object value) {
        if (!(value instanceof String s)) return value;
        if (!ai.proxyma.core.security.CredentialEncryption.isEncrypted(s)) return s;
        return CredentialEncryptionService.decryptOrNull(credentialEncryptionService, s, "a per-user setting");
    }

    // - Typed reads and the role savers with callers -

    public String getEmbeddingProvider() {
        return getSettings(EmbeddingSettings.PREFIX, EmbeddingSettings.class).getProvider();
    }

    public String getEmbeddingModel() {
        return getSettings(EmbeddingSettings.PREFIX, EmbeddingSettings.class).getModel();
    }

    public String getAgentModel() {
        return getSettings(ChatSettings.PREFIX, ChatSettings.class).getModel();
    }

    public void saveAgentProvider(String provider) {
        ChatSettings s = getSettings(ChatSettings.PREFIX, ChatSettings.class);
        s.setProvider(provider);
        setSettings(ChatSettings.PREFIX, s);
    }

    public void saveAgentModel(String model) {
        ChatSettings s = getSettings(ChatSettings.PREFIX, ChatSettings.class);
        s.setModel(model);
        setSettings(ChatSettings.PREFIX, s);
    }

    public void saveEmbeddingProvider(String provider) {
        EmbeddingSettings s = getSettings(EmbeddingSettings.PREFIX, EmbeddingSettings.class);
        s.setProvider(provider);
        setSettings(EmbeddingSettings.PREFIX, s);
    }

    public void saveEmbeddingModel(String model) {
        EmbeddingSettings s = getSettings(EmbeddingSettings.PREFIX, EmbeddingSettings.class);
        s.setModel(model);
        setSettings(EmbeddingSettings.PREFIX, s);
    }

    // - Connectors -

    public int getJiraSyncIntervalSeconds() {
        return getSettings(JiraSettings.PREFIX, JiraSettings.class).getSyncIntervalSeconds();
    }

    public int getConfluenceSyncIntervalSeconds() {
        return getSettings(ConfluenceSettings.PREFIX, ConfluenceSettings.class).getSyncIntervalSeconds();
    }

    public int getBitbucketSyncIntervalSeconds() {
        return getSettings(BitbucketSettings.PREFIX, BitbucketSettings.class).getSyncIntervalSeconds();
    }

    public int getSvnSyncIntervalSeconds() {
        return getSettings(SvnSettings.PREFIX, SvnSettings.class).getSyncIntervalSeconds();
    }

    public int getNetworkShareSyncIntervalSeconds() {
        return getSettings(NetworkShareSettings.PREFIX, NetworkShareSettings.class).getSyncIntervalSeconds();
    }

    /**
     * The deployment's optional list of file servers a Network share CONNECTION may name - a
     * ceiling on Enterprise Server, ignored on the single-user editions (see
     * ConnectorConnectionService.fileServerAllowedByPolicy). Blank means no ceiling since
     * 2026-10-01; it meant "network shares are off" while the list was the only boundary.
     *
     * <p>ALWAYS THE GLOBAL VALUE, never a per-user override - the prefix is admin-only, and this
     * reader must not grow a userId parameter.
     */
    public String getNetworkShareAllowedHosts() {
        return getSettings(NetworkShareSettings.PREFIX, NetworkShareSettings.class).getAllowedHosts();
    }

    // - Privacy -

    public boolean isDataCollectionAllowed() {
        return getSettings(PrivacySettings.PREFIX, PrivacySettings.class).isDataCollectionAllowed();
    }

    // - Connector management permission (per-user) -
    //
    // Registering and removing sources of any type. The per-service Read and Write switches that
    // used to sit beside it moved onto each connection (ConnectorConnection) - see McpGuardService.

    public boolean isUserConnectorManagementAllowed(String userId) {
        return getSettingsForUser(ConnectorToolSettings.PREFIX, ConnectorToolSettings.class, userId)
                .isConnectorManagement();
    }

    // - Generic prefix-based field access -

    public Map<String, Object> getSettingsFields(String prefix) {
        Map<String, Object> result = new LinkedHashMap<>();
        Properties props = loadFile();
        String prefixDot = prefix + ".";
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(prefixDot)) {
                String value = props.getProperty(key);
                if (ai.proxyma.core.security.CredentialEncryption.isEncrypted(value)) {
                    // One that will not decrypt reads as unset - not a 503 on every read of the
                    // prefix, which /api/v1/config turned into a broken page for everyone when the
                    // m365 client secret was the value (settings-core-07).
                    value = CredentialEncryptionService.decryptOrNull(credentialEncryptionService, value, key);
                    if (value == null) continue;
                }
                result.put(key.substring(prefixDot.length()), value);
            }
        }
        return result;
    }

    /**
     * Tells LlmProviderFactory a model role's settings changed, so it rebuilds that role's model.
     * saveAiProviderField does this on its own; the typed savers (saveAgentProvider and friends) do
     * NOT, so a caller writing a role through them at startup has to say so itself - see
     * DemoProviderSeeder, which answered nothing on the Server edition until it did.
     */
    public void announceRoleChanged(String prefix) {
        if (MODEL_ROLE_PREFIXES.contains(prefix)) {
            eventPublisher.publishEvent(ProviderSettingsChangedEvent.forRole(prefix));
        }
    }

    // Returns true when the field requires an application restart to take effect.
    public boolean saveAiProviderField(String prefix, String field, String value) {
        return saveFields(prefix, java.util.Collections.singletonMap(field, value));
    }

    /**
     * Writes several fields of one prefix at once: one write to the store, one reinit for a model
     * role, one consent event - where a configuration import used to cost a full-table rewrite and
     * a model rebuild per field (settings-core-12). Returns true when the prefix needs a restart.
     */
    public boolean saveFields(String prefix, Map<String, String> fields) {
        if (fields.isEmpty()) return false;
        write(props -> fields.forEach((field, value) -> {
            String key = prefix + "." + field;
            requireStorableKey(key);
            String storedValue = value != null ? value : "";
            // SEC-31: was `"api-key".equalsIgnoreCase(field)`, which encrypted web-search.api-key
            // by accident of its name and wrote every other @EncryptedField - four connector
            // credentials, three Azure values - verbatim. The annotation is the contract
            // getSettings() already decrypts on, so it is the contract this must write on.
            if (SettingsSecrets.isEncryptedField(prefix, field) && !storedValue.isEmpty()) {
                storedValue = credentialEncryptionService.encrypt(storedValue);
            }
            props.setProperty(key, storedValue);
        }));
        announceRoleChanged(prefix);
        String collection = fields.get("data-collection-allowed");
        if (PrivacySettings.PREFIX.equals(prefix) && collection != null) {
            eventPublisher.publishEvent(new DataCollectionChangedEvent(Boolean.parseBoolean(collection)));
        }
        return requiresRestart(prefix);
    }

    /**
     * Whether a change under this prefix takes effect only after a restart.
     *
     * <p>Only "proxy" is read once into a startup-built RestClient/WebClient bean
     * (RestClientConfig) - everything else, including every provider's base-url, is re-read live
     * via the reinit events saveAiProviderField publishes. Do not flag "base-url" as a blanket
     * restart trigger: it is read fresh on every rebuild, same as api-key.
     *
     * <p>One place, because three callers ask: the two field writes below, and a configuration
     * import, which has to say so in its preview before anything is written.
     */
    public static boolean requiresRestart(String prefix) {
        return "proxy".equals(prefix);
    }

    // Returns true when the deleted field requires an application restart to take effect.
    public boolean deleteAiProviderField(String prefix, String field) {
        write(props -> props.remove(prefix + "." + field));
        announceRoleChanged(prefix);
        return requiresRestart(prefix);
    }

    // - Global settings store (GLOBAL_SETTINGS table) -

    // The current snapshot, loaded from the table the first time it is asked for. Never mutated.
    private Properties loadFile() {
        Properties cached = cachedProps;
        if (cached != null) return cached;
        writeLock.lock();
        try {
            if (cachedProps == null) {
                Properties props = new Properties();
                for (GlobalSetting row : globalSettingRepository.findAll()) {
                    if (row.getSettingValue() != null) {
                        props.setProperty(row.getSettingKey(), row.getSettingValue());
                    }
                }
                cachedProps = props;
            }
            return cachedProps;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Applies a change to a COPY of the snapshot, persists only the keys it added, changed or
     * removed - in one transaction - and publishes the copy once that has committed. A write that
     * fails leaves both the table and the snapshot as they were, and the caller gets the failure.
     */
    private void write(Consumer<Properties> change) {
        writeLock.lock();
        try {
            Properties before = loadFile();
            Properties after = new Properties();
            after.putAll(before);
            change.accept(after);

            List<String> removed = new ArrayList<>();
            for (String key : before.stringPropertyNames()) {
                if (!after.containsKey(key)) removed.add(key);
            }
            List<GlobalSetting> changed = new ArrayList<>();
            for (String key : after.stringPropertyNames()) {
                String value = after.getProperty(key);
                if (!Objects.equals(value, before.getProperty(key))) changed.add(new GlobalSetting(key, value));
            }
            if (removed.isEmpty() && changed.isEmpty()) return;

            Runnable persist = () -> {
                if (!removed.isEmpty()) globalSettingRepository.deleteAllById(removed);
                if (!changed.isEmpty()) globalSettingRepository.saveAll(changed);
            };
            if (tx != null) {
                tx.executeWithoutResult(status -> persist.run());
            } else {
                persist.run();
            }
            cachedProps = after;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Forgets the in-memory copy, so the next read comes from the table. For a writer that changed
     * GLOBAL_SETTINGS behind this service - a reset or a restore of the database.
     */
    public void reloadFromStore() {
        writeLock.lock();
        try {
            cachedProps = null;
        } finally {
            writeLock.unlock();
        }
    }

    // A key longer than GlobalSetting.SETTING_KEY can never be stored; refused before anything is
    // written, as a bad request rather than a database error.
    private static void requireStorableKey(String key) {
        if (key.length() > MAX_KEY_CHARS
                || key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_CHARS) {
            throw new IllegalArgumentException("That setting name is too long.");
        }
    }

    // Converts a value to its String representation, using Jackson for enums so that an enum with
    // a @JsonValue serializes as its JSON name, not its Java constant.
    private String toPropertyString(Object value) {
        if (value instanceof String) return (String) value;
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.startsWith("\"") && json.endsWith("\""))
                return json.substring(1, json.length() - 1);
            return json;
        } catch (Exception e) {
            return value.toString();
        }
    }

    private static <T> T defaultInstance(Class<T> cls) {
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + cls.getName(), e);
        }
    }
}
