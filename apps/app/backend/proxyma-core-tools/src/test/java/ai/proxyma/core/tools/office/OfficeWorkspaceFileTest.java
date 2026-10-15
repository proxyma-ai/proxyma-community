package ai.proxyma.core.tools.office;

import ai.proxyma.core.search.domain.SearchContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The attach tools send a file to a server the user does not control, so which files they can
 * reach is the security property: this conversation's attachments/ and outputs/, and nothing a
 * crafted name could climb out to.
 */
class OfficeWorkspaceFileTest {

    @TempDir
    Path root;
    private OfficeWorkspace workspace;
    private Path conversation;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new OfficeWorkspace(root.toString(), false);
        SearchContext.setConversationId("c1");
        conversation = root.resolve("c1");
        Files.createDirectories(conversation.resolve("attachments"));
        Files.createDirectories(conversation.resolve("outputs"));
        Files.writeString(conversation.resolve("attachments/upload.pdf"), "u");
        Files.writeString(conversation.resolve("outputs/report.pdf"), "o");
        Files.writeString(conversation.resolve("outputs/upload.pdf"), "made");
        Files.writeString(root.resolve("secret.txt"), "s");
    }

    @AfterEach
    void tearDown() {
        SearchContext.clear();
    }

    @Test
    void aBareNamePrefersOutputsThenAttachments() {
        assertEquals(conversation.resolve("outputs/upload.pdf"), workspace.file("upload.pdf"));
        assertEquals(conversation.resolve("outputs/report.pdf"), workspace.file("report.pdf"));
    }

    @Test
    void aFolderQualifiedOrFullPathInsideIsAccepted() {
        assertEquals(conversation.resolve("attachments/upload.pdf"), workspace.file("attachments/upload.pdf"));
        String full = conversation.resolve("attachments/upload.pdf").toString();
        assertEquals(conversation.resolve("attachments/upload.pdf"), workspace.file(full));
    }

    @Test
    void nothingOutsideTheTwoFoldersIsReachable() {
        assertThrows(IllegalArgumentException.class, () -> workspace.file("../../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> workspace.file("attachments/../../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> workspace.file(root.resolve("secret.txt").toString()));
        assertThrows(IllegalArgumentException.class, () -> workspace.file("memory.json"));
    }

    @Test
    void outsideAConversationNothingIsReachable() {
        SearchContext.clear();
        assertThrows(IllegalArgumentException.class, () -> workspace.file("report.pdf"));
    }
}
