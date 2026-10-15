package ai.proxyma.core.search.domain;

import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;
import io.micrometer.context.ThreadLocalAccessor;

/**
 * Bridges {@link SearchContext}'s userId ThreadLocal across Reactor scheduler hops (notably
 * spring-ai-mcp's {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} tool
 * execution wrapper, which otherwise silently drops the caller's identity set by
 * JwtAuthFilter on the WebFlux/Netty thread).
 *
 * <p>Registered with {@code ContextRegistry.getInstance()} in
 * {@link ai.proxyma.core.search.config.SearchContextPropagationConfig}. Once automatic context
 * propagation is enabled, Reactor snapshots the current value via {@link #getValue()} whenever a
 * Reactor Context carries {@link SearchContext#REACTOR_CONTEXT_KEY}, restores it via
 * {@link #setValue(String)} on the destination thread before a task runs, and calls
 * {@link #reset()} on that same thread right after the task completes.
 */
public final class SearchContextUserIdAccessor implements ThreadLocalAccessor<String> {

    @Override
    public Object key() {
        return SearchContext.REACTOR_CONTEXT_KEY;
    }

    @Override
    public String getValue() {
        return SearchContext.getUserId();
    }

    @Override
    public void setValue(String value) {
        SearchContext.setUserId(value);
        // And who this thread's model calls are billed to. An MCP tool call reaches its tool
        // through here and through nothing else, so without this every model call an MCP tool made
        // outside a search - OCR while converting a document, a delegate's own calls - was filed
        // under SYSTEM. Only a real id: a blank one says nothing about who is asking.
        if (value != null && !value.isBlank()) {
            SpendContext.set(SpendSubject.user(value));
        }
    }

    @Override
    public void reset() {
        // This runs on the SAME pooled boundedElastic thread immediately after each propagated
        // task completes - it is what actually prevents a userId leaking into the next unrelated
        // request that happens to reuse this thread from the pool. JwtAuthFilter's
        // .doFinally(s -> SearchContext.clear()) is defense-in-depth only (it runs on the
        // filter-chain-completion thread, not necessarily every worker thread a tool call
        // touched) - do not remove this reset() thinking that doFinally covers it.
        SearchContext.removeUserId();
        SpendContext.clear();
    }
}
