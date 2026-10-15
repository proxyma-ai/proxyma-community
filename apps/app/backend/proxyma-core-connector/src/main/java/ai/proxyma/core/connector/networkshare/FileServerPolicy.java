package ai.proxyma.core.connector.networkshare;

/**
 * The deployment's ceiling on which file servers a Network share source may reach - Enterprise
 * Server's optional {@code network-share.allowed-hosts} list. Asked at every session, not only at
 * registration, so narrowing the list stops sources that are already running.
 *
 * <p>An interface here because the list is a setting and this module does not read settings:
 * ConnectorConnectionService answers it, the same answer a connection's own save is checked against.
 */
@FunctionalInterface
public interface FileServerPolicy {

    /** Whether a source may reach the file server {@code host}. */
    boolean allows(String host);
}
