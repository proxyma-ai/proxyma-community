package ai.proxyma.core.telemetryclient;

import ai.proxyma.core.system.util.SensitivePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the tail of this installation's own log file, for the feedback form's
 * "include my latest log" option.
 *
 * THE TAIL, NOT THE FILE. A long-running installation's log is unbounded - attaching all of it
 * would mean uploading tens of megabytes, and the useful part of a bug report is what happened
 * just before the user gave up and filled in the form. {@link #MAX_BYTES} of the end covers that
 * comfortably and bounds what leaves the machine.
 *
 * This is the one attachment kind the PRODUCT assembles rather than the user choosing a file, so
 * it is the one that can pick up something they never individually looked at. Two consequences,
 * both deliberate: the modal has to state plainly what a log can contain before the box is ticked,
 * and it travels tagged as {@code FeedbackAttachmentKind.LOG} so the receiving end can label and
 * retain it as such rather than filing it with screenshots.
 *
 * <p>AND IT IS SCREENED BEFORE IT LEAVES (isd-03). It went verbatim - Desktop logs ai.proxyma at
 * DEBUG - while the daily diagnostics report, which crosses the same boundary, carries no free text
 * at all. {@link #redact} takes out what a line should never carry to another trust domain:
 * absolute server paths (SensitivePaths, as at every client boundary), query strings, and anything
 * shaped like a credential.
 */
@Component
public class LogTailProvider {

    private static final Logger log = LoggerFactory.getLogger(LogTailProvider.class);

    // Roughly a few thousand lines - enough to cover a startup plus the session that went wrong.
    private static final long MAX_BYTES = 512 * 1024;

    // Same property Spring Boot itself writes the log with, so this cannot drift from where the
    // file actually is. Empty (no file logging configured) means there is nothing to offer.
    @Value("${logging.file.name:}")
    private String logFileName;

    public boolean isAvailable() {
        Path p = resolve();
        return p != null && Files.isRegularFile(p);
    }

    /** The last {@link #MAX_BYTES} of the log as UTF-8 text, or null when there is no log file. */
    public String readTail() {
        Path path = resolve();
        if (path == null || !Files.isRegularFile(path)) return null;
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long length = raf.length();
            long from = Math.max(0, length - MAX_BYTES);
            raf.seek(from);
            byte[] buf = new byte[(int) (length - from)];
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            // Seeking to a byte offset can land mid-line (and mid-UTF-8-sequence); drop the
            // partial first line rather than shipping a mangled one.
            if (from > 0) {
                int nl = text.indexOf('\n');
                if (nl >= 0 && nl + 1 < text.length()) text = text.substring(nl + 1);
            }
            return redact(text);
        } catch (IOException e) {
            log.warn("[LogTail] Could not read {}: {}", path, e.getMessage());
            return null;
        }
    }

    // "Bearer abc...", "Basic abc..." - the scheme word kept, the credential not.
    private static final Pattern AUTH_SCHEME = Pattern.compile(
            "(?i)\\b(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{8,}");
    // key=value and "key": "value" where the key names a credential. A scheme word after it is left
    // to AUTH_SCHEME, which has already masked what follows it.
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:api[-_]?key|access[-_]?token|refresh[-_]?token|id[-_]?token|client[-_]?secret|secret|"
            + "password|passwd|pwd|passphrase|token|authorization|cookie|session[-_]?id|credential)"
            + "[\"']?\\s*[:=]\\s*[\"']?)(?!(?:bearer|basic|token)\\s)([^\\s\"'&,;)}\\]]+)");
    // Values that are credentials by their shape alone: vendor key prefixes, the product's own MCP
    // tokens, source-host tokens, Google API keys, JWTs.
    private static final Pattern TOKEN_SHAPE = Pattern.compile(
            "\\b(?:(?:sk|pk|rk)-[A-Za-z0-9_-]{16,}|pxm_[0-9a-fA-F]{16,}"
            + "|(?:ghp|gho|ghs|ghu|glpat|xox[abpsr])[-_][A-Za-z0-9_-]{10,}"
            + "|AIza[0-9A-Za-z_-]{30,}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})");
    // A URL's query string: where a signed link, an authorization code or a search term rides.
    private static final Pattern QUERY_STRING = Pattern.compile("(https?://[^\\s?#\"'<>]+)\\?[^\\s\"'<>]*");

    /**
     * A log text with what must not leave the host taken out. Best-effort by nature - a filter over
     * free text, recognizing the shapes these values take - and never a reason to log one.
     */
    static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = SensitivePaths.scrub(text);
        out = QUERY_STRING.matcher(out).replaceAll("$1?...");
        out = AUTH_SCHEME.matcher(out).replaceAll("$1 ***");
        out = NAMED_SECRET.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "***"));
        out = TOKEN_SHAPE.matcher(out).replaceAll("***");
        return out;
    }

    public String fileName() {
        Path p = resolve();
        return p != null && p.getFileName() != null ? p.getFileName().toString() : "proxyma.log";
    }

    private Path resolve() {
        if (logFileName == null || logFileName.isBlank()) return null;
        try {
            return Path.of(logFileName);
        } catch (Exception e) {
            return null;
        }
    }
}
