package ai.proxyma.core.tools.office;

import ai.proxyma.core.settings.service.WriteToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * runCommand: a shell command on the user's own machine on Desktop - in the conversation's outputs/
 * folder or a directory named, with none of the app's environment - and in the sandbox container on a
 * server, its outputs/ kept as the conversation's files. A write tool everywhere, so Ask always asks.
 */
class RunCommandToolTest {

    @TempDir Path conversation;

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private RunCommandTool desktop() throws Exception {
        OfficeWorkspace workspace = mock(OfficeWorkspace.class);
        when(workspace.conversationDir()).thenReturn(conversation);
        return new RunCommandTool(mock(LocalPythonSandbox.class), workspace);
    }

    @Test
    @DisplayName("Desktop: the command runs in the conversation's outputs/ folder and its output comes back")
    void runsLocally() throws Exception {
        Map<String, Object> r = desktop().runCommand("echo made > note.txt && echo done", null, 20);
        assertThat(r).as(String.valueOf(r)).containsEntry("success", true).containsEntry("exitCode", 0);
        assertThat((String) r.get("stdout")).contains("done");
        assertThat(conversation.resolve("outputs").resolve("note.txt")).exists();
    }

    @Test
    @DisplayName("Desktop: a failing command reports its exit code and its errors, and a directory must exist")
    void failuresAreReported() throws Exception {
        Map<String, Object> r = desktop().runCommand(WINDOWS ? "dir C:\\no-such-dir-proxyma" : "ls /no-such-dir-proxyma", null, 20);
        assertThat(r).containsEntry("success", false);
        assertThat((Integer) r.get("exitCode")).isNotZero();
        assertThat(desktop().runCommand("echo x", "relative/dir", 20)).containsEntry("success", false);
        assertThat(desktop().runCommand(" ", null, 20)).containsEntry("success", false);
    }

    @Test
    @DisplayName("Desktop: the command starts with a clean environment - nothing the app holds in its own")
    void cleanEnvironment() throws Exception {
        // The test JVM's environment is the app's stand-in: a variable that is not on the kept list
        // must not reach the command. JAVA_HOME is set wherever this suite runs.
        Map<String, Object> r = desktop().runCommand(WINDOWS ? "echo [%JAVA_HOME%]" : "echo [$JAVA_HOME]", null, 20);
        assertThat((String) r.get("stdout")).doesNotContain(System.getenv().getOrDefault("JAVA_HOME", "unset-in-env"));
    }

    @Test
    @DisplayName("Desktop: a command past its time is stopped and says how to give it longer")
    void timeout() throws Exception {
        Map<String, Object> r = desktop().runCommand(WINDOWS ? "ping -n 30 127.0.0.1 > NUL" : "sleep 30", null, 1);
        assertThat(r).containsEntry("success", false).containsEntry("exitCode", -1);
        assertThat((String) r.get("error")).contains("background");
    }

    @Test
    @DisplayName("a server: the command goes to the sandbox container, and its outputs are the conversation's files")
    void serverUsesTheSandbox() throws Exception {
        PythonSandbox sandbox = mock(PythonSandbox.class);
        OfficeWorkspace workspace = mock(OfficeWorkspace.class);
        when(workspace.conversationDir()).thenReturn(conversation);
        when(workspace.inputs(conversation)).thenReturn(Map.of());
        when(sandbox.shell(eq("make report"), anyMap(), any(Duration.class)))
                .thenReturn(new PythonSandbox.Result(0, "ok", "", false, Map.of("outputs/report.pdf", new byte[] {1})));
        when(workspace.saveOutputs(eq(conversation), anyMap(), anyMap())).thenReturn(List.of());

        Map<String, Object> r = new RunCommandTool(sandbox, workspace).runCommand("make report", "/etc", 30);

        assertThat(r).containsEntry("success", true).containsEntry("stdout", "ok");
        verify(sandbox).shell(eq("make report"), anyMap(), eq(Duration.ofSeconds(30)));
        verify(workspace).saveOutputs(eq(conversation), anyMap(),
                org.mockito.ArgumentMatchers.argThat((Map<String, byte[]> m) -> m.containsKey("outputs/report.pdf")));
    }

    @Test
    @DisplayName("a write tool everywhere: Read-only refuses it and Ask asks every time")
    void itIsAWrite() {
        assertThat(WriteToolRegistry.NAMES).contains("runCommand");
        assertThat(Files.exists(conversation)).isTrue();
    }
}
