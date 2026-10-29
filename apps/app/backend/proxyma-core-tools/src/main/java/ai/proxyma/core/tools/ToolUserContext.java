package ai.proxyma.core.tools;

import ai.proxyma.core.chat.module.AgentProfile;
import ai.proxyma.core.chat.module.AgentProfiles;
import ai.proxyma.core.search.domain.SearchContext;

import java.util.Optional;

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

    // The saved agent this tool call runs for, if a product module holds its conversation for one
    // (AgentProfile) - read off the conversation and user every tool thread carries. Empty outside
    // a chat turn (an MCP client's call), where no conversation is named.
    static Optional<AgentProfile> agentProfile(AgentProfiles profiles) {
        if (profiles == null) return Optional.empty();
        return profiles.of(SearchContext.getConversationId(), SearchContext.getUserId());
    }

    // Whose memory remember and recall work on: a saved agent's own when the conversation is one
    // of its, else the user's - and null, as resolveUserId, when there is nobody to key it by. An
    // agent's memory needs a person behind the turn too: a guest gets none of it. A LOCKED profile
    // (a module that could not say whose memory it is) gets nobody's, never the user's.
    static String resolveMemoryOwner(boolean multiUser, AgentProfiles profiles) {
        String userId = resolveUserId(multiUser);
        if (userId == null) return null;
        Optional<AgentProfile> profile = agentProfile(profiles);
        if (profile.isPresent() && profile.get().locked()) return null;
        return profile.map(AgentProfile::memoryKey).orElse(userId);
    }
}
