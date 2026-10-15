package ai.proxyma.core.ingest.service;

import ai.proxyma.core.webcontent.HtmlContentExtractor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
class DefaultDocumentFetcher implements DocumentFetcher {

    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentFetcher.class);

    private final DocumentConversionService conversionService;
    private final HtmlContentExtractor htmlContentExtractor;

    DefaultDocumentFetcher(DocumentConversionService conversionService, HtmlContentExtractor htmlContentExtractor) {
        this.conversionService = conversionService;
        this.htmlContentExtractor = htmlContentExtractor;
    }

    @Override
    public String convertToMarkdown(InputStream rawBytes, String contentType) {
        String result;
        if (contentType != null && contentType.contains("html")) {
            // Reuses the same jsoup + flexmark + readability4j pipeline the web-crawl connector
            // already converts crawled pages through (HtmlContentExtractor, proxyma-core-webcontent)
            // rather than routing through markitdown's own HTML converter - main-content extraction
            // (Readability) strips nav/header/footer chrome that markitdown's converter would keep.
            result = convertHtml(rawBytes);
        } else if (contentType != null && isConverted(contentType)) {
            result = convertViaTempFile(rawBytes, contentType);
        } else {
            byte[] bytes;
            try (rawBytes) {
                bytes = rawBytes.readAllBytes();
            } catch (Exception e) {
                // Thrown, not "": an empty result is reported as a file that converted to no text -
                // "it may be a scanned image" - when the file could not be read at all.
                throw new RuntimeException("Could not read the file: " + e.getMessage(), e);
            }
            result = asText(bytes, contentType);
        }
        return sanitize(result);
    }

    /** How far into a file to look for the zero bytes that say it is not text. */
    private static final int BINARY_SNIFF_BYTES = 8192;

    /**
     * Bytes no converter claims, read as text - or refused when they are not text.
     *
     * <p>A NUL byte near the start is the mark of a binary format: a zip (OpenDocument, an archive),
     * an executable, an image under a name no converter knows. Decoded as UTF-8 with its NULs
     * stripped - which removed the one sign that it was binary - it was indexed as mojibake and
     * COMPLETED. UTF-16 text has NULs too, and says so with its byte order mark.
     */
    static String asText(byte[] bytes, String contentType) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        int sniff = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < sniff; i++) {
            if (bytes[i] == 0) {
                throw new RuntimeException("This file is not text and no converter reads its format"
                        + (contentType != null && !contentType.isBlank() ? " (" + contentType + ")" : "")
                        + ". Save it as PDF, an Office format or plain text to index it.");
            }
        }
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    private String convertHtml(InputStream rawBytes) {
        try (rawBytes) {
            String html = new String(rawBytes.readAllBytes(), StandardCharsets.UTF_8);
            // No real URL for an uploaded file - baseUri is left blank. Readability/link
            // absolutization degrade gracefully to an empty href/src rather than throwing when the
            // base is blank (HtmlContentExtractor.extractArticle() already catches and falls back
            // to the plain main-content-root heuristic on any Readability failure).
            Document doc = Jsoup.parse(html, "");
            return htmlContentExtractor.extract(doc, null).markdown();
        } catch (Exception e) {
            log.error("Failed to convert HTML content: {}", e.getMessage());
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    // Some source documents (observed via Markitdown output) contain an embedded NUL
    // character (code point zero), which downstream storage (Postgres text columns,
    // Lucene, ChromaDB) rejects outright. The octal escape below is an explicit, readable
    // NUL literal - this used to be a raw NUL byte typed directly into the string, which
    // renders as an ordinary-looking space and is easy to mistake for one.
    private static String sanitize(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("\0", "");
    }

    // Package-private: ChunkingService.ContentType.of reads it too - what a converter turned into
    // Markdown is chunked as Markdown, and what was read verbatim as text is not.
    static boolean isConverted(String contentType) {
        // Images are here so that a connector delivering one (no built-in connector collects image
        // extensions today, but content types arrive from remote sources too) reaches a converter
        // that can OCR it, instead of the plain-text branch below decoding image bytes as UTF-8 -
        // which produces silent garbage rather than an error.
        return contentType.startsWith("image/")
                || contentType.contains("pdf")
                || contentType.contains("msword")
                || contentType.contains("officedocument")
                || contentType.contains("ms-powerpoint")
                || contentType.contains("ms-excel")
                || contentType.contains("ms-outlook")
                || contentType.contains("epub");
    }

    // Every binary format goes through DocumentConversionService, which picks the converter from the
    // INDEXING surface's configured method (markitdown, pdfplumber, OCR, or extract-then-OCR) - this
    // is the sync pipeline's only conversion entry point, so it is where the setting has to be read.
    // The temp file exists because every converter behind it drives a CLI or Python process that
    // needs a real path, not an in-memory stream.
    private String convertViaTempFile(InputStream rawBytes, String contentType) {
        String ext = extensionForContentType(contentType);
        try {
            Path temp = Files.createTempFile("proxyma-convert-", ext);
            try {
                Files.copy(rawBytes, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                DocumentConversionService.Converted converted =
                        conversionService.convert(temp, DocumentConversionService.Surface.INDEXING);
                log.debug("Converted {} content via {} ({} chars)",
                        contentType, converted.method(), converted.content().length());
                return converted.content();
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            // Previously swallowed to "" here, which made SyncService treat every conversion
            // failure (wrong markitdown extension, corrupt file, unsupported legacy format, ...)
            // as an indistinguishable generic "empty after conversion" - propagate the real
            // message instead so it reaches TrackedDocument.failureMessage and the UI.
            log.error("Failed to convert binary content ({}): {}", contentType, e.getMessage());
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    // The extension picked here is what markitdown dispatches its converter selection on (see
    // MarkitdownService.convert) - it must match the real underlying binary format, not just
    // "OOXML vs not". Legacy binary Office MIME types (msword/ms-powerpoint/ms-excel) and their
    // OOXML successors (wordprocessingml/presentationml/spreadsheetml) are deliberately mapped to
    // different extensions; giving a legacy-binary file an OOXML extension makes markitdown try to
    // open it as a zip container and fail with a confusing error (or, for .xls, miss its real
    // xlrd-based converter entirely even though markitdown does support .xls).
    private static String extensionForContentType(String contentType) {
        // An image's extension has to survive: OcrService decides "this is a page picture, OCR it"
        // from the file name, and a .bin would send it down the document path instead.
        if (contentType.startsWith("image/")) {
            String subtype = contentType.substring("image/".length()).trim().toLowerCase();
            int semi = subtype.indexOf(';');
            if (semi >= 0) subtype = subtype.substring(0, semi).trim();
            return OcrService.IMAGE_EXTENSIONS.contains(subtype) ? "." + subtype : ".png";
        }
        if (contentType.contains("pdf")) return ".pdf";
        if (contentType.contains("wordprocessingml")) return ".docx";
        if (contentType.contains("msword")) return ".doc";
        if (contentType.contains("presentationml")) return ".pptx";
        if (contentType.contains("ms-powerpoint")) return ".ppt";
        if (contentType.contains("spreadsheetml")) return ".xlsx";
        if (contentType.contains("ms-excel")) return ".xls";
        if (contentType.contains("ms-outlook")) return ".msg";
        if (contentType.contains("epub")) return ".epub";
        return ".bin";
    }
}
