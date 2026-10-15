package ai.proxyma.core.connector.files;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.SourceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The file source of every deployment with no filesystem worth pointing at: bytes a user uploaded,
 * which Proxyma stored in a sandbox and therefore owns.
 *
 * THE SERVER EDITION'S, AND SINCE 0.8.0 THE HOME SERVER'S TOO. Both are Proxyma on a machine the
 * user is not sitting at, which is the property that decides this - not the number of accounts. The
 * home server has exactly one user and no accounts, so its sandbox has no {@code users/{id}}
 * segment; see {@link UploadSandbox}, which owns both shapes, and {@link FileSourceMode}, which
 * decides between this type and {@link LocalFolderConnector}.
 *
 * Everything it indexes lives under {storageRoot}/users/{userId}/uploads/ - or {storageRoot}/uploads/
 * where there is one local user. Those bytes count against the user's storage quota, and deleting
 * the source deletes them - which is the whole reason this is a different type from
 * {@link LocalFolderConnector} rather than the same one behind a flag. One of these two
 * lifecycles reclaims storage; the other must never touch a file. That is not a difference to express
 * with an `if`.
 *
 * The sandbox check in {@link UploadedFilesSession} is this type's own invariant and has no
 * counterpart for a local folder, where an arbitrary path is exactly what a user is meant to
 * register. It is a second line behind ConnectorService.requireAllowedLocalPath, which validates at
 * registration time: this one re-checks at every sync, so a row that predates that guard - or that
 * was edited directly in the database - still cannot be used to walk the storage root and read
 * another tenant's uploads or the server's key material.
 */
@Component
@Conditional(FileSourceMode.UploadsEnabled.class)
public class UploadedFilesConnector implements ConnectorPlugin {

    // Same property and default as LocalDiskStorageService - the root every user's upload sandbox
    // hangs off.
    private final String storageRoot;

    public UploadedFilesConnector(@Value("${proxyma.storage.local.root:/data}") String storageRoot) {
        this.storageRoot = storageRoot;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return CoreDescriptors.of(ConnectorType.UPLOADED_FILES);
    }

    /** The session checks the folder against its OWNER's sandbox - the source's owner, never the caller. */
    @Override
    public ConnectorSession open(SourceContext context) {
        return new UploadedFilesSession(storageRoot, context.ownerId(), context.get("path"), context.scope());
    }

    @Override
    public String identity(Map<String, String> config) {
        return config.get("path");
    }
}
