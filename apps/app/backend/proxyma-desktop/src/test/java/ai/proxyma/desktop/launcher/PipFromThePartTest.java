package ai.proxyma.desktop.launcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Python rebuild installs pip from the python part's own wheel, never from PyPI.
 *
 * <p>get-pip.py, run with no arguments, has the pip it carries install pip from PyPI - five seconds
 * of every rebuild on Windows, whose embeddable Python has no pip, and a rebuild that failed
 * outright offline or behind a proxy that blocks PyPI. requirements.txt now names pip, so the part
 * carries its wheel and get-pip.py is pointed at it.
 */
class PipFromThePartTest {

    @TempDir
    Path part;

    private List<String> command() throws Exception {
        return ProxymaLauncher.getPipCommand(part.resolve("runtime/python.exe"), part.resolve("runtime/get-pip.py"),
                part.resolve("wheels"));
    }

    @Test
    @DisplayName("a part carrying a pip wheel installs pip from its wheels alone")
    void offlineFromTheWheels() throws Exception {
        Files.createDirectories(part.resolve("wheels"));
        Files.writeString(part.resolve("wheels/pip-26.0.1-py3-none-any.whl"), "");
        Files.writeString(part.resolve("wheels/chromadb-1.5.0-cp39-abi3-win_amd64.whl"), "");

        assertThat(command()).contains("--no-index", "--find-links=" + part.resolve("wheels"));
    }

    @Test
    @DisplayName("a part built before pip was in it is still installed, the old way")
    void anOlderPartStillWorks() throws Exception {
        Files.createDirectories(part.resolve("wheels"));
        // Named like pip, and not pip.
        Files.writeString(part.resolve("wheels/pipdeptree-2.0.0-py3-none-any.whl"), "");

        assertThat(command()).doesNotContain("--no-index")
                .containsExactly(part.resolve("runtime/python.exe").toString(),
                        part.resolve("runtime/get-pip.py").toString(), "--no-warn-script-location", "--no-compile");
    }
}
