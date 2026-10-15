package ai.proxyma.connectors.box;

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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Box as a source: the files of everything the connection's user can see in Box ("All Files"), or of
 * one folder - and, where the source says so, the folders inside it. Files are read as they are and
 * converted like a folder's; a Box Note is read as text.
 *
 * <p><b>Identity</b> is the folder's ID ({@code 0} for All Files) - a link to the folder reads as
 * its ID.
 *
 * <p><b>Incremental</b>: the user's event stream ({@code stream_type=changes}), its position kept as
 * the source's cursor. See BoxSession for why the stream rather than comparing folder listings.
 *
 * <p><b>Deletion</b>: files the stream reports trashed, deleted or moved out are listed as changed,
 * and their fetch finds them gone; a complete full listing removes what it did not list.
 *
 * <p>Not shareable: what is indexed is what the connection's user may read, and nobody else passed
 * that check.
 */
@Component
public class BoxConnectorPlugin implements ConnectorPlugin {

    static final String FOLDER = "folder";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.BOX)
            .protocol(ConnectionProtocol.BOX)
            .displayName("Box")
            .description("Files in Box - PDF and Office files, text and Box Notes - from All Files or one folder.")
            .noun("Box folder", "Box folders")
            .addTitle("Add Box")
            .order(48)
            .category(Category.STORAGE)
            .mark("box")
            .fields(FieldSpec.text(FOLDER, "Folder").placeholder("Folder ID or link")
                    .help("Leave it empty to read All Files."))
            .capabilities(Capabilities.builder().incremental().cursor().fileTypeFilter().recursiveScope().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("box")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new BoxSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        return "folder:" + folder(config.get(FOLDER));
    }

    @Override
    public String displayName(Map<String, String> config) {
        String folder = folder(config.get(FOLDER));
        return BoxApi.ALL_FILES.equals(folder) ? "All of Box" : "Box folder " + folder;
    }

    /** The folder in Box's web app. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        return "https://app.box.com/folder/" + folder(config.get(FOLDER));
    }

    private static final Pattern IN_LINK = Pattern.compile("/folder/([0-9]+)");

    /**
     * The folder ID in what was typed: an ID as it is, or the ID inside a link to the folder
     * ({@code https://app.box.com/folder/123}, a company's {@code <name>.app.box.com} alike). All
     * Files ({@code 0}) for nothing; a value that is neither is kept, and the session refuses it.
     */
    static String folder(String raw) {
        if (raw == null || raw.isBlank()) return BoxApi.ALL_FILES;
        String v = raw.strip();
        Matcher m = IN_LINK.matcher(v);
        return m.find() ? m.group(1) : v;
    }

    /** Whether a value {@link #folder} returned is a Box ID at all. */
    static boolean isId(String id) {
        return id != null && id.matches("[0-9]+");
    }
}
