package ai.proxyma.core.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UpdateService#isNewer} decides whether to offer a user an update, so getting it wrong
 * means either never offering one or offering a downgrade.
 *
 * The qualifier case below was a KNOWN DEFECT, recorded in HANDOFF.md as "mis-sorts qualifier
 * tags - 1.2.1-rc1 parses as 1.2.11" and deferred on the grounds that it was latent while
 * /releases/latest excludes prereleases. It was: {@code parse} did
 * {@code replaceAll("[^0-9.]", "")}, which deletes the hyphen and the letters and glues the
 * remaining digits onto the patch number. Fixed 2026-09-03 along with these tests, because a
 * comparator that is wrong only when a feature is switched on is a trap laid for whoever
 * switches it on.
 */
class UpdateVersionTest {

    @Nested
    @DisplayName("ordinary releases")
    class Ordinary {
        @Test
        void patchIsNewer() {
            assertTrue(UpdateService.isNewer("1.4.3", "1.4.2"));
        }

        @Test
        void minorIsNewer() {
            assertTrue(UpdateService.isNewer("1.5.0", "1.4.9"));
        }

        @Test
        void majorIsNewer() {
            assertTrue(UpdateService.isNewer("2.0.0", "1.9.9"));
        }

        @Test
        void sameIsNotNewer() {
            assertFalse(UpdateService.isNewer("1.4.2", "1.4.2"));
        }

        @Test
        void olderIsNotNewer() {
            assertFalse(UpdateService.isNewer("1.4.1", "1.4.2"));
        }

        @Test
        @DisplayName("a leading v is conventional on the tag and must not change the answer")
        void leadingVIsIgnored() {
            assertTrue(UpdateService.isNewer("v1.4.3", "1.4.2"));
            assertFalse(UpdateService.isNewer("v1.4.2", "1.4.2"));
        }

        @Test
        @DisplayName("10 is newer than 9, not older - the old code was digit-safe here, keep it so")
        void doubleDigitsCompareNumerically() {
            assertTrue(UpdateService.isNewer("0.10.0", "0.9.0"));
            assertFalse(UpdateService.isNewer("0.9.0", "0.10.0"));
        }

        @Test
        @DisplayName("a 0 major compares like any other - 0.10.0 is the next release after 0.9.0")
        void zeroMajor() {
            assertTrue(UpdateService.isNewer("0.9.1", "0.9.0"));
            assertFalse(UpdateService.isNewer("0.0.1", "0.9.0"));
        }
    }

    @Nested
    @DisplayName("qualifier tags - the defect this file was written for")
    class Qualifiers {
        @Test
        @DisplayName("1.2.1-rc1 is NOT newer than 1.2.1 (it used to parse as 1.2.11)")
        void prereleaseIsNotNewerThanItsRelease() {
            assertFalse(UpdateService.isNewer("1.2.1-rc1", "1.2.1"));
        }

        @Test
        @DisplayName("and it is not newer than the release before it either")
        void prereleaseDoesNotLeapfrog() {
            assertFalse(UpdateService.isNewer("1.2.1-rc1", "1.2.5"));
        }

        @Test
        @DisplayName("the finished release IS newer than its own release candidate")
        void releaseBeatsItsPrerelease() {
            assertTrue(UpdateService.isNewer("1.2.1", "1.2.1-rc1"));
        }

        @Test
        @DisplayName("a later version with a qualifier still beats an earlier one without")
        void qualifierDoesNotBlockARealUpgrade() {
            assertTrue(UpdateService.isNewer("1.3.0-rc1", "1.2.9"));
        }

        @Test
        @DisplayName("build metadata is not precedence - 1.4.2+build7 equals 1.4.2")
        void buildMetadataIsIgnored() {
            assertFalse(UpdateService.isNewer("1.4.2+build7", "1.4.2"));
            assertFalse(UpdateService.isNewer("1.4.2", "1.4.2+build7"));
        }
    }

    @Nested
    @DisplayName("malformed input must not throw - it arrives from a remote API")
    class Malformed {
        @Test
        void emptyIsNotNewer() {
            assertFalse(UpdateService.isNewer("", "1.4.2"));
        }

        @Test
        void gibberishIsNotNewer() {
            assertFalse(UpdateService.isNewer("not-a-version", "1.4.2"));
        }

        @Test
        @DisplayName("a short tag is padded with zeros rather than rejected")
        void shortTagsWork() {
            assertTrue(UpdateService.isNewer("2", "1.9.9"));
            assertFalse(UpdateService.isNewer("1.4", "1.4.2"));
        }
    }
}
