package ai.proxyma.core.ingest.service;

import ai.proxyma.core.ingest.service.ChunkingService.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a document is cut into before it is embedded and indexed (ingest-10, -35, -36).
 */
class ChunkingServiceTest {

    private final ChunkingService chunking = new ChunkingService();

    private List<Document> chunk(String text, ContentType type) {
        return chunking.chunk("doc", text, Map.of(), type);
    }

    @Test
    @DisplayName("a shell script's comments are not sections: plain text is not split at '#' lines")
    void plainTextHasNoHeadings() {
        StringBuilder script = new StringBuilder("#!/bin/sh\n");
        for (int i = 0; i < 40; i++) script.append("# step ").append(i).append("\necho ").append(i).append('\n');

        List<Document> chunks = chunk(script.toString(), ContentType.of("application/x-sh"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getMetadata()).doesNotContainKey("section");
    }

    @Test
    @DisplayName("which content types are Markdown: written as such or converted, and nothing read verbatim")
    void contentTypes() {
        assertThat(ContentType.of(null)).isEqualTo(ContentType.MARKDOWN);
        assertThat(ContentType.of("text/markdown")).isEqualTo(ContentType.MARKDOWN);
        assertThat(ContentType.of("text/html")).isEqualTo(ContentType.MARKDOWN);
        assertThat(ContentType.of("application/pdf")).isEqualTo(ContentType.MARKDOWN);
        assertThat(ContentType.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .isEqualTo(ContentType.MARKDOWN);
        assertThat(ContentType.of("text/plain")).isEqualTo(ContentType.PLAIN_TEXT);
        assertThat(ContentType.of("text/x-csrc")).isEqualTo(ContentType.PLAIN_TEXT);
        assertThat(ContentType.of("application/x-powershell")).isEqualTo(ContentType.PLAIN_TEXT);
        assertThat(ContentType.of("application/octet-stream")).isEqualTo(ContentType.PLAIN_TEXT);
    }

    @Test
    @DisplayName("a '#' line inside a fenced code block is code, not a heading")
    void fencesAreNotSplit() {
        String readme = "# Install\n\nRun this:\n\n```bash\n# fetch the sources\ngit clone x\n# build\nmake\n```\n\nDone.\n";

        List<Document> chunks = chunk(readme, ContentType.MARKDOWN);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getMetadata()).containsEntry("section", "Install");
        assertThat(chunks.get(0).getText()).contains("# fetch the sources", "# build", "Done.");
    }

    @Test
    @DisplayName("headings are found after a tab and on CRLF lines, and each section starts with its heading")
    void headings() {
        List<Document> chunks = chunk("#\tFirst\r\nalpha\r\n## Second\r\nbeta\r\n", ContentType.MARKDOWN);

        assertThat(chunks).extracting(d -> d.getMetadata().get("section")).containsExactly("First", "Second");
        assertThat(chunks.get(1).getText()).startsWith("Second\n\n").contains("beta");
    }

    @Test
    @DisplayName("ingest-35: a character cut by a window boundary is never decoded as a replacement character")
    void windowsKeepWholeCharacters() {
        // Vietnamese with tone marks: several bytes per letter, and byte-level tokens that split them.
        String text = "Ti\u1ebfng Vi\u1ec7t c\u00f3 d\u1ea5u, m\u1ed7i ch\u1eef c\u00e1i nhi\u1ec1u byte. ".repeat(400);

        List<Document> chunks = chunk(text, ContentType.PLAIN_TEXT);

        assertThat(chunks.size()).isGreaterThan(2);
        for (Document d : chunks) assertThat(d.getText()).doesNotContain("\ufffd");
    }

    @Test
    @DisplayName("an edge byte sequence is trimmed to whole characters, and whole input is untouched")
    void wholeCharacters() {
        byte[] word = "Vi\u1ec7t".getBytes(StandardCharsets.UTF_8);
        assertThat(ChunkingService.wholeCharacters(word)).isEqualTo("Vi\u1ec7t");
        // Cut inside the three-byte "\u1ec7" at the end, and inside it again at the start.
        byte[] cutEnd = java.util.Arrays.copyOf(word, 3);
        assertThat(ChunkingService.wholeCharacters(cutEnd)).isEqualTo("Vi");
        byte[] cutStart = java.util.Arrays.copyOfRange(word, 3, word.length);
        assertThat(ChunkingService.wholeCharacters(cutStart)).isEqualTo("t");
    }
}
