package ai.proxyma.connectors.dropbox;

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

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dropbox as a source: the files of the whole Dropbox the connection signs in to, or of one folder -
 * and, where the source says so, the folders inside it. Files are read as they are and converted like
 * a folder's; a Paper doc, which Dropbox will not hand out as a file, is exported as markdown.
 *
 * <p><b>Identity</b> is the folder's path, lower case as Dropbox compares paths ({@code /} for the
 * whole Dropbox) - a link to the folder on dropbox.com reads as its path.
 *
 * <p><b>Incremental</b>: Dropbox's own list-folder cursor, kept as the source's cursor - a CHANGES
 * pass asks {@code list_folder/continue} for what changed since, and a cursor Dropbox has reset lists
 * the folder in full again.
 *
 * <p><b>Deletion</b>: the changes Dropbox reports as deleted are listed as changed, and their fetch
 * finds them gone; a complete full listing removes what it did not list.
 *
 * <p>Not shareable: what is indexed is what the connection's account may read, and nobody else
 * passed that check.
 */
@Component
public class DropboxConnectorPlugin implements ConnectorPlugin {

    static final String PATH = "path";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.DROPBOX)
            .protocol(ConnectionProtocol.DROPBOX)
            .displayName("Dropbox")
            .description("Files in Dropbox - PDF and Office files, text and Paper docs - from the whole Dropbox or one folder.")
            .noun("Dropbox folder", "Dropbox folders")
            .addTitle("Add Dropbox")
            .order(46)
            .category(Category.STORAGE)
            .mark("dropbox")
            .fields(FieldSpec.text(PATH, "Folder").placeholder("/Projects, or a link to the folder")
                    .help("Leave it empty to read your whole Dropbox."))
            .capabilities(Capabilities.builder().incremental().cursor().fileTypeFilter().recursiveScope().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("dropbox")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new DropboxSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        String path = path(config.get(PATH));
        return path.isEmpty() ? "/" : path.toLowerCase(Locale.ROOT);
    }

    @Override
    public String displayName(Map<String, String> config) {
        String path = path(config.get(PATH));
        return path.isEmpty() ? "All of Dropbox" : "Dropbox " + path;
    }

    /** The folder on dropbox.com. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        return webUrl(path(config.get(PATH)));
    }

    static String webUrl(String path) {
        StringBuilder out = new StringBuilder("https://www.dropbox.com/home");
        if (path == null) return out.toString();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            out.append('/').append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    private static final Pattern IN_LINK = Pattern.compile("^https?://(?:www\\.)?dropbox\\.com/home(/[^?#]*)?(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * The folder's path in what was typed: a path as it is ({@code /Projects/Q4}, a missing leading
     * slash added, a trailing one dropped), or the path inside a dropbox.com/home link. Empty for
     * the whole Dropbox.
     */
    static String path(String raw) {
        if (raw == null) return "";
        String v = raw.strip();
        Matcher m = IN_LINK.matcher(v);
        if (m.matches()) v = m.group(1) == null ? "" : URLDecoder.decode(m.group(1).replace("+", "%2B"), StandardCharsets.UTF_8);
        v = v.replace('\\', '/').replaceAll("/{2,}", "/");
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (v.isEmpty()) return "";
        return v.startsWith("/") ? v : "/" + v;
    }
}
