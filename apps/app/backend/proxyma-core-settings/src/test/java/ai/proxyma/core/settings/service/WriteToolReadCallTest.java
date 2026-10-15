package ai.proxyma.core.settings.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A READ through a tool that can also write is not a write.
 *
 * <p>The chat decides whether to ask the user to switch write access ON from this answer. It used
 * to decide from the tool name alone, so the agent's first GET through callJiraApi - read on, write
 * off, the defaults - stopped on an "enable Jira write tools" prompt for a call that needed only
 * the read access it already had.
 */
class WriteToolReadCallTest {

    @Test
    @DisplayName("a GET through a passthrough tool is a read, in any case and with the verb omitted")
    void passthroughGetIsARead() {
        for (String tool : WriteToolRegistry.PASSTHROUGH_NAMES) {
            assertFalse(WriteToolRegistry.isWriteCall(tool, "{\"method\":\"GET\",\"path\":\"/rest/api/2/myself\"}"), tool);
            assertFalse(WriteToolRegistry.isWriteCall(tool, "{\"method\":\" get \"}"), tool);
            assertFalse(WriteToolRegistry.isWriteCall(tool, "{\"path\":\"/x\"}"), tool);
        }
    }

    @Test
    @DisplayName("every other verb through a passthrough tool is a write")
    void passthroughOtherVerbsWrite() {
        for (String verb : new String[]{"POST", "PUT", "DELETE", "PATCH", "HEAD"}) {
            assertTrue(WriteToolRegistry.isWriteCall("callJiraApi", Map.of("method", verb)), verb);
        }
    }

    @Test
    @DisplayName("linkJiraIssues lists link types without a linkType, and writes with one")
    void linkJiraIssues() {
        assertFalse(WriteToolRegistry.isWriteCall("linkJiraIssues", "{}"));
        assertFalse(WriteToolRegistry.isWriteCall("linkJiraIssues", "{\"linkType\":\"  \"}"));
        assertTrue(WriteToolRegistry.isWriteCall("linkJiraIssues",
                "{\"linkType\":\"Blocks\",\"inwardIssueKey\":\"A-1\",\"outwardIssueKey\":\"A-2\"}"));
    }

    @Test
    @DisplayName("transitionJiraIssue lists transitions without a targetStatus, and moves the issue with one")
    void transitionJiraIssue() {
        assertFalse(WriteToolRegistry.isWriteCall("transitionJiraIssue", "{\"issueKey\":\"A-1\"}"));
        assertFalse(WriteToolRegistry.isWriteCall("transitionJiraIssue", "{\"issueKey\":\"A-1\",\"targetStatus\":\" \"}"));
        assertTrue(WriteToolRegistry.isWriteCall("transitionJiraIssue",
                "{\"issueKey\":\"A-1\",\"targetStatus\":\"Done\"}"));
    }

    @Test
    @DisplayName("a write-only tool is a write whatever it is called with, and a read tool never is")
    void byName() {
        assertTrue(WriteToolRegistry.isWriteCall("createJiraIssue", "{}"));
        assertTrue(WriteToolRegistry.isWriteCall("removeSource", (String) null));
        assertTrue(WriteToolRegistry.isWriteCall("addSource", "{}"));
        assertFalse(WriteToolRegistry.isWriteCall("listSourceTypes", "{}"));
        assertFalse(WriteToolRegistry.isWriteCall("getJiraIssue", "{\"method\":\"POST\"}"));
    }

    @Test
    @DisplayName("arguments that cannot be read count as a write - the stricter answer")
    void unreadableArguments() {
        assertTrue(WriteToolRegistry.isWriteCall("callConfluenceApi", "not json"));
    }

    @Test
    @DisplayName("saveToSource is a write in every form, and kept from an agent nobody can ask")
    void saveToSourceWrites() {
        assertTrue(WriteToolRegistry.isWriteCall("saveToSource", "{}"));
        assertTrue(WriteToolRegistry.isWriteCall("saveToSource", Map.of("sourceId", "x", "title", "t", "text", "b")));
        assertTrue(WriteToolRegistry.NOT_FOR_UNATTENDED_AGENTS.contains("saveToSource"));
    }
}
