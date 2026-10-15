package ai.proxyma.core.common.diagnostics;

/**
 * Every operation whose duration the diagnostics report can carry. A closed list on purpose: an
 * operation name is on the wire, so it has to be something a person reviewing the report can read
 * and something no caller can build out of a string it happened to have in scope.
 */
public enum DiagOp {
    /** JVM start to ApplicationReadyEvent. */
    APP_STARTUP("app.startup"),

    /** Fetching one item's bytes from its source. Labels: connector, fileClass. */
    INGEST_DOWNLOAD("ingest.download"),
    /** Converting one item to Markdown (markitdown, OCR fallback included). Labels: connector, fileClass, size. */
    INGEST_CONVERT("ingest.convert"),
    /** One OCR run. Labels: engine, fileClass. */
    INGEST_OCR("ingest.ocr"),
    /** Chunking and embedding one item. Labels: connector, size. */
    INGEST_EMBED("ingest.embed"),
    /** One item end to end, success or failure. Labels: connector, fileClass, size, outcome. */
    INGEST_ITEM("ingest.item"),
    /** Knowledge-graph extraction of one document. Labels: outcome. */
    GRAPH_EXTRACT("graph.extract"),
    /** One reconcile pass of one source. Labels: connector, mode (full/incremental). */
    SYNC_RECONCILE("sync.reconcile"),

    SEARCH_EXPANSION("search.expansion"),
    SEARCH_DENSE("search.dense"),
    SEARCH_BM25("search.bm25"),
    SEARCH_RERANK("search.rerank"),
    /** The knowledge-graph channel of one search. Labels: found (on/off - whether it ranked anything). */
    SEARCH_GRAPH("search.graph"),
    /** Labels: expansion, rerank (on/off). */
    SEARCH_TOTAL("search.total"),

    /** Labels: phase (rules, examples, retrieval, classifier, total). */
    GUARDRAIL_PHASE("guardrail.phase"),

    /** Time to the first streamed token of a chat turn. Labels: protocol. */
    CHAT_TTFT("chat.ttft"),
    /** A whole chat turn, tool calls included. Labels: protocol, outcome. */
    CHAT_TOTAL("chat.total"),
    /** One model call through the gateway. Labels: role, protocol, mode (call/stream), outcome. */
    LLM_CALL("llm.call"),
    /** One tool execution. Labels: tool, outcome. */
    TOOL_CALL("tool.call");

    private final String wireName;

    DiagOp(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
