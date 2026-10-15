package ai.proxyma.core.system.activity;

// Presence hook implemented by proxyma-core-auth (UserPresenceService, server profile) and
// called by proxyma-core-network's ActivityTrackingFilter on every authenticated request.
// Lives in proxyma-core-common for the same dependency-direction reason as
// ActivityCounterStore: network cannot depend on auth.
public interface UserPresencePort {

    // Marks the user as seen "now". Implementations throttle the underlying write - callers may
    // invoke this on every request.
    void touch(String userId);
}
