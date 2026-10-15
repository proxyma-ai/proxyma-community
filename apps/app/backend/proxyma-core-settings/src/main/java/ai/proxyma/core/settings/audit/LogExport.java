package ai.proxyma.core.settings.audit;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * A log as a file - the Audit Logs page's Download, for the audit tabs and the LLM call log alike.
 *
 * <p>BOUNDED: at most {@link #MAX_ROWS} entries, newest first under the reader's own filters, and the
 * response says when it stopped there ({@code X-Proxyma-Truncated}). A tool call carries up to
 * twelve thousand characters of arguments and result, so ten thousand rows is already a file of
 * some size; a longer history is exported a period at a time.
 *
 * <p>CSV that a spreadsheet opens as written: UTF-8 with a byte-order mark, so Excel does not read
 * Vietnamese as mojibake, and every cell that begins like a formula (= + - @, a tab or a carriage
 * return) is prefixed with an apostrophe - a log of what an agent was given is exactly where text
 * somebody else wrote ends up, and a spreadsheet must not run it.
 */
public final class LogExport {

    public static final int MAX_ROWS = 10_000;
    public static final String TRUNCATED_HEADER = "X-Proxyma-Truncated";

    private LogExport() {}

    /** One CSV document: the header row, then the rows, CRLF-terminated as RFC 4180 has it. */
    public static String csv(List<String> header, List<List<Object>> rows) {
        StringBuilder out = new StringBuilder("\uFEFF");
        line(out, header.stream().map(h -> (Object) h).toList());
        for (List<Object> row : rows) line(out, row);
        return out.toString();
    }

    private static void line(StringBuilder out, List<Object> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(',');
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
    }

    static String cell(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (!(value instanceof Number) && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        return quote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    /** The file response: its type, a dated name, and whether it stopped at the row limit. */
    public static ResponseEntity<byte[]> file(String baseName, String format, String body, boolean truncated) {
        boolean json = "json".equals(format);
        String name = baseName + "-" + LocalDate.now() + (json ? ".json" : ".csv");
        return ResponseEntity.ok()
                .contentType(json ? MediaType.APPLICATION_JSON : new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(TRUNCATED_HEADER, String.valueOf(truncated))
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION + ", " + TRUNCATED_HEADER)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Who, for a person reading the file: a name where one was captured, the one person on Desktop
     * and Home Server as "Local user", the system as "Proxyma" and a visitor as "Guest" - never the
     * internal markers those are stored under.
     */
    public static String who(String kind, String id, String name) {
        if ("SYSTEM".equals(kind)) return "Proxyma";
        if ("ANONYMOUS".equals(kind)) return "Guest";
        if (ai.proxyma.core.common.spend.SpendSubject.LOCAL_ID.equals(id)) return "Local user";
        return name != null && !name.isBlank() ? name : id;
    }

    /** csv unless json was asked for by name - anything else is the default rather than an error. */
    public static String format(String requested) {
        return "json".equalsIgnoreCase(requested) ? "json" : "csv";
    }
}
