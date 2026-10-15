package ai.proxyma.core.chat.service;
import ai.proxyma.core.chat.event.ChatEvent;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;
import ai.proxyma.core.chat.exception.ConversationAccessDeniedException;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.common.conversation.ConversationPaths;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.provider.gateway.LlmCaller;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.provider.gateway.LlmSession;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.usage.ChatSpendService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ChatMemory implementation backed by JPA repositories.
 *
 * Owns:
 * - Conversation lifecycle (create, delete, list, find)
 * - Per-conversation folder management (offload, orphan cleanup)
 * - Token-aware memory condensation via LLM summarizer
 *
 * On get(), if the conversation's last known real context-window size (Conversation.contextTokens
 * - see its javadoc) is at or above 80% of maxTokens, condense() is called first: the range from
 * the very first non-system message through (not including) the last USER message currently in
 * memory is replaced with a single LLM-written handoff summary, inserted back as its own USER
 * message (see CONDENSED_MARKER_PREFIX / isCondensedSummaryMarker()). A 'condense-start'/
 * 'condense-end' ChatEvent pair brackets the (potentially slow) LLM call so the chat UI can show
 * a "Condensing conversation..." indicator instead of looking stalled.
 *
 * Two kinds of message survive that replacement verbatim rather than being summarized, because a
 * paraphrase of them changes what the agent was TOLD rather than merely what it knows: a loaded
 * skill's body (see isSkillMarker) and the opening plan of a workflow run still in flight (see
 * isWorkflowPlan). Everything else in the range, including each completed step's brief and output,
 * goes through the summarizer - with a workflow-specific instruction added when a run is live, since
 * those outputs are the only surviving record of what the finished steps found.
 *
 * Ported from the earlier tenant build's SmartChatMemory.
 */
