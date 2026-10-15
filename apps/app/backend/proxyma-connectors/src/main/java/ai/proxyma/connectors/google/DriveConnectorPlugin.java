package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.Capabilities;
import ai.proxyma.core.connector.spi.Category;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.SourceContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Drive as a source: the files of My Drive, of what others shared with the account, of
 * chosen shared drives - or of one folder and the folders inside it. Google Docs, Sheets and Slides
 * are exported (to Markdown, CSV and text); every other file is read as it is, with its own content
 * type, so the converters read PDF and Office files as they read them from a folder.
 *
 * <p><b>Identity</b> is what the source reads: {@code folder:<id>}, else the chosen parts of the
 * Drive - {@code mydrive}, {@code shared}, {@code drive:<id>} for each shared drive.
 *
 * <p><b>Incremental</b>: Drive's change feed, a page token per corpus (the account's own, and each
 * shared drive's) kept as the cursor. A token Google no longer accepts lists that corpus in full.
 *
 * <p><b>Deletion</b>: the change feed's removed and trashed files are listed as changed, and their
 * fetch finds them gone; a complete full listing removes what it did not list.
 *
 * <p>Not shareable: what is indexed is what the connection's account may read, and nobody else
 * passed that check.
 */
@Component
public class DriveConnectorPlugin implements ConnectorPlugin {

    static final String MY_DRIVE = "myDrive";
    static final String SHARED_WITH_ME = "sharedWithMe";
    static final String SHARED_DRIVES = "sharedDrives";
    static final String FOLDER = "folder";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.GOOGLE_DRIVE)
            .protocol(ConnectionProtocol.GOOGLE)
            .displayName("Google Drive")
            .description("Files in Google Drive - Docs, Sheets and Slides, PDF and Office files - from My Drive, "
                    + "shared drives or one folder.")
            .noun("Google Drive", "Google Drive")
            .addTitle("Add Google Drive")
            .order(40)
            .category(Category.STORAGE)
            .mark("googledrive")
            .fields(
                    FieldSpec.bool(MY_DRIVE, "My Drive").defaultValue("true")
                            .help("The files you own."),
                    FieldSpec.bool(SHARED_WITH_ME, "Shared with me").defaultValue("false")
                            .help("Files other people shared with you."),
                    FieldSpec.list(SHARED_DRIVES, "Shared drives").placeholder("Shared drive ID or link")
                            .help("Each shared drive to read, by its ID or the link to it."),
                    FieldSpec.text(FOLDER, "Only this folder").placeholder("Folder ID or link").asAdvanced()
                            .help("Read this folder and the folders inside it, instead of the choices above."))
            .capabilities(Capabilities.builder().incremental().cursor().fileTypeFilter().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("google-drive")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new DriveSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        String folder = id(config.get(FOLDER));
        if (!folder.isEmpty()) return "folder:" + folder;
        List<String> parts = new ArrayList<>();
        if (flag(config, MY_DRIVE, true)) parts.add("mydrive");
        if (flag(config, SHARED_WITH_ME, false)) parts.add("shared");
        for (String drive : sharedDrives(config)) parts.add("drive:" + drive);
        return String.join(",", parts);
    }

    /** "My Drive", "Shared with me, shared drive 0AB...", "Folder 1x2y..." - one name per scope. */
    @Override
    public String displayName(Map<String, String> config) {
        String folder = id(config.get(FOLDER));
        if (!folder.isEmpty()) return "Drive folder " + folder;
        List<String> parts = new ArrayList<>();
        if (flag(config, MY_DRIVE, true)) parts.add("My Drive");
        if (flag(config, SHARED_WITH_ME, false)) parts.add("Shared with me");
        for (String drive : sharedDrives(config)) parts.add("Shared drive " + drive);
        return String.join(", ", parts);
    }

    /** The source's page in Drive - the folder, the one shared drive, My Drive or Shared with me. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String folder = id(config.get(FOLDER));
        if (!folder.isEmpty()) return "https://drive.google.com/drive/folders/" + folder;
        boolean mine = flag(config, MY_DRIVE, true);
        boolean shared = flag(config, SHARED_WITH_ME, false);
        TreeSet<String> drives = sharedDrives(config);
        if (!mine && !shared && drives.size() == 1) return "https://drive.google.com/drive/folders/" + drives.first();
        if (mine && !shared && drives.isEmpty()) return "https://drive.google.com/drive/my-drive";
        if (!mine && shared && drives.isEmpty()) return "https://drive.google.com/drive/shared-with-me";
        return "https://drive.google.com/drive/";
    }

    static boolean flag(Map<String, String> config, String key, boolean fallback) {
        String v = config.get(key);
        return v == null || v.isBlank() ? fallback : Boolean.parseBoolean(v.strip());
    }

    /** The shared drives the source names, as IDs, in a stable order. */
    static TreeSet<String> sharedDrives(Map<String, String> config) {
        TreeSet<String> out = new TreeSet<>();
        String raw = config.get(SHARED_DRIVES);
        if (raw == null) return out;
        for (String line : raw.split("\\r?\\n")) {
            String id = id(line);
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    private static final Pattern IN_LINK = Pattern.compile("(?:/folders/|/drives/|/d/|[?&]id=)([A-Za-z0-9_-]+)");
    private static final Pattern BARE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * The Drive ID in what was typed - an ID as it is, or the ID inside a link to a folder or a shared
     * drive ({@code https://drive.google.com/drive/folders/<id>}). Empty for nothing, and for a value
     * that is neither, which the session refuses.
     */
    static String id(String raw) {
        if (raw == null) return "";
        String v = raw.strip();
        if (v.isEmpty()) return "";
        Matcher m = IN_LINK.matcher(v);
        return m.find() ? m.group(1) : v;
    }

    /** Whether a value {@link #id} returned is a Drive ID at all. */
    static boolean isId(String id) {
        return id != null && BARE_ID.matcher(id).matches();
    }
}
