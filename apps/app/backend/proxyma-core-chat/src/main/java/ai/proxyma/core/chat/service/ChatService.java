package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.external.ExternalTools;
import ai.proxyma.core.chat.external.ExternalToolSource;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.common.audit.ToolAudit;
import ai.proxyma.core.common.conversation.ConversationPaths;
import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.guardrail.GuardrailDecision;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.guardrail.GuardrailStage;
import ai.proxyma.core.guardrail.StreamingScrubber;
import ai.proxyma.core.chat.domain.ApprovalDecision;
import ai.proxyma.core.chat.domain.PermissionMode;
import ai.proxyma.core.chat.domain.EnableDecision;
import ai.proxyma.core.chat.domain.FeedbackSource;
import ai.proxyma.core.chat.dto.ChatMessageResponse;
import ai.proxyma.core.chat.event.ChatEvent;
import ai.proxyma.core.chat.event.ChatTurnCompletedEvent;
import ai.proxyma.core.chat.event.ChatTurnLatencyRecordedEvent;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.model.ConversationShare;
import ai.proxyma.core.chat.dto.SharedConversationResponse;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmCaller;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.provider.gateway.LlmSession;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.system.util.SensitivePaths;
import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import ai.proxyma.core.settings.service.WriteToolRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ai.proxyma.core.common.diagnostics.DiagOp;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@Lazy
public class ChatService implements AgentHost {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    // How long a write-tool call waits for the user to Accept/Accept All/Deny in the chat UI
    // before it's treated as a denial - generous, since it depends on a human actually reading
    // and reacting, not a model/network timeout.
    private static final int APPROVAL_TIMEOUT_MINUTES = 30;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String toJsonString(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    /**
     * Where a turn runs - threads of its own (BlockingWork), not the bounded-elastic scheduler
     * every database read shares. A turn blocks for minutes.
     */
    public static final Scheduler TURNS = Schedulers.fromExecutor(BlockingWork.executor());

    private static String maskUserId(String userId) {
        return userId != null ? userId.substring(0, Math.min(8, userId.length())) + "..." : "anon";
    }

    // The effective prompt ({{PRODUCT_NAME}} placeholder, filled from ProductIdentity) is resolved by
    // SystemPromptService: the built-in default, or an admin's saved custom prompt. There is one
    // prompt now, not a provider-selected smart/light pair.
    //
    // userDisplayName is appended dynamically (not baked into the prompt via a placeholder, since
    // it's absent for anonymous/desktop-mode chats) so the agent knows who it's talking to and can
    // act more naturally - e.g. greet the user by name - without contradicting a custom prompt that
    // doesn't expect a name placeholder at all.
    private String resolveSystemPrompt(String userDisplayName, String userId, String conversationId) {
        // Delegation guidance is put in by resolve() when this deployment can actually delegate -
        // `delegate` is not advertised as a tool when it cannot (ToolSkillGating), so the prompt must
        // not name it. Read here and persisted in the SystemMessage like everything else below - and
        // written again at the start of a later turn if any of it has changed since. See
        // refreshSystemPrompt.
        boolean canDelegate = subAgentService.isAvailable();
        String prompt = SystemPromptService.instructions(systemPromptService.read(),
                productIdentity.getProductName(), canDelegate);
        // Injected instead of a getCurrentDateTime tool - the agent needs "what's today" far more
        // often than a tool round-trip is worth, and this covers it for free. Resolved at
        // conversation start and persisted with the SystemMessage (same staleness tradeoff as the
        // skills block below), so a conversation resumed days later still shows its start date -
        // acceptable, and the only case where knowing the exact live time would have mattered.
        java.time.LocalDate today = java.time.LocalDate.now();
        prompt += SystemPromptService.DATE_LEAD + today + " ("
                + today.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + ").";
        if (userDisplayName != null && !userDisplayName.isBlank()) {
            prompt += "\n\nYou are talking with " + userDisplayName + ". You may address them by name " +
                    "when it feels natural (e.g. a greeting), but don't force it into every reply.";
        }
        // What a product module says about THIS conversation - the key-account panel names the one
        // account it works on (ChatModule). Identity only, so refreshSystemPrompt's whole-prompt
        // comparison does not see it move between turns.
        prompt += moduleContext(conversationId, userId);
        // Appended last, and - unlike the block this replaced - never rewritten afterwards. It
        // advertises every skill visible to this user by name and short description only; a body
        // reaches the model as a message instead (readSkill's result, or applyPickedSkills below).
        // What this installation's license leaves out, and its limits - before the skills, which
        // mark the skills it leaves out. Empty on a tier with nothing left out. See LicenseToolScope.
        String license = licenseToolScope != null ? licenseToolScope.promptSection() : "";
        if (!license.isBlank()) prompt += "\n\n" + license;
        String skills = chatSkillService.formatForSystemPrompt(userId);
        if (!skills.isBlank()) prompt += "\n\n" + skills;
        // Same point in the same method, and for the same cache reason: the system prompt is the
        // provider's cached prompt prefix, so appending a workflow catalog later - when the user
        // first types "/" - would cascade-invalidate that cache on every subsequent turn of the
        // conversation. The block only tells the agent which workflows EXIST; it cannot start one,
        // and says so, because a run is driven from outside the model's control.
        // Nothing is said about workflows while the feature is off - a model told they exist will
        // offer them to the user, which is a door to a feature with no handle on it.
        String workflows = workflowsEnabled ? workflowService.formatForSystemPrompt(userId) : "";
        if (!workflows.isBlank()) prompt += "\n\n" + workflows;
        // Said once more, last: several thousand words of skills now stand between the model and
        // the section that told it how to work. See SystemPromptService.DELEGATION.
        if (canDelegate) prompt += "\n\n" + SystemPromptService.DELEGATION_REMINDER;
        // The system-prompt canary, when it is switched on. Empty otherwise, and appended LAST so
        // that a leak has to recite everything above it to reach the marker - a truncated recital
        // of the first few lines is a leak this cannot see either way, and putting the marker
        // first would only make it the easiest line to leak.
        //
        // Nothing anywhere tells the model what it is. See GuardrailService.canaryLine.
        return prompt + guardrailService.canaryLine();
    }

    /**
     * Gives a conversation the system message this build would start it with, when the one it
     * holds is another: written by an earlier version, before the prompt was edited, before a
     * skill was added or its description changed. See
     * {@link SystemPromptService#sameButForDate}.
     *
     * <p>The row is rewritten in place, at the start of a turn. That costs the provider's cached
     * prefix once per conversation per change, which is the price of the model reading the
     * instructions it is meant to follow. Nothing is rewritten while nothing has changed - a new
     * day alone is not a change - so the cache holds turn after turn.
     */
    void refreshSystemPrompt(String conversationId, List<Message> history, String userDisplayName, String userId) {
        if (history.isEmpty() || !(history.get(0) instanceof SystemMessage held)) return;
        String current = resolveSystemPrompt(userDisplayName, userId, conversationId);
        if (SystemPromptService.sameButForDate(held.getText(), current)) return;
        if (chatMemory.replaceSystemPrompt(conversationId, current)) {
            log.info("[Chat] conversation {} was given the current system prompt", conversationId);
        }
    }

    // Each module's context, each a paragraph of its own; "" when none applies. A module that fails
    // to answer adds nothing rather than failing the turn - and since the same failure happens again
    // next turn, it cannot make refreshSystemPrompt flip the prompt back and forth either.
    private String moduleContext(String conversationId, String userId) {
        if (conversationId == null || chatModules.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (ChatModule module : chatModules) {
            try {
                module.systemContext(conversationId, userId).filter(c -> !c.isBlank())
                        .ifPresent(c -> out.append("\n\n").append(c.strip()));
            } catch (RuntimeException e) {
                log.warn("[Chat] a chat module could not describe conversation {}: {}", conversationId, e.getMessage());
            }
        }
        return out.toString();
    }

    // Conversation.enabledSkillIds is a plain CSV column; these two keep its parse/format in one
    // place, since it is read and written from more than one path.
    private static Set<String> parseSkillIds(String csv) {
        if (csv == null || csv.isBlank()) return new LinkedHashSet<>();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String formatSkillIds(Set<String> ids) {
        return ids.isEmpty() ? null : String.join(",", ids);
    }

    private Set<String> loadedSkillIds(String conversationId) {
        return chatMemory.findConversation(conversationId)
                .map(Conversation::getEnabledSkillIds)
                .map(ChatService::parseSkillIds)
                .orElseGet(LinkedHashSet::new);
    }

    // Skill names are arbitrary user text and may contain a comma, but never a newline
    // (ChatSkillService.sanitizeLine strips those) - so unlike the id CSV above, this set is stored
    // newline-separated.
    private static Set<String> parseSkillNames(String stored) {
        if (stored == null || stored.isEmpty()) return new LinkedHashSet<>();
        return Arrays.stream(stored.split("\n")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // The skills whose tools are unlocked for this conversation (see ToolSkillGating). Read from the
    // persisted column, falling back to a scan of message history ONLY while that column is still
    // null - i.e. for a conversation that predates it, so an upgrade doesn't strip tools the agent
    // had already unlocked. The first recordSkillReads()/unlockSkillTools() write makes the column
    // authoritative from then on.
    private Set<String> unlockedSkillNames(String conversationId) {
        return unlockedSkillNames(conversationId, storedUnlockedSkillNames(conversationId));
    }

    // Split from the above so a caller that needs to know whether the column has ever been written
    // (recordSkillReads) can tell that apart from an empty set - null and "" mean different things
    // here, see Conversation.unlockedSkillNames.
    private String storedUnlockedSkillNames(String conversationId) {
        return chatMemory.findConversation(conversationId)
                .map(Conversation::getUnlockedSkillNames)
                .orElse(null);
    }

    private Set<String> unlockedSkillNames(String conversationId, String stored) {
        if (stored != null) return parseSkillNames(stored);
        // getWithoutCondense, not get(): triggering a condensation's summarizer call from inside a
        // tool-visibility lookup would be badly out of place.
        return new LinkedHashSet<>(
                toolSkillGating.scanUnlockedGroups(chatMemory.getWithoutCondense(conversationId)));
    }

    // Records the skills this iteration's assistant message asked to read, so their tools stay
    // advertised for the rest of the conversation regardless of what later condensation does to the
    // message that requested them. Called once per iteration from react(), on the thread that owns
    // the turn - deliberately not from inside callTools(), which runs tool calls in parallel and
    // would need locking to do the same read-modify-write safely.
    private void recordSkillReads(String conversationId, AssistantMessage assistant) {
        if (!assistant.hasToolCalls()) return;
        Set<String> read = toolSkillGating.scanUnlockedGroups(List.of(assistant));
        if (read.isEmpty()) return;
        String stored = storedUnlockedSkillNames(conversationId);
        Set<String> unlocked = unlockedSkillNames(conversationId, stored);
        boolean changed = unlocked.addAll(read);
        // `changed` alone is not enough to decide whether to write. `assistant` was already appended
        // to memory before this runs, so on a not-yet-backfilled conversation the history scan
        // inside unlockedSkillNames() has ALREADY seen this very readSkill call and addAll() reports
        // nothing new - and the column would stay null forever, leaving the conversation on the
        // history scan this whole mechanism exists to get off. The first read of any skill is what
        // materializes the column.
        if (stored != null && !changed) return;
        chatMemory.setUnlockedSkillNames(conversationId, String.join("\n", unlocked));
    }

    // Loads a skill into the conversation by putting its full instructions into the history as a
    // message, once, at the point it was loaded - never into the system prompt, which stays exactly
    // as it was resolved on turn 1. This is the same shape a model-initiated readSkill already had:
    // the body arrives as message content, stays there for the rest of the conversation, and is
    // never re-read from disk afterwards.
    //
    // A skill already in the ledger gets a one-line note instead of a second copy of its
    // instructions, so re-picking one every message costs ~20 tokens rather than another body.
    // The note is still worth sending: the user picked the skill for THIS message, and saying so
    // is what re-focuses the model on instructions that may be far up the history by now.
    private void applyPickedSkills(String conversationId, Set<String> requestedSkillIds, String userId) {
        if (requestedSkillIds.isEmpty()) return;
        List<ChatSkillService.InjectableSkill> picked =
                chatSkillService.injectableSkills(requestedSkillIds, userId);
        if (picked.isEmpty()) return;

        Set<String> loaded = loadedSkillIds(conversationId);
        List<Message> injected = new ArrayList<>();
        List<String> fresh = new ArrayList<>();
        for (ChatSkillService.InjectableSkill skill : picked) {
            if (loaded.contains(skill.id())) {
                injected.addAll(SmartChatMemory.skillReminderMessages(skill.name()));
            } else {
                injected.addAll(SmartChatMemory.skillBodyMessages(skill.name(), skill.body()));
                loaded.add(skill.id());
                fresh.add(skill.name());
            }
        }
        chatMemory.add(conversationId, injected);
        if (!fresh.isEmpty()) {
            chatMemory.setEnabledSkillIds(conversationId, formatSkillIds(loaded));
            log.info("[Chat] loaded skill(s) {} into conversationId={}", fresh, conversationId);
        }
        // Picking unlocks the skill's TOOLS as well as its instructions. Done for re-picks too (it
        // is idempotent), so a conversation whose history was cleared still gets its tools back.
        unlockSkillTools(conversationId, picked.stream()
                .map(ChatSkillService.InjectableSkill::name).toList());
    }

    // Unlocks a picked skill's tools directly, rather than waiting for the model to call readSkill
    // for a skill the user has already chosen. Without this a picked connector skill would leave
    // its tools invisible for the whole conversation: ToolSkillGating advertises them only for a
    // name in this set, and a pick puts the instructions into history without ever going through
    // readSkill. Picking is at least as explicit a signal as the readSkill call it stands in for -
    // it is a person choosing the skill, not the model guessing from a one-liner.
    private void unlockSkillTools(String conversationId, List<String> skillNames) {
        if (skillNames.isEmpty()) return;
        String stored = storedUnlockedSkillNames(conversationId);
        Set<String> unlocked = unlockedSkillNames(conversationId, stored);
        boolean changed = false;
        for (String name : skillNames) {
            if (unlocked.stream().noneMatch(existing -> existing.equalsIgnoreCase(name))) {
                unlocked.add(name);
                changed = true;
            }
        }
        // Same reason recordSkillReads() writes on a null column even when nothing changed: leaving
        // it null keeps the conversation on unlockedSkillNames()'s history-scan fallback.
        if (stored != null && !changed) return;
        chatMemory.setUnlockedSkillNames(conversationId, String.join("\n", unlocked));
    }

    private static final String FORCED_ANSWER_INSTRUCTION =
            "IMPORTANT: This is your final opportunity to respond. " +
            "Based on all search results gathered so far, provide your final answer now. " +
            "Do NOT call any more search tools.";

    /**
     * Whether a stored user or assistant row is the product's own scaffolding rather than words
     * either party said: a condensation summary or its acknowledgement, a loaded skill, a workflow's
     * plan or step brief, sub-agents' reports, the last step's nudge. The transcript draws each
     * of these its own way (see transcript); anything that only wants what was SAID - an earlier
     * conversation read back to the agent - leaves them all out.
     */
    static boolean isScaffolding(String content) {
        return SmartChatMemory.isCondensedSummaryMarker(content) || SmartChatMemory.isSkillMarker(content)
                || SmartChatMemory.isWorkflowMarker(content) || SubAgentService.isReportMessage(content)
                || FORCED_ANSWER_INSTRUCTION.equals(content);
    }

    private final LlmGateway llmGateway;
    private final SettingsService settingsService;
    private final GlobalQuotaGate globalQuotaGate;
    private final McpGuardService mcpGuard;
    private final SmartChatMemory chatMemory;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductIdentity productIdentity;
    private final ConversationUsageRecorder usageRecorder;
    private final List<ToolCallback> toolCallbacks;
    // Subset of toolCallbacks NOT gated behind a skill read (see ToolSkillGating) - always
    // advertised to the model. Everything else only appears once its matching skill has been
    // read earlier in the same conversation.
    private final List<ToolCallback> coreToolCallbacks;
    private final ChatSkillService chatSkillService;
    // Setter-injected and optional, like the graph worker elsewhere: tests build this service by hand.
    private LicenseToolScope licenseToolScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLicenseToolScope(LicenseToolScope licenseToolScope) {
        this.licenseToolScope = licenseToolScope;
    }

    // The product modules built into this deployable (ChatModule): none on Desktop and Home Server.
    // Setter-injected and optional, for the same reason as the license scope above.
    private List<ChatModule> chatModules = List.of();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setChatModules(List<ChatModule> chatModules) {
        this.chatModules = chatModules == null ? List.of() : List.copyOf(chatModules);
    }
    private final ToolSkillGating toolSkillGating;
    private final SystemPromptService systemPromptService;
    private final FeedbackRepository feedbackRepository;
    private final ConversationShareRepository conversationShareRepository;
    private final WorkflowRunService workflowRunService;
    private final WorkflowService workflowService;

    /**
     * Whether the workflow feature is on at all. OFF by default, and every workflow surface in
     * both editions is gated on it - the Settings tab, the "/" list, the step table, and the
     * controllers.
     *
     * <p>A FLAG RATHER THAN A REVERT, deliberately. Nothing is deleted: the definitions on disk,
     * the run tables and the engine underneath all stay, so turning it back on is one property
     * rather than a re-merge.
     *
     * <p>The gate has to cover more than a menu. A workflow the model is TOLD about gets offered
     * to the user in prose ("there is a workflow for that, type /Name"), and a name the composer
     * still resolves still runs one - so hiding the UI alone would leave a feature that is
     * invisible and reachable, which is worse than either.
     */
    @Value("${proxyma.features.workflows:false}")
    private boolean workflowsEnabled;
    private final WorkflowRunner workflowRunner;
    private final ChatEventSink chatEventSink;
    private final ConversationTitleService conversationTitleService;
    private final ModelPricingService modelPricingService;
    private final GuardrailService guardrailService;
    // Only read to decide whether the system prompt's delegation guidance applies - the `delegate`
    // tool itself reaches this loop as an ordinary ToolCallback like any other. Injectable without a
    // cycle: SubAgentService depends on nothing from this class (it already arrives indirectly via
    // ToolSkillGating, which needs it for the same availability check).
    private final SubAgentService subAgentService;
    private final ConcurrentHashMap<String, AtomicBoolean> abortFlags = new ConcurrentHashMap<>();
    // Stop requests that arrived before the turn they were meant for had registered its abort flag,
    // keyed by conversationId and stamped with when they arrived. See stop().
    private final ConcurrentHashMap<String, Long> pendingStops = new ConcurrentHashMap<>();
    // Turns started (POST /chat answered, or a report's continuation scheduled) that have not
    // registered their abort flag yet, stamped with when they were started. Only such a turn can
    // be stopped "in advance", and only by a stop that came after it was started - see stop().
    private final ConcurrentHashMap<String, Long> turnsDispatched = new ConcurrentHashMap<>();
    // How long an unconsumed stop request stays meaningful. It has to outlast the longest wait
    // that can happen BEFORE registration - lockMemory() behind a condensation, whose summarizer
    // call is minutes - while still expiring, because the marker is otherwise consumed by the next
    // turn to register on this conversation and a stray one would abort a turn nobody stopped.
    private static final long PENDING_STOP_TTL_MS = Duration.ofMinutes(5).toMillis();
    // Pending human-approval gates for write-tool calls, keyed by the tool call's trackingId
    // (== ToolCall.id(), or a generated UUID when the model didn't supply one - see callTools).
    // Completed by resolveApproval() when POST /chat/conversations/{id}/tool-approval arrives.
    private final ConcurrentHashMap<String, CompletableFuture<ApprovalDecision>> pendingApprovals = new ConcurrentHashMap<>();
    // Conversations where the user has chosen "accept all" - suppresses further approval prompts
    // for the rest of that conversation. A brand-new conversationId simply has no entry here, so
    // "reset on new conversation" needs no extra code beyond removing stale entries on delete/clear.
    private final Set<String> acceptAllConversations = ConcurrentHashMap.newKeySet();
    // The permission mode the latest message in each conversation was sent with (PermissionMode).
    // Absent is ASK. Removed with the conversation, like the accept-all set above.
    private final ConcurrentHashMap<String, PermissionMode> permissionModes = new ConcurrentHashMap<>();
    // Who started each conversation's latest turn, and when - so a person can be told which of THEIR
    // conversations are still working (activeConversationsOf) without the conversation having been
    // persisted yet, which a first turn is not until it ends. One small entry per conversation.
    // `surface` is the conversation's (ConversationSurface) - null for the chat page's own.
    private record TurnOwner(String owner, long startedAt, String surface) {}
    private final ConcurrentHashMap<String, TurnOwner> turnOwners = new ConcurrentHashMap<>();
    // Pending live-enable gates for a write-tool call blocked solely by the user's own missing
    // personal consent (see McpGuardService.isBlockedOnlyByUserConsent), keyed the same way as
    // pendingApprovals. Completed by resolveEnableDecision() when POST
    // /chat/conversations/{id}/tool-enable-decision arrives.
    private final ConcurrentHashMap<String, CompletableFuture<EnableDecision>> pendingEnableDecisions = new ConcurrentHashMap<>();
    // Which conversation each pending approval or enable decision belongs to (SEC-49). The two
    // maps above are keyed by the tool call alone, so a decision posted under ANY conversation id
    // - one the caller could open - resolved a pending write in someone else's turn. Decisions are
    // only accepted from the conversation that asked.
    private final ConcurrentHashMap<String, String> pendingDecisionConversation = new ConcurrentHashMap<>();

    /**
     * The longest a tool may run, since 2026-09-24 (maintainer: "60s, should not more"). Before,
     * a call was bounded only by the 300s HTTP read timeout, so one slow endpoint held the whole
     * turn for five minutes. Measured from when the tool STARTS EXECUTING - time spent waiting for
     * the user's approval or consent is the user's, not the tool's. Delegation is exempt: it is an
     * agent running many calls, each already bounded, not a tool call.
     */
    static final int TOOL_CALL_TIMEOUT_SECONDS = 60;
    /**
     * The tools held to the SEARCH limit instead (ChatSettings.searchTimeoutSeconds, five minutes
     * unless set) - maintainer's request, 2026-10-04. Each runs the hybrid pipeline over many
     * collections, reranking included, which on a machine also crawling took half a minute.
     */
    static final Set<String> SEARCH_TOOLS = Set.of(
            "searchIndexedSources", "searchKnowledgeGraph", "findGraphPath", "findGraphPeople",
            "findGraphTimeline", "findGraphThemes",
            "searchConversations", "recall");

    /** How long this tool may execute before it is abandoned. */
    int toolTimeoutSeconds(String toolName) {
        if (!SEARCH_TOOLS.contains(toolName)) return TOOL_CALL_TIMEOUT_SECONDS;
        try {
            return settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class).searchTimeoutSeconds();
        } catch (Exception e) {
            return ChatSettings.DEFAULT_SEARCH_TIMEOUT_MINUTES * 60;
        }
    }
    // trackingId -> System.nanoTime() when the tool began executing, read by joinRespectingAbort.
    private final ConcurrentHashMap<String, Long> toolExecutionStarted = new ConcurrentHashMap<>();
    // Which workflow step, if any, the conversation's current turn is inside. Two things read it,
    // both of which sit deep inside the ReAct loop and neither of which is worth another parameter
    // on react()'s already long signature (it loops, so a parameter is threaded through every
    // iteration as well):
    //
    //   - callTool() stamps every tool event with `stepCallId` as its parentCallId, which is what
    //     nests a step's tool calls under its row - the frontend already nests on parentCallId
    //     because sub-agent calls needed exactly this, so no new rendering code is involved.
    //   - react()'s text flush drops the event entirely while `streamText` is false, i.e. for
    //     every step but the last. An intermediate step's output belongs in its own row, not in a
    //     bubble of its own.
    //
    // Keyed by conversationId and safe to be: ChatEventSink cannot survive two turns in flight for
    // one conversation anyway (see useChat's stop() comment), so a conversation has at most one
    // running step at a time. Entered and cleared around each step by chat()'s workflow branch;
    // the absence of an entry is the ordinary, non-workflow case.
    private record WorkflowStepContext(String stepCallId, boolean streamText) {}
    private final ConcurrentHashMap<String, WorkflowStepContext> workflowSteps = new ConcurrentHashMap<>();
    // The last message id each conversation held when its RUNNING turn began: every row the turn
    // writes has a higher one. Read by getMessagesWithToolCalls, which stops at the turn's opening
    // message while this is set - the rest of a running turn reaches the page on the event stream,
    // which replays it from its start, and a transcript that also carried it showed every finished
    // step twice on a page that reconnected. Removed BEFORE the turn's DONE is published, not in
    // the finally after it: the page fetches the transcript the moment it sees DONE, and has to be
    // given the whole of it.
    private final ConcurrentHashMap<String, Long> turnFloors = new ConcurrentHashMap<>();
    // When each conversation's last turn ended - what the Auto prompt cache reads the reader's pace
    // from (ChatSettings.PromptCache.forTurn). In memory only: after a restart the first turn of a
    // conversation knows no pace and asks for five minutes, which is what it always asked for.
    private final ConcurrentHashMap<String, Instant> lastTurnEnded = new ConcurrentHashMap<>();
    // The display name each conversation's latest user turn carried, for the turns nobody sends: a
    // continuation started for a sub-agent's report has no request to read a name from. Without it
    // the continuation's system prompt lacked the "You are talking with" line the user's turns
    // have, so each report rewrote the prompt without it and the user's next turn rewrote it back -
    // two misses of the provider's cached prefix per background report. In memory only, like
    // lastTurnEnded.
    private final ConcurrentHashMap<String, String> turnDisplayNames = new ConcurrentHashMap<>();

    public ChatService(LlmGateway llmGateway,
                       SettingsService settingsService,
                       GlobalQuotaGate globalQuotaGate,
                       McpGuardService mcpGuard,
                       SmartChatMemory chatMemory,
                       ApplicationEventPublisher eventPublisher,
                       ProductIdentity productIdentity,
                       ConversationUsageRecorder usageRecorder,
                       ToolCallbackProvider toolCallbackProvider,
                       ChatSkillService chatSkillService,
                       ToolSkillGating toolSkillGating,
                       SystemPromptService systemPromptService,
                       FeedbackRepository feedbackRepository,
                       ConversationShareRepository conversationShareRepository,
                       ChatEventSink chatEventSink,
                       ConversationTitleService conversationTitleService,
                       ModelPricingService modelPricingService,
                       SubAgentService subAgentService,
                       WorkflowRunService workflowRunService,
                       WorkflowService workflowService,
                       WorkflowRunner workflowRunner,
                       GuardrailService guardrailService) {
        this.workflowRunService = workflowRunService;
        this.workflowService = workflowService;
        this.workflowRunner = workflowRunner;
        this.subAgentService = subAgentService;
        this.llmGateway = llmGateway;
        this.settingsService = settingsService;
        this.globalQuotaGate = globalQuotaGate;
        this.mcpGuard = mcpGuard;
        this.chatMemory = chatMemory;
        this.eventPublisher = eventPublisher;
        this.productIdentity = productIdentity;
        this.usageRecorder = usageRecorder;
        this.toolCallbacks = List.of(toolCallbackProvider.getToolCallbacks());
        this.toolSkillGating = toolSkillGating;
        Set<String> gatedNames = toolSkillGating.allGatedToolNames();
        this.coreToolCallbacks = this.toolCallbacks.stream()
                .filter(tc -> !gatedNames.contains(tc.getToolDefinition().name()))
                .toList();
        this.chatSkillService = chatSkillService;
        this.systemPromptService = systemPromptService;
        this.feedbackRepository = feedbackRepository;
        this.conversationShareRepository = conversationShareRepository;
        this.chatEventSink = chatEventSink;
        this.conversationTitleService = conversationTitleService;
        this.modelPricingService = modelPricingService;
        this.guardrailService = guardrailService;
        log.debug("[Chat] registered {} tool(s), {} always advertised (core): {}", toolCallbacks.size(),
                coreToolCallbacks.size(), coreToolCallbacks.stream().map(tc -> tc.getToolDefinition().name()).toList());
    }

    // -- Public API ------------------------------------------------------------

    public record ChatResult(String answer, String conversationId, int iterations) {}

    // Accumulates whole-turn latency data across every react() iteration of a single chat() call
    // - what ChatTurnLatencyRecordedEvent and the daily diagnostics are made from, and all of it is
    // read. Passed through the react() loop, so no restructuring of the ReAct loop is needed.
    // streamStartNanos/firstTokenNanos use compareAndSet(-1, ...) so only the FIRST iteration's
    // values stick - later iterations (tool-call follow-ups) touch these fields too but are no-ops,
    // which is exactly "time to first token of the turn", not per-iteration.
    //
    // The per-turn token and cost totals, the spend staged for a turn by condensation, sub-agents
    // and search, and a turn-long list of every tool call's result are gone (2026-10-14): they fed
    // an anonymized per-turn record whose publisher was removed on 2026-08-08, and cost a pricing
    // lookup per step and every tool result held for the whole turn, read by nothing.
    private static final class TurnLatencyTracker {
        final long turnStartNanos = System.nanoTime();
        final AtomicLong streamStartNanos = new AtomicLong(-1);
        final AtomicLong firstTokenNanos = new AtomicLong(-1);
        final AtomicInteger retryCount = new AtomicInteger(0);
        final AtomicLong outputTokenCount = new AtomicLong(0);
        volatile boolean success = true;
    }

    // `surface` (ConversationSurface) takes effect only when this message creates the conversation.
    public ChatResult chat(String conversationId, String message, List<String> mentions,
                           List<String> attachmentPaths, String userId,
                           String userDisplayName, String guestSessionId, List<String> skillMentions,
                           String surface) {
        return turn(conversationId, message, mentions, attachmentPaths, userId, userDisplayName, guestSessionId,
                skillMentions, false, surface);
    }

    // One turn. `continuation` is a turn nobody sent a message to start: a background sub-agent
    // reported while the conversation was idle, and the reports are what opens it (see wake()).
    // Everything a turn is - the budget gate, the lock, the abort flag, the loop, the output
    // guardrail, the latency record - is the same for both; a continuation has no user message to
    // screen, to name a conversation after, or to pick skills and workflows with.
    private ChatResult turn(String conversationId, String message, List<String> mentions,
                            List<String> attachmentPaths, String userId,
                            String userDisplayName, String guestSessionId, List<String> skillMentions,
                            boolean continuation, String surface) {
        // Refused before anything is persisted or any provider is contacted, so an exhausted
        // budget costs nothing and leaves no half-written turn. The per-user quota deliberately
        // never blocks chat; this one is the deployment's bill and does.
        try {
            globalQuotaGate.checkOrRefuse("this message");
        } catch (RuntimeException e) {
            turnsDispatched.remove(conversationId);
            throw e;
        }

        // The INPUT guardrail, placed here for the same reason the quota gate is: before anything
        // is persisted and before any provider is contacted, so a refused message costs nothing
        // and leaves no half-written turn behind. Everything below this line either writes to the
        // conversation or spends money.
        //
        // A refusal deliberately persists NOTHING - not the user's message, not a refusal turn.
        // The alternative is writing the very text a rule objected to into the conversation store,
        // which for the secret rules is the control writing down the credential it exists to
        // catch. The client is told over the event stream instead.
        //
        // REDACT continues the turn on the rewritten text: `message` is reassigned, so everything
        // downstream - the persisted user row, the prompt, the title, the telemetry - sees the
        // redacted version and no caller has to remember to use it.
        //
        // Not for a continuation: there is no user message, and the report that opens it crossed
        // the retrieval boundary when the sub-agent handed it over (SubAgentService.settle).
        if (!continuation && guardrailService.enabled(GuardrailStage.INPUT)) {
            GuardrailDecision decision = checkInput(message, userId, conversationId);
            if (decision.isBlocked()) {
                turnsDispatched.remove(conversationId);
                String refusal = guardrailService.refusalMessage(decision, GuardrailStage.INPUT);
                publishGuardrail(conversationId, GuardrailStage.INPUT, decision, refusal);
                eventPublisher.publishEvent(new ChatEvent(conversationId,
                        chatEventSink.nextSequence(conversationId),
                        ChatEvent.Type.DONE.value(), null, null, ""));
                return new ChatResult(refusal, conversationId, 0);
            }
            if (decision.verdict() == GuardrailDecision.Verdict.REDACT) {
                message = decision.text();
            }
        }
        // Serialize this turn against any whole-conversation memory rewrite already in flight -
        // in practice a manual "condense now" the user triggered and then carried on typing over.
        // Without this the turn ran fully in parallel with the condensation's (minutes-long)
        // summarizer call, and the saveAll() that call ends in - delete-all-then-reinsert of a
        // snapshot taken BEFORE the turn existed - deleted this turn's user message, tool calls and
        // tool results from under it. See SmartChatMemory.memoryLocks. Taken before abortFlags.put
        // below so a second turn waiting here has not yet replaced the running turn's abort flag.
        //
        // The wait is bracketed with the same condense-start/condense-end events the condensation
        // itself publishes, so the UI shows "Condensing conversation..." for it instead of an
        // unexplained pause - but only when the thing being waited on actually IS a condensation
        // (isCondensing); waiting on another turn has no honest label. Both are published even
        // though the condensation publishes its own end event, so a condensation that finishes
        // between the check and the acquisition can't leave the client showing it indefinitely.
        if (!continuation) {
            if (userDisplayName != null && !userDisplayName.isBlank()) {
                turnDisplayNames.put(conversationId, userDisplayName);
            } else {
                turnDisplayNames.remove(conversationId);
            }
        }
        boolean waitingOnCondense = chatMemory.isCondensing(conversationId);
        if (waitingOnCondense) publishCondensing(conversationId, true);
        boolean memoryLocked = chatMemory.lockMemory(conversationId);
        if (waitingOnCondense) publishCondensing(conversationId, false);

        AtomicBoolean abort = new AtomicBoolean(false);
        // A turn takes what is typed WHILE IT RUNS. Anything accepted before this instant was
        // accepted for something else, and the page still holds it to send - see inject().
        injected.remove(conversationId);
        abortFlags.put(conversationId, abort);
        // The conversation has a turn now, so nothing has to start one for a report any more: this
        // turn takes whatever is owed at its next step. See wake().
        waking.remove(conversationId);
        // Honor a Stop that was pressed while this turn was still getting here - most likely during
        // the lockMemory() wait just above, which is where a turn can sit for minutes behind a
        // condensation while the UI shows a Stop button that had nothing to set. Read AFTER the put
        // above, and stop() re-reads abortFlags after writing its marker, so whichever order the two
        // threads interleave in, one of them sees the other. Only a stop that came after this turn
        // was started is this turn's - an earlier one was meant for a turn that has ended.
        Long dispatchedAt = turnsDispatched.remove(conversationId);
        Long stopRequestedAt = pendingStops.remove(conversationId);
        if (stopRequestedAt != null && dispatchedAt != null && stopRequestedAt >= dispatchedAt
                && System.currentTimeMillis() - stopRequestedAt <= PENDING_STOP_TTL_MS) {
            abort.set(true);
            log.info("[Chat] turn starts already stopped - Stop arrived before it registered, conversationId={}",
                    conversationId);
        }
        // Message length only, never raw content - conversation content must never land in logs.
        log.info("[Chat] request conversationId={} userId={} mentions={} messageLen={}",
                conversationId, maskUserId(userId), mentions != null ? mentions.size() : 0,
                message != null ? message.length() : 0);

        SpendSubject previousSubject = null;
        // Set by a continuation that found nothing it could deliver - see the finally.
        boolean deliveredNothing = false;
        try {
            SearchContext.setUserId(userId);
            // On the turn's own thread too, not only on its tools' threads: what is recorded from
            // here - a provider that kept refusing for its rate limit, an output guardrail check -
            // names the conversation.
            SearchContext.setConversationId(conversationId);
            // Who this turn's model calls are billed to. SpendSubject.user() maps an absent id to
            // ANONYMOUS rather than to nobody, which is the whole point: a guest's tokens are as
            // real as anyone's, and "we could not tell" and "there was no account" are different
            // facts that must not share a bucket. Everything the turn reaches - sub-agents,
            // condensation, query expansion, reranking, the title - inherits it from here.
            previousSubject = SpendContext.current();
            SpendContext.set(SpendSubject.user(userId));
            // Also set on this (the react()/condense() calling) thread, not just inside
            // callTools()'s worker threads below - SmartChatMemory.condense() runs synchronously
            // here and needs SearchContext.isAborted() to see a Stop request too.
            SearchContext.setAbortFlag(abort);

            ChatSettings settings = settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class);
            String provider = settings.getProvider();
            String model = settings.getModel();
            int maxOutputTokens = settings.getMaxOutputTokens();
            int maxIterations = ChatSettings.iterationCeiling(settings.getMaxIterations());
            ChatSettings.Tone tone = settings.getTone();

            // What the user picked in the composer for THIS message. Every skill visible to them is
            // already advertised by name in the system prompt (see resolveSystemPrompt); a pick is
            // the stronger signal that skips the model's own judgement and loads the body outright,
            // which applyPickedSkills() below does by putting it into the conversation as a message.
            // The conversation's stored id set is the ledger of what has already been loaded that
            // way, so a re-pick costs a one-line note instead of a second copy of the instructions.
            // A "/Name" chip is ONE namespace to the person typing it, and either kind of thing can
            // be behind it. Workflows are lifted out of the list first so everything below this
            // line deals only in skills; the two names cannot collide, because creation of either
            // rejects a name already taken by the other (see WorkflowController/ChatSkillController).
            // A name with nothing visible behind it resolves to neither and passes through as
            // ordinary text - the same fail-soft stance resolveIdsForNames takes for a stale chip
            // left in a draft.
            // With the feature off a "/Name" chip resolves to a skill or to nothing, exactly as it
            // did before workflows existed. Gated HERE rather than inside resolveWorkflowMention so
            // the log line that class writes about ignored picks does not fire either.
            WorkflowService.WorkflowDetail workflow = workflowsEnabled && !continuation
                    ? resolveWorkflowMention(skillMentions, userId) : null;
            List<String> plainSkillMentions = skillMentions == null ? List.of()
                    : skillMentions.stream()
                            .filter(n -> workflow == null || !workflow.name().equalsIgnoreCase(n))
                            .toList();
            Set<String> requestedSkillIds = chatSkillService.resolveIdsForNames(plainSkillMentions, userId);
            boolean isNewConversation = !continuation
                    && chatMemory.ensureConversation(conversationId, message, userId, guestSessionId, null, surface);

            // Fired here, immediately after the first user message creates the conversation, so the
            // history list stops showing a truncated placeholder while the answer is still being
            // written (ConversationTitleService pushes a 'title' event when it resolves, mid-turn).
            // This used to run AFTER react() instead, because both calls hit the same
            // GPU-constrained chat model and the title call kept losing that race - the reason it
            // is safe to move back is that titles now run on the SUB-AGENT's model whenever one is
            // configured, so the two no longer compete. With no sub-agent configured it does fall
            // back to the main model and that contention returns; the cost of losing is unchanged
            // and cosmetic (the placeholder title stands, and it never blocks the turn).
            if (isNewConversation) {
                conversationTitleService.generateTitleAsync(conversationId, message);
            }

            // Product name, user display name, personality, and skills are all resolved here and
            // baked into the persisted SystemMessage the first time this conversation has no
            // messages yet (see resolveSystemPrompt's own javadoc for why each piece is appended
            // where it is). A conversation that already has one is given the current one when
            // any of that has changed since - an update, an edited prompt, a new skill.
            List<Message> existing = chatMemory.get(conversationId);
            if (existing.isEmpty()) {
                String systemPrompt = resolveSystemPrompt(userDisplayName, userId, conversationId);
                chatMemory.add(conversationId, List.of(new SystemMessage(systemPrompt)));
            } else {
                refreshSystemPrompt(conversationId, existing, userDisplayName, userId);
            }
            // Runs on every turn, new conversation or not: the system message above lists skills
            // by name only, so the BODY of one picked on turn 7 lands in the history rather than
            // in the prompt. Ordered before the user turn is built so the instructions precede the
            // message they were picked for.
            applyPickedSkills(conversationId, requestedSkillIds, userId);

            // A workflow may declare the provider and model its steps run on (WorkflowService's
            // `provider:`/`model:` frontmatter). Applied HERE, before the options and the ChatModel
            // below are built, rather than inside runWorkflowTurn: every downstream consumer of the
            // pair - the tool options, the usage recorder, the price lookup, the latency event -
            // then names what actually ran, with nothing threaded through react() to do it. A run
            // is one turn per step, so a workflow left on the main chat model multiplies its price
            // by the step count, which is the whole reason the field exists.
            boolean ownModel = workflow != null && workflow.model() != null;
            if (ownModel) {
                // A workflow may name a model without a provider, meaning "the chat role's
                // provider, this model" - see WorkflowService.normalizeModelChoice.
                if (workflow.provider() != null) provider = workflow.provider();
                model = workflow.model();
            }

            // Full advertised-list resolution happens fresh at the top of every react() iteration
            // (see ToolSkillGating) - coreToolCallbacks here is just this method's own honest
            // starting point, not the final list used for iteration 1's actual LLM call.
            if (ownModel && !llmGateway.isAvailable(new LlmGateway.ModelOverride(provider, model))) {
                // Refused, rather than quietly falling back to the chat model. The override is
                // chosen to spend LESS, so silently running the whole procedure on the expensive
                // model would bill the author for the opposite of what the file says while
                // reporting success. Says which workflow and which provider, because the fix is one
                // edit in Settings > Workflows.
                throw new IllegalStateException("Workflow '" + workflow.name()
                        + "' is set to run on a provider that is no longer configured. Open it in "
                        + "Settings > Workflows and pick another, or clear the choice to run it on "
                        + "the main chat model.");
            }
            // ONE session for the whole turn. Resolving per iteration would rebuild an overridden
            // model every time round the loop - it is deliberately uncached - and the tool list is
            // the only thing that varies between iterations, so it is passed per call instead.
            //
            // The prompt cache is the setting's, and on Auto it is chosen for this turn from what
            // will read the prefix next (ChatSettings.PromptCache.forTurn): background sub-agents
            // still at work, or a reader who came back after more than five minutes, ask for an
            // hour. A turn that starts on five minutes can still step up mid-turn - see react().
            ChatSettings.PromptCache cacheSetting = settings.getPromptCache();
            Instant previousTurn = lastTurnEnded.get(conversationId);
            ChatSettings.PromptCache turnCache = cacheSetting.forTurn(
                    subAgentService.hasRunning(conversationId) || subAgentService.hasUndelivered(conversationId),
                    previousTurn == null ? null : Duration.between(previousTurn, Instant.now()));
            LlmGateway.SessionOptions tunedOptions = LlmGateway.SessionOptions.tuned(tone, maxOutputTokens);
            LlmGateway.SessionOptions sessionOptions = ownModel
                    ? tunedOptions.on(new LlmGateway.ModelOverride(provider, model))
                    : tunedOptions;
            LlmCaller chatCaller = LlmCaller.forUser(LlmRole.CHAT, userId);
            LlmSession session = llmGateway.open(chatCaller, sessionOptions.cached(turnCache));
            // How this turn steps up to an hour, when Auto chose five minutes and a background
            // sub-agent is then started: null where there is nothing to step up to.
            java.util.function.Supplier<LlmSession> longCacheSession =
                    cacheSetting == ChatSettings.PromptCache.AUTO && turnCache != ChatSettings.PromptCache.ONE_HOUR
                            ? () -> llmGateway.open(chatCaller, sessionOptions.cached(ChatSettings.PromptCache.ONE_HOUR))
                            : null;
            log.debug("[Chat] prompt cache setting={} turn={} conversationId={}", cacheSetting, turnCache, conversationId);
            // Logged from the SESSION rather than from settings, so it names the model that will
            // actually answer rather than the one this method asked for.
            if (ownModel) {
                log.info("[Workflow] '{}' runs on its own model: {}/{}",
                        workflow.name(), session.provider(), session.model());
            }
            // A gateway instance may be told not to stream (ResolvedProvider.streams).
            boolean streaming = session.streams();

            TurnLatencyTracker latency = new TurnLatencyTracker();
            String userTurn = continuation ? null : buildUserTurn(message, mentions, plainSkillMentions,
                    workflow == null ? List.of() : List.of(workflow.name()), attachmentPaths);
            Long lastBeforeTurn = chatMemory.lastMessageId(conversationId);
            turnFloors.put(conversationId, lastBeforeTurn == null ? 0L : lastBeforeTurn);
            // What opens the turn: what the user sent, or the reports nobody was there to receive.
            Message opening = continuation ? takeReports(conversationId)
                    : new org.springframework.ai.chat.messages.UserMessage(userTurn);
            if (opening == null) {
                // A turn the user started in the same instant took the reports first. Nothing is
                // left to say, and the page that was told a turn started is told it is over.
                deliveredNothing = true;
                turnFloors.remove(conversationId);
                eventPublisher.publishEvent(new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                        ChatEvent.Type.DONE.value(), null, null, doneData(conversationId)));
                return new ChatResult("", conversationId, 0);
            }
            ChatResult result;
            try {
                result = workflow != null
                        ? runWorkflowTurn(conversationId, workflow, userTurn, session,
                                streaming, maxIterations, abort, latency)
                        : react(conversationId, session, longCacheSession, streaming, opening,
                                1, maxIterations, abort, latency);
            } catch (RuntimeException e) {
                // Without this, a turn that throws here never reaches publishTurnLatency()
                // below at all (the exception propagates straight out of chat() - there is no catch
                // around the rest of this method, only the finally at the bottom) - meaning a failed
                // turn produced no ChatTurnMetric row whatsoever, not a row with success=false.
                // errorRate/avgRetryCount are computed only over rows that DO exist, so this made
                // errorRate structurally near-zero regardless of how often turns actually failed, and
                // avgRetryCount reflect only turns that eventually succeeded (confirmed live,
                // 2026-07-29, auditing the Evaluation dashboard's metrics for correctness). react()'s
                // own reactive error handler already sets latency.success=false for a genuine
                // provider/stream failure (not a user-initiated abort, which returns normally instead
                // of throwing), but set it unconditionally here too - reaching this catch at all means
                // the turn did not complete successfully, regardless of which internal path threw.
                latency.success = false;
                publishTurnLatency(provider, model, latency);
                recordTurnDiagnostics(session, latency);
                throw e;
            }

            // Cumulative usage is now persisted and pushed to the client per iteration (see
            // publishUsageUpdate(), called from react() right after each LLM response) rather than
            // summed once here at the end - the UI meter needs to move DURING a long multi-
            // iteration turn, not just jump once the whole thing finishes.
            // The OUTPUT guardrail's whole-answer pass, after the stream has finished and before
            // 'done' tells the client the turn is over.
            //
            // The deterministic half of this has usually already run over the same text flush by
            // flush inside react(), so nothing it catches here should be new. This pass exists for
            // the two things a stream cannot do: judge the answer AS A WHOLE, which is all the
            // classifier can do, and rewrite what was PERSISTED. react() appended the assistant
            // message before any guardrail saw it, and that table backs both the display history
            // and the memory the next turn is built from - so without the rewrite below a withheld
            // answer returns in full on the next page load and the model keeps reasoning from text
            // the reader was never allowed to see.
            //
            // Ordered before recordExchange() and 'done' so the client never sees the turn
            // complete and then get corrected.
            String finalAnswer = result.answer();
            if (guardrailService.enabled(GuardrailStage.OUTPUT)) {
                GuardrailDecision decision = guardrailService.checkOutput(finalAnswer);
                if (!decision.isAllowed()) {
                    finalAnswer = decision.isBlocked()
                            ? guardrailService.refusalMessage(decision, GuardrailStage.OUTPUT)
                            : decision.text();
                    chatMemory.replaceLastAssistantText(conversationId, finalAnswer);
                    publishGuardrail(conversationId, GuardrailStage.OUTPUT, decision, finalAnswer);
                }
            }

            chatMemory.recordExchange(conversationId);
            turnFloors.remove(conversationId);
            eventPublisher.publishEvent(new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.DONE.value(), null, null, doneData(conversationId)));
            log.info("[Chat] completed conversationId={} iter={}", conversationId, result.iterations());

            // The completed-turn count in the daily diagnostics (ChatTelemetryListener) - published
            // unconditionally, for every completed turn regardless of userId/cost/Evaluation Mode,
            // unlike the two events below. See ChatTurnCompletedEvent's javadoc for why it carries
            // no identifying fields at all.
            eventPublisher.publishEvent(new ChatTurnCompletedEvent(Instant.now()));

            publishTurnLatency(provider, model, latency);
            recordTurnDiagnostics(session, latency);

            // finalAnswer, not result.answer(): the non-streaming callers of this method (the MCP
            // surface and a sub-agent's own turn) read the answer from here rather than from the
            // event stream, so returning the pre-guardrail text would leave exactly one path
            // through the product where the output guardrail does nothing at all.
            return new ChatResult(finalAnswer, conversationId, result.iterations());
        } finally {
            SearchContext.clear();
            // Restored, never left behind: chat() runs on a pooled boundedElastic thread, and a
            // subject left set there billed the NEXT task on that thread to this turn's user.
            SpendContext.set(previousSubject);
            // This turn's flag only: never one a later turn has put in its place.
            abortFlags.remove(conversationId, abort);
            turnFloors.remove(conversationId);
            lastTurnEnded.put(conversationId, Instant.now());
            // A message that arrived after the last step was never placed; the page, which saw no
            // 'user-injected' for it, sends it as a turn of its own. See inject().
            injected.remove(conversationId);
            // Only ever released by the turn that actually acquired it - lockMemory() returns false
            // when its bounded wait timed out, in which case this turn ran unserialized and holds
            // nothing to give back.
            if (memoryLocked) chatMemory.unlockMemory(conversationId);
            // LAST, with the turn fully gone: a report that landed after this turn's final step
            // found a turn running and left itself for it. Nothing took it, so a turn is started
            // for it now.
            //
            // EXCEPT after a continuation that delivered nothing. A report still owed then is one
            // that could not be READ (takeReports puts it back on a failure), and waking again
            // starts the same turn that just failed: on PostgreSQL, before SubAgentRun's columns
            // were text, that was a loop logging a stack trace several times a second for as long
            // as the server ran. The report stays owed, and the user's next turn takes it.
            if (!deliveredNothing) wake(conversationId);
        }
    }

    // -- Background sub-agents reporting back ---------------------------------------------------

    // Conversations a continuation turn has been started for and has not registered yet - so two
    // sub-agents finishing together start one turn between them, not two.
    private final Set<String> waking = ConcurrentHashMap.newKeySet();

    /**
     * What a turn's DONE says beside "done": {@code more} while sub-agents are still running in
     * the conversation or a report is still owed to its agent - the page keeps following the
     * stream then, because more is coming that no message of the user's will have started.
     */
    private String doneData(String conversationId) {
        return hasBackgroundWork(conversationId) ? "more" : "";
    }

    /** Whether sub-agents are running in the conversation, or a report is still owed to its agent. */
    public boolean hasBackgroundWork(String conversationId) {
        return subAgentService.hasRunning(conversationId) || subAgentService.hasUndelivered(conversationId);
    }

    /** Stops one sub-agent of a conversation. False when it is not running there. */
    public boolean stopSubAgent(String conversationId, String agentId) {
        return subAgentService.stop(conversationId, agentId);
    }

    /** The conversation's agents and what each spent - see ConversationAgents. */
    public ai.proxyma.core.chat.dto.ConversationAgents agents(Conversation conv) {
        var total = new ai.proxyma.core.chat.dto.ConversationAgents.Spend(conv.getInputTokens(),
                conv.getOutputTokens(), conv.getCacheReadTokens(), conv.getCacheWriteTokens(),
                conv.getEstimatedCostUsd());
        var turns = ai.proxyma.core.chat.dto.ConversationAgents.turnsOf(chatMemory.messageStamps(conv.getId()),
                isActive(conv.getId()));
        // Titles the model wrote, crossing to the browser: screened as the sub-agent's own panel
        // screens the same title (SubAgentView.scrubbed).
        List<SubAgentService.AgentUsage> agents = subAgentService.usage(conv.getId()).stream()
                .map(a -> new SubAgentService.AgentUsage(a.id(), SensitivePaths.scrub(a.title()), a.mode(), a.status(),
                        a.startedAt(), a.endedAt(), a.provider(), a.model(), a.inputTokens(), a.outputTokens(),
                        a.cacheReadTokens(), a.cacheWriteTokens(), a.costUsd(), a.contextTokens(), a.contextWindow()))
                .toList();
        return ai.proxyma.core.chat.dto.ConversationAgents.of(total, agents, turns);
    }

    /**
     * The reports owed to this conversation's agent, as the message that puts them in front of
     * it - or null when there are none. Each is handed over once; the page is told as each lands
     * ('sub-agent-reported'), which is where the thread says the sub-agent reported back.
     */
    private Message takeReports(String conversationId) {
        if (!subAgentService.hasUndelivered(conversationId)) return null;
        List<SubAgentService.Settled> reports = subAgentService.takeReports(conversationId);
        // And what running sub-agents have said meanwhile (messageMainAgent), after the reports.
        List<SubAgentService.Note> notes = subAgentService.takeNotes(conversationId);
        if (reports.isEmpty() && notes.isEmpty()) return null;
        for (SubAgentService.Settled report : reports) {
            publishReported(conversationId, report.agentId(), report.title(), report.status(), null);
        }
        for (SubAgentService.Note note : notes) {
            publishReported(conversationId, note.agentId(), note.title(), "RUNNING", "message");
        }
        String text = reports.isEmpty() ? ""
                : SubAgentService.reportMessage(reports, subAgentService.runningTitles(conversationId));
        if (!notes.isEmpty()) text = (text.isEmpty() ? "" : text + "\n\n") + SubAgentService.noteMessage(notes);
        return new org.springframework.ai.chat.messages.UserMessage(text);
    }

    // The thread's line where a report - or a running sub-agent's message, `kind` "message" -
    // reached the main agent.
    private void publishReported(String conversationId, String agentId, String title, String status, String kind) {
        String data;
        try {
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("title", title == null ? "" : title);
            fields.put("status", status.toLowerCase(Locale.ROOT));
            if (kind != null) fields.put("kind", kind);
            data = JSON.writeValueAsString(fields);
        } catch (Exception e) {
            data = "{}";
        }
        eventPublisher.publishEvent(new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                ChatEvent.Type.SUB_AGENT_REPORTED.value(), agentId, null, data));
    }

