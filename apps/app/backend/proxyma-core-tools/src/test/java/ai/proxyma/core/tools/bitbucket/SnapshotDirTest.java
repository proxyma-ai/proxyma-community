package ai.proxyma.core.tools.bitbucket;

import ai.proxyma.core.system.util.AppPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A snapshot's directory is one segment under the snapshots folder, and two refs or repositories
 * that sanitize alike still get two directories - "feature/x" and "feature-x" replaced each other.
 */
class SnapshotDirTest {

    @Test
    @DisplayName("distinct project, repository and ref give distinct directories, all under the snapshots folder")
    void snapshotDirsDoNotCollide() {
        Path root = Path.of("snapshots").toAbsolutePath();
        AppPaths paths = mock(AppPaths.class);
        when(paths.repoSnapshotsDir()).thenReturn(root);
        BitbucketToolService service = new BitbucketToolService(null, null, null, paths);

        Set<Path> dirs = new HashSet<>();
        for (List<String> c : List.of(List.of("P", "r", "feature/x"), List.of("P", "r", "feature-x"),
                List.of("P_A", "b", "main"), List.of("P", "A_b", "main"), List.of("P", "r", "../../etc"))) {
            Path dir = service.snapshotDir(c.get(0), c.get(1), c.get(2));
            assertThat(dir.getParent()).isEqualTo(root.resolve("local"));
            dirs.add(dir);
        }
        assertThat(dirs).hasSize(5);
    }
}
