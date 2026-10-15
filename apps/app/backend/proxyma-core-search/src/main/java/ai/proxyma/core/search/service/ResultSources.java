package ai.proxyma.core.search.service;

import org.springframework.ai.document.Document;

import java.util.regex.Pattern;

/**
 * Where a search result came from, as a caller outside the server may be told it.
 *
 * <p>A chunk's {@code url} metadata when it has one - the browse link of a Jira issue, a Confluence
 * page, a crawled page - because its {@code source} is then an internal id (an issue key, a page
 * id) worth nothing to a reader. Otherwise the {@code source}, reduced to its file name when it is
 * a filesystem path, so no server directory ever leaves the server.
 *
 * <p>Both path conventions are recognised on every server: a Windows path indexed on a desktop and
 * read on Linux is not absolute to {@code Path.of} there, and passed through whole.
 */
public final class ResultSources {

    private ResultSources() {
    }

    // /home/..., C:\... or C:/..., \\server\share\...
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("^(/|[A-Za-z]:[\\\\/]|\\\\\\\\).*");

    /** The url when the chunk has one, else its source as {@link #sanitize} leaves it; null if neither. */
    public static String of(Document doc) {
        Object url = doc.getMetadata().get("url");
        if (url != null && !url.toString().isBlank()) return url.toString();
        Object source = doc.getMetadata().get("source");
        return source == null ? null : sanitize(source.toString());
    }

    /** An absolute filesystem path as its last segment; anything else unchanged. */
    public static String sanitize(String source) {
        if (source == null || source.isEmpty()) return null;
        if (!ABSOLUTE_PATH.matcher(source).matches()) return source;
        String trimmed = source.replaceAll("[\\\\/]+$", "");
        int cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String name = cut >= 0 ? trimmed.substring(cut + 1) : trimmed;
        return name.isEmpty() ? null : name;
    }
}
