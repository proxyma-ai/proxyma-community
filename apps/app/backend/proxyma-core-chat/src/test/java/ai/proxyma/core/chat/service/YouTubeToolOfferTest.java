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
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The YouTube tools are offered only behind their skill: a conversation that has not read "YouTube"
 * is not handed a tool that posts publicly as the user's channel.
 */
class YouTubeToolOfferTest {

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
    @DisplayName("the ten YouTube tools, the channel's numbers, its own replies and the replies waiting for approval included, are one skill's group, and none is a core tool")
    void oneGroup() {
        assertThat(gating.toolNamesForGroup("YouTube"))
                .containsExactlyInAnyOrder("listYouTubeComments", "getYouTubeViewerHistory", "listYouTubeChannelReplies",
                        "getYouTubeVideo", "replyToYouTubeComment",
                        "getYouTubeChannelStats", "listYouTubeVideos", "getYouTubeAnalytics", "getYouTubeReach",
                        "listWaitingYouTubeReplies");
        assertThat(gating.allGatedToolNames()).contains("listYouTubeComments", "listYouTubeChannelReplies", "getYouTubeVideo", "replyToYouTubeComment",
                "getYouTubeChannelStats", "listYouTubeVideos", "getYouTubeAnalytics", "getYouTubeReach");
    }

    @Test
    @DisplayName("they are advertised once the YouTube skill is read, and not before")
    void behindTheSkill() {
        when(skills.toolsForSkillName(anyString())).thenReturn(Optional.empty());
        when(license.isToolIncluded(anyString(), anySet())).thenReturn(true);
        ToolCallback search = tool("searchIndexedSources");
        ToolCallback list = tool("listYouTubeComments");
        ToolCallback reply = tool("replyToYouTubeComment");
        ToolCallback analytics = tool("getYouTubeAnalytics");
        ToolCallback reach = tool("getYouTubeReach");
        List<ToolCallback> all = List.of(search, list, reply, analytics, reach);
        List<ToolCallback> core = List.of(search);

        assertThat(gating.resolveAdvertised(all, core, Set.of()).stream().map(cb -> cb.getToolDefinition().name()))
                .containsExactly("searchIndexedSources");
        assertThat(gating.resolveAdvertised(all, core, Set.of("YouTube")).stream().map(cb -> cb.getToolDefinition().name()))
                .containsExactlyInAnyOrder("searchIndexedSources", "listYouTubeComments", "replyToYouTubeComment",
                        "getYouTubeAnalytics", "getYouTubeReach");
    }
}
