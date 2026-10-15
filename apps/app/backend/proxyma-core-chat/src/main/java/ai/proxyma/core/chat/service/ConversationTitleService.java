package ai.proxyma.core.chat.service;

import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.chat.event.ChatEvent;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmCaller;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.provider.usage.TokenUsageExtractor;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.service.SettingsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

// Replaces the naive truncated title SmartChatMemory.ensureConversation() sets on a brand-new
// conversation with a real LLM-generated one, once the call resolves. A separate bean (not a
// method on ChatService/SmartChatMemory) because @Async only takes effect through Spring's proxy
// on an external call - a self-invoked method would silently run synchronously and add this
// call's latency to the main ReAct loop, which is exactly what this must avoid.
//
// Runs on the SUB-AGENT's model whenever one is configured, falling back to the main agent's only
// when it isn't. Naming a conversation from one short message is exactly the cheap, self-contained
// job sub-agents exist for, and it keeps the title call off the main (usually larger, often
// GPU-bound) model that the user's actual answer is waiting on - which is what made the earlier
// ordering fix necessary in the first place (see ChatService's call site).
//
// It only replaces that naive title when there is something to replace it WITH: a message short
// enough to be its own title is left alone and costs no provider call at all (see
// needsGeneratedTitle).
@Service
public class ConversationTitleService {

    private static final Logger log = LoggerFactory.getLogger(ConversationTitleService.class);
    private static final int TITLE_TIMEOUT_SECONDS = 30;
    private static final int MAX_TITLE_CHARS = 60;

    // Below this many words a message is used as its own title - "Kubernetes upgrade" already
    // names its topic. It was 8 until 2026-09-24, which left every short QUESTION as its own
    // title: "how many documents are indexed" sat in the history list as a question rather than
    // as "Indexed documents", which is what a list of conversations needs (maintainer's request).
    // A title call is a real billed call on the cheapest model configured, and a few tokens.
    private static final int MIN_WORDS_FOR_LLM = 3;

    // One job: name the TOPIC the message is about, the way a list of conversations names them.
    //
    // Two failures shape the rules. The model treating the message as a question to ANSWER from
    // pretrained knowledge - asked to title a message about an internal tool it had never seen, one
    // expanded the name into "... Smart Home Virtual Assistant Overview", a guess about a system it
    // knows nothing about. And, since 2026-09-24, the model DESCRIBING the request instead of
    // naming its subject - "The user is asking about their latest SAI tasks" - which is a sentence
    // about the conversation, not a title for it. The examples are the maintainer's own.
    //
    // The message goes in as "Message: ...\nTitle:" rather than bare, so the model is completing a
    // labeling exercise rather than replying to a user.
    private static final String TITLE_SYSTEM_PROMPT = """
            You name conversations. Reply with a title for the TOPIC of the message: 2 to 5 words, \
            a noun phrase, in the language of the message.

            - Name the subject, not the request. Never start with "The user", "User asks", \
            "Question about", "Request for", "How to" or "Asking".
            - Never answer the message, and add nothing that is not in it.
            - Keep names, acronyms and IDs exactly as written; never expand or guess at a term you do \
            not recognize.
            - No quotes, no trailing punctuation, no label.

            Message: what is my latest task on SAI project and what should I do next
            Title: SAI project tasks

            Message: how many documents are indexed
            Title: Indexed documents

            Message: give me information about ABC organization and business updates
            Title: ABC organization updates

            Output ONLY the title.""";

    private final LlmGateway llmGateway;
    private final ConversationRepository conversationRepository;
    private final ChatEventSink chatEventSink;
    private final ApplicationEventPublisher eventPublisher;
    private final SettingsService settingsService;
    private final ConversationUsageRecorder usageRecorder;

