package ai.proxyma.core.registry.files.upload.storage;

import ai.proxyma.core.connector.files.UploadSandbox;
import ai.proxyma.core.registry.files.upload.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;

@Component
public class LocalDiskStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskStorageService.class);

    @Value("${proxyma.storage.local.root:/data}")
    private String storageRoot;

    @Override
    public String store(UUID fileId, String originalName, InputStream content, long sizeBytes,
                        String userId, String folder, String relativePath) {
        Path folderRoot = userUploadDir(userId, folder);
        Path dir = resolveSubPath(folderRoot, stripDuplicateFolderPrefix(folder, relativePath));
        // originalName is the client-supplied multipart filename - observed in production
        // (2026-07-27) to sometimes carry the file's FULL path from the drop root
        // (e.g. "ChangAn_M3/AGENTS.md") instead of a bare basename, which - because dir is
        // ALREADY folder+relativePath - made dir.resolve(originalName) silently duplicate the
        // whole chain a second time (".../ChangAn_M3/ChangAn_M3/AGENTS.md") and then fail with
        // NoSuchFileException since nothing ever created that bogus nested directory. relativePath
        // is the single source of truth for directory structure; originalName must contribute
        // ONLY the leaf filename, so take its basename unconditionally before resolving - this
        // also strengthens the existing traversal guard below (a basename can never escape dir).
        String baseName = basenameOf(originalName);
        Path dest = dir.resolve(baseName).normalize();
        if (!dest.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("Invalid file name: " + originalName);
        }
        // Refused BEFORE a byte is written: a path the file's row cannot hold used to be written
        // first and then fail the row's insert, leaving a file nothing tracked or counted.
        if (dest.toString().length() > ai.proxyma.core.registry.files.upload.model.FileMetadata.STORAGE_PATH_MAX
                || baseName.length() > ai.proxyma.core.registry.files.upload.model.FileMetadata.NAME_MAX) {
            throw new IllegalArgumentException("The path of " + baseName + " is too long to store - "
                    + "upload it from a folder with a shorter name or fewer levels.");
        }
        try {
            Files.createDirectories(dir);
            Files.copy(content, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest.toString();
        } catch (IOException e) {
            // e.getMessage() on a FileSystemException includes the full absolute server path -
            // log it here (server-side only) but never forward it to the client, which only
            // needs to know which of ITS files failed.
            log.error("Failed to store file '{}' at {}", originalName, dest, e);
            throw new IllegalStateException("Failed to store file: " + originalName, e);
        }
    }

    // Defense against a client sending a relativePath that already duplicates the destination
    // folder itself (observed in production: folder="A/B/C", relativePath="A/B/C", producing
    // .../A/B/C/A/B/C/file.c on disk - a directory chain nothing ever created, which then fails
    // with NoSuchFileException on write). Only strips an EXACT, full duplicate of folder as
    // relativePath's leading segment(s) - a partial/coincidental overlap (e.g. a genuine nested
    // folder that happens to share only its last segment's name) is left untouched rather than
    // guessed at.
    private String stripDuplicateFolderPrefix(String folder, String relativePath) {
        if (folder == null || folder.isBlank() || relativePath == null || relativePath.isBlank()) {
            return relativePath;
        }
        String normalizedFolder = folder.replace('\\', '/').replaceAll("/+$", "");
        String normalizedRelative = relativePath.replace('\\', '/');
        String deduped = null;
        if (normalizedRelative.equals(normalizedFolder)) {
            deduped = "";
        } else if (normalizedRelative.startsWith(normalizedFolder + "/")) {
            deduped = normalizedRelative.substring(normalizedFolder.length() + 1);
        }
        if (deduped != null) {
            log.warn("relativePath '{}' duplicated destination folder '{}' - stripping duplicate prefix to '{}'",
                    relativePath, folder, deduped);
            return deduped;
        }
        return relativePath;
    }

    // Strips any leading path components from a client-supplied filename, keeping only the
    // final segment - see the comment at the store() call site for why this must never be
    // trusted to already be a bare name.
    private String basenameOf(String originalName) {
        if (originalName == null) return "";
        String normalized = originalName.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    }

    // Resolves a (possibly empty) relative subpath under folderRoot, rejecting any path that
    // would escape it (e.g. "../../etc") - relativePath originates from the client-supplied
    // dropped-folder structure and must be treated as untrusted input.
    private Path resolveSubPath(Path folderRoot, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return folderRoot;
        Path resolved = folderRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(folderRoot.normalize())) {
            throw new IllegalArgumentException("Invalid relative path: " + relativePath);
        }
        return resolved;
    }

    @Override
    public InputStream retrieve(String storagePath) {
        try {
            return Files.newInputStream(Path.of(storagePath));
        } catch (IOException e) {
            log.error("Failed to retrieve file at {}", storagePath, e);
            throw new IllegalStateException("Failed to retrieve file: " + fileNameOf(storagePath), e);
        }
    }

    @Override
    public void delete(String storagePath, String userId, String folder) {
        // Jailed to an uploads sandbox, exactly like deleteFolderRecursively() below, and for a
        // stronger reason: this method deletes a FILE, and every path it is handed comes from a
        // FileMetadata row. Uploaded bytes always live under {storageRoot}/users/{userId}/uploads/,
        // so a path outside that is never something this service created and never something it may
        // remove - most importantly a DESKTOP "Local Folder" connector, whose config is a folder on
        // the user's own disk that Proxyma indexes strictly by reference and must never modify,
        // move or delete. Returning instead of throwing keeps a stray row from failing a connector
        // teardown that is otherwise fine; the row itself is still dropped by the caller.
        Path file = uploadsSandboxedOrNull(storagePath);
        if (file == null) {
            log.warn("Refusing to delete '{}': outside the uploads sandbox - left untouched on disk",
                    storagePath);
            return;
        }
        try {
            Files.deleteIfExists(file);
            // Climb up removing the directories this left empty - the dropped folder's own
            // subdirectories - and STOP BELOW THE NAMED UPLOAD FOLDER, which is never removed here.
            //
            // It used to climb on to the named folder and remove that too, stopping only at the
            // uploads root. The named folder is what an upload source is registered at, and
            // Enterprise Server deletes every upload's bytes once it is indexed, so indexing the
            // last file of a source deleted the source's folder - and the next sync of every fully
            // indexed upload source failed with "no longer on the server" while all its documents
            // were searchable (2026-10-10). A folder goes when its SOURCE goes,
            // deleteFolderRecursively() below, or when nothing accounts for it any more,
            // UploadFolderGarbageCollectorService - never because it happens to be empty.
            //
            // Every step is still also a containment check on the resolved PATH against the sandbox
            // root, never a name match against "uploads": a name match is not a containment check,
            // and a path that had escaped the sandbox would climb towards the filesystem root
            // deleting every empty ancestor. A folder that cannot be resolved, or that does not hold
            // this file, climbs nowhere - the file alone is removed.
            Path keep = namedFolderOrNull(userId, folder);
            Path dir = file.toAbsolutePath().getParent();
            while (keep != null && dir != null && !dir.equals(file.getRoot())
                    && dir.startsWith(keep) && !dir.equals(keep)
                    && startsWithUploadsRoot(dir) && !isUploadsRoot(dir)
                    && Files.isDirectory(dir) && isDirectoryEmpty(dir)) {
                Files.deleteIfExists(dir);
                dir = dir.getParent();
            }
        } catch (IOException e) {
            log.error("Failed to delete file at {}", storagePath, e);
            throw new IllegalStateException("Failed to delete file: " + fileNameOf(storagePath), e);
        }
    }

    // The named upload folder a stored file was written into - the directory delete()'s climb must
    // stop below - or null when it cannot be resolved inside that owner's sandbox. A blank folder is
    // the sandbox root itself, which the climb never removes either.
    private Path namedFolderOrNull(String userId, String folder) {
        try {
            return userUploadDir(userId, folder == null ? null : folder.trim());
        } catch (IllegalArgumentException e) { // an escaping name, or InvalidPathException
            return null;
        }
    }

    // The normalized path, or null when it does not sit strictly inside SOME user's uploads
    // directory. Deliberately matches {storageRoot}/users/*/uploads/** without being told which
    // user: delete(storagePath) is called from the per-row cleanup path, which has a storage path
    // but no userId to check it against.
    private Path uploadsSandboxedOrNull(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) return null;
        Path target;
        try {
            target = Path.of(storagePath).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        return startsWithUploadsRoot(target) && !isUploadsRoot(target) ? target : null;
    }

    // True when `candidate` is {storageRoot}/users/{anyUser}/uploads or anything beneath it.
    private boolean startsWithUploadsRoot(Path candidate) {
        return UploadSandbox.isAnyUploadPath(storageRoot, candidate);
    }

    private boolean isUploadsRoot(Path candidate) {
        return UploadSandbox.isUploadRoot(storageRoot, candidate);
    }

    @Override
    public String ensureFolder(String userId, String folder) {
        Path dir = userUploadDir(userId, folder);
        try {
            Files.createDirectories(dir);
            return dir.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("Failed to create folder '{}' for user {} at {}", folder, userId, dir, e);
            throw new IllegalStateException("Failed to create folder: " + folder, e);
        }
    }

    @Override
    public boolean folderExists(String userId, String folder) {
        return Files.isDirectory(userUploadDir(userId, folder));
    }

    @Override
    public boolean fileExists(String storagePath) {
        return Files.exists(Path.of(storagePath));
    }

    // Final sweep on connector removal, on top of the per-tracked-file deletes already done via
    // delete(storagePath) - catches directories/files a FAILED upload left on disk (createDirectories
    // succeeds, then the write itself fails) that never got a FileMetadata row and so were never
    // individually tracked or deleted (see ../proxyma-docs/LESSONS_LEARNED.md 2026-07-27 entry). Scoped to
    // this user's own uploads sandbox so a FILES connector whose config is an arbitrary
    // directly-registered host path (see ConnectorService.registerFiles path-based overloads) is
    // never touched - only physicalPath values that actually live under
    // {storageRoot}/users/{userId}/uploads/ qualify.
    @Override
    public void deleteFolderRecursively(String userId, String physicalPath) {
        if (physicalPath == null || physicalPath.isBlank()) return;
        // A NULL userId USED TO MEAN "NOTHING TO SWEEP" AND NOW MEANS "THE SINGLE LOCAL USER".
        //
        // It was a deliberate no-op while uploads were server-only: a null owner was a desktop
        // install, whose file source is a folder on the user's own disk registered by reference,
        // and sweeping there would delete documents Proxyma does not own. The home server is a null
        // owner that DOES upload, so returning early would leave every deleted source's bytes on
        // the volume forever - the precise failure the UPLOADED_FILES type exists to prevent.
        //
        // What keeps the old danger out is the sandbox check below, which is the real guard and
        // always was: a local folder registered by reference is not inside an upload root, so it is
        // refused on its path rather than on its owner being null. UploadSandbox knows both shapes.
        Path uploadRoot = UploadSandbox.rootFor(storageRoot, userId);
        Path target = Path.of(physicalPath).toAbsolutePath().normalize();
        if (!target.startsWith(uploadRoot) || target.equals(uploadRoot)) return;
        if (!Files.exists(target)) return;
        try (var walk = Files.walk(target)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Failed to delete '{}' during folder cleanup", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to walk '{}' during folder cleanup", target, e);
        }
    }

    @Override
    public List<String> listUserIdsWithUploads() {
        Path usersRoot = Path.of(storageRoot, "users");
        if (!Files.isDirectory(usersRoot)) return List.of();
        try (var entries = Files.list(usersRoot)) {
            return entries
                    .filter(p -> Files.isDirectory(p.resolve("uploads")))
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list users under {}", usersRoot, e);
            return List.of();
        }
    }

    @Override
    public List<String> listTopLevelFolders(String userId) {
        Path uploadsRoot = UploadSandbox.rootFor(storageRoot, userId);
        if (!Files.isDirectory(uploadsRoot)) return List.of();
        try (var entries = Files.list(uploadsRoot)) {
            return entries
                    .filter(Files::isDirectory)
                    .map(p -> p.toAbsolutePath().toString())
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list folders under {}", uploadsRoot, e);
            return List.of();
        }
    }

    // folder is client-supplied (multipart/JSON field, e.g. "folder=../../../../tmp/evil") and
    // must be treated as untrusted the same way relativePath already is in resolveSubPath() -
    // without this check it could create/write outside {storageRoot}/users/{userId}/uploads/.
    private Path userUploadDir(String userId, String folder) {
        return UploadSandbox.folderIn(storageRoot, userId, folder);
    }

    private boolean isDirectoryEmpty(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        }
    }

    private String fileNameOf(String storagePath) {
        Path name = Path.of(storagePath).getFileName();
        return name != null ? name.toString() : storagePath;
    }
}