public class SmartChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(SmartChatMemory.class);

    private static final double CONDENSE_THRESHOLD = 0.8;
    private static final int MIN_RANGE_SIZE = 3;
    private static final int AUTO_TITLE_MAX_CHARS = 60;

    // Both the handoff summary (inserted as a USER message) and its synthetic acknowledgement
    // (inserted as an ASSISTANT message right after it - see condense() for why a bare summary
    // message can't be followed directly by the real next USER message) start with this exact
    // prefix, so a single check identifies either role's condensation artifact. Deliberately a
    // real, meaningful instruction to the model as well as a marker - it's never stripped before
    // being sent, so it also has to make sense as something the model actually reads.
    private static final String CONDENSED_MARKER_PREFIX = "[CONVERSATION SUMMARY";
    private static final String SUMMARY_USER_PREFIX = CONDENSED_MARKER_PREFIX
            + " - earlier messages were condensed to save context space; treat this as ground "
            + "truth for everything that happened before]\n";
    private static final String SUMMARY_ACK_TEXT = CONDENSED_MARKER_PREFIX
            + " ACKNOWLEDGED] Understood - I have the context above and will continue the "
            + "conversation from here.";

    // True for any USER/ASSISTANT message this class itself inserted during condensation, never
    // for real user or model content - used by ChatService.getMessagesWithToolCalls() to hide
    // both from the displayed transcript (see class javadoc and condense() below).
    public static boolean isCondensedSummaryMarker(String content) {
        return content != null && content.startsWith(CONDENSED_MARKER_PREFIX);
    }

    // A skill loaded into the conversation as message content rather than into the system prompt
    // (see ChatService.applyPickedSkills for why). Same USER + synthetic ASSISTANT acknowledgement
    // shape the condensation summary uses, and for the same reason: the real next message is a
    // USER message, and Anthropic rejects two consecutive same-role messages outright.
    //
    // The wording states only that the skill is active, not that the user asked for it this
    // message: the body stays in history for every later turn too, where that would be false. The
    // per-message emphasis lives on the user's own message instead (see ChatService.buildUserTurn's
    // skill-mention marker).
    private static final String SKILL_MARKER_PREFIX = "[SKILL";
    private static final String SKILL_BODY_PREFIX = SKILL_MARKER_PREFIX
            + " LOADED - this skill is active for this conversation; follow its instructions for the "
            + "rest of it, and do not spend a readSkill call re-reading it] ";
    private static final String SKILL_ACK_TEXT = SKILL_MARKER_PREFIX
            + " ACKNOWLEDGED] Understood - I will follow that skill's instructions from here.";

    public static List<Message> skillBodyMessages(String skillName, String body) {
        return List.of(
                new UserMessage(SKILL_BODY_PREFIX + skillName + "\n\n" + body),
                new AssistantMessage(SKILL_ACK_TEXT));
    }

    // What a RE-pick costs instead of a second copy of the body: a pointer back to instructions
    // already in this conversation. Deliberately not silent - the user picked the skill for this
    // message, and the body may be thousands of tokens up the history by now.
    public static List<Message> skillReminderMessages(String skillName) {
        return List.of(
                new UserMessage(SKILL_MARKER_PREFIX + " ALREADY LOADED] The user picked \"" + skillName
                        + "\" again for this message. Its instructions are already above - re-read them "
                        + "there and apply them; do not call readSkill for it."),
                new AssistantMessage(SKILL_ACK_TEXT));
    }

    // True for either half of the pair above. Two consumers: ChatService.getMessagesWithToolCalls()
    // hides them from the displayed transcript, and condense() below carries them PAST a
    // condensation instead of letting the summarizer paraphrase a skill's instructions away.
    public static boolean isSkillMarker(String content) {
        return content != null && content.startsWith(SKILL_MARKER_PREFIX);
    }

    // A workflow run's scaffolding, written into the conversation as ordinary messages for exactly
    // the reason skills are (above): the model has to READ this, and the system prompt is frozen
    // at conversation start. Same USER + synthetic ASSISTANT acknowledgement shape, same reason -
    // the next message is a USER message and Anthropic rejects two consecutive same-role messages.
    //
    // Three kinds of row carry the prefix, all hidden from the displayed transcript by
    // ChatService.getMessagesWithToolCalls(): the opening plan, each step's brief, and the
    // acknowledgements between them. What the user SEES of a run is their own "/Name" message and
    // the step table (see WorkflowRunSnapshot) - the scaffolding is machinery, not transcript.
    private static final String WORKFLOW_MARKER_PREFIX = "[WORKFLOW";
    // The opening plan, distinguished from the other two marker kinds because condense() treats it
    // differently from both - see isWorkflowPlan() below.
    private static final String WORKFLOW_PLAN_PREFIX = WORKFLOW_MARKER_PREFIX + " STARTED]";
    private static final String WORKFLOW_ACK_PREFIX = WORKFLOW_MARKER_PREFIX + " ACKNOWLEDGED]";
    private static final String WORKFLOW_ACK_TEXT = WORKFLOW_ACK_PREFIX
            + " Understood - I will wait for each step and answer only that step.";
    // Matches the step brief's own header, which is where the display layer learns a step's
    // position: an intermediate step's answer is hidden and the LAST step's answer is the turn's
    // visible reply, so "which step is this" has to survive into the persisted row.
    private static final Pattern WORKFLOW_STEP_HEADER =
            Pattern.compile("\\A\\[WORKFLOW STEP (\\d+)/(\\d+)\\b");

    /**
     * The plan, stated once before step 1 rather than repeated per step.
     *
     * <p>A run is ONE conversation thread (see proposals/workflows.md) - step N's brief is appended
     * after step N-1's answer, so anything said here is still in context for every later step. The
     * preamble is therefore "given to every step" by being in the thread, not by being re-sent with
     * each one, which on a five-step workflow would be five copies of it in the prompt.
     *
     * <p>The full step list is included deliberately: without it the model is handed step 1 with no
     * indication that anything follows, and answers the whole workflow in one go - which produces a
     * table whose remaining rows describe work already done.
     */
    public static List<Message> workflowOpeningMessages(String workflowName, String preamble,
                                                        List<String> stepTitles) {
        StringBuilder sb = new StringBuilder(WORKFLOW_MARKER_PREFIX)
                .append(" STARTED] The user invoked the workflow \"").append(workflowName)
                .append("\". It has ").append(stepTitles.size())
                .append(" steps, which you will be given one at a time as separate messages. ")
                .append("Answer ONLY the step you have just been given and then stop - do not run ")
                .append("ahead, and do not summarize steps that have not been given to you yet. ")
                .append("The plan is:\n");
        for (int i = 0; i < stepTitles.size(); i++) {
            sb.append(i + 1).append(". ").append(stepTitles.get(i)).append('\n');
        }
        if (preamble != null && !preamble.isBlank()) {
            sb.append("\nContext for every step:\n").append(preamble.strip()).append('\n');
        }
        return List.of(new UserMessage(sb.toString()), new AssistantMessage(WORKFLOW_ACK_TEXT));
    }

    /** One step's brief, as the scripted user turn that drives it. {@code number} is 1-based. */
    public static String workflowStepBrief(int number, int total, String title, String instruction) {
        return WORKFLOW_MARKER_PREFIX + " STEP " + number + "/" + total + " - " + title + "]\n"
                + (instruction == null ? "" : instruction.strip());
    }

    /** True for the opening plan, its acknowledgement, or any step brief. */
    public static boolean isWorkflowMarker(String content) {
        return content != null && content.startsWith(WORKFLOW_MARKER_PREFIX);
    }

    /**
     * {@code [number, total]} for a step brief, or null for anything else - including the other two
     * workflow marker kinds. The display layer uses it to decide whether the assistant text that
     * follows is an intermediate step's output (hidden, it belongs in the step row) or the last
     * step's (the turn's visible reply).
     */
    public static int[] workflowStepPosition(String content) {
        if (content == null) return null;
        Matcher m = WORKFLOW_STEP_HEADER.matcher(content);
        if (!m.find()) return null;
        try {
            return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * True for a run's opening plan - the standing instructions, not a step and not an
     * acknowledgement.
     *
     * <p>Condensation carries this one verbatim, for the reason it already carries a skill body
     * (see {@link #isSkillMarker}): it is what tells the model that steps arrive one at a time and
     * that it must answer only the step in front of it. Summarized away mid-run, the model is left
     * holding a step brief with no indication that anything follows it, and answers the rest of the
     * workflow in one go - which is the failure {@code workflowOpeningMessages} exists to prevent,
     * reappearing at whichever step the condensation happened to land on.
     *
     * <p>Step BRIEFS are deliberately not carried the same way. A brief re-emitted after the
     * summary is indistinguishable from a brief being issued for the first time, so carrying the
     * six completed ones forward would present six finished steps as pending work and invite the
     * model to redo them. A skill body has no such tense - it is true whenever it is read, which is
     * why the same treatment does not transfer. What a completed step needs preserved is its
     * FINDINGS, and that is asked of the summarizer instead (see buildSummarizePrompt).
     */
    private static boolean isWorkflowPlan(String content) {
        return content != null && content.startsWith(WORKFLOW_PLAN_PREFIX);
    }

    /**
     * {@code [number, total]} of the step a run is on RIGHT NOW, or null if no run is mid-flight.
     *
     * <p>Read from the last USER message rather than by scanning for the highest step seen, and
     * that is the whole point: condense() is called from {@link #get} on the turn's own thread, and
     * during a run the message being answered IS the current step's brief (WorkflowRunner appends
     * it, then the step executes). So "the last USER message is a step brief" is an exact statement
     * that a run is live and where it has got to - not a heuristic over history.
     *
     * <p>The distinction matters because a run that FAILED or was stopped leaves its briefs in
     * history forever. Keyed off the highest brief seen, the carve-out would keep asserting "you
     * will be given N steps one at a time" for the rest of a conversation whose workflow died at
     * step 4 - which is worse than not carrying it at all.
     */
    private static int[] liveWorkflowStep(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) {
                return workflowStepPosition(um.getText());
            }
        }
        return null;
    }

    private final JpaChatMemoryRepository repository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final LlmGateway llmGateway;
    private final SettingsService settingsService;
    private final ModelPricingService modelPricingService;
    private final ChatSpendService chatSpendService;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatEventSink chatEventSink;
    // Every folder this class names is built, and every delete checked, here - see ConversationPaths.
    private final ConversationPaths paths;
    private final ObjectMapper objectMapper;
    private final boolean multiUser;

    // Serializes whole-conversation memory REWRITES against each other and against a live turn.
    //
    // condense() ends in a write computed from a snapshot taken BEFORE its (minutes-long) summarizer
    // call - a delete-all-then-reinsert until 2026-09-24, a range of rows marked condensed since. Anything else that wrote to the same conversation's
    // memory while that call was in flight is therefore silently erased. The manual "condense now"
    // endpoint used to guard only one direction of that hazard - it 409s while a turn is active, but
    // nothing stopped a turn from STARTING right after the condense had begun, which is exactly what
    // a user does when the condense takes a while and they carry on typing. The turn then ran fully
    // in parallel and, once the summarizer returned, had its user message plus every tool call and
    // result of that turn deleted from under it.
    //
    // A turn holds this for its whole duration (ChatService.chat()); forceCondense() below only ever
    // tryLock()s it, so "condense while a turn is running" stays an immediate 409 rather than a wait.
    // Auto-condensation (condense() from inside get()) needs no acquisition of its own - it always
    // runs on the turn's own thread, inside the lock that turn already holds.
    private final Map<String, MemoryLock> memoryLocks = new ConcurrentHashMap<>();

    // True while a MANUAL condensation (forceCondense) is in flight for the conversation - lets
    // ChatService tell "I am waiting for a condensation" (which the UI can name: 'condense-start')
    // apart from "I am waiting for another turn" (which it cannot). Never set for auto-condensation,
    // which by definition runs inside a turn that is already holding the lock.
    private final Set<String> manualCondensations = ConcurrentHashMap.newKeySet();

    // Every condensation actually summarizing right now, manual and automatic alike - deliberately
    // separate from manualCondensations above, whose narrower meaning ChatService depends on for
    // its wait label and must not change.
    //
    // This one answers a different question, for a different caller: GET .../status reports it so a
    // client that reloaded, or navigated away and came back, can tell that a condensation is in
    // flight RIGHT NOW rather than inferring it from a 'condense-start' event it may never have
    // been connected to see. Without it, refreshing during a condensation showed an idle
    // conversation until the run happened to finish.
    private final Set<String> activeCondensations = ConcurrentHashMap.newKeySet();

    // Reference-counted so an entry can be dropped once nothing holds or wants it, without the
    // remove-while-another-thread-is-acquiring race a bare ConcurrentHashMap<String, ReentrantLock>
    // has (thread A removes the entry, thread B computeIfAbsent()s a fresh lock, and the two then
    // "hold" different locks for the same conversation). Both refs mutations happen inside
    // ConcurrentHashMap.compute/computeIfPresent, which is atomic per key, so the plain int is safe.
    private static final class MemoryLock {
        final ReentrantLock lock = new ReentrantLock();
        int refs;
    }

    /**
     * @param repository            JPA-backed ChatMemoryRepository
     * @param conversationRepository conversation CRUD
     * @param messageRepository     chat message CRUD
     * @param llmGateway            resolves the model condensation runs on (the chat role's own)
     * @param settingsService       reads ChatSettings (memory-max-tokens, model) live on every get()
     * @param modelPricingService   estimates USD cost per LLM call for addCumulativeUsage()
     * @param chatSpendService      rolls that same cost up per model per calendar month (the cost view)
     * @param eventPublisher        publishes condense-start/condense-end ChatEvents for the chat UI
     * @param chatEventSink         assigns each published ChatEvent its ordering sequence number
     * @param conversationsDir      root path for per-conversation offload folders
     * @param objectMapper          Jackson ObjectMapper
     * @param multiUser             true in server mode (anonymous users get no history)
     */
    public SmartChatMemory(JpaChatMemoryRepository repository,
                           ConversationRepository conversationRepository,
                           ChatMessageRepository messageRepository,
                           LlmGateway llmGateway,
                           SettingsService settingsService,
                           ModelPricingService modelPricingService,
                           ChatSpendService chatSpendService,
                           ApplicationEventPublisher eventPublisher,
                           ChatEventSink chatEventSink,
                           String conversationsDir,
                           ObjectMapper objectMapper,
                           boolean multiUser) {
        this.repository = repository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.llmGateway = llmGateway;
        this.settingsService = settingsService;
        this.modelPricingService = modelPricingService;
        this.chatSpendService = chatSpendService;
        this.eventPublisher = eventPublisher;
        this.chatEventSink = chatEventSink;
        this.paths = new ConversationPaths(conversationsDir, multiUser);
        this.objectMapper = objectMapper;
        this.multiUser = multiUser;
    }

    /** Whether this is the multi-user edition, where conversations have owners and visitors are kept apart. */
    public boolean isMultiUser() {
        return multiUser;
    }

    // -- ChatMemory interface --------------------------------------------------

    /**
     * How much the agent's model is taken to read at once - what Settings > Agents says, else what
     * the model's provider states (LlmGateway.contextWindow). It is what a conversation is
     * condensed against and what the context meter fills against.
     */
    public int contextWindow() {
        return llmGateway.contextWindow(LlmRole.CHAT);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        repository.appendMessages(conversationId, messages);
    }

    /** Rewrites the system message a conversation starts with. False when it has none. */
    public boolean replaceSystemPrompt(String conversationId, String text) {
        return repository.replaceSystemMessage(conversationId, text);
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> messages = repository.findByConversationId(conversationId);
        // contextTokens is the real promptTokens the provider reported for this conversation's
        // most recent LLM call (see recordContextTokens()), not an estimate - re-read fresh on
        // every call (not just once per turn) so a single long multi-iteration turn (many tool
        // calls growing the context call by call) can still trigger a mid-turn condense, the same
        // way a fresh turn can. 0 means no real call has happened yet for this conversation -
        // never condense before there's anything real to measure.
        int contextTokens = conversationRepository.findById(conversationId)
                .map(Conversation::getContextTokens).orElse(0);
        int maxTokens = contextWindow();
        if (contextTokens > 0 && maxTokens > 0 && contextTokens >= maxTokens * CONDENSE_THRESHOLD) {
            log.info("[SmartMemory] condense triggered for {} ({} tokens)", conversationId, contextTokens);
            condense(conversationId, messages);
            messages = repository.findByConversationId(conversationId);
        }
        // Repaired here, on the way out, and not in the repository: condensation addresses rows by
        // their index in the UNREPAIRED list, and a synthetic result inserted there would shift
        // every index after it onto the wrong row. See ToolPairing for why this is needed at all.
        ToolPairing.Repaired repaired = ToolPairing.repair(messages);
        if (repaired.repairs() > 0) {
            log.warn("[SmartMemory] repaired {} unpaired tool call(s) or result(s) in {} before sending",
                    repaired.repairs(), conversationId);
        }
        return repaired.messages();
    }

    // The same rows get() returns, without its condense-on-threshold side effect - for callers that
    // only want to READ history and must not risk kicking off a minutes-long summarizer call, e.g.
    // the one-time unlocked-skills backfill in ChatService, which also runs from HTTP request
    // threads (unloadSkill) rather than only from inside a turn.
    public List<Message> getWithoutCondense(String conversationId) {
        return repository.findByConversationId(conversationId);
    }

    @Override
    @Transactional("chatTransactionManager")
    public void clear(String conversationId) {
        repository.deleteByConversationId(conversationId);
    }

    // -- Conversation lifecycle ------------------------------------------------

    // Returns true when this call actually created a new conversation (false when it was a
    // no-op for an already-existing one) - callers use this to fire work that should only ever
    // happen once per conversation, e.g. ChatService's background suggested-topics recompute.
    // guestSessionId (the caller's guest cookie, see ChatController) is only ever recorded when
    // userId is null - it binds a brand-new anonymous conversation to the browser that started
    // it, without perturbing anything userId already scopes (search, settings, personality).
    //
    // `surface` (ConversationSurface) is recorded on the CREATING call only: what a later message
    // says about it is ignored, so a conversation never moves into or out of the chat's history.
    //
    // The id names this conversation's folder too, so one that could be a path is refused before
    // anything is stored - see ConversationPaths.
    @Transactional("chatTransactionManager")
    public boolean ensureConversation(String conversationId, String firstMessage, String userId, String guestSessionId,
                                      String enabledSkillIdsCsv, String surface) {
        ConversationPaths.requireValidId(conversationId);
        Optional<Conversation> existing = conversationRepository.findById(conversationId);
        if (existing.isPresent()) {
            Conversation c = existing.get();
            if (c.getUserId() != null && !c.getUserId().equals(userId))
                throw new ConversationAccessDeniedException("Conversation does not belong to caller");
            // SEC-43, the second wall behind ChatController's: a visitor's conversation belongs to
            // the visitor who started it, so a turn from another browser - or from a signed-in user
            // - is refused here too. Multi-user only; see ConversationAccess.canAccess.
            if (multiUser && c.getUserId() == null && c.getGuestSessionId() != null
                    && !c.getGuestSessionId().equals(guestSessionId))
                throw new ConversationAccessDeniedException("Conversation does not belong to caller");
            return false;
        }
        Conversation c = new Conversation();
        c.setId(conversationId);
        c.setUserId(userId);
        if (userId == null) c.setGuestSessionId(guestSessionId);
        c.setTitle(autoTitle(firstMessage));
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        c.setMessageCount(0);
        c.setEnabledSkillIds(enabledSkillIdsCsv);
        c.setSurface(surface);
        conversationRepository.save(c);
        ensureFolder(conversationId);
        // No title: it is the opening of the user's first message, and conversation content never
        // goes to the log.
        log.info("[SmartMemory] created conversation {} userId={}", conversationId,
                userId != null ? userId.substring(0, Math.min(8, userId.length())) + "..." : "anon");
        return true;
    }

    // Every write below to one conversation's counters or flags is a statement of its own rather
    // than a whole-row save - see ConversationRepository.addUsage for the lost updates that made it.
    @Transactional("chatTransactionManager")
    public void recordExchange(String conversationId) {
        conversationRepository.recordExchange(conversationId, Instant.now());
    }

    // Adds `usage` onto the conversation's running totals ("total usage" - input/output/cache,
    // never reset by condensation, unlike contextTokens below), and its estimated USD cost
    // (provider+model priced via ModelPricingService - an unconfigured pair simply contributes 0,
    // never an error). Called once per completed turn with that turn's summed usage (see
    // ChatService.chat()) and once per condensation summarizer call (see condense()) - a
    // summarizer call is real provider usage too, but its own promptTokens reflects the
    // SUMMARIZATION prompt, not the conversation's context size, so it must never feed into
    // contextTokens (see recordContextTokens() below, which is deliberately separate). `provider`/
    // `model` are whichever ChatSettings said was active at the moment of THIS specific call -
    // there is no per-conversation provider/model column, so cost is computed and accumulated at
    // write time instead of reconstructed later. Returns the cost this call just added, so a
    // caller that needs to attribute the same spend somewhere else too (a sub-agent's own record)
    // doesn't have to re-price the usage against the pricing table a second time.
    @Transactional("chatTransactionManager")
    public double addCumulativeUsage(String conversationId, TokenUsage usage, String provider, String model) {
        if (usage.inputTokens() == 0 && usage.outputTokens() == 0
                && usage.cacheReadTokens() == 0 && usage.cacheWriteTokens() == 0) {
            return 0.0;
        }
        double costDelta = modelPricingService.costUsd(provider, model, usage);
        // Same spend, second dimension. The conversation total below cannot answer "which model"
        // or "which month" - it has no provider/model column and only a mutable updatedAt - so the
        // per-model, per-calendar-month rollup is kept alongside it rather than derived from it
        // later. Outside the ifPresent deliberately: the tokens were spent whether or not the
        // conversation row is still there to charge them to (a delete racing a completing turn),
        // and a total that quietly omits those is worse than one that includes them. Commits in
        // its own transaction and swallows its own failures - see ChatSpendService.
        chatSpendService.record(usage, provider, model, costDelta);
        // Added by the database: the turn, its sub-agents, the title and a search's own calls all
        // add here at once. No per-user quota event here any more (2026-09-29): the per-call ledger
        // publishes one for every priced call, this one included - see LlmSpendRecordedEvent.
        conversationRepository.addUsage(conversationId, usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadTokens(), usage.cacheWriteTokens(), costDelta);
        return costDelta;
    }

    // Overwrites (never accumulates - see Conversation.contextTokens' javadoc) the conversation's
    // "context window" snapshot with the real promptTokens the provider just reported for one
    // main-flow LLM call - called after EVERY iteration of ChatService.react(), not just at the
    // end of a turn, so get()'s trigger check above sees a fresh number on the very next call even
    // mid-turn. Never called with a condensation summarizer's own usage - see addCumulativeUsage().
    @Transactional("chatTransactionManager")
    public void recordContextTokens(String conversationId, int inputTokens) {
        conversationRepository.updateContextTokens(conversationId, inputTokens);
    }

    // EVERY conversation the caller has, whatever its surface - what deleting a user's data walks.
    // The history list is the overload below.
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public Page<Conversation> listConversationsPaged(String userId, Pageable pageable) {
        if (userId != null) {
            return conversationRepository.findAllByUserIdOrderByPinnedDescUpdatedAtDesc(userId, pageable);
        }
        if (multiUser) {
            return Page.empty(pageable);
        }
        return conversationRepository.findAllByOrderByPinnedDescUpdatedAtDesc(pageable);
    }

    // Paginated - backs the conversation history modal's lazy-loaded list, since a
    // long-running session can accumulate hundreds of conversations. `surface` null is the chat
    // page's own; a page holding conversations of its own names its surface (ConversationSurface).
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public Page<Conversation> listConversationsPaged(String userId, String surface, Pageable pageable) {
        if (userId != null) {
            return surface == null
                    ? conversationRepository.pageOfChatOfUser(userId, pageable)
                    : conversationRepository.pageOfSurfaceOfUser(userId, surface, pageable);
        }
        if (multiUser) {
            return Page.empty(pageable);
        }
        return surface == null
                ? conversationRepository.pageOfChat(pageable)
                : conversationRepository.pageOfSurface(surface, pageable);
    }

    // The history of a page that records for itself which conversations are its own (a key
    // account's panel, by its bindings): those of `ids` that exist and belong to `userId`, pinned
    // first, then the most recently active - the chat history's order, whatever each one's surface.
    // A signed-in user only: a list keyed by ids somebody else could know must have an owner.
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public Page<Conversation> listConversationsAmong(String userId, java.util.Collection<String> ids, Pageable pageable) {
        if (userId == null || userId.isBlank() || ids == null || ids.isEmpty()) return Page.empty(pageable);
        return conversationRepository.pageAmongOfUser(userId, ids, pageable);
    }

    @Transactional("chatTransactionManager")
    public void setPinned(String conversationId, boolean pinned) {
        conversationRepository.updatePinned(conversationId, pinned);
    }

    @Transactional(value = "chatTransactionManager", readOnly = true)
    public Optional<Conversation> findConversation(String conversationId) {
        return conversationRepository.findById(conversationId);
    }

    // The ids of the skills whose instructions are already in this conversation's history, so a
    // re-pick can be answered with a pointer instead of a second copy (see
    // ChatService.applyPickedSkills). Null/blank clears it.
    @Transactional("chatTransactionManager")
    public void setEnabledSkillIds(String conversationId, String csv) {
        conversationRepository.updateEnabledSkillIds(conversationId, csv == null || csv.isBlank() ? null : csv);
    }

    // The servers useServer picked for this conversation - see Conversation.activeServers.
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public String activeServers(String conversationId) {
        return conversationRepository.findById(conversationId).map(Conversation::getActiveServers).orElse(null);
    }

    public void setActiveServers(String conversationId, String value) {
        conversationRepository.updateActiveServers(conversationId, value);
    }

    // The skill names the agent has read in this conversation, which is what keeps their tools
    // advertised (see Conversation.unlockedSkillNames and ChatService.unlockedSkillNames). Stores
    // the given value verbatim - unlike setEnabledSkillIds above, an EMPTY string is meaningful
    // here ("recorded, nothing unlocked") and must not be collapsed to null, which means something
    // different ("never recorded, fall back to scanning history").
    @Transactional("chatTransactionManager")
    public void setUnlockedSkillNames(String conversationId, String value) {
        conversationRepository.updateUnlockedSkillNames(conversationId, value);
    }

    // Unfiltered (unlike findMessages above) - includes TOOL rows and blank-content ASSISTANT
    // rows that carry only tool-call metadata, so a caller can reconstruct which tool calls
    // happened between two displayed messages. See ChatService.getMessagesWithToolCalls().
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public List<ChatMessage> findAllMessages(String conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
    }

    /** Whether message {@code messageId} is one of this conversation's - one row read, not all of them. */
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public boolean messageBelongs(String conversationId, Long messageId) {
        return messageId != null && messageRepository.findById(messageId)
                .filter(m -> conversationId.equals(m.getConversationId())).isPresent();
    }

    /** Every row's role and time, oldest first - when the main agent worked (ConversationAgents.turnsOf). */
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public List<ai.proxyma.core.chat.dto.ConversationAgents.Stamp> messageStamps(String conversationId) {
        return messageRepository.findStampsByConversationId(conversationId).stream()
                .map(row -> new ai.proxyma.core.chat.dto.ConversationAgents.Stamp(
                        (ai.proxyma.core.chat.domain.MessageRole) row[0], (java.time.Instant) row[1],
                        Boolean.TRUE.equals(row[2])))
                .toList();
    }

    /**
     * The id of the most recently appended row, or null for a conversation with no messages.
     *
     * <p>Exists for one caller: a workflow run has to record WHICH user message started it, so its
     * step table can be drawn back in the right place after a reload (see WorkflowRun's
     * triggerMessageId). add() returns void - Spring AI's ChatMemory contract - so the id is read
     * back rather than handed over, which is safe here because the run is started on the same
     * thread that just appended the message, before anything else can write to the conversation.
     */
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public Long lastMessageId(String conversationId) {
        return messageRepository.findMaxIdByConversationId(conversationId);
    }

    /**
     * Rewrites the text of the most recent ASSISTANT row, leaving everything else about it alone.
     *
     * <p>Exists for the output guardrail. When a guardrail withholds an answer, the client is told
     * so over the event stream - but the answer has already been appended here by react(), and
     * this table backs BOTH the display history and the raw LLM memory the next turn is built
     * from. Without this, a withheld answer would come back in full on the next page load, and the
     * model would go on reasoning from text the reader was never allowed to see.
     *
     * <p>Rows carrying only tool-call metadata are skipped: their content is blank by design and
     * overwriting one would replace a tool call with prose. Returns false when there is no
     * assistant row to rewrite, which is not an error - an aborted turn persists nothing.
     */
    @Transactional("chatTransactionManager")
    public boolean replaceLastAssistantText(String conversationId, String replacement) {
        List<ChatMessage> messages = messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != MessageRole.ASSISTANT) continue;
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            m.setContent(replacement);
            messageRepository.save(m);
            return true;
        }
        return false;
    }

    /**
     * Records, on the most recent ASSISTANT row, that its turn stopped at the step limit - so the
     * transcript can say so after a reload, as the event stream said it live. See
     * ChatService.announceStepLimit.
     *
     * <p>ANY assistant row, a tool-call-only one included: a last step that asked for more tools
     * instead of answering wrote exactly such a row, and it is the one the notice belongs to.
     */
    @Transactional("chatTransactionManager")
    public void markStepLimit(String conversationId, int limit) {
        List<ChatMessage> messages = messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != MessageRole.ASSISTANT) continue;
            m.setMetadataJson(JpaChatMemoryRepository.withStepLimit(m.getMetadataJson(), limit));
            messageRepository.save(m);
            return;
        }
    }

    // Deletes messageId and every row after it in conversation order (createdAt, then id - see
    // ChatMessageRepository) - this table backs both the display history (findMessages above) and the raw LLM
    // memory (JpaChatMemoryRepository reads the same rows), so this single delete keeps both in
    // sync with no separate "forget" step needed for the agent's context. Also recomputes
    // Conversation.messageCount from the surviving USER rows - left untouched, it would keep
    // counting turns that no longer exist (recordExchange() only ever increments it, once per
    // completed chat() call), which is stale but cosmetic for the regular history list, and
    // actively wrong in Evaluation Mode's per-conversation turn counts (AdminEvaluationController).
    // Only USER rows the user actually sent count as turns. Not every USER row is one: a
    // condensation handoff summary, a loaded skill's instructions and a workflow's opening plan
    // and step briefs are all stored as USER messages (see condense(), skillBodyMessages() and
    // workflowStepBrief()), and counting those would report a conversation as having had more
    // turns than it has had exchanges - a five-step workflow is ONE exchange, not six.
    //
    // SINCE CONDENSATION MARKS ROWS (2026-09-24), a rewind can land INSIDE a condensed range. The
    // summary then describes messages that no longer exist, so every summary row goes and the
    // folded rows that remain are given back to the model: its memory becomes the plain history up
    // to the rewind point, which is what that history was. A rewind after a condensed range keeps
    // the summary - it is timed before the target and survives the delete.
    //
    // The id must belong to this conversation. It used to be trusted, and a stale one from before a
    // condensation matched nothing and deleted every row "after" it - the whole conversation.
    @Transactional("chatTransactionManager")
    public void rewindTo(String conversationId, Long messageId) {
        ChatMessage target = messageRepository.findById(messageId)
                .filter(m -> conversationId.equals(m.getConversationId()))
                .orElseThrow(() -> new NoSuchElementException(
                        "Message " + messageId + " is not in conversation " + conversationId));
        messageRepository.deleteFromMessage(conversationId, target.getCreatedAt(), target.getId());
        if (target.isCondensed()) {
            messageRepository.deleteByConversationIdAndSummaryTrue(conversationId);
            messageRepository.restoreCondensed(conversationId);
        }
        long remainingTurns = messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .filter(m -> !isCondensedSummaryMarker(m.getContent()) && !isSkillMarker(m.getContent())
                        && !isWorkflowMarker(m.getContent()))
                .count();
        conversationRepository.updateMessageCount(conversationId, (int) remainingTurns);
    }

    @Transactional("chatTransactionManager")
    public void deleteAllByUserId(String userId) {
        List<String> ids = conversationRepository.findIdsByUserId(userId);
        for (String id : ids) {
            repository.deleteByConversationId(id);
            deleteFolder(id);
        }
        conversationRepository.deleteByUserId(userId);
        // Remove the user's whole folder, not just the per-conversation dirs above - it also
        // holds user-level files (the personality agent's personality.md), which must not
        // survive account deletion. Never a visitor's shared folder: a blank id is nobody's account.
        if (multiUser && userId != null && !userId.isBlank()) {
            try {
                paths.deleteTree(paths.userRoot(userId));
            } catch (IllegalArgumentException e) {
                log.warn("[SmartMemory] not deleting the folder of a user id that is not one name: {}", e.getMessage());
            }
        }
        log.info("[SmartMemory] deleted {} conversations for userId={}", ids.size(), userId);
    }

    // The folder is found BEFORE the row goes: on a multi-user server it is under the OWNER's
    // folder, and the owner is read off the row. Found afterwards, the lookup came back empty and
    // named users/_anonymous instead, so no signed-in user's conversation files - uploads, outputs,
    // web media, offloaded results, condensation archives - were ever deleted.
    @Transactional("chatTransactionManager")
    public void deleteConversation(String conversationId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw new NoSuchElementException("Conversation not found: " + conversationId);
        }
        Path folder = conversationFolder(conversationId);
        clear(conversationId);
        conversationRepository.deleteById(conversationId);
        if (folder != null) paths.deleteTree(folder);
        log.info("[SmartMemory] deleted conversation {}", conversationId);
    }

    /**
     * Removes conversation folders no conversation owns - what an upload to a conversation that
     * never got its first message leaves, and what deletes before 2026-10-14 left on Enterprise
     * Server (see deleteConversation). Only folders untouched for {@code minAge}: an upload is made
     * a moment before the message that creates its conversation, and must survive that moment.
     */
    public void cleanOrphanFolders(Duration minAge) {
        Path root = paths.root();
        if (!Files.isDirectory(root)) return;
        Set<String> activeIds = Set.copyOf(conversationRepository.findAllIds());
        Instant cutoff = Instant.now().minus(minAge);
        List<Path> parents = new ArrayList<>();
        if (multiUser) {
            Path usersDir = root.resolve("users");
            if (!Files.isDirectory(usersDir)) return;
            try (var userDirs = Files.list(usersDir)) {
                userDirs.filter(Files::isDirectory).map(d -> d.resolve("conversations"))
                        .filter(Files::isDirectory).forEach(parents::add);
            } catch (IOException e) {
                log.warn("[SmartMemory] failed to scan users dir: {}", e.getMessage());
                return;
            }
        } else {
            parents.add(root);
        }
        int removed = 0;
        for (Path parent : parents) {
            try (var dirs = Files.list(parent)) {
                for (Path d : dirs.toList()) {
                    String name = d.getFileName().toString();
                    // Only what could be a conversation's folder is ever a candidate.
                    if (!Files.isDirectory(d) || !ConversationPaths.isValidId(name) || activeIds.contains(name)) continue;
                    if (Files.getLastModifiedTime(d).toInstant().isAfter(cutoff)) continue;
                    if (paths.deleteTree(d)) removed++;
                }
            } catch (IOException e) {
                log.warn("[SmartMemory] failed to scan {}: {}", parent, e.getMessage());
            }
        }
        if (removed > 0) log.info("[SmartMemory] removed {} conversation folder(s) no conversation owns", removed);
    }

    // -- Tool result offloading ------------------------------------------------

    public record WriteResult(String absolutePath, int totalLength) {}

    // A string field at or below this length with no newline is kept inline in the "key: value"
    // header; longer or multi-line fields become their own raw "===== key =====" text block.
    private static final int OFFLOAD_LONG_TEXT_THRESHOLD = 200;

    // Estimated-token ceiling above which a tool result is written to a file instead of being
    // handed back to the model whole.
    private static final int LARGE_RESULT_TOKEN_THRESHOLD = 8000;

    /**
     * The longest result that is never offloaded: a little under what
     * {@link #LARGE_RESULT_TOKEN_THRESHOLD} works out to in characters (8000 / 1.2 * 4 = 26666).
     * The tools that page an offloaded file keep their own output inside it - see
     * {@link #PAGING_TOOLS}.
     */
    public static final int MAX_INLINE_RESULT_CHARS = 26_000;

    /**
     * The tools an agent reads an offloaded file BACK with. Their results are never offloaded.
     *
     * <p>They were, and it was a loop with no way out (2026-10-01): a {@code grep} matching 200
     * long lines came to 38000 characters, was written to a file of its own, and the envelope told
     * the agent to grep or read THAT - whose result was offloaded in turn. A {@code read} was worse:
     * its output starts with a line number, the lenient JSON parse in renderOffloadContent took
     * that number for the whole document, and the file it wrote held the single character "1".
     * The agent went round those three calls until its step limit.
     *
     * <p>Both tools now cap their own output with a note saying where to continue, so the result
     * that reaches the model is the page it asked for. The cut in offloadIfLarge is the floor
     * under that, for a result that somehow still arrives too long.
     */
    public static final Set<String> PAGING_TOOLS = Set.of("read", "grep");

    // The offload DECISION, shared by every agent loop that owns the grep/read follow-up call:
    // the main agent (ChatService) and every sub-agent (SubAgentService). It used to be private to
    // ChatService, which meant sub-agents had no ceiling at all - one oversized tool result (a
    // converted spreadsheet, a whole-space sweep) landed in a cheap model's context in a single
    // piece, blowing it with no recovery path, on the very code path whose entire purpose is
    // absorbing bulk reading.
    //
    // Returns `result` unchanged when it already fits, when there is no conversation to write into
    // (the `delegate` tool is also reachable from the raw /mcp endpoint, where there is no
    // conversation id and hence no per-conversation folder), or when the write itself failed: an
    // oversized result the model has to cope with is survivable, silently losing it is not.
    public String offloadIfLarge(String conversationId, String toolName, String trackingId, String result) {
        if (result == null) return "{}";
        int estimatedTokens = (int) Math.ceil((result.length() / 4.0) * 1.2);
        if (estimatedTokens <= LARGE_RESULT_TOKEN_THRESHOLD || conversationId == null) return result;
        if (PAGING_TOOLS.contains(toolName)) {
            log.warn("[SmartMemory] tool '{}' returned {} chars, over its own limit - cut, not offloaded",
                    toolName, result.length());
            return result.substring(0, MAX_INLINE_RESULT_CHARS)
                    + "\n... [cut here: the result was too long. Ask for fewer lines or a narrower query.]";
        }

        WriteResult written = offloadResult(conversationId, toolName, trackingId, result);
        if (written == null) {
            log.warn("[SmartMemory] offload failed for tool '{}', returning full result", toolName);
            return result;
        }
        String safePath = written.absolutePath().replace("\\", "/");
        log.info("[SmartMemory] offloaded tool '{}' result (~{} tokens) to {}", toolName, estimatedTokens, safePath);
        return "{\"resultOffloaded\":true,\"filePath\":\"" +
                safePath.replace("\"", "\\\"") + "\"," +
                "\"totalLength\":" + written.totalLength() + "," +
                "\"hint\":\"grep filePath for relevant lines, then read at a match's line as offset. Read it whole only for a full listing.\"}";
    }

    public WriteResult offloadResult(String conversationId, String toolName, String trackingId, String content) {
        Path dir = ensureFolder(conversationId);
        String rendered = renderOffloadContent(content);
        // .txt, not .json: the rendered form is human-readable multi-line text, not JSON, and is
        // paged back through the line-based 'read'/'grep' tools. The call id is the provider's,
        // so only its plain characters make it into a file name.
        String filename = (toolName + "_" + trackingId).replaceAll("[^A-Za-z0-9_-]", "_") + ".txt";
        Path file = dir.resolve(filename);
        try {
            Files.writeString(file, rendered, StandardCharsets.UTF_8);
            return new WriteResult(file.toAbsolutePath().toString(), rendered.length());
        } catch (IOException e) {
            log.warn("[SmartMemory] failed to write offload file {}: {}", file, e.getMessage());
            return null;
        }
    }

    // Tool results arrive here as Spring AI's JSON serialization, so any newlines inside string
    // fields are escaped to "\n" and the whole payload is effectively one line - unreadable once
    // offloaded and paged back through 'read'/'grep'. Re-render a JSON object into readable text:
    // small scalar fields as a "key: value" header, and each long or multi-line string field (e.g.
    // webFetch's textContent, a PDF's extracted markdown) as its own raw block under a
    // "===== key =====" divider so real line breaks survive on disk. A top-level array is
    // pretty-printed; anything that isn't a JSON object or array is stored unchanged.
    //
    // STRICT, since 2026-10-01. Jackson's readTree stops at the first complete value, so a plain
    // text result that happens to start with one parsed "successfully" as that value alone: a
    // line-numbered listing ("     1\t...") became the number 1, and the file written for it held
    // one character. FAIL_ON_TRAILING_TOKENS makes that a parse failure, and a bare scalar is
    // treated as the text it is - the same lenient-parser shape as the Gemini tool-result rewrite.
    private String renderOffloadContent(String raw) {
        if (raw == null || raw.isBlank()) return raw == null ? "" : raw;
        try {
            JsonNode root = objectMapper.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(raw);
            if (root == null || !root.isContainerNode()) return raw;
            if (!root.isObject()) {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            }
            StringBuilder header = new StringBuilder();
            StringBuilder blocks = new StringBuilder();
            for (var entry : root.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    String text = value.asText();
                    if (text.indexOf('\n') >= 0 || text.length() > OFFLOAD_LONG_TEXT_THRESHOLD) {
                        blocks.append("\n===== ").append(key).append(" =====\n").append(text).append('\n');
                    } else {
                        header.append(key).append(": ").append(text).append('\n');
                    }
                } else if (value.isValueNode()) {
                    header.append(key).append(": ").append(value.asText()).append('\n');
                } else {
                    blocks.append("\n===== ").append(key).append(" =====\n")
                            .append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)).append('\n');
                }
            }
            String out = header.append(blocks).toString().strip();
            return out.isEmpty() ? raw : out + "\n";
        } catch (Exception e) {
            return raw;
        }
    }

    // -- Chat attachments ------------------------------------------------------

    public record AttachmentResult(String absolutePath, String storedFileName, long sizeBytes) {}

    static final int MAX_ATTACHMENTS_PER_CONVERSATION = 100;
    static final long MAX_ATTACHMENT_BYTES_PER_CONVERSATION = 500L * 1024 * 1024;

    // Writes an uploaded chat attachment under <conversationDir>/attachments/<name>, keyed by the
    // CALLER'S OWN userId (from the authenticated request) rather than a DB lookup - the
    // Conversation row may not exist yet (a brand-new conversation's very first action can be
    // "attach a file"). originalFileName is reduced to its base name only (no path traversal) and
    // de-duplicated with a short random suffix on collision, never overwriting a same-named prior
    // attachment.
    //
    // Bounded per conversation, in files and in bytes: each upload was capped and the conversation
    // was not, on an endpoint a visitor can reach.
    public AttachmentResult saveAttachment(String conversationId, String userId,
                                            String originalFileName, byte[] bytes) throws IOException {
        Path dir = buildConversationDir(conversationId, userId).resolve("attachments");
        Files.createDirectories(dir);
        long files = 0;
        long stored = 0;
        try (var listing = Files.list(dir)) {
            for (Path p : listing.filter(Files::isRegularFile).toList()) {
                files++;
                stored += Files.size(p);
            }
        }
        if (files >= MAX_ATTACHMENTS_PER_CONVERSATION) {
            throw new IllegalArgumentException("This conversation already has " + MAX_ATTACHMENTS_PER_CONVERSATION
                    + " attachments. Start a new conversation to attach more.");
        }
        if (stored + bytes.length > MAX_ATTACHMENT_BYTES_PER_CONVERSATION) {
            throw new IllegalArgumentException("This conversation's attachments would exceed "
                    + (MAX_ATTACHMENT_BYTES_PER_CONVERSATION / (1024 * 1024)) + "MB. Start a new conversation to attach more.");
        }
        String base = sanitizeFileName(originalFileName);
        Path file = dir.resolve(base);
        if (Files.exists(file)) {
            int dot = base.lastIndexOf('.');
            String stem = dot > 0 ? base.substring(0, dot) : base;
            String ext = dot > 0 ? base.substring(dot) : "";
            file = dir.resolve(stem + "-" + UUID.randomUUID().toString().substring(0, 8) + ext);
        }
        Files.write(file, bytes);
        return new AttachmentResult(file.toAbsolutePath().toString(), file.getFileName().toString(), (long) bytes.length);
    }

    private static String sanitizeFileName(String name) {
        String base = Path.of(name).getFileName().toString();
        return base.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
    }

    // -- Condensation ----------------------------------------------------------

    /**
     * Forces a condensation pass right now, bypassing the {@link #CONDENSE_THRESHOLD} (80%)
     * auto-trigger check inside {@link #get}. Backs the chat UI's manual "condense now" button,
     * which the frontend shows once usage crosses its own lower, earlier warning threshold (60%)
     * - see ChatController's /condense endpoint. A no-op (logged by condense() itself) if there's
     * nothing condensable yet or no chat model is configured.
     *
     * @return false if this conversation's memory is already held by a turn or by another
     *         condensation, in which case NOTHING was condensed (the caller should reject the
     *         request rather than report success) - see memoryLocks' javadoc for why the two can
     *         never be allowed to overlap.
     */
    public boolean forceCondense(String conversationId) {
        MemoryLock held = memoryLocks.compute(conversationId,
                (k, v) -> { MemoryLock m = v != null ? v : new MemoryLock(); m.refs++; return m; });
        if (!held.lock.tryLock()) {
            releaseRef(conversationId);
            log.info("[SmartMemory] condense skipped for {}: conversation memory is busy", conversationId);
            return false;
        }
        manualCondensations.add(conversationId);
        try {
            condense(conversationId, repository.findByConversationId(conversationId));
        } finally {
            manualCondensations.remove(conversationId);
            held.lock.unlock();
            releaseRef(conversationId);
        }
        return true;
    }

    // True while forceCondense() above is running for this conversation. Read by ChatService before
    // it blocks on lockMemory(), purely to decide whether the wait it is about to do is one the UI
    // can name ("Condensing conversation...").
    public boolean isCondensing(String conversationId) {
        return manualCondensations.contains(conversationId);
    }

    // True while ANY condensation - manual or the automatic one inside a turn - is summarizing for
    // this conversation. Backs the `condensing` flag on GET .../status, which is how a reloaded or
    // returned-to tab recovers the indicator without having been connected for 'condense-start'.
    public boolean isCondensingNow(String conversationId) {
        return activeCondensations.contains(conversationId);
    }

    // A turn waits this long for a manual condensation to finish before giving up and running
    // anyway. Comfortably past condense()'s own 3-minute summarizer timeout, so the wait normally
    // ends when the condensation genuinely ends; the cap exists only so a wedged condensation can
    // never strand a user's message forever.
    private static final Duration MEMORY_LOCK_WAIT = Duration.ofMinutes(4);
    // The summarizer call's whole allowance, retries included - under MEMORY_LOCK_WAIT, which a
    // turn waiting behind a manual condensation is given.
    private static final Duration SUMMARIZER_LIMIT = Duration.ofMinutes(3);
    // How long a turn waits for ANOTHER TURN of the same conversation - see lockMemory.
    private static final Duration TURN_LOCK_WAIT = Duration.ofMinutes(90);

    /**
     * Takes this conversation's memory lock for the caller (see memoryLocks' javadoc), blocking up
     * to {@link #MEMORY_LOCK_WAIT}. Reentrant: a turn that already holds it re-enters freely, which
     * is what lets auto-condensation run inside get() without any locking of its own.
     *
     * @return true if the lock was acquired - and ONLY then must the caller pair this with
     *         {@link #unlockMemory}. False means the wait timed out; the caller is expected to
     *         proceed regardless (losing a user's turn is worse than an unserialized one) and must
     *         NOT unlock.
     */
    public boolean lockMemory(String conversationId) {
        MemoryLock held = memoryLocks.compute(conversationId,
                (k, v) -> { MemoryLock m = v != null ? v : new MemoryLock(); m.refs++; return m; });
        long started = System.nanoTime();
        try {
            while (true) {
                if (held.lock.tryLock(1, TimeUnit.SECONDS)) return true;
                long waited = System.nanoTime() - started;
                // ANOTHER TURN IS WAITED OUT, not four minutes of it. A turn routinely runs longer -
                // sub-agents, an approval that waits half an hour - and a second message for the same
                // conversation (another tab, another device) used to give up here and run alongside
                // it: both loops appending to one history, the first turn's Stop and its abort flag
                // taken over. Every turn ends on its own limits; the ceiling is only for one that is
                // wedged. A manual condensation keeps the short wait, as its summarizer call does.
                Duration limit = manualCondensations.contains(conversationId) ? MEMORY_LOCK_WAIT : TURN_LOCK_WAIT;
                if (waited > limit.toNanos()) break;
            }
            log.warn("[SmartMemory] timed out waiting for conversation memory lock on {} - proceeding unserialized",
                    conversationId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        releaseRef(conversationId);
        return false;
    }

    public void unlockMemory(String conversationId) {
        MemoryLock held = memoryLocks.get(conversationId);
        if (held == null) return;
        held.lock.unlock();
        releaseRef(conversationId);
    }

    // Drops the map entry once the last holder/waiter is gone. computeIfPresent is atomic per key,
    // so this can never race a concurrent acquisition into handing two threads different locks for
    // the same conversation.
    private void releaseRef(String conversationId) {
        memoryLocks.computeIfPresent(conversationId, (k, m) -> --m.refs <= 0 ? null : m);
    }

    // A condensation the USER asked for always ends in a CONDENSE_END, even one that found nothing
    // to fold - otherwise the click produced a spinner that blinked off with the indicator
    // unchanged and nothing said why (2026-09-24). Zero tells the client nothing was freed. An
    // automatic one stays silent, as it always has: nobody asked for it.
    private void reportNothingCondensed(String conversationId) {
        if (manualCondensations.contains(conversationId)) publishCondensing(conversationId, false, 0);
    }

    private void condense(String conversationId, List<Message> messages) {
        // ChatService.chat() sets this on its own thread for the duration of the turn (not just
        // inside callTools()'s worker threads) specifically so condensation - called synchronously
        // from react() on that same thread - can see a Stop request too; before this, a 3-minute
        // summarizer call ignored Stop entirely.
        if (SearchContext.isAborted()) return;

        int[] range = findCondensableRange(messages);
        if (range == null) {
            log.info("[SmartMemory] no condensable range found for {}", conversationId);
            reportNothingCondensed(conversationId);
            return;
        }
        int a = range[0];
        int b = range[1];
        List<Message> target = messages.subList(a, b + 1);
        // Non-null only while a workflow run is mid-flight - see liveWorkflowStep(). Decides both
        // what is carried past this condensation verbatim and what the summarizer is told to
        // preserve; null leaves condensation behaving exactly as it did before workflows existed.
        int[] liveStep = liveWorkflowStep(messages);

        if (!llmGateway.isAvailable(LlmRole.CONDENSATION)) {
            log.warn("[SmartMemory] condensation skipped for {}: no chat model configured", conversationId);
            reportNothingCondensed(conversationId);
            return;
        }
        // PLAIN options, not tuned: condensation has never applied a temperature or an output
        // ceiling, and giving it one now could truncate a summary mid-sentence.
        // Billed to the conversation's OWNER, read from the conversation rather than the thread:
        // a condensation the user asks for from the menu runs outside any turn, where nothing
        // had set a subject and it was filed under SYSTEM or under whoever last used the thread.
        SpendSubject owner = conversationRepository.findById(conversationId)
                .map(c -> SpendSubject.user(c.getUserId()))
                .orElse(SpendSubject.ANONYMOUS);
        LlmSession session = llmGateway.open(LlmCaller.of(LlmRole.CONDENSATION, owner),
                LlmGateway.SessionOptions.plain());
        String provider = session.provider();
        String model = session.model();

        activeCondensations.add(conversationId);
        publishCondensing(conversationId, true, 0);
        // Read by the finally block below, which publishes the CONDENSE_END event on every exit
        // path - including the several early `return`s inside the try, all of which mean "nothing
        // was condensed" and must report zero rather than a stale figure.
        int freed = 0;
        try {
            log.info("[SmartMemory] condensing {} messages (range [{},{}]) for {} using model={}",
                    target.size(), a, b, conversationId, model);
            // Skill messages are excluded from what the summarizer is asked to summarize: they are
            // carried forward verbatim below, so paying to summarize them would be paying twice for
            // text that survives either way - and inviting the summary to restate instructions in
            // its own words right next to the originals. A live run's opening plan is excluded for
            // exactly the same reason, and workflow acknowledgements always are - they are
            // role-alternation scaffolding ("Understood - I will wait for each step") with no
            // content a summary could want.
            //
            // Step BRIEFS are deliberately left IN. They are not carried forward (see
            // isWorkflowPlan), and they are what lets the summarizer attribute a finding to the
            // step that produced it rather than emitting a flat pile of facts.
            String summarizePrompt = buildSummarizePrompt(target.stream()
                    .filter(m -> !isSkillMarker(m.getText()))
                    .filter(m -> !isWorkflowAckOrCarriedPlan(m.getText(), liveStep != null))
                    .toList(), liveStep);

            // THE AGENTS' OWN MODEL CALL (ModelCall), since 2026-10-14: Stop within a moment, the
            // thinking left out of the summary, a busy provider retried, and the usage folded from
            // every chunk. This made its own streamed call until then and kept the LAST chunk's
            // usage - Anthropic states the prompt size in its first chunk only, so the input of every
            // condensation, which is the whole folded conversation, was recorded as nothing - and
            // one 429 abandoned it. Nothing is shown while it writes; the summary is not an answer.
            ChatResponse response;
            TokenUsage summarizerUsage;
            try {
                ModelCall.Outcome outcome = ModelCall.run(session, session.streams(),
                        List.of(new UserMessage(summarizePrompt)), List.of(), SearchContext::isAborted,
                        new ai.proxyma.core.guardrail.StreamingScrubber(List.of()), text -> { }, SUMMARIZER_LIMIT);
                if (outcome.stopped()) {
                    log.info("[SmartMemory] condensation aborted for {}", conversationId);
                    return;
                }
                response = outcome.response();
                summarizerUsage = session.priced(outcome.usage());
            } catch (Exception e) {
                log.warn("[SmartMemory] summarizer call failed for {}: {}", conversationId, e.getMessage());
                return;
            }
            if (response == null) return;
            String summaryText = response.getResult().getOutput().getText();
            summaryText = summaryText != null ? summaryText.trim() : "";
            if (summaryText.isBlank()) {
                log.warn("[SmartMemory] summarizer returned an empty handoff summary for {}", conversationId);
                return;
            }

            // Only actually remove the condensed range from live memory once it's safely archived
            // to disk - a failed write aborts the whole condensation rather than silently losing
            // the raw messages nowhere (they'd be neither in memory nor recoverable on disk).
            if (!writeOffloadedMessages(conversationId, target)) {
                log.warn("[SmartMemory] condensation aborted for {}: failed to archive condensed range", conversationId);
                return;
            }

            // The summary replaces [a, b] as its own USER message, immediately followed by a
            // synthetic ASSISTANT acknowledgement - never merged into the system message. Both
            // are required: messages[b + 1] (the start of "raw messages after that") is always
            // itself a real USER message (see findCondensableRange), and Anthropic's API (unlike
            // Ollama/OpenAI-style APIs, which tolerate it) rejects two consecutive same-role
            // messages outright - the synthetic bridge is what keeps roles alternating. Both carry
            // the CONDENSED_MARKER_PREFIX so getMessagesWithToolCalls() hides them from the
            // displayed transcript (see isCondensedSummaryMarker()).
            //
            // Any skill body inside the condensed range is then re-attached verbatim after the
            // summary. A skill is standing instructions for the rest of the conversation, so
            // letting the summarizer paraphrase it would quietly change what the agent was told to
            // do - the one thing a summary must not do. Each is re-emitted with its own fresh
            // acknowledgement, which keeps roles alternating no matter how many are carried.
            // Built as its own list first, rather than appended straight onto newMemory, so that
            // "what replaced the condensed range" is a value this method can measure below instead
            // of a slice someone has to re-derive from three indices.
            List<Message> replacement = new ArrayList<>();
            replacement.add(new UserMessage(SUMMARY_USER_PREFIX + summaryText));
            replacement.add(new AssistantMessage(SUMMARY_ACK_TEXT));
            for (Message pinned : pinnedSkillBodies(target)) {
                replacement.add(pinned);
                replacement.add(new AssistantMessage(SKILL_ACK_TEXT));
            }
            // ...and the opening plan of a run still in flight, on the same terms and for the same
            // reason. Only while a run is live (liveStep != null): re-asserting "you will be given
            // N steps one at a time" into a conversation whose workflow already finished or died
            // would leave the model waiting for steps that are never coming. Its own
            // acknowledgement is re-emitted rather than carried, which is what keeps roles
            // alternating here no matter how many messages precede it - and unlike a step brief,
            // that wording ("I will wait for each step") is as true after the summary as before it.
            if (liveStep != null) {
                for (Message pinned : pinnedWorkflowPlans(target)) {
                    replacement.add(pinned);
                    replacement.add(new AssistantMessage(WORKFLOW_ACK_TEXT));
                }
            }

            List<Message> newMemory = new ArrayList<>(messages.subList(0, a));
            newMemory.addAll(replacement);
            newMemory.addAll(messages.subList(b + 1, messages.size()));
            repository.condenseRange(conversationId, a, b, replacement);

            // What this condensation actually bought, and the one number the user is shown for it.
            //
            // Estimated, and unavoidably so: the real post-condense context size is whatever the
            // provider reports for the NEXT main-flow call, which has not happened yet. But the
            // DELTA is far more honestly estimable than the absolute size would be - we hold the
            // exact text that left and the exact text that replaced it, both ordinary prose the
            // same tokenizer treats the same way, so differencing two estimates cancels most of the
            // error either one carries alone. Everything else in the prompt (system message, tool
            // definitions) is untouched by condensation and cancels exactly.
            int freedTokens = Math.max(0, estimateTokens(target) - estimateTokens(replacement));
            applyCondensedContextEstimate(conversationId, freedTokens);

            // The summarizer call is real provider usage too - counts toward the conversation's
            // (and user's) running total, but deliberately never touches contextTokens: its own
            // promptTokens reflects the summarization prompt, not the post-condense conversation
            // size, which the very next main-flow call will report correctly on its own.
            addCumulativeUsage(conversationId, summarizerUsage, provider, model);
            publishUsageUpdate(conversationId);
            freed = freedTokens;
            log.info("[SmartMemory] condensed {} -> {} messages ({} folded into handoff summary, ~{} tokens freed) for {}",
                    messages.size(), newMemory.size(), target.size(), freedTokens, conversationId);
        } finally {
            activeCondensations.remove(conversationId);
            // Carries the freed-token count so the client can report what the wait achieved instead
            // of just going quiet. Zero on every path that bailed out (aborted, summarizer failed,
            // nothing condensable) - the client shows no figure for a condensation that freed
            // nothing, which is exactly what happened.
            publishCondensing(conversationId, false, freed);
        }
    }

    // Overwrites contextTokens with old-minus-freed. THE ONE PLACE this column is written with
    // anything other than a provider-reported number (see recordContextTokens()), and deliberately
    // so: without it the UI's context-window indicator kept showing the pre-condense figure - and
    // the warning state that goes with it - until the next turn happened to report a real one,
    // which for a user who condensed precisely so they could keep going is both wrong and the exact
    // moment the reassurance was needed. Self-correcting: the very next main-flow call overwrites
    // this with the real measurement.
    @Transactional("chatTransactionManager")
    void applyCondensedContextEstimate(String conversationId, int freedTokens) {
        if (freedTokens <= 0) return;
        conversationRepository.reduceContextTokens(conversationId, freedTokens);
    }

    // Same 4-chars-per-token heuristic (with the same 1.2 safety factor) that offloadIfLarge()
    // already uses to decide when a tool result is too big to hand back - kept identical rather
    // than tuned separately so there is one notion of "roughly how many tokens is this text" in
    // this class instead of two that disagree.
    private static int estimateTokens(List<Message> messages) {
        long chars = 0;
        for (Message m : messages) chars += messageText(m).length();
        return (int) Math.ceil((chars / 4.0) * 1.2);
    }

    private void publishCondensing(String conversationId, boolean starting, int freedTokens) {
        eventPublisher.publishEvent(new ChatEvent(conversationId,
                chatEventSink.nextSequence(conversationId),
                (starting ? ChatEvent.Type.CONDENSE_START : ChatEvent.Type.CONDENSE_END).value(),
                null, null, starting ? "" : String.valueOf(freedTokens)));
    }

    // Mirrors ChatService.publishUsageUpdate() for the condensation summarizer's own call - it's
    // real provider usage too (see the comment above addCumulativeUsage() just above this call
    // site), so the client's cumulative counters should reflect it live the same way a main-flow
    // iteration's usage does, without waiting for the turn's next real response.
    private void publishUsageUpdate(String conversationId) {
        Conversation conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv == null) return;
        int limit = contextWindow();
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", conv.getInputTokens());
        usage.put("outputTokens", conv.getOutputTokens());
        usage.put("cacheReadTokens", conv.getCacheReadTokens());
        usage.put("cacheWriteTokens", conv.getCacheWriteTokens());
        usage.put("contextTokens", conv.getContextTokens());
        usage.put("contextTokenLimit", limit);
        String data;
        try {
            data = objectMapper.writeValueAsString(usage);
        } catch (Exception e) {
            return;
        }
        eventPublisher.publishEvent(new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                ChatEvent.Type.USAGE.value(), null, null, data));
    }

    // [a, b]: from the first non-system message through (not including) the last USER message
    // currently in memory - the last USER message is always either the turn actively being
    // answered (get() runs after it's already added, before the model has replied - see
    // ChatService.react()) or, on a re-condense pass, the most recent real content, so it and
    // everything the model has produced in response to it since must never be summarized away.
    // `a` doubles as "the existing summary" on a second-or-later pass (see class javadoc) - it's
    // just the first USER message in memory, whatever that happens to be.
    // The skill-body messages inside a range about to be condensed away, in their original order,
    // deduplicated by content so a body that somehow appears twice is carried once. Reminder
    // messages (see skillReminderMessages) are deliberately NOT carried: they only point at a body,
    // and the body itself is right here being carried forward.
    private static List<Message> pinnedSkillBodies(List<Message> range) {
        List<Message> pinned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Message m : range) {
            if (m instanceof UserMessage um && um.getText() != null
                    && um.getText().startsWith(SKILL_BODY_PREFIX) && seen.add(um.getText())) {
                pinned.add(um);
            }
        }
        return pinned;
    }

    // The opening plan(s) inside a range about to be condensed away, in order, deduplicated by
    // content. A list rather than a single message because one conversation can invoke several
    // workflows before it ever crosses the condensation threshold; only the live one's plan is
    // wanted, but an earlier run's plan is cheap to carry and impossible to tell apart here without
    // the run state, which this class deliberately has no access to.
    private static List<Message> pinnedWorkflowPlans(List<Message> range) {
        List<Message> pinned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Message m : range) {
            if (m instanceof UserMessage um && isWorkflowPlan(um.getText()) && seen.add(um.getText())) {
                pinned.add(um);
            }
        }
        return pinned;
    }

    // Excluded from the summarizer's input: an acknowledgement always (scaffolding, no content),
    // and the opening plan only when it is being carried forward verbatim instead.
    private static boolean isWorkflowAckOrCarriedPlan(String content, boolean planIsCarried) {
        if (content == null) return false;
        if (content.startsWith(WORKFLOW_ACK_PREFIX)) return true;
        return planIsCarried && isWorkflowPlan(content);
    }

    private int[] findCondensableRange(List<Message> messages) {
        int a = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                a = i;
                break;
            }
        }
        if (a < 0) return null;

        int lastUserIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastUserIndex = i;
                break;
            }
        }

        int b = lastUserIndex - 1;
        if (b <= a) return null;
        if ((b - a + 1) < MIN_RANGE_SIZE) return null;

        return new int[]{a, b};
    }

    // Asks for a handoff summary, not a compression of each message in isolation: the agent will
    // see ONLY this summary going forward, never the original messages below, so the goal is
    // "the agent can keep going as if nothing was removed" rather than "a shorter transcript".
    private String buildSummarizePrompt(List<Message> messages, int[] liveStep) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are compacting an ongoing AI assistant conversation so the assistant can keep helping ")
          .append("the user without this history taking up space in its context window. Going forward the ")
          .append("assistant will see ONLY the summary you write - none of the original messages below - so ")
          .append("it must be able to resume the conversation as if it had continued uninterrupted.\n\n")
          .append("Read all of the messages below in order - user turns, assistant turns, and tool call ")
          .append("results alike - then write a single handoff summary that preserves, explicitly and ")
          .append("specifically:\n")
          .append("- The user's overall goal(s), and any requirements, constraints, or preferences they stated ")
          .append("(tone, format, scope, things to avoid)\n")
          .append("- Concrete facts, decisions, and conclusions already reached - the assistant must not ")
          .append("re-derive or contradict these later\n")
          .append("- Specific identifiers that may be needed again: file paths, URLs, IDs/keys, names, numbers, ")
          // Not "the queries": messageText() gives a tool RESULT with its tool's name but an
          // assistant turn's text only, so the arguments a tool was called with are not in the
          // transcript for the summarizer to preserve - asking for them invited invented ones.
          .append("code symbols, and the key results each tool returned (named by the tool)\n")
          .append("- Outstanding questions, unresolved issues, or agreed next steps still pending\n")
          .append("- Anything the user explicitly asked to be remembered, or not to be repeated\n\n")
          .append("Do not add commentary about the summarization process itself, do not pad with filler, and ")
          .append("do not repeat long tool output verbatim - extract only the facts from it that still matter ")
          .append("going forward.\n\n")
          .append("Write the summary as short labeled sections or bullet points so it stays easy to scan. ")
          .append("Respond with ONLY the summary text - no preamble, no meta-commentary, no closing remarks.\n\n");
        // A run in flight raises the stakes on the general instructions above, and does it in a way
        // the assistant reading the summary cannot detect or recover from: it will be handed step
        // n+1 and answer it against whatever this summary says the earlier steps found. A workflow
        // exists to be followed exactly, so "roughly what happened" is a worse failure here than in
        // ordinary chat - hence naming the steps, and demanding results rather than narration.
        if (liveStep != null) {
            sb.append("IMPORTANT - the messages below contain a WORKFLOW RUN that is still in ")
              .append("progress. It is on step ").append(liveStep[0]).append(" of ")
              .append(liveStep[1]).append(", so ")
              // Step 1 is the one case with nothing finished behind it. Saying "steps 1 to 0 are
              // already finished" there is the kind of contradiction that makes a model start
              // inventing which steps it means.
              .append(liveStep[0] <= 1
                      ? "no step has finished yet"
                      : "steps 1 to " + (liveStep[0] - 1) + " are already finished")
              .append(", and the assistant will be given the remaining steps ")
              .append("one at a time after reading your summary. Their outputs are the only record ")
              .append("of what those steps found. Therefore:\n")
              .append("- Give each completed step its own labeled entry, in order, naming the step\n")
              .append("- Record what the step actually PRODUCED - the identifiers, paths, counts, ")
              .append("names, versions, quoted findings and tool results a later step would need to ")
              .append("act on - not a description of what the step did\n")
              .append("- Preserve exact values exactly. A later step comparing, filtering or citing ")
              .append("them cannot recover a number or an id that has been rounded or paraphrased\n")
              .append("- Say so explicitly if a completed step found nothing, failed, or was ")
              .append("inconclusive; a later step must not assume it succeeded\n")
              .append("- Do not describe steps ").append(liveStep[0]).append(" to ")
              .append(liveStep[1]).append(" as done, and do not attempt them yourself\n\n");
        }
        sb.append("Conversation to summarize:\n");
        for (Message m : messages) {
            sb.append(roleLabel(m)).append(": ").append(messageText(m)).append("\n");
        }
        return sb.toString();
    }

    // -- Folder management -----------------------------------------------------

    public Path conversationsRoot() {
        return paths.root();
    }

    // The conversation's folder under its OWNER's part of the root, read off the row - or, with no
    // row, the visitors' part. Throws for an id that may not name a folder (ConversationPaths).
    private Path resolveConversationDir(String conversationId) {
        String uid = multiUser
                ? conversationRepository.findById(conversationId).map(Conversation::getUserId).orElse(null)
                : null;
        return buildConversationDir(conversationId, uid);
    }

    // Shared by resolveConversationDir (DB-derived userId, once the Conversation row exists) and
    // saveAttachment below (caller-supplied userId, used BEFORE the row exists - the normal case
    // for an attachment on a brand-new conversation). Both must compute the identical path for
    // the same (conversationId, userId) pair, and both go through ConversationPaths, which refuses
    // an id or a user id that would put the folder anywhere but where it belongs.
    private Path buildConversationDir(String conversationId, String userId) {
        return paths.conversationDir(conversationId, userId);
    }

    // The folder to delete with a conversation, or null for an id stored before ids were checked -
    // such a row is still deleted, and the folder it could not have had safely is left alone.
    private Path conversationFolder(String conversationId) {
        try {
            return resolveConversationDir(conversationId);
        } catch (IllegalArgumentException e) {
            log.warn("[SmartMemory] conversation {} has an id that names no folder; none deleted", conversationId);
            return null;
        }
    }

    // Resolves a file previously saved by WebMediaStorage (proxyma-core-tools) under this
    // conversation's web-media/ subfolder, for ChatController's GET media-serving endpoint.
    // fileName is caller-supplied (from the URL path) - reject anything that isn't a bare
    // filename (no separators, no "..") before it ever touches the filesystem, then re-check the
    // resolved path's parent is exactly the web-media dir as a second, path-canonicalization-proof
    // guard against traversal.
    public Optional<Path> resolveWebMediaFile(String conversationId, String fileName) {
        return fileIn(resolveConversationDir(conversationId).resolve("web-media"), fileName);
    }

    // An uploaded attachment, in the conversation OWNER's folder - for serving it back once the
    // caller has passed the conversation's ownership check (ChatController.getAttachment).
    public Optional<Path> resolveStoredAttachment(String conversationId, String fileName) {
        return fileIn(resolveConversationDir(conversationId).resolve("attachments"), fileName);
    }

    // A file runPython produced (OfficeWorkspace), in the conversation OWNER's folder - for
    // ChatController.getOutputFile, after its ownership check.
    public Optional<Path> resolveOutputFile(String conversationId, String fileName) {
        return fileIn(resolveConversationDir(conversationId).resolve("outputs"), fileName);
    }

    // An attachment a chat request names, in the CALLER's own folder - where saveAttachment put it,
    // keyed the same way. That is what makes naming one safe: a request can only ever reach a file
    // its own sender uploaded, and it works before the conversation row exists (the first message
    // of a conversation carries attachments uploaded under an id the client minted).
    public Optional<Path> resolveOwnAttachment(String conversationId, String userId, String fileName) {
        return fileIn(buildConversationDir(conversationId, userId).resolve("attachments"), fileName);
    }

    // One flat file directly inside `dir`, or nothing: no separator, not "." or "..", and the
    // normalized result must still sit in `dir` - the path jail every name from a request goes
    // through. With no separator the name IS its only segment, so ".." is refused as a segment and
    // not as a substring: "Q3..final.xlsx" is an ordinary name saveAttachment keeps verbatim, and
    // refusing it dropped the attachment from the message without a word.
    static Optional<Path> fileIn(Path dir, String fileName) {
        if (fileName == null || fileName.isBlank()
                || fileName.contains("/") || fileName.contains("\\")
                || fileName.equals(".") || fileName.equals("..")) {
            return Optional.empty();
        }
        Path file = dir.resolve(fileName).normalize();
        if (!file.getParent().equals(dir.normalize()) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(file);
    }

    private Path ensureFolder(String conversationId) {
        Path dir = resolveConversationDir(conversationId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("[SmartMemory] could not create folder {}: {}", dir, e.getMessage());
        }
        return dir;
    }

    private void deleteFolder(String conversationId) {
        Path folder = conversationFolder(conversationId);
        if (folder != null) paths.deleteTree(folder);
    }

    // Returns whether the archive actually succeeded - condense() only removes the condensed
    // range from live memory once this returns true (see spec: offload must succeed before the
    // range is considered safe to drop).
    private boolean writeOffloadedMessages(String conversationId, List<Message> messages) {
        Path dir = ensureFolder(conversationId);
        String filename = "offload_" + UUID.randomUUID().toString().replace("-", "") + ".json";
        Path file = dir.resolve(filename);
        try {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (Message m : messages) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("role", roleLabel(m));
                entry.put("content", messageText(m));
                payload.add(entry);
            }
            Files.writeString(file,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload),
                    StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            log.warn("[SmartMemory] failed to write offload file {}: {}", file, e.getMessage());
            return false;
        }
    }

    // -- Helpers ---------------------------------------------------------------

    private static String autoTitle(String message) {
        if (message == null || message.isBlank()) return "New conversation";
        String trimmed = message.trim();
        if (trimmed.length() <= AUTO_TITLE_MAX_CHARS) return trimmed;
        int cut = trimmed.lastIndexOf(' ', AUTO_TITLE_MAX_CHARS);
        return (cut > 0 ? trimmed.substring(0, cut) : trimmed.substring(0, AUTO_TITLE_MAX_CHARS)) + "...";
    }

    private static String roleLabel(Message m) {
        if (m instanceof UserMessage) return "USER";
        if (m instanceof AssistantMessage) return "ASSISTANT";
        if (m instanceof ToolResponseMessage) return "TOOL";
        if (m instanceof SystemMessage) return "SYSTEM";
        return "MESSAGE";
    }

    private static String messageText(Message m) {
        if (m instanceof ToolResponseMessage trm) {
            return trm.getResponses().stream()
                    .map(r -> r.name() + ": " + r.responseData())
                    .collect(Collectors.joining("; "));
        }
        return m.getText() != null ? m.getText() : "";
    }
}