    public ConversationTitleService(LlmGateway llmGateway,
                                    ConversationRepository conversationRepository,
                                    ChatEventSink chatEventSink,
                                    ApplicationEventPublisher eventPublisher,
                                    SettingsService settingsService,
                                    ConversationUsageRecorder usageRecorder) {
        this.llmGateway = llmGateway;
        this.conversationRepository = conversationRepository;
        this.chatEventSink = chatEventSink;
        this.eventPublisher = eventPublisher;
        this.settingsService = settingsService;
        this.usageRecorder = usageRecorder;
    }

    // NO TRANSACTION AROUND IT, and not on the default executor, since 2026-10-03. It was
    // @Transactional, which held a database connection for the whole model call - up to thirty
    // seconds, once per new conversation - and it ran on the pool that delivers chat events. Each
    // read and write below is its own short transaction now.
    @Async("blockingWorkExecutor")
    public void generateTitleAsync(String conversationId, String firstMessage) {
        // Checked before anything else, so a short message costs neither a provider call nor the
        // lookup below.
        if (!needsGeneratedTitle(firstMessage)) {
            log.debug("[ConversationTitle] message is its own title, not generating one for conversationId={}",
                    conversationId);
            return;
        }
        // READ FROM THE CONVERSATION, not from the calling thread. @Async means this runs on a
        // pool thread that never saw the turn that asked for it, so the spend subject set at the
        // top of the turn is not here - a title billed to SYSTEM while the turn that caused it was
        // billed to a person is exactly the kind of quiet mis-attribution nobody would notice.
        // The conversation knows whose it is, and for a guest's conversation userId is null,
        // which SpendSubject.user maps to ANONYMOUS.
        SpendSubject subject = conversationRepository.findById(conversationId)
                .map(c -> SpendSubject.user(c.getUserId()))
                .orElse(SpendSubject.ANONYMOUS);
        generateTitle(conversationId, firstMessage, subject);
    }

    private void generateTitle(String conversationId, String firstMessage, SpendSubject subject) {
        try {
            // TITLE resolves to the sub-agent model, falling back to the main one - see the class
            // comment. Which of the two answered comes back on the result rather than being worked
            // out twice.
            if (!llmGateway.isAvailable(LlmRole.TITLE)) {
                log.debug("[ConversationTitle] no chat model configured, skipping conversationId={}", conversationId);
                return;
            }
            List<Message> messages = List.of(new SystemMessage(TITLE_SYSTEM_PROMPT),
                    new UserMessage("Message: " + firstMessage.trim() + "\nTitle:"));
            LlmGateway.LlmResult result = CompletableFuture
                    .supplyAsync(() -> llmGateway.chat(LlmCaller.of(LlmRole.TITLE, subject), messages),
                            BlockingWork.executor())
                    .get(TITLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            recordUsage(conversationId, result);
            String title = sanitize(result.text());
            if (title.isBlank()) return;
            // This column alone. A whole-row save would write back the token counters as they
            // were read, and the turn that asked for this title is updating them meanwhile.
            conversationRepository.updateTitle(conversationId, title);
            // Pushed live rather than left for the client's next refetch: this now runs at the
            // START of the first turn, so the turn is still streaming and its event sink still
            // open, which is exactly the window in which the history list can rename itself in
            // place. A client that has already disconnected simply never sees it - the row is
            // persisted either way, so the next fetch shows the same title.
            eventPublisher.publishEvent(new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.TITLE.value(), null, null, title));
        } catch (Exception e) {
            log.warn("[ConversationTitle] generation failed for conversationId={}", conversationId, e);
            // Falls back silently - the naive truncated title ensureConversation() already set stands.
        }
    }

