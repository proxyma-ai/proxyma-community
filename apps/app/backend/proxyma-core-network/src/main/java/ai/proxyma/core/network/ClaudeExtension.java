package ai.proxyma.core.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PROXYMA AS A CLAUDE DESKTOP EXTENSION (2026-11-14, the maintainer: people pay for Claude, not an API, and a JSON config
 * file is not for them): a {@code .mcpb} bundle - a zip of {@code manifest.json} (MCPB manifest 0.3) and a bridge,
 * {@code server/index.js}, which passes Claude Desktop's MCP over standard input and output to this install's
 * {@code /mcp} - with the address and the install's token written into the manifest's environment, so opening the file
 * is the whole setup. Claude Desktop runs the bridge with the Node it ships; the bridge needs no package.
 *
 * <p>The file carries the token, as the configurations Settings > AI Assistants shows already do: whoever holds it can
 * use this install's tools, and a new access code makes it stop working.
 */
public final class ClaudeExtension {

    /** The bundle's version: the bridge's, raised when index.js changes. */
    static final String VERSION = "1.0.0";
    static final String BRIDGE = "/mcpb/index.js";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ClaudeExtension() {}

    /** The bundle for an install reached at {@code mcpUrl} with {@code token}. */
    public static byte[] pack(String mcpUrl, String token) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest(mcpUrl, token)));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("server/index.js"));
            try (InputStream in = ClaudeExtension.class.getResourceAsStream(BRIDGE)) {
                if (in == null) throw new IOException("The extension's bridge is missing from this build.");
                in.transferTo(zip);
            }
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    static ObjectNode manifest(String mcpUrl, String token) {
        ObjectNode m = JSON.createObjectNode();
        m.put("manifest_version", "0.3");
        m.put("name", "proxyma");
        m.put("display_name", "Proxyma");
        m.put("version", VERSION);
        m.put("description", "Your Proxyma sources, and your YouTube channels' comments, for Claude.");
        m.putObject("author").put("name", "Proxyma").put("url", "https://proxyma.ai");
        m.put("homepage", "https://proxyma.ai");
        ObjectNode server = m.putObject("server");
        server.put("type", "node");
        server.put("entry_point", "server/index.js");
        ObjectNode config = server.putObject("mcp_config");
        config.put("command", "node");
        config.putArray("args").add("${__dirname}/server/index.js");
        ObjectNode env = config.putObject("env");
        env.put("PROXYMA_URL", mcpUrl);
        env.put("PROXYMA_TOKEN", token);
        m.putObject("compatibility").putObject("runtimes").put("node", ">=18.0.0");
        m.put("tools_generated", true);
        return m;
    }

    /** The bridge's text, for a test to read. */
    static String bridge() throws IOException {
        try (InputStream in = ClaudeExtension.class.getResourceAsStream(BRIDGE)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
