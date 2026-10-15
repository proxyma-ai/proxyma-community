package ai.proxyma.desktop.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Reset everything" (editions-assembly-04, -05): carried out at the next start whoever starts it,
 * covering what lives outside the data directory, never touching what must survive, and never
 * waiting for an unrelated restart to wipe everything days later.
 */
class PendingResetTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    private static Path file(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(name), "x");
    }

    @Test
    void aRecentRequestDeletesTheDataAndWhatTheAppListed(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("proxyma");
        Path conversations = tmp.resolve("home/.proxyma/conversations");
        Path skills = tmp.resolve("home/.proxyma/skills");
        file(data.resolve("lucene"), "segments_1");
        file(data.resolve("repos"), "snapshot");
        file(data, "chat_history.mv.db");
        file(data, "local_folders.lock.db");
        file(conversations.resolve("c1"), "messages.json");
        file(skills, "SKILL.md");
        // What must survive.
        file(data, "license.dat");
        file(data, ".secret");
        file(data, "access-code");
        file(data.resolve("parts"), "active.txt");
        file(data.resolve("runtime"), "python.exe");

        PendingReset.request(data, List.of(conversations, skills), NOW.minusSeconds(5));
        assertThat(PendingReset.applyIfRequested(data, NOW)).isTrue();

        assertThat(data.resolve("lucene")).doesNotExist();
        assertThat(data.resolve("repos")).doesNotExist();
        assertThat(data.resolve("chat_history.mv.db")).doesNotExist();
        assertThat(data.resolve("local_folders.lock.db")).doesNotExist();
        assertThat(conversations).as("conversations live in ~/.proxyma, which the reset used to miss").doesNotExist();
        assertThat(skills).doesNotExist();
        for (String kept : List.of("license.dat", ".secret", "access-code", "parts/active.txt", "runtime/python.exe")) {
            assertThat(data.resolve(kept)).as(kept).exists();
        }
        assertThat(data.resolve(PendingReset.MARKER)).doesNotExist();
        assertThat(PendingReset.applyIfRequested(data, NOW)).as("done once").isFalse();
    }

    @Test
    void anOldRequestIsCancelledNotCarriedOut(@TempDir Path data) throws Exception {
        file(data, "chat_history.mv.db");
        PendingReset.request(data, List.of(), NOW.minus(Duration.ofDays(2)));
        assertThat(PendingReset.applyIfRequested(data, NOW)).isFalse();
        assertThat(data.resolve("chat_history.mv.db")).as("a stray marker wiped nothing").exists();
        assertThat(data.resolve(PendingReset.MARKER)).doesNotExist();
    }

    @Test
    void aMarkerFromAnOlderBuildIsDatedByItsFile(@TempDir Path data) throws Exception {
        file(data, "chat_history.mv.db");
        Files.writeString(data.resolve(PendingReset.MARKER), "Written by Proxyma when the user chose Reset everything.\n");
        // Its file time is now - recent, so it is carried out.
        assertThat(PendingReset.applyIfRequested(data, Instant.now())).isTrue();
        assertThat(data.resolve("chat_history.mv.db")).doesNotExist();
    }

    @Test
    void aListedPathThatIsNotAPlaceToDeleteIsLeftAlone(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("a/b/proxyma");
        Files.createDirectories(data);
        assertThat(PendingReset.safeToDelete(tmp.resolve("a"), data)).as("a parent of the data root").isFalse();
        assertThat(PendingReset.safeToDelete(Path.of("relative/dir"), data)).isFalse();
        assertThat(PendingReset.safeToDelete(Path.of(System.getProperty("user.home")), data)).isFalse();
        assertThat(PendingReset.safeToDelete(tmp.getRoot(), data)).isFalse();
        assertThat(PendingReset.safeToDelete(tmp.resolve("home/.proxyma/skills"), data)).isTrue();
    }
}
