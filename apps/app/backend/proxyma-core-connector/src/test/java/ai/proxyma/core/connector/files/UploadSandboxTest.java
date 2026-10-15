package ai.proxyma.core.connector.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upload sandbox, which is a security boundary and was three separate implementations of one.
 *
 * <p>{@code LocalDiskStorageService} decided where to write, {@code ConnectorService} decided what
 * to accept at registration, and {@link UploadedFilesConnector} re-checked at every sync -
 * deliberately, as a second line behind the registration guard. A second line is only a defence
 * while it agrees with the first, and all three were about to be taught the single-user shape one
 * at a time. They are one function now, and this is the test it earned.
 *
 * <p><b>THE FIRST TEST IN {@code proxyma-core-connector}</b>, which is why that module joins the
 * {@code -pl} list in {@code tools/ci/gate.bat}, {@code CLAUDE.md} and {@code HANDOFF.md} in the
 * same commit. A test in an unlisted module is a test nobody runs.
 */
class UploadSandboxTest {

    private static final String ROOT = "/srv/proxyma";

    private static String abs(String p) {
        return Path.of(p).toAbsolutePath().normalize().toString();
    }

    // ── the two shapes ────────────────────────────────────────────────────────

    @Test
    @DisplayName("an account's sandbox carries its id; the single local user's does not")
    void bothShapes() {
        assertEquals(abs(ROOT + "/users/u-42/uploads"),
                UploadSandbox.rootFor(ROOT, "u-42").toString());
        assertEquals(abs(ROOT + "/uploads"),
                UploadSandbox.rootFor(ROOT, null).toString());
    }

    /**
     * A blank id is the single local user too, not an account named "". Server mode never produces
     * one - FileController refuses a null caller where uploads are attributed - but a blank
     * reaching here must not build {@code {root}/users//uploads}, which normalizes to the wrong
     * directory rather than to an error.
     */
    @Test
    @DisplayName("a blank owner is the single local user, not an account with an empty name")
    void blankOwnerIsTheLocalUser() {
        assertEquals(UploadSandbox.rootFor(ROOT, null), UploadSandbox.rootFor(ROOT, "  "));
    }

    // ── containment, which is the actual boundary ─────────────────────────────

    @Test
    @DisplayName("the sandbox root itself is inside it - that is how a user indexes everything")
    void rootIsContained() {
        assertTrue(UploadSandbox.contains(ROOT, "u-42", Path.of(ROOT + "/users/u-42/uploads")));
        assertTrue(UploadSandbox.contains(ROOT, null, Path.of(ROOT + "/uploads")));
    }

    /**
     * The whole reason the check exists. A registered path is WALKED and indexed, so one pointing
     * at the storage root would read every other account's uploads and whatever key material sits
     * beside them.
     */
    @Test
    @DisplayName("one account cannot reach another's, and neither can reach the storage root")
    void tenantsAreSeparated() {
        assertFalse(UploadSandbox.contains(ROOT, "u-42", Path.of(ROOT + "/users/u-99/uploads")));
        assertFalse(UploadSandbox.contains(ROOT, "u-42", Path.of(ROOT + "/users")));
        assertFalse(UploadSandbox.contains(ROOT, "u-42", Path.of(ROOT)));
        assertFalse(UploadSandbox.contains(ROOT, null, Path.of(ROOT)));
        assertFalse(UploadSandbox.contains(ROOT, null, Path.of("/etc")));
    }

    /**
     * The single-user sandbox is NOT a parent of the per-account ones and must not be treated as
     * one. {@code {root}/uploads} and {@code {root}/users/x/uploads} are siblings; a deployment
     * only ever has one shape, and a check that let one see the other would be a tenant escape the
     * day anything ran both.
     */
    @Test
    @DisplayName("the single-user sandbox and an account's are siblings, not nested")
    void theTwoShapesDoNotSeeEachOther() {
        assertFalse(UploadSandbox.contains(ROOT, null, Path.of(ROOT + "/users/u-42/uploads")));
        assertFalse(UploadSandbox.contains(ROOT, "u-42", Path.of(ROOT + "/uploads")));
    }

