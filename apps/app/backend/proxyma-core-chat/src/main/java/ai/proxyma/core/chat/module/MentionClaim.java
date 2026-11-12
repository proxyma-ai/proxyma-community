package ai.proxyma.core.chat.module;

import java.util.List;

/**
 * The @-mentions of one message that a product module recognizes as its own rather than as sources
 * (ChatModule.claimMentions) - a Constellations project, addressed through its leader.
 *
 * @param names the mentioned names it claims, exactly as the composer sent them; they reach no source
 *              search, and the sent message keeps them as chips
 * @param note  what the agent is told about them, one or two sentences, or null - plain text: a double
 *              quote in it is written as a single one, since the chips are read back from quotes
 */
public record MentionClaim(List<String> names, String note) {

    public static final MentionClaim NONE = new MentionClaim(List.of(), null);

    public MentionClaim {
        names = names == null ? List.of() : List.copyOf(names);
    }

    public boolean isEmpty() {
        return names.isEmpty();
    }
}
