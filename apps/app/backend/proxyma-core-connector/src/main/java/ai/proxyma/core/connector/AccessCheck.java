package ai.proxyma.core.connector;

/**
 * The answer to {@link ai.proxyma.core.connector.spi.ConnectorSession#verifyAccess()}: one cheap
 * authenticated read against the resource being registered, so a wrong credential is refused while
 * the person who typed it is still looking at the form - not hours later as an empty source that
 * claims to be healthy.
 *
 * <p>THREE states, not two. {@link #unsupported()} means "this connector has no probe", and a
 * caller that could not tell it from {@link #verified()} would have to either block registration
 * for every connector without one or record "not checked" as "checked". Registration proceeds on
 * unsupported exactly as it did before probes existed.
 *
 * <p>{@code detail} is read by the person who just typed the credential, so it says what to do in
 * their words - "Jira rejected the token - update it in Settings > Connections > Jira", never a
 * bare status code and never an HTML error page. A session's verifyAccess() must not
 * throw; a failure is one of these, not an exception.
 */
public record AccessCheck(boolean supported, boolean ok, String detail) {

    public static AccessCheck unsupported() {
        return new AccessCheck(false, true, null);
    }

    public static AccessCheck verified() {
        return new AccessCheck(true, true, null);
    }

    public static AccessCheck failed(String why) {
        return new AccessCheck(true, false, why);
    }

    /** True only when a probe actually ran and said no. Unsupported is never a rejection. */
    public boolean rejected() {
        return supported && !ok;
    }
}
