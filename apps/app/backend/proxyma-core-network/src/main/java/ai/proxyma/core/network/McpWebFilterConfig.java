package ai.proxyma.core.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.WebFilter;

@Configuration
public class McpWebFilterConfig {

    private static final Logger log = LoggerFactory.getLogger(McpWebFilterConfig.class);

    // Reactor Netty keep-alive can corrupt/truncate MCP streamable responses.
    // Force Connection: close on /mcp routes so each response is fully flushed.
    @Bean
    @Order(-100)
    public WebFilter mcpConnectionCloseFilter() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();
            if (!path.startsWith("/mcp")) {
                return chain.filter(exchange);
            }
            exchange.getResponse().getHeaders().set(HttpHeaders.CONNECTION, "close");
            log.info("MCP transport request: {} {} (Accept={})",
                    exchange.getRequest().getMethod(), path,
                    exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT));
            return chain.filter(exchange)
                    .doOnSuccess(v -> log.info("MCP transport response: {} {} -> {}",
                            exchange.getRequest().getMethod(), path,
                            exchange.getResponse().getStatusCode()))
                    .doOnError(e -> log.error("MCP transport request {} {} failed: {}",
                            exchange.getRequest().getMethod(), path, e.toString()));
        };
    }
}
