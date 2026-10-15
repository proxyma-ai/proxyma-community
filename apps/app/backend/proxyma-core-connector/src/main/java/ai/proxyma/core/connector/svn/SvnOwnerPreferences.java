package ai.proxyma.core.connector.svn;

/**
 * Which file extensions a source's OWNER indexes from Subversion when a source names none of its own -
 * read fresh for every session, as the old configure step read it, so a setting changed after a
 * source was registered reaches its next sync.
 *
 * <p>A port, as {@link ai.proxyma.core.connector.jira.JiraOwnerPreferences} is for Jira: the session
 * depends on nothing of the application but this module, and the setting lives in the settings
 * module. Implemented in proxyma-core-registry; a deployment or a test without it uses the built-in
 * extension list.
 */
@FunctionalInterface
public interface SvnOwnerPreferences {

    /**
     * The owner's {@code svn.indexed-extensions} - comma separated - or null when they set none.
     * {@code ownerId} is null on Desktop and Home Server.
     */
    String indexedExtensions(String ownerId);
}
