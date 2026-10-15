package ai.proxyma.core.settings.dto;

import java.util.Map;

/**
 * Settings prefixes a module the Community edition does not have adds to {@link SettingsSecrets}'s
 * map - so their {@link EncryptedField} values are encrypted on every write path, read back as the
 * placeholder by the settings API, and known to the configuration export, exactly as a built-in
 * prefix's are. Proxyma Enterprise's Microsoft 365 module adds {@code m365} (the Entra app
 * registration, whose client secret is encrypted) and the two per-user sync prefixes beside it.
 *
 * <p>Found through {@link java.util.ServiceLoader}, NOT through Spring: the map is static and read by
 * static code, and it has to be complete the first time any of it is read - before the context
 * finishes starting, in a unit test with no context at all. A module lists its implementation in
 * {@code META-INF/services/ai.proxyma.core.settings.dto.SettingsPrefixContributor}. That file is the
 * explicit registration the hand-written map stands for: nothing is found by scanning, so a DTO is
 * covered because somebody named it, not because it exists.
 *
 * <p>A contributor may not take a prefix the map already has: that would let a module change how a
 * built-in prefix's secrets are stored, and the map refuses to load instead.
 */
public interface SettingsPrefixContributor {

    /** Each prefix this module adds, and the DTO describing its fields. */
    Map<String, Class<?>> prefixes();
}
