package ai.proxyma.core.ingest.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    static final int CHUNK_TOKENS   = 512;
    static final int OVERLAP_TOKENS = 75;

    // Precompiled, and with the backslash doubled: a single-backslash s in a Java string literal is
    // the escape for a SPACE (Java 15+), so the old pattern matched only spaces after the hashes.
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*");
    private static final Pattern HEADING_MARKS = Pattern.compile("^#{1,6}\\s+");
    // A fence opens or closes a code block: three or more backticks or tildes, indented at most
    // three spaces (CommonMark). What is inside one is code, and its '#' lines are not headings.
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*");

    private final Encoding encoding;

    public ChunkingService() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public enum ContentType {
        MARKDOWN, PLAIN_TEXT;

        /**
         * How text that arrived as {@code mimeType} is chunked. Markdown - written as such, or what
         * a converter made of HTML, a PDF or an Office file - is split at its headings. Everything
         * read verbatim as text (code, configuration, CSV, a log) is not: a shell script or a YAML
         * file has a {@code #} comment where Markdown has a heading, and splitting at each one made
         * a 200-line script forty sections. Null is Markdown, as every caller meant before this
         * existed.
         */
        public static ContentType of(String mimeType) {
            if (mimeType == null || mimeType.isBlank()) return MARKDOWN;
            String m = mimeType.toLowerCase(Locale.ROOT);
            if (m.contains("markdown") || m.contains("html")) return MARKDOWN;
            return DefaultDocumentFetcher.isConverted(m) ? MARKDOWN : PLAIN_TEXT;
        }
    }

    public List<Document> chunk(String source, String content,
                                Map<String, Object> baseMetadata, ContentType type) {
        if (content == null || content.isBlank()) return List.of();
        return switch (type) {
            case MARKDOWN   -> chunkMarkdown(source, content, baseMetadata);
            case PLAIN_TEXT -> chunkWithOverlap(source, content, baseMetadata, null);
        };
    }

    private List<Document> chunkMarkdown(String source, String content, Map<String, Object> baseMetadata) {
        List<Document> result = new ArrayList<>();
        for (Section section : splitByHeadings(content)) {
            if (section.content().isBlank()) continue;
            Map<String, Object> meta = new HashMap<>(baseMetadata);
            if (section.heading() != null) meta.put("section", section.heading());
            result.addAll(chunkWithOverlap(source, section.content(), meta, section.heading()));
        }
        if (result.isEmpty()) result.addAll(chunkWithOverlap(source, content, baseMetadata, null));
        return result;
    }

    private List<Document> chunkWithOverlap(String source, String text,
                                            Map<String, Object> baseMetadata, String contextPrefix) {
        if (text == null || text.isBlank()) return List.of();
        IntArrayList tokens = encoding.encode(text);
        if (tokens.size() == 0) return List.of();

        List<Document> chunks = new ArrayList<>();
        int start = 0;
        while (start < tokens.size()) {
            int end = Math.min(start + CHUNK_TOKENS, tokens.size());
            IntArrayList slice = new IntArrayList();
            for (int i = start; i < end; i++) slice.add(tokens.get(i));
            String chunkText = wholeCharacters(encoding.decodeBytes(slice));
            if (contextPrefix != null && !contextPrefix.isBlank()) {
                chunkText = contextPrefix.strip() + "\n\n" + chunkText;
            }
            Map<String, Object> meta = new HashMap<>(baseMetadata);
            meta.put("source", source);
            chunks.add(new Document(chunkText, meta));
            if (end >= tokens.size()) break;
            start += (CHUNK_TOKENS - OVERLAP_TOKENS);
        }
        log.debug("Chunked '{}': {} tokens -> {} chunks", source, tokens.size(), chunks.size());
        return chunks;
    }

    /**
     * A window's bytes as text, without the pieces of a character cut at either edge.
     *
     * <p>cl100k is a byte-level tokenizer, so a character outside ASCII - every Vietnamese letter
     * with a tone mark - can span two tokens, and a window boundary can fall between them. Decoded
     * on its own, each half became U+FFFD. The cut character is never lost by dropping it here: the
     * windows overlap, so whatever is cut at one window's edge lies whole inside its neighbour.
     */
    static String wholeCharacters(byte[] bytes) {
        int from = 0;
        // Continuation bytes (10xxxxxx) at the start belong to a character the previous window holds.
        while (from < bytes.length && from < 3 && (bytes[from] & 0xC0) == 0x80) from++;
        int to = bytes.length;
        // A lead byte near the end whose sequence does not fit is a character the next window holds.
        for (int back = 1; back <= 4 && to - back >= from; back++) {
            int b = bytes[to - back] & 0xFF;
            if ((b & 0xC0) == 0x80) continue;
            int length = b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC0 ? 2 : 1;
            if (length > back) to -= back;
            break;
        }
        return new String(Arrays.copyOfRange(bytes, from, to), StandardCharsets.UTF_8);
    }

    private record Section(String heading, String content) {}

    private List<Section> splitByHeadings(String markdown) {
        List<Section> sections = new ArrayList<>();
        // CRLF too: a file saved on Windows, or a converter's output there, carries a CR at the end
        // of every line, and '.' does not match it - so no heading of such a document was found.
        String[] lines = markdown.split("\r?\n", -1);
        StringBuilder buf = new StringBuilder();
        String currentHeading = null;
        boolean inFence = false;
        for (String line : lines) {
            if (FENCE.matcher(line).matches()) inFence = !inFence;
            if (!inFence && HEADING.matcher(line).matches()) {
                String body = buf.toString().strip();
                if (!body.isEmpty() || currentHeading != null) sections.add(new Section(currentHeading, body));
                buf.setLength(0);
                currentHeading = HEADING_MARKS.matcher(line).replaceFirst("").strip();
            } else {
                buf.append(line).append("\n");
            }
        }
        String body = buf.toString().strip();
        if (!body.isEmpty() || currentHeading != null) sections.add(new Section(currentHeading, body));
        if (sections.isEmpty()) sections.add(new Section(null, markdown));
        return sections;
    }
}
