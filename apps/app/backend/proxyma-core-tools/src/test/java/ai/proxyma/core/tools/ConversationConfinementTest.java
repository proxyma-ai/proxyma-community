package ai.proxyma.core.tools;

import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.tools.office.OfficeWorkspace;
import ai.proxyma.core.tools.office.PythonSandbox;
import ai.proxyma.core.tools.office.RunPythonTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * tools-core-02: the tools that work in "this conversation's folder" - runPython, the Jira and
 * Confluence attach tools, web media - built that folder from the conversation id with nothing
 * keeping it inside the caller's own part of the root. A conversation id of
 * {@code ../../../users/bob/conversations/conv-2} put alice's run in bob's folder: his uploads were
 * shipped into her sandbox, her outputs overwrote his, and his files could be attached to her Jira.
 *
 * <p>Multi-user throughout, alice calling, bob's conversation on disk beside hers.
 */
class ConversationConfinementTest {

    @TempDir
    Path data;

    private Path bobs;

    private static final String[] TRAVERSALS = {
            "../../../users/bob/conversations/conv-2", "../../bob/conversations/conv-2", "..", "../..", "a/b"};

    @BeforeEach
    void setUp() throws Exception {
        bobs = data.resolve("users").resolve("bob").resolve("conversations").resolve("conv-2");
        Files.createDirectories(bobs.resolve("attachments"));
        Files.createDirectories(bobs.resolve("outputs"));
        Files.writeString(bobs.resolve("attachments").resolve("salaries.xlsx"), "bob's");
        Files.writeString(bobs.resolve("outputs").resolve("report.docx"), "bob's");
        SearchContext.setUserId("alice");
    }

    @AfterEach
    void tearDown() {
        SearchContext.clear();
    }

    @Test
    @DisplayName("the workspace refuses a conversation id that is a path, and never names bob's folder")
    void workspaceStaysInTheCallersFolder() {
        OfficeWorkspace workspace = new OfficeWorkspace(data.toString(), true);
        for (String id : TRAVERSALS) {
            SearchContext.setConversationId(id);
            assertThatThrownBy(workspace::conversationDir).as(id).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> workspace.file("salaries.xlsx")).as(id).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> workspace.file(bobs.resolve("attachments/salaries.xlsx").toString())).as(id)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // Bob's conversation's id, as alice: her own (empty) folder of that name, never his.
        SearchContext.setConversationId("conv-2");
        assertThat(workspace.conversationDir()).isEqualTo(data.toAbsolutePath().normalize()
                .resolve("users").resolve("alice").resolve("conversations").resolve("conv-2"));
        assertThatThrownBy(() -> workspace.file("salaries.xlsx")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("runPython with a traversal id runs nothing, reads nothing of bob's and overwrites nothing")
    void runPythonRefuses() throws Exception {
        PythonSandbox sandbox = mock(PythonSandbox.class);
        when(sandbox.isAvailable()).thenReturn(true);
        RunPythonTool tool = new RunPythonTool(sandbox, new OfficeWorkspace(data.toString(), true));
        for (String id : TRAVERSALS) {
            SearchContext.setConversationId(id);
            Map<String, Object> result = tool.runPython("open('outputs/report.docx','w').write('x')");
            assertThat(result.get("success")).as(id).isEqualTo(false);
        }
        verify(sandbox, never()).run(any(), any(), any());
        assertThat(Files.readString(bobs.resolve("outputs").resolve("report.docx"))).isEqualTo("bob's");
    }

    @Test
    @DisplayName("web media for a traversal id is not saved anywhere")
    void webMediaRefuses() throws Exception {
        WebMediaStorage storage = new WebMediaStorage(RestClient.builder(), data.toString(), true);
        for (String id : TRAVERSALS) {
            SearchContext.setConversationId(id);
            assertThat(storage.saveDirect("https://example.com/cat.png", "image/png", new byte[]{1, 2, 3})).as(id).isNull();
            assertThat(storage.downloadAndSave(List.of("https://example.invalid/cat.png"))).as(id).isEmpty();
        }
        assertThat(bobs.resolve("web-media")).doesNotExist();
        assertThat(bobs.getParent().getParent().getParent().resolve("web-media")).doesNotExist();
        // An ordinary id saves into the caller's own folder.
        SearchContext.setConversationId("conv-1");
        assertThat(storage.saveDirect("https://example.com/cat.png", "image/png", new byte[]{1, 2, 3})).isNotNull();
        assertThat(data.resolve("users/alice/conversations/conv-1/web-media/cat.png")).exists();
    }

    @Test
    @DisplayName("read and grep: a visitor's root is users/_anonymous, never the users folder itself")
    void offloadRootOfAVisitor() {
        Path users = data.toAbsolutePath().normalize().resolve("users");
        for (String blank : new String[]{null, "", "  "}) {
            SearchContext.setUserId(blank);
            Path allowed = OffloadFileAccess.resolveAllowedRoot(data.toString(), true);
            assertThat(allowed).isEqualTo(users.resolve("_anonymous"));
            assertThat(OffloadFileAccess.isWithinAllowedRoot(bobs.resolve("outputs/report.docx"), allowed)).isFalse();
        }
        SearchContext.setUserId("alice");
        assertThat(OffloadFileAccess.isWithinAllowedRoot(bobs.resolve("outputs/report.docx"),
                OffloadFileAccess.resolveAllowedRoot(data.toString(), true))).isFalse();
    }
}
