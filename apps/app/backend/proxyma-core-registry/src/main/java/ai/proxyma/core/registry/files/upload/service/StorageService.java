package ai.proxyma.core.registry.files.upload.service;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

public interface StorageService {
    String store(UUID fileId, String originalName, InputStream content, long sizeBytes,
                 String userId, String folder, String relativePath);
    InputStream retrieve(String storagePath);
    // Deletes one stored file, and any directories that leaves empty INSIDE its named upload
    // folder - never the folder itself, which an upload source is registered at and which must
    // outlive its last file (Enterprise Server purges every file once it is indexed). userId and
    // folder are the ones the file was stored with (FileMetadata.uploadedByUserId and .folder).
    void delete(String storagePath, String userId, String folder);
    String ensureFolder(String userId, String folder);
    boolean folderExists(String userId, String folder);
    boolean fileExists(String storagePath);
    // Recursively deletes the given physical folder tree, but only if it resolves under
    // userId's own uploads sandbox - a no-op (not an error) for any path outside it, so this is
    // always safe to call for a FILES connector regardless of whether its config is an uploaded
    // folder or a directly-registered arbitrary host path.
    void deleteFolderRecursively(String userId, String physicalPath);

    // The two methods below exist only for UploadFolderGarbageCollectorService's disk-vs-DB
    // reconciliation sweep - they enumerate current disk state, they never mutate anything.

    // Every userId that currently has at least one directory under its uploads sandbox -
    // the outer loop of the orphan-folder sweep.
    List<String> listUserIdsWithUploads();

    // Absolute physical paths of every top-level folder directory directly under this user's
    // uploads sandbox (one entry per RegisteredConnector.config/FileMetadata.folder candidate) -
    // never recurses into a folder's own subdirectories.
    List<String> listTopLevelFolders(String userId);
}
