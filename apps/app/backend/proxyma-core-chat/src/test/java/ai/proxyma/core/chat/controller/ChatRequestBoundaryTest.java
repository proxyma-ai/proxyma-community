package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.dto.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What ChatController refuses at its door, and how it serves a conversation's stored files.
 *
 * <p>tools-core-01: the conversation id a client sends becomes that conversation's folder, and
 * {@code ../../..} named the data volume. chat-api-data-08: every field of the request is stored and
 * sent to the model, on an endpoint a visitor can reach, with a 200 MB body limit and no field
 * bounded. chat-api-data-02: fetched web media was served inline, an .svg among it, as a document
 * of this origin.
 */
class ChatRequestBoundaryTest {

    @TempDir
    Path dir;

    private static ChatRequest request(String id, String message, List<String> mentions) {
        return new ChatRequest(id, message, mentions, null, null, null, null, null);
    }

    @Test
    @DisplayName("a conversation id that could be a path is refused; a UUID, or none, is not")
    void conversationIdShape() {
        assertThat(ChatController.invalidRequest(request(UUID.randomUUID().toString(), "hi", null))).isNull();
        assertThat(ChatController.invalidRequest(request(null, "hi", null))).isNull();
        assertThat(ChatController.invalidRequest(request("", "hi", null))).isNull();
        for (String id : new String[]{"../../..", "..", "a/b", "a\\b", "x.y", "%2e%2e"}) {
            assertThat(ChatController.invalidRequest(request(id, "hi", null))).as(id).isNotNull();
        }
    }

    @Test
    @DisplayName("the message and the lists beside it are bounded")
    void requestIsBounded() {
        assertThat(ChatController.invalidRequest(request(null, "x".repeat(ChatController.MAX_MESSAGE_CHARS), null))).isNull();
        assertThat(ChatController.invalidRequest(request(null, "x".repeat(ChatController.MAX_MESSAGE_CHARS + 1), null)))
                .isNotNull();
        assertThat(ChatController.invalidRequest(request(null, "hi",
                Collections.nCopies(ChatController.MAX_LIST_ENTRIES + 1, "doc")))).isNotNull();
        assertThat(ChatController.invalidRequest(request(null, "hi",
                List.of("x".repeat(ChatController.MAX_LIST_ENTRY_CHARS + 1))))).isNotNull();
    }

    @Test
    @DisplayName("a stored file is never a document of this origin; only a picture, video or sound is shown in place")
    void storedFilesAreNotDocuments() throws Exception {
        Path file = Files.writeString(dir.resolve("f"), "<svg onload=alert(1)/>");

        ResponseEntity<Resource> svg = ChatController.storedFile(file, "cat.svg", MediaType.valueOf("image/svg+xml"), true);
        assertThat(svg.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
        assertThat(svg.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
        assertThat(svg.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        ResponseEntity<Resource> html = ChatController.storedFile(file, "page.html", MediaType.TEXT_HTML, true);
        assertThat(html.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");

        ResponseEntity<Resource> png = ChatController.storedFile(file, "cat.png", MediaType.IMAGE_PNG, true);
        assertThat(png.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");
        assertThat(png.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");

        // Uploads and outputs are downloads whatever they are.
        ResponseEntity<Resource> upload = ChatController.storedFile(file, "cat.png", MediaType.IMAGE_PNG, false);
        assertThat(upload.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
    }

    @Test
    @DisplayName("the event stream's fallback error is valid JSON whatever the message holds")
    void fallbackErrorIsJson() throws Exception {
        String message = "line one\nhe said \"no\"\\ and\ttab \u0001";
        String json = "{\"type\":\"error\",\"data\":" + ChatController.jsonString(message) + "}";
        assertThat(new ObjectMapper().readTree(json).path("data").asText()).isEqualTo(message);
    }
}
