package ai.proxyma.core.tools;

import ai.proxyma.core.common.audit.Audit;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.common.audit.ToolAudit;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.system.activity.ActivityCounterStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A tool call is recorded ONCE. The wrapper every tool runs through records what nobody above it
 * does - an MCP client's call, a workflow node's - and stands aside for the chat's tool path and a
 * sub-agent's, which record their own after the guardrail has seen the result.
 */
class ToolCallAuditTest {

    private ToolCallback wrapped;

    @BeforeEach
    void setUp() {
        Audit.drain(Integer.MAX_VALUE);
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("searchIndexedSources");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(anyString())).thenReturn("{\"results\":[]}");
        wrapped = new ActivityRecordingToolCallback(tool, mock(ActivityCounterStore.class));
    }

    @AfterEach
    void clear() {
        SearchContext.clear();
    }

    @Test
    @DisplayName("an MCP client's call - no conversation - is recorded by the wrapper")
    void mcpCallIsRecorded() {
        SearchContext.setUserId("u-1");
        wrapped.call("{\"query\":\"pumps\"}");

        List<AuditEntry> all = Audit.drain(10);
        assertEquals(1, all.size());
        assertEquals("searchIndexedSources", all.get(0).target());
        assertEquals("MCP client", all.get(0).detail().get("origin"));
        assertEquals("{\"query\":\"pumps\"}", all.get(0).detail().get("arguments"));
        assertEquals(AuditEntry.OK, all.get(0).outcome());
    }

    @Test
    @DisplayName("a call whose caller records it is not recorded a second time")
    void callerRecordedCallIsNotDoubled() {
        ToolAudit.recordedByCaller(() -> wrapped.call("{}"));
        assertTrue(Audit.drain(10).isEmpty());
    }

    @Test
    @DisplayName("a call that throws is recorded as FAILED, and the exception still reaches the caller")
    void failureIsRecorded() {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("read");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(anyString())).thenThrow(new IllegalStateException("boom"));
        ToolCallback failing = new ActivityRecordingToolCallback(tool, mock(ActivityCounterStore.class));

        assertThrows(IllegalStateException.class, () -> failing.call("{}"));
        assertEquals(AuditEntry.FAILED, Audit.drain(10).get(0).outcome());
    }
}
