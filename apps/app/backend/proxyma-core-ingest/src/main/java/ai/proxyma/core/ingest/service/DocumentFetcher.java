package ai.proxyma.core.ingest.service;

import java.io.InputStream;

public interface DocumentFetcher {

    /**
     * Converts raw binary content into Markdown.
     * Caller provides the stream; implementation closes it after reading.
     *
     * @param rawBytes   binary content stream
     * @param contentType MIME type or file extension (e.g. "application/pdf", ".docx")
     * @return Markdown string
     */
    String convertToMarkdown(InputStream rawBytes, String contentType);
}
