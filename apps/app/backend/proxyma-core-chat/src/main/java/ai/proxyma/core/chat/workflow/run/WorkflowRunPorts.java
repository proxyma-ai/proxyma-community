package ai.proxyma.core.chat.workflow.run;

import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.gateway.LlmGateway;
import org.springframework.ai.chat.messages.UserMessage;
import ai.proxyma.core.provider.gateway.LlmCaller;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.provider.usage.TokenUsageExtractor;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.service.WriteToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The real implementations of the interpreter's four ports.
 *
 * <p>Kept together because they are one story - how a graph node reaches the rest of the
 * application - and separate from {@link WorkflowInterpreter} because that class must stay runnable
 * against stubs, which is the only way its branching, loops and pause semantics get verified in a
 * workspace with no test suite.
 */
public final class WorkflowRunPorts {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunPorts.class);

    private WorkflowRunPorts() {}

    /**
     * Invokes a registered tool by name.
     *
     * <p>THE WRITE GUARD SURVIVES LOSING THE MODEL. A Tool node's arguments were fixed by an
     * author rather than chosen by a model, which changes who decided - not what the call does. So
     * a write tool goes through {@code WriteToolRegistry} and {@code McpGuardService} exactly as a
     * model-issued call does in ChatService. An author is as capable of writing
     * {@code deleteRepository} as a model is.
     *
     * <p>What it does NOT do is prompt per call. A graph says where it stops to ask - that is what
     * an Approval node is - and turning every write into a modal would make a deterministic
     * procedure unrunnable. The consent check still applies; only the per-call prompt is replaced.
     */
    public static final class RegistryToolPort implements WorkflowPorts.ToolPort {

        private final Map<String, ToolCallback> callbacks;
        private final McpGuardService guard;
        private final ObjectMapper mapper;
        private final String userId;

        public RegistryToolPort(List<ToolCallback> callbacks, McpGuardService guard,
                                ObjectMapper mapper, String userId) {
            this.callbacks = new ConcurrentHashMap<>();
            callbacks.forEach(cb -> this.callbacks.put(cb.getToolDefinition().name(), cb));
            this.guard = guard;
            this.mapper = mapper;
            this.userId = userId;
        }

        @Override
        public Map<String, Object> invoke(String toolName, Map<String, Object> arguments) {
            ToolCallback callback = callbacks.get(toolName);
            if (callback == null) {
                throw new WorkflowPorts.NodeFailedException(
                        "'" + toolName + "' is not a registered tool in this build.");
            }
            // Per CALL, not per tool: a GET through callJiraApi needs read access only, which the
            // tool checks itself - refusing it for want of WRITE failed a read-only workflow node.
            if (WriteToolRegistry.isWriteCall(toolName, arguments) && guard != null) {
                String rejection = guard.checkWriteAllowed(toolName, userId);
                if (rejection != null) {
                    throw new WorkflowPorts.NodeFailedException(rejection);
                }
            }
            String result;
            try {
                result = callback.call(mapper.writeValueAsString(arguments == null ? Map.of() : arguments));
            } catch (Exception e) {
                throw new WorkflowPorts.NodeFailedException(
                        "'" + toolName + "' failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
            }
            return asMap(result, mapper);
        }

        /** Tools return a JSON string. The two that return raw text (read, grep) are wrapped rather
         *  than failed, so a graph can bind to {@code .text} for those exactly as the declared
         *  schemas say - see #177's note that 58 of 60 return a map and 2 do not. */
        private static Map<String, Object> asMap(String result, ObjectMapper mapper) {
            if (result == null || result.isBlank()) return Map.of();
            try {
                Object parsed = mapper.readValue(result, Object.class);
                if (parsed instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    return typed;
                }
                return Map.of("text", parsed);
            } catch (Exception e) {
                return Map.of("text", result);
            }
        }
    }

    /**
     * One model call per LLM or Classify node.
     *
     * <p>Built on {@code LlmGateway.chat} with an explicit model override rather than on
     * ChatService.react(). react() is
     * built around one conversation, a memory, a system prompt resolved once and condensation, and
     * per-component memory deletes the reason for all four.
     *
     * <p>TOOLS INSIDE AN LLM NODE ARE REFUSED, for now, with a message saying so. The engine
     * proposal is clear that such a node is a miniature ReAct loop needing its own iteration
     * budget, and the honest way to build it is on SubAgentService's loop, which is the next piece
     * of work rather than a corner of this one. The validator already warns when a node declares
     * tools, so this refuses something an author was told about.
     */
    public static final class ProviderModelPort implements WorkflowPorts.ModelPort {

        private final LlmGateway llmGateway;
        private final ModelPricingService pricing;
        // The run's owner - who every model call in it is billed to. Null for an ownerless run,
        // which SpendSubject.owner files under SYSTEM.
        private final String ownerId;

        public ProviderModelPort(LlmGateway llmGateway, ModelPricingService pricing, String ownerId) {
            this.llmGateway = llmGateway;
            this.pricing = pricing;
            this.ownerId = ownerId;
        }

        @Override
        public WorkflowPorts.ModelReply complete(WorkflowPorts.ModelCall call) {
            if (!call.tools().isEmpty()) {
                throw new WorkflowPorts.NodeFailedException(
                        "This step is set to use tools inside a model call, which this build cannot "
                                + "run yet. Express the tool calls as Tool nodes, or clear the tool "
                                + "list on this step.");
            }
            // A node stores the model its author picked, so the pair travels as DATA - either half
            // unset means "whatever chat uses", which the gateway resolves. Building the model,
            // choosing CONCISE and applying the chat role's output ceiling all moved there.
            LlmGateway.LlmResult result;
            try {
                // Blocking, not streamed: a workflow step's text is a value bound by later nodes,
                // not something a reader watches arrive token by token. The one place streaming
                // matters is a chat-invoked run's final answer, which is the caller's business.
                // No override at all when the node names neither half: the gateway's cached chat
                // model then serves it, where an override builds a new client stack per step.
                LlmGateway.ModelOverride override = call.provider() == null && call.model() == null
                        ? null : new LlmGateway.ModelOverride(call.provider(), call.model());
                result = llmGateway.chat(LlmCaller.forOwner(LlmRole.WORKFLOW, ownerId), List.of(new UserMessage(call.instruction())),
                        override);
            } catch (RuntimeException e) {
                throw new WorkflowPorts.NodeFailedException(
                        "This step's model call failed: "
                                + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
            }

            TokenUsage usage = result.usage();
            double cost = pricing == null ? 0 : pricing.costUsd(result.provider(), result.model(), usage);
            return new WorkflowPorts.ModelReply(result.text(),
                    usage.inputTokens(), usage.outputTokens(), cost);
        }
    }

    /**
     * Blocks a run at an Approval node until someone answers over the run's own endpoint.
     *
     * <p>One latch per waiting node. The timeout exists because a run holds a thread: an approval
     * nobody ever answers would otherwise park it for the life of the process, and a run that
     * quietly waits forever is indistinguishable from one that hung.
     */
    public static final class LatchApprovalPort implements WorkflowPorts.ApprovalPort {

        /** Long enough for someone to come back from lunch, short enough that a forgotten run
         *  releases its thread the same day. Matches the spirit of ChatService's own 30-minute
         *  write-approval wait, lengthened because a workflow is not a live conversation. */
        private static final Duration WAIT = Duration.ofHours(2);

        private final Map<String, Pending> pending = new ConcurrentHashMap<>();
        private final RunControl control;

        public LatchApprovalPort(RunControl control) {
            this.control = control;
        }

        private static final class Pending {
            final CountDownLatch latch = new CountDownLatch(1);
            volatile WorkflowPorts.ApprovalDecision decision;
        }

        @Override
        public WorkflowPorts.ApprovalDecision awaitDecision(String nodeId, String prompt) {
            Pending waiting = new Pending();
            pending.put(nodeId, waiting);
            try {
                if (!waiting.latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.info("[WorkflowRun] approval for node {} timed out after {}", nodeId, WAIT);
                    control.stop();
                    return WorkflowPorts.ApprovalDecision.DENIED;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                control.stop();
                return WorkflowPorts.ApprovalDecision.DENIED;
            } finally {
                pending.remove(nodeId);
            }
            return waiting.decision == null ? WorkflowPorts.ApprovalDecision.DENIED : waiting.decision;
        }

        /** @return false when nothing was waiting - an answer to a question already resolved, which
         *          a double-click produces and which is not an error worth reporting as one. */
        public boolean resolve(String nodeId, WorkflowPorts.ApprovalDecision decision) {
            Pending waiting = pending.get(nodeId);
            if (waiting == null) return false;
            waiting.decision = decision;
            waiting.latch.countDown();
            return true;
        }

        /** Answers every approval still waiting DENIED - the run was stopped while it waited. */
        public void cancelAll() {
            pending.values().forEach(waiting -> {
                waiting.decision = WorkflowPorts.ApprovalDecision.DENIED;
                waiting.latch.countDown();
            });
        }
    }

}
