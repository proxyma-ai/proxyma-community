package ai.proxyma.core.common.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * tools-core-01: a conversation id is a name a client chose, and it used to be resolved under the
 * conversations root as it came - {@code ../../..} named the data volume, and deleting that
 * "conversation" deleted every user's files. These pin the two walls: the id's shape, and that no
 * folder built from one, nor any delete, leaves the root.
 */
class ConversationPathsTest {

    @TempDir
    Path data;

    private static final String[] TRAVERSALS = {
            "..", "../..", "../../..", "../../../users/victim/conversations/x", "a/b", "a\\b", ".", "",
            " ", "x..y", "abc.def", "%2e%2e", "C:", "/etc", "\\\\host\\share", "a b",
            "0123456789012345678901234567890123456789012345678901234567890123456789"};

    @Test
    @DisplayName("a UUID and a plain dashed name are conversation ids; anything that could be a path is not")
    void idShape() {
        assertThat(ConversationPaths.isValidId(UUID.randomUUID().toString())).isTrue();
        assertThat(ConversationPaths.isValidId("conv-1")).isTrue();
        assertThat(ConversationPaths.isValidId(null)).isFalse();
        for (String id : TRAVERSALS) {
            assertThat(ConversationPaths.isValidId(id)).as(id).isFalse();
            assertThatThrownBy(() -> ConversationPaths.requireValidId(id)).as(id)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("on a multi-user server a traversal id is refused before any folder is named")
    void multiUserFolderStaysInTheUsersOwn() {
        ConversationPaths paths = new ConversationPaths(data.toString(), true);
        Path own = paths.conversationDir("conv-1", "alice");
        assertThat(own).isEqualTo(data.toAbsolutePath().normalize()
                .resolve("users").resolve("alice").resolve("conversations").resolve("conv-1"));
        for (String id : TRAVERSALS) {
            assertThatThrownBy(() -> paths.conversationDir(id, "alice")).as(id)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // Another user's folder is not reachable as one's own conversation.
        assertThatThrownBy(() -> paths.conversationDir("../../bob/conversations/conv-2", "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a visitor's folder is users/_anonymous, never users/ itself, and a user id is one name")
    void userRoots() {
        ConversationPaths paths = new ConversationPaths(data.toString(), true);
        Path users = data.toAbsolutePath().normalize().resolve("users");
        assertThat(paths.userRoot(null)).isEqualTo(users.resolve(ConversationPaths.ANONYMOUS));
        assertThat(paths.userRoot("")).isEqualTo(users.resolve(ConversationPaths.ANONYMOUS));
        assertThat(paths.userRoot("  ")).isEqualTo(users.resolve(ConversationPaths.ANONYMOUS));
        for (String uid : new String[]{"..", "../x", "a/b", ".", "a\\.."}) {
            assertThatThrownBy(() -> paths.userRoot(uid)).as(uid).isInstanceOf(IllegalArgumentException.class);
        }
        ConversationPaths single = new ConversationPaths(data.toString(), false);
        assertThat(single.userRoot("anyone")).isEqualTo(data.toAbsolutePath().normalize());
        assertThat(single.conversationDir("conv-1", null)).isEqualTo(data.toAbsolutePath().normalize().resolve("conv-1"));
    }

    @Test
    @DisplayName("deleting never leaves the root: the root itself and anything outside it are refused")
    void deleteStaysInsideTheRoot() throws Exception {
        Path root = Files.createDirectories(data.resolve("conversations"));
        Path outside = Files.writeString(data.resolve("keep.txt"), "keep");
        Path sibling = Files.createDirectories(root.resolve("other"));
        Files.writeString(sibling.resolve("f.txt"), "keep");
        ConversationPaths paths = new ConversationPaths(root.toString(), false);

        assertThat(paths.deleteTree(root)).isFalse();
        assertThat(paths.deleteTree(root.resolve(".."))).isFalse();
        assertThat(paths.deleteTree(data)).isFalse();
        assertThat(paths.deleteTree(root.resolve("conv-1").resolve("..").resolve(".."))).isFalse();
        assertThat(outside).exists();
        assertThat(sibling.resolve("f.txt")).exists();

        Path conv = paths.conversationDir("conv-1", null);
        Files.createDirectories(conv.resolve("attachments"));
        Files.writeString(conv.resolve("attachments").resolve("a.txt"), "x");
        assertThat(paths.deleteTree(conv)).isTrue();
        assertThat(conv).doesNotExist();
        assertThat(sibling.resolve("f.txt")).exists();
        assertThat(outside).exists();
        // Gone already is not a failure.
        assertThat(paths.deleteTree(conv)).isTrue();
    }

    @Test
    @DisplayName("a link inside a conversation folder is removed as a link; what it points at stays")
    void linksAreNotFollowed() throws Exception {
        Path root = Files.createDirectories(data.resolve("conversations"));
        Path elsewhere = Files.createDirectories(data.resolve("elsewhere"));
        Path precious = Files.writeString(elsewhere.resolve("precious.txt"), "keep");
        ConversationPaths paths = new ConversationPaths(root.toString(), false);
        Path conv = Files.createDirectories(paths.conversationDir("conv-1", null));
        try {
            Files.createSymbolicLink(conv.resolve("link"), elsewhere);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            // Creating a link needs a privilege some Windows accounts do not have.
            org.junit.jupiter.api.Assumptions.abort("cannot create a symbolic link here: " + e.getMessage());
        }
        assertThat(paths.deleteTree(conv)).isTrue();
        assertThat(conv).doesNotExist();
        assertThat(precious).exists();
    }
}
