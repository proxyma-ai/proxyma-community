package ai.proxyma.core.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROXYMA AS A CLAUDE DESKTOP EXTENSION (2026-11-14): a .mcpb file whose opening is the whole setup - an MCPB 0.3 manifest
 * with every required field, the install's /mcp address and token in the bridge's environment, and the bridge itself.
 */
class ClaudeExtensionTest {

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }

    @Test
    @DisplayName("a manifest Claude Desktop installs as it is - required fields, Node entry point, address and token - and the bridge beside it")
    void packed() throws Exception {
        Map<String, byte[]> files = unzip(ClaudeExtension.pack("http://127.0.0.1:4246/mcp", "pxm_abc"));
        assertThat(files).containsOnlyKeys("manifest.json", "server/index.js");

        JsonNode m = new ObjectMapper().readTree(files.get("manifest.json"));
        assertThat(m.path("manifest_version").asText()).isEqualTo("0.3");
        for (String required : new String[]{"name", "version", "description"}) {
            assertThat(m.path(required).asText()).as(required).isNotBlank();
        }
        assertThat(m.path("author").path("name").asText()).isNotBlank();
        JsonNode server = m.path("server");
        assertThat(server.path("type").asText()).isEqualTo("node");
        assertThat(server.path("entry_point").asText()).isEqualTo("server/index.js");
        assertThat(server.path("mcp_config").path("command").asText()).isEqualTo("node");
        assertThat(server.path("mcp_config").path("args").get(0).asText()).isEqualTo("${__dirname}/server/index.js");
        assertThat(server.path("mcp_config").path("env").path("PROXYMA_URL").asText()).isEqualTo("http://127.0.0.1:4246/mcp");
        assertThat(server.path("mcp_config").path("env").path("PROXYMA_TOKEN").asText()).isEqualTo("pxm_abc");

        String bridge = new String(files.get("server/index.js"), StandardCharsets.UTF_8);
        assertThat(bridge).isEqualTo(ClaudeExtension.bridge())
                .contains("process.env.PROXYMA_URL", "Mcp-Session-Id", "text/event-stream", "notifications/initialized");
        assertThat(bridge.chars().allMatch(c -> c < 128)).as("ASCII only").isTrue();
    }
}
