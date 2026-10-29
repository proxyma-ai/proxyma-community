package ai.proxyma.core.chat.module;

import java.util.Set;
import java.util.UUID;

/**
 * How a conversation a module holds runs DIFFERENTLY from an ordinary chat: a saved agent's own
 * model, its step limit, the tools it is not given, the sources it is limited to and the memory it
 * keeps. Every field may be null, and null always means "as an ordinary chat" - so an empty profile
 * and no profile behave the same.
 *
 * <p><b>EVERY FIELD ONLY NARROWS OR REDIRECTS; NONE WIDENS.</b> {@code withheldTools} takes tools
 * away, never adds one: the license scope, a module's own gate, a connection's Write switch and the
 * permission mode still decide everything they decide today. {@code sourceIds} is intersected with
 * what the turn's user can read, so an agent never reads a source its person cannot. The model is
 * refused, never silently replaced, when its provider is no longer configured. And the system
 * prompt is not here at all: a module says who an agent is through
 * {@link ChatModule#systemContext}, after the product's own instructions, which it cannot replace.
 *
 * @param providerId    the provider INSTANCE the agent runs on (as every role setting names one),
 *                      or null for the chat role's provider - with a model, "this provider's model"
 * @param model         the model, or null for the chat role's model
 * @param maxSteps      the most model calls one turn may make, or null for the chat setting; held to
 *                      the same ceiling ({@code ChatSettings.iterationCeiling})
 * @param withheldTools tool names this conversation is not offered and may not call, or null
 * @param sourceIds     the only sources its searches read, intersected with the user's, or null
 *                      for every source the user can read
 * @param memoryKey     whose long-term memory remember and recall read and write, in place of the
 *                      user's own - {@code AgentMemoryService.agentKey(id)} - or null for the user's
 * @param withheldSkills skills this conversation may not read, so none of their tools is unlocked -
 *                      a tool group, or a connected MCP server ({@code MCP: <name>}), whose tool
 *                      names are not known until the server answers - or null
 * @param locked        the conversation is given NOTHING: no tool, no skill, no source and no memory
 *                      ({@link #LOCKED}). What a module that failed to answer is taken to have said
 */
public record AgentProfile(String providerId, String model, Integer maxSteps, Set<String> withheldTools,
                           Set<UUID> sourceIds, String memoryKey, Set<String> withheldSkills, boolean locked) {

    /**
     * Nothing at all: the profile of a conversation a module holds but could not describe - it threw,
     * or its binding names an agent that is gone. A failure costs the narrowed conversation what it
     * reads; it never hands it the user's whole account, which "no profile" would mean.
     */
    public static final AgentProfile LOCKED = new AgentProfile(null, null, null, null, Set.of(), null, null, true);

    /** A profile that is not {@link #LOCKED}. */
    public AgentProfile(String providerId, String model, Integer maxSteps, Set<String> withheldTools,
                        Set<UUID> sourceIds, String memoryKey, Set<String> withheldSkills) {
        this(providerId, model, maxSteps, withheldTools, sourceIds, memoryKey, withheldSkills, false);
    }

    /** A profile that withholds no skill. */
    public AgentProfile(String providerId, String model, Integer maxSteps, Set<String> withheldTools,
                        Set<UUID> sourceIds, String memoryKey) {
        this(providerId, model, maxSteps, withheldTools, sourceIds, memoryKey, null, false);
    }

    public AgentProfile {
        withheldSkills = withheldSkills == null ? null : withheldSkills.stream()
                .filter(java.util.Objects::nonNull).map(n -> n.strip().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        withheldTools = withheldTools == null ? null : Set.copyOf(withheldTools);
        sourceIds = sourceIds == null ? null : Set.copyOf(sourceIds);
        if (providerId != null && providerId.isBlank()) providerId = null;
        if (model != null && model.isBlank()) model = null;
        if (memoryKey != null && memoryKey.isBlank()) memoryKey = null;
        if (locked) sourceIds = Set.of();
    }

    /**
     * The sources this conversation may read, for a caller that intersects them with the user's own:
     * empty for "not narrowed", an empty set for none at all.
     */
    public java.util.Optional<Set<UUID>> narrowedSources() {
        return java.util.Optional.ofNullable(sourceIds);
    }

    /** Whether this conversation may use this tool. */
    public boolean allows(String toolName) {
        if (locked) return false;
        return withheldTools == null || toolName == null || !withheldTools.contains(toolName);
    }

    /** Whether this conversation may read this skill - compared without regard to case or spacing. */
    public boolean allowsSkill(String skillName) {
        if (locked) return false;
        return withheldSkills == null || skillName == null
                || !withheldSkills.contains(skillName.strip().toLowerCase(java.util.Locale.ROOT));
    }

    /** Whether a search may read this source - one the user can read is still required besides. */
    public boolean reads(UUID sourceId) {
        if (locked) return false;
        return sourceIds == null || sourceIds.contains(sourceId);
    }

    /** Whether the agent runs on a model of its own. */
    public boolean ownModel() {
        return model != null;
    }
}
