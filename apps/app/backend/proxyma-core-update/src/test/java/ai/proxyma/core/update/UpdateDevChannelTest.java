package ai.proxyma.core.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dev-build channel: how dev builds order against each other and against releases, which
 * release a given installation picks from the list.
 */
class UpdateDevChannelTest {

    @Test
    @DisplayName("dev builds of one version are ordered by their number")
    void devBuildsAreOrdered() {
        assertTrue(UpdateService.isNewer("v0.12.1-dev58", "0.12.1-dev57"));
        assertFalse(UpdateService.isNewer("v0.12.1-dev57", "0.12.1-dev58"));
        assertFalse(UpdateService.isNewer("v0.12.1-dev57", "0.12.1-dev57"));
        // Numeric, not by string: dev100 is after dev99.
        assertTrue(UpdateService.isNewer("v0.12.1-dev100", "0.12.1-dev99"));
    }

    @Test
    @DisplayName("a dev build is newer than the release it follows, older than the next one")
    void devAgainstReleases() {
        assertTrue(UpdateService.isNewer("v0.12.1-dev57", "0.12.0"));
        assertTrue(UpdateService.isNewer("v0.12.1", "0.12.1-dev57"));
        assertFalse(UpdateService.isNewer("v0.12.0", "0.12.1-dev57"));
    }

    @Test
    @DisplayName("other prereleases stay unordered")
    void otherPrereleasesUnordered() {
        assertFalse(UpdateService.isNewer("v1.0.0-rc2", "1.0.0-rc1"));
        assertEquals(-1, UpdateService.devNumber("1.0.0-rc2"));
        assertEquals(-1, UpdateService.devNumber("1.0.0-devx"));
        assertEquals(42, UpdateService.devNumber("v1.0.0-dev42"));
    }

    private static final String LIST = """
            [
              {"tag_name":"v0.12.1-dev60","draft":true,"prerelease":true,
               "assets":[{"name":"proxyma-x64-v0.12.1-dev60.deb","browser_download_url":"u"},{"name":"SHA256SUMS.txt","browser_download_url":"s"},{"name":"SHA256SUMS.txt.sig"}]},
              {"tag_name":"v0.12.1-dev59","draft":false,"prerelease":true,
               "assets":[{"name":"proxyma-x64-v0.12.1-dev59.deb","browser_download_url":"u59"},{"name":"SHA256SUMS.txt","browser_download_url":"s59"},{"name":"SHA256SUMS.txt.sig"}]},
              {"tag_name":"v0.12.1-dev58","draft":false,"prerelease":true,
               "assets":[{"name":"proxyma-x64-v0.12.1-dev58-update.zip","browser_download_url":"z58"},
                         {"name":"proxyma-x64-v0.12.1-dev58.deb","browser_download_url":"u58"},{"name":"SHA256SUMS.txt","browser_download_url":"s58"},{"name":"SHA256SUMS.txt.sig"}]},
              {"tag_name":"v0.12.0","draft":false,"prerelease":false,
               "assets":[{"name":"proxyma-x64-v0.12.0-update.zip","browser_download_url":"z0"},{"name":"SHA256SUMS.txt","browser_download_url":"s0"},{"name":"SHA256SUMS.txt.sig"}]}
            ]""";

    @Test
    @DisplayName("each installation takes the newest non-draft release that carries ITS artifact")
    void choosesPerChannel() {
        String linux = UpdateService.chooseRelease(LIST, n -> n.endsWith(".deb"), true);
        assertTrue(linux.contains("\"v0.12.1-dev59\""), linux);
        // Windows: dev59 has no update.zip (nobody built one on the PC), so dev58 is the answer.
        String windows = UpdateService.chooseRelease(LIST, n -> n.endsWith("-update.zip"), true);
        assertTrue(windows.contains("\"v0.12.1-dev58\""), windows);
        // Detect-only needs no artifact: the newest version is the whole answer.
        String docker = UpdateService.chooseRelease(LIST, n -> false, false);
        assertTrue(docker.contains("\"v0.12.1-dev59\""), docker);
        // Nothing matches: no answer, rather than an arbitrary release.
        assertNull(UpdateService.chooseRelease(LIST, n -> n.endsWith(".rpm"), true));
    }

    @Test
    @DisplayName("a release without SHA256SUMS.txt is never chosen - it could not be installed")
    void requiresChecksums() {
        String list = """
                [{"tag_name":"v0.12.1-dev61","draft":false,"assets":[{"name":"a.deb"}]},
                 {"tag_name":"v0.12.1-dev59","draft":false,"assets":[{"name":"b.deb"},{"name":"SHA256SUMS.txt"},{"name":"SHA256SUMS.txt.sig"}]}]""";
        assertTrue(UpdateService.chooseRelease(list, n -> n.endsWith(".deb"), true).contains("dev59"));
    }

    @Test
    @DisplayName("an unsigned release is never chosen from the list")
    void unsignedReleasesAreSkipped() {
        String list = """
                [{"tag_name":"v1.0.1-dev9","draft":false,"assets":[{"name":"a.deb"},{"name":"SHA256SUMS.txt"}]},
                 {"tag_name":"v1.0.0","draft":false,"assets":[{"name":"b.deb"},{"name":"SHA256SUMS.txt"},{"name":"SHA256SUMS.txt.sig"}]}]""";
        String chosen = UpdateService.chooseRelease(list, n -> n.endsWith(".deb"), true);
        assertTrue(chosen.contains("v1.0.0"), chosen);
        assertFalse(chosen.contains("v1.0.1-dev9"), chosen);
    }
}
