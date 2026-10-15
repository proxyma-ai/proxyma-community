package ai.proxyma.core.settings.connection;

/**
 * Tests an MCP server connection: connects, initializes, lists its tools. A PORT, because the MCP
 * client lives in proxyma-core-tools, above this module - the connection controller asks it the way
 * it asks a {@link ConnectionTester} of any other protocol, and gets the same kind of answer.
 */
public interface McpServerProbe {

    /** Never throws; a refusal is a failed {@link ConnectionTester.Result} with a readable sentence. */
    ConnectionTester.Result probe(ResolvedMcpServer server);
}
