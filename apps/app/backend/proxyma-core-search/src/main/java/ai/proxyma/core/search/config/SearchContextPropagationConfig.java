package ai.proxyma.core.search.config;

import ai.proxyma.core.search.domain.SearchContextUserIdAccessor;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Wires {@link SearchContextUserIdAccessor} into Micrometer's global {@link ContextRegistry} and
 * enables Reactor's automatic ThreadLocal-to-Context propagation.
 *
 * <p>Without this, {@code SearchContext.getUserId()} (a plain ThreadLocal set by
 * JwtAuthFilter on the WebFlux/Netty thread) reads {@code null} once execution hops onto a
 * different pooled thread - which spring-ai-mcp's tool-execution wrapper does for every MCP tool
 * call ({@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}). Enabling
 * automatic propagation is process-global, but with a single accessor registered today the added
 * cost is one extra map lookup per Reactor scheduler hop.
 */
@Configuration
public class SearchContextPropagationConfig {

    @PostConstruct
    void registerAccessorAndEnablePropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new SearchContextUserIdAccessor());
        Hooks.enableAutomaticContextPropagation();
    }
}