    // Naming a conversation is a real, billed provider call, so it lands on the same cumulative
    // conversation counters (and, through them, the same per-user monthly quota) as the main agent's
    // own iterations and a delegated sub-agent's - it used to be the one LLM call in the chat feature
    // that was recorded nowhere at all, which made the usage meter under-report every conversation
    // by its first title call. Priced against whichever model actually served it: the sub-agent's
    // when one is configured (see the class comment), otherwise the main chat model's.
    private void recordUsage(String conversationId, LlmGateway.LlmResult result) {
        TokenUsage usage = result.usage();
        if (usage == TokenUsage.EMPTY) return;
        // PRICED BY PROVIDER NAME, which is a fix rather than a rename. This method used to pass
        // settings.getProvider() - an instance ID, since role settings store one in a field still
        // called "provider" - while every other caller passes the display NAME. Rates are keyed on
        // the name, so a lookup by id matched nothing and every title generated on this deployment
        // was costed at exactly zero from the day rates were introduced.
        usageRecorder.record(conversationId, usage, result.provider(), result.model());
    }

    // WHEN THE MODEL IS WORTH ASKING. A short message is already the best title it could be given -
    // and it is ALREADY the title, verbatim: SmartChatMemory.ensureConversation() set the trimmed
    // message, and its AUTO_TITLE_MAX_CHARS is the same 60 as MAX_TITLE_CHARS here. So the short
    // path is a skip, not a second write. Those two constants have to stay equal; a message falling
    // between them would arrive here assumed verbatim and be sitting in the list with an ellipsis.
    //
    // Two independent ways to be long enough to condense. Word count is the one that matters -
    // enough words that the request has structure to reduce to a topic. The character bound is
    // there for the case word count cannot see: a language written without spaces counts as one
    // word however long the question is, and would otherwise never be titled at all.
    private static boolean needsGeneratedTitle(String message) {
        if (message == null) return false;
        String trimmed = message.trim();
        return trimmed.length() > MAX_TITLE_CHARS || wordCount(trimmed) >= MIN_WORDS_FOR_LLM;
    }

    // Counted rather than split: a threshold test does not need the array, and this runs on the
    // first turn of every conversation.
    private static int wordCount(String text) {
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            boolean whitespace = Character.isWhitespace(text.charAt(i));
            if (!whitespace && !inWord) words++;
            inWord = !whitespace;
        }
        return words;
    }

    // What the prompt forbids and small models produce anyway: a "Title:" or "Topic:" label, and a
    // sentence about the request - "The user is asking about X" - where X is the title.
    private static final Pattern LABEL = Pattern.compile("(?i)^(title|topic)\\s*:\\s*");
    private static final Pattern DESCRIBES_THE_REQUEST = Pattern.compile(
            "(?i)^(the\\s+)?user('s)?\\s+(is\\s+)?(asking|asks|wants|would like|question(s|ing)?)"
            + "(\\s+(about|for|to know|to find out|regarding|on))?\\s*:?\\s*");

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    static String sanitize(String raw) {
        if (raw == null) return "";
        // First line only: a model that adds an explanation puts it after the title. A line that is
        // nothing but a label ("Title:") is skipped, or the real title on the next line is lost.
        String trimmed = raw.strip().lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !LABEL.matcher(l).replaceFirst("").isEmpty())
                .findFirst().orElse("");
        trimmed = unquote(LABEL.matcher(trimmed).replaceFirst(""));
        String subject = DESCRIBES_THE_REQUEST.matcher(trimmed).replaceFirst("");
        boolean cut = !subject.equals(trimmed);
        trimmed = subject;
        if (trimmed.length() >= 2 && (trimmed.startsWith("\"") && trimmed.endsWith("\"")
                || trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        while (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        // Capitalized only where a phrase was CUT, which leaves "their latest SAI tasks" starting
        // mid-sentence. A title the model wrote as it stands keeps its casing - "iOS build failures"
        // is right as it is.
        if (cut && !trimmed.isEmpty() && Character.isLowerCase(trimmed.charAt(0))) {
            trimmed = Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
        }
        return trimmed.length() <= MAX_TITLE_CHARS ? trimmed : trimmed.substring(0, MAX_TITLE_CHARS).trim();
    }
}
