package ai.proxyma.core.common.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;

/**
 * Where a conversation's files live, and the one check that keeps every path built from a
 * conversation id inside the folders it is meant to name.
 *
 * <p>THE ID ARRIVES FROM A CLIENT. A browser mints it, POST /chat stores it as the conversation's
 * key, and every folder of the conversation - uploads, runPython's outputs, web media, offloaded
 * tool results, condensation archives - is that id resolved under the conversations root. Until
 * 2026-10-14 nothing checked it: an id of {@code ../../..} resolved, on Enterprise Server, to the
 * data volume itself, and deleting that "conversation" or the account that held it deleted every
 * user's files. A path from another user's id, given as one's own conversation, read and wrote
 * that user's folder.
 *
 * <p>So an id is refused unless it is a plain name ({@link #isValidId}), and every folder this
 * builds is normalized and checked to lie where it should before anything is created, read, written
 * or deleted. Both, deliberately: the shape check is what a client is told about, and the
 * confinement check is what still holds if a caller forgets the first.
 *
 * <p>Layout: on Desktop and Home Server every conversation is a folder directly in the root; on
 * Enterprise Server it is {@code users/<userId>/conversations/<id>}, with visitors sharing
 * {@code users/_anonymous}.
 */
public final class ConversationPaths {

    private static final Logger log = LoggerFactory.getLogger(ConversationPaths.class);

    /** The folder visitors' conversations share on a multi-user server - nobody's account. */
    public static final String ANONYMOUS = "_anonymous";

    // A UUID, which is what every client mints, fits this; so does anything else made only of
    // letters, digits and dashes. Never a dot, a separator or an empty string.
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final Path root;
    private final boolean multiUser;

    public ConversationPaths(String conversationsDir, boolean multiUser) {
        this.root = Paths.get(conversationsDir).toAbsolutePath().normalize();
        this.multiUser = multiUser;
    }

    /** Whether {@code id} may name a conversation - and so a folder. */
    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /**
     * {@code id}, when it may name a conversation.
     *
     * @throws IllegalArgumentException otherwise - a 400 at every controller that lets it through
     */
    public static String requireValidId(String id) {
        if (!isValidId(id)) throw new IllegalArgumentException("Not a valid conversation id.");
        return id;
    }

    /** The conversations root, absolute. */
    public Path root() {
        return root;
    }

    /**
     * Everything one person's conversations hold: the whole root where there is one person, and
     * {@code users/<userId>} on a multi-user server - {@code users/_anonymous} for a visitor.
     *
     * @throws IllegalArgumentException for a user id that is not a single plain name
     */
    public Path userRoot(String userId) {
        if (!multiUser) return root;
        Path users = root.resolve("users");
        String uid = userId == null || userId.isBlank() ? ANONYMOUS : userId;
        // Either separator, on every OS: a backslash is a plain character on Linux, so `a\..` passed
        // there and was refused on Windows - one rule, whatever the host (CI on Linux, 2026-10-15).
        if (uid.indexOf('/') >= 0 || uid.indexOf('\\') >= 0) throw new IllegalArgumentException("Not a valid user id.");
        Path dir = users.resolve(uid).normalize();
        if (!users.equals(dir.getParent())) throw new IllegalArgumentException("Not a valid user id.");
        return dir;
    }

    /**
     * The folder of conversation {@code conversationId}, as {@code userId} keeps it. Nothing is
     * created.
     *
     * @throws IllegalArgumentException for an id that may not name a conversation
     */
    public Path conversationDir(String conversationId, String userId) {
        requireValidId(conversationId);
        Path base = multiUser ? userRoot(userId).resolve("conversations") : root;
        Path dir = base.resolve(conversationId).normalize();
        if (!base.equals(dir.getParent())) throw new IllegalArgumentException("Not a valid conversation id.");
        return dir;
    }

    /** Whether {@code path} lies strictly inside the root - the root itself does not. */
    public boolean contains(Path path) {
        if (path == null) return false;
        Path p = path.toAbsolutePath().normalize();
        return p.startsWith(root) && !p.equals(root);
    }

    /**
     * Deletes a folder and everything in it, when it lies strictly inside the root. Anything else -
     * the root itself, a path outside it - is refused and logged, and nothing is deleted.
     *
     * <p>Links are deleted, never followed: a symbolic link or an NTFS junction inside the folder
     * is removed as the link it is, and what it points at is left alone.
     *
     * @return false when the folder was refused or could not be deleted whole
     */
    public boolean deleteTree(Path dir) {
        if (!contains(dir)) {
            log.warn("[ConversationPaths] refused to delete {}: it is not inside the conversations root {}", dir, root);
            return false;
        }
        Path target = dir.toAbsolutePath().normalize();
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return true;
        try {
            Path realTarget = target.toRealPath();
            if (!realTarget.equals(target.getParent().toRealPath().resolve(target.getFileName()))) {
                // The folder is itself a link: the link goes, what it points at stays.
                Files.delete(target);
                return true;
            }
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                    // A junction reads as a directory on Windows, so a walk would descend into
                    // whatever it points at. Its real path gives it away: a folder that is really
                    // here resolves to where the walk says it is.
                    if (!d.toRealPath().equals(realTarget.resolve(target.relativize(d)))) {
                        Files.delete(d);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                    Files.delete(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    if (e != null) throw e;
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException e) {
            log.warn("[ConversationPaths] failed to delete {}: {}", target, e.getMessage());
            return false;
        }
    }
}
