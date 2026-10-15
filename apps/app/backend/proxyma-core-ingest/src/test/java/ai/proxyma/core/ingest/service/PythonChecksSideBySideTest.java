package ai.proxyma.core.ingest.service;

import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.dto.OcrSettings;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The startup Python checks run side by side, and nothing reads one before it has answered.
 *
 * <p>markitdown, pdfplumber, the OCR rasterizer and Tesseract were each checked in its service's
 * {@code @PostConstruct}, one after another - about 3.5 seconds of every start, each spawning an
 * interpreter. They now start together and a start waits for none of them; what must not come with
 * that is an answer read off a default while the check is still running, which would make an
 * indexing pass at startup skip every PDF as unconvertible.
 */
class PythonChecksSideBySideTest {

    // markitdown, pdfplumber, pypdfium2 and pytesseract - one import each.
    private static final int IMPORTS = 4;

    private final PythonRuntime python = mock(PythonRuntime.class);
    private final SettingsService settings = mock(SettingsService.class);

    private MarkitdownService markitdown;
    private PdfTableExtractionService pdfTables;
    private OcrService ocr;

    @BeforeEach
    void setUp() throws Exception {
        when(python.isAvailable()).thenReturn(true);
        when(python.interpreter()).thenReturn("python");
        when(python.run(anyString(), anyList(), anyInt())).thenReturn("5.4.0\n");
        OcrSettings tesseract = new OcrSettings();
        tesseract.setEngine(OcrSettings.Engine.TESSERACT);
        when(settings.getSettings(eq(OcrSettings.PREFIX), eq(OcrSettings.class))).thenReturn(tesseract);

        markitdown = new MarkitdownService(null, python);
        pdfTables = new PdfTableExtractionService(new ObjectMapper(), python);
        ocr = new OcrService(python, mock(LlmGateway.class), settings, new ObjectMapper(), null);
        ReflectionTestUtils.setField(ocr, "tesseractCmd", "");
    }

    private void initAll() {
        markitdown.init();
        pdfTables.init();
        ocr.init();
    }

    @Test
    @DisplayName("the four imports are in flight at the same time")
    void importsRunTogether() {
        // Each import holds until all four have begun. One after another, the first waits out its
        // three seconds alone and answers "cannot import", and so does every one after it.
        CountDownLatch begun = new CountDownLatch(IMPORTS);
        when(python.canImport(anyString())).thenAnswer(inv -> {
            begun.countDown();
            return begun.await(3, TimeUnit.SECONDS);
        });

        initAll();

        assertThat(markitdown.isAvailable()).isTrue();
        assertThat(pdfTables.isAvailable()).isTrue();
        assertThat(ocr.isAvailable()).isTrue();
        assertThat(ocr.unavailableReason()).isEmpty();
    }

    @Test
    @DisplayName("a start does not wait for the checks, and a reader waits for its answer")
    void readersWaitForTheAnswer() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(python.canImport(anyString())).thenAnswer(inv -> release.await(10, TimeUnit.SECONDS));

        // Run in the calling thread these never return before `release`, which nothing opens yet.
        assertTimeoutPreemptively(Duration.ofSeconds(2), this::initAll);

        CompletableFuture<Boolean> pdfs = CompletableFuture.supplyAsync(pdfTables::isAvailable);
        CompletableFuture<Boolean> office = CompletableFuture.supplyAsync(markitdown::isAvailable);
        CompletableFuture<String> scans = CompletableFuture.supplyAsync(ocr::unavailableReason);
        Thread.sleep(300);
        // Still checking is not "unavailable": nobody has been told no.
        assertThat(pdfs).isNotDone();
        assertThat(office).isNotDone();
        assertThat(scans).isNotDone();

        release.countDown();
        assertThat(pdfs.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(office.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(scans.get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    @DisplayName("a check that cannot import still answers no, as before")
    void aFailedImportIsStillNo() {
        when(python.canImport(anyString())).thenReturn(false);

        initAll();

        assertThat(pdfTables.isAvailable()).isFalse();
        assertThat(ocr.isAvailable()).isFalse();
        assertThat(ocr.unavailableReason()).isEqualTo("the pytesseract package is not installed");
    }

    @Test
    @DisplayName("a check that throws answers no rather than failing its readers")
    void aCheckThatThrowsIsNo() {
        when(python.canImport(any())).thenThrow(new IllegalStateException("boom"));

        initAll();

        assertThat(pdfTables.isAvailable()).isFalse();
        assertThat(ocr.isAvailable()).isFalse();
    }
}
