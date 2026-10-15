package ai.proxyma.core.ingest.service;

import ai.proxyma.core.settings.dto.DocumentConversionSettings;
import ai.proxyma.core.settings.dto.DocumentConversionSettings.Method;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which converter a file goes to, and when AUTO escalates to OCR (ingest-07, -09, -30, -31).
 */
class ConversionRoutingTest {

    private final DocumentConversionSettings settings = new DocumentConversionSettings();
    private MarkitdownService markitdown;
    private PdfTableExtractionService pdfplumber;
    private OcrService ocr;
    private DocumentConversionService conversion;

    @BeforeEach
    void setUp() {
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getSettings(DocumentConversionSettings.PREFIX, DocumentConversionSettings.class))
                .thenReturn(settings);
        markitdown = mock(MarkitdownService.class);
        pdfplumber = mock(PdfTableExtractionService.class);
        ocr = mock(OcrService.class);
        when(markitdown.requiresConversion(anyString())).thenReturn(true);
        when(markitdown.isAvailable()).thenReturn(true);
        when(ocr.isAvailable()).thenReturn(true);
        conversion = new DocumentConversionService(settingsService, markitdown, pdfplumber, ocr);
    }

    // What markitdown makes of a 30-page copier scan whose only text is its footers: pdfminer's
    // text, a form feed after every page, and no "## Page" headings.
    private static String scannedFooters() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 30; i++) text.append("Page ").append(i).append(" of 30\n\f");
        return text.toString();
    }

    @Test
    @DisplayName("ingest-07: a scan whose text layer is its page footers is OCRed on the indexing surface")
    void footersAreNotAText() throws Exception {
        Path scan = Path.of("scan.pdf");
        when(markitdown.convert(scan)).thenReturn(scannedFooters());
        when(ocr.ocr(scan)).thenReturn("## Page 1\n\nThe contract runs for two years.");

        DocumentConversionService.Converted converted = conversion.convert(scan, DocumentConversionService.Surface.INDEXING);

        assertThat(converted.method()).isEqualTo("markitdown+ocr");
        assertThat(DocumentConversionService.pageCount(scannedFooters())).isEqualTo(30);
    }

    @Test
    @DisplayName("a real text layer is not OCRed")
    void textIsKept() throws Exception {
        Path doc = Path.of("report.pdf");
        when(markitdown.convert(doc)).thenReturn("A real paragraph of a real report, long enough to be text.\f");

        assertThat(conversion.convert(doc, DocumentConversionService.Surface.INDEXING).method()).isEqualTo("markitdown");
        verify(ocr, never()).ocr(any());
    }

    @Test
    @DisplayName("ingest-09: OCR refused for budget fails the conversion instead of keeping the footers")
    void aBudgetRefusalIsNotKeptAsText() throws Exception {
        Path scan = Path.of("scan.pdf");
        when(markitdown.convert(scan)).thenReturn(scannedFooters());
        when(ocr.ocr(scan)).thenThrow(new GlobalQuotaExceededException("reading text from a scanned page", 10, 10));

        assertThatThrownBy(() -> conversion.convert(scan, DocumentConversionService.Surface.INDEXING))
                .isInstanceOf(GlobalQuotaExceededException.class);
    }

    @Test
    @DisplayName("ingest-31: with the OCR method, a .docx still goes to markitdown and a PDF to OCR")
    void ocrMethodReadsOnlyPages() throws Exception {
        settings.setIndexingMethod(Method.OCR);
        Path docx = Path.of("minutes.docx");
        when(markitdown.convert(docx)).thenReturn("# Minutes");

        assertThat(conversion.convert(docx, DocumentConversionService.Surface.INDEXING).method()).isEqualTo("markitdown");
        verify(ocr, never()).ocr(docx);
        assertThat(conversion.canConvert(docx, DocumentConversionService.Surface.INDEXING)).isTrue();

        Path pdf = Path.of("scan.pdf");
        when(ocr.ocr(pdf)).thenReturn("## Page 1\n\ntext");
        assertThat(conversion.convert(pdf, DocumentConversionService.Surface.INDEXING).method()).isEqualTo("ocr");
    }

    @Test
    @DisplayName("ingest-30: bytes no converter claims are read as text only when they are text")
    void binaryIsRefused() {
        assertThat(DefaultDocumentFetcher.asText("plain text".getBytes(StandardCharsets.UTF_8), "text/plain"))
                .isEqualTo("plain text");
        byte[] utf8Bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        assertThat(DefaultDocumentFetcher.asText(utf8Bom, null)).isEqualTo("hi");
        byte[] utf16 = {(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0};
        assertThat(DefaultDocumentFetcher.asText(utf16, null)).isEqualTo("hi");
        // An OpenDocument file is a zip: its header carries zero bytes.
        byte[] zip = {'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0};
        assertThatThrownBy(() -> DefaultDocumentFetcher.asText(zip, "application/vnd.oasis.opendocument.text"))
                .hasMessageContaining("not text");
    }
}
