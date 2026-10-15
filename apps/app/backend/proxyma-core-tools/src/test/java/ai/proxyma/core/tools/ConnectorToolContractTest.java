package ai.proxyma.core.tools;

import ai.proxyma.core.common.net.HttpVerb;
import ai.proxyma.core.settings.service.WriteToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What every connector tool must hold to, checked across all of them rather than one by one.
 *
 * <ul>
 *   <li>A tool that can change something is in WriteToolRegistry - membership is what buys the
 *       approval pause and keeps an unattended agent away - and nothing checked it: two tools were
 *       once registered and ran writes without asking.</li>
 *   <li>A model-supplied id reaches a REST path only in the shape its server gives it.</li>
 *   <li>One verb rule: missing or blank is GET, only GET reads.</li>
 * </ul>
 */
class ConnectorToolContractTest {

    // Verbs that name a change. A tool whose name starts with one writes.
    private static final List<String> WRITE_VERBS = List.of("create", "update", "delete", "add", "merge", "move",
            "attach", "log", "link", "transition", "register", "remove", "review", "call", "save", "run", "download");

    // Starts with such a verb and does not change anything on a server or in an index.
    private static final Set<String> NOT_WRITES = Set.of(
            // A local snapshot: nothing on Bitbucket changes (BitbucketToolService.downloadSnapshot).
            "downloadBitbucketRepo", "deleteBitbucketRepo");

    @Test
    @DisplayName("every tool that writes is in WriteToolRegistry")
    void everyWriteIsRegistered() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        Set<String> missing = new TreeSet<>();
        int seen = 0;
        for (BeanDefinition bean : scanner.findCandidateComponents("ai.proxyma.core.tools")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            for (Method m : type.getDeclaredMethods()) {
                Tool tool = m.getAnnotation(Tool.class);
                if (tool == null) continue;
                seen++;
                String name = tool.name().isBlank() ? m.getName() : tool.name();
                boolean writes = WRITE_VERBS.stream().anyMatch(name::startsWith);
                // runPython writes only the conversation's own workspace, and is listed apart for that.
                boolean listed = WriteToolRegistry.NAMES.contains(name) || WriteToolRegistry.WORKSPACE_NAMES.contains(name);
                if (writes && !NOT_WRITES.contains(name) && !listed) missing.add(name);
            }
        }
        assertThat(seen).as("tools found").isGreaterThan(50);
        assertThat(missing).as("write tools missing from WriteToolRegistry").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1/../../space/DOCS", "10501/../../issue/PROJ-1", "12?x=1", "", "1 2", "-1"})
    @DisplayName("a numeric id carrying a path, a query or anything else is refused")
    void numericIdsAreChecked(String id) {
        assertThatThrownBy(() -> ToolIds.number("comment id", id)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("keys, slugs and issue keys in their servers' shapes pass; traversal does not")
    void keysAreChecked() {
        assertThat(ToolIds.issueKey("PROJ-123")).isEqualTo("PROJ-123");
        assertThat(ToolIds.issueKey("MY-PROJ-7")).isEqualTo("MY-PROJ-7");
        assertThat(ToolIds.key("space key", "~jdoe")).isEqualTo("~jdoe");
        assertThat(ToolIds.slug("my.repo-2")).isEqualTo("my.repo-2");
        assertThat(ToolIds.number("page id", " 98765 ")).isEqualTo("98765");
        for (String bad : List.of("PROJ-1/../../x", "PROJ", "../x")) {
            assertThatThrownBy(() -> ToolIds.issueKey(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ToolIds.slug("..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolIds.slug("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolIds.key("project key", "P/../x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("one verb rule for the tools, the services and the chat's gate")
    void oneVerbRule() {
        assertThat(HttpVerb.normalize(null)).isEqualTo("GET");
        assertThat(HttpVerb.normalize("  ")).isEqualTo("GET");
        assertThat(HttpVerb.normalize(" post ")).isEqualTo("POST");
        assertThat(HttpVerb.isRead("")).isTrue();
        assertThat(HttpVerb.isRead("DELETE")).isFalse();
        assertThat(WriteToolRegistry.isWriteCall("callJiraApi", Map.of("method", ""))).isFalse();
        assertThat(WriteToolRegistry.isWriteCall("callJiraApi", Map.of("method", "PUT"))).isTrue();
    }

    @Test
    @DisplayName("a write the server made without returning an id is still a success, with a note")
    void writtenWithoutAnId() {
        Map<String, Object> ok = ToolResults.written("issueKey", "PROJ-1", "commentId", null);
        assertThat(ok).containsEntry("success", true).containsEntry("issueKey", "PROJ-1").containsKey("note");
        assertThat(new ArrayList<>(ToolResults.written("pageId", "5").keySet())).containsExactly("success", "pageId");
    }
}
