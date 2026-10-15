package ai.proxyma.core.common.audit;

import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit queue every module records into, and the tool-call recorder on top of it.
 *
 * <p>Nothing here may fail or block what it records: a guardrail check and a tool call both call
 * it inline. So it is a bounded queue that keeps the newest entries when nothing drains it, and a
 * tool call is recorded by exactly one of the two places that can see it.
 */
class AuditQueueTest {

    @BeforeEach
    void empty() {
        Audit.drain(Integer.MAX_VALUE);
        Audit.takeDropped();
    }

    @Test
    @DisplayName("entries come out oldest first, and a drain takes no more than it is asked for")
    void drainsInOrder() {
        AuditEntry.of(AuditCategory.CONFIG, "a").system().record();
        AuditEntry.of(AuditCategory.CONFIG, "b").system().record();
        AuditEntry.of(AuditCategory.CONFIG, "c").system().record();

        List<AuditEntry> first = Audit.drain(2);
        assertEquals(List.of("a", "b"), first.stream().map(AuditEntry::action).toList());
        assertEquals(1, Audit.pending());
        assertEquals("c", Audit.drain(10).get(0).action());
    }

    @Test
    @DisplayName("with nobody draining, the queue keeps the newest entries and counts what it dropped")
    void boundedWhenNotDrained() {
        for (int i = 0; i < Audit.CAPACITY + 25; i++) {
            AuditEntry.of(AuditCategory.TOOL, "n" + i).system().record();
        }
        assertEquals(Audit.CAPACITY, Audit.pending());
        assertEquals(25, Audit.takeDropped());
        assertEquals(0, Audit.takeDropped());
        assertEquals("n25", Audit.drain(1).get(0).action());
    }

    @Test
    @DisplayName("an entry with no actor is the thread's spend subject, and SYSTEM where none is set")
    void actorDefaultsToTheThreadsSubject() {
        assertEquals(SpendSubject.SYSTEM, AuditEntry.of(AuditCategory.DATA, "x").build().actor());
        SpendContext.set(SpendSubject.user("u-7"));
        try {
            assertEquals(SpendSubject.user("u-7"), AuditEntry.of(AuditCategory.DATA, "x").build().actor());
        } finally {
            SpendContext.clear();
        }
    }

    @Test
    @DisplayName("a null detail value is left out rather than written as null")
    void nullDetailIsSkipped() {
        AuditEntry e = AuditEntry.of(AuditCategory.DATA, "x").detail("kept", 1).detail("gone", null).build();
        assertEquals(1, e.detail().size());
        assertTrue(e.detail().containsKey("kept"));
    }

    @Test
    @DisplayName("cap keeps the beginning and says how much was left out")
    void capSaysWhatItCut() {
        assertNull(AuditEntry.cap(null, 5));
        assertEquals("short", AuditEntry.cap("short", 5));
        assertEquals("abcde... [3 more characters]", AuditEntry.cap("abcdefgh", 5));
    }

    @Test
    @DisplayName("a failure envelope, or a plain-text tool's Error:, is FAILED; anything else is OK")
    void outcomeOfAResult() {
        assertEquals(AuditEntry.FAILED, ToolAudit.outcomeOf("{\"success\":false,\"error\":\"x\"}"));
        assertEquals(AuditEntry.FAILED, ToolAudit.outcomeOf("{ \"success\" : false }"));
        assertEquals(AuditEntry.FAILED, ToolAudit.outcomeOf(null));
        assertEquals(AuditEntry.OK, ToolAudit.outcomeOf("{\"success\":true}"));
        assertEquals(AuditEntry.OK, ToolAudit.outcomeOf("1: line one"));
        // read and grep return plain text, and report a failure as "Error: ...".
        assertEquals(AuditEntry.FAILED, ToolAudit.outcomeOf("Error: NullPointerException"));
    }

    @Test
    @DisplayName("a call wrapped by its caller says so to the wrapper below it, and only for its own length")
    void recordedByCallerIsScoped() {
        assertFalse(ToolAudit.isRecordedByCaller());
        boolean inside = ToolAudit.recordedByCaller(() ->
                ToolAudit.recordedByCaller(ToolAudit::isRecordedByCaller) && ToolAudit.isRecordedByCaller());
        assertTrue(inside);
        assertFalse(ToolAudit.isRecordedByCaller());
    }

    @Test
    @DisplayName("a tool call is recorded with its arguments and result, each cut to its cap")
    void toolCallIsCapped() {
        String big = "x".repeat(ToolAudit.RESULT_CAP + 100);
        ToolAudit.record("u-1", "conv-1", "searchIndexedSources", "Agent", null, "{\"q\":\"pumps\"}",
                big, null, null, 42);
        AuditEntry e = Audit.drain(1).get(0);
        assertEquals(AuditCategory.TOOL, e.category());
        assertEquals("searchIndexedSources", e.target());
        assertEquals("conv-1", e.conversationId());
        assertEquals(AuditEntry.OK, e.outcome());
        assertEquals(42L, e.durationMs());
        assertEquals("{\"q\":\"pumps\"}", e.detail().get("arguments"));
        assertTrue(((String) e.detail().get("result")).endsWith("[100 more characters]"));
        assertEquals(big.length(), e.detail().get("resultCharacters"));
    }
}