    /** A background sub-agent has reported - see {@link AgentHost#subAgentSettled}. */
    @Override
    public void subAgentSettled(String conversationId) {
        wake(conversationId);
    }

    /**
     * Gets a report that is owed in front of the main agent.
     *
     * <p>A RUNNING TURN TAKES IT ITSELF, at its next step - after the tool round in progress, or
     * once the model has answered (reactStep, react) - the way it takes a message the user typed
     * meanwhile. With no turn running there is nobody to take it, so one is started: the main
     * agent is told the sub-agent has finished without the user having to ask (maintainer's
     * request, 2026-10-02: "the main agent ... will be notified by the sub-agent when it finishes
     * the job").
     *
     * <p>Called when a sub-agent settles and again when a turn ends, which between them close the
     * gap where a report lands after a turn's last step but before the turn is gone.
     */
    private void wake(String conversationId) {
        try {
            if (!subAgentService.hasUndelivered(conversationId) || isActive(conversationId)) return;
            if (!waking.add(conversationId)) return;
            Optional<Conversation> conversation = chatMemory.findConversation(conversationId);
            if (conversation.isEmpty()) {
                waking.remove(conversationId);
                return;
            }
            String userId = conversation.get().getUserId();
            String guestSessionId = conversation.get().getGuestSessionId();
            // The sink is KEPT: a page that was following the sub-agent is subscribed to it, and
            // that is the page this turn has to reach.
            chatEventSink.startTurn(conversationId, true);
            eventPublisher.publishEvent(new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.TURN_START.value(), null, null, ""));
            log.info("[Chat] continuing conversationId={} for a sub-agent's report", conversationId);
            turnsDispatched.put(conversationId, System.currentTimeMillis());
            // The name the user's own turns put in the prompt, so the continuation's prompt is the
            // same prompt and the cached prefix holds - see turnDisplayNames.
            String displayName = turnDisplayNames.get(conversationId);
            Mono.fromCallable(() -> turn(conversationId, null, null, List.of(), userId, displayName, guestSessionId,
                            null, true, null))
                    .subscribeOn(TURNS)
                    .subscribe(result -> {}, err -> {
                        waking.remove(conversationId);
                        log.error("[Chat] continuation failed conversationId={}: {}", conversationId,
                                Failures.describe(err), err);
                        eventPublisher.publishEvent(new ChatEvent(conversationId,
                                chatEventSink.nextSequence(conversationId),
                                ChatEvent.Type.ERROR.value(), null, null, Failures.describe(err)));
                    });
        } catch (RuntimeException e) {
            // Called from a turn's finally and from a sub-agent's last step: neither may be failed
            // by a report that could not be delivered. It stays owed, and the next turn takes it.
            waking.remove(conversationId);
            log.warn("[Chat] could not continue conversationId={} for a report: {}", conversationId, e.getMessage(), e);
        }
    }

    // Same 'condense-start'/'condense-end' pair SmartChatMemory.condense() publishes around its own
    // summarizer call (see ChatWindow's "Condensing conversation..." indicator) - published from
    // here too for the stretch of a turn spent WAITING on someone else's condensation, which from
    // the user's side is the same thing and would otherwise look like a stalled response.
    /**
     * Tells the client a guardrail withheld something.
     *
     * <p>Carries the stage, the verdict and the CATEGORIES - never the matched text and never the
     * rule's own findings verbatim. A guardrail event travels the same channel as the answer and
     * ends up in the same places, so quoting what it caught would defeat the secret rules
     * completely. {@code text} is the sentence written for a person to read.
     */
    private void publishGuardrail(String conversationId, GuardrailStage stage,
                                  GuardrailDecision decision, String message) {
        // ONE payload in `data`. The wire record has a single free-form field (callId and toolName
        // beside it mean what their names say), so the message travels inside the JSON rather than
        // in a field that would have to be read as something it is not.
        String data;
        try {
            data = JSON.writeValueAsString(Map.of(
                    "stage", stage.name().toLowerCase(java.util.Locale.ROOT),
                    "verdict", decision.verdict().name().toLowerCase(java.util.Locale.ROOT),
                    "categories", decision.categories(),
                    "message", message));
        } catch (Exception e) {
            // The event still has to reach the client: without it the turn simply stops with no
            // explanation, which is the one outcome worse than an event with a thin payload.
            log.warn("[Guardrail] could not serialize event data: {}", e.getMessage());
            data = "{\"stage\":\"" + stage.name().toLowerCase(java.util.Locale.ROOT)
                    + "\",\"verdict\":\"unknown\",\"categories\":[],\"message\":\"\"}";
        }
        eventPublisher.publishEvent(new ChatEvent(conversationId,
                chatEventSink.nextSequence(conversationId),
                ChatEvent.Type.GUARDRAIL.value(), null, null, data));
    }

    /**
     * The retrieval guardrail, applied to one tool result on its way back to the model.
     *
     * <p>Returns the string the agent should actually see: unchanged when nothing fired, redacted
     * when spans were cut, and a short JSON notice when the result was withheld outright. The
     * caller assigns the return value over `result` before publishing TOOL_RESULT, so the row the
     * user sees in the transcript is the row the model was given - the two must not disagree,
     * because a reader looking at a tool result to understand an answer is looking for exactly
     * what the model read.
     *
     * <p>NEVER THROWS. This runs inside the tool-call path of every turn, and a guardrail that can
     * take down retrieval is a worse outcome than one that misses something: a settings store that
     * fails to deserialize, a pattern that behaves unexpectedly on a pathological input, anything
     * at all here would otherwise turn every tool call in the deployment into an error. It fails
     * OPEN and says so in the log, which is the same choice the classifier makes and is recorded
     * as an accepted risk in the same place.
     */
    private String guardRetrieved(String conversationId, String toolName, String result) {
        if (result == null || result.isEmpty()) return result;
        try {
            if (!guardrailService.enabled(GuardrailStage.RETRIEVAL)) return result;
            GuardrailDecision decision = guardrailService.checkRetrieved(result);
            if (decision.isAllowed()) return result;

            // NO GUARDRAIL EVENT HERE, and that is a decision rather than an omission. That event
            // means "this turn was refused": the client replaces the assistant bubble's text with
            // the notice and stops offering the message's actions, which is right when the whole
            // answer was withheld and destructive when a single tool call was. A retrieval block
            // happens MID-STREAM, while an answer the user is reading is still being written.
            //
            // What tells them instead: the tool row in the transcript, which carries the exact
            // string the model was handed, and the agent itself - withheldToolResult asks it to
            // say a source was held back if it mattered. Both survive a reload; a mid-turn banner
            // would not.
            if (decision.isBlocked()) {
                log.info("[Chat] guardrail withheld the result of tool '{}' - {}",
                        toolName, decision.categories());
                return guardrailService.withheldToolResult(decision);
            }
            return decision.text();
        } catch (Exception e) {
            log.warn("[Chat] retrieval guardrail failed on tool '{}', allowing the result through: {}",
                    toolName, Failures.describe(e), e);
            return result;
        }
    }

    private void publishCondensing(String conversationId, boolean starting) {
        eventPublisher.publishEvent(new ChatEvent(conversationId,
                chatEventSink.nextSequence(conversationId),
                (starting ? ChatEvent.Type.CONDENSE_START : ChatEvent.Type.CONDENSE_END).value(),
                null, null, ""));
    }

    // Publishes ChatTurnLatencyRecordedEvent for this turn, every turn - see TurnLatencyTracker's
    // javadoc for how the timings were accumulated across react()'s iterations. Whether anything
    // keeps it is the listener's decision. Deliberately takes no userId/conversationId - the record
    // is anonymous by construction (see ChatTurnLatencyRecordedEvent's javadoc).
    private void publishTurnLatency(String provider, String model, TurnLatencyTracker latency) {
        long totalDurationMs = (System.nanoTime() - latency.turnStartNanos) / 1_000_000;
        long streamStart = latency.streamStartNanos.get();
        long firstToken = latency.firstTokenNanos.get();
        long ttftMs = (streamStart >= 0 && firstToken >= 0) ? (firstToken - streamStart) / 1_000_000 : 0;
        eventPublisher.publishEvent(new ChatTurnLatencyRecordedEvent(provider, model,
                ttftMs, totalDurationMs, latency.retryCount.get(), latency.success, latency.outputTokenCount.get()));
    }

    /**
     * Time to first token and the whole turn, into the daily diagnostics - labeled by the provider
     * PROTOCOL, never the instance name a user gave it or the model. Always recorded in memory, like
     * every other timing; whether it is kept or sent is proxyma-core-diagnostics' decision.
     */
    private void recordTurnDiagnostics(LlmSession session, TurnLatencyTracker latency) {
        String protocol = session == null ? "unknown" : session.protocol();
        long streamStart = latency.streamStartNanos.get();
        long firstToken = latency.firstTokenNanos.get();
        if (streamStart >= 0 && firstToken >= 0) {
            Diagnostics.time(DiagOp.CHAT_TTFT, (firstToken - streamStart) / 1_000_000, "protocol", protocol);
        }
        Diagnostics.timeSince(DiagOp.CHAT_TOTAL, latency.turnStartNanos,
                "protocol", protocol, "outcome", latency.success ? "ok" : "failed");
    }

    /**
     * Marks a conversation's in-flight turn as stopped.
     *
     * <p>A turn does not register its abort flag the moment POST /chat returns - that endpoint
     * answers immediately and the turn continues on a worker thread, where it first resolves
     * settings, opens an LLM session and waits on {@code lockMemory()}. That last one can hold for
     * MINUTES behind a running condensation. This method used to do nothing at all in that window
     * and still answer 204, so a Stop pressed while the UI read "Condensing conversation..." was
     * silently discarded and the turn ran to completion - which is one of the two things that made
     * Stop look unreliable.
     *
     * <p>So a request that finds no flag leaves a marker instead, and the turn honors it the
     * instant it registers. The re-read below closes the interleaving where the turn registers
     * between this method's first look and its write.
     *
     * <p>ONLY FOR A TURN THAT IS ON ITS WAY. A marker used to be left whenever no flag was found -
     * including when the turn had just ENDED, a Stop clicked as its last event arrived - and the
     * next turn within five minutes started stopped: the user's next message was answered "Chat
     * was stopped." and never reached the model. Now a marker is left only while a turn has been
     * started and has not registered, and that turn honors only a stop that came after it was
     * started.
     */
    public void stop(String conversationId) {
        AtomicBoolean abort = abortFlags.get(conversationId);
        if (abort != null) {
            abort.set(true);
            log.info("[Chat] abort requested for conversationId={}", conversationId);
            return;
        }
        if (!turnsDispatched.containsKey(conversationId)) {
            // A turn registers its flag BEFORE it stops being on its way, so one that registered
            // since the first look is found here.
            abort = abortFlags.get(conversationId);
            if (abort != null) {
                abort.set(true);
                log.info("[Chat] abort requested for conversationId={} (registered mid-request)", conversationId);
            } else {
                log.info("[Chat] stop requested with no turn running or on its way, conversationId={}", conversationId);
            }
            return;
        }

        pendingStops.put(conversationId, System.currentTimeMillis());
        abort = abortFlags.get(conversationId);
        if (abort != null) {
            abort.set(true);
            pendingStops.remove(conversationId);
            log.info("[Chat] abort requested for conversationId={} (registered mid-request)", conversationId);
        } else {
            log.info("[Chat] stop requested before the turn registered, conversationId={} - it will "
                    + "start already stopped", conversationId);
        }
    }

    /** Whether a Stop is waiting for a turn of this conversation to register. For StopLeavesNoMarkerTest. */
    boolean stopWaiting(String conversationId) {
        return pendingStops.containsKey(conversationId);
    }

    // True for the duration of a chat(...) call (see the try/finally around abortFlags.put/remove
    // above) - used to reject rewind attempts against a conversation with a turn in flight.
    public boolean isActive(String conversationId) {
        return abortFlags.containsKey(conversationId);
    }

    /**
     * Whether anything is still to publish into this conversation's event stream: a turn running
     * or a condensation (isActive, isCondensingNow), a turn started and not registered yet - POST
     * /chat answered, or a report's continuation scheduled - or sub-agents at work in the
     * background. What a page opening the stream is told (ChatController.chatEvents), since
     * 2026-10-16: with none of these, every event there will be has its number already, and a page
     * that holds them all and still waits is waiting for a turn that is gone - the server restarted
     * under it. It covers everything GET .../status calls running, so a page reloaded because of it
     * is never sent straight back to the stream by that status.
     *
     * <p>Asked BEFORE {@link ChatEventSink#currentSequence}, because a turn that ends normally gives
     * its DONE a number before the finally that drops its abort flag - so "nothing is running" is
     * never followed by an event the count did not include. A turn that THROWS and a manual
     * condensation are the exception: their last event is published by the dispatcher just after
     * the flag goes, and a page opening its stream in that instant loads the conversation instead
     * of hearing it.
     */
    public boolean isBusy(String conversationId) {
        return isActive(conversationId) || chatMemory.isCondensingNow(conversationId)
                || turnsDispatched.containsKey(conversationId) || waking.contains(conversationId)
                || hasBackgroundWork(conversationId);
    }

    // -- Messages typed while a turn runs ------------------------------------------------------

    // A message the user sent while this conversation's turn was running, waiting for the loop's
    // next step. clientId is the page's own id for it, echoed back so the page can place it.
    private record Injected(String clientId, String text) {}

    private final ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<Injected>> injected =
            new ConcurrentHashMap<>();

    // Conversations held by a manual condensation rather than a turn - see inject().
    private final java.util.Set<String> condensingNow = ConcurrentHashMap.newKeySet();

    /** How many typed messages wait for this conversation's turn. For InjectDuringCondenseTest. */
    int waitingInjections(String conversationId) {
        java.util.concurrent.ConcurrentLinkedQueue<Injected> queue = injected.get(conversationId);
        return queue == null ? 0 : queue.size();
    }

    public enum InjectOutcome { ACCEPTED, NOT_RUNNING, BLOCKED }

    public record InjectResult(InjectOutcome outcome, String message) {}

    /**
     * Hands a message to the RUNNING turn, which folds it in at its next step instead of making it
     * wait for the whole turn to end - since 2026-09-29, the way a coding assistant in an editor
     * takes a message typed while it works. The next step is after the tool round in progress
     * (reactStep) or, if the model has already answered, one more step with the message (react).
     *
     * <p>Screened by the input guardrail exactly as a new message is - this is a user message
     * reaching the model, whatever path it took. NOT_RUNNING means there is no turn to join (it
     * ended, or never started); the page then sends the message as a turn of its own. A message
     * accepted and then not consumed - it arrived after the last step - is dropped when the turn
     * ends, and the page, which never saw it placed, sends it on too.
     *
     * <p>A MANUAL CONDENSATION IS NOT A TURN, and until 2026-10-03 this could not tell them apart:
     * "condense now" holds the same abort flag a turn does, so a message typed while it ran was
     * ACCEPTED - by something with no loop to take it and nothing to drop it afterwards. The page
     * then sent the message as a turn of its own, and that turn found the stale copy waiting at
     * its first step: the model was given the same message twice, and the page was told to move
     * a bubble it had already placed (a feedback screenshot, 0.23.0 - the message jumped below the
     * tool steps as they finished). A condensation now answers NOT_RUNNING, and a turn starts with
     * nothing waiting, whatever was accepted before it began.
     */
    public InjectResult inject(String conversationId, String text, String clientId, String userId) {
        if (conversationId == null || text == null || text.isBlank() || !isActive(conversationId)
                || condensingNow.contains(conversationId)) {
            return new InjectResult(InjectOutcome.NOT_RUNNING, null);
        }
        String message = text;
        if (guardrailService.enabled(GuardrailStage.INPUT)) {
            GuardrailDecision decision = checkInput(message, userId, conversationId);
            if (decision.isBlocked()) {
                return new InjectResult(InjectOutcome.BLOCKED,
                        guardrailService.refusalMessage(decision, GuardrailStage.INPUT));
            }
            if (decision.verdict() == GuardrailDecision.Verdict.REDACT) message = decision.text();
        }
        injected.computeIfAbsent(conversationId, k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                .add(new Injected(clientId, message));
        return new InjectResult(InjectOutcome.ACCEPTED, null);
    }

    /**
     * The INPUT guardrail over one piece of text the user wrote, wherever it reaches the model from -
     * a message, a message typed into a running turn, an answer to the assistant's question.
     *
     * <p>The repeat counter's subject: the USER where there is one, the conversation otherwise.
     * Per-user is what an operator means by "somebody keeps trying" - a new conversation is one click
     * away, so counting per conversation in server mode would be a cooldown that clears itself.
     * Desktop has no users, so it falls back to the conversation and the setting is honest about
     * that being weaker.
     *
     * <p>Made with the user and the conversation on SearchContext, which is where the guardrail's
     * Audit Logs entry reads them from: every input check ran before a turn had put them there, or
     * on a request thread that never had them, and was filed under nobody and no conversation
     * (chat-agent-23). Whatever the thread held before is put back.
     */
    private GuardrailDecision checkInput(String text, String userId, String conversationId) {
        String previousUser = SearchContext.getUserId();
        String previousConversation = SearchContext.getConversationId();
        SearchContext.setUserId(userId);
        SearchContext.setConversationId(conversationId);
        try {
            String subject = (userId != null && !userId.isBlank()) ? "u:" + userId : "c:" + conversationId;
            return guardrailService.checkInput(text, subject);
        } finally {
            SearchContext.setUserId(previousUser);
            SearchContext.setConversationId(previousConversation);
        }
    }

    // Takes every message waiting for this conversation and tells the page where each landed.
    private List<Injected> drainInjected(String conversationId) {
        java.util.concurrent.ConcurrentLinkedQueue<Injected> queue = injected.get(conversationId);
        if (queue == null || queue.isEmpty()) return List.of();
        List<Injected> out = new ArrayList<>();
        for (Injected i; (i = queue.poll()) != null; ) out.add(i);
        for (Injected i : out) {
            eventPublisher.publishEvent(new ChatEvent(conversationId,
                    chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.USER_INJECTED.value(), i.clientId(), null, i.text()));
        }
        return out;
    }

    /**
     * Runs a manual "condense now" as a first-class, interruptible unit of work rather than as a
     * bare call into {@link SmartChatMemory#forceCondense}.
     *
     * Two things this wrapper provides that calling forceCondense() directly could not:
     *
     * - <b>Stop works.</b> condense() decides whether to keep going by asking
     *   {@code SearchContext.isAborted()}, and SearchContext is thread-local. A manual condensation
     *   runs on an HTTP worker thread that no turn ever set up, so that flag was always false and a
     *   Stop press did nothing at all - the user watched a three-minute summarizer call they had
     *   explicitly cancelled. Registering the flag in the same {@code abortFlags} map a turn uses
     *   means the existing {@link #stop} needs no special case for condensation.
     * - <b>It is visibly busy.</b> Sharing that map also makes {@link #isActive} true for the
     *   duration, so a rewind or a second condense is rejected with the same 409 a live turn gets,
     *   instead of racing the write that condense() ends in.
     *
     * @return false if a turn or another condensation already holds this conversation, in which
     *         case nothing was condensed.
     */
    public boolean condenseNow(String conversationId) {
        AtomicBoolean abort = new AtomicBoolean(false);
        // putIfAbsent, not put: a turn that started between the caller's isActive() check and here
        // owns this conversation, and must not have its abort flag swapped out from under it.
        if (abortFlags.putIfAbsent(conversationId, abort) != null) return false;
        // Busy, but not a turn: nothing here reads a typed message - see inject().
        condensingNow.add(conversationId);
        try {
            SearchContext.setAbortFlag(abort);
            return chatMemory.forceCondense(conversationId);
        } finally {
            SearchContext.clear();
            condensingNow.remove(conversationId);
            injected.remove(conversationId);
            abortFlags.remove(conversationId, abort);
        }
    }

    // Deletes messageId and everything after it from this conversation's history - see
    // MessageBubble's rewind action on the frontend. Rejects while a turn is in flight so the
    // ReAct loop currently reading/appending to this conversation's memory can't race a
    // concurrent truncation of it.
    public void rewind(String conversationId, Long messageId) {
        if (isActive(conversationId)) {
            throw new IllegalStateException("Cannot rewind conversationId=" + conversationId + " while a turn is in progress");
        }
        // A manual condensation is the same hazard as a turn, for the same reason: it ends in a
        // write computed from a snapshot taken before this truncation, whose row positions the
        // truncation would invalidate. Rejected identically (the caller maps
        // IllegalStateException to 409) rather than waited on - the user can retry in a moment.
        if (chatMemory.isCondensing(conversationId)) {
            throw new IllegalStateException("Cannot rewind conversationId=" + conversationId + " while it is being condensed");
        }
        chatMemory.rewindTo(conversationId, messageId);
        log.info("[Chat] rewound conversationId={} to messageId={}", conversationId, messageId);
    }

    // The history list: the chat page's conversations (surface null), or one surface's.
    public org.springframework.data.domain.Page<Conversation> listConversationsPaged(
            String userId, String surface, org.springframework.data.domain.Pageable pageable) {
        return chatMemory.listConversationsPaged(userId, surface, pageable);
    }

    // Every conversation-scoped endpoint looks its conversation up here first, so an id that could
    // not name one - a path, say - is a 400 at all of them (see ConversationPaths).
    public Conversation getConversation(String conversationId) {
        ConversationPaths.requireValidId(conversationId);
        return chatMemory.findConversation(conversationId)
                .orElseThrow(() -> new NoSuchElementException("Conversation not found: " + conversationId));
    }

    // The live context-window budget (Settings > Chat > Memory max tokens) - see
    // ConversationDetail.contextTokenLimit's javadoc for why this is read fresh rather than
    // persisted per-conversation.
    public int getContextTokenLimit() {
        return llmGateway.contextWindow(LlmRole.CHAT);
    }

    // Display messages (see ChatMessageRepository.findDisplayMessagesByConversationId) with each
    // one's tool calls reattached from the raw chat_messages rows - those rows already carry
    // tool-call requests (ASSISTANT.toolCallsJson) and results (TOOL.toolResponsesJson), written
    // every turn by JpaChatMemoryRepository for Spring AI's own memory, but the display query
    // filters TOOL rows and blank-content ASSISTANT rows out entirely since they're not their own
    // display bubble.
    //
    // A tool call is attached to the NEXT content-bearing ASSISTANT row, never the one whose
    // iteration triggered it - mirrors useChat.ts's live attachStream semantics, where a
    // 'tool-start' event never attaches to the message that's currently open (the ReAct loop only
    // learns of a tool call after that iteration's own text has already streamed).
    //
    // NO TOOL CALL IS EVER DROPPED, since 2026-10-01. Calls still waiting for a message when the
    // next USER row arrives - or when the rows run out - get a row of their own, with no text. The
    // old rule cleared them at every user message, on the reasoning that they were a stray batch
    // from a truncated turn; but a message typed while the turn ran is stored as a USER row in the
    // MIDDLE of it, so every step taken before it vanished on reload, and so did the last steps of
    // any turn that ended without an answer (stopped, out of steps, a provider error). A call that
    // never got a result is sent as it is, not done - the page shows that as interrupted.
    //
    // A RUNNING turn is the one exception to "the whole conversation": see turnFloors.
    public List<ChatMessageResponse> getMessagesWithToolCalls(String conversationId) {
        return getTranscript(conversationId).messages();
    }

    /**
     * A conversation as a page is given it: the thread, the position in the event stream it is
     * true as of, the prompts open at that position, and whether anything is going on in the
     * background.
     *
     * <p>THE POSITION IS WHAT MAKES "LOAD, THEN FOLLOW THE STREAM" EXACT. The stream replays, so
     * a page that subscribes is sent events it may already hold as messages - and since
     * 2026-10-02 that is no longer only a running turn's: a background sub-agent publishes while
     * no turn runs, into a sink that still holds the last turn. The page handles what is numbered
     * above {@code eventSeq} and nothing else. With a turn running that is where the turn began
     * (the transcript stops at the turn's opening message, see turnFloors); otherwise it is the
     * last event published, and everything a sub-agent had done by then is in its panel here.
     */
    public record Transcript(List<ChatMessageResponse> messages, long eventSeq,
                             List<ai.proxyma.core.chat.dto.ConversationDetail.PendingPrompt> prompts,
                             boolean background) {}

    public Transcript getTranscript(String conversationId) {
        for (int attempt = 0; ; attempt++) {
            Long floor = turnFloors.get(conversationId);
            // Read BEFORE the rows, so nothing numbered at or below it can be missing from them.
            long asOf = (floor != null || isActive(conversationId)
                    ? chatEventSink.turnStartSequence(conversationId)
                    : chatEventSink.currentSequence(conversationId)) - 1;
            List<ChatMessageResponse> messages = transcript(chatMemory.findAllMessages(conversationId),
                    workflowRunService.runIdsByTriggerMessage(conversationId), floor,
                    (callId, result) -> subAgentFor(conversationId, callId, result, asOf));
            // A turn that began, or ended, while the rows were being read: the rows and the
            // position describe two different moments. Read again, once.
            if (attempt == 0 && !Objects.equals(floor, turnFloors.get(conversationId))) continue;
            List<ai.proxyma.core.chat.dto.ConversationDetail.PendingPrompt> prompts = openPrompts.values().stream()
                    .filter(e -> conversationId.equals(e.conversationId()) && e.sequence() <= asOf)
                    .sorted(java.util.Comparator.comparingLong(ChatEvent::sequence))
                    .map(e -> new ai.proxyma.core.chat.dto.ConversationDetail.PendingPrompt(e.streamType(),
                            e.callId(), e.toolName(), SensitivePaths.scrub(e.data()), e.parentCallId()))
                    .toList();
            return new Transcript(messages, asOf, prompts, hasBackgroundWork(conversationId));
        }
    }

    // The sub-agent a `delegate` call started, as of that stream position. The call's result names
    // it; a call that has not returned yet is matched by its own id among the ones running.
    private ai.proxyma.core.chat.dto.SubAgentView subAgentFor(String conversationId, String callId, String result,
                                                              long asOf) {
        String agentId = null;
        if (result != null && result.contains("\"agentId\"")) {
            try {
                agentId = JSON.readTree(result).path("agentId").asText(null);
            } catch (Exception e) {
                agentId = null;
            }
        }
        Optional<ai.proxyma.core.chat.dto.SubAgentView> view = agentId != null
                ? subAgentService.view(conversationId, agentId, asOf)
                : subAgentService.viewByCall(conversationId, callId, asOf);
        return view.map(ai.proxyma.core.chat.dto.SubAgentView::scrubbed).orElse(null);
    }

    /** Finds the sub-agent a `delegate` call started; null when there is none to show. */
    interface AgentLookup {
        ai.proxyma.core.chat.dto.SubAgentView find(String callId, String result);
    }

    static List<ChatMessageResponse> transcript(List<ChatMessage> rows, Map<Long, String> workflowRunsByMessage,
                                                Long liveTurnFloor) {
        return transcript(rows, workflowRunsByMessage, liveTurnFloor, (callId, result) -> null);
    }

    // A function of the rows alone, so the rules above are tested without a running turn
    // (TranscriptTest). liveTurnFloor is null unless a turn is running - see turnFloors.
    static List<ChatMessageResponse> transcript(List<ChatMessage> rows, Map<Long, String> workflowRunsByMessage,
                                                Long liveTurnFloor, AgentLookup agents) {
        boolean liveTurnReached = false;
        List<ChatMessageResponse> result = new ArrayList<>();
        List<PendingToolCall> pending = new ArrayList<>();
        // Set by a last step that still asked for tools (or wrote nothing at all): the notice
        // belongs after those calls, so it rides on the row that carries them.
        Integer stepLimitToFlush = null;
        // True while the rows being walked belong to a workflow step whose output is NOT the
        // turn's visible reply, i.e. every step but the last. Set by that step's brief (which
        // carries its own position - see SmartChatMemory.workflowStepPosition) and cleared by the
        // next brief or by the next real user message. The step's tool calls are deliberately NOT
        // suppressed with it: they stay in `pending` and attach to the next visible message, which
        // is what an ordinary multi-iteration turn already does, and dropping them would erase the
        // record of writes a workflow actually performed.
        boolean insideHiddenStep = false;

        for (ChatMessage row : rows) {
            // A condensation handoff summary (USER) and its synthetic role-alternation bridge
            // (ASSISTANT) - see SmartChatMemory.condense() - are internal bookkeeping, never
            // something the user actually said or the model actually replied; skip both entirely
            // rather than rendering them as transcript bubbles.
            if (SmartChatMemory.isCondensedSummaryMarker(row.getContent())) continue;
            // Same reasoning for a skill loaded into the conversation (see applyPickedSkills): the
            // instructions are real message content the model reads, but the user did not type
            // them and the model did not write them, so neither half belongs in the transcript.
            // The pick itself is still visible - it stays a "/name" chip on the user's own message.
            if (SmartChatMemory.isSkillMarker(row.getContent())) continue;
            // react()'s own last-iteration nudge, stored as a USER row like everything it adds to
            // memory. It was never something the user said, so it does not belong in the transcript
            // - and inside a workflow it would also read as a real user message and end the step
            // suppression below, putting an intermediate step's answer back in the thread.
            if (FORCED_ANSWER_INSTRUCTION.equals(row.getContent())) continue;
            // A workflow run's scaffolding - the opening plan, its acknowledgement, and each step's
            // brief. Same reasoning again: real message content the model reads, written by neither
            // party to the conversation. What the reader gets instead of these rows is the step
            // table, which says the same things in a form worth looking at. The step brief is also
            // what tells this loop whether the answer that follows is an intermediate step's
            // (hidden, it belongs in its row) or the last step's (the turn's visible reply).
            if (SmartChatMemory.isWorkflowMarker(row.getContent())) {
                int[] position = SmartChatMemory.workflowStepPosition(row.getContent());
                if (position != null) insideHiddenStep = position[0] < position[1];
                continue;
            }
            switch (row.getRole()) {
                case USER -> {
                    // The steps taken before this message stay ABOVE it, in a row of their own -
                    // never carried into the reply that follows it, and never dropped.
                    flushToolCalls(result, pending, stepLimitToFlush, agents);
                    stepLimitToFlush = null;
                    // A real user message ends any run that was still in progress - a run that was
                    // interrupted (a restart, a Stop) never reaches its last step, and without this
                    // its suppression would swallow every later reply in the conversation.
                    insideHiddenStep = false;
                    // Background sub-agents reporting to the main agent. Stored as a USER row - the
                    // only role a provider accepts there - and nothing the user said: the thread
                    // says WHO reported, and the report itself is in that sub-agent's panel.
                    if (SubAgentService.isReportMessage(row.getContent())) {
                        // The one that OPENED a turn still running is left to the stream, with the
                        // rest of that turn: its 'sub-agent-reported' event is replayed, and a row
                        // here as well would say it twice.
                        if (liveTurnFloor != null && row.getId() != null && row.getId() > liveTurnFloor) return result;
                        List<ChatMessageResponse.SubAgentReported> reported =
                                SubAgentService.reportsIn(row.getContent()).stream()
                                        .map(r -> new ChatMessageResponse.SubAgentReported(r[0],
                                                SensitivePaths.scrub(r[1]), r[2].toLowerCase(Locale.ROOT),
                                                "message".equals(r[3]) ? "message" : null))
                                        .toList();
                        result.add(new ChatMessageResponse(null, ai.proxyma.core.chat.domain.MessageRole.ASSISTANT,
                                "", null, null, null, null, null, null, reported));
                        continue;
                    }
                    SanitizedUserContent sanitized = sanitizeUserContent(row.getContent());
                    // sanitizeUserContent already replaces the attachment paths it knows about;
                    // the scrub is the catch-all behind it, for a path the user simply typed.
                    result.add(new ChatMessageResponse(row.getId(), row.getRole(),
                            SensitivePaths.scrub(sanitized.content()), null,
                            sanitized.attachmentNames().isEmpty() ? null : sanitized.attachmentNames(),
                            sanitized.mentions().isEmpty() ? null : sanitized.mentions(),
                            sanitized.skillMentions().isEmpty() ? null : sanitized.skillMentions(),
                            workflowRunsByMessage.get(row.getId()), null));
                    liveTurnReached = liveTurnFloor != null && row.getId() != null && row.getId() > liveTurnFloor;
                }
                case ASSISTANT -> {
                    boolean visible = row.getContent() != null && !row.getContent().isBlank()
                            && !insideHiddenStep;
                    List<ToolCall> ownCalls = row.getToolCallsJson() == null ? List.of()
                            : parseToolCalls(row.getToolCallsJson());
                    Integer stepLimit = insideHiddenStep ? null
                            : JpaChatMemoryRepository.readStepLimit(row.getMetadataJson());
                    // Under the answer when the last step wrote one and asked for nothing more;
                    // otherwise after the calls it asked for, which the flush below carries.
                    boolean noticeHere = stepLimit != null && visible && ownCalls.isEmpty();
                    if (visible) {
                        List<ChatMessageResponse.ToolCallResponse> claimed = pending.isEmpty() ? null
                                : pending.stream().map(p -> p.toResponse(agents)).toList();
                        result.add(new ChatMessageResponse(row.getId(), row.getRole(),
                                SensitivePaths.scrub(row.getContent()), claimed, null, null, null, null,
                                noticeHere ? stepLimit : null));
                        pending.clear();
                    }
                    for (ToolCall tc : ownCalls) {
                        pending.add(new PendingToolCall(tc.id(), tc.name(), tc.arguments()));
                    }
                    if (stepLimit != null && !noticeHere) stepLimitToFlush = stepLimit;
                }
                case TOOL -> {
                    if (row.getToolResponsesJson() != null) {
                        Map<String, long[]> timings = JpaChatMemoryRepository.readToolTimings(row.getMetadataJson());
                        for (ToolResponse tr : parseToolResponses(row.getToolResponsesJson())) {
                            // Null-safe: rows written before every call was given an id can hold
                            // a call without one, and the transcript failed on it.
                            pending.stream()
                                    .filter(p -> !p.done && Objects.equals(p.callId, tr.id()))
                                    .findFirst()
                                    .ifPresent(p -> {
                                        p.result = tr.responseData();
                                        p.done = true;
                                        long[] times = timings.get(tr.id());
                                        if (times != null) {
                                            p.startedAt = times[0];
                                            p.endedAt = times[1];
                                        }
                                    });
                        }
                    }
                }
                case SYSTEM -> { /* never displayed */ }
            }
            // The turn's opening message is the last thing the transcript says about a turn that
            // is still running - see turnFloors.
            if (liveTurnReached) return result;
        }
        flushToolCalls(result, pending, stepLimitToFlush, agents);
        return result;
    }

    // Gives the tool calls no message has claimed a row of their own, and empties the list. Also
    // the row a step-limit notice lands on when the last step wrote nothing to put it under.
    private static void flushToolCalls(List<ChatMessageResponse> result, List<PendingToolCall> pending,
                                       Integer stepLimit, AgentLookup agents) {
        if (pending.isEmpty() && stepLimit == null) return;
        List<ChatMessageResponse.ToolCallResponse> calls = pending.isEmpty() ? null
                : pending.stream().map(p -> p.toResponse(agents)).toList();
        // No id: it is not one stored message, and nothing on it can be rated, shared or rewound.
        result.add(new ChatMessageResponse(null, ai.proxyma.core.chat.domain.MessageRole.ASSISTANT, "",
                calls, null, null, null, null, stepLimit));
        pending.clear();
    }

    private static final class PendingToolCall {
        final String callId;
        final String toolName;
        final String arguments;
        String result;
        boolean done;
        Long startedAt;
        Long endedAt;

        PendingToolCall(String callId, String toolName, String arguments) {
            this.callId = callId;
            this.toolName = toolName;
            this.arguments = arguments;
        }

        // Scrubbed here rather than at the persistence layer: the stored rows are also the model's
        // own memory, and it needs the real paths there to read an offloaded result back on a later
        // turn. Only the copy heading for the browser is screened - same boundary rule as
        // ChatController.formatSseEvent, which covers these two fields live.
        ChatMessageResponse.ToolCallResponse toResponse(AgentLookup agents) {
            // A `delegate` call carries the sub-agent it started: what it did and what it reported.
            ai.proxyma.core.chat.dto.SubAgentView agent =
                    SubAgentService.DelegateToolNames.DELEGATE.equals(toolName) ? agents.find(callId, result) : null;
            return new ChatMessageResponse.ToolCallResponse(callId, toolName,
                    SensitivePaths.scrub(arguments), SensitivePaths.scrub(result), done, startedAt, endedAt, agent);
        }
    }

    private static List<ToolCall> parseToolCalls(String json) {
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<ToolCall>>() {});
        } catch (Exception e) {
            log.warn("[Chat] failed to parse toolCallsJson: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<ToolResponse> parseToolResponses(String json) {
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<ToolResponse>>() {});
        } catch (Exception e) {
            log.warn("[Chat] failed to parse toolResponsesJson: {}", e.getMessage());
            return List.of();
        }
    }

    public void setPinned(String conversationId, boolean pinned) {
        chatMemory.setPinned(conversationId, pinned);
    }

    // -- Conversation sharing --------------------------------------------------

    // Mints (or returns the existing) read-only share token covering this conversation from its
    // first message through uptoMessageId inclusive. Idempotent per (conversation, message) so
    // clicking Share twice on the same reply yields the same link rather than a second live
    // capability URL for identical content - see ConversationShareRepository.
    //
    // The caller (ChatController) has already established that the requester owns the conversation;
    // this method does not re-check, exactly like setPinned/rewind/deleteConversation above.
    // Takes back the thumbs-up/down on one message - the user un-clicking the thumb they just
    // clicked, or flipping to the other one (which retracts before submitting the new mood, so a
    // message never ends up carrying two contradictory ratings). Only auto-ratings are affected;
    // anything the user actually wrote in the feedback dialog carries a comment and is left alone.
    public void retractRating(String conversationId, Long messageId) {
        feedbackRepository.deleteRating(conversationId, messageId, FeedbackSource.GENERAL);
    }

    public ConversationShare createShare(String conversationId, Long uptoMessageId,
                                         String userId, String displayName) {
        // Guards against sharing a message id from a DIFFERENT conversation, which would otherwise
        // silently produce a link showing the whole target conversation (every id <= a foreign,
        // possibly larger id). Message ids are a global IDENTITY sequence, not per-conversation.
        if (!chatMemory.messageBelongs(conversationId, uptoMessageId)) {
            throw new IllegalArgumentException(
                    "messageId " + uptoMessageId + " is not part of conversation " + conversationId);
        }
        return conversationShareRepository.findByConversationIdAndUptoMessageId(conversationId, uptoMessageId)
                .orElseGet(() -> {
                    ConversationShare share = new ConversationShare();
                    share.setToken(UUID.randomUUID().toString().replace("-", ""));
                    share.setConversationId(conversationId);
                    share.setUptoMessageId(uptoMessageId);
                    share.setCreatedByUserId(userId);
                    share.setCreatedByName(displayName);
                    share.setCreatedAt(Instant.now());
                    try {
                        ConversationShare saved = conversationShareRepository.save(share);
                        log.info("[Chat] shared conversationId={} up to messageId={}", conversationId, uptoMessageId);
                        return saved;
                    } catch (org.springframework.dao.DataIntegrityViolationException sharedMeanwhile) {
                        // Another click shared the same message first - its link is this one.
                        return conversationShareRepository
                                .findByConversationIdAndUptoMessageId(conversationId, uptoMessageId)
                                .orElseThrow(() -> sharedMeanwhile);
                    }
                });
    }

    // Resolves a share token to its read-only transcript. Empty when the token is unknown OR the
    // conversation behind it is gone - the two are deliberately indistinguishable to the caller so
    // a deleted conversation's link and a made-up one produce the identical 404, leaking nothing
    // about which ids ever existed.
    public Optional<SharedConversationResponse> getSharedConversation(String token) {
        Optional<ConversationShare> found = conversationShareRepository.findById(token);
        if (found.isEmpty()) return Optional.empty();
        ConversationShare share = found.get();
        Optional<Conversation> conversation = chatMemory.findConversation(share.getConversationId());
        if (conversation.isEmpty()) return Optional.empty();

        // Truncation happens here, on the already-assembled display messages, rather than in the
        // query: getMessagesWithToolCalls() reattaches each tool call to the message that
        // eventually reported it, so cutting the raw rows short first would strip tool calls off
        // the very message being shared.
        //
        // By POSITION, up to and including the shared message - never past a later one. A row with
        // no id is one the transcript made (tool calls taken before a message typed mid-turn, a
        // turn that ended without an answer, a report from a sub-agent), and filtering on the id
        // left every such row out of what was shared.
        List<ChatMessageResponse> messages = new ArrayList<>();
        for (ChatMessageResponse m : getMessagesWithToolCalls(share.getConversationId())) {
            if (m.id() != null && m.id() > share.getUptoMessageId()) break;
            messages.add(m);
            if (share.getUptoMessageId().equals(m.id())) break;
        }
        return Optional.of(new SharedConversationResponse(conversation.get().getTitle(),
                share.getCreatedByName(), share.getCreatedAt(), messages));
    }

    // THE CONVERSATION GOES FIRST, AND EVERYTHING IT OWNED ELSEWHERE AFTER IT, EACH ON ITS OWN. A
    // missing conversation is the caller's 404 and nothing below runs. After that, a step that fails
    // is logged and the rest still run - until 2026-10-14 the feedback delete threw for any
    // conversation anybody had rated, and the share links, the workflow runs, the sub-agents (one
    // still running kept spending) and the deleted event behind it were all skipped for good.
    public void deleteConversation(String conversationId) {
        // Abort any in-flight generation for this conversation first - otherwise a delete mid-
        // stream leaves the retry loop below running against a conversation that no longer
        // exists (ModelCall's retry budget is ten minutes of pointless retries).
        stop(conversationId);
        forgetTurnState(conversationId);
        chatMemory.deleteConversation(conversationId);
        deleteOwned(List.of(conversationId));
    }

    public void deleteAllByUserId(String userId) {
        // Gathered before chatMemory.deleteAllByUserId() removes the conversation rows below -
        // feedback is keyed by conversationId, not userId, so the ids have to be collected while
        // the conversations still exist.
        List<String> conversationIds = chatMemory.listConversationsPaged(userId,
                        org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream().map(Conversation::getId).toList();
        conversationIds.forEach(id -> {
            stop(id);
            forgetTurnState(id);
        });
        chatMemory.deleteAllByUserId(userId);
        deleteOwned(conversationIds);
    }

    // What conversations that are gone owned outside their own rows and folder.
    private void deleteOwned(List<String> conversationIds) {
        for (String id : conversationIds) {
            cascade(id, "feedback", () -> feedbackRepository.deleteByConversationId(id));
            // Every share link pointing at this conversation dies with it. getSharedConversation()
            // would already 404 on the missing conversation without this, so the row removal is
            // about not accumulating orphans (including from the retention sweep, which deletes
            // through this same method) rather than about enforcing the expiry.
            cascade(id, "share links", () -> conversationShareRepository.deleteByConversationId(id));
            // Workflow runs live in their own table rather than on ChatMessage (see WorkflowRun),
            // so cascade-on-delete is not free here - and account deletion has to reach them too,
            // or a deleted user's run rows, which carry step titles and step OUTPUT, outlive them.
            cascade(id, "workflow runs", () -> workflowRunService.deleteForConversation(id));
            // The same for its sub-agents: one still running is stopped, and none is left to
            // report to a conversation that is gone.
            cascade(id, "sub-agents", () -> subAgentService.forget(id));
        }
        // And for what other modules keep by its id (a key account's panel binding).
        if (!conversationIds.isEmpty()) {
            eventPublisher.publishEvent(new ai.proxyma.core.chat.event.ConversationsDeletedEvent(conversationIds));
        }
    }

    private static void cascade(String conversationId, String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.error("[Chat] deleting conversation {}: could not delete its {}", conversationId, what, e);
        }
    }

    // The in-memory state a conversation's turns leave behind, for a conversation that is gone.
    private void forgetTurnState(String conversationId) {
        acceptAllConversations.remove(conversationId);
        permissionModes.remove(conversationId);
        turnOwners.remove(conversationId);
        lastTurnEnded.remove(conversationId);
        turnDisplayNames.remove(conversationId);
        // A stop left for a turn still on its way is kept for it; any other is nobody's.
        if (!turnsDispatched.containsKey(conversationId)) pendingStops.remove(conversationId);
    }

    // -- Workflows -------------------------------------------------------------

    /**
     * The one workflow behind this message's "/Name" chips, or null when there is none.
     *
     * <p>Only the FIRST is run when several are picked. A workflow is a program, and two programs
     * picked for one message have no defined order, no way to pass anything between them, and no
     * sensible answer to what a failure in the first means for the second - so the honest options
     * were "run one" or "refuse". Running one is the less annoying of the two for a chip the user
     * most likely added by accident, and the ignored names are logged rather than silently dropped.
     */
    private WorkflowService.WorkflowDetail resolveWorkflowMention(List<String> skillMentions, String userId) {
        if (skillMentions == null || skillMentions.isEmpty()) return null;
        WorkflowService.WorkflowDetail first = null;
        List<String> ignored = new ArrayList<>();
        for (String name : skillMentions) {
            Optional<WorkflowService.WorkflowDetail> found = workflowService.findByNameVisibleTo(name, userId);
            if (found.isEmpty()) continue;
            if (first == null) first = found.get();
            else ignored.add(found.get().name());
        }
        if (!ignored.isEmpty()) {
            log.info("[Workflow] running '{}' only; {} other workflow(s) picked on the same message ignored: {}",
                    first.name(), ignored.size(), ignored);
        }
        return first;
    }

    /**
     * Runs a workflow as this turn, one scripted user turn per step on the ordinary ReAct loop.
     *
     * <p>The user's own message is persisted HERE rather than by react(), which is the one thing
     * this path does differently from an ordinary turn and it is not cosmetic: the run has to
     * record which message its step table hangs under, and react() appends its message argument
     * internally with nothing handed back. Writing it first means the id is readable before step 1
     * starts - which matters precisely because a run that dies in step 1 still has to draw a table.
     *
     * <p>Each step gets its OWN iteration budget (starting again at 1), not a share of one. A step
     * is a turn; capping the whole run at one turn's worth of iterations would make a five-step
     * workflow's later steps fail for reasons belonging to its earlier ones.
     */
    private ChatResult runWorkflowTurn(String conversationId, WorkflowService.WorkflowDetail workflow,
                                       String userTurn, LlmSession session, boolean streaming, int maxIterations,
                                       AtomicBoolean abort, TurnLatencyTracker latency) {
        chatMemory.add(conversationId, List.of(new org.springframework.ai.chat.messages.UserMessage(userTurn)));
        Long triggerMessageId = chatMemory.lastMessageId(conversationId);
        log.info("[Workflow] '{}' ({} steps) invoked in conversationId={} anchored to message {}",
                workflow.name(), workflow.steps().size(), conversationId, triggerMessageId);
        try {
            WorkflowRunner.Outcome outcome = workflowRunner.run(conversationId, workflow, triggerMessageId, abort,
                    (brief, stepCallId, visible) -> {
                        workflowSteps.put(conversationId, new WorkflowStepContext(stepCallId, visible));
                        try {
                            // A workflow's steps keep the session's cache: its runs do not
                            // delegate in the background.
                            ChatResult stepResult = react(conversationId, session, null,
                                    streaming,
                                    new org.springframework.ai.chat.messages.UserMessage(brief),
                                    1, maxIterations, abort, latency);
                            return new WorkflowRunner.StepResult(stepResult.answer(), stepResult.iterations());
                        } finally {
                            workflowSteps.remove(conversationId);
                        }
                    });
            return new ChatResult(outcome.answer(), conversationId, outcome.iterations());
        } finally {
            // Belt and braces over the per-step removal above: a step context left behind would
            // silently swallow the NEXT turn's streamed text, which reads as the model answering
            // nothing at all and would be a miserable thing to diagnose.
            workflowSteps.remove(conversationId);
        }
    }

    // -- ReAct loop ------------------------------------------------------------

    // A LOOP, since 2026-09-27. It recursed once per iteration, which was harmless while the ceiling
    // was 50 and is not once the ceiling can be "no limit": a long unattended turn would have grown
    // the stack by a frame per model call until it overflowed. reactStep is one iteration; the
    // loop feeds each tool round back in, exactly as the recursive call did.
    private ChatResult react(String conversationId, LlmSession session,
                             java.util.function.Supplier<LlmSession> longCacheSession, boolean streaming,
                             Message message,
                             int iteration, int maxIteration,
                             AtomicBoolean abort, TurnLatencyTracker latency) {
        // What one "yes, go on" buys: as many steps again as the turn started with.
        final int budget = maxIteration;
        while (true) {
            // Auto's step up: once a background sub-agent is working, what this turn writes is read
            // when its report wakes the agent - minutes from now, past a five-minute cache. The
            // steps from here on ask for an hour (ChatSettings.PromptCache.forTurn says why).
            if (longCacheSession != null && subAgentService.hasRunning(conversationId)) {
                session = longCacheSession.get();
                longCacheSession = null;
                log.debug("[Chat] prompt cache steps up to one hour conversationId={}", conversationId);
            }
            ReactStep step = reactStep(conversationId, session, streaming, message,
                    iteration, maxIteration, abort, latency);
            if (step.result() != null) {
                // The model has answered - but a message the user typed meanwhile is waiting, so the
                // turn goes one step further with it rather than ending and making them send it again.
                if (!abort.get() && iteration < maxIteration) {
                    List<Injected> late = drainInjected(conversationId);
                    // And so is a background sub-agent's report: the agent hears it now rather than
                    // in a turn of its own a moment after this one ends.
                    Message reports = takeReports(conversationId);
                    if (!late.isEmpty()) {
                        if (reports != null) chatMemory.add(conversationId, List.of(reports));
                        message = new org.springframework.ai.chat.messages.UserMessage(
                                late.stream().map(Injected::text).collect(java.util.stream.Collectors.joining("\n\n")));
                        iteration++;
                        continue;
                    }
                    if (reports != null) {
                        message = reports;
                        iteration++;
                        continue;
                    }
                }
                return step.result();
            }
            message = step.next();
            iteration++;
            // The next step is the last one, and the model has just asked for more work rather
            // than answering. Until 2026-10-03 that step was told to answer with what it had and
            // the turn ended - on a job the reader may well have wanted finished. They are asked.
            if (iteration == maxIteration && !abort.get()
                    && askToContinue(conversationId, maxIteration, budget, abort)) {
                maxIteration += budget;
            }
        }
    }

    /** The name the continue question goes out under - no tool is called this. */
    static final String CONTINUE_PROMPT = "continueTurn";
    /** The answer that means go on. The page sends it whatever language it asked in. */
    static final String CONTINUE_ANSWER = "Continue";

    /**
     * Asks the reader whether a turn that has used its steps goes on, and waits for the answer -
     * the pause an approval or an askUser question takes, and the same timeout. True only for an
     * explicit "Continue": a skip, a stop, an answer that never comes or one that cannot be read
     * all leave the turn to end as it always did, with the last step answering from what it has
     * and the notice under it.
     *
     * <p>Sent as a USER_QUESTION with the questions written out in English, so anything that can
     * show an askUser question can show this one; `stepLimit` beside them is what lets the page
     * put it in the reader's language instead.
     *
     * <p>Not inside a workflow: a step has a budget of its own, and a run is not a conversation
     * to stop and ask.
     */
    boolean askToContinue(String conversationId, int limit, int more, AtomicBoolean abort) {
        if (workflowSteps.containsKey(conversationId)) return false;
        String trackingId = "step-limit-" + UUID.randomUUID();
        CompletableFuture<String> future = registerPendingQuestion(conversationId, trackingId);
        try {
            askUser(new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.USER_QUESTION.value(), trackingId, CONTINUE_PROMPT,
                    continueQuestion(limit, more)), null);
            log.info("[Chat] turn at its step limit asks to continue conversationId={} limit={}", conversationId, limit);
            long deadline = System.currentTimeMillis() + Duration.ofMinutes(APPROVAL_TIMEOUT_MINUTES).toMillis();
            while (true) {
                if (abort.get()) return false;
                try {
                    return wantsToContinue(future.get(1, TimeUnit.SECONDS));
                } catch (TimeoutException te) {
                    if (System.currentTimeMillis() > deadline) return false;
                } catch (Exception e) {
                    return false;
                }
            }
        } finally {
            pendingQuestions.remove(trackingId);
            pendingDecisionConversation.remove(trackingId);
            promptClosed(trackingId, null);
            eventPublisher.publishEvent(new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.PROMPT_CLOSED.value(), trackingId, null, null));
        }
    }

    static String continueQuestion(int limit, int more) {
        try {
            return JSON.writeValueAsString(Map.of(
                    "stepLimit", limit,
                    "moreSteps", more,
                    "questions", List.of(Map.of(
                            "header", "Step limit",
                            "question", "The agent has reached its limit of " + limit
                                    + " steps and is not finished. Let it continue?",
                            "options", List.of(
                                    Map.of("label", CONTINUE_ANSWER, "description", "Up to " + more + " more steps."),
                                    Map.of("label", "Stop here", "description", "It answers with what it has found."))))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether resolveQuestion's result carries the one answer that means go on. */
    static boolean wantsToContinue(String result) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(result);
            if (!root.path("success").asBoolean(false)) return false;
            for (com.fasterxml.jackson.databind.JsonNode answer : root.path("answers")) {
                if (CONTINUE_ANSWER.equalsIgnoreCase(answer.path("answer").asText("").trim())) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** One iteration's outcome: the turn's result, or the tool round to feed the next one. */
    private record ReactStep(ChatResult result, Message next) {
        static ReactStep done(ChatResult result) { return new ReactStep(result, null); }
        static ReactStep next(Message message) { return new ReactStep(null, message); }
    }

    private ReactStep reactStep(String conversationId, LlmSession session, boolean streaming,
                                Message message,
                                int iteration, int maxIteration,
                                AtomicBoolean abort, TurnLatencyTracker latency) {
        if (abort.get()) {
            // EXCEPT the results of a tool round. Stop pressed while tools run lands HERE, with
            // the assistant message that called them already in memory - and a tool call with no
            // result is a history every provider refuses, so returning without these made the
            // conversation's NEXT turn fail with a 400 ("tool_use ids were found without
            // tool_result blocks"), and every turn after it. A tool cut short by the stop is in
            // here too, answered "Aborted by user" by joinRespectingAbort - which is also the
            // truth the model should be told.
            if (message instanceof ToolResponseMessage) {
                chatMemory.add(conversationId, List.of(message));
            }
            // And the reports that open a continuation: takeReports has already marked them
            // delivered, so a continuation stopped before its first step dropped them for good -
            // the agent never heard what its sub-agents found. Kept, they are read next turn.
            if (message instanceof org.springframework.ai.chat.messages.UserMessage opening
                    && SubAgentService.isReportMessage(opening.getText())) {
                chatMemory.add(conversationId, List.of(message));
            }
            return ReactStep.done(new ChatResult("Chat was stopped.", conversationId, iteration));
        }

        chatMemory.add(conversationId, List.of(message));
        // Messages the user typed during the tool round just finished go in right after its
        // results, so the next model call reads them - see inject().
        if (message instanceof ToolResponseMessage) {
            List<Injected> typed = drainInjected(conversationId);
            if (!typed.isEmpty()) {
                chatMemory.add(conversationId, typed.stream()
                        .map(i -> (Message) new org.springframework.ai.chat.messages.UserMessage(i.text()))
                        .toList());
            }
            // The reports of background sub-agents that finished during that round go in at the
            // same point, for the same reason - see wake().
            Message reports = takeReports(conversationId);
            if (reports != null) chatMemory.add(conversationId, List.of(reports));
        }

        // Re-resolved fresh every iteration (not just once per turn) - a readSkill call earlier
        // in THIS same turn must unlock its connector's tools before the very next LLM call, not
        // just on the next user message. See ToolSkillGating's class javadoc.
        // The turn's user is on this thread (chat() put it there), as callTools reads it below.
        List<ToolCallback> advertised = withExternalTools(toolSkillGating.resolveAdvertised(
                toolCallbacks, coreToolCallbacks, unlockedSkillNames(conversationId), conversationId,
                SearchContext.getUserId()), unlockedSkillNames(conversationId), SearchContext.getUserId());
        // Advertised per CALL now - the session owns the options object that used to be mutated
        // here, which read as though the options were constant across a turn. They never were.
        log.debug("[Chat] iter={} advertising {} tool(s)", iteration, advertised.size());

        // Hard ceiling: on the last allowed iteration, nudge the model toward a final answer.
        // This is a HARD stop, not merely a nudge - once iteration reaches maxIteration, no
        // further tool calls are followed below regardless of what the model tries next, so a
        // model that ignores the instruction (or that keeps calling tools other than "search")
        // can no longer run the loop unbounded.
        if (iteration == maxIteration) {
            chatMemory.add(conversationId,
                    List.of(new org.springframework.ai.chat.messages.UserMessage(FORCED_ANSWER_INSTRUCTION)));
        }

        List<Message> promptMessages = chatMemory.get(conversationId);

        // One model call, streamed - the same call a sub-agent makes (ModelCall). What is this
        // loop's own is what happens to each piece of it: the answer goes out as TEXT events, a
        // thinking count as THINKING, a retry as RETRY, and the turn's latency record is kept.
        //
        // The OUTPUT guardrail's streaming half, one scrubber per call: the cheap deterministic
        // rules run on the way past, before any text becomes a client event. Inert (a
        // pass-through) when output guardrails are off or no output rule is enabled.
        StreamingScrubber scrubber = guardrailService.outputScrubber();

        // "Time to first token" baseline and value - see TurnLatencyTracker's javadoc for why
        // compareAndSet(-1, ...) is enough to make both of these "first iteration of the turn
        // only" without an explicit iteration check. Always executed (cheap nanoTime() call);
        // only the eventual publish is gated by the Evaluation Mode toggle.
        latency.streamStartNanos.compareAndSet(-1, System.nanoTime());

        final int thisIteration = iteration;
        ModelCall.Outcome outcome = ModelCall.run(session, streaming, promptMessages, advertised, abort::get,
                scrubber, new ModelCall.Listener() {
                    @Override public void firstChunk() {
                        latency.firstTokenNanos.compareAndSet(-1, System.nanoTime());
                    }

                    @Override public void text(String text) {
                        // An intermediate workflow step's text is buffered and DROPPED rather than
                        // streamed: it is returned to the runner as that step's output and rendered
                        // inside the step's own row (see WorkflowRunSnapshot.Step.output). Streaming
                        // it as well would give a five-step workflow five walls of text in the
                        // thread, with the step table decorating the mess it exists to replace. The
                        // last step is not suppressed - its text IS the turn's visible reply - and
                        // an ordinary turn has no entry here at all.
                        WorkflowStepContext step = workflowSteps.get(conversationId);
                        if (step != null && !step.streamText()) return;
                        eventPublisher.publishEvent(new ChatEvent(conversationId,
                                chatEventSink.nextSequence(conversationId),
                                ChatEvent.Type.TEXT.value(), null, null, text));
                    }

                    @Override public void thinking(int tokens) {
                        eventPublisher.publishEvent(new ChatEvent(conversationId,
                                chatEventSink.nextSequence(conversationId), ChatEvent.Type.THINKING.value(),
                                null, null, String.valueOf(tokens)));
                    }

                    @Override public void retry(int attempt, Throwable failure) {
                        latency.retryCount.incrementAndGet();
                        eventPublisher.publishEvent(new ChatEvent(conversationId,
                                chatEventSink.nextSequence(conversationId),
                                ChatEvent.Type.RETRY.value(), null, null, "Retry " + attempt));
                        // Full stack trace (with cause chain) on the first attempt only - a generic
                        // wrapper message like "Failed to generate content" from a provider SDK is
                        // useless for diagnosis without what's underneath it, but logging the whole
                        // trace on every attempt would flood the log for what's almost always the
                        // same root cause repeating.
                        if (attempt == 1) {
                            log.warn("[Chat] LLM stream failed iter={} attempt={}: {}",
                                    thisIteration, attempt, failure.getMessage(), failure);
                        } else {
                            log.warn("[Chat] LLM stream failed iter={} attempt={}: {}",
                                    thisIteration, attempt, failure.getMessage());
                        }
                    }

                    // Deliberately does not publish its own ERROR event: the failure is rethrown out
                    // of ModelCall.run, so ChatController's top-level subscribe() error handler will
                    // publish the ERROR event for it - with the root cause Failures.describe() walks
                    // to. Publishing both used to race: the frontend closes its SSE connection on
                    // the FIRST 'error' event it sees, so a shorter one from here always arrived
                    // first and the detailed one was silently discarded.
                    @Override public void failed(Throwable failure) {
                        latency.success = false;
                    }
                });
        if (scrubber.fired()) {
            log.info("[Guardrail] OUTPUT redacted while streaming conversationId={} rules={}",
                    conversationId, scrubber.findings().stream().map(f -> f.rule()).toList());
        }

        // A stopped turn persists nothing of the call that was cut short: the assistant message
        // may be truncated mid-tool-call. Matches the early return at the top of this method.
        if (outcome.stopped()) {
            return ReactStep.done(new ChatResult("Chat was stopped.", conversationId, iteration));
        }

        AssistantMessage assistant = outcome.assistant();
        chatMemory.add(conversationId, List.of(assistant));
        // Before the next iteration below, so the next iteration's resolveAdvertised() already sees
        // anything this message asked to read.
        recordSkillReads(conversationId, assistant);
        // Already folded across every chunk that reported any - see ModelCall.aggregate. The
        // EMPTY check replaces the old "did the response carry metadata at all": a stream that
        // reported nothing now arrives as EMPTY rather than as a null metadata object.
        TokenUsage iterationUsage = session.priced(outcome.usage());
        if (!TokenUsage.EMPTY.equals(iterationUsage)) {
            // Refreshed after EVERY iteration (not just once at the end of the whole turn) so a
            // single long multi-iteration turn - many tool calls growing the context call by call,
            // e.g. a long Jira listing - can still trigger a mid-turn condense via the very next
            // chatMemory.get() call above, the same way a fresh turn can. See
            // SmartChatMemory.recordContextTokens()'s javadoc.
            // promptTokensTotal, NOT inputTokens - for Anthropic, inputTokens alone excludes
            // cache read/write tokens (separate, additive categories in Anthropic's own usage
            // accounting), so it understates the real prompt size by a swing that varies call to
            // call with the cache hit rate - the exact "context window bounces up and down
            // instead of growing" bug this was. promptTokensTotal is pre-normalized per provider
            // by TokenUsageExtractor so this call site doesn't need to know which provider it is.
            chatMemory.recordContextTokens(conversationId, (int) iterationUsage.promptTokensTotal());
            // Persists the cumulative total and pushes it to the client right after THIS response
            // - not just once at the very end of the whole turn - so the usage meter visibly moves
            // through a long multi-iteration turn instead of jumping once at the very end.
            publishUsageUpdate(conversationId, iterationUsage, session.provider(), session.model());
            // Accumulated across every iteration of this turn (not just the last) for
            // ChatTurnLatencyRecordedEvent.outputTokenCount - reuses iterationUsage, already
            // computed above for the usage-meter push, rather than threading a fresh lookup
            // through to publishTurnLatency().
            latency.outputTokenCount.addAndGet(iterationUsage.outputTokens());
        }

        // Text length only, never raw content - conversation content must never land in logs.
        log.info("[Chat] iter={} hasToolCalls={} textLen={} usage[in={} out={} cacheRead={} cacheWrite={}]",
                iteration, assistant.hasToolCalls(),
                assistant.getText() != null ? assistant.getText().length() : 0,
                iterationUsage.inputTokens(), iterationUsage.outputTokens(),
                iterationUsage.cacheReadTokens(), iterationUsage.cacheWriteTokens());

        if (assistant.hasToolCalls() && iteration < maxIteration) {
            ToolResponseMessage toolResponse = callTools(conversationId, assistant.getToolCalls(), abort, null);
            return ReactStep.next(toolResponse);
        }
        if (assistant.hasToolCalls()) {
            // Out of steps, and the model still asked for tools. They are not run - the ceiling is
            // the point - but the calls are already in memory, and unanswered they would break
            // the next turn exactly as a stop mid-tool did. So they are answered, truthfully.
            ToolResponseMessage notRun = ToolPairing.notRun(assistant,
                    "Not run: this turn reached its step limit. Answer with what you have, or ask the user to continue.");
            chatMemory.add(conversationId, List.of(notRun));
            // And shown, as what they are. The transcript lists these calls after a reload, so a
            // turn watched live has to list them too - a call that started and was refused, which
            // is the row the page already draws for any failed tool.
            WorkflowStepContext step = workflowSteps.get(conversationId);
            String parentCallId = step == null ? null : step.stepCallId();
            for (int i = 0; i < assistant.getToolCalls().size(); i++) {
                ToolCall call = assistant.getToolCalls().get(i);
                String trackingId = call.id() != null && !call.id().isEmpty() ? call.id() : UUID.randomUUID().toString();
                eventPublisher.publishEvent(new ChatEvent(conversationId,
                        chatEventSink.nextSequence(conversationId), ChatEvent.Type.TOOL_START.value(),
                        trackingId, call.name(), call.arguments() != null ? call.arguments() : "{}", parentCallId));
                eventPublisher.publishEvent(new ChatEvent(conversationId,
                        chatEventSink.nextSequence(conversationId), ChatEvent.Type.TOOL_RESULT.value(),
                        trackingId, null, notRun.getResponses().get(i).responseData(), parentCallId));
            }
        }
        if (iteration == maxIteration) {
            announceStepLimit(conversationId, maxIteration);
        }

        String answer = assistant.getText();
        if (answer == null || answer.isBlank()) {
            answer = assistant.hasToolCalls()
                    ? "Reached the maximum number of reasoning steps without a final answer."
                    : "The chat produced no response.";
        }
        return ReactStep.done(new ChatResult(answer, conversationId, iteration));
    }

    /**
     * Says, to the reader, that the turn stopped because it ran out of steps.
     *
     * <p>The last allowed step is told to answer with what it has (FORCED_ANSWER_INSTRUCTION), and
     * until 2026-10-01 nothing told the READER any of that: the answer simply trailed off, or -
     * when the model asked for more tools instead of answering - there was no answer at all and the
     * turn ended on a row of tool calls. So it is announced on the stream, and recorded on the
     * step's assistant row so the transcript says it again after a reload.
     *
     * <p>Not for a workflow's intermediate steps: each has a budget of its own and its output is
     * shown inside the step table, where a notice in the thread would belong to nothing visible.
     */
    private void announceStepLimit(String conversationId, int limit) {
        WorkflowStepContext step = workflowSteps.get(conversationId);
        if (step != null && !step.streamText()) return;
        log.info("[Chat] turn reached its step limit conversationId={} limit={}", conversationId, limit);
        chatMemory.markStepLimit(conversationId, limit);
        eventPublisher.publishEvent(new ChatEvent(conversationId,
                chatEventSink.nextSequence(conversationId),
                ChatEvent.Type.STEP_LIMIT.value(), null, null, String.valueOf(limit)));
    }

    // Persists this iteration's usage onto the conversation's running totals and pushes the fresh
    // snapshot (both the lifetime cumulative counters and the just-updated context-window size) to
    // the client as its own SSE event, mirroring ConversationDetail's field set so the frontend can
    // apply it directly without a REST round-trip. Called once per LLM response (see react()) -
    // deliberately NOT batched to once per turn, so the usage meter moves live through a long
    // multi-iteration turn instead of jumping once at the very end when 'done' fires.
    // Body extracted to ConversationUsageRecorder so SubAgentService accumulates delegated spend
    // onto the same conversation counters through the identical path - see that class's javadoc.
    private void publishUsageUpdate(String conversationId, TokenUsage iterationUsage, String provider, String model) {
        usageRecorder.record(conversationId, iterationUsage, provider, model);
    }

    // -- Tool execution --------------------------------------------------------

    /**
     * One round of tool calls, the conversation's own turn's or a sub-agent's - see
     * {@link AgentHost#callTools}.
     *
     * <p>`scope` is null for the conversation's own turn and names the sub-agent otherwise. It
     * changes three things and nothing else: whose row the events nest under, what a call cut short
     * is told, and two tools a sub-agent is refused (callTool). Everything that decides whether a
     * call may run - the permission mode, the approval pause, the consent prompt, the timeout - is
     * the same code for both.
     */
    @Override
    public ToolResponseMessage callTools(String conversationId, List<ToolCall> toolCalls, AtomicBoolean abort,
                                         AgentScope scope) {
        List<String> trackingIds = toolCalls.stream()
                .map(tc -> (tc.id() != null && !tc.id().isEmpty()) ? tc.id() : UUID.randomUUID().toString())
                .toList();

        String callerUserId = SearchContext.getUserId();
        // Who the turn is billed to, carried onto each tool's worker thread with the rest of the
        // context: a search, an OCR or an embedding a tool makes is this turn's spend, and without
        // this it was filed under SYSTEM.
        SpendSubject callerSubject = SpendContext.current();

        // When each call started and ended, for the time a tool list or a sub-agent says it took.
        // The end is written by the worker itself: the joins below run in call order, so a fast
        // call behind a slow one would otherwise be given the slow one's time.
        long startedAt = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicLongArray endedAt =
                new java.util.concurrent.atomic.AtomicLongArray(toolCalls.size());

        List<CompletableFuture<ToolResponse>> futures = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            final ToolCall tc = toolCalls.get(i);
            final String tid = trackingIds.get(i);
            final int index = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                SearchContext.setUserId(callerUserId);
                // Tools run on this pooled worker thread, not react()'s thread, so every piece of
                // SearchContext they read has to be re-set here. WebMediaStorage saves downloaded
                // media under the current conversation's folder keyed off getConversationId() - it
                // silently drops everything (empty `media`) when this is null, so without this the
                // whole web-media save path is dead in chat.
                SearchContext.setConversationId(conversationId);
                // Lets tool-internal work (e.g. RerankingService's per-candidate scoring loop)
                // notice a Stop request between LLM calls, since the outer react() loop can't -
                // it's blocked on this exact tool call until it returns.
                SearchContext.setAbortFlag(abort);
                SpendContext.set(callerSubject);
                try {
                    return callTool(conversationId, tc, tid, abort, scope);
                } finally {
                    endedAt.set(index, System.currentTimeMillis());
                    SearchContext.clear();
                    SpendContext.clear();
                }
            }, BlockingWork.executor()));
        }

        List<ToolResponse> responses = new ArrayList<>();
        Map<String, Object> timings = new java.util.LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            ToolResponse tr = joinRespectingAbort(conversationId, futures.get(i), toolCalls.get(i),
                    trackingIds.get(i), abort, scope);
            responses.add(tr);
            // A call the turn stopped waiting on has no end of its own; it ended, for the reader,
            // when the turn gave up on it.
            long ended = endedAt.get(i);
            if (tr.id() != null && !tr.id().isEmpty()) {
                timings.put(tr.id(), List.of(startedAt, ended > 0 ? ended : System.currentTimeMillis()));
            }
        }
        return ToolResponseMessage.builder().responses(responses)
                .metadata(Map.of(JpaChatMemoryRepository.TOOL_TIMINGS, timings)).build();
    }

    // A plain future.join() would block react() for as long as the slowest tool call takes to
    // finish/time out (up to the ~300s HTTP read timeout - see RestClientConfig) even if the user
    // clicked Stop the instant the batch was dispatched. Polling instead means a Stop request is
    // noticed within ~1s: react()'s next iteration right after callTools() re-checks abort.get()
    // at its own entry and returns immediately, so the overall turn winds down promptly even
    // though the still-running worker thread behind an already-dispatched blocking HTTP call
    // keeps running until it naturally completes or times out (cancelling that call outright is
    // out of scope here).
    //
    // EVERY early return here also has to TELL THE CLIENT, which is what the three
    // abandon() calls below are for. callTool() publishes TOOL_START before running and
    // TOOL_RESULT when it finishes, and the frontend flips a tool row out of its "running"
    // animation on that TOOL_RESULT and on nothing else. Returning a synthetic response to the
    // model without publishing one left the row pulsing for the rest of the session: the turn
    // was over, the input was back, and one action line went on claiming work was happening.
    // Reloading the conversation was the only thing that settled it (settleUnfinishedToolCalls on
    // the frontend). Reported as "the stop button did not stop it and the loading indicator kept
    // going", which was the UI's account of it, not the backend's.
    private ToolResponse joinRespectingAbort(String conversationId, CompletableFuture<ToolResponse> future,
                                             ToolCall toolCall, String trackingId, AtomicBoolean abort,
                                             AgentScope scope) {
        int timeoutSeconds = toolTimeoutSeconds(toolCall.name());
        while (true) {
            try {
                return future.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                if (abort.get()) {
                    // A sub-agent is stopped for more reasons than the user's Stop - it ran out of
                    // time, or the turn it serves ended - and it says which.
                    return abandon(conversationId, toolCall, trackingId,
                            failure(scope == null ? "Aborted by user" : scope.stopNote()), scope);
                }
                // The tool's own limit (toolTimeoutSeconds). Only once it is EXECUTING - see
                // TOOL_CALL_TIMEOUT_SECONDS.
                // The worker thread behind it runs on until its HTTP call returns, exactly as after
                // a Stop; the turn does not wait for it.
                Long started = toolExecutionStarted.get(trackingId);
                if (started != null && System.nanoTime() - started
                        > TimeUnit.SECONDS.toNanos(timeoutSeconds)) {
                    toolExecutionStarted.remove(trackingId);
                    log.warn("[Chat] tool '{}' timed out after {}s trackingId={}", toolCall.name(),
                            timeoutSeconds, trackingId);
                    // A WRITE may still land: the worker is not cancelled, only abandoned. Retrying
                    // it blindly is how one issue becomes two (review, 2026-09-24).
                    String advice = WriteToolRegistry.isWriteCall(toolCall.name(), toolCall.arguments())
                            ? " It may still complete in the background - check whether it took effect before trying again, and never repeat it blindly."
                            : " Try a narrower request, or tell the user the service is slow.";
                    return abandon(conversationId, toolCall, trackingId,
                            "{\"success\":false,\"error\":\"Timed out after " + timeoutSeconds
                                    + " seconds." + advice + "\"}", scope);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return abandon(conversationId, toolCall, trackingId,
                        "{\"success\":false,\"error\":\"Interrupted\"}", scope);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                log.warn("[Chat] tool '{}' future failed unexpectedly: {}", toolCall.name(),
                        Failures.describe(cause), cause);
                return abandon(conversationId, toolCall, trackingId,
                        Failures.describe(cause, "Tool call failed"), scope);
            }
        }
    }

    // Stops waiting on a tool call, tells both the model and the client so, and leaves the worker
    // thread to finish on its own time.
    //
    // The worker may still publish its own TOOL_RESULT for this same trackingId later, if the call
    // it is blocked on ever returns. That is a duplicate rather than a conflict: the frontend keys
    // tool rows by call id and would simply replace this placeholder with the real result, which is
    // strictly more information. In practice it rarely arrives, since the sink for this
    // conversation closes when the turn publishes DONE moments from now.
    // `payload` is passed through verbatim to BOTH the model and the client rather than being
    // wrapped here, so each caller keeps the exact result string it used before this method
    // existed - the two abort cases stay a success:false envelope, and a genuine future failure
    // stays the bare Failures.describe() text that every other failure path in callTool produces.
    private ToolResponse abandon(String conversationId, ToolCall toolCall, String trackingId, String payload,
                                 AgentScope scope) {
        // Same derivation as callTool()'s own events, so an abandoned call inside a workflow step
        // or a sub-agent stays nested there rather than jumping to the top level as it settles.
        publishToolResult(conversationId, scope, trackingId, payload);
        return new ToolResponse(toolCall.id(), toolCall.name(), payload);
    }

    // Whose row a tool event nests under: the sub-agent's, else the running workflow step's, else
    // nobody's - which is every ordinary turn.
    private String parentCallId(String conversationId, AgentScope scope) {
        if (scope != null) return scope.agentId();
        WorkflowStepContext workflowStep = workflowSteps.get(conversationId);
        return workflowStep == null ? null : workflowStep.stepCallId();
    }

    // The two events every tool call publishes. A sub-agent is told of each as it is numbered, so
    // it can say exactly what it had done as of any point in the stream - see SubAgentService.
    //
    // NUMBERED AND RECORDED UNDER THE SUB-AGENT'S OWN LOCK. What it says it had done as of event N
    // (SubAgentService.Run.viewAsOf) has to agree with which events are numbered N and below, and
    // a number handed out a moment before the record is written is a step a page joining at
    // exactly that moment gets from neither.
    private void publishToolStart(String conversationId, AgentScope scope, String trackingId,
                                  String toolName, String arguments) {
        ChatEvent event;
        if (scope == null) {
            event = new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.TOOL_START.value(), trackingId, toolName, arguments,
                    parentCallId(conversationId, null));
        } else {
            synchronized (scope) {
                event = new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                        ChatEvent.Type.TOOL_START.value(), trackingId, toolName, arguments, scope.agentId());
                scope.toolStarted(trackingId, toolName, arguments, event.sequence(), event.at());
            }
        }
        eventPublisher.publishEvent(event);
    }

    private void publishToolResult(String conversationId, AgentScope scope, String trackingId, String result) {
        ChatEvent event;
        if (scope == null) {
            event = new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.TOOL_RESULT.value(), trackingId, null, result,
                    parentCallId(conversationId, null));
        } else {
            synchronized (scope) {
                event = new ChatEvent(conversationId, chatEventSink.nextSequence(conversationId),
                        ChatEvent.Type.TOOL_RESULT.value(), trackingId, null, result, scope.agentId());
                scope.toolFinished(trackingId, result, event.sequence(), event.at());
            }
        }
        eventPublisher.publishEvent(event);
    }

    // -- Prompts waiting for the user -----------------------------------------------------------

    // The request each open prompt was asked with - an approval, a consent switch, a question.
    // A prompt is asked ONCE, as an event; a page that opens the conversation afterwards was not
    // there to hear it, and a sub-agent in the background can ask while nobody is looking at all.
    // So what is still open is kept, and handed to a page with the transcript (pendingPrompts).
    private final ConcurrentHashMap<String, ChatEvent> openPrompts = new ConcurrentHashMap<>();

    // Asks the user. The time a sub-agent spends waiting for the answer is the user's, not its own.
    private void askUser(ChatEvent request, AgentScope scope) {
        openPrompts.put(request.callId(), request);
        if (scope != null) scope.waitingForUser(true);
        eventPublisher.publishEvent(request);
    }

    private void promptClosed(String trackingId, AgentScope scope) {
        if (openPrompts.remove(trackingId) != null && scope != null) scope.waitingForUser(false);
    }

    // A prompt asked by a sub-agent says which one; one asked by the main agent has no parent, as
    // before - inside a workflow step too, where the step's id means nothing to a prompt.
    private static String askedBy(AgentScope scope) {
        return scope == null ? null : scope.agentId();
    }

    // The Audit Logs page's record of each call, made here rather than in the tool wrapper below
    // because only this path knows what the model was given (the result after guardRetrieved) and
    // what never ran (a write refused or denied, a tool a sub-agent may not call) - see ToolAudit.
    private ToolResponse callTool(String conversationId, ToolCall toolCall, String trackingId, AtomicBoolean abort,
                                  AgentScope scope) {
        long started = System.nanoTime();
        ToolResponse response = ToolAudit.recordedByCaller(
                () -> callToolUnaudited(conversationId, toolCall, trackingId, abort, scope));
        try {
            ApprovalNote approval = approvalNotes.remove(trackingId);
            String result = response.responseData();
            boolean threw = threwCalls.remove(trackingId);
            String outcome = (approval != null && approval.denied())
                    || (scope != null && refusedToSubAgents(toolCall.name()) != null)
                    ? AuditEntry.DENIED : threw ? AuditEntry.FAILED : null;
            ToolAudit.record(SearchContext.getUserId(), conversationId, toolCall.name(),
                    scope == null ? "Agent" : "Sub-agent", scope == null ? null : scope.agentId(),
                    toolCall.arguments(), result, outcome, approval == null ? null : approval.text(),
                    (System.nanoTime() - started) / 1_000_000);
        } catch (Exception ignored) {
            // Auditing must never fail a tool call.
        }
        return response;
    }

    /** How a write was let through or stopped, for the audit entry callTool writes - by tracking id. */
    record ApprovalNote(String text, boolean denied) {}

    private final ConcurrentHashMap<String, ApprovalNote> approvalNotes = new ConcurrentHashMap<>();

    // Calls whose tool THREW: the catch below turns the exception into readable text, which carries
    // no failure envelope for the audit entry to recognize.
    private final Set<String> threwCalls = ConcurrentHashMap.newKeySet();

    private void noteApproval(String trackingId, String text, boolean denied) {
        if (trackingId != null) approvalNotes.put(trackingId, new ApprovalNote(text, denied));
    }

    // - Tools of connected MCP servers (ExternalToolSource) -

    // Optional: absent in tests and wherever no module implements it.
    private ExternalToolSource externalTools;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setExternalTools(ExternalToolSource externalTools) {
        this.externalTools = externalTools;
    }

    /** The advertised list plus the tools of the MCP servers this conversation has read the skill of. */
    List<ToolCallback> withExternalTools(List<ToolCallback> advertised, Set<String> unlocked, String userId) {
        return ExternalTools.merge(externalTools, advertised, unlocked, userId);
    }

    private Optional<ToolCallback> externalTool(String name) {
        if (externalTools == null || name == null) return Optional.empty();
        try {
            return externalTools.find(SearchContext.getUserId(), name);
        } catch (RuntimeException e) {
            log.warn("[Chat] MCP tool '{}' could not be found: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    private ToolResponse callToolUnaudited(String conversationId, ToolCall toolCall, String trackingId,
                                           AtomicBoolean abort, AgentScope scope) {
        ToolCallback cb = toolCallbacks.stream()
                .filter(t -> t.getToolDefinition().name().equals(toolCall.name()))
                .findFirst()
                // A connected MCP server's tool, which is not registered at startup - see
                // ExternalToolSource. Found for THIS user only, so a name copied from somebody
                // else's conversation reaches nothing.
                .or(() -> externalTool(toolCall.name()))
                .orElse(null);

        if (cb == null) {
            log.warn("[Chat] no registered tool '{}'. Available: {}", toolCall.name(),
                    toolCallbacks.stream().map(t -> t.getToolDefinition().name()).toList());
            // The failure envelope every other tool failure uses - plain text here rendered as a
            // SUCCESS in the thread, a green tick beside a call that never ran.
            return new ToolResponse(toolCall.id(), toolCall.name(), failure("No such tool: " + toolCall.name()));
        }

        // The arguments' length only: they are the user's words made into a query, a comment being
        // posted, a file being written - conversation content, which never goes to the log.
        log.info("[Chat] tool call: '{}' trackingId={} argsLen={}", toolCall.name(), trackingId,
                toolCall.arguments() != null ? toolCall.arguments().length() : 0);

        String arguments = toolCall.arguments() != null ? toolCall.arguments() : "{}";

        String result;
        try {
            publishToolStart(conversationId, scope, trackingId, toolCall.name(), arguments);

            // The two tools a sub-agent is not handed (SubAgentService leaves them out of what it
            // advertises), refused here as well - a model can call a tool by name without having
            // been offered it, and the tool list is a visibility gate, never the control.
            String refusal = scope == null ? null : refusedToSubAgents(toolCall.name());
            if (refusal != null) {
                publishToolResult(conversationId, scope, trackingId, refusal);
                return new ToolResponse(toolCall.id(), toolCall.name(), refusal);
            }

            // Argument names guessed for a tool whose schema the model was never offered - see
            // ToolArgumentCheck. Answered with the real ones rather than run with none of them.
            String misnamed = ToolArgumentCheck.misnamedArguments(toolCall.name(),
                    cb.getToolDefinition().inputSchema(), toolCall.arguments());
            if (misnamed != null) {
                log.info("[Chat] tool call '{}' not run - its argument names do not match the tool's", toolCall.name());
                String answer = failure(misnamed);
                publishToolResult(conversationId, scope, trackingId, answer);
                return new ToolResponse(toolCall.id(), toolCall.name(), answer);
            }

            // The assistant asking the user: answered by the person, never by a tool - see
            // awaitUserAnswers. The answer is this call's result.
            if (ai.proxyma.core.chat.question.ChatOnlyTools.ASK_USER.equals(toolCall.name())) {
                String answers = awaitUserAnswers(conversationId, toolCall, trackingId, abort, scope);
                publishToolResult(conversationId, scope, trackingId, answers);
                return new ToolResponse(toolCall.id(), toolCall.name(), answers);
            }

            // A sub-agent messaging the agent that started it: answered by that agent, never by a
            // tool - see SubAgentService.messageMain. The reply, if it waits for one, is the result.
            if (SubAgentService.DelegateToolNames.MESSAGE_MAIN.equals(toolCall.name())) {
                String answer = scope == null
                        ? failure("Only a sub-agent can message the agent that started it.")
                        : subAgentService.messageMain(conversationId, scope.agentId(), toolCall.arguments(), abort);
                publishToolResult(conversationId, scope, trackingId, answer);
                return new ToolResponse(toolCall.id(), toolCall.name(), answer);
            }

            String denial = awaitApprovalIfNeeded(conversationId, toolCall, trackingId, abort, scope);
            if (denial != null) {
                publishToolResult(conversationId, scope, trackingId, denial);
                return new ToolResponse(toolCall.id(), toolCall.name(), denial);
            }

            // Published so DelegateTool can find the call it is running as (SearchContext
            // .getToolCallId). Cleared in the finally below because these are pooled worker threads -
            // a stale id left behind would make a later, unrelated tool call look like it belonged
            // to a delegation.
            SearchContext.setToolCallId(trackingId);
            if (!SubAgentService.DelegateToolNames.DELEGATE.equals(toolCall.name())) {
                toolExecutionStarted.put(trackingId, System.nanoTime());
            }
            try {
                result = cb.call(toolCall.arguments());
            } finally {
                toolExecutionStarted.remove(trackingId);
                SearchContext.removeToolCallId();
            }
            // Raw /mcp tool calls (e.g. from an IDE) never reach here, so they still get the
            // tool's unmodified result: external MCP clients have their own context handling and
            // aren't expecting a proprietary "resultOffloaded" envelope. Only an agent loop, which
            // owns the grep/read follow-up call, offloads.
            result = chatMemory.offloadIfLarge(conversationId, toolCall.name(), trackingId, result);

            // Everything the agent retrieves passes through here, which is why the check is here
            // and not in each of the ~60 tools: a search hit, a fetched page, a Jira comment and
            // an MCP tool's result are all one `result` string at this point. Screening at the
            // BOUNDARY rather than at the producer is the same rule SensitivePaths.scrub follows,
            // and for the same reason - a new tool is then covered on the day it is written.
            //
            // AFTER offloadIfLarge, deliberately. An offloaded result is a short envelope naming a
            // file, so this scans a few hundred characters instead of the megabytes that made the
            // envelope necessary - and nothing is skipped by it, because the only way that file
            // reaches the model is the agent reading it back with `read` or `grep`, which are tool
            // calls that arrive right back here.
            result = guardRetrieved(conversationId, toolCall.name(), result);

            publishToolResult(conversationId, scope, trackingId, result);
        } catch (Exception e) {
            log.warn("[Chat] tool '{}' threw {}: {}", toolCall.name(),
                    e.getClass().getSimpleName(), Failures.describe(e), e);
            // Was e.getMessage(): null for the exceptions that carry no message (an NPE out of a
            // connector, say), which put a null result into the ToolResponse and the TOOL_RESULT
            // event rather than anything the agent or the user could read.
            result = Failures.describe(e, "Tool call failed");
            threwCalls.add(trackingId);
            publishToolResult(conversationId, scope, trackingId, result);
        }
        return new ToolResponse(toolCall.id(), toolCall.name(), result);
    }

    /**
     * Why a sub-agent may not call this tool, or null if it may.
     *
     * <p>Three tools, and each for a reason that is about the CONVERSATION rather than about trust.
     * {@code delegate}: a sub-agent that starts sub-agents has no bound on what one turn spends.
     * {@code steerSubAgent}: the conversation's sub-agents are the main agent's to direct.
     * {@code useServer}: it switches which Jira, Confluence or Bitbucket server the whole
     * conversation talks to, and several sub-agents run in it at once - one switching it would
     * send another's write to the wrong server. The main agent switches before it delegates.
     */
    static String refusedToSubAgents(String toolName) {
        if (SubAgentService.DelegateToolNames.DELEGATE.equals(toolName)) {
            return failure("A sub-agent cannot delegate. Do this part yourself, or report it as remaining.");
        }
        // Sub-agents are the main agent's to direct, and a sub-agent has none of its own.
        if (SubAgentService.DelegateToolNames.STEER.equals(toolName)) {
            return failure("A sub-agent cannot direct other sub-agents.");
        }
        if (WriteToolRegistry.CONVERSATION_STATE_NAMES.contains(toolName)) {
            return failure("A sub-agent cannot switch the conversation's server - other agents share it. "
                    + "Report which server the task needs; the agent that delegated to you switches it.");
        }
        return null;
    }

    // Pauses the ReAct loop for a write-capable tool call until the user accepts (once, or "all"
    // for the rest of this conversation) or denies it via POST .../tool-approval, unless "accept
    // all" was already chosen earlier in this same conversation. Runs on the tool-execution
    // worker thread (see callTools' CompletableFuture.supplyAsync), not the LLM-streaming thread,
    // so blocking here does not stall the SSE event bus. Returns null to proceed with the call,
    // or a synthetic denial result string if the user (or an abort/timeout) denies it.
    String awaitApprovalIfNeeded(String conversationId, ToolCall toolCall, String trackingId, AtomicBoolean abort) {
        return awaitApprovalIfNeeded(conversationId, toolCall, trackingId, abort, null);
    }

    // The same gate for a sub-agent's call (`scope`), deliberately not a second one: the mode, the
    // conversation's "accept all", the connection's switches and the pause itself are the user's
    // decisions about what may be written in THIS conversation, whoever asks.
    String awaitApprovalIfNeeded(String conversationId, ToolCall toolCall, String trackingId, AtomicBoolean abort,
                                 AgentScope scope) {
        // Per CALL, not per tool name. A READ through a tool that can also write - a GET through
        // callJiraApi, linkJiraIssues listing link types - changes nothing, so it takes neither the
        // "enable write tools" prompt nor the approval pause (maintainer's decision, 2026-09-24).
        // The tool checks read access itself.
        if (!WriteToolRegistry.isWriteCall(toolCall.name(), toolCall.arguments())) return null;
        PermissionMode mode = permissionModes.getOrDefault(conversationId, PermissionMode.ASK);
        // Read-only refuses every CHANGE outright, before any prompt. Opening a local file is in the
        // write registry only so that it always asks (it reaches the disk); it changes nothing, so
        // it still asks here rather than being refused.
        if (mode == PermissionMode.READ_ONLY && !"openLocalFile".equals(toolCall.name())) {
            log.info("[Chat] write refused in read-only mode: '{}' trackingId={}", toolCall.name(), trackingId);
            noteApproval(trackingId, "Refused: the conversation is read-only", true);
            return READ_ONLY_REFUSAL;
        }
        if (acceptAllConversations.contains(conversationId)) {
            noteApproval(trackingId, "Allowed: accept all for this conversation", false);
            return null;
        }

        String userId = SearchContext.getUserId();
        String rejection = mcpGuard.checkWriteAllowed(toolCall.name(), userId);
        if (rejection != null) {
            // Only a missing *personal* consent flag is self-serve fixable from a chat prompt -
            // org-mode/org-wide-blocked writes keep the immediate rejection below unchanged, since
            // those settings aren't this user's to flip.
            if (mcpGuard.isBlockedOnlyByUserConsent(toolCall.name(), userId)) {
                String enableRejection = awaitEnableDecision(conversationId, toolCall, trackingId, abort, scope);
                if (enableRejection != null) {
                    noteApproval(trackingId, "Refused: write tools left switched off", true);
                    return enableRejection;
                }
                // User enabled it live - re-check before falling through to the normal approval
                // flow below, in case the frontend's settings save silently failed.
                rejection = mcpGuard.checkWriteAllowed(toolCall.name(), userId);
                if (rejection != null) {
                    noteApproval(trackingId, "Refused: " + rejection, true);
                    return "{\"success\":false,\"error\":" + toJsonString(rejection) + "}";
                }
            } else {
                noteApproval(trackingId, "Refused: " + rejection, true);
                return "{\"success\":false,\"error\":" + toJsonString(rejection) + "}";
            }
        }

        // Auto skips the PAUSE and nothing else: the switches checked above still decided whether
        // this write may happen at all, and the tool checks the connection's Write switch again.
        if (mode == PermissionMode.AUTO) {
            noteApproval(trackingId, "Allowed: Auto permission mode", false);
            return null;
        }

        CompletableFuture<ApprovalDecision> future = registerPendingApproval(conversationId, trackingId);
        try {
            askUser(new ChatEvent(conversationId,
                    chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.TOOL_APPROVAL_REQUEST.value(), trackingId, toolCall.name(),
                    toolCall.arguments() != null ? toolCall.arguments() : "{}", askedBy(scope)), scope);
            log.info("[Chat] approval requested: '{}' trackingId={} conversationId={}",
                    toolCall.name(), trackingId, conversationId);

            ApprovalDecision decision = null;
            // Distinguishes *why* a DENY happened for the log line below - "aborted"/"timed out"
            // are backend/UX-driven denials very different in cause from a real user click, but
            // both used to be indistinguishable from the outside as a bare "User denied" message.
            String denyReason = null;
            // ...and what the MODEL is told, which was the same "User denied" for all of them - so
            // an approval nobody answered for half an hour was reported to the user as their own
            // refusal. Null means the user really did deny it.
            String notWritten = null;
            long deadline = System.currentTimeMillis() + Duration.ofMinutes(APPROVAL_TIMEOUT_MINUTES).toMillis();
            while (decision == null) {
                if (abort.get()) {
                    decision = ApprovalDecision.DENY;
                    denyReason = "aborted";
                    notWritten = STOPPED_BEFORE_ANSWER;
                    break;
                }
                try {
                    decision = future.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    if (System.currentTimeMillis() > deadline) {
                        decision = ApprovalDecision.DENY;
                        denyReason = "timed out after " + APPROVAL_TIMEOUT_MINUTES + "m";
                        notWritten = NO_ANSWER_IN_TIME;
                    }
                } catch (Exception e) {
                    decision = ApprovalDecision.DENY;
                    denyReason = e.getClass().getSimpleName();
                    notWritten = ANSWER_UNREADABLE;
                }
            }

            if (decision == ApprovalDecision.ACCEPT_ALL) {
                acceptAllConversations.add(conversationId);
            }
            log.info("[Chat] approval resolved: '{}' trackingId={} decision={}{}",
                    toolCall.name(), trackingId, decision, denyReason != null ? " (" + denyReason + ")" : "");
            if (decision != ApprovalDecision.DENY) {
                noteApproval(trackingId, decision == ApprovalDecision.ACCEPT_ALL
                        ? "Approved by the user, for the rest of the conversation" : "Approved by the user", false);
                return null;
            }
            noteApproval(trackingId, denyReason == null ? "Denied by the user"
                    : "Not approved: " + denyReason, true);
            return "{\"success\":false,\"error\":"
                    + toJsonString(notWritten != null ? notWritten : "User denied this write action.") + "}";
        } finally {
            pendingApprovals.remove(trackingId);
            pendingDecisionConversation.remove(trackingId);
            promptClosed(trackingId, scope);
        }
    }

    // -- The assistant asking the user (askUser) ------------------------------------------------

    // Completed by resolveQuestion() when POST /chat/conversations/{id}/question-answer arrives.
    // Holds the result JSON the model will read. Owned by a conversation through
    // pendingDecisionConversation, like an approval, so one conversation cannot answer another's.
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingQuestions = new ConcurrentHashMap<>();

    /**
     * Pauses the turn until the user answers the assistant's questions - since 2026-09-29, at the
     * maintainer's request, the way a coding assistant in an editor asks. The same blocking pause a
     * write approval takes (awaitApprovalIfNeeded): the same worker thread, the same timeout and the
     * same abort checkpoint, so Stop ends the wait at once. Returns the result the model reads.
     */
    String awaitUserAnswers(String conversationId, ToolCall toolCall, String trackingId, AtomicBoolean abort) {
        return awaitUserAnswers(conversationId, toolCall, trackingId, abort, null);
    }

    String awaitUserAnswers(String conversationId, ToolCall toolCall, String trackingId, AtomicBoolean abort,
                            AgentScope scope) {
        CompletableFuture<String> future = registerPendingQuestion(conversationId, trackingId);
        try {
            askUser(new ChatEvent(conversationId,
                    chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.USER_QUESTION.value(), trackingId, toolCall.name(),
                    toolCall.arguments() != null ? toolCall.arguments() : "{}", askedBy(scope)), scope);
            long deadline = System.currentTimeMillis() + Duration.ofMinutes(APPROVAL_TIMEOUT_MINUTES).toMillis();
            while (true) {
                if (abort.get()) return failure("The turn was stopped before the user answered.");
                try {
                    return future.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    if (System.currentTimeMillis() > deadline) {
                        return failure("No answer arrived within " + APPROVAL_TIMEOUT_MINUTES + " minutes. "
                                + "Continue with your best judgment and say what you assumed.");
                    }
                } catch (Exception e) {
                    return failure("The user's answer could not be read. Ask again in plain text if it matters.");
                }
            }
        } finally {
            pendingQuestions.remove(trackingId);
            pendingDecisionConversation.remove(trackingId);
            promptClosed(trackingId, scope);
        }
    }

    // The pending half, apart from the wait - see registerPendingApproval, which it mirrors.
    CompletableFuture<String> registerPendingQuestion(String conversationId, String trackingId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingDecisionConversation.put(trackingId, conversationId);
        pendingQuestions.put(trackingId, future);
        return future;
    }

    /**
     * Answers a pending askUser call. {@code answers} pairs each question with the chosen label or
     * the user's own words - a String, or a List of them for a question that took several picks
     * (QuestionAnswers); {@code skipped} means they dismissed it. A missing or already answered
     * call - a double click, the timeout already passed - is a no-op.
     */
    public void resolveQuestion(String conversationId, String callId, List<? extends Map<String, ?>> answers,
                                boolean skipped) {
        CompletableFuture<String> future = pendingQuestions.get(callId);
        if (future == null || !Objects.equals(conversationId, pendingDecisionConversation.get(callId))) return;
        if (skipped || answers == null || answers.isEmpty()) {
            future.complete(failure("The user chose not to answer. Continue with your best judgment and say what "
                    + "you assumed."));
            return;
        }
        // An answer is the user's own words reaching the model, as a message is - and was the one
        // way past the INPUT guardrail: a credential typed into it went to the provider and into the
        // stored history (chat-agent-12). A withheld answer is told to the model as one.
        List<Map<String, Object>> screened = screenAnswers(conversationId, answers);
        if (screened == null) {
            future.complete(failure("The user's answer was withheld by this installation's input rules. Continue "
                    + "without it, and say what you assumed."));
            return;
        }
        try {
            future.complete(JSON.writeValueAsString(Map.of("success", true, "answers", screened)));
        } catch (Exception e) {
            future.complete(failure("The user's answer could not be read. Ask again in plain text if it matters."));
        }
    }

    // The answers as the model may read them: every answer and every pick through the INPUT
    // guardrail, redacted where it redacts - or null when it withholds any of them.
    private List<Map<String, Object>> screenAnswers(String conversationId, List<? extends Map<String, ?>> answers) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean screening = guardrailService.enabled(GuardrailStage.INPUT);
        String userId = screening
                ? chatMemory.findConversation(conversationId).map(Conversation::getUserId).orElse(null) : null;
        for (Map<String, ?> answer : answers) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>(answer);
            if (screening) {
                Object given = answer.get("answer");
                Object kept;
                if (given instanceof List<?> picks) {
                    List<String> keptPicks = new ArrayList<>();
                    for (Object pick : picks) {
                        String screenedPick = screenAnswer(String.valueOf(pick), userId, conversationId);
                        if (screenedPick == null) return null;
                        keptPicks.add(screenedPick);
                    }
                    kept = keptPicks;
                } else {
                    kept = given == null ? null : screenAnswer(String.valueOf(given), userId, conversationId);
                    if (given != null && kept == null) return null;
                }
                entry.put("answer", kept);
            }
            out.add(entry);
        }
        return out;
    }

    // One answer through the INPUT guardrail: as written, redacted, or null when withheld.
    private String screenAnswer(String text, String userId, String conversationId) {
        GuardrailDecision decision = checkInput(text, userId, conversationId);
        if (decision.isBlocked()) return null;
        return decision.verdict() == GuardrailDecision.Verdict.REDACT ? decision.text() : text;
    }

    private static String failure(String message) {
        return "{\"success\":false,\"error\":" + toJsonString(message) + "}";
    }

    // Why a paused write or enable decision ended without the user's answer. Package-private for
    // PermissionModeTest.
    static final String STOPPED_BEFORE_ANSWER =
            "The turn was stopped before the user answered, so nothing was written.";
    static final String NO_ANSWER_IN_TIME = "No approval arrived within " + APPROVAL_TIMEOUT_MINUTES
            + " minutes, so nothing was written. Ask the user whether to try again.";
    static final String ANSWER_UNREADABLE =
            "The user's answer could not be read, so nothing was written. Ask the user whether to try again.";

    static final String READ_ONLY_REFUSAL = "{\"success\":false,\"error\":\"The user has set this chat to Read-only, "
            + "so nothing can be created, changed or deleted. Say what you would have done, and that switching "
            + "the permission beside the model to Ask or Auto lets you do it.\"}";

    /**
     * Records who started this conversation's turn - see turnOwners - and where the conversation is
     * held ({@code surface}, ConversationSurface: the stored one when it exists, else the one its
     * first message names).
     */
    public void recordTurnStart(String conversationId, String owner, String surface) {
        if (conversationId == null || owner == null) return;
        long now = System.currentTimeMillis();
        turnOwners.put(conversationId, new TurnOwner(owner, now, surface));
        // On its way: a Stop from now until it registers is kept for it - see stop().
        turnsDispatched.put(conversationId, now);
    }

    /**
     * Whether the conversation's latest turn was started by somebody other than {@code owner} -
     * what decides a request about a conversation that is not stored yet (its first turn still
     * running). A conversation nobody has started a turn in is nobody's yet.
     */
    public boolean startedByAnother(String conversationId, String owner) {
        TurnOwner started = conversationId == null ? null : turnOwners.get(conversationId);
        return started != null && !started.owner().equals(owner);
    }

    /**
     * The conversations of {@code owner} with a turn running now, the most recently started first.
     * Only turns this owner started are listed, so one person's list can never name another's
     * conversation. Only those held on {@code surface} - null for the chat page's own, which is what
     * the history's dots and the header's working dot point back to.
     */
    public List<String> activeConversationsOf(String owner, String surface) {
        if (owner == null) return List.of();
        return turnOwners.entrySet().stream()
                .filter(e -> owner.equals(e.getValue().owner())
                        && java.util.Objects.equals(surface, e.getValue().surface())
                        && (isActive(e.getKey()) || subAgentService.hasRunning(e.getKey())))
                .sorted((a, b) -> Long.compare(b.getValue().startedAt(), a.getValue().startedAt()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** The mode the next tool calls in this conversation run under - see PermissionMode. */
    public void setPermissionMode(String conversationId, PermissionMode mode) {
        if (conversationId == null) return;
        permissionModes.put(conversationId, mode == null ? PermissionMode.ASK : mode);
    }

    // The pending half of an approval, apart from the wait, so the SEC-49 binding can be tested
    // without a model turn: the conversation is recorded BEFORE the future is published, so no
    // resolver can ever see a future without its owner. The caller removes both in its finally.
    CompletableFuture<ApprovalDecision> registerPendingApproval(String conversationId, String trackingId) {
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        pendingDecisionConversation.put(trackingId, conversationId);
        pendingApprovals.put(trackingId, future);
        return future;
    }

    // The same for an enable decision - see registerPendingApproval.
    CompletableFuture<EnableDecision> registerPendingEnableDecision(String conversationId, String trackingId) {
        CompletableFuture<EnableDecision> future = new CompletableFuture<>();
        pendingDecisionConversation.put(trackingId, conversationId);
        pendingEnableDecisions.put(trackingId, future);
        return future;
    }

    // Resolves a pending write-tool approval (see awaitApprovalIfNeeded), called from
    // ChatController's POST .../tool-approval endpoint. A missing/already-resolved future
    // (stale double-click, or the 30-minute timeout already fired) is a safe no-op.
    public void resolveApproval(String conversationId, String callId, String decision) {
        CompletableFuture<ApprovalDecision> future = pendingApprovals.get(callId);
        if (future == null || !Objects.equals(conversationId, pendingDecisionConversation.get(callId))) return;
        ApprovalDecision parsed;
        try {
            parsed = ApprovalDecision.valueOf(decision.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown approval decision: " + decision);
        }
        future.complete(parsed);
        // Accept All also answers the calls ALREADY waiting in this conversation. The set it adds
        // the conversation to is only consulted for calls that ask later, so parallel calls to the
        // same tool each kept their own prompt after it (maintainer's report, 2026-09-26).
        if (parsed == ApprovalDecision.ACCEPT_ALL) {
            acceptAllConversations.add(conversationId);
            pendingDecisionConversation.forEach((otherCallId, otherConversation) -> {
                if (!otherCallId.equals(callId) && conversationId.equals(otherConversation)) {
                    CompletableFuture<ApprovalDecision> other = pendingApprovals.get(otherCallId);
                    if (other != null) other.complete(ApprovalDecision.ACCEPT_ONCE);
                }
            });
        }
    }

    // Pauses the ReAct loop for a write-capable tool call blocked solely by the user's own
    // missing personal consent (see McpGuardService.isBlockedOnlyByUserConsent), asking the
    // frontend to offer a live Enable/Keep Disabled choice - and, if Enable is chosen, the same
    // typed-consent modal Settings uses - before resolving via POST
    // .../tool-enable-decision. Structurally identical to awaitApprovalIfNeeded's blocking-poll
    // pattern (same worker thread, same bounded timeout, same abort-checkpoint). Returns null if
    // the user enabled the setting live (caller re-checks the gate before proceeding), or a
    // synthetic rejection result string if they kept it disabled / aborted / timed out.
    private String awaitEnableDecision(String conversationId, ToolCall toolCall, String trackingId, AtomicBoolean abort,
                                       AgentScope scope) {
        WriteToolRegistry.Connector connector = WriteToolRegistry.connectorFor(toolCall.name());
        if (connector == null) return null; // not one of WriteToolRegistry's connectors - defensive, shouldn't happen

        CompletableFuture<EnableDecision> future = registerPendingEnableDecision(conversationId, trackingId);
        try {
            // Connector management is one per-user setting (connectorField). A Jira, Confluence or
            // Bitbucket write is a switch on a CONNECTION, so the client is told which one - the
            // connection this conversation writes through - and turns on its Write switch.
            String payload;
            try {
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("connectorLabel", connector.label());
                if (connector.field() != null) body.put("connectorField", connector.field());
                mcpGuard.writeTarget(toolCall.name(), SearchContext.getUserId()).ifPresent(t -> {
                    body.put("connectionId", t.id().toString());
                    body.put("connectionName", t.name());
                });
                payload = JSON.writeValueAsString(body);
            } catch (Exception e) {
                payload = "{}";
            }
            askUser(new ChatEvent(conversationId,
                    chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.TOOL_ENABLE_REQUEST.value(), trackingId, toolCall.name(), payload,
                    askedBy(scope)), scope);
            log.info("[Chat] enable-decision requested: '{}' trackingId={} conversationId={}",
                    toolCall.name(), trackingId, conversationId);

            EnableDecision decision = null;
            String reason = null;
            // Same distinction as awaitApprovalIfNeeded: null means the user chose to keep them off.
            String notWritten = null;
            long deadline = System.currentTimeMillis() + Duration.ofMinutes(APPROVAL_TIMEOUT_MINUTES).toMillis();
            while (decision == null) {
                if (abort.get()) {
                    decision = EnableDecision.KEEP_DISABLED;
                    reason = "aborted";
                    notWritten = STOPPED_BEFORE_ANSWER;
                    break;
                }
                try {
                    decision = future.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    if (System.currentTimeMillis() > deadline) {
                        decision = EnableDecision.KEEP_DISABLED;
                        reason = "timed out after " + APPROVAL_TIMEOUT_MINUTES + "m";
                        notWritten = "No answer arrived within " + APPROVAL_TIMEOUT_MINUTES + " minutes on "
                                + "switching on " + connector.label() + " write tools, so nothing was written. "
                                + "Ask the user whether to try again.";
                    }
                } catch (Exception e) {
                    decision = EnableDecision.KEEP_DISABLED;
                    reason = e.getClass().getSimpleName();
                    notWritten = ANSWER_UNREADABLE;
                }
            }

            log.info("[Chat] enable-decision resolved: '{}' trackingId={} decision={}{}",
                    toolCall.name(), trackingId, decision, reason != null ? " (" + reason + ")" : "");
            if (decision == EnableDecision.ENABLED) return null;
            return "{\"success\":false,\"error\":" + toJsonString(notWritten != null ? notWritten
                    : "User chose to keep " + connector.label() + " write tools disabled.") + "}";
        } finally {
            pendingEnableDecisions.remove(trackingId);
            pendingDecisionConversation.remove(trackingId);
            promptClosed(trackingId, scope);
        }
    }

    // Resolves a pending tool-enable decision (see awaitEnableDecision), called from
    // ChatController's POST .../tool-enable-decision endpoint. A missing/already-resolved future
    // (stale double-click, or the timeout already fired) is a safe no-op.
    public void resolveEnableDecision(String conversationId, String callId, String decision) {
        CompletableFuture<EnableDecision> future = pendingEnableDecisions.get(callId);
        if (future == null || !Objects.equals(conversationId, pendingDecisionConversation.get(callId))) return;
        EnableDecision parsed;
        try {
            parsed = EnableDecision.valueOf(decision.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown enable decision: " + decision);
        }
        future.complete(parsed);
    }

    // -- Helpers ---------------------------------------------------------------

    private String buildUserTurn(String question, List<String> mentions, List<String> skillMentions,
                                  List<String> workflowMentions, List<String> attachmentPaths) {
        String result = question;
        if (mentions != null && !mentions.isEmpty()) {
            String mentionList = mentions.stream()
                    .map(m -> "\"" + m + "\"")
                    .collect(Collectors.joining(", "));
            result = result + MENTION_MARKER +
                    "prioritize searching within them, but still search elsewhere if needed. " +
                    "If your answer relies on content NOT from these documents, flag it explicitly. " +
                    "Mentioned: " + mentionList + "]";
        }
        if (skillMentions != null && !skillMentions.isEmpty()) {
            result = result + skillMentionBlock(skillMentions);
        }
        // Its own marker rather than a sixth name on the skill list above, because the skill text
        // says something that would be false here: a workflow's instructions are NOT loaded as
        // messages above this one, they arrive one step at a time after it. The names are still
        // rendered as "/Name" chips on the user's bubble, which is why sanitizeUserContent() reads
        // them back out into the same field skill mentions use - one namespace on screen, two
        // meanings for the model.
        if (workflowMentions != null && !workflowMentions.isEmpty()) {
            String workflowList = workflowMentions.stream()
                    .map(m -> "\"" + m + "\"")
                    .collect(Collectors.joining(", "));
            result = result + WORKFLOW_MENTION_MARKER + "its steps follow as separate messages, one " +
                    "at a time. Answer each step on its own; anything the user wrote above is context " +
                    "for the whole run. Invoked: " + workflowList + "]";
        }
        if (attachmentPaths != null && !attachmentPaths.isEmpty()) {
            String fileList = attachmentPaths.stream()
                    .map(p -> "\"" + p.replace("\\", "/") + "\"")
                    .collect(Collectors.joining(", "));
            result = result + ATTACHMENT_MARKER + fileList + ". Call convertDocument on a path " +
                    "before relying on its content - do not guess from the file name alone.]";
        }
        return result;
    }

    // The markers buildUserTurn() appends above - the model needs this instructional text (the
    // real absolute path for attachments; which connector names to prioritize for mentions; which
    // skills to force-load), so it's what gets persisted via ChatMemory.add().
    // getMessagesWithToolCalls() must never hand any raw annotation back to the frontend though
    // (attachments: server filesystem layout, e.g. /data/users/{userId}/...; mentions/skill
    // mentions: internal instructional text was never meant to be user-visible), so it strips
    // from the EARLIEST marker present onward and surfaces only the attachment file names via
    // sanitizeUserContent() below - cutIdx takes the true minimum across all four markers, so
    // buildUserTurn's own append order among them doesn't matter for correctness.
    private static final String MENTION_MARKER = "\n\n[Context: The user has highlighted these specific documents - ";
    private static final String SKILL_MENTION_MARKER = "\n\n[Context: The user has explicitly requested these skills - ";
    private static final String WORKFLOW_MENTION_MARKER = "\n\n[Context: The user invoked this workflow - ";
    private static final String ATTACHMENT_MARKER = "\n\n[Attached file(s): ";
    private static final Pattern QUOTED_SEGMENT = Pattern.compile("\"([^\"]+)\"");

    /**
     * The block a user message carries for the skills picked with "/", which the transcript reads
     * back as chips (sanitizeUserContent). Shared with ExampleConversationImporter, which writes a
     * recorded message back in the shape a typed one is stored in.
     */
    static String skillMentionBlock(List<String> skillMentions) {
        String skillList = skillMentions.stream()
                .map(m -> "\"" + m + "\"")
                .collect(Collectors.joining(", "));
        return SKILL_MENTION_MARKER +
                "their full instructions were loaded into this conversation as messages above - " +
                "apply them to this message even if they don't seem obviously relevant to your " +
                "own judgment, and do not spend a readSkill call re-reading what you have " +
                "already been given. Requested: " + skillList + "]";
    }

    private record SanitizedUserContent(
            String content, List<String> attachmentNames, List<String> mentions, List<String> skillMentions) {}

    private static SanitizedUserContent sanitizeUserContent(String raw) {
        if (raw == null) return new SanitizedUserContent(raw, List.of(), List.of(), List.of());
        int mentionIdx = raw.indexOf(MENTION_MARKER);
        int skillMentionIdx = raw.indexOf(SKILL_MENTION_MARKER);
        int workflowIdx = raw.indexOf(WORKFLOW_MENTION_MARKER);
        int attachmentIdx = raw.indexOf(ATTACHMENT_MARKER);
        int[] all = { mentionIdx, skillMentionIdx, workflowIdx, attachmentIdx };
        int cutIdx = -1;
        for (int idx : all) {
            if (idx >= 0 && (cutIdx < 0 || idx < cutIdx)) cutIdx = idx;
        }
        if (cutIdx < 0) return new SanitizedUserContent(raw, List.of(), List.of(), List.of());
        String clean = raw.substring(0, cutIdx);
        List<String> attachmentNames = attachmentIdx < 0 ? List.of()
                : extractFileNames(raw.substring(attachmentIdx + ATTACHMENT_MARKER.length(),
                        markerBlockEnd(attachmentIdx, raw, all)));
        List<String> mentions = mentionIdx < 0 ? List.of()
                : extractQuotedNames(raw.substring(mentionIdx + MENTION_MARKER.length(),
                        markerBlockEnd(mentionIdx, raw, all)));
        // Workflow names are returned in the SAME field as skill names, and deliberately: "/Name"
        // is one namespace on screen, so MessageBubble renders both kinds of chip from this one
        // list and does not need to know (or be told twice) which kind it is looking at. The
        // distinction is the model's business, and it is carried by the two separate markers above.
        List<String> skillMentions = new ArrayList<>();
        if (skillMentionIdx >= 0) {
            skillMentions.addAll(extractQuotedNames(raw.substring(skillMentionIdx + SKILL_MENTION_MARKER.length(),
                    markerBlockEnd(skillMentionIdx, raw, all))));
        }
        if (workflowIdx >= 0) {
            skillMentions.addAll(extractQuotedNames(raw.substring(workflowIdx + WORKFLOW_MENTION_MARKER.length(),
                    markerBlockEnd(workflowIdx, raw, all))));
        }
        return new SanitizedUserContent(clean, attachmentNames, mentions, List.copyOf(skillMentions));
    }

    // Each marker's own instructional text ends where the next marker (whichever of the others
    // comes next, by raw string position) begins, rather than always running to the end of the
    // string - buildUserTurn() appends them in a fixed order, but this doesn't rely on that, so
    // extraction stays correct even if it ever changes. `others` may include selfIdx and any
    // number of -1s for markers that are absent; both are ignored by the comparison.
    private static int markerBlockEnd(int selfIdx, String raw, int[] others) {
        int end = raw.length();
        for (int other : others) {
            if (other > selfIdx && other < end) end = other;
        }
        return end;
    }

    private static List<String> extractFileNames(String tail) {
        List<String> names = new ArrayList<>();
        Matcher m = QUOTED_SEGMENT.matcher(tail);
        while (m.find()) {
            String path = m.group(1);
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            names.add(slash >= 0 ? path.substring(slash + 1) : path);
        }
        return names;
    }

    private static List<String> extractQuotedNames(String tail) {
        List<String> names = new ArrayList<>();
        Matcher m = QUOTED_SEGMENT.matcher(tail);
        while (m.find()) names.add(m.group(1));
        return names;
    }
}
