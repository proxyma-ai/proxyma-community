package ai.proxyma.core.chat.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The one place that asks the chat modules for a conversation's {@link AgentProfile} - for the turn
 * (its model and step limit), the tool gate (what is withheld), the search tools (which sources) and
 * the memory tools (whose memory). One answer for all of them, so they cannot disagree about which
 * agent a conversation is.
 *
 * <p>Never throws, and FAILS CLOSED: a module that fails to answer gives {@link AgentProfile#LOCKED} -
 * no tools, no skills, no sources and no memory for that call - since it cannot say whether the
 * conversation is one it narrows, and "no profile" would hand a narrowed conversation the user's
 * whole account. The first module with a profile wins; two modules holding one conversation is not
 * a case that exists.
 *
 * <p>Modules are looked up per call rather than at construction, because a module's own beans
 * depend on chat's services, and resolving them here would make the dependency a cycle.
 */
@Component
public class AgentProfiles {

    private static final Logger log = LoggerFactory.getLogger(AgentProfiles.class);

    private final ObjectProvider<ChatModule> modules;

    public AgentProfiles(ObjectProvider<ChatModule> modules) {
        this.modules = modules;
    }

    /** This conversation's profile for the user the call runs for, or empty. */
    public Optional<AgentProfile> of(String conversationId, String userId) {
        return of(modules.orderedStream().toList(), conversationId, userId);
    }

    /** The same answer over a list of modules a caller already holds. */
    public static Optional<AgentProfile> of(List<ChatModule> modules, String conversationId, String userId) {
        if (conversationId == null || modules == null || modules.isEmpty()) return Optional.empty();
        for (ChatModule module : modules) {
            try {
                Optional<AgentProfile> profile = module.profileFor(conversationId, userId);
                if (profile != null && profile.isPresent()) return profile;
            } catch (RuntimeException e) {
                log.warn("[Chat] a chat module could not give conversation {} its profile, so it gets nothing: {}",
                        conversationId, e.getMessage());
                return Optional.of(AgentProfile.LOCKED);
            }
        }
        return Optional.empty();
    }
}
