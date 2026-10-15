package ai.proxyma.core.settings.audit;

import ai.proxyma.core.common.audit.Audit;
import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A log as a file - the Audit Logs page's Download. CSV a spreadsheet opens as written and never
 * runs: a log of what an agent was given is exactly where text somebody else wrote ends up. Bounded,
 * and saying so. Read by the same people who may read the log, and recorded.
 */
class LogExportTest {

    @BeforeEach
    void empty() {
        Audit.drain(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("a cell that begins like a formula is defused, and quotes, commas and newlines are quoted")
    void cellsAreSafe() {
        assertEquals("'+1", LogExport.cell("+1"));
        assertEquals("'@cmd", LogExport.cell("@cmd"));
        assertEquals("'=1+1", LogExport.cell("=1+1"));
        assertEquals("\"a,b\"", LogExport.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", LogExport.cell("say \"hi\""));
        assertEquals("\"two\nlines\"", LogExport.cell("two\nlines"));
        // A negative NUMBER is a number, not a formula.
        assertEquals("-3", LogExport.cell(-3));
        assertEquals("", LogExport.cell(null));
    }

    @Test
    @DisplayName("CSV starts with a byte-order mark and ends each row with CRLF")
    void csvShape() {
        String csv = LogExport.csv(List.of("A", "B"), List.of(Arrays.asList("x", 1), Arrays.asList(null, "y")));
        assertEquals("\uFEFFA,B\r\nx,1\r\n,y\r\n", csv);
    }

    @Test
    @DisplayName("who is a name, never an internal marker")
    void whoReadsAsAName() {
        assertEquals("Local user", LogExport.who("USER", "_local", null));
        assertEquals("Proxyma", LogExport.who("SYSTEM", "system", null));
        assertEquals("Guest", LogExport.who("ANONYMOUS", "anonymous", null));
        assertEquals("Ana", LogExport.who("USER", "u-1", "Ana"));
        assertEquals("u-1", LogExport.who("USER", "u-1", null));
    }

    @Test
    @DisplayName("anything but json is CSV")
    void formatDefault() {
        assertEquals("json", LogExport.format("JSON"));
        assertEquals("csv", LogExport.format(null));
        assertEquals("csv", LogExport.format("xlsx"));
    }

    private static AuditEvent event(int i) {
        AuditEvent e = new AuditEvent();
        e.setOccurredAt(Instant.parse("2026-10-12T10:00:00Z").minusSeconds(i));
        e.setCategory(AuditCategory.TOOL);
        e.setAction("tool.call");
        e.setOutcome("OK");
        e.setActorKind("USER");
        e.setActorId("_local");
        e.setTarget("read");
        e.setSummary("=2+3");
        e.setDetail("{\"arguments\":\"{}\"}");
        return e;
    }

    private static AuditController controller(int rows) {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        List<AuditEvent> all = new ArrayList<>();
        for (int i = 0; i < rows; i++) all.add(event(i));
        when(repository.search(any(), any(), anyCollection(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    PageRequest p = inv.getArgument(9);
                    int from = (int) p.getOffset();
                    int to = Math.min(all.size(), from + p.getPageSize());
                    return new PageImpl<>(from >= all.size() ? List.of() : all.subList(from, to), p, all.size());
                });
        return new AuditController(repository, mock(AuditWriter.class), new ObjectMapper());
    }

    private static Mono<ResponseEntity<byte[]>> export(AuditController c, String format) {
        return c.export("TOOL", null, 30, null, null, null, null, null, null, null, null, format);
    }

    @Test
    @DisplayName("the CSV carries every row, a formula in a summary defused, and the download is recorded")
    void csvExportIsRecorded() {
        ResponseEntity<byte[]> r = export(controller(3), "csv").block();

        String body = new String(r.getBody(), StandardCharsets.UTF_8);
        assertEquals(4, body.split("\r\n").length);
        assertTrue(body.contains(",'=2+3,"), body);
        assertTrue(r.getHeaders().getContentDisposition().getFilename().startsWith("proxyma-audit-tool-"));
        assertEquals("false", r.getHeaders().getFirst(LogExport.TRUNCATED_HEADER));
        AuditEntry recorded = Audit.drain(10).get(0);
        assertEquals("audit.exported", recorded.action());
        assertEquals(3, recorded.detail().get("entries"));
    }

    @Test
    @DisplayName("JSON carries each entry whole, detail included")
    void jsonExport() throws Exception {
        ResponseEntity<byte[]> r = export(controller(2), "json").block();
        JsonNode doc = new ObjectMapper().readTree(r.getBody());
        assertEquals(2, doc.get("entries").size());
        assertEquals("{}", doc.get("entries").get(0).get("detail").get("arguments").asText());
    }

    @Test
    @DisplayName("it stops at the row limit and says so")
    void boundedAndSaysSo() {
        ResponseEntity<byte[]> r = export(controller(LogExport.MAX_ROWS + 7), "csv").block();
        String body = new String(r.getBody(), StandardCharsets.UTF_8);
        assertEquals(LogExport.MAX_ROWS + 1, body.split("\r\n").length);
        assertEquals("true", r.getHeaders().getFirst(LogExport.TRUNCATED_HEADER));
    }

    @Test
    @DisplayName("on Enterprise Server only an administrator downloads it, and is named as who did")
    void onlyAdministratorsDownload() {
        AuditController c = controller(1);
        ReflectionTestUtils.setField(c, "multiUser", true);
        var user = new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertEquals(403, export(c, "csv").contextWrite(ReactiveSecurityContextHolder.withAuthentication(user))
                .block().getStatusCode().value());
        var admin = new UsernamePasswordAuthenticationToken("a", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertEquals(200, export(c, "csv").contextWrite(ReactiveSecurityContextHolder.withAuthentication(admin))
                .block().getStatusCode().value());
        assertEquals("a", Audit.drain(10).get(0).actor().id());
    }
}
