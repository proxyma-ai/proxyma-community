package ai.proxyma.core.connector.files;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The file vocabulary both file-backed connectors read from: which extensions exist, what they are
 * called, how to recognise one, how to hash one, and how to walk a directory collecting them.
 *
 * WHY THIS IS SHARED when {@link LocalFolderConnector} and {@link UploadedFilesConnector} are
 * deliberately not. Everything in this class is a pure function that reads and returns. Nothing
 * here decides anything, owns any state, or touches a byte: the caller supplies the root, the
 * filter, the recursion depth, the ceiling and even which {@link ConnectorType} to stamp on the
 * results. The two connectors are separate because their POLICY is separate - what a valid root
 * is, who owns the bytes under it, and what deleting the connector means. A MIME lookup table and a
 * SHA-256 loop are not policy.
 *
 * Duplicating this instead would put two copies of the extension list in the tree, and this project
 * already has the receipt for that: proxyma-license and proxyma-telemetry drifted into three
 * separate fixes applied to one and not the other (see ../proxyma-docs/LESSONS_LEARNED.md). An
 * extension quietly supported by the desktop edition and not the server one would be exactly that
 * bug, reported as "this file type does not index" months later.
 */
public final class FileTypeSupport {

    private FileTypeSupport() {}

    // What a folder indexes when it names no file types of its own, grouped the way the file-type
    // pickers offer them. A connector may narrow this, and may also name extensions outside it
    // (see parseFileTypes): anything markitdown has no converter for is read as plain UTF-8 text
    // by the ingest pipeline, so an arbitrary extension is a usable choice, just not a default
    // one. Group KEYS only - the display labels are the frontend's, this class has no business
    // holding UI copy; the grouping itself lives here so it cannot drift from the extension list.
    //
    // The macro-enabled OOXML variants are in "office" because markitdown genuinely converts them:
    // they are the same zip container as their non-macro counterparts plus a vbaProject.bin part
    // (see MarkitdownService.CONVERTIBLE_EXTENSIONS, which has always listed them). Leaving them
    // out meant a folder full of .xlsm workbooks indexed as nothing at all.
    private static final Map<String, List<String>> EXTENSION_GROUPS = new LinkedHashMap<>();
    static {
        EXTENSION_GROUPS.put("office", List.of(
                "pdf", "doc", "docx", "docm", "dotm",
                "ppt", "pptx", "pptm", "potm", "ppsm",
                "xls", "xlsx", "xlsm", "xltm"));
        EXTENSION_GROUPS.put("text", List.of("txt", "md", "csv", "html", "htm"));
        EXTENSION_GROUPS.put("mail", List.of("msg", "epub"));
        EXTENSION_GROUPS.put("code", List.of(
                "c", "h", "cpp", "hpp", "cc", "hh", "cxx", "hxx",
                "sh", "bat", "cmd", "ps1"));
        EXTENSION_GROUPS.put("engineering", List.of(
                "s", "asm", "ld", "lds", "lsl", "arxml", "dbc", "a2l"));
    }

    private static final Set<String> KNOWN_EXTENSIONS = EXTENSION_GROUPS.values().stream()
            .flatMap(List::stream).collect(Collectors.toUnmodifiableSet());

    // The group the file-type pickers pre-select, per the "default to office types only" rule.
    // Every other group is opt-in.
    private static final String DEFAULT_GROUP = "office";

    /** Ceiling value meaning "no ceiling" - see {@link #walk}. */
    public static final int UNLIMITED = -1;

