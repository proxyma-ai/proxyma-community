package ai.proxyma.core.connector.jira;

/**
 * What a source's OWNER has said about how their Jira issues are written down - read fresh for
 * every session, since it is a setting of the person and not of the source: a source registered
 * before they set it picks it up on its next sync without being registered again.
 *
 * <p>A port so the Jira session, which depends on nothing of the application but this module, can
 * read a setting the settings module keeps. Implemented in proxyma-core-registry; a deployment or a
 * test without it renders with the built-in deny list alone.
 */
@FunctionalInterface
public interface JiraOwnerPreferences {

    /**
     * The owner's {@code jira.skip-custom-fields} - custom field display names, comma separated - or
     * null when they set none. {@code ownerId} is null on Desktop and Home Server.
     */
    String skipCustomFields(String ownerId);
}
