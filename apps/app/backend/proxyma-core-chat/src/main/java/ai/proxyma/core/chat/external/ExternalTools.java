package ai.proxyma.core.chat.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adds a person's MCP server tools to what a model call advertises - the one rule the main agent and
 * a sub-agent share, so the two cannot come to offer different things.
 */
public final class ExternalTools {

    private static final Logger log = LoggerFactory.getLogger(ExternalTools.class);

    private ExternalTools() {}

    /**
     * {@code advertised} plus the tools of each MCP server whose skill is among {@code unlocked}.
     * A name already advertised is never added twice - Spring AI refuses the whole call over a
     * duplicate. A source that fails to answer adds nothing: a server that is down costs its own
     * tools, never the turn.
     */
    public static List<ToolCallback> merge(ExternalToolSource source, List<ToolCallback> advertised,
                                           Set<String> unlocked, String userId) {
        if (source == null || unlocked == null || unlocked.isEmpty()) return advertised;
        List<ToolCallback> extra;
        try {
            extra = source.toolsFor(userId, unlocked);
        } catch (RuntimeException e) {
            log.warn("[Chat] MCP server tools could not be listed: {}", e.getMessage());
            return advertised;
        }
        if (extra == null || extra.isEmpty()) return advertised;
        Set<String> names = new HashSet<>();
        advertised.forEach(cb -> names.add(cb.getToolDefinition().name()));
        List<ToolCallback> out = new ArrayList<>(advertised);
        for (ToolCallback cb : extra) {
            if (names.add(cb.getToolDefinition().name())) out.add(cb);
        }
        return out;
    }
}
