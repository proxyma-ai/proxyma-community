package ai.proxyma.core.ingest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

// Table-aware PDF extraction via pdfplumber, exposed as an agent-facing conversion primitive
// (higher column fidelity than the generic markitdown conversion used for indexing; MarkitdownService
// is untouched and still used for indexing and for non-PDF formats in ConvertDocumentTool).
//
// pdfplumber is a Python library with no JVM port, so this runs its extractor program through
// PythonRuntime (which owns interpreter resolution and the `python -` stdin protocol - see that
// class). The program itself lives here as EXTRACTOR_SCRIPT, so there is NO .py file to bundle,
// copy, or keep in sync across the desktop installer, the server venv, and downstream deployments
//. Availability is confirmed at startup by importing pdfplumber in the resolved
// interpreter, not just by the interpreter existing.
@Service
public class PdfTableExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PdfTableExtractionService.class);
    private static final int TIMEOUT_MINUTES = 5;

    // The pdfplumber extractor program, fed to `python -` over stdin (argv[1] is the PDF path).
    // Font-size-relative x_tolerance (x_tolerance_ratio) restores word spacing on PDFs that position
    // glyphs without space characters (e.g. some vendor manuals), which pdfplumber's default 3pt tolerance
    // otherwise glues into "Themainpurposeofthe..."; it falls back for older pdfplumber that lacks
    // the kwarg. Kept here rather than as a .py file so a single source of truth travels inside this
    // module's jar - no separate script to bundle/copy/sync per deployment.
    private static final String EXTRACTOR_SCRIPT = """
            import sys
            import json
            import pdfplumber


            def extract_text_spaced(page):
                try:
                    return page.extract_text(x_tolerance_ratio=0.15) or ""
                except TypeError:
                    try:
                        return page.extract_text(x_tolerance=1.5) or ""
                    except Exception:
                        return page.extract_text() or ""


            def extract_tables_spaced(page):
                try:
                    return page.extract_tables({"text_x_tolerance": 1.5})
                except Exception:
                    return page.extract_tables()


            def main():
                path = sys.argv[1]
                result = {"pages": []}
                with pdfplumber.open(path) as pdf:
                    for i, page in enumerate(pdf.pages, start=1):
                        tables = [{"rows": table} for table in extract_tables_spaced(page)]
                        result["pages"].append({
                            "page": i,
                            "text": extract_text_spaced(page),
                            "tables": tables,
                        })
                print(json.dumps(result))


            try:
                main()
            except Exception as e:
                print(f"pdf_table_extract failed: {e}", file=sys.stderr)
                sys.exit(1)
            """;

    private final ObjectMapper objectMapper;
    private final PythonRuntime python;
    // Whether pdfplumber imports, from init()'s check - see PythonProbe. Already answered (no) for a
    // service nobody initialized, as the plain field was.
    private volatile CompletableFuture<Boolean> pdfplumberAvailable = CompletableFuture.completedFuture(false);

    public PdfTableExtractionService(ObjectMapper objectMapper, PythonRuntime python) {
        this.objectMapper = objectMapper;
        this.python = python;
    }

    @PostConstruct
    public void init() {
        pdfplumberAvailable = PythonProbe.start("pdfplumber", () -> {
            boolean imports = python.canImport("pdfplumber");
            if (python.isAvailable() && imports) {
                log.debug("PDF table extraction ready using python: {}", python.interpreter());
            } else {
                log.warn("PDF table extraction disabled - python interpreter or pdfplumber package not available");
            }
            return imports;
        }, false);
    }

    // Waits for init()'s check when it is still running: "not yet known" is not "unavailable".
    public boolean isAvailable() {
        return python.isAvailable() && pdfplumberAvailable.join();
    }

    public record PageResult(int page, String text, List<List<List<String>>> tables) {}
    public record ExtractionResult(List<PageResult> pages) {}

    // Runs `python - <pdf>` with EXTRACTOR_SCRIPT piped to stdin and parses the JSON blob on stdout
    // into ExtractionResult/PageResult. Throws if unavailable, times out, or exits non-zero. Every
    // exception message here uses pdfPath's FILE NAME only, never the full path - this message is
    // what ConvertDocumentTool returns verbatim to the chat agent (and
    // from there, potentially straight into a user-visible answer), so it must never contain the
    // server's absolute filesystem layout (e.g. /data/users/{userId}/conversations/{id}/...).
    public ExtractionResult extract(Path pdfPath) throws IOException {
        String name = pdfPath.getFileName().toString();
        if (!isAvailable()) {
            log.warn("PDF table extraction unavailable (python/pdfplumber not resolved) for {}", pdfPath);
            throw new IllegalStateException("PDF table extraction is not available - "
                    + "the pdfplumber package is not installed in the Python runtime.");
        }
        String stdout;
        try {
            stdout = python.run(EXTRACTOR_SCRIPT, List.of(pdfPath.toString()), TIMEOUT_MINUTES);
        } catch (IOException e) {
            log.warn("pdf_table_extract failed for {}: {}", pdfPath, e.getMessage());
            // The Python side's own error text (e.g. a pdfplumber exception) can itself embed the
            // full path it was given - strip both path-separator forms before this reaches the
            // caller, same reasoning as the unavailable message above.
            String safe = String.valueOf(e.getMessage())
                    .replace(pdfPath.toString(), name)
                    .replace(pdfPath.toString().replace("\\", "/"), name);
            throw new IOException("Failed to extract '" + name + "': " + safe);
        }
        return parseResult(stdout);
    }

    // Renders an extraction result as Markdown: one "## Page N" section per page, its text, then
    // each detected table as Markdown rows. Lives here rather than in the chat tool that used to
    // own it because both surfaces render the same result now - the indexing pipeline's pdfplumber
    // method (DocumentConversionService) and the on-demand chat conversion (ConvertDocumentTool /
    // WebFetchTool for a PDF at a URL).
    public static String renderAsMarkdown(ExtractionResult result) {
        StringBuilder sb = new StringBuilder();
        for (PageResult page : result.pages()) {
            sb.append("## Page ").append(page.page()).append("\n\n");
            if (page.text() != null && !page.text().isBlank()) {
                sb.append(page.text()).append("\n\n");
            }
            for (List<List<String>> table : page.tables()) {
                for (List<String> row : table) {
                    sb.append("| ").append(String.join(" | ", renderCells(row))).append(" |\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // pdfplumber represents empty table cells as null - render those as blank Markdown cells rather
    // than the literal string "null".
    private static List<String> renderCells(List<String> row) {
        return row.stream().map(cell -> cell != null ? cell : "").toList();
    }

    private ExtractionResult parseResult(String json) throws IOException {
        RawResult raw = objectMapper.readValue(json, RawResult.class);
        List<PageResult> pages = new ArrayList<>();
        if (raw.pages != null) {
            for (RawPage rp : raw.pages) {
                List<List<List<String>>> tables = new ArrayList<>();
                if (rp.tables != null) {
                    for (RawTable rt : rp.tables) {
                        tables.add(rt.rows != null ? rt.rows : List.of());
                    }
                }
                pages.add(new PageResult(rp.page, rp.text != null ? rp.text : "", tables));
            }
        }
        return new ExtractionResult(pages);
    }

    // Jackson binding shapes for {"pages": [{"page": N, "text": "...", "tables": [{"rows": [[...]]}]}]}
    private static class RawResult {
        public List<RawPage> pages;
    }

    private static class RawPage {
        public int page;
        public String text;
        public List<RawTable> tables;
    }

    private static class RawTable {
        public List<List<String>> rows;
    }

}
