package ai.proxyma.core.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the "you lead, the agents you deploy do the work" section goes. It was appended at the end of the
 * prompt, behind the sections telling the agent to search and read for itself - and the agent did.
 */
class SystemPromptDelegationTest {

    private static final String HEADING = "HOW YOU WORK - you lead, the agents you deploy do the work.";

    @Test
    @DisplayName("an agent that can delegate reads how it works before the sections describing the work")
    void theSectionComesBeforeTheWork() {
        String prompt = SystemPromptService.resolve(SystemPromptService.DEFAULT, true);

        assertThat(prompt.indexOf(HEADING))
                .isGreaterThan(prompt.indexOf("ABSOLUTE RULES"))
                .isLessThan(prompt.indexOf("SOURCES - "))
                .isLessThan(prompt.indexOf("TASKS - "))
                .isLessThan(prompt.indexOf("SEARCH:"));
        assertThat(prompt.split(java.util.regex.Pattern.quote(HEADING), -1)).as("said once").hasSize(2);
        // Nothing of the built-in prompt is lost by the insertion.
        assertThat(prompt.replace(SystemPromptService.DELEGATION, "").replaceAll("\\s+", " "))
                .isEqualTo(SystemPromptService.DEFAULT.replaceAll("\\s+", " "));
    }

    @Test
    @DisplayName("an agent that cannot delegate is never told about a tool it does not have")
    void noDelegationNoSection() {
        String prompt = SystemPromptService.resolve(SystemPromptService.DEFAULT, false);

        assertThat(prompt).isEqualTo(SystemPromptService.DEFAULT).doesNotContain("delegate");
    }

    @Test
    @DisplayName("a custom prompt with no such section gets it at the end, and nothing of it is changed")
    void aCustomPromptGetsItAtTheEnd() {
        String custom = "You are a careful assistant for the legal team.\n\nAnswer briefly.\n";

        String prompt = SystemPromptService.resolve(custom, true);

        assertThat(prompt).startsWith("You are a careful assistant for the legal team.\n\nAnswer briefly.")
                .endsWith(SystemPromptService.DELEGATION);
    }

    @Test
    @DisplayName("the section names the first call of a job, and what the agent keeps for itself")
    void theSectionSaysWhatItMeans() {
        assertThat(SystemPromptService.DELEGATION)
                .contains("DEPLOY BY DEFAULT")
                .contains("ONE CALL OF YOUR OWN, THEN DEPLOY")
                .contains("'readSkill', 'useServer', 'askUser', 'recall' and 'searchConversations' are always yours")
                .contains("An agent works ON ITS OWN")
                .contains("'tellAgent'").contains("'controlAgent'").contains("'awaitAgents'")
                .contains("'alarm'").contains("'background'")
                .doesNotContain("sub-agent").doesNotContain("'delegate'");
        assertThat(SystemPromptService.DELEGATION_REMINDER).contains("the agents you deploy do the work");
    }

    @Test
    @DisplayName("memory is checked before any source is searched or any agent deployed")
    void memoryComesFirst() {
        String prompt = SystemPromptService.resolve(SystemPromptService.DEFAULT, true);

        assertThat(prompt.indexOf("MEMORY FIRST - "))
                .isGreaterThan(prompt.indexOf("ABSOLUTE RULES"))
                .isLessThan(prompt.indexOf(HEADING))
                .isLessThan(prompt.indexOf("SOURCES - "))
                .isLessThan(prompt.indexOf("SEARCH:"));
        // And without delegation, the same order against the sections describing the work.
        String alone = SystemPromptService.resolve(SystemPromptService.DEFAULT, false);
        assertThat(alone.indexOf("MEMORY FIRST - ")).isLessThan(alone.indexOf("SOURCES - "));
    }
}
