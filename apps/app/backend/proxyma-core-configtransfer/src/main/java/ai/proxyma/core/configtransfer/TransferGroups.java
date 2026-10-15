package ai.proxyma.core.configtransfer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The groups a person picks from when exporting or importing - the maintainer's request,
 * 2026-09-27: "it should allow the user to choose what is exported and imported."
 *
 * <p>Named after the Settings tabs a person knows, not after the stores behind them. "Agents" is
 * two settings prefixes and the chat system prompt file; "Usage & Cost" is the quota prefix and the
 * model pricing table. The ids are the wire format - the frontend holds the labels - so an id is
 * never renamed.
 *
 * <p>A settings prefix that no group names falls into {@link #OTHER}, so a prefix added later still
 * travels with the rest rather than being silently left out of every file.
 */
public final class TransferGroups {

    public static final String PROVIDERS = "providers";
    public static final String AGENTS = "agents";
    public static final String SKILLS = "skills";
    public static final String WORKFLOWS = "workflows";
    public static final String RETRIEVAL = "retrieval";
    public static final String DOCUMENTS = "documents";
    public static final String CONNECTORS = "connectors";
    public static final String NETWORK = "network";
    public static final String USAGE = "usage";
    public static final String GUARDRAILS = "guardrails";
    public static final String RETENTION = "retention";
    public static final String UPDATES = "updates";
    public static final String PRIVACY = "privacy";
    public static final String ADMINISTRATION = "administration";
    public static final String OTHER = "other";

    /** Every group, in the order the Settings rail shows them. */
    public static final List<String> ALL = List.of(CONNECTORS, SKILLS, WORKFLOWS, PROVIDERS, AGENTS,
            RETRIEVAL, DOCUMENTS, GUARDRAILS, USAGE, NETWORK, RETENTION, UPDATES, PRIVACY,
            ADMINISTRATION, OTHER);

    private static final Map<String, String> PREFIX_GROUP = Map.ofEntries(
            // With the Search tab, where it is set (2026-10-10), not with AI Providers.
            Map.entry("web-search", RETRIEVAL),
            Map.entry("chat", AGENTS),
            Map.entry("sub-agent", AGENTS),
            Map.entry("embedding", RETRIEVAL),
            Map.entry("query-expansion", RETRIEVAL),
            Map.entry("reranking", RETRIEVAL),
            Map.entry("graph", RETRIEVAL),
            Map.entry("doc-conversion", DOCUMENTS),
            Map.entry("ocr", DOCUMENTS),
            Map.entry("jira", CONNECTORS),
            Map.entry("confluence", CONNECTORS),
            Map.entry("bitbucket", CONNECTORS),
            Map.entry("svn", CONNECTORS),
            Map.entry("network-share", CONNECTORS),
            Map.entry("m365", CONNECTORS),
            Map.entry("sharepoint", CONNECTORS),
            Map.entry("teams", CONNECTORS),
            Map.entry("proxy", NETWORK),
            Map.entry("quota", USAGE),
            Map.entry("guardrail", GUARDRAILS),
            Map.entry("retention", RETENTION),
            Map.entry("updates", UPDATES),
            Map.entry("privacy", PRIVACY),
            Map.entry("storage", ADMINISTRATION),
            Map.entry("backup-schedule", ADMINISTRATION),
            Map.entry("maintenance-announcement", ADMINISTRATION));

    private TransferGroups() {}

    /** The group a settings prefix belongs to. */
    public static String ofPrefix(String prefix) {
        return PREFIX_GROUP.getOrDefault(prefix, OTHER);
    }

    /**
     * The groups a request asked for. Null or empty means all of them, which is what a caller that
     * predates the choice sends - and an unknown id is simply never matched.
     */
    public static Set<String> selection(List<String> requested) {
        return requested == null || requested.isEmpty() ? Set.copyOf(ALL) : Set.copyOf(requested);
    }
}
