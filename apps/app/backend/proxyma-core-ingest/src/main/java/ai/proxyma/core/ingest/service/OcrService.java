package ai.proxyma.core.ingest.service;

import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.provider.gateway.LlmCaller;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.common.usage.TokenUsage;

import ai.proxyma.core.settings.usage.GlobalQuotaExceededException;
import ai.proxyma.core.settings.usage.UserBudgetExceededException;
import ai.proxyma.core.settings.usage.PlatformService;
import ai.proxyma.core.settings.usage.PlatformSpendService;
import ai.proxyma.core.settings.dto.OcrSettings;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;

import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

// Reads text off documents that have no text to read: scans, photographed pages, image-only PDFs
// exported by a copier. Everything else in this package extracts an existing text layer; this is
// the path for documents where that layer does not exist at all.
//
// Two engines, chosen by OcrSettings.engine (see that class for the trade-off):
//   VISION    - each page is rasterized, then transcribed by a multimodal model. Handles layout,
//               tables and handwriting; costs one model call per page.
//   TESSERACT - rasterization and recognition both happen in Python (pytesseract driving the
//               tesseract binary). Free and offline; returns flat text only.
//
// Rasterization is Python either way, via PythonRuntime. pypdfium2 is tried first and pdf2image
// second, so a deployment that has either one works - and pypdfium2 in particular arrives as a
// pdfplumber dependency, meaning a server already set up for PDF table extraction usually needs
// nothing new installed for the vision engine.
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private static final int RASTERIZE_TIMEOUT_MINUTES = 15;
    private static final int TESSERACT_TIMEOUT_MINUTES = 30;

    // Image formats fed to OCR directly, with no rasterization step - they are already a page image.
    public static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff");

    private static final String TRANSCRIBE_PROMPT = """
            Transcribe this page image to Markdown, exactly as it appears.

            Rules:
            - Output ONLY the page's own content - no preamble, no commentary, no "Here is the text".
            - Preserve the reading order, headings, lists and paragraph breaks.
            - Render tables as Markdown tables, keeping the original columns.
            - Transcribe what is actually written, including numbers and identifiers, character for
              character. Never guess at, complete, correct or summarize the content.
            - Mark anything genuinely illegible as [illegible] rather than inventing a plausible word.
            - If the page has no readable text at all, output nothing.
            """;

    // Rasterizes each page to a PNG and returns them base64-encoded, for the vision engine.
    // pypdfium2 first, pdf2image second; a clear error naming both if neither imports.
    private static final String RASTERIZE_SCRIPT = """
            import sys
            import json
            import base64
            import io

            path = sys.argv[1]
            dpi = int(sys.argv[2])
            max_pages = int(sys.argv[3])


            def with_pypdfium2():
                import pypdfium2 as pdfium
                pdf = pdfium.PdfDocument(path)
                total = len(pdf)
                pages = []
                for i in range(min(total, max_pages)):
                    bitmap = pdf[i].render(scale=dpi / 72.0)
                    buf = io.BytesIO()
                    bitmap.to_pil().save(buf, format="PNG")
                    pages.append(base64.b64encode(buf.getvalue()).decode("ascii"))
                return total, pages


            def with_pdf2image():
                from pdf2image import convert_from_path
                images = convert_from_path(path, dpi=dpi)
                total = len(images)
                pages = []
                for image in images[:max_pages]:
                    buf = io.BytesIO()
                    image.save(buf, format="PNG")
                    pages.append(base64.b64encode(buf.getvalue()).decode("ascii"))
                return total, pages


            def main():
                errors = []
                for fn in (with_pypdfium2, with_pdf2image):
                    try:
                        total, pages = fn()
                        print(json.dumps({"totalPages": total, "pages": pages}))
                        return
                    except ImportError as e:
                        errors.append(str(e))
                raise RuntimeError(
                    "no PDF rasterizer available (tried pypdfium2, pdf2image): " + "; ".join(errors))


            try:
                main()
            except Exception as e:
                print(f"rasterize failed: {e}", file=sys.stderr)
                sys.exit(1)
            """;

    // Rasterizes and recognizes in one pass for the tesseract engine, returning per-page text.
    // Accepts a PDF or a single image file, so the Java side does not branch on type here.
    private static final String TESSERACT_SCRIPT = """
            import sys
            import json

            path = sys.argv[1]
            dpi = int(sys.argv[2])
            max_pages = int(sys.argv[3])
            languages = sys.argv[4]

            import pytesseract
            from PIL import Image

            # The program itself, when it is not on PATH - the Windows installer's own copy.
            if len(sys.argv) > 5:
                pytesseract.pytesseract.tesseract_cmd = sys.argv[5]


            def page_images():
                if not path.lower().endswith(".pdf"):
                    return 1, [Image.open(path)]
                try:
                    import pypdfium2 as pdfium
                    pdf = pdfium.PdfDocument(path)
                    total = len(pdf)
                    return total, [pdf[i].render(scale=dpi / 72.0).to_pil()
                                   for i in range(min(total, max_pages))]
                except ImportError:
                    from pdf2image import convert_from_path
                    images = convert_from_path(path, dpi=dpi)
                    return len(images), images[:max_pages]


            def main():
                total, images = page_images()
                pages = []
                for i, image in enumerate(images, start=1):
                    pages.append({"page": i, "text": pytesseract.image_to_string(image, lang=languages)})
                print(json.dumps({"totalPages": total, "pages": pages}))


            try:
                main()
            except Exception as e:
                print(f"tesseract ocr failed: {e}", file=sys.stderr)
                sys.exit(1)
            """;

    // Asks the PROGRAM for its version, not merely whether the Python wrapper imports. pytesseract
    // is a thin driver that shells out to the tesseract executable; the desktop packages shipped
    // the driver without the program until 0.16.0, so the old import-only probe said "available"
    // and every scanned document then failed with TesseractNotFoundError.
    private static final String TESSERACT_PROBE = """
            import sys
            import pytesseract
            if len(sys.argv) > 1:
                pytesseract.pytesseract.tesseract_cmd = sys.argv[1]
            print(pytesseract.get_tesseract_version())
            """;

    private final PythonRuntime python;
    private final LlmGateway llmGateway;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final PlatformSpendService platformSpendService;

    // The tesseract executable, when it is not on PATH. The Windows launcher points this at the
    // copy the installer puts in the install root; Linux and the Docker images leave it empty and
    // use the system package.
    @Value("${proxyma.ocr.tesseract-cmd:}")
    private String tesseractCmd;

    // Both from init()'s checks, which run side by side and beside the other startup Python checks
    // (PythonProbe); read through rasterizerAvailable() and tesseractMissing(), which wait for them.
    // Already answered for a service nobody initialized, with the defaults the plain fields had.
    private volatile CompletableFuture<Boolean> rasterizerAvailable = CompletableFuture.completedFuture(false);
    // Blank when the tesseract engine can run; otherwise why not, phrased for unavailableReason().
    private volatile CompletableFuture<String> tesseractMissing = CompletableFuture.completedFuture("");

    public OcrService(PythonRuntime python, LlmGateway llmGateway,
                      SettingsService settingsService, ObjectMapper objectMapper,
                      PlatformSpendService platformSpendService) {
        this.python = python;
        this.llmGateway = llmGateway;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.platformSpendService = platformSpendService;
    }

    @PostConstruct
    public void init() {
        // Probed once at startup, not per call: each probe spawns an interpreter and pays its
        // import cost, which has no business happening inside a document conversion.
        CompletableFuture<Boolean> rasterizer = PythonProbe.start("OCR rasterizer",
                () -> python.canImport("pypdfium2") || python.canImport("pdf2image"), false);
        CompletableFuture<String> tesseract = PythonProbe.start("Tesseract OCR", this::probeTesseract,
                "the Tesseract program is not installed");
        rasterizerAvailable = rasterizer;
        tesseractMissing = tesseract;
        rasterizer.thenAcceptBoth(tesseract, (raster, missing) -> log.debug("OCR probes: rasterizer={} tesseract={}",
                raster, missing.isEmpty() ? "ok" : missing));
    }

    private boolean rasterizerAvailable() {
        return rasterizerAvailable.join();
    }

    private String tesseractMissing() {
        return tesseractMissing.join();
    }

    private String probeTesseract() {
        if (!python.isAvailable()) return "no Python interpreter is available";
        if (!python.canImport("pytesseract")) return "the pytesseract package is not installed";
        try {
            String version = python.run(TESSERACT_PROBE, tesseractArgs(), 1).trim();
            log.info("Tesseract OCR {} found{}", version,
                    tesseractCmd.isBlank() ? " on PATH" : " at " + tesseractCmd);
            return "";
        } catch (Exception e) {
            log.info("Tesseract OCR is not available: {}", e.getMessage());
            return "the Tesseract program is not installed";
        }
    }

    // sys.argv entries naming the executable - none when it is left to PATH.
    private List<String> tesseractArgs() {
        return tesseractCmd.isBlank() ? List.of() : List.of(tesseractCmd);
    }

    // Whether OCR can run right now, for the CONFIGURED engine. Callers use this to decide whether
    // to attempt OCR at all (the auto method's fallback, an image attachment) instead of discovering
    // mid-conversion that the engine was never set up.
    public boolean isAvailable() {
        return switch (settings().getEngine()) {
            // A single image needs no rasterizer, but a PDF does, and this answers for both - a
            // vision engine that cannot rasterize can only ever handle image files.
            case VISION -> llmGateway.isAvailable(LlmRole.OCR) && rasterizerAvailable();
            case TESSERACT -> tesseractMissing().isEmpty() && rasterizerAvailable();
        };
    }

    // Why OCR is unavailable, phrased for whoever has to fix it. Blank when it IS available.
    // Deliberately does not name the environment ("on this server", "your administrator"):
    // this module is shared by proxyma-desktop and proxyma-server, and these strings are
    // returned verbatim to the chat agent, so a desktop user would be told to go ask an
    // administrator about a server that is their own laptop.
    public String unavailableReason() {
        OcrSettings s = settings();
        if (s.getEngine() == OcrSettings.Engine.VISION) {
            if (!llmGateway.isAvailable(LlmRole.OCR)) {
                return "no OCR vision model is configured (Settings > Conversion > OCR)";
            }
            if (!rasterizerAvailable()) {
                return "no PDF rasterizer is installed (pypdfium2 or pdf2image)";
            }
            return "";
        }
        if (!tesseractMissing().isEmpty()) return tesseractMissing();
        if (!rasterizerAvailable()) return "no PDF rasterizer is installed (pypdfium2 or pdf2image)";
        return "";
    }

    public static boolean isImage(String fileName) {
        String lower = fileName.toLowerCase();
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && IMAGE_EXTENSIONS.contains(lower.substring(dot + 1));
    }

    // OCRs a PDF or image file into Markdown, one "## Page N" section per page. Throws
    // IllegalStateException when the configured engine isn't available (message from
    // unavailableReason()), IOException when the conversion itself fails.
    public String ocr(Path filePath) throws IOException {
        String name = filePath.getFileName().toString();
        if (!isAvailable()) {
            throw new IllegalStateException("OCR is not available - " + unavailableReason() + ".");
        }
        OcrSettings s = settings();
        long start = System.currentTimeMillis();
        String markdown = s.getEngine() == OcrSettings.Engine.VISION
                ? ocrWithVisionModel(filePath, s)
                : ocrWithTesseract(filePath, s);
        long elapsed = System.currentTimeMillis() - start;
        log.info("OCR ({}) produced {} chars for {} in {}ms",
                s.getEngine().value(), markdown.length(), name, elapsed);
        // Engine and file CLASS only - the name above stays in the local log.
        ai.proxyma.core.common.diagnostics.Diagnostics.time(ai.proxyma.core.common.diagnostics.DiagOp.INGEST_OCR,
                elapsed, "engine", s.getEngine().value(),
                "fileClass", ai.proxyma.core.common.diagnostics.DiagLabels.fileClass(name));
        return markdown;
    }

    private String ocrWithVisionModel(Path filePath, OcrSettings s) throws IOException {
        List<byte[]> pageImages;
        int totalPages;
        MimeType mimeType = MimeTypeUtils.IMAGE_PNG;
        if (isImage(filePath.getFileName().toString())) {
            pageImages = List.of(Files.readAllBytes(filePath));
            totalPages = 1;
            // An image is sent as-is, so it must be announced as what it actually is - several
            // providers reject a JPEG declared as image/png rather than sniffing the bytes.
            mimeType = mimeTypeOf(filePath.getFileName().toString());
        } else {
            RasterResult raster = rasterize(filePath, s);
            pageImages = raster.pages().stream().map(b64 -> Base64.getDecoder().decode(b64)).toList();
            totalPages = raster.totalPages();
        }

        StringBuilder md = new StringBuilder();
        int failed = 0;
        String lastFailure = null;
        for (int i = 0; i < pageImages.size(); i++) {
            // Pages are transcribed one call at a time, deliberately not in parallel: OCR already
            // runs inside a per-document pipeline step, and fanning N page calls at a GPU that the
            // chat model is also serving is how one scanned document stalls every live conversation.
            String text;
            try {
                text = transcribePage(pageImages.get(i), mimeType);
            } catch (GlobalQuotaExceededException | UserBudgetExceededException budget) {
                // Not an unreadable page - OCR that may not run. The document fails and is tried
                // again once the budget allows; written into the text, the refusal was indexed as
                // the page and the document completed, never to be read again.
                throw budget;
            } catch (Exception e) {
                // One unreadable page must not lose the whole document - a 40-page scan where page
                // 12 trips a provider limit is still worth 39 pages of text. The gap is marked in
                // the output so nobody mistakes it for a blank page.
                log.warn("OCR page {} of {} failed: {}", i + 1, filePath.getFileName(), e.getMessage());
                failed++;
                lastFailure = e.getMessage();
                text = "[OCR failed for this page]";
            }
            md.append("## Page ").append(i + 1).append("\n\n");
            if (!text.isBlank()) {
                md.append(text.strip()).append("\n\n");
            }
        }
        if (failed > 0 && failed == pageImages.size()) {
            // Every page failed: that is a model that could not be reached, not a document with
            // gaps. Thrown, so the conversion fails and the document is retried - it used to come
            // back as one "[OCR failed]" line per page and be marked COMPLETED.
            throw new IOException("OCR failed on every page of '" + filePath.getFileName() + "': " + lastFailure);
        }
        appendPageCapNotice(md, totalPages, pageImages.size(), s);
        return md.toString();
    }

    private String transcribePage(byte[] imageBytes, MimeType mimeType) {
        Media image = new Media(mimeType, new ByteArrayResource(imageBytes));
        UserMessage message = UserMessage.builder()
                .text(TRANSCRIBE_PROMPT)
                .media(image)
                .build();
        // Billed to whoever the conversion is for - a connector's owner during a sync, the user
        // whose chat tool converted an attachment - which the caller put in the context. Document
        // conversion sits too many frames below them to carry it as a parameter. The gateway checks
        // the budget itself, the deployment's and that person's, and refuses with an exception.
        LlmGateway.LlmResult result = llmGateway.chat(
                LlmCaller.of(LlmRole.OCR, SpendContext.current()), List.of(message));
        // The per-call ledger already holds this call, under that subject; this is the platform
        // usage breakdown's own line for OCR, which is still recorded on its own.
        recordOcrSpend(result);
        return result.text();
    }

    private String ocrWithTesseract(Path filePath, OcrSettings s) throws IOException {
        List<String> args = new ArrayList<>(List.of(filePath.toString(), String.valueOf(s.getDpi()),
                String.valueOf(Math.max(1, s.getMaxPages())), s.getLanguages()));
        args.addAll(tesseractArgs());
        String stdout = python.run(TESSERACT_SCRIPT, args, TESSERACT_TIMEOUT_MINUTES);
        TesseractResult result = read(stdout, TesseractResult.class, filePath);
        StringBuilder md = new StringBuilder();
        List<TesseractPage> pages = result.pages != null ? result.pages : List.of();
        for (TesseractPage page : pages) {
            md.append("## Page ").append(page.page).append("\n\n");
            if (page.text != null && !page.text.isBlank()) {
                md.append(page.text.strip()).append("\n\n");
            }
        }
        appendPageCapNotice(md, result.totalPages, pages.size(), s);
        return md.toString();
    }

    private RasterResult rasterize(Path filePath, OcrSettings s) throws IOException {
        String stdout = python.run(RASTERIZE_SCRIPT,
                List.of(filePath.toString(), String.valueOf(s.getDpi()),
                        String.valueOf(Math.max(1, s.getMaxPages()))),
                RASTERIZE_TIMEOUT_MINUTES);
        RasterResult result = read(stdout, RasterResult.class, filePath);
        if (result.pages() == null || result.pages().isEmpty()) {
            throw new IOException("No pages could be rasterized from '" + filePath.getFileName() + "'");
        }
        return result;
    }

    // States the truncation in the document itself rather than only in a log line: this text is
    // what gets indexed and what the agent reads, so "there is more of this document" has to be
    // visible there, not just to whoever greps the server logs.
    private void appendPageCapNotice(StringBuilder md, int totalPages, int processedPages, OcrSettings s) {
        if (totalPages > processedPages) {
            md.append("> OCR covered the first ").append(processedPages).append(" of ")
                    .append(totalPages).append(" pages (the configured maximum is ")
                    .append(s.getMaxPages()).append(").\n");
        }
    }

    private <T> T read(String json, Class<T> type, Path filePath) throws IOException {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IOException("Could not read OCR output for '" + filePath.getFileName() + "'");
        }
    }

    private static MimeType mimeTypeOf(String fileName) {
        String lower = fileName.toLowerCase();
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        return switch (ext) {
            case "jpg", "jpeg" -> MimeTypeUtils.IMAGE_JPEG;
            case "gif" -> MimeTypeUtils.IMAGE_GIF;
            case "webp" -> MimeType.valueOf("image/webp");
            case "bmp" -> MimeType.valueOf("image/bmp");
            case "tif", "tiff" -> MimeType.valueOf("image/tiff");
            default -> MimeTypeUtils.IMAGE_PNG;
        };
    }

    // Bookkeeping must never fail an ingest: one unreadable page already does not lose a
    // document, and an unrecorded cost certainly should not.
    private void recordOcrSpend(LlmGateway.LlmResult result) {
        try {
            TokenUsage usage = result.usage();
            if (usage == TokenUsage.EMPTY) return;
            platformSpendService.record(PlatformService.OCR, usage, result.provider(), result.model());
        } catch (Exception e) {
            log.debug("[PlatformSpend] Could not record OCR spend: {}", e.getMessage());
        }
    }

    private OcrSettings settings() {
        return settingsService.getSettings(OcrSettings.PREFIX, OcrSettings.class);
    }

    // Jackson binding shapes for the two scripts' JSON output.
    private record RasterResult(int totalPages, List<String> pages) {}

    private static class TesseractResult {
        public int totalPages;
        public List<TesseractPage> pages;
    }

    private static class TesseractPage {
        public int page;
        public String text;
    }
}
