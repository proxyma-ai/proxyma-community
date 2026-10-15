package ai.proxyma.core.connector.files;

import java.nio.file.Path;

/**
 * Where a caller's uploaded bytes live, answered in ONE place.
 *
 * <p><b>Why this exists at all.</b> The same path was being computed independently in three files -
 * {@code LocalDiskStorageService} decided where to WRITE, {@code ConnectorService} decided what to
 * ACCEPT at registration, and {@link UploadedFilesConnector} decided what to accept at every SYNC.
 * The third is deliberately a second line of defence behind the second, which only works while all
 * three agree on the answer. Three copies of a security boundary is the shape
 * {@code .claude/rules/security.md} names as this project's most expensive: a documented contract
 * enforced by matching on something other than the contract. Two of those copies were about to be
 * edited to understand a new case, so they became one.
 *
 * <h2>The single-user case, which is the new one</h2>
 *
 * <p>The server edition has accounts, so every sandbox is {@code {root}/users/{userId}/uploads} and
 * the {@code userId} segment is what keeps one tenant out of another's files. The HOME SERVER has
 * no accounts at all - it is the desktop application packaged for a machine with no screen - so
 * there is no id to put there, and inventing one would be worse than leaving it out: a synthetic
 * "local" user would own connectors that every other desktop code path looks up under a NULL owner,
 * and those sources would simply not appear.
 *
 * <p>So a null owner gets {@code {root}/uploads}, with no {@code users} segment. That is not a
 * weaker sandbox, and the distinction is worth being precise about: the {@code users/{id}} level
 * separates tenants FROM EACH OTHER, and a deployment with one user has nobody to be separated
 * from. What both shapes do is the thing that actually matters here - confine writes and walks to a
 * directory Proxyma owns, so a registered path can never be used to walk the storage root and read
 * key material.
 */
public final class UploadSandbox {

    private UploadSandbox() {}

    /** The directory holding {@code uploads}, below which everything a sandbox check allows sits. */
    public static final String USERS_SEGMENT = "users";

    /** The leaf every sandbox ends in, for both shapes. */
    public static final String UPLOADS_SEGMENT = "uploads";

    /**
     * This owner's upload root: {@code {storageRoot}/users/{userId}/uploads} when there is an
     * owner, {@code {storageRoot}/uploads} when there is not.
     *
     * <p>Absolute and normalized, because every caller compares it against another absolute
     * normalized path and a comparison between one of each silently answers "outside the sandbox".
     */
    public static Path rootFor(String storageRoot, String userId) {
        Path base = Path.of(storageRoot);
        Path root = (userId == null || userId.isBlank())
                ? base.resolve(UPLOADS_SEGMENT)
                : base.resolve(USERS_SEGMENT).resolve(userId).resolve(UPLOADS_SEGMENT);
        return root.toAbsolutePath().normalize();
    }

    /**
     * Where the text of an uploaded document is kept once its original bytes are purged:
     * {@code {storageRoot}/users/{userId}/markdown-cache/{documentId}.md}. Server mode deletes an
     * upload's bytes as soon as it is indexed (SyncService) and keeps this - the converted text,
     * which for a CSV is the file itself - so a re-index, and anything else that needs the content
     * rather than its search chunks (a key account's revenue import), reads it from here. One
     * definition, for the writer and every reader.
     *
     * @return null when there is no owner - only server uploads are purged, and they always have one
     */
    public static Path retainedTextFor(String storageRoot, String userId, java.util.UUID documentId) {
        if (userId == null || userId.isBlank() || documentId == null) return null;
        return Path.of(storageRoot, USERS_SEGMENT, userId, "markdown-cache", documentId + ".md").normalize();
    }

    /**
     * One named folder inside that root.
     *
     * @throws IllegalArgumentException when {@code folder} escapes the root - which a folder name
     *         containing {@code ..} would otherwise do, and which is checked HERE so that no caller
     *         can resolve a folder without it having been checked.
     */
    public static Path folderIn(String storageRoot, String userId, String folder) {
        Path root = rootFor(storageRoot, userId);
        if (folder == null || folder.isBlank()) return root;
        Path resolved = root.resolve(folder).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid folder name: " + folder);
        }
        return resolved;
    }

    /**
     * Whether {@code candidate} is this owner's sandbox or something beneath it.
     *
     * <p>The registration guard and the per-sync guard both ask exactly this, which is the point of
     * the class. Equality counts: registering the sandbox root itself is how a user indexes
     * everything they have uploaded.
     */
    public static boolean contains(String storageRoot, String userId, Path candidate) {
        if (candidate == null) return false;
        Path root = rootFor(storageRoot, userId);
        Path normalized = candidate.toAbsolutePath().normalize();
        return normalized.equals(root) || normalized.startsWith(root);
    }

    /**
     * Whether {@code candidate} IS an upload root, rather than something inside one.
     *
     * <p>The stop condition for the cleanup that climbs upward removing directories it has just
     * emptied. Both shapes qualify, because both are a root nothing may delete: removing it would
     * take the sandbox itself, and the next upload would then write into a directory that has to be
     * recreated for a reason nobody could see from the code that deleted it.
     */
    public static boolean isUploadRoot(String storageRoot, Path candidate) {
        if (candidate == null) return false;
        Path normalized = candidate.toAbsolutePath().normalize();
        Path base = Path.of(storageRoot).toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) return false;
        int depth = normalized.getNameCount() - base.getNameCount();
        if (depth == 1) {
            return UPLOADS_SEGMENT.equals(normalized.getName(base.getNameCount()).toString());
        }
        return depth == 3
                && USERS_SEGMENT.equals(normalized.getName(base.getNameCount()).toString())
                && UPLOADS_SEGMENT.equals(normalized.getName(base.getNameCount() + 2).toString());
    }

    /**
     * Whether {@code candidate} is inside SOME sandbox under this storage root - any user's, or the
     * single-user one - without saying whose.
     *
     * <p>Used only by the cleanup path, which reclaims bytes Proxyma owns and must therefore be able
     * to recognise a directory it owns without being told who it belongs to. It is NOT an access
     * check and must never be used as one: "this is an upload directory" and "this is YOUR upload
     * directory" are different questions, and {@link #contains} is the one that answers the second.
     */
    public static boolean isAnyUploadPath(String storageRoot, Path candidate) {
        if (candidate == null) return false;
        Path normalized = candidate.toAbsolutePath().normalize();
        Path base = Path.of(storageRoot).toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) return false;

        int depth = normalized.getNameCount() - base.getNameCount();
        // {root}/uploads/...  - the single-user shape.
        if (depth >= 1 && UPLOADS_SEGMENT.equals(normalized.getName(base.getNameCount()).toString())) {
            return true;
        }
        // {root}/users/{id}/uploads/... - the multi-user shape.
        return depth >= 3
                && USERS_SEGMENT.equals(normalized.getName(base.getNameCount()).toString())
                && UPLOADS_SEGMENT.equals(normalized.getName(base.getNameCount() + 2).toString());
    }
}
