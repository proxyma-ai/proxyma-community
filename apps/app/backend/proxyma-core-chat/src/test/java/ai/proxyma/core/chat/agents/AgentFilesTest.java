package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.domain.PermissionMode;
import ai.proxyma.core.chat.model.AgentInboxEntry;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.repository.AgentAlarmRepository;
import ai.proxyma.core.chat.repository.AgentArtifactRepository;
import ai.proxyma.core.chat.repository.AgentInboxRepository;
import ai.proxyma.core.chat.repository.AgentNodeRepository;
import ai.proxyma.core.chat.service.ChatEventSink;
import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FILES HANDED ON IN A TREE: a worker's file reaches its lead's attachments, where the lead's scripts
 * read it, with a record of where it came from and a message saying so; files handed down reach a new
 * agent before its first step; and only a bare name in one's own outputs/ or attachments/, only to one's
 * parent or one's own agents, is ever copied.
 */
class AgentFilesTest {

    static final String ROOT = "conv-root";

    @TempDir
    Path root;

    AgentNodeRepository nodes;
    AgentArtifactRepository artifacts;
    AgentInbox inbox;
    AgentTree tree;
    AgentFiles files;
    ChatService chat;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        nodes = InMemoryRepositories.of(AgentNodeRepository.class);
        artifacts = InMemoryRepositories.of(AgentArtifactRepository.class);
        AgentInboxRepository inboxRows = InMemoryRepositories.of(AgentInboxRepository.class, Map.of(
                "markDelivered", (store, args) -> {
                    int n = 0;
                    for (Object id : (Collection<?>) args[0]) {
                        AgentInboxEntry e = (AgentInboxEntry) store.get(id);
                        if (e != null && e.getDeliveredAt() == null) {
                            e.setDeliveredAt((Instant) args[1]);
                            n++;
                        }
                    }
                    return n;
                }));
        chat = mock(ChatService.class);
        when(chat.permissionMode(anyString())).thenReturn(PermissionMode.ASK);
        ObjectProvider<ChatService> chats = mock(ObjectProvider.class);
        when(chats.getObject()).thenReturn(chat);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(new SubAgentSettings());
        ApplicationEventPublisher events = e -> {};
        inbox = new AgentInbox(inboxRows, events);
        tree = new AgentTree(nodes, inbox, InMemoryRepositories.of(AgentAlarmRepository.class), chats, settings,
                mock(ChatEventSink.class), events);
        tree.runInline();
        files = new AgentFiles(root.toString(), false, tree, inbox, artifacts);
        tree.setFiles(AgentTreeWorkTest.provider(files));
    }

    Path folder(String conversationId, String name) throws IOException {
        Path dir = root.resolve(conversationId).resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    AgentNode deploy(String parent, String title, List<String> handed) {
        Map<String, Object> out = tree.deploy(parent, "u-1",
                new AgentTree.Deploy(title, "Do " + title, null, null, null, null, null, false, handed));
        assertThat(out).as(String.valueOf(out)).containsEntry("success", true);
        return nodes.findById(UUID.fromString((String) out.get("agentId"))).orElseThrow();
    }

    @Test
    @DisplayName("a worker's file reaches its lead's attachments, recorded, and the lead is told where it is")
    void handsUp() throws IOException {
        AgentNode worker = deploy(ROOT, "Exposure", null);
        Files.writeString(folder(worker.getConversationId(), "outputs").resolve("exposure.csv"), "ecu,affected\nBCM,yes\n");
        Files.writeString(folder(ROOT, "attachments").resolve("exposure.csv"), "an older copy");

        Map<String, Object> out = files.hand(worker.getConversationId(), "u-1", "exposure.csv", "parent", "One row per ECU");

        assertThat(out).containsEntry("success", true).containsEntry("name", "exposure-2.csv");
        assertThat(Files.readString(root.resolve(ROOT).resolve("attachments").resolve("exposure-2.csv"))).contains("BCM,yes");
        assertThat(artifacts.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getFromConversationId()).isEqualTo(worker.getConversationId());
            assertThat(a.getToConversationId()).isEqualTo(ROOT);
            assertThat(a.getRootConversationId()).isEqualTo(ROOT);
        });
        List<AgentInbox.Taken> got = inbox.takeWhere(ROOT, t -> true);
        assertThat(got).singleElement().satisfies(t -> assertThat(t.text())
                .contains("exposure-2.csv").contains("One row per ECU").contains("attachments/exposure-2.csv"));
    }

    @Test
    @DisplayName("files handed down are in a new agent's attachments before its first step, and its task says so")
    void handsDown() throws IOException {
        Files.writeString(folder(ROOT, "outputs").resolve("sbom.json"), "{}");
        AgentNode child = deploy(ROOT, "Read the SBOM", List.of("sbom.json"));
        assertThat(root.resolve(child.getConversationId()).resolve("attachments").resolve("sbom.json")).exists();
        verify(chat).runUnattended(eq(child.getConversationId()), contains("attachments (a script reads attachments/<name>): sbom.json"),
                any(), any(), any(), anyList());
    }

    @Test
    @DisplayName("a missing file stops the deployment before anything is made")
    void missingFileRefusesDeploy() {
        Map<String, Object> out = tree.deploy(ROOT, "u-1",
                new AgentTree.Deploy("x", "x", null, null, null, null, null, false, List.of("nope.csv")));
        assertThat(out).containsEntry("success", false);
        assertThat(nodes.findAll()).isEmpty();
    }

    @Test
    @DisplayName("only a bare name in one's own folders, and only to one's parent or one's own agents")
    void bounded() throws IOException {
        AgentNode a = deploy(ROOT, "A", null);
        AgentNode b = deploy(ROOT, "B", null);
        Files.writeString(folder(a.getConversationId(), "outputs").resolve("notes.md"), "x");
        assertThat(files.hand(a.getConversationId(), "u-1", "../" + b.getConversationId() + "/outputs/x", "parent", null))
                .containsEntry("success", false);
        assertThat(files.hand(a.getConversationId(), "u-1", ".secret", "parent", null)).containsEntry("success", false);
        assertThat(files.hand(a.getConversationId(), "u-1", "notes.md", b.getId().toString(), null))
                .as("a sibling is not one's own agent").containsEntry("success", false);
        assertThat(files.hand(ROOT, "u-1", "notes.md", "parent", null)).as("the root has no parent").containsEntry("success", false);
        assertThat(files.hand(a.getConversationId(), "u-1", "outputs/notes.md", "parent", null)).containsEntry("success", true);
    }

    @Test
    @DisplayName("a page lists a conversation's outputs and attachments, newest first, with no dot files")
    void lists() throws IOException {
        Files.writeString(folder(ROOT, "outputs").resolve("chart.png"), "png");
        Files.writeString(folder(ROOT, "attachments").resolve(".hidden"), "x");
        assertThat(files.filesOf(List.of(ROOT, "../escape"), "u-1")).extracting(AgentFiles.FileView::name)
                .containsExactly("chart.png");
    }
}
