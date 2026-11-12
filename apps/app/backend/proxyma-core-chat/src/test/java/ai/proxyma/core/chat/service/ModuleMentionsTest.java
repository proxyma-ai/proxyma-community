package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.chat.module.MentionClaim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An @-mention a product module claims (ChatModule.claimMentions - a Constellations project, whose
 * leader the person addresses from the main chat) is no source to prioritize: it leaves the sources'
 * list, the module's note is put in the message instead, and the sent message still draws it as a
 * chip, so a rewind sends it again.
 */
class ModuleMentionsTest {

    /** Claims what it is told to, for the user it is told to. */
    private static final class Claiming implements ChatModule {
        final List<String> names;
        final String note;
        String askedFor;

        Claiming(List<String> names, String note) {
            this.names = names;
            this.note = note;
        }

        @Override public List<Object> toolObjects() { return List.of(); }
        @Override public Map<String, Set<String>> skillToolGroups() { return Map.of(); }
        @Override public Set<String> ownToolNames() { return Set.of(); }
        @Override public boolean offersToolsIn(String conversationId, String userId) { return false; }
        @Override public Optional<String> systemContext(String conversationId, String userId) { return Optional.empty(); }
        @Override public boolean licensed() { return true; }

        @Override
        public MentionClaim claimMentions(String conversationId, String userId, List<String> mentions) {
            askedFor = userId;
            return new MentionClaim(names, note);
        }
    }

    /** An ordinary module, which claims nothing. */
    private static class Plain implements ChatModule {
        @Override public List<Object> toolObjects() { return List.of(); }
        @Override public Map<String, Set<String>> skillToolGroups() { return Map.of(); }
        @Override public Set<String> ownToolNames() { return Set.of(); }
        @Override public boolean offersToolsIn(String conversationId, String userId) { return false; }
        @Override public Optional<String> systemContext(String conversationId, String userId) { return Optional.empty(); }
        @Override public boolean licensed() { return true; }
    }

    @Test
    @DisplayName("a claimed mention leaves the sources' list, and the note and the name go in the message")
    void claimed() {
        Claiming module = new Claiming(List.of("Release readiness"),
                "the lead of the project \"Release readiness\"; use messageProjectLead.");
        ChatService.ModuleMentions m = ChatService.claimModuleMentions(List.of(new Plain(), module), "conv-1", "u-1",
                List.of("PRX Jira", "Release readiness"));

        assertThat(module.askedFor).isEqualTo("u-1");
        assertThat(m.sourceMentions()).containsExactly("PRX Jira");
        assertThat(m.claimedNames()).containsExactly("Release readiness");
        assertThat(m.block()).contains("use messageProjectLead").contains("'Release readiness'")
                .endsWith("Addressed: \"Release readiness\"]");
    }

    @Test
    @DisplayName("nothing is claimed that the message does not mention, and a module that fails costs only its claim")
    void onlyWhatWasMentioned() {
        ChatModule failing = new Plain() {
            @Override
            public MentionClaim claimMentions(String conversationId, String userId, List<String> mentions) {
                throw new IllegalStateException("down");
            }
        };
        Claiming stranger = new Claiming(List.of("Another project"), "a note");
        ChatService.ModuleMentions m = ChatService.claimModuleMentions(List.of(failing, stranger), "conv-1", null,
                List.of("PRX Jira"));
        assertThat(m.sourceMentions()).containsExactly("PRX Jira");
        assertThat(m.claimedNames()).isEmpty();
        assertThat(m.block()).isEmpty();

        assertThat(ChatService.claimModuleMentions(List.of(stranger), "conv-1", null, null).sourceMentions()).isEmpty();
    }

    @Test
    @DisplayName("the sent message shows the words alone, and every mention - claimed or not - as a chip")
    void readBack() {
        Claiming module = new Claiming(List.of("Release readiness"), "the lead of a project.");
        ChatService.ModuleMentions m = ChatService.claimModuleMentions(List.of(module), "conv-1", "u-1",
                List.of("PRX Jira", "Release readiness"));
        String stored = "@Release readiness what about @PRX Jira?"
                + "\n\n[Context: The user has highlighted these specific documents - prioritize. Mentioned: \"PRX Jira\"]"
                + m.block();

        ChatService.SanitizedUserContent shown = ChatService.sanitizeUserContent(stored);
        assertThat(shown.content()).isEqualTo("@Release readiness what about @PRX Jira?");
        assertThat(shown.mentions()).containsExactly("PRX Jira", "Release readiness");
    }
}
