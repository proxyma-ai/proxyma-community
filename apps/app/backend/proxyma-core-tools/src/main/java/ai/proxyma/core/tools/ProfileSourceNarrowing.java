package ai.proxyma.core.tools;

import ai.proxyma.core.chat.module.AgentProfile;
import ai.proxyma.core.chat.module.AgentProfiles;
import ai.proxyma.core.registry.service.SourceNarrowing;
import ai.proxyma.core.search.domain.SearchContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A conversation's agent profile as the registry's SourceNarrowing: while a turn or its tools run
 * (SearchContext carries the conversation and the user on every one of those threads), the sources
 * ANY tool can see - search, the knowledge graph, readSource, getStatus, skill gating - are those
 * the profile names, intersected by the registry with what the user can see. A LOCKED profile names
 * none. Off a turn - a REST request, a sync, the scheduler - no conversation is on the thread and
 * nothing is narrowed.
 */
@Component
public class ProfileSourceNarrowing implements SourceNarrowing {

    private final AgentProfiles profiles;

    public ProfileSourceNarrowing(AgentProfiles profiles) {
        this.profiles = profiles;
    }

    @Override
    public Optional<Set<UUID>> allowedSources() {
        String conversationId = SearchContext.getConversationId();
        if (conversationId == null) return Optional.empty();
        return profiles.of(conversationId, SearchContext.getUserId()).flatMap(AgentProfile::narrowedSources);
    }
}
