package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who pays for each kind of model call: the USER it was made for, or the PLATFORM - since
 * 2026-09-29, set by a super admin on the Administration page.
 *
 * <p>A service charged to the user counts toward that user's quota and is refused once it is used
 * up, on every path it runs through (DefaultLlmGateway). A service charged to the platform never
 * touches a user's quota; it counts toward the deployment's monthly limit only. Both always count
 * toward that limit, and every call is in the per-call log with the person it was for, whichever
 * pays.
 *
 * <p>Chat is charged to the user by default and everything else to the platform: indexing a
 * user's own sources, titles and condensation are the deployment's cost of running, and a user
 * whose chat quota runs out should not find their sources stop syncing. One field per LlmRole,
 * named after it, holding "user" or "platform".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CostCenterSettings {

    public static final String PREFIX = "cost-centers";

    public static final String USER = "user";
    public static final String PLATFORM = "platform";

    @JsonProperty("chat") private String chat = USER;
    @JsonProperty("sub-agent") private String subAgent = PLATFORM;
    @JsonProperty("title") private String title = PLATFORM;
    @JsonProperty("condensation") private String condensation = PLATFORM;
    @JsonProperty("query-expansion") private String queryExpansion = PLATFORM;
    @JsonProperty("rerank") private String rerank = PLATFORM;
    @JsonProperty("embedding") private String embedding = PLATFORM;
    @JsonProperty("ocr") private String ocr = PLATFORM;
    @JsonProperty("graph") private String graph = PLATFORM;
    @JsonProperty("space-fact") private String spaceFact = PLATFORM;
    @JsonProperty("workflow") private String workflow = PLATFORM;
    @JsonProperty("guardrail") private String guardrail = PLATFORM;

    /** Role name (LlmRole.name()) to "user" or "platform", in the order the Admin page lists them. */
    public Map<String, String> byRole() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("CHAT", chat);
        m.put("SUB_AGENT", subAgent);
        m.put("TITLE", title);
        m.put("CONDENSATION", condensation);
        m.put("QUERY_EXPANSION", queryExpansion);
        m.put("RERANK", rerank);
        m.put("EMBEDDING", embedding);
        m.put("OCR", ocr);
        m.put("GRAPH", graph);
        m.put("SPACE_FACT", spaceFact);
        m.put("WORKFLOW", workflow);
        m.put("GUARDRAIL", guardrail);
        return m;
    }

    public String getChat() { return chat; }
    public void setChat(String v) { chat = v; }
    public String getSubAgent() { return subAgent; }
    public void setSubAgent(String v) { subAgent = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public String getCondensation() { return condensation; }
    public void setCondensation(String v) { condensation = v; }
    public String getQueryExpansion() { return queryExpansion; }
    public void setQueryExpansion(String v) { queryExpansion = v; }
    public String getRerank() { return rerank; }
    public void setRerank(String v) { rerank = v; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String v) { embedding = v; }
    public String getOcr() { return ocr; }
    public void setOcr(String v) { ocr = v; }
    public String getGraph() { return graph; }
    public void setGraph(String v) { graph = v; }
    public String getSpaceFact() { return spaceFact; }
    public void setSpaceFact(String v) { spaceFact = v; }
    public String getWorkflow() { return workflow; }
    public void setWorkflow(String v) { workflow = v; }
    public String getGuardrail() { return guardrail; }
    public void setGuardrail(String v) { guardrail = v; }
}
