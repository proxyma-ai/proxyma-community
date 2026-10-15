package ai.proxyma.core.tools;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

// Suppresses Spring AI's stock ToolCallbackConverterAutoConfiguration so that WebFetchMcpToolConfig
// can build the MCP sync tool specs itself and make webFetch return a real MCP ImageContent block
// for direct-image URLs (the stock converter only allows one fixed text-or-image mime per tool, so
// a mixed text/image tool like webFetch cannot switch content type per result - see that class).
//
// Done as an AutoConfigurationImportFilter (registered in META-INF/spring.factories) rather than a
// per-app spring.autoconfigure.exclude on purpose: this way the exclusion travels WITH this module,
// so every consumer that runs the MCP server - proxyma-desktop, proxyma-server, and the separate
// app - automatically gets it alongside the replacement bean, instead of each app's properties
// having to remember the line. Leaving stock in place alongside WebFetchMcpToolConfig would register
// webFetch twice (stock text spec + custom image spec).
//
// Scope note: this always removes the stock converter wherever proxyma-core-tools is present. Every
// such consumer also has WebFetchMcpToolConfig, which re-supplies all tool specs for the SYNC
// server (proxyma's only configured mode). A consumer that switched the MCP server to ASYNC would
// need an async equivalent, since both the stock async converter and the sync replacement would be
// absent - no app does this today.
public class StockMcpToolConverterExclusionFilter implements AutoConfigurationImportFilter {

    private static final String STOCK_CONVERTER =
            "org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration";

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        boolean[] matches = new boolean[autoConfigurationClasses.length];
        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            matches[i] = !STOCK_CONVERTER.equals(autoConfigurationClasses[i]);
        }
        return matches;
    }
}
