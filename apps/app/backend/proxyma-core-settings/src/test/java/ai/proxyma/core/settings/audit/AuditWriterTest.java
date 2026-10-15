package ai.proxyma.core.settings.audit;

import ai.proxyma.core.common.audit.Audit;
import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.settings.usage.UserSpendPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What an audit row is made of, written by the one place every entry crosses on its way to disk.
 *
 * <p>The Audit Logs page is a client like any other, so an absolute server path must never reach
 * a row - in the summary, the target or anywhere in the detail. And who did it is filed as the LLM
 * call log files it: everyone as the one local user on Desktop and Home Server, except work nobody
 * asked for, which stays the system's.
 */
class AuditWriterTest {

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void empty() {
        Audit.drain(Integer.MAX_VALUE);
    }

    private AuditWriter writer(boolean multiUser) {
        return new AuditWriter(mock(AuditEventRepository.class), mock(PlatformTransactionManager.class), json, multiUser);
    }

    private static AuditEntry entry(SpendSubject actor) {
        return AuditEntry.of(AuditCategory.TOOL, "tool.call").actor(actor).build();
    }

    @Test
    @DisplayName("on Desktop and Home Server a person is the local user, and the system stays the system")
    void singleUserFilesEveryoneAsLocal() {
        List<AuditEvent> rows = writer(false).toRows(List.of(
                entry(SpendSubject.user("u-1")), entry(SpendSubject.ANONYMOUS), entry(SpendSubject.SYSTEM)));

        assertEquals(SpendSubject.LOCAL_ID, rows.get(0).getActorId());
        assertEquals(SpendSubject.LOCAL_ID, rows.get(1).getActorId());
        assertEquals("SYSTEM", rows.get(2).getActorKind());
        assertEquals(SpendSubject.SYSTEM_ID, rows.get(2).getActorId());
    }

    @Test
    @DisplayName("on Enterprise Server a person keeps their id, and their name is captured as the row is written")
    void multiUserNamesThePerson() {
        AuditWriter writer = writer(true);
        UserSpendPort users = mock(UserSpendPort.class);
        when(users.displayNames(any())).thenReturn(Map.of("u-1", "Ana"));
        writer.setUsers(users);

        AuditEvent row = writer.toRows(List.of(entry(SpendSubject.user("u-1")))).get(0);

        assertEquals("USER", row.getActorKind());
        assertEquals("u-1", row.getActorId());
        assertEquals("Ana", row.getActorName());
    }

    @Test
    @DisplayName("no absolute server path survives - summary, target, or anywhere in the detail")
    void pathsAreScrubbed() throws Exception {
        AuditEntry e = AuditEntry.of(AuditCategory.TOOL, "tool.call").system()
                .summary("read /data/users/u-1/conversations/c-9/notes.txt")
                .target("C:\\Users\\bob\\AppData\\Local\\proxyma\\x.md")
                .detail("arguments", "{\"path\":\"/home/bob/secret/plan.docx\"}")
                .detail("nested", Map.of("list", List.of("D:\\Work\\deep\\file.pdf")))
                .build();

        AuditEvent row = writer(false).toRows(List.of(e)).get(0);

        String all = row.getSummary() + row.getTarget() + row.getDetail();
        assertFalse(all.contains("/data/users"), all);
        assertFalse(all.contains("Users\\bob"), all);
        assertFalse(all.contains("/home/bob"), all);
        assertFalse(all.contains("D:\\"), all);
        assertTrue(row.getSummary().contains("notes.txt"));
        // Still JSON after the scrub.
        json.readTree(row.getDetail());
    }

    @Test
    @DisplayName("a URL is not a path and is kept whole")
    void urlsAreKept() {
        AuditEvent row = writer(false).toRows(List.of(AuditEntry.of(AuditCategory.SOURCES, "source.added")
                .system().target("https://wiki.example.com/data/space").build())).get(0);
        assertEquals("https://wiki.example.com/data/space", row.getTarget());
    }

    @Test
    @DisplayName("a detail too large to keep is replaced by a note saying so, never truncated mid-JSON")
    void oversizedDetailIsANote() throws Exception {
        AuditEvent row = writer(false).toRows(List.of(AuditEntry.of(AuditCategory.DIAGNOSTICS, "diagnostics.sent")
                .system().detail("report", "y".repeat(AuditWriter.MAX_DETAIL_CHARS + 1)).build())).get(0);

        assertTrue(json.readTree(row.getDetail()).get("note").asText().contains("was not kept"));
    }

    @Test
    @DisplayName("columns are clipped to their width, and an empty detail is no detail")
    void clippedToColumns() {
        AuditEvent row = writer(false).toRows(List.of(AuditEntry.of(AuditCategory.CONFIG, "setting.saved")
                .system().summary("s".repeat(900)).target("t".repeat(400)).build())).get(0);

        assertEquals(500, row.getSummary().length());
        assertEquals(255, row.getTarget().length());
        assertNull(row.getDetail());
    }
}
