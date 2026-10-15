package ai.proxyma.core.tools.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compares what a tool actually returned against what its {@link ToolResult} says it returns, and
 * says so when they disagree.
 *
 * <p>This is the answer to the one real objection to declaring output schemas by hand: the
 * declaration sits next to the method, so an edit to the method can leave it behind, and a
 * workflow binding to a key that quietly stopped existing fails at a distance from the change that
 * caused it. A declaration that is merely WRITTEN can rot silently; one that is checked against
 * reality on every boot cannot rot for long.
 *
 * <p>Deliberately advisory - it logs and never fails the call. A result that does not match its
 * declaration is still a perfectly good result to hand a model, and taking a user's tool call down
 * over a documentation defect would be a far worse bug than the one being reported.
 *
 * <h2>Why it stops checking</h2>
 *
 * Drift is caused by editing code, not by calling a tool, so the first handful of calls after a
 * boot prove as much as the ten thousandth would. Each tool gets {@link #CHECKS_PER_TOOL} checks
 * per process and is then left alone, which keeps a JSON parse of every tool result off a hot path
 * that already serialises large payloads. The cost of the cap is that a rarely-taken branch (an
 * error path of a tool called mostly successfully) may not be sampled before the budget runs out.
 */
public class ToolResultDriftCheck implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ToolResultDriftCheck.class);

    private static final int CHECKS_PER_TOOL = 20;

    // Shared across every wrapped callback: the budget is per TOOL, and one instance of this class
    // wraps one tool, so the counters have to live somewhere both instances and calls can see.
    private static final Map<String, AtomicInteger> BUDGET = new ConcurrentHashMap<>();
    // One line per distinct problem, not per occurrence - a mismatching tool called in a loop
    // would otherwise bury the rest of the log in copies of one finding.
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback delegate;
    private final ToolResultSchemaRegistry registry;

    public ToolResultDriftCheck(ToolCallback delegate, ToolResultSchemaRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        String result = delegate.call(toolInput);
        check(result);
        return result;
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String result = delegate.call(toolInput, toolContext);
        check(result);
        return result;
    }

    private void check(String result) {
        try {
            String name = delegate.getToolDefinition().name();
            ToolResultSchemaRegistry.Declaration declared = registry.declarationFor(name).orElse(null);
            // No declaration is already reported once, loudly, at startup - see
            // ToolResultSchemaRegistry.scan(). Repeating it per call would add nothing.
            if (declared == null || declared.rawText() || result == null) return;
            if (BUDGET.computeIfAbsent(name, k -> new AtomicInteger(CHECKS_PER_TOOL)).decrementAndGet() < 0) return;

            JsonNode root = MAPPER.readTree(result);
            if (!root.isObject()) {
                report(name, "declares an object result but returned " + root.getNodeType());
                return;
            }

            List<String> undeclaredKeys = new ArrayList<>();
            for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                if (!declared.allKeys().contains(key)) undeclaredKeys.add(key);
            }
            if (!undeclaredKeys.isEmpty()) {
                report(name, "returned key(s) its @ToolResult does not declare: " + undeclaredKeys);
            }

            // The other direction, and the one that actually breaks a binding: a key promised on
            // every success that this success did not carry. Only meaningful on a successful call -
            // a failure is entitled to carry nothing but the error.
            boolean succeeded = !declared.envelope()
                    || (root.path("success").isBoolean() && root.path("success").asBoolean());
            if (succeeded) {
                List<String> missing = new ArrayList<>();
                for (String key : declared.alwaysKeys()) {
                    if (!"success".equals(key) && !root.has(key)) missing.add(key);
                }
                if (!missing.isEmpty()) {
                    report(name, "succeeded without key(s) its @ToolResult marks always=true: " + missing);
                }
            }
        } catch (Exception ignored) {
            // A tool that returns something unparseable is not this class's problem to raise, and
            // checking a declaration must never be able to fail a call.
        }
    }

    private static void report(String toolName, String problem) {
        if (REPORTED.add(toolName + " " + problem)) {
            log.warn("[ToolSchema] '{}' {}", toolName, problem);
        }
    }
}