    /**
     * A LOCAL_FOLDER source is a directory on the user's own disk that Proxyma indexes by reference
     * and must never write to, move or delete. It is refused here on its PATH - which is what lets
     * the cleanup paths stop keying that decision off a null owner, where a home server's genuine
     * single local user would have been mistaken for "nothing of ours to sweep".
     */
    @Test
    @DisplayName("a folder on the user's own disk is outside every sandbox")
    void aRegisteredHostFolderIsNeverInside() {
        assertFalse(UploadSandbox.contains(ROOT, null, Path.of("/home/someone/Documents")));
        assertFalse(UploadSandbox.isAnyUploadPath(ROOT, Path.of("/home/someone/Documents")));
    }

    // ── traversal ─────────────────────────────────────────────────────────────

    /**
     * {@code folder} is client-supplied - a multipart field, and a name like {@code ../../etc} is
     * the obvious thing to send. It is rejected HERE so no caller can resolve a folder without the
     * check having run, which is the difference between a shared helper and a shared convention.
     */
    @Test
    @DisplayName("a folder name that climbs out is refused, not normalized into somewhere else")
    void traversalIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> UploadSandbox.folderIn(ROOT, "u-42", "../../etc"));
        assertThrows(IllegalArgumentException.class,
                () -> UploadSandbox.folderIn(ROOT, null, "../.local/share/proxyma"));
    }

    @Test
    @DisplayName("an ordinary nested folder resolves inside the sandbox")
    void nestedFolderResolves() {
        assertEquals(abs(ROOT + "/uploads/specs/autosar"),
                UploadSandbox.folderIn(ROOT, null, "specs/autosar").toString());
        assertTrue(UploadSandbox.contains(ROOT, null, UploadSandbox.folderIn(ROOT, null, "specs")));
    }

    @Test
    @DisplayName("no folder name means the sandbox root")
    void blankFolderIsTheRoot() {
        assertEquals(UploadSandbox.rootFor(ROOT, "u-42"), UploadSandbox.folderIn(ROOT, "u-42", null));
        assertEquals(UploadSandbox.rootFor(ROOT, "u-42"), UploadSandbox.folderIn(ROOT, "u-42", " "));
    }

    // ── the two cleanup predicates ────────────────────────────────────────────

    /**
     * {@code isAnyUploadPath} answers "is this a directory Proxyma owns", without saying whose -
     * the per-row delete path holds a storage path and no owner to check it against. It is NOT an
     * access check, and the separation test above is what keeps that from being confused with one.
     */
    @Test
    @DisplayName("both shapes are recognised as ours, and nothing else is")
    void anyUploadPathSpansBothShapes() {
        assertTrue(UploadSandbox.isAnyUploadPath(ROOT, Path.of(ROOT + "/users/u-1/uploads/a/b.pdf")));
        assertTrue(UploadSandbox.isAnyUploadPath(ROOT, Path.of(ROOT + "/uploads/a/b.pdf")));
        assertFalse(UploadSandbox.isAnyUploadPath(ROOT, Path.of(ROOT + "/users/u-1/secrets/k.pem")));
        assertFalse(UploadSandbox.isAnyUploadPath(ROOT, Path.of(ROOT + "/keys/signing.pem")));
    }

    /**
     * The stop condition for the climb that removes directories it has just emptied. Getting it
     * wrong upward means deleting the sandbox itself and then its ancestors: the previous version
     * of this check compared a directory NAME against "uploads", and a name match is not a
     * containment check.
     */
    @Test
    @DisplayName("an upload root is a root, and something inside one is not")
    void uploadRootIsRecognised() {
        assertTrue(UploadSandbox.isUploadRoot(ROOT, Path.of(ROOT + "/uploads")));
        assertTrue(UploadSandbox.isUploadRoot(ROOT, Path.of(ROOT + "/users/u-1/uploads")));
        assertFalse(UploadSandbox.isUploadRoot(ROOT, Path.of(ROOT + "/uploads/specs")));
        assertFalse(UploadSandbox.isUploadRoot(ROOT, Path.of(ROOT + "/users/u-1")));
        assertFalse(UploadSandbox.isUploadRoot(ROOT, Path.of(ROOT)));
        // A directory merely NAMED uploads, somewhere it has no business being.
        assertFalse(UploadSandbox.isUploadRoot(ROOT, Path.of("/tmp/uploads")));
    }

    @Test
    @DisplayName("a null candidate is outside everything rather than a NullPointerException")
    void nullCandidateIsOutside() {
        assertFalse(UploadSandbox.contains(ROOT, "u-1", null));
        assertFalse(UploadSandbox.isAnyUploadPath(ROOT, null));
        assertFalse(UploadSandbox.isUploadRoot(ROOT, null));
    }
}
