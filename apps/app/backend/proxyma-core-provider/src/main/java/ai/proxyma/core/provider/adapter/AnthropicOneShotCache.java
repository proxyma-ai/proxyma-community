package ai.proxyma.core.provider.adapter;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * NO ANTHROPIC CALL GOES OUT UNCACHED (2026-11-14, the maintainer: "ensure all providers are handled correctly, always
 * avoid non-cache calls").
 *
 * <p>A call made with no options of its own - a conversation's title, the knowledge graph's extraction, the guardrail,
 * query expansion, reranking, OCR, a workflow step, the YouTube team's calls - and a session opened with plain options
 * (condensation) asked Anthropic to cache nothing: the YouTube team's drafting calls, about 10,000 tokens each on Claude
 * Haiku, never read a cache. A default on the model cannot fix it - Spring AI 1.1.7 does not carry the default options'
 * cache settings into a request that has no options (AnthropicChatModel.buildRequestPrompt's else branch), and a
 * request with generic options arrives with caching DISABLED, which wins over the default.
 *
 * <p>So the prompt is given its cache settings here, before Spring AI sees it. A request whose options name a cache
 * strategy - a turn's own (AnthropicAdapter.buildChatOptions: the conversation's history) - is left as it is. Any other
 * is ONE-SHOT: its system prompt is what repeats, its message what is new, so the system prompt alone is cached, for
 * five minutes ({@link #ONE_SHOT}), and the message is never written to a cache nothing will read. Anthropic ignores a
 * breakpoint on a prompt below its minimum size, at no cost.
 */
final class AnthropicOneShotCache implements ChatModel {

    static final AnthropicCacheOptions ONE_SHOT = AnthropicCacheOptions.builder()
            .strategy(AnthropicCacheStrategy.SYSTEM_ONLY).build();

    private final ChatModel delegate;

    AnthropicOneShotCache(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(cached(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(cached(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /** The prompt with its cache settings: its own when it names a strategy, else the one-shot ones. */
    static Prompt cached(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options instanceof AnthropicChatOptions own && own.getCacheOptions() != null
                && own.getCacheOptions().getStrategy() != AnthropicCacheStrategy.NONE) {
            return prompt;
        }
        AnthropicChatOptions withCache;
        if (options == null) {
            // Merged with the model's defaults by Spring AI, as a request with no options would have been.
            withCache = AnthropicChatOptions.builder().build();
        } else if (options instanceof AnthropicChatOptions own) {
            withCache = AnthropicChatOptions.fromOptions(own);
        } else if (options instanceof ToolCallingChatOptions tools) {
            withCache = ModelOptionsUtils.copyToTarget(tools, ToolCallingChatOptions.class, AnthropicChatOptions.class);
            // What JSON copying leaves out, as Spring AI's own merge carries it.
            withCache.setToolCallbacks(tools.getToolCallbacks());
            withCache.setToolNames(tools.getToolNames());
            withCache.setInternalToolExecutionEnabled(tools.getInternalToolExecutionEnabled());
            withCache.setToolContext(tools.getToolContext());
        } else {
            withCache = ModelOptionsUtils.copyToTarget(options, ChatOptions.class, AnthropicChatOptions.class);
        }
        withCache.setCacheOptions(ONE_SHOT);
        return new Prompt(prompt.getInstructions(), withCache);
    }
}
