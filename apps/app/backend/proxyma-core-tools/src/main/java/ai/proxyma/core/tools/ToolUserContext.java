package ai.proxyma.core.tools;

import ai.proxyma.core.search.domain.SearchContext;

// Shared userId resolution for tools that need a stable per-user identity (agent memory tools -
// unlike OffloadFileAccess's per-request-scoped file reads, these persist data across sessions).
// Desktop mode has exactly one persistent local user, so it gets a fixed sentinel rather than
// being treated as "no identity" - that would silently disable the feature for that whole
// deployment mode. Server mode's anonymous (unauthenticated) sessions have no persistent identity
// at all and get null - there is nothing durable to key memory off of for them.
final class ToolUserContext {

    // The same identity Settings > Memory reads and edits under.
    static final String DESKTOP_USER_ID = ai.proxyma.core.search.service.AgentMemoryService.LOCAL_USER_ID;

    private ToolUserContext() {
    }

    static String resolveUserId(boolean multiUser) {
        String userId = SearchContext.getUserId();
        if (userId != null && !userId.isBlank()) return userId;
        return multiUser ? null : DESKTOP_USER_ID;
    }
}
