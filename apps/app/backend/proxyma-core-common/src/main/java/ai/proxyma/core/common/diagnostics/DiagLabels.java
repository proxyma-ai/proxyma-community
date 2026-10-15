package ai.proxyma.core.common.diagnostics;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns what a call site has into what a diagnostics label may hold.
 *
 * <p>THE LAST LINE, NOT THE FIRST. Call sites are expected to pass enum names and the classifiers
 * below, never a title or a path. {@link #clean(String)} is what makes a mistake harmless: a label
 * value that is not a short token of letters, digits and {@code _ . + -} becomes {@code "other"},
 * so a file name, a URL or a sentence cannot survive it - they all contain a character outside
 * that set, or are too long.
 */
public final class DiagLabels {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.+-]{1,40}");
    // A long unbroken run of letters AND digits, or of hex, is what a key, a hash or an id looks
    // like - never a code-defined label ("OPENAI_COMPATIBLE", "search_documents", "10m-100m").
    private static final Pattern LONG_RUN = Pattern.compile("[A-Za-z0-9]{16,}");
    private static final Pattern HEX_RUN = Pattern.compile("[0-9a-fA-F]{12,}");

    /**
     * Every string the classifiers below can return, plus the fixed words. A FEATURE value that is
     * a string must be one of these - see {@link #isVocabulary}.
     */
    private static final Set<String> VOCABULARY = Set.of(
            "on", "off", "none", "other", "unknown",
            "office", "pdf", "image", "text", "code",
            "lt100k", "100k-1m", "1m-10m", "10m-100m", "100m+",
            "0", "1-10", "11-100", "101-1k", "1k-10k", "10k-100k", "100k+",
            "<8", "8-16", "16-32", "32+",
            "Windows", "Mac", "Linux");

    private static final Set<String> OFFICE = Set.of(
            "doc", "docx", "docm", "dot", "dotx", "xls", "xlsx", "xlsm", "ppt", "pptx", "pptm",
            "odt", "ods", "odp", "rtf", "msg", "eml", "epub", "vsdx");
    private static final Set<String> IMAGE = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "webp", "heic");
    private static final Set<String> TEXT = Set.of(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "yaml", "yml",
            "log", "ini", "toml", "rst", "adoc", "properties");
    private static final Set<String> CODE = Set.of(
            "java", "kt", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c", "h", "cpp", "hpp", "cs",
            "rb", "php", "swift", "scala", "sql", "sh", "bat", "ps1", "css", "scss", "vue", "svelte");

    private DiagLabels() {}

    /**
     * A label value that is safe to put on the wire. Anything else becomes "other": text with a
     * space, a slash, a colon or a backslash (every path, URL and sentence), anything over 40
     * characters, and anything with a long random-looking run - a key or an id that happens to be
     * made only of token characters.
     */
    public static String clean(String value) {
        if (value == null || value.isBlank()) return "none";
        if (!TOKEN.matcher(value).matches()) return "other";
        if (HEX_RUN.matcher(value).find()) return "other";
        java.util.regex.Matcher run = LONG_RUN.matcher(value);
        while (run.find()) {
            String r = run.group();
            boolean letters = r.chars().anyMatch(Character::isLetter);
            boolean digits = r.chars().anyMatch(Character::isDigit);
            if (letters && digits) return "other";
        }
        return value;
    }

    /** Whether a string is one of the fixed words and ranges this class produces. */
    public static boolean isVocabulary(String value) {
        return value != null && VOCABULARY.contains(value);
    }

    public static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    /**
     * The class of a file - office, pdf, image, text, code or other - from a file name, an
     * extension or a MIME type. Only the class leaves: never the name, and not even the exact
     * extension, since an unusual extension is itself a small fingerprint.
     */
    public static String fileClass(String nameOrMimeType) {
        if (nameOrMimeType == null || nameOrMimeType.isBlank()) return "other";
        String v = nameOrMimeType.trim().toLowerCase(Locale.ROOT);
        if (v.contains("/")) {
            // A MIME type ("application/pdf") - or a path, whose last segment is then the name.
            if (v.startsWith("application/pdf")) return "pdf";
            if (v.startsWith("image/")) return "image";
            if (v.contains("officedocument") || v.contains("msword") || v.contains("ms-excel")
                    || v.contains("ms-powerpoint") || v.contains("opendocument") || v.contains("rtf")) {
                return "office";
            }
            if (v.startsWith("text/") || v.endsWith("/json") || v.endsWith("/xml")) return "text";
            int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
            v = v.substring(slash + 1);
        }
        int dot = v.lastIndexOf('.');
        String ext = dot >= 0 ? v.substring(dot + 1) : v;
        if (ext.equals("pdf")) return "pdf";
        if (OFFICE.contains(ext)) return "office";
        if (IMAGE.contains(ext)) return "image";
        if (TEXT.contains(ext)) return "text";
        if (CODE.contains(ext)) return "code";
        return "other";
    }

    /** A byte count as a range. Negative means unknown. */
    public static String sizeBucket(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 100L * 1024) return "lt100k";
        if (bytes < 1024L * 1024) return "100k-1m";
        if (bytes < 10L * 1024 * 1024) return "1m-10m";
        if (bytes < 100L * 1024 * 1024) return "10m-100m";
        return "100m+";
    }

    /** A count as a range - how many documents, how many sources. */
    public static String countBucket(long n) {
        if (n <= 0) return "0";
        if (n <= 10) return "1-10";
        if (n <= 100) return "11-100";
        if (n <= 1_000) return "101-1k";
        if (n <= 10_000) return "1k-10k";
        if (n <= 100_000) return "10k-100k";
        return "100k+";
    }

    /** Physical memory as a range, in GB. */
    public static String memoryBucket(long bytes) {
        if (bytes <= 0) return "unknown";
        double gb = bytes / (1024.0 * 1024 * 1024);
        if (gb < 7.5) return "<8";
        if (gb < 15.5) return "8-16";
        if (gb < 31.5) return "16-32";
        return "32+";
    }

    /** The OS family, never its version or build number. */
    public static String osFamily(String osName) {
        if (osName == null) return "other";
        String v = osName.toLowerCase(Locale.ROOT);
        if (v.contains("win")) return "Windows";
        if (v.contains("mac") || v.contains("darwin")) return "Mac";
        if (v.contains("linux")) return "Linux";
        return "other";
    }
}
