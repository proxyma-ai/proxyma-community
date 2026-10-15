package ai.proxyma.core.connector.files;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.SourceContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The DESKTOP edition's file source: a folder that already exists on the user's own disk, indexed
 * strictly BY REFERENCE.
 *
 * Proxyma does not own a single byte under the configured path. It never copies, moves, rewrites or
 * deletes anything there; deleting the source means "stop indexing this folder" and can never mean
 * anything else. That is not a convention this class asks callers to respect - the desktop
 * container simply has no bean capable of deleting a user file, because the entire upload package
 * is absent from that edition.
 *
 * SEPARATE FROM {@link UploadedFilesConnector} ON PURPOSE, rather than one class with a mode
 * flag. The two answer different questions with different consequences - what counts as a valid
 * root, who owns the bytes, what deletion means, whether a storage quota applies - and a runtime `if`
 * is the wrong instrument for a distinction where one branch can destroy a user's documents and the
 * other cannot. The shared part is {@link FileTypeSupport}, which is pure read-only vocabulary and
 * decides nothing.
 *
 * GATED ON {@link FileSourceMode}, not on the "desktop" profile, since 0.8.0. The profile was the
 * gate precisely because it could not be switched on for a server deployment by an unset property -
 * good reasoning resting on an assumption the home server broke: it RUNS the desktop profile, being
 * this application in a container, and it must NOT offer this type. "Pick a folder" there means a
 * folder inside a container, which is a bind mount the operator has to get right before a single
 * document can be indexed. The safety is kept rather than dropped: multi-user forces uploads on, so no
 * unset property can hand a server deployment this class - see FileSourceMode's own note.
 *
 * <p>The source's config column is the folder's absolute path, as it always was - the one shape
 * that is not its fields as JSON, because a path never reaches a client and has no fields to edit
 * there. The session reads it as the field {@code path} (StoredConfig).
 */
@Component
@Conditional(FileSourceMode.LocalFoldersEnabled.class)
public class LocalFolderConnector implements ConnectorPlugin {

    @Override
    public ConnectorDescriptor descriptor() {
        return CoreDescriptors.of(ConnectorType.LOCAL_FOLDER);
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new LocalFolderSession(context.get("path"), context.scope());
    }

    @Override
    public String identity(Map<String, String> config) {
        return config.get("path");
    }

    // A folder is named by its own last segment (ConnectorService.folderNameOf), never by its path.
    @Override
    public String displayName(Map<String, String> config) {
        String path = config.get("path");
        if (path == null || path.isBlank()) return path;
        try {
            java.nio.file.Path name = java.nio.file.Path.of(path.strip()).getFileName();
            return name != null ? name.toString() : path;
        } catch (java.nio.file.InvalidPathException e) {
            return path;
        }
    }
}
