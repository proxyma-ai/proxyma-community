package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

// Which converter turns a document into text, chosen per SURFACE rather than once globally: a
// document being indexed and a file a user just dropped into chat have genuinely different
// trade-offs. Indexing runs unattended over potentially thousands of files, where a slow or
// per-page-priced converter is a budget decision; a single chat attachment is one document a user
// is waiting on, where fidelity usually matters more than cost.
//
// AUTO is the default for both and is the only method that combines converters: it extracts text
// normally first and escalates to OCR only for the documents that come back empty - which is
// exactly the scanned-document case, and only those pay OCR's price. The three explicit methods
// exist for when an operator knows their corpus (all-scanned, or all-digital) and wants one
// predictable path with no fallback.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentConversionSettings {

    public static final String PREFIX = "doc-conversion";

    @JsonProperty("indexing-method")
    private Method indexingMethod = Method.AUTO;
    @JsonProperty("attachment-method")
    private Method attachmentMethod = Method.AUTO;

    public enum Method {
        // Text extraction first, OCR only for pages that come back with essentially no text.
        AUTO("auto"),
        // The bundled markitdown CLI: broadest format coverage (Office, PDF, e-mail, epub).
        MARKITDOWN("markitdown"),
        // pdfplumber: table-aware PDF extraction, highest column fidelity. Non-PDFs still go
        // through markitdown - pdfplumber has nothing to say about a .docx.
        PDFPLUMBER("pdfplumber"),
        // Always OCR, even when a text layer exists. For corpora of scans whose embedded text
        // layer is known to be wrong (e.g. a bad prior OCR pass baked into the file).
        OCR("ocr");

        private final String value;

        Method(String value) { this.value = value; }

        @JsonValue
        public String value() { return this.value; }

        @JsonCreator
        public static Method fromValue(String value) {
            for (Method m : values()) {
                if (m.value.equalsIgnoreCase(value)) return m;
            }
            throw new IllegalArgumentException("Unknown document conversion method: " + value);
        }
    }

    public Method getIndexingMethod() { return indexingMethod; }
    public void setIndexingMethod(Method indexingMethod) { this.indexingMethod = indexingMethod; }

    public Method getAttachmentMethod() { return attachmentMethod; }
    public void setAttachmentMethod(Method attachmentMethod) { this.attachmentMethod = attachmentMethod; }
}
