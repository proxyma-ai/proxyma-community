package ai.proxyma.core.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SmartChatMemory.fileIn - the path jail every attachment, output and web-media name from a request
 * goes through.
 *
 * <p>The failure this pins: it refused any name CONTAINING "..", so "Q3..final.xlsx" uploaded fine
 * and was then silently dropped from the message that named it. Only ".." as a path segment is
 * traversal.
 */
class ConversationFileJailTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("a name with two dots inside it is an ordinary file name")
    void doubleDotInsideANameIsAccepted() throws Exception {
        Path dir = Files.createDirectories(root.resolve("attachments"));
        Path file = Files.writeString(dir.resolve("Q3..final.xlsx"), "x");

        assertEquals(file.normalize(), SmartChatMemory.fileIn(dir, "Q3..final.xlsx").orElseThrow());
    }

    @Test
    @DisplayName("traversal is still refused, whether or not the target exists")
    void traversalIsRefused() throws Exception {
        Path dir = Files.createDirectories(root.resolve("conv").resolve("attachments"));
        Files.writeString(root.resolve("conv").resolve("x"), "secret");
        Files.createDirectories(dir.resolve("a"));
        Files.writeString(dir.resolve("b"), "b");

        for (String name : new String[]{"../x", "a/../b", "..\\x", "a\\..\\b", "..", ".", "", " ", null}) {
            assertTrue(SmartChatMemory.fileIn(dir, name).isEmpty(), String.valueOf(name));
        }
    }
}
