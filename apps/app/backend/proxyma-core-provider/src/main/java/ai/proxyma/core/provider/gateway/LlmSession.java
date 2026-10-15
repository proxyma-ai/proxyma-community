package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.common.usage.TokenUsage;

import ai.proxyma.core.provider.gateway.LlmGateway.LlmResult;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * A model resolved ONCE and called many times - what a ReAct loop needs.
 *
 * <p>{@link LlmGateway#chat} resolves a role per call, which is right for the single-shot callers.
 * An agent loop is not single-shot: ChatService runs up to N iterations against the same model with
 * the same ceiling and tone, and a workflow-overridden model is built UNCACHED, so resolving per
 * iteration would rebuild a ChatModel on every turn of the loop.
 *
 * <p>The tool list is the one thing that changes between iterations, so it is a parameter of the
 * CALL rather than of the session: a readSkill earlier in the same turn must unlock its connector's
 * tools before the very next request, not on the next user message. Each call sets the tools on the
 * session's one options object, which Spring AI reads at request time - so the options are not
 * constant, whatever their type suggests.
 *
 * <p>NOT THREAD-SAFE, for that reason, and does not need to be: a session belongs to one turn on one
 * thread.
 */
public interface LlmSession {

    /** The provider name serving this session - what a rate is keyed on. */
    String provider();

    /** The model name serving this session. */
    String model();

    /** Whether this is a role's dedicated model rather than the main chat one. */
    boolean dedicated();

    /**
     * The serving provider's PROTOCOL name ("ANTHROPIC", "OPENAI_COMPATIBLE") - for diagnostics
     * labels, which may name a protocol but never an instance or a model. "unknown" when a session
     * implementation cannot say.
     */
    default String protocol() { return "unknown"; }

    /**
     * Whether this session asked for ONE-HOUR prompt caching, so its cache writes are priced as
     * one-hour writes - see TokenUsage.longCacheWriteTokens. False where nothing was asked.
     */
    default boolean longPromptCache() { return false; }

    /**
     * Whether an agent loop should STREAM this session's calls, or make one blocking call per step.
     * Every protocol streams; a LiteLLM instance can be told not to, for a deployment that answers
     * stream=true with a single JSON body once tools are present (ResolvedProvider.streams).
     */
    default boolean streams() { return true; }

    /** A usage this session reported, with its cache writes marked by the lifetime it asked for. */
    default TokenUsage priced(TokenUsage usage) {
        return usage != null && longPromptCache() ? usage.withLongCacheWrites() : usage;
    }

    /** One blocking completion, advertising the given tools. */
    LlmResult chat(List<Message> messages, List<ToolCallback> tools);

    /**
     * One streamed completion, advertising the given tools.
     *
     * <p>Returns the raw Flux rather than an LlmResult: a stream's usage is only known once it has
     * finished, and every caller here already aggregates the chunks its own way - buffering text for
     * the UI, watching for tool calls, or timing out on silence.
     */
    Flux<ChatResponse> stream(List<Message> messages, List<ToolCallback> tools);
}
