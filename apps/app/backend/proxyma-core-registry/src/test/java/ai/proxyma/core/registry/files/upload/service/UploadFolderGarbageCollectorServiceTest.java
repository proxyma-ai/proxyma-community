package ai.proxyma.core.registry.files.upload.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The upload garbage collector, on a deployment with NO ACCOUNTS.
 *
 * <p><b>WHY THIS EXISTS, and it is the most dangerous code added in 0.8.0.</b> This sweep decides
 * which directories under the upload sandbox are orphaned and deletes them. It was written when
 * uploads were server-only, where every uploader is an account; the home server uploads too and has
 * no accounts at all, so its single local user is a NULL owner - and a null reaching a derived JPA
 * finder becomes {@code where user_id = null}, which is UNKNOWN in SQL and matches nothing.
 *
 * <p>An empty "known good" set means every registered folder looks orphaned. The sweep runs daily
 * with a 24-hour grace period, so the failure is not visible on the day it is introduced: a
 * customer's home server would index everything, work perfectly, and lose every uploaded file
 * the following day. There is no test that could have been written after the fact.
 *
 * <p>So these are about the NULL, specifically. {@code sweep()} itself is package-private-friendly
 * through {@link FileSourceMode}, which is what decides whether there are accounts to enumerate.
 */
class UploadFolderGarbageCollectorServiceTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempRoot;

    private StorageService storage;
    private RegisteredConnectorRepository connectors;
    private FileMetadataRepository files;

    @BeforeEach
    void setUp() {
        storage = mock(StorageService.class);
        connectors = mock(RegisteredConnectorRepository.class);
        files = mock(FileMetadataRepository.class);
        when(files.findDistinctFoldersByUserId(any())).thenReturn(List.of());
        when(connectors.findByUserIdIsNullAndConnectorType(any())).thenReturn(List.of());
        // any(), NOT anyString(): anyString() does not match null, so a regression that passed a
        // null here would get an unstubbed null back, NPE, be swallowed by the sweep's own catch,
        // and delete nothing - which every assertion below would have read as correct behaviour.
        when(connectors.findByUserIdAndConnectorType(any(), any())).thenReturn(List.of());
        when(storage.listUserIdsWithUploads()).thenReturn(List.of());
        when(storage.listTopLevelFolders(any())).thenReturn(List.of());
    }

    /** multi-user false, file-upload true - the home server. */
    private UploadFolderGarbageCollectorService singleUser() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FileSourceMode.FILE_UPLOAD_PROPERTY, "true");
        return new UploadFolderGarbageCollectorService(storage, connectors, files, new FileSourceMode(env));
    }

    /** multi-user true - the server edition. */
    private UploadFolderGarbageCollectorService multiUser() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FileSourceMode.MULTI_USER_PROPERTY, "true");
        return new UploadFolderGarbageCollectorService(storage, connectors, files, new FileSourceMode(env));
    }

    private static RegisteredConnector uploaded(String name, String config) {
        RegisteredConnector r = new RegisteredConnector();
        r.setName(name);
        r.setConfig(config);
        r.setConnectorType(ConnectorType.UPLOADED_FILES);
        return r;
    }

    // ── the null owner ──────────────────────────────────────────────────────

    /**
     * THE ONE THAT MATTERS. A deployment with no accounts has one sandbox belonging to a null
     * owner, and {@code listUserIdsWithUploads()} cannot find it - there is no account directory to
     * list. If the sweep enumerated only that, it would visit nothing, which is safe; the reason
     * this is asserted is the opposite risk, that somebody "fixes" the empty list by falling back
     * to something that does visit folders without being able to tell which are registered.
     */
    @Test
    @DisplayName("with no accounts the sweep visits the single local user's sandbox")
    void singleUserSandboxIsSwept() {
        singleUser().sweep();
        verify(storage).listTopLevelFolders(null);
        verify(storage, never()).listUserIdsWithUploads();
    }

    /**
     * THE DATA-LOSS CASE, stated as an assertion. A registered folder must be recognised as
     * known-good through the NULL-SAFE query, because the derived one silently matches nothing for
     * a null owner - and anything not recognised is deleted once it is a day old.
     */
    @Test
    @DisplayName("a registered folder owned by the local user is never swept")
    void registeredFolderIsNotOrphaned() throws Exception {
        // A REAL directory, aged past the grace period. The first version of this test used a
        // made-up path, and that made it THEATRE: a path that does not exist can never pass the
        // grace-period check, so the sweep declined to delete it for a reason that had nothing to
        // do with whether it was recognised as registered. It passed against deliberately broken
        // code. Proved by reverting the null-safe query and watching this still report green.
        java.nio.file.Path specs = java.nio.file.Files.createDirectory(tempRoot.resolve("Specs"));
        java.nio.file.Files.setLastModifiedTime(specs, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofHours(48))));

        when(connectors.findByUserIdIsNullAndConnectorType(ConnectorType.UPLOADED_FILES))
                .thenReturn(List.of(uploaded("Specs", specs.toString())));
        when(storage.listTopLevelFolders(null)).thenReturn(List.of(specs.toString()));

        singleUser().sweep();

        verify(storage, never()).deleteFolderRecursively(any(), anyString());
    }

    /**
     * The null-safe query is a DIFFERENT method, and this is what stops somebody collapsing the two
     * branches back into one call with a null argument - which compiles, reads correctly, and
     * deletes everything.
     */
    @Test
    @DisplayName("the local user is looked up by the null-safe query, never by passing null")
    void nullOwnerUsesItsOwnQuery() {
        singleUser().sweep();
        verify(connectors).findByUserIdIsNullAndConnectorType(ConnectorType.UPLOADED_FILES);
        verify(connectors, never()).findByUserIdAndConnectorType(any(), any());
    }

    /**
     * A folder that IS genuinely orphaned still has to be swept, or the test above would pass just
     * as well against a sweep that had been disabled - which is the other way to make this file
     * report green while the behaviour is wrong.
     */
    @Test
    @DisplayName("an unregistered folder is still swept, so the test above means something")
    void orphanedFolderIsStillRemoved() throws Exception {
        // A REAL directory, aged past the grace period by setting its mtime - not a made-up path.
        // The first version of this test used one that did not exist and failed, which turned out
        // to be the sweep being right: isOlderThanGracePeriod() cannot read the age of a missing
        // directory and returns "not old enough" rather than guessing, so nothing is deleted on
        // the strength of a path it could not stat. That is the correct instinct for a method
        // whose other branch removes files, and the test was wrong rather than the code.
        java.nio.file.Path orphan = java.nio.file.Files.createDirectory(tempRoot.resolve("Leftover"));
        java.nio.file.Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofHours(48))));
        when(storage.listTopLevelFolders(null)).thenReturn(List.of(orphan.toString()));

        singleUser().sweep();

        verify(storage).deleteFolderRecursively(null, orphan.toString());
    }

    // ── nested folder names, and the desktop ────────────────────────────────

    private java.nio.file.Path agedDirectory(String relative) throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createDirectories(tempRoot.resolve(relative));
        java.nio.file.attribute.FileTime old = java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofHours(48)));
        for (java.nio.file.Path p = dir; !p.equals(tempRoot); p = p.getParent()) {
            java.nio.file.Files.setLastModifiedTime(p, old);
        }
        return dir;
    }

    /**
     * A folder name may hold a separator - UploadSandbox.folderIn accepts "Clients/Acme" - and the
     * sweep lists only the top level, "Clients". It compared that with the whole name and with the
     * config's last segment, matched neither, and deleted the registered source a day later.
     */
    @Test
    @DisplayName("a top-level folder holding a nested upload folder is never swept")
    void nestedUploadFolderIsKept() throws Exception {
        agedDirectory("Clients/Acme");
        java.nio.file.Path clients = tempRoot.resolve("Clients");
        when(files.findDistinctFoldersByUserId(null)).thenReturn(List.of("Clients/Acme"));
        when(storage.listTopLevelFolders(null)).thenReturn(List.of(clients.toString()));

        singleUser().sweep();

        verify(storage, never()).deleteFolderRecursively(any(), anyString());
    }

    @Test
    @DisplayName("a source registered at a nested folder keeps its top-level folder")
    void nestedRegisteredSourceIsKept() throws Exception {
        java.nio.file.Path acme = agedDirectory("Clients/Acme");
        java.nio.file.Path clients = tempRoot.resolve("Clients");
        when(connectors.findByUserIdIsNullAndConnectorType(ConnectorType.UPLOADED_FILES))
                .thenReturn(List.of(uploaded("Acme", acme.toString())));
        when(storage.listTopLevelFolders(null)).thenReturn(List.of(clients.toString()));

        singleUser().sweep();

        verify(storage, never()).deleteFolderRecursively(any(), anyString());
    }

    @Test
    @DisplayName("a source registered at the sandbox root keeps every folder in it")
    void sandboxRootSourceKeepsEverything() throws Exception {
        java.nio.file.Path leftover = agedDirectory("Leftover");
        when(connectors.findByUserIdIsNullAndConnectorType(ConnectorType.UPLOADED_FILES))
                .thenReturn(List.of(uploaded("uploads", tempRoot.toString())));
        when(storage.listTopLevelFolders(null)).thenReturn(List.of(leftover.toString()));

        singleUser().sweep();

        verify(storage, never()).deleteFolderRecursively(any(), anyString());
    }

    /**
     * The desktop takes no uploads, and its storage root defaults to /data - C:/data on Windows, a
     * directory that is the user's own. The sweep must not so much as list it there.
     */
    @Test
    @DisplayName("on a desktop install, which takes no uploads, nothing is listed or swept")
    void desktopIsNeverSwept() throws Exception {
        java.nio.file.Path orphan = agedDirectory("Leftover");
        when(storage.listTopLevelFolders(any())).thenReturn(List.of(orphan.toString()));
        UploadFolderGarbageCollectorService desktop = new UploadFolderGarbageCollectorService(
                storage, connectors, files, new FileSourceMode(new MockEnvironment()));

        desktop.sweep();

        verify(storage, never()).listTopLevelFolders(any());
        verify(storage, never()).deleteFolderRecursively(any(), anyString());
    }

    // ── the server edition is unchanged ─────────────────────────────────────

    @Test
    @DisplayName("with accounts the sweep still enumerates them, one sandbox each")
    void multiUserStillEnumeratesAccounts() {
        when(storage.listUserIdsWithUploads()).thenReturn(List.of("u-1", "u-2"));

        multiUser().sweep();

        verify(storage).listTopLevelFolders("u-1");
        verify(storage).listTopLevelFolders("u-2");
        verify(storage, never()).listTopLevelFolders(null);
    }

    @Test
    @DisplayName("with accounts a registered folder is looked up by the owner-scoped query")
    void multiUserUsesTheOwnerScopedQuery() {
        when(storage.listUserIdsWithUploads()).thenReturn(List.of("u-1"));

        multiUser().sweep();

        verify(connectors).findByUserIdAndConnectorType(eq("u-1"), eq(ConnectorType.UPLOADED_FILES));
        verify(connectors, never()).findByUserIdIsNullAndConnectorType(any());
    }

    /**
     * A sweep that throws would kill the scheduled task for the life of the process, and the next
     * thing anybody would notice is the disk filling months later.
     */
    @Test
    @DisplayName("a storage failure is swallowed rather than killing the schedule")
    void failuresNeverEscape() {
        when(storage.listTopLevelFolders(any())).thenThrow(new IllegalStateException("disk gone"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> singleUser().sweep());
    }
}
