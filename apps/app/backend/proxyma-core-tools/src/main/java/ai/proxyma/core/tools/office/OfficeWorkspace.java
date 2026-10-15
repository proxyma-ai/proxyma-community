package ai.proxyma.core.tools.office;

import ai.proxyma.core.common.conversation.ConversationPaths;
import ai.proxyma.core.search.domain.SearchContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The current conversation's files as runPython sees them: {@code attachments/} - what the user
 * uploaded - and {@code outputs/} - what the agent made, which a later run can edit again.
 *
 * <p>The conversation folder is the one SmartChatMemory and WebMediaStorage name, built by the same
 * ConversationPaths. Keyed by the CALLER's user id, so a run only ever sees the files of the person
 * whose turn it is - and confined to that person's part of the root: a conversation id that is a
 * path (tools-core-02) is refused rather than resolved into somebody else's folder, whose files a
 * run would otherwise have read, overwritten and uploaded to Jira or Confluence.
 */
@Component
public class OfficeWorkspace {

    private final ConversationPaths paths;

    public OfficeWorkspace(@Value("${proxyma.conversations-dir:${user.home}/.proxyma/conversations}") String conversationsDir,
                           @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.paths = new ConversationPaths(conversationsDir, multiUser);
    }

    /**
     * The current conversation's folder, or null outside a conversation (an MCP call).
     *
     * @throws IllegalArgumentException when the conversation's id could not name a folder of its own
     */
    public Path conversationDir() {
        String conversationId = SearchContext.getConversationId();
        if (conversationId == null || conversationId.isBlank()) return null;
        return paths.conversationDir(conversationId, SearchContext.getUserId());
    }

    /** Every file in attachments/ and outputs/, keyed by that relative path, within the size limits. */
    public Map<String, byte[]> inputs(Path conversation) throws IOException {
        Map<String, byte[]> inputs = new LinkedHashMap<>();
        long total = 0;
        for (String folder : List.of("attachments", "outputs")) {
            Path dir = conversation.resolve(folder);
            if (!Files.isDirectory(dir)) continue;
            List<Path> files;
            try (Stream<Path> list = Files.list(dir)) {
                files = list.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path f : files) {
                long size = Files.size(f);
                if (size > PythonSandbox.Limits.MAX_FILE_BYTES || total + size > PythonSandbox.Limits.MAX_TOTAL_BYTES) continue;
                total += size;
                inputs.put(folder + "/" + f.getFileName(), Files.readAllBytes(f));
            }
        }
        return inputs;
    }

    /**
     * Writes a run's outputs into the conversation's outputs/ folder and says which changed. Only
     * files directly in outputs/ are kept - one flat folder is what the download URLs address.
     */
    public List<SavedFile> saveOutputs(Path conversation, Map<String, byte[]> before, Map<String, byte[]> outputs)
            throws IOException {
        Path dir = conversation.resolve("outputs");
        Files.createDirectories(dir);
        List<SavedFile> saved = new java.util.ArrayList<>();
        for (Map.Entry<String, byte[]> e : outputs.entrySet()) {
            String rel = e.getKey();
            if (!rel.startsWith("outputs/") || rel.indexOf('/', "outputs/".length()) >= 0) continue;
            String name = rel.substring("outputs/".length());
            if (name.isBlank() || name.startsWith(".")) continue;
            byte[] previous = before.get(rel);
            boolean changed = previous == null || !java.util.Arrays.equals(previous, e.getValue());
            if (changed) Files.write(dir.resolve(name), e.getValue());
            saved.add(new SavedFile(name, e.getValue().length, changed, dir.resolve(name)));
        }
        return saved;
    }

    /**
     * A file of this conversation, for a tool that sends it somewhere - a Jira or Confluence
     * attachment. Only {@code attachments/} (the user's uploads, and what openLocalFile brought in)
     * and {@code outputs/} (what runPython made) are reachable: the name may be bare ("report.pdf",
     * outputs/ first), relative ("attachments/report.pdf"), or the full path openLocalFile returned.
     * Anything resolving outside those two folders is refused, so a model cannot be talked into
     * uploading an arbitrary file off the disk.
     */
    public Path file(String name) {
        Path conversation = conversationDir();
        if (conversation == null) throw new IllegalArgumentException("Files can be sent only from inside a chat conversation.");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name the file to send.");
        String n = name.strip().replace('\\', '/');
        Path attachments = conversation.resolve("attachments").normalize();
        Path outputs = conversation.resolve("outputs").normalize();
        List<Path> candidates = new java.util.ArrayList<>();
        Path given = Paths.get(name.strip());
        if (given.isAbsolute()) candidates.add(given.normalize());
        else if (n.contains("/")) candidates.add(conversation.resolve(n).normalize());
        else {
            candidates.add(outputs.resolve(n).normalize());
            candidates.add(attachments.resolve(n).normalize());
        }
        for (Path p : candidates) {
            boolean inside = (p.startsWith(attachments) || p.startsWith(outputs)) && p.getParent() != null
                    && (p.getParent().equals(attachments) || p.getParent().equals(outputs));
            if (!inside) {
                throw new IllegalArgumentException("Only files in this conversation's attachments/ or outputs/ can be sent.");
            }
            if (Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (Files.size(p) > PythonSandbox.Limits.MAX_FILE_BYTES) {
                        throw new IllegalArgumentException(p.getFileName() + " is larger than 25 MB.");
                    }
                } catch (IOException e) {
                    throw new IllegalArgumentException("Could not read " + p.getFileName() + ".");
                }
                return p;
            }
        }
        throw new IllegalArgumentException("This conversation has no file '" + name.strip()
                + "' in attachments/ or outputs/.");
    }

    public static String fileUrl(String conversationId, String fileName) {
        return "/api/v1/chat/conversations/" + conversationId + "/files/"
                + UriUtils.encodePathSegment(fileName, StandardCharsets.UTF_8);
    }

    public record SavedFile(String name, long sizeBytes, boolean changed, Path path) {}
}
