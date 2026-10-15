package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

// OCR configuration: how a scanned document (one whose pages are images, with no extractable text
// layer) gets turned into text. Which documents actually go through OCR is NOT decided here - that
// is DocumentConversionSettings' job, per surface.
//
// Two engines, because they fail in opposite directions. A VISION model reads layout, tables and
// handwriting far better and needs nothing installed, but costs tokens per page and needs a
// multimodal model to be configured. TESSERACT is free, fast and fully offline, but needs the
// tesseract binary plus language data present in the deployment, and only ever returns flat text.
// `provider`/`model` apply to the vision engine only; `languages` to tesseract only.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OcrSettings {

    public static final String PREFIX = "ocr";

    @JsonProperty("engine")
    private Engine engine = Engine.VISION;
    @JsonProperty("provider")
    // EMPTY, not a provider name - a role holds a provider INSTANCE id. See
    // GraphSettings.provider for what this default cost and how long it went unnoticed.
    private String provider = "";
    @JsonProperty("model")
    private String model = "";
    // Tesseract language codes, "+"-joined exactly as tesseract's own -l argument takes them
    // (e.g. "eng", "eng+deu"). Each one needs its traineddata file installed alongside the binary.
    @JsonProperty("languages")
    private String languages = "eng";
    // Rasterization resolution. 200 DPI is the usual floor for reliable OCR of body text; higher
    // is sharper but quadratically bigger, which for the vision engine also means more tokens.
    @JsonProperty("dpi")
    private int dpi = 200;
    // Hard cap on pages OCR'd per document. OCR is the most expensive conversion path by a wide
    // margin (one model call per page for the vision engine), so a 900-page scan must not be able
    // to run away with a deployment's budget unnoticed - pages past the cap are reported as
    // skipped in the output rather than silently dropped.
    @JsonProperty("max-pages")
    private int maxPages = 50;

    public enum Engine {
        VISION("vision"),
        TESSERACT("tesseract");

        private final String value;

        Engine(String value) { this.value = value; }

        @JsonValue
        public String value() { return this.value; }

        @JsonCreator
        public static Engine fromValue(String value) {
            for (Engine e : values()) {
                if (e.value.equalsIgnoreCase(value)) return e;
            }
            throw new IllegalArgumentException("Unknown OCR engine: " + value);
        }
    }

    public Engine getEngine() { return engine; }
    public void setEngine(Engine engine) { this.engine = engine; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getLanguages() { return languages; }
    public void setLanguages(String languages) { this.languages = languages; }

    public int getDpi() { return dpi; }
    public void setDpi(int dpi) { this.dpi = dpi; }

    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
}
