package ai.proxyma.core.settings.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a sub-agent with nobody to ask - one started from outside a conversation - is never handed.
 *
 * <p>The failure this pins: useServer sat in the Jira, Confluence and Bitbucket skill groups, so a
 * sub-agent working under one of them received it - and a sub-agent runs under its parent's
 * conversation id, so its useServer switched the PARENT conversation's server for every later call.
 * (A sub-agent inside a conversation is refused useServer too, by ChatService.refusedToSubAgents.)
 */
class SubAgentToolBlockTest {

    @Test
    @DisplayName("useServer is kept from unattended sub-agents, and is still not a write tool")
    void useServerIsBlockedButNotAWrite() {
        assertTrue(WriteToolRegistry.NOT_FOR_UNATTENDED_AGENTS.contains("useServer"));
        assertFalse(WriteToolRegistry.NAMES.contains("useServer"));
        assertFalse(WriteToolRegistry.isWriteCall("useServer", "{\"type\":\"JIRA\",\"name\":\"x\"}"));
    }

    @Test
    @DisplayName("every write tool and every workspace tool is kept from unattended sub-agents")
    void writeAndWorkspaceToolsStayBlocked() {
        assertTrue(WriteToolRegistry.NOT_FOR_UNATTENDED_AGENTS.containsAll(WriteToolRegistry.NAMES));
        assertTrue(WriteToolRegistry.NOT_FOR_UNATTENDED_AGENTS.containsAll(WriteToolRegistry.WORKSPACE_NAMES));
    }
}
