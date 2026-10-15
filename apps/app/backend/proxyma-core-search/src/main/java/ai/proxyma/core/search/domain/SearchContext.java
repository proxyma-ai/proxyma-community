package ai.proxyma.core.search.domain;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SearchContext {

    // Reactor Context key used to bridge USER_ID across Schedulers.boundedElastic() hops (e.g.
    // spring-ai-mcp's Mono.fromCallable(...).subscribeOn(...) tool-execution wrapper) via
    // io.micrometer's automatic ThreadLocal-to-Reactor-Context propagation. See
    // SearchContextUserIdAccessor and SearchContextPropagationConfig in this module.
    public static final String REACTOR_CONTEXT_KEY = "proxyma.searchContext.userId";

    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    // Set by ChatService.callTools() to the same AtomicBoolean threaded through that turn's
    // react() loop - lets tool-internal work (e.g. RerankingService's per-candidate scoring
    // loop) notice a Stop request between LLM calls instead of running to completion regardless.
    private static final ThreadLocal<AtomicBoolean> ABORT_FLAG = new ThreadLocal<>();
    // The trackingId of the tool call currently executing on this thread, set by
    // ChatService.callTool() around the callback invocation. Only DelegateTool reads it, to stamp
    // its sub-agent's events with a parentCallId the UI can nest under this exact row. Null for
    // MCP tool calls made outside the chat ReAct loop, which have no row to nest under.
    private static final ThreadLocal<String> TOOL_CALL_ID = new ThreadLocal<>();
    // Identifies the ONE chat turn currently in flight on this thread - minted per
    // ChatService.chat() call and re-set on every tool worker thread alongside CONVERSATION_ID.
    // Exists so spend a turn caused but did not itself make (a delegated sub-agent's calls, a
    // search's query-expansion/reranking calls) can be staged against that specific turn rather
    // than against the conversation at large: a turn the user STOPS leaves its sub-agent winding
    // down on its own thread, and without a turn identity that late spend was folded into whatever
    // turn came next. See SmartChatMemory.addAuxiliarySpend(). Null outside the chat ReAct loop
    // (MCP tool calls, /api/v1/search), which is the signal that there is no turn to charge.
    private static final ThreadLocal<String> TURN_ID = new ThreadLocal<>();

    private SearchContext() {}

    public static void setTurnId(String turnId) { TURN_ID.set(turnId); }
    public static String getTurnId()            { return TURN_ID.get(); }

    public static void setToolCallId(String toolCallId) { TOOL_CALL_ID.set(toolCallId); }
    public static String getToolCallId()                { return TOOL_CALL_ID.get(); }
    public static void removeToolCallId()               { TOOL_CALL_ID.remove(); }

    public static void setConversationId(String conversationId) { CONVERSATION_ID.set(conversationId); }
    public static String getConversationId()                    { return CONVERSATION_ID.get(); }

    public static void setUserId(String userId) { USER_ID.set(userId); }
    public static String getUserId()            { return USER_ID.get(); }
    // Clears only USER_ID - kept separate from clear() (below) which also resets
    // CONVERSATION_ID/ABORT_FLAG. Used by SearchContextUserIdAccessor.reset(), which the
    // automatic context-propagation machinery calls per-task on the pooled worker thread; it
    // must not touch the other two ThreadLocals since it has no knowledge of them.
    public static void removeUserId() { USER_ID.remove(); }

    public static void setAbortFlag(AtomicBoolean abortFlag) { ABORT_FLAG.set(abortFlag); }
    // Never null-unsafe to call .get() on directly - callers should treat a null return (no
    // abort flag set for this thread, e.g. an MCP tool call outside the chat ReAct loop) the
    // same as "not aborted".
    public static AtomicBoolean getAbortFlag() { return ABORT_FLAG.get(); }
    public static boolean isAborted() {
        AtomicBoolean flag = ABORT_FLAG.get();
        return flag != null && flag.get();
    }

    public static void clear() {
        CONVERSATION_ID.remove();
        USER_ID.remove();
        ABORT_FLAG.remove();
        TOOL_CALL_ID.remove();
        TURN_ID.remove();
    }
}