    // Parses a connector's stored fileTypes string ("pdf, .DOCX ,json") into the extension set
    // isSupportedFileType() expects: lowercase, no leading dot, no blanks, deduped, order
    // preserved. Returns null - meaning "no restriction, use KNOWN_EXTENSIONS" - for null/blank
    // input, so a folder that never chose types keeps the original behavior exactly.
    //
    // Deliberately NOT intersected with KNOWN_EXTENSIONS: an extension the user typed themselves
    // is an explicit instruction, and the ingest pipeline reads anything markitdown has no
    // converter for as plain UTF-8 text (DocumentConversionService), so ".json" or ".log" is a
    // perfectly good answer here. Intersecting would silently drop exactly the custom types the
    // filter exists to allow.
    public static Set<String> parseFileTypes(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Set<String> parsed = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String ext = part.trim().toLowerCase();
            while (ext.startsWith(".")) ext = ext.substring(1);
            if (!ext.isEmpty()) parsed.add(ext);
        }
        return parsed.isEmpty() ? null : parsed;
    }

    // The extensions the "which file types?" pickers offer as checkboxes, keyed by group, and
    // which of them are pre-selected for a new connector.
    public static Map<String, List<String>> extensionGroups() {
        return Collections.unmodifiableMap(EXTENSION_GROUPS);
    }

    public static List<String> defaultExtensions() {
        return EXTENSION_GROUPS.get(DEFAULT_GROUP);
    }

    // Absent, blank or unparseable all mean unlimited. A missing key must not silently become a
    // ceiling of zero - that would index nothing at all for any caller built before this key
    // existed, which is a far worse failure than not enforcing a limit.
    public static int parseMaxFiles(String raw) {
        if (raw == null || raw.isBlank()) return UNLIMITED;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 0 ? UNLIMITED : parsed;
        } catch (NumberFormatException e) {
            return UNLIMITED;
        }
    }

    // enabledExts narrows the default set; pass null for "every supported type". Callers should
    // hand it the output of parseFileTypes().
    public static boolean isSupportedFileType(Path path, Set<String> enabledExts) {
        return isSupportedFileName(path.getFileName().toString(), enabledExts);
    }

    /**
     * The same question asked of a bare NAME, for connectors whose listing hands back names rather
     * than local paths - the network share walks a remote directory and never has a {@link Path}.
     *
     * <p>An overload rather than a second extension list, for the reason the class javadoc gives:
     * a type quietly supported over SMB and not on disk would be reported as "this file type does
     * not index" months later, by someone comparing a share against the same folder copied locally.
     */
    public static boolean isSupportedFileName(String filename, Set<String> enabledExts) {
        if (filename == null) return false;
        String name = filename.toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1);
        if (enabledExts != null) return enabledExts.contains(ext);
        return KNOWN_EXTENSIONS.contains(ext);
    }

    // Used by SyncService (proxyma-core-connector) to populate TrackedDocument.contentHash for
    // cross-user duplicate detection - see docs on that field. Reads the whole file in one pass;
    // called once per (new or modified) file during sync, not on every request.
    public static String computeFileHash(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Collects every supported file under {@code root} as {@link ItemMetadata}, stamped with the
     * {@code type} the CALLER names.
     *
     * Read-only by construction: it opens no streams, and the only filesystem calls are the walk
     * itself and the attributes the visitor is handed. `type` is a parameter rather than something
     * this class decides, which is the line between "shared vocabulary" and "shared policy" - the
     * connectors keep the latter.
     *
     * @param maxFiles {@link #UNLIMITED}, or a ceiling to stop the walk at.
     */
    public static List<ItemMetadata> walk(Path root, Set<String> enabledExts, boolean recursive,
                                          int maxFiles, ConnectorType type) throws IOException {
        List<ItemMetadata> items = new ArrayList<>();
        int maxDepth = recursive ? Integer.MAX_VALUE : 1;
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        // A walk stopped at maxDepth hands the directories it did not enter to
                        // visitFile too, so a folder named like a file ("notes.md") became an item
                        // that failed with "not a regular file".
                        if (!attrs.isDirectory() && isSupportedFileType(file, enabledExts)) {
                            ItemMetadata meta = new ItemMetadata(
                                    file.toAbsolutePath().toString(),
                                    file.getFileName().toString(),
                                    type,
                                    attrs.lastModifiedTime().toInstant(),
                                    resolveMimeType(file.getFileName().toString()));
                            meta.setSizeBytes(attrs.size());
                            items.add(meta);
                            // TERMINATE rather than collect-then-truncate: a capped tier pointed at
                            // a 200k-file directory should stop walking, not walk it all and
                            // discard the rest. Which files land inside the cap is directory-entry
                            // order, which is stable for an unchanged tree - so a repeat sync of an
                            // untouched folder sees the same set and does not churn documents in
                            // and out of the index.
                            if (maxFiles != UNLIMITED && items.size() >= maxFiles) {
                                return FileVisitResult.TERMINATE;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        return items;
    }

    /** Opens a file for reading. The only stream this class ever creates, and it is read-only. */
    public static InputStream openForReading(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) {
            throw new IllegalArgumentException("ItemMetadata.id is null or blank");
        }
        Path path = Path.of(absolutePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File does not exist or is not a regular file: " + absolutePath);
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open file: " + absolutePath, e);
        }
    }

    static Instant lastModified(BasicFileAttributes attrs) {
        return attrs.lastModifiedTime().toInstant();
    }

    public static String resolveMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = lower.substring(dot + 1);
        return switch (ext) {
            case "pdf"  -> "application/pdf";
            case "doc"  -> "application/msword";
            // The macro-enabled variants are the same container as their siblings and convert the
            // same way. They had no entry, so they resolved to octet-stream, which the ingest
            // pipeline reads as UTF-8 text: a folder's .xlsm workbooks indexed as ZIP bytes.
            case "docx", "docm", "dotm" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "ppt"  -> "application/vnd.ms-powerpoint";
            case "pptx", "pptm", "potm", "ppsm" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xls"  -> "application/vnd.ms-excel";
            case "xlsx", "xlsm", "xltm" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "msg"  -> "application/vnd.ms-outlook";
            case "epub" -> "application/epub+zip";
            case "txt"  -> "text/plain";
            case "md"   -> "text/markdown";
            case "csv"  -> "text/csv";
            case "html", "htm" -> "text/html";
            case "c", "h", "cpp", "hpp", "cc", "hh", "cxx", "hxx" -> "text/x-csrc";
            case "s", "asm" -> "text/x-asm";
            case "ld", "lds", "lsl" -> "text/plain";
            case "arxml" -> "application/xml";
            case "dbc"  -> "text/plain";
            case "a2l"  -> "text/plain";
            case "sh"   -> "application/x-sh";
            case "bat", "cmd" -> "application/x-bat";
            case "ps1"  -> "application/x-powershell";
            default     -> "application/octet-stream";
        };
    }
}
