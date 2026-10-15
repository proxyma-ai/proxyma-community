package ai.proxyma.core.ingest.service;

import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.settings.dto.OcrSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.PlatformSpendService;
import ai.proxyma.core.settings.usage.UserBudgetExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vision OCR that could not read a page (ingest-09): a budget refusal is not a page, and a document
 * no page of which was read is not a document.
 */
class VisionOcrFailureTest {

    private LlmGateway gateway;
    private OcrService ocr;
    private Path image;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        gateway = mock(LlmGateway.class);
        when(gateway.isAvailable(LlmRole.OCR)).thenReturn(true);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(OcrSettings.PREFIX, OcrSettings.class)).thenReturn(new OcrSettings());
        ocr = new OcrService(mock(PythonRuntime.class), gateway, settings, new ObjectMapper(),
                mock(PlatformSpendService.class));
        // A single image needs no rasterizer, but availability asks for one.
        org.springframework.test.util.ReflectionTestUtils.setField(ocr, "rasterizerAvailable",
                java.util.concurrent.CompletableFuture.completedFuture(true));
        image = dir.resolve("receipt.png");
        Files.write(image, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    }

    @Test
    @DisplayName("a page no model read, and none read at all, fails the conversion - it used to complete as '[OCR failed]'")
    void everyPageFailing() {
        when(gateway.chat(any(), anyList())).thenThrow(new IllegalStateException("model unreachable"));

        assertThatThrownBy(() -> ocr.ocr(image))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("every page");
    }

    @Test
    @DisplayName("a budget refusal propagates, so the document fails and is tried again once the budget allows")
    void aBudgetRefusalPropagates() {
        when(gateway.chat(any(), anyList()))
                .thenThrow(new UserBudgetExceededException("month", Instant.now(), "over budget"));

        assertThatThrownBy(() -> ocr.ocr(image)).isInstanceOf(UserBudgetExceededException.class);
    }
}
