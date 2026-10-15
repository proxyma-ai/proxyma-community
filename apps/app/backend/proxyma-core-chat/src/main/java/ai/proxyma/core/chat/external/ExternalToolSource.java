package ai.proxyma.core.chat.external;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tools that are not Proxyma's own - those of the MCP servers a person connected (2026-10-13) - and
 * the skills that offer them. A PORT: the chat defines what it needs, and proxyma-core-tools, which
 * holds the MCP clients, implements it.
 *
 * <p>Every tool Proxyma registers itself is fixed at startup (McpToolConfig), and nothing else about
 * the chat's tool list could change at run time. These can, per person: a server is added, edited
 * or switched off, and the next call sees it. So they are asked for here, per turn, rather than
 * registered.
 *
 * <p>OFFERED THE WAY CONNECTOR TOOLS ARE: each server is one skill, listed in the system prompt by
 * name and description; reading it ({@code readSkill}) unlocks its tools for the rest of the
 * conversation. A server's forty tools are not in every request of every conversation.
 *
 * <p>A person sees only the servers they may use - their own, and on Enterprise Server those a
 * Super admin shared with everyone - and every method takes the user for exactly that reason.
 */
public interface ExternalToolSource {

    /** A skill this source offers: what the system prompt lists. */
    record ExternalSkill(String name, String description) {}

    /**
     * The skills this person may read, for the system prompt. MUST NOT reach a server - it runs at
     * the start of every turn - so a description comes from what is already known.
     */
    List<ExternalSkill> skillsFor(String userId);

    /** Whether a skill name is one of this source's, whoever asks - to route {@code readSkill}. */
    boolean owns(String skillName);

    /**
     * A skill's text for {@code readSkill}: the server's own instructions and its tools. May connect
     * to the server. Empty when the name is not one this person may use.
     */
    Optional<String> readSkill(String skillName, String userId);

    /** The tools of those of {@code skillNames} that are this source's and this person's. */
    List<ToolCallback> toolsFor(String userId, Set<String> skillNames);

    /** One tool by the name it was advertised under, for this person - what a call is dispatched to. */
    Optional<ToolCallback> find(String userId, String toolName);
}
