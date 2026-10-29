package ai.proxyma.core.chat.service;

import ai.proxyma.core.graph.service.KnowledgeGraphService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reading memory is offered on every call, so the prompt's MEMORY FIRST rule can be followed
 * without a readSkill call first (maintainer's feedback, 2026-10-03: the agent searched the
 * sources from scratch for what it had already saved). Writing it stays behind the skill that
 * says what is worth saving.
 */
class MemoryToolsAreCoreTest {

    private final ChatSkillService skills = mock(ChatSkillService.class);
    private final LicenseToolScope license = mock(LicenseToolScope.class);
    private final ToolSkillGating gating = new ToolSkillGating(skills, mock(KnowledgeGraphService.class), license);

    private static ToolCallback tool(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    @Test
    @DisplayName("recall and searchConversations are core tools; remember is unlocked by its skill")
    void readingMemoryIsCore() {
        assertThat(gating.allGatedToolNames()).doesNotContain("recall", "searchConversations")
                .contains("remember");
        assertThat(gating.toolNamesForGroup("Memory & Knowledge")).containsExactly("remember");
    }

    @Test
    @DisplayName("a skill file still listing a core tool does not offer it twice")
    void staleSkillFileDoesNotDuplicateACoreTool() {
        // The 0.26.0 report: a Memory & Knowledge file seeded before recall was core still lists it,
        // and Spring AI refused the next call with "Multiple tools with the same name (recall)".
        when(skills.toolsForSkillName("Memory & Knowledge"))
                .thenReturn(Optional.of(Set.of("recall", "remember", "searchConversations")));
        when(license.isToolIncluded(anyString(), anySet())).thenReturn(true);

        ToolCallback recall = tool("recall");
        ToolCallback search = tool("searchConversations");
        ToolCallback remember = tool("remember");
        List<ToolCallback> core = List.of(recall, search);

        List<String> names = gating.resolveAdvertised(List.of(recall, search, remember), core,
                        Set.of("Memory & Knowledge")).stream()
                .map(cb -> cb.getToolDefinition().name()).toList();

        assertThat(names).containsExactlyInAnyOrder("recall", "searchConversations", "remember");
    }
}
