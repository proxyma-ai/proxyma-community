package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.event.ConversationsDeletedEvent;
import ai.proxyma.core.chat.model.AgentArtifact;
import ai.proxyma.core.chat.model.AgentEvent;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.repository.AgentArtifactRepository;
import ai.proxyma.core.common.conversation.ConversationPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * FILES HANDED UP AND DOWN A TREE (2026-10-29, the Constellations samples' gap 4). An agent's scripts
 * read its own conversation's attachments/ and write its outputs/, and nothing else - so a CSV a
 * worker computed never reached the lead that needed it. handFile copies one file from the caller's
 * outputs/ or attachments/ into its parent's or one of its descendants' attachments/, records where it
 * came from (AgentArtifact) and tells the recipient; deployAgent's {@code files} hands files down to a
 * new agent before its first step.
 *
 * <p>Only between conversations of ONE tree, which are all one person's, and only by a bare file name
 * - a path, a dot file or a link is refused - so nothing outside the two folders is ever read.
 */
@Service
public class AgentFiles {

    private static final Logger log = LoggerFactory.getLogger(AgentFiles.class);

    static final long FILE_MAX = 25L * 1024 * 1024;
    static final int FILES_MAX = 10;
    static final int LIST_MAX = 200;

    /** One file a page lists: where it lives and what it is. */
    public record FileView(String conversationId, String folder, String name, long sizeBytes, Instant modifiedAt) {}

    private final ConversationPaths paths;
    private final AgentTree tree;
    private final AgentInbox inbox;
    private final AgentArtifactRepository artifacts;
    private AgentEvents record;

    public AgentFiles(@Value("${proxyma.conversations-dir:${user.home}/.proxyma/conversations}") String conversationsDir,
                      @Value("${proxyma.multi-user:false}") boolean multiUser, AgentTree tree, AgentInbox inbox,
                      AgentArtifactRepository artifacts) {
        this.paths = new ConversationPaths(conversationsDir, multiUser);
        this.tree = tree;
        this.inbox = inbox;
        this.artifacts = artifacts;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRecord(AgentEvents record) {
        this.record = record;
    }

    /**
     * handFile: from the caller's conversation to {@code to} - "parent", or the agentId of an agent it
     * deployed or one of theirs - and the recipient is told.
     */
    public Map<String, Object> hand(String fromConversationId, String userId, String name, String to, String note) {
        if (fromConversationId == null) return AgentTree.failure("Files are handed on only inside a conversation.");
        String target;
        String toTitle;
        Optional<AgentNode> me = tree.node(fromConversationId);
        if ("parent".equalsIgnoreCase(to == null ? "" : to.strip())) {
            if (me.isEmpty()) return AgentTree.failure("You have no parent agent: you are the agent the user is talking to.");
            target = me.get().getParentConversationId();
            toTitle = "your parent";
        } else {
            Optional<AgentNode> t = tree.byId(to);
            if (t.isEmpty() || !tree.isAncestor(fromConversationId, t.get())) {
                return AgentTree.failure("You can hand files to your parent (to \"parent\") and the agents you deployed - "
                        + "not " + to + ".");
            }
            target = t.get().getConversationId();
            toTitle = t.get().getTitle();
        }
        Map<String, Object> copied = copy(fromConversationId, target, userId, name, note);
        if (!Boolean.TRUE.equals(copied.get("success"))) return copied;
        String fromTitle = me.map(AgentNode::getTitle).orElse("your parent");
        inbox.post(target, AgentInbox.Kind.MESSAGE, me.map(AgentNode::getId).orElse(null), fromTitle,
                "I handed you the file \"" + copied.get("name") + "\" (" + copied.get("sizeBytes") + " bytes)"
                        + (note == null || note.isBlank() ? "." : ": " + note.strip())
                        + " It is in your attachments: a script reads it as attachments/" + copied.get("name") + ".");
        Map<String, Object> out = new LinkedHashMap<>(copied);
        out.put("note", "Handed to " + toTitle + ", who is told.");
        return out;
    }

    /** Whether these names are files of the parent's that can be handed down - null when they are, else why not. */
    String checkDown(String parentConversationId, String userId, List<String> names) {
        if (names == null || names.isEmpty()) return null;
        if (names.size() > FILES_MAX) return "At most " + FILES_MAX + " files go with one agent.";
        for (String n : names) {
            try {
                source(parentConversationId, userId, n);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        return null;
    }

    /** Hands these files down to a new agent, before its first step. */
    List<String> handDown(String parentConversationId, String childConversationId, String userId, List<String> names) {
        List<String> handed = new ArrayList<>();
        if (names == null) return handed;
        for (String n : names) {
            Map<String, Object> r = copy(parentConversationId, childConversationId, userId, n, "handed down at deployment");
            if (Boolean.TRUE.equals(r.get("success"))) handed.add(String.valueOf(r.get("name")));
        }
        return handed;
    }

    private Map<String, Object> copy(String fromConversationId, String toConversationId, String userId, String name,
                                     String note) {
        Path source;
        try {
            source = source(fromConversationId, userId, name);
        } catch (IllegalArgumentException e) {
            return AgentTree.failure(e.getMessage());
        }
        try {
            Path dir = paths.conversationDir(toConversationId, userId).resolve("attachments");
            Files.createDirectories(dir);
            String fileName = free(dir, source.getFileName().toString());
            Files.copy(source, dir.resolve(fileName), StandardCopyOption.COPY_ATTRIBUTES);
            long size = Files.size(dir.resolve(fileName));
            AgentArtifact a = new AgentArtifact();
            a.setRootConversationId(tree.rootOf(fromConversationId));
            a.setFromConversationId(fromConversationId);
            a.setToConversationId(toConversationId);
            a.setFileName(fileName);
            a.setSizeBytes(size);
            a.setNote(note == null || note.isBlank() ? null : AgentInbox.clip(note.strip(), AgentArtifact.NOTE_MAX));
            artifacts.save(a);
            if (record != null) {
                record.write(a.getRootConversationId(), fromConversationId,
                        tree.node(fromConversationId).map(AgentNode::getId).orElse(null), AgentEvent.FILE, null,
                        tree.node(toConversationId).map(AgentNode::getId).orElse(null), fileName);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("name", fileName);
            out.put("sizeBytes", size);
            return out;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("[Agents] could not hand on {} from {}: {}", name, fromConversationId, e.getMessage());
            return AgentTree.failure("The file could not be copied.");
        }
    }

    // The file a bare name means in a conversation: its outputs/ first, then its attachments/.
    private Path source(String conversationId, String userId, String name) {
        String n = name == null ? "" : name.strip();
        if (n.startsWith("outputs/")) n = n.substring("outputs/".length());
        else if (n.startsWith("attachments/")) n = n.substring("attachments/".length());
        if (n.isEmpty() || n.startsWith(".") || n.contains("/") || n.contains("\\") || n.contains("..") || n.length() > 255) {
            throw new IllegalArgumentException("Name the file as it is in your outputs/ or attachments/ - a bare file "
                    + "name, e.g. exposure.csv.");
        }
        Path conversation = paths.conversationDir(conversationId, userId);
        for (String folder : List.of("outputs", "attachments")) {
            Path p = conversation.resolve(folder).resolve(n).normalize();
            if (!p.getParent().equals(conversation.resolve(folder).normalize())) continue;
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (Files.size(p) > FILE_MAX) throw new IllegalArgumentException(n + " is larger than 25 MB.");
                } catch (IOException e) {
                    throw new IllegalArgumentException("Could not read " + n + ".");
                }
                return p;
            }
        }
        throw new IllegalArgumentException("You have no file \"" + n + "\" in outputs/ or attachments/.");
    }

    // A name not yet taken in the folder: report.csv, then report-2.csv, ...
    static String free(Path dir, String name) {
        if (!Files.exists(dir.resolve(name))) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            String candidate = stem + "-" + i + ext;
            if (!Files.exists(dir.resolve(candidate))) return candidate;
        }
        return stem + "-" + System.currentTimeMillis() + ext;
    }

    // -- Reading, for a page ------------------------------------------------------------------------

    /** The files in these conversations' outputs/ and attachments/, newest first, at most {@value #LIST_MAX}. */
    public List<FileView> filesOf(Collection<String> conversationIds, String userId) {
        List<FileView> out = new ArrayList<>();
        if (conversationIds == null) return out;
        for (String id : conversationIds) {
            if (!ConversationPaths.isValidId(id)) continue;
            Path conversation;
            try {
                conversation = paths.conversationDir(id, userId);
            } catch (IllegalArgumentException e) {
                continue;
            }
            for (String folder : List.of("outputs", "attachments")) {
                Path dir = conversation.resolve(folder);
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> list = Files.list(dir)) {
                    list.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                            .filter(p -> !p.getFileName().toString().startsWith("."))
                            .forEach(p -> {
                                try {
                                    out.add(new FileView(id, folder, p.getFileName().toString(), Files.size(p),
                                            Files.getLastModifiedTime(p).toInstant()));
                                } catch (IOException ignored) {
                                    // Gone between the listing and the read.
                                }
                            });
                } catch (IOException e) {
                    log.debug("[Agents] could not list {} of {}: {}", folder, id, e.getMessage());
                }
            }
        }
        out.sort((a, b) -> b.modifiedAt().compareTo(a.modifiedAt()));
        return out.size() > LIST_MAX ? new ArrayList<>(out.subList(0, LIST_MAX)) : out;
    }

    /** The hand-offs in these trees, newest first. */
    public List<AgentArtifact> handOffsIn(Collection<String> roots) {
        if (roots == null || roots.isEmpty()) return List.of();
        return artifacts.findByRootConversationIdInOrderByCreatedAtDesc(roots, PageRequest.of(0, LIST_MAX));
    }

    @EventListener
    public void onConversationsDeleted(ConversationsDeletedEvent event) {
        if (event.conversationIds().isEmpty()) return;
        try {
            artifacts.deleteByRootConversationIdIn(event.conversationIds());
        } catch (RuntimeException e) {
            log.warn("[Agents] could not remove the hand-offs of deleted conversations: {}", e.getMessage());
        }
    }
}
