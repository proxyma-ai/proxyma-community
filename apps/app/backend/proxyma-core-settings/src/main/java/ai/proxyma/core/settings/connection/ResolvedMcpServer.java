package ai.proxyma.core.settings.connection;

import java.util.UUID;

/**
 * An MCP server connection with its secrets decrypted - what the MCP client manager connects with.
 * Like {@link ResolvedConnection}, the one shape a plaintext secret leaves the connection service
 * in, and never serialized: {@link #toString} names the server and nothing it holds.
 *
 * @param url      the endpoint for streamable HTTP and SSE; null for a local command
 * @param shared   a Super admin's server offered to everyone on Enterprise Server
 * @param updatedAt a value that changes whenever the connection is saved, so a cached client
 *                  built from an older version can tell it is stale
 */
public record ResolvedMcpServer(UUID id, String ownerId, String name, String url, McpServerConfig config,
                                McpServerConfig.McpSecrets secrets, boolean shared, ConnectionStatus status,
                                String updatedAt) {

    @Override
    public String toString() {
        return "ResolvedMcpServer[" + name + " (" + id + "), " + config.transport() + "]";
    }
}
