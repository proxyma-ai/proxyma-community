package ai.proxyma.core.indexstore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which leftover processes the startup orphan kill recognizes as a ChromaDB of this install.
 *
 * <p>On Windows the JDK reports no command line for another process - only its executable. The
 * match used to require the command line for the bundled-interpreter case, so on Windows it never
 * matched: an orphaned chroma kept port 8000, the new one could not bind it and exited, and the
 * application ran against the orphan it could not stop. These pin both platforms' shapes.
 */
class ChromaOrphanMatchTest {

    private static final Path PYTHON = Path.of("runtime", "python.exe").toAbsolutePath();
    private static final Optional<String> NONE = Optional.empty();

    @Test
    @DisplayName("Windows: no command line, the bundled interpreter itself - an orphan")
    void windowsInterpreterWithoutCommandLineMatches() {
        assertThat(ChromaStoreService.isOrphanChroma(NONE, Optional.of(PYTHON.toString()), PYTHON, true))
                .isTrue();
    }

    @Test
    @DisplayName("Windows: no command line, some other python - left alone")
    void windowsOtherInterpreterIsLeftAlone() {
        String other = Path.of("elsewhere", "python.exe").toAbsolutePath().toString();
        assertThat(ChromaStoreService.isOrphanChroma(NONE, Optional.of(other), PYTHON, true)).isFalse();
    }

    @Test
    @DisplayName("Where the command line is readable, only chroma's own invocation matches")
    void commandLineDecidesWhenPresent() {
        Optional<String> exe = Optional.of(PYTHON.toString());
        assertThat(ChromaStoreService.isOrphanChroma(
                Optional.of(PYTHON + " -c from chromadb.cli.cli import app; app() run --port 8000"),
                exe, PYTHON, true)).isTrue();
        assertThat(ChromaStoreService.isOrphanChroma(
                Optional.of(PYTHON + " -m markitdown report.docx"), exe, PYTHON, true)).isFalse();
    }

    @Test
    @DisplayName("Nothing known about a process - never matched")
    void unknownProcessIsNeverMatched() {
        assertThat(ChromaStoreService.isOrphanChroma(NONE, NONE, PYTHON, true)).isFalse();
        assertThat(ChromaStoreService.isOrphanChroma(NONE, NONE, PYTHON, false)).isFalse();
    }
}
