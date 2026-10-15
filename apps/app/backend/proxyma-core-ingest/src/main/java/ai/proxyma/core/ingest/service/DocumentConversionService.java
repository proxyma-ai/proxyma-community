package ai.proxyma.core.ingest.service;

import ai.proxyma.core.settings.dto.DocumentConversionSettings;
import ai.proxyma.core.settings.dto.DocumentConversionSettings.Method;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaExceededException;
import ai.proxyma.core.settings.usage.UserBudgetExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// The single decision point for "how do we turn this file into text": markitdown, pdfplumber, OCR,
// or text-extraction-then-OCR-if-empty. Both surfaces route through here - the indexing pipeline
// (DefaultDocumentFetcher, the sync path's only conversion entry point) and on-demand chat
// conversion (ConvertDocumentTool, plus WebFetchTool for a PDF at a URL) - each reading its own
// configured method from DocumentConversionSettings, so an operator can index cheaply while still
// giving chat attachments the expensive treatment, or vice versa.
//
// Before this existed the two surfaces each hardcoded a different converter (indexing always
// markitdown, chat always pdfplumber for PDFs), which is also why AUTO's defaults look the way they
// do: it keeps each surface's previous behavior as the first attempt and only adds an escalation
// path that did not exist at all.
@Service
public class DocumentConversionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentConversionService.class);

    // Below this many non-whitespace characters per page, a PDF is treated as having no real text
    // layer - i.e. a scan - and AUTO escalates to OCR. Page headers/footers and a stray page number
    // survive rasterization as text in some scans, so the threshold is not zero; a genuine text page
    // carries hundreds of characters, well clear of this.
    private static final int MIN_CHARS_PER_PAGE = 32;

    // Which surface is asking. The method is configured per surface (see DocumentConversionSettings)
    // because unattended bulk indexing and a single file a user is waiting on price differently.
    public enum Surface { INDEXING, ATTACHMENT }

    // `method` is what actually ran, not what was configured - for AUTO those differ, and the
    // caller (a tool result, a log line) is more useful when it can say "pdfplumber, then OCR".
    public record Converted(String content, String method) {}

    private final SettingsService settingsService;
    private final MarkitdownService markitdownService;
    private final PdfTableExtractionService pdfTableExtractionService;
    private final OcrService ocrService;

    public DocumentConversionService(SettingsService settingsService,
                                     MarkitdownService markitdownService,
                                     PdfTableExtractionService pdfTableExtractionService,
                                     OcrService ocrService) {
        this.settingsService = settingsService;
        this.markitdownService = markitdownService;
        this.pdfTableExtractionService = pdfTableExtractionService;
        this.ocrService = ocrService;
    }

    public Method methodFor(Surface surface) {
        DocumentConversionSettings s = settingsService.getSettings(
                DocumentConversionSettings.PREFIX, DocumentConversionSettings.class);
        return surface == Surface.INDEXING ? s.getIndexingMethod() : s.getAttachmentMethod();
    }

    // True when this file can be converted at all under the surface's configured method - for a
    // caller that wants to skip a file up front rather than fail it mid-run (the guardrail's policy
    // library and the key-account revenue sheets; the sync pipeline does not ask).
    public boolean canConvert(Path filePath, Surface surface) {
        String name = filePath.getFileName().toString();
        Method method = methodFor(surface);
        if (OcrService.isImage(name)) {
            // Images have no text to extract by any other route, so they are convertible exactly
            // when OCR is available.
            return ocrService.isAvailable();
        }
        if (method == Method.OCR && isPdf(name)) {
            return ocrService.isAvailable();
        }
        if (markitdownService.requiresConversion(name)) {
            return markitdownService.isAvailable() || (isPdf(name) && pdfTableExtractionService.isAvailable());
        }
        return true;
    }

    public Converted convert(Path filePath, Surface surface) throws IOException {
        String name = filePath.getFileName().toString();
        Method method = methodFor(surface);

        // An image is a page picture: OCR is the only thing that can read it, whatever the
        // configured method says about documents.
        if (OcrService.isImage(name)) {
            return new Converted(ocrService.ocr(filePath), "ocr");
        }

        return switch (method) {
            // OCR reads page pictures: a PDF (rasterized first) or an image. A .docx has no pages to
            // rasterize, and sending it there failed every Office file with a Python image error -
            // everything else still needs markitdown, as PDFPLUMBER already routes it.
            case OCR -> isPdf(name)
                    ? new Converted(ocrService.ocr(filePath), "ocr")
                    : new Converted(extractWithMarkitdown(filePath), "markitdown");
            case MARKITDOWN -> new Converted(extractWithMarkitdown(filePath), "markitdown");
            case PDFPLUMBER -> isPdf(name)
                    ? new Converted(extractWithPdfplumber(filePath), "pdfplumber")
                    // pdfplumber has nothing to say about a .docx - the configured method names the
                    // PDF strategy, and everything else still needs markitdown.
                    : new Converted(extractWithMarkitdown(filePath), "markitdown");
            case AUTO -> convertAuto(filePath, surface);
        };
    }

    // Text extraction first, OCR only if what came back is too thin to be a real text layer. That
    // check is the whole point of AUTO: a scanned PDF extracts as a handful of stray characters, and
    // without the escalation it would index as an empty document while looking like a success.
    private Converted convertAuto(Path filePath, Surface surface) throws IOException {
        String name = filePath.getFileName().toString();
        if (!isPdf(name)) {
            // Only PDFs can be scans among the remaining formats: a .docx/.xlsx either has text or
            // is genuinely empty, and OCR cannot help with either.
            return new Converted(extractWithMarkitdown(filePath), "markitdown");
        }

        String text = null;
        String usedMethod = null;
        // Historic per-surface preference preserved: chat attachments already used pdfplumber for
        // its column fidelity, indexing already used markitdown. AUTO does not quietly change which
        // extractor a deployment has been getting.
        boolean preferPdfplumber = surface == Surface.ATTACHMENT;
        if (preferPdfplumber && pdfTableExtractionService.isAvailable()) {
            text = tryExtract(filePath, this::extractWithPdfplumber, "pdfplumber");
            usedMethod = text != null ? "pdfplumber" : null;
        }
        if (text == null && markitdownService.isAvailable()) {
            text = tryExtract(filePath, this::extractWithMarkitdown, "markitdown");
            usedMethod = text != null ? "markitdown" : null;
        }
        if (text == null && !preferPdfplumber && pdfTableExtractionService.isAvailable()) {
            text = tryExtract(filePath, this::extractWithPdfplumber, "pdfplumber");
            usedMethod = text != null ? "pdfplumber" : null;
        }

        boolean textPoor = text == null || isTextPoor(text, filePath);
        if (!textPoor) {
            return new Converted(text, usedMethod);
        }
        if (!ocrService.isAvailable()) {
            if (text != null) {
                // Deliberately still returns the thin text rather than failing: a document with a
                // near-empty text layer and no OCR configured is not an error, it is a document
                // whose content this deployment cannot read. The log line is where an operator
                // finds out OCR would have helped.
                log.info("'{}' has little extractable text and OCR is unavailable ({}) - indexing what was extracted",
                        filePath.getFileName(), ocrService.unavailableReason());
                return new Converted(text, usedMethod);
            }
            throw new IOException("Could not extract text from '" + filePath.getFileName()
                    + "' and OCR is not available - " + ocrService.unavailableReason() + ".");
        }
        log.info("'{}' looks scanned ({} chars extracted) - escalating to OCR",
                filePath.getFileName(), text != null ? text.strip().length() : 0);
        try {
            return new Converted(ocrService.ocr(filePath), usedMethod != null ? usedMethod + "+ocr" : "ocr");
        } catch (GlobalQuotaExceededException | UserBudgetExceededException budget) {
            // Not a failed OCR: OCR that was not allowed to run. Keeping the thin text would mark a
            // scan COMPLETED as a list of page footers, never to be read again once the budget resets.
            throw budget;
        } catch (Exception e) {
            if (text != null) {
                // Same reasoning as the unavailable branch: a failed OCR attempt should not throw
                // away text extraction's (thin, but real) result.
                log.warn("OCR fallback failed for '{}': {} - keeping the extracted text",
                        filePath.getFileName(), e.getMessage());
                return new Converted(text, usedMethod);
            }
            throw new IOException("Could not extract text from '" + filePath.getFileName()
                    + "' and OCR failed: " + e.getMessage());
        }
    }

    private interface Extractor {
        String apply(Path path) throws IOException;
    }

    // Returns null instead of throwing, so AUTO can move on to the next extractor. A hard failure
    // only happens once every route has been tried (see convertAuto).
    private String tryExtract(Path filePath, Extractor extractor, String label) {
        try {
            return extractor.apply(filePath);
        } catch (Exception e) {
            log.debug("{} extraction failed for {}: {}", label, filePath.getFileName(), e.getMessage());
            return null;
        }
    }

    // "Too thin to be a text layer", scaled by page count so a 40-page scan with one text-bearing
    // cover page still counts as scanned. See pageCount for where the count comes from.
    static boolean isTextPoor(String text, Path filePath) {
        String stripped = text.replaceAll("(?m)^##\\s+Page\\s+\\d+\\s*$", "").strip();
        int chars = stripped.replaceAll("\\s", "").length();
        int pages = pageCount(text);
        boolean poor = chars < MIN_CHARS_PER_PAGE * pages;
        if (poor) {
            log.debug("'{}' judged text-poor: {} chars across {} page(s)", filePath.getFileName(), chars, pages);
        }
        return poor;
    }

    /**
     * How many pages extracted text came from, whichever extractor produced it: the "## Page N"
     * sections pdfplumber's renderer (and OCR) write, or the form feed pdfminer ends every page
     * with - which is what markitdown returns for a PDF with no tables. One when neither says.
     *
     * <p>It counted the headings alone, and markitdown writes none, so on the INDEXING surface -
     * where AUTO tries markitdown first - every PDF was one page and the bar for "text-poor" was 32
     * characters for the whole document: a 30-page copier scan whose text layer is its "Page N of
     * 30" footers passed as text-rich, was indexed as a list of footers and never OCRed.
     */
    static int pageCount(String text) {
        int formFeeds = (int) text.chars().filter(c -> c == '\f').count();
        return Math.max(1, Math.max(countPageMarkers(text), formFeeds));
    }

    private static int countPageMarkers(String text) {
        int count = 0;
        int idx = text.indexOf("## Page ");
        while (idx >= 0) {
            count++;
            idx = text.indexOf("## Page ", idx + 1);
        }
        return count;
    }

    private String extractWithMarkitdown(Path filePath) throws IOException {
        if (markitdownService.requiresConversion(filePath.getFileName().toString())) {
            return markitdownService.convert(filePath);
        }
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    private String extractWithPdfplumber(Path filePath) throws IOException {
        return PdfTableExtractionService.renderAsMarkdown(pdfTableExtractionService.extract(filePath));
    }

    private static boolean isPdf(String fileName) {
        return fileName.toLowerCase().endsWith(".pdf");
    }
}
