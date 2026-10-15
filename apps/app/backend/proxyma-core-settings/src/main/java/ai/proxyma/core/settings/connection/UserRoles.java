package ai.proxyma.core.settings.connection;

/**
 * Whether a user holds the Super admin role - asked where no request carries the role, such as a
 * chat turn starting an MCP server the user's connection names. Implemented by the auth module,
 * which exists only on Enterprise Server; Desktop and Home Server have one user, who is everything.
 */
public interface UserRoles {

    /** False for an unknown user. */
    boolean isSuperAdmin(String userId);

    /**
     * Whether the account may still act - false for an unknown one or one awaiting deletion, which
     * is locked out of everything. Asked where a credential outlives the session that made it: a
     * custom source's ingest token. True by default, for the editions with one user.
     */
    default boolean isActive(String userId) {
        return true;
    }
}
