package ai.proxyma.core.chat.agents;

import ai.proxyma.core.system.util.Utf16;
import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContexts;
import ai.proxyma.core.chat.event.ApprovalDecidedEvent;
import ai.proxyma.core.chat.event.ConversationsDeletedEvent;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.model.ToolApprovalRequest;
import ai.proxyma.core.chat.repository.ToolApprovalRequestRepository;
import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.search.domain.SearchContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * THE APPROVALS INBOX (2026-10-29, the Constellations samples' gap 2). A write an agent asks to make
 * where nobody watches its conversation - a ChatModule says which (parksApprovals: a scheduled run, a
 * teammate's message, an agent such a run deployed) - is not a prompt that holds the turn, and the
 * worker under it, for the chat's half hour. It is KEPT here and the call answers at once: nothing was
 * changed, a person decides, the decision arrives as a message. The agent's turn goes on; its report
 * or reply waits for the decision like background work does (ChatService.hasBackgroundWork).
 *
 * <p>A person decides one request or many at once. Approved, the call runs exactly as it would have
 * in the turn - ChatService.callTools, the guardrail, the write switches and the connection's Write
 * switch all checked again now - as that person, in that conversation; its result, or the denial and
 * the person's note, reaches the agent's inbox and wakes it. Only the person the conversation works
 * for decides (ChatController checks it as for any prompt of theirs): approving runs a write on their
 * account. Undecided for {@link #EXPIRY}, a request expires, and the agent is told.
 *
 * <p>Since 2026-11-02 (the maintainer's review of the YouTube assistant): the person may EDIT what the
 * context marks editable - a reply's text - before approving, and the call runs with their text; the
 * agent is told its draft and what was sent, and an {@link ApprovalDecidedEvent} lets a module keep the
 * lesson. And a revised draft for the same SUBJECT (the comment a reply answers) replaces the request
 * still waiting for it, rather than leaving two for one comment.
 *
 * <p>Since the maintainer's next review the same day ("will the rework be reflected here?"): that holds
 * whoever drafts - another agent, another run, another conversation of the same person - and for two
 * drafts made at once (one lock per subject; the context was looked up between the check and the save,
 * five seconds in which a second agent's draft found nothing waiting). The draft replaced is not
 * rewritten in place but RETIRED as {@link ToolApprovalRequest#REPLACED}, listed with the decided ones,
 * and its agent is told; an agent can also withdraw a request of its own ({@link #withdraw}). And a
 * context that was not there when the request was kept - a lookup past its few seconds in a burst of
 * drafts, a service that failed once - is looked up again ({@link #fillMissingContexts}), so the page
 * does not fall back to the call's raw arguments for good.
 *
 * <p>Since 2026-11-03 (the maintainer: "the agent should be aware and revise the remaining comments ... it
 * will revise only the comments that are not actively being edited by the user"): a person editing a waiting
 * draft on the Approvals page HOLDS it ({@link #holdEditing}, renewed while the editor is open, lapsing
 * {@link #EDIT_LEASE} after the last renewal - in memory). A revision of a held draft is refused and the draft
 * kept, and so is its withdrawal ({@link #EDITING}); {@link #waiting} marks it, so an agent revising the
 * drafts still waiting skips it. And when an edit and a revision cross anyway - the hold lapsed, the revision
 * landed, then the person approved - the person's text wins: approving the replaced draft with an edit approves
 * the one now waiting in its place, with the person's text ({@link #decide}).
 *
 * <p>Since 2026-11-04 (the maintainer: "Many comments I already replied but still asked to approve"): a request
 * done another way meanwhile - a YouTube comment the person answered himself, or one deleted - leaves the
 * Waiting list as WITHDRAWN with the reason ({@link #recheckLater}, {@link #recheckWaiting}), and an approval
 * asks once more just before the write, so it never makes a second reply ({@link #decideAll}).
 *
 * <p>AUTOMATIC APPROVAL (2026-11-05, the maintainer: "It should also support auto approve so the agents won't need
 * to wait for the user approval"): where a module says a conversation's writes need nobody (ChatModule.autoApproves -
 * a Constellations project switched to approve its agents' writes), or the conversation runs in the Auto permission
 * mode where nobody watches (a saved agent's run settings), a write is still KEPT here - a row, so nothing a team
 * writes escapes the Decided list - and approved by the server the moment it is asked ({@link #approveAutomatically}),
 * its decider {@link #AUTO_PROJECT} or {@link #AUTO_AGENT}, never a person. It goes through what a person's approval
 * does before the write: a draft waiting for the same subject is replaced, one the person is editing wins, a comment
 * answered or deleted meanwhile is withdrawn and nothing is made, and a second write for a subject already approved
 * is not made. Then the call runs IN THE AGENT'S OWN TURN, and the agent reads its result there, as Auto always
 * gave it; {@link #finishAutomatic} records how it went - made, FAILED, or HELD for a limit and retried by
 * {@link #retryHeld} like any approved write. Nobody reviewed it, so its decision event says {@code automatic}: what
 * learns from a person's decisions (a project's decisions file, its learner) leaves it out.
 */
@Service
public class ApprovalInbox {

    private static final Logger log = LoggerFactory.getLogger(ApprovalInbox.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final Duration EXPIRY = Duration.ofDays(7);
    /** The call ids an approved request runs under: ChatService lets exactly these through once. */
    public static final String CALL_PREFIX = "approved-";
    public static final int PAGE_MAX = 100;
    public static final int DECIDE_MAX = 100;

    public enum Decision { APPROVE, DENY }

    /** Which requests a page lists: those still waiting, those decided (approved, denied or expired), or all. */
    public enum Show { PENDING, DECIDED, ALL }

    /** The decider of a request the server approved because its project approves its agents' writes. */
    public static final String AUTO_PROJECT = "auto:project";
    /** The decider of a request the server approved because its conversation runs in the Auto permission mode. */
    public static final String AUTO_AGENT = "auto:agent";

    /** Whether a request's decider is the server rather than a person. A person's id never starts so. */
    public static boolean automatic(String decidedBy) {
        return decidedBy != null && decidedBy.startsWith("auto:");
    }

    /**
     * Which decided requests a page lists (2026-11-05, the maintainer: "The Decided page should have filters, e.g. manual
     * approved, auto approved, rejected, withdraw"): every one, those a PERSON approved, those approved AUTOmatically,
     * denied, withdrawn, replaced by a newer draft, expired, approved and FAILED, approved and HELD for a limit. Approved
     * ones are counted under who approved them AND under how their call went, so Failed and Held overlap the two.
     */
    public enum DecidedFilter {
        ALL, PERSON, AUTO, DENIED, WITHDRAWN, REPLACED, EXPIRED, FAILED, HELD;

        /** A filter a request names, any case; empty for anything else - never a column name. */
        public static Optional<DecidedFilter> parse(String raw) {
            if (raw == null || raw.isBlank()) return Optional.of(ALL);
            try {
                return Optional.of(valueOf(raw.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * How the Waiting list is ordered (2026-11-05, the maintainer: "Waiting for approval should have a sort by date, by
     * video, by user"): newest first; by the video the write is about, by the viewer it answers, or by the agent that
     * asked - each of those then newest first, then by id, so a poll never moves a row.
     */
    public enum WaitingSort {
        DATE, VIDEO, VIEWER, AGENT;

        /** A sort a request names, any case; empty for anything else - never a column name. */
        public static Optional<WaitingSort> parse(String raw) {
            if (raw == null || raw.isBlank()) return Optional.of(DATE);
            try {
                return Optional.of(valueOf(raw.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * How many waiting requests of a page's trees are read to sort them by what they are about - which is in each
     * one's stored context, JSON no database orders by. Waiting requests expire in {@link #EXPIRY} and are tens in
     * practice; past this many, the list is ordered by date, and the answer says so.
     */
    public static final int SORT_READ_MAX = 1_000;

    /** A person's text, as a page sends it, may be at most this long per edited argument. */
    public static final int EDIT_MAX = 10_000;

    /**
     * How many lookups a request's context gets in all - the one when it is kept and those after
     * (fillMissingContexts) - before the request is left with its arguments alone.
     */
    public static final int CONTEXT_ATTEMPTS = 5;
    /** What a lookup nobody waits on may take: a slow service still answers it. */
    static final Duration FILL_TIMEOUT = Duration.ofSeconds(20);
    /** The tool an agent withdraws a request of its own with (a module offers it; Constellations does). */
    public static final String WITHDRAW_TOOL = "withdrawApproval";

    /** How long a person's hold on a draft they edit lasts after its last renewal (a page renews every 30 s). */
    public static final Duration EDIT_LEASE = Duration.ofMinutes(2);
    /** What an agent revising or withdrawing a draft the person is editing reads. */
    public static final String EDITING = "The person is editing this draft; skip it.";
    /** Holds kept at most - past that the lapsed ones are swept, and a new one past it refused. */
    static final int LEASES_MAX = 10_000;

    /**
     * One request as a page shows it. {@code context}: what the write is about, when a provider said (null
     * otherwise). {@code draftArguments}: the agent's own, when the person edited them before approving.
     * {@code contextPending}: it has no context yet and one is still being looked up - a page asks again soon.
     * {@code subject}: what the write is about, as a provider names it ("youtube-comment:<id>"), or null - a
     * revised draft has the subject of the one it replaced, so a page can keep it in that one's place.
     * {@code autoApproved}: PROJECT or AGENT for a request the server approved itself ({@link #AUTO_PROJECT},
     * {@link #AUTO_AGENT}), null for one a person decided or none did - never the decider's id.
     */
    public record View(String id, String conversationId, String rootConversationId, String agentTitle, String toolName,
                       String arguments, String status, Instant requestedAt, Instant decidedAt, String note, String result,
                       ApprovalContext context, String draftArguments, boolean contextPending, String subject,
                       String runOutcome, Instant retryAt, String autoApproved) {
        /** A view of a request that has not run. */
        public View(String id, String conversationId, String rootConversationId, String agentTitle, String toolName,
                    String arguments, String status, Instant requestedAt, Instant decidedAt, String note, String result,
                    ApprovalContext context, String draftArguments, boolean contextPending, String subject) {
            this(id, conversationId, rootConversationId, agentTitle, toolName, arguments, status, requestedAt, decidedAt,
                    note, result, context, draftArguments, contextPending, subject, null, null, null);
        }

        static View of(ToolApprovalRequest r, boolean lookingUp) {
            ApprovalContext context = ApprovalContext.fromJson(r.getContext()).orElse(null);
            // A retry under way is shown as held: it is not made until it reports.
            String ran = ToolApprovalRequest.RAN_RETRYING.equals(r.getRunOutcome()) ? ToolApprovalRequest.RAN_HELD : r.getRunOutcome();
            String auto = !automatic(r.getDecidedBy()) ? null : AUTO_AGENT.equals(r.getDecidedBy()) ? "AGENT" : "PROJECT";
            return new View(r.getId().toString(), r.getConversationId(), r.getRootConversationId(), r.getAgentTitle(),
                    r.getToolName(), r.getArguments(), r.getStatus(), r.getRequestedAt(), r.getDecidedAt(), r.getNote(),
                    r.getResult(), context, r.getDraftArguments(), context == null && lookingUp, r.getSubject(),
                    ran, ToolApprovalRequest.RAN_HELD.equals(ran) ? r.getRetryAt() : null, auto);
        }
    }

    private final ToolApprovalRequestRepository requests;
    private final AgentInbox inbox;
    private final AgentTree tree;
    private final ObjectProvider<ChatService> chat;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> preApproved = ConcurrentHashMap.newKeySet();
    // The requests whose context is being looked up now, so a page read and the minute's pass do not both.
    private final Set<UUID> filling = ConcurrentHashMap.newKeySet();
    // One draft per subject at a time (park): striped, so the locks do not grow with the subjects.
    private final Object[] subjectLocks = new Object[64];
    {
        for (int i = 0; i < subjectLocks.length; i++) subjectLocks[i] = new Object();
    }
    private Executor executor = BlockingWork.executor();
    private AgentEvents record;
    // The drafts a person is editing now, by request: whose hold it is, and until when (holdEditing).
    private record Lease(String userId, Instant until) {}
    private final ConcurrentHashMap<UUID, Lease> leases = new ConcurrentHashMap<>();
    private java.util.function.Supplier<Instant> clock = Instant::now;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRecord(AgentEvents record) {
        this.record = record;
    }

    private ApprovalContexts contexts;
    private org.springframework.context.ApplicationEventPublisher events;

    /** Where a decision is announced (ApprovalDecidedEvent). Optional for test beds. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    /** What a kept write is about (ApprovalContexts) - asked once, when it is kept. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setContexts(ApprovalContexts contexts) {
        this.contexts = contexts;
    }

    public ApprovalInbox(ToolApprovalRequestRepository requests, AgentInbox inbox, AgentTree tree,
                         ObjectProvider<ChatService> chat) {
        this.requests = requests;
        this.inbox = inbox;
        this.tree = tree;
        this.chat = chat;
    }

    /** Tests only: run an approved call on the deciding thread. */
    void runInline() {
        executor = Runnable::run;
    }

    /** Tests only: the clock a hold lapses by. */
    void setClockForTest(java.util.function.Supplier<Instant> clock) {
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadPending() {
        try {
            pending.addAll(requests.conversationsWithPending());
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read the requests waiting from before the restart: {}", e.getMessage());
        }
        try {
            int held = requests.releaseRetries();
            if (held > 0) log.info("[Approvals] {} retry(ies) cut short by the restart are held again", held);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not hold again the retries cut short by the restart: {}", e.getMessage());
        }
        try {
            int cut = recoverCutShort(clock.get());
            if (cut > 0) log.info("[Approvals] {} approved write(s) the restart cut short were taken up again", cut);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not take up the approved writes the restart cut short: {}", e.getMessage());
        }
    }

    /** How far back an approved write with no outcome is taken up after a restart: an older one is history. */
    static final Duration CUT_SHORT_WINDOW = Duration.ofHours(48);

    /**
     * APPROVED WRITES A RESTART CUT SHORT (2026-11-11 review): approved, never recorded as made, failed or held - the
     * server stopped between the approval and the call's end. Left alone, the write was never made and its subject read
     * as approved for ever, so no new draft was kept for it. A write whose provider can tell whether it was done another
     * way (a YouTube reply: whether the channel answered) is held for a retry now, and the retry asks that first - so a
     * reply the restart caught already posted is withdrawn, never posted twice. Any other is FAILED with why: whether it
     * was made cannot be told, so it is never made blind. Only writes decided within {@link #CUT_SHORT_WINDOW}.
     */
    int recoverCutShort(Instant now) {
        List<ToolApprovalRequest> cut = requests.findByStatusAndRunOutcomeIsNullAndResultIsNullAndDecidedAtAfter(
                ToolApprovalRequest.APPROVED, now.minus(CUT_SHORT_WINDOW));
        int n = 0;
        for (ToolApprovalRequest r : cut) {
            if (contexts != null && contexts.settles(r.getToolName())) {
                r.setRunOutcome(ToolApprovalRequest.RAN_HELD);
                r.setRetryAt(now);
                requests.save(r);
            } else {
                r.setResult("{\"success\":false,\"error\":\"Proxyma stopped while this was being made, so whether it was made is "
                        + "not known. It was not tried again.\"}");
                ran(r, ToolApprovalRequest.RAN_FAILED, now, r.getRunAttempts());
                requests.save(r);
                settle(r, "failed", "The approved " + r.getToolName() + " (request " + r.getId() + ") was cut short when "
                        + "Proxyma stopped, so whether it was made is not known. It was not tried again.", true);
                announce(r, ToolApprovalRequest.APPROVED, r.getDraftArguments(), r.getArguments(), r.getContext(), r.getNote(),
                        false, false);
            }
            n++;
        }
        return n;
    }

    // -- The turn's side --------------------------------------------------------------------------

    /**
     * Keeps a write for a person to decide and returns what the agent reads instead of its result.
     * Called by ChatService where it would otherwise wait for an answer.
     */
    public String park(String conversationId, String userId, ToolCall call) {
        String arguments = AgentInbox.clip(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments(),
                ToolApprovalRequest.ARGUMENTS_MAX);
        // Its subject already approved and held for a limit: made by the server once it lifts, never asked again - and
        // never REPLACED by this draft. Checked first, so a refused write spends no lookup (2026-11-05).
        Optional<String> held = alreadyHeld(call.name(), arguments, userId);
        if (held.isPresent()) return held.get();
        // A write that must not be kept at all - a reply to a comment under another channel's video, or one whose
        // card would lack a field it must carry (a comment YouTube does not have) - is refused before anything is
        // stored, in words the agent can act on. Looked up BEFORE the subject's lock: a slow lookup holds no other
        // draft of the same subject.
        ApprovalContexts.Prepared ready = prepared(conversationId, userId, call.name(), arguments);
        if (ready.refused()) return refusedAnswer(call.name(), ready.refusal(), ready.definite());
        String subject = contexts == null ? null : contexts.subjectOf(call.name(), arguments).orElse(null);
        String context = ready.context() == null ? null : ready.context().toJson();
        if (subject == null) return answer(keep(conversationId, userId, call.name(), arguments, null, context), List.of());
        // One draft per subject: the check for a waiting one, the new row and the old one's retirement
        // are one step, so two agents drafting for one comment at once leave one request, not two.
        synchronized (subjectLocks[Math.floorMod(subject.hashCode(), subjectLocks.length)]) {
            List<ToolApprovalRequest> waiting = waitingFor(call.name(), subject, userId);
            // The person is editing the draft this would replace: theirs stays, and nothing is kept.
            for (ToolApprovalRequest old : waiting) {
                if (beingEdited(old.getId())) return beingEditedAnswer(old);
            }
            if (context == null) context = carried(waiting, arguments);
            ToolApprovalRequest saved = keep(conversationId, userId, call.name(), arguments, subject, context);
            List<UUID> replaced = new java.util.ArrayList<>();
            for (ToolApprovalRequest old : waiting) {
                if (replace(old, saved)) replaced.add(old.getId());
                else if (approvedMeanwhile(old)) return notMadeNow(saved, ALREADY_APPROVED);
            }
            return answer(saved, replaced);
        }
    }

    // Stores one request, waiting. A request kept without a context, of a tool a provider describes, is
    // looked up again later (fillMissingContexts); of any other tool, never.
    private ToolApprovalRequest keep(String conversationId, String userId, String tool, String arguments, String subject,
                                     String context) {
        return keep(conversationId, userId, tool, arguments, subject, context, true);
    }

    // {@code waits}: the conversation waits on it (hasPending) - not for one approved at once (approveAutomatically).
    private ToolApprovalRequest keep(String conversationId, String userId, String tool, String arguments, String subject,
                                     String context, boolean waits) {
        ToolApprovalRequest r = new ToolApprovalRequest();
        r.setConversationId(conversationId);
        r.setRootConversationId(tree.rootOf(conversationId));
        r.setUserId(blankToNull(userId));
        r.setToolName(tool);
        r.setArguments(arguments);
        r.setSubject(subject);
        r.setAgentTitle(tree.node(conversationId).map(AgentNode::getTitle).orElse(null));
        r.setContext(context);
        if (context == null) {
            r.setContextAttempts(contexts != null && contexts.describes(tool) ? 1 : CONTEXT_ATTEMPTS);
        }
        ToolApprovalRequest saved = requests.save(r);
        if (waits) pending.add(conversationId);
        if (record != null) {
            record.write(saved.getRootConversationId(), conversationId,
                    tree.node(conversationId).map(AgentNode::getId).orElse(null),
                    ai.proxyma.core.chat.model.AgentEvent.APPROVAL_ASKED, ToolApprovalRequest.PENDING, null, tool);
        }
        log.info("[Approvals] write '{}' kept for a decision requestId={} conversationId={}{}", tool, saved.getId(),
                conversationId, context == null ? " (no context yet)" : "");
        return saved;
    }

    // What the agent reads instead of the call's result.
    private static String answer(ToolApprovalRequest saved, List<UUID> replaced) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("status", "WAITING_FOR_APPROVAL");
        out.put("requestId", saved.getId().toString());
        StringBuilder note = new StringBuilder("Nothing was changed yet: nobody is watching this conversation, so the "
                + "write waits in the approvals inbox for a person to decide. The decision - and the result, if it is "
                + "approved and made - reaches you as a message beginning \"[Approval\". Do not call it again to wait for it.");
        if (!replaced.isEmpty()) {
            out.put("replaced", replaced.stream().map(UUID::toString).toList());
            note.append(" It REPLACED the earlier draft still waiting for the same thing (request ")
                    .append(replaced.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(", ")))
                    .append("), so the person sees only this one.");
        }
        if (saved.getSubject() != null) {
            note.append(" To revise it, make the same call again for the same thing: the new draft replaces this one.");
        }
        note.append(" To drop it, call ").append(WITHDRAW_TOOL).append(" with its requestId in ids.");
        note.append(" Go on with the rest of your work, and say in your answer what is waiting for approval.");
        out.put("note", note.toString());
        return write(out);
    }

    // Whether a write may be kept and what it is about (ApprovalContexts.prepare) - bounded, never throws: with no
    // provider, or one that fails, it may, with no context.
    private ApprovalContexts.Prepared prepared(String conversationId, String userId, String tool, String arguments) {
        if (contexts == null) return new ApprovalContexts.Prepared(null, null, null);
        try {
            return contexts.prepare(tool, arguments, conversationId, blankToNull(userId));
        } catch (RuntimeException e) {
            return new ApprovalContexts.Prepared(null, null, null);
        }
    }

    // What an agent reads for a write refused before it was kept: nothing was kept or changed.
    // "definite": refused for an answer retrying will not change (the comment is gone), rather than for now (a spent
    // quota, an outage) - what a caller that writes without an agent decides by (CommentPipeline, 2026-11-11).
    private static String refusedAnswer(String tool, String why, boolean definite) {
        log.info("[Approvals] write '{}' refused before it was kept: {}", tool, why);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("status", "REFUSED");
        out.put("definite", definite);
        out.put("error", why + " Nothing was kept or changed.");
        return write(out);
    }

    // What an agent reads when the draft its call would replace is being edited: nothing was kept.
    private static String beingEditedAnswer(ToolApprovalRequest held) {
        log.info("[Approvals] a revision of requestId={} refused: the person is editing it", held.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("status", "BEING_EDITED");
        out.put("requestId", held.getId().toString());
        out.put("error", EDITING);
        return write(out);
    }

    // -- Approved by the server (the project's switch, or the Auto permission mode) ----------------------

    /**
     * What {@link #approveAutomatically} came to: the request to finish once the call ran ({@code requestId}), or -
     * {@code refusal} - what the agent reads instead, nothing having been made.
     */
    public record AutoApproval(UUID requestId, String refusal) {
        public boolean proceed() {
            return refusal == null;
        }
    }

    /** What a second automatic write for a subject already approved is withdrawn with - the reason the Approvals page shows. */
    public static final String ALREADY_APPROVED = notice("A write for the same thing was already approved.");

    // A reason the page shows and translates (tools/i18n/server-messages.mjs reads notice("...")).
    private static String notice(String sentence) {
        return sentence;
    }

    /**
     * Keeps a write and approves it at once as {@code autoBy} ({@link #AUTO_PROJECT} or {@link #AUTO_AGENT}): the row a
     * person's approval would have, decided by the server. Before the call is let through, what a person's approval
     * checks: a draft the person is editing for the same subject wins (nothing kept); one waiting is replaced; a write
     * for a subject already approved and not failed is withdrawn rather than made twice; and the world is asked once
     * whether it was done another way meanwhile (a comment answered or deleted), withdrawn if so. Proceeding, the caller
     * runs the call in its turn and hands its result to {@link #finishAutomatic}.
     */
    public AutoApproval approveAutomatically(String conversationId, String userId, ToolCall call, String autoBy) {
        String by = AUTO_AGENT.equals(autoBy) ? AUTO_AGENT : AUTO_PROJECT;
        String arguments = AgentInbox.clip(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments(),
                ToolApprovalRequest.ARGUMENTS_MAX);
        Optional<String> held = alreadyHeld(call.name(), arguments, userId);
        if (held.isPresent()) return new AutoApproval(null, held.get());
        ApprovalContexts.Prepared ready = prepared(conversationId, userId, call.name(), arguments);
        if (ready.refused()) return new AutoApproval(null, refusedAnswer(call.name(), ready.refusal(), ready.definite()));
        String subject = contexts == null ? null : contexts.subjectOf(call.name(), arguments).orElse(null);
        String context = ready.context() == null ? null : ready.context().toJson();
        ToolApprovalRequest saved;
        if (subject == null) {
            saved = keep(conversationId, userId, call.name(), arguments, null, context, false);
        } else {
            synchronized (subjectLocks[Math.floorMod(subject.hashCode(), subjectLocks.length)]) {
                List<ToolApprovalRequest> waiting = waitingFor(call.name(), subject, userId);
                for (ToolApprovalRequest old : waiting) {
                    if (beingEdited(old.getId())) return new AutoApproval(null, beingEditedAnswer(old));
                }
                if (context == null) context = carried(waiting, arguments);
                boolean twice = approvedBefore(call.name(), subject, userId);
                saved = keep(conversationId, userId, call.name(), arguments, subject, context, false);
                if (twice) return new AutoApproval(null, notMadeNow(saved, ALREADY_APPROVED));
                for (ToolApprovalRequest old : waiting) {
                    if (!replace(old, saved) && approvedMeanwhile(old)) {
                        return new AutoApproval(null, notMadeNow(saved, ALREADY_APPROVED));
                    }
                }
                if (!claimAutomatically(saved, by)) return new AutoApproval(null, notMadeNow(saved, null));
            }
            String reason = doneBeforeApproval(List.of(saved)).get(saved.getId().toString());
            if (reason != null) return new AutoApproval(null, withdrawnApproved(saved, reason));
            return new AutoApproval(saved.getId(), null);
        }
        if (!claimAutomatically(saved, by)) return new AutoApproval(null, notMadeNow(saved, null));
        String reason = doneBeforeApproval(List.of(saved)).get(saved.getId().toString());
        if (reason != null) return new AutoApproval(null, withdrawnApproved(saved, reason));
        return new AutoApproval(saved.getId(), null);
    }

    /**
     * Whether a draft this one was to replace was APPROVED meanwhile (the person's approval crossed an agent's revision,
     * 2026-11-11 review): the revision must not wait beside it, nor be made, or the comment is answered twice.
     */
    private boolean approvedMeanwhile(ToolApprovalRequest old) {
        return requests.findById(old.getId()).map(r -> ToolApprovalRequest.APPROVED.equals(r.getStatus())
                && !ToolApprovalRequest.RAN_FAILED.equals(r.getRunOutcome())).orElse(false);
    }

    /**
     * A WAITING request approved by the server, as {@code autoBy} ({@link #AUTO_PROJECT}), for a caller that kept it with
     * {@link #park} and decides by itself - CommentPipeline's auto-approve (2026-11-11 review: it called {@link #decide}
     * directly, skipping what every other approval checks). Checked first as {@link #approveAutomatically} checks: a
     * subject already approved and not failed withdraws it, and the world is asked once whether it was done another way
     * meanwhile (a comment the channel answered) - withdrawn if so. Whether it was approved.
     */
    public boolean approveWaitingAutomatically(ToolApprovalRequest r, String autoBy) {
        if (r == null || !r.isPending()) return false;
        String by = AUTO_AGENT.equals(autoBy) ? AUTO_AGENT : AUTO_PROJECT;
        if (r.getSubject() != null && approvedBefore(r.getToolName(), r.getSubject(), r.getUserId())) {
            notMadeNow(r, ALREADY_APPROVED);
            return false;
        }
        return decideAll(List.of(r), Decision.APPROVE, null, by, Map.of()).decided() > 0;
    }

    // Decided APPROVED by the server; recorded on the agents' timeline as a person's approval is.
    private boolean claimAutomatically(ToolApprovalRequest saved, String by) {
        if (requests.decide(saved.getId(), ToolApprovalRequest.APPROVED, Instant.now(), by, null) == 0) return false;
        saved.setStatus(ToolApprovalRequest.APPROVED);
        saved.setDecidedBy(by);
        log.info("[Approvals] requestId={} APPROVED automatically ({}) conversationId={}", saved.getId(), by,
                saved.getConversationId());
        recordDecided(saved, "approved");
        return true;
    }

    // Whether this person has a write for this subject approved and not failed - one made, being made or held.
    private boolean approvedBefore(String tool, String subject, String userId) {
        String user = blankToNull(userId);
        try {
            return (user == null ? requests.countApprovedNotFailedForNobody(tool, subject)
                    : requests.countApprovedNotFailed(tool, subject, user)) > 0;
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read whether '{}' was approved before: {}", subject, e.getMessage());
            return false;
        }
    }

    // A request kept and not made: WITHDRAWN with the reason ({@code why} null: decided meanwhile, left as it is).
    private String notMadeNow(ToolApprovalRequest r, String why) {
        if (why != null && requests.decide(r.getId(), ToolApprovalRequest.WITHDRAWN, Instant.now(), null, why) == 1) {
            log.info("[Approvals] requestId={} WITHDRAWN: {} conversationId={}", r.getId(), why, r.getConversationId());
            recordDecided(r, "withdrawn");
        }
        return notMadeAnswer(r, why == null ? "It was decided meanwhile." : why);
    }

    // An automatic approval whose subject was done another way: WITHDRAWN with the reason, nothing made.
    private String withdrawnApproved(ToolApprovalRequest r, String reason) {
        String why = AgentInbox.clip(reason.strip(), ToolApprovalRequest.NOTE_MAX);
        requests.findById(r.getId()).ifPresent(row -> {
            row.setStatus(ToolApprovalRequest.WITHDRAWN);
            row.setNote(why);
            row.setRanAt(clock.get());
            requests.save(row);
        });
        log.info("[Approvals] requestId={} approved automatically but WITHDRAWN: {}", r.getId(), why);
        recordDecided(r, "withdrawn");
        return notMadeAnswer(r, why);
    }

    // What the agent reads for an automatic approval that made nothing.
    private static String notMadeAnswer(ToolApprovalRequest r, String why) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("status", "NOT_MADE");
        out.put("requestId", r.getId().toString());
        // A caller that writes without an agent (CommentPipeline) takes the subject as handled: one is approved already.
        if (ALREADY_APPROVED.equals(why)) out.put("alreadyApproved", true);
        out.put("error", "Not made: " + why + " Nothing was changed. Do not make it again.");
        return write(out);
    }

    /**
     * How an automatically approved call went, from its result as the turn got it: made (DONE, the context linked to
     * where it went), FAILED, or refused for a limit that lifts - HELD, and retried by {@link #retryHeld} like any
     * approved write, the agent told so in place of the refusal. Announced as automatic. Returns what the agent reads.
     */
    public String finishAutomatic(UUID requestId, String result) {
        if (requestId == null) return result;
        Optional<ToolApprovalRequest> found;
        try {
            found = requests.findById(requestId);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not record how request {} went: {}", requestId, e.getMessage());
            return result;
        }
        if (found.isEmpty()) return result;
        ToolApprovalRequest r = found.get();
        String clipped = AgentInbox.clip(result == null ? "" : result, ToolApprovalRequest.RESULT_MAX);
        Instant now = clock.get();
        boolean succeeded = succeeded(clipped);
        Optional<Instant> until = succeeded ? Optional.empty() : heldUntil(r, clipped, now);
        try {
            if (until.isPresent() && withinBound(r, until.get(), 1, now)) {
                hold(r.getId(), clipped, until.get(), 1, now);
                log.info("[Approvals] requestId={} approved automatically, refused for a limit: held until {}", r.getId(),
                        until.get());
                announce(r, ToolApprovalRequest.APPROVED, null, r.getArguments(), r.getContext(), null, false, true, true);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("success", false);
                out.put("status", "HELD");
                out.put("requestId", r.getId().toString());
                out.put("error", ApprovalContexts.errorOf(clipped).orElse("Refused for a limit."));
                out.put("note", "Nothing was made yet. The server makes it itself after " + stamp(until.get())
                        + " and tells you then. Nothing for you to do; do not make it again.");
                return write(out);
            }
            r.setResult(clipped);
            if (succeeded) r.setContext(linked(r.getContext(), clipped));
            ran(r, succeeded ? ToolApprovalRequest.RAN_DONE : ToolApprovalRequest.RAN_FAILED, now, 1);
            requests.save(r);
            log.info("[Approvals] requestId={} approved automatically and {}", r.getId(), succeeded ? "MADE" : "FAILED");
            announce(r, ToolApprovalRequest.APPROVED, null, r.getArguments(), r.getContext(), null, succeeded, false, true);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not record how request {} went: {}", requestId, e.getMessage());
        }
        return result;
    }

    // The agents' timeline: a request decided, as settle records it.
    private void recordDecided(ToolApprovalRequest r, String outcome) {
        if (record == null) return;
        try {
            record.write(r.getRootConversationId(), r.getConversationId(),
                    tree.node(r.getConversationId()).map(AgentNode::getId).orElse(null),
                    ai.proxyma.core.chat.model.AgentEvent.APPROVAL_DECIDED, outcome.toUpperCase(Locale.ROOT), null,
                    r.getToolName());
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not record request {} on the timeline: {}", r.getId(), e.getMessage());
        }
    }

    // This person's requests still waiting for this tool and subject, newest first - one, unless an older
    // build left more.
    private List<ToolApprovalRequest> waitingFor(String tool, String subject, String userId) {
        String user = blankToNull(userId);
        try {
            return user == null
                    ? requests.findByToolNameAndSubjectAndUserIdIsNullAndStatusOrderByRequestedAtDesc(tool, subject,
                            ToolApprovalRequest.PENDING)
                    : requests.findByToolNameAndSubjectAndUserIdAndStatusOrderByRequestedAtDesc(tool, subject, user,
                            ToolApprovalRequest.PENDING);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read the drafts waiting for '{}': {}", subject, e.getMessage());
            return List.of();
        }
    }

    // A newer draft whose own lookup came back empty shows what the draft it replaces was about - the same
    // subject, so the same video, viewer and comment - with its own words. Null when none had a context.
    private static String carried(List<ToolApprovalRequest> waiting, String arguments) {
        Map<String, Object> args = arguments(arguments);
        for (ToolApprovalRequest old : waiting) {
            Optional<ApprovalContext> c = ApprovalContext.fromJson(old.getContext());
            if (c.isPresent()) return c.get().withArguments(args).bounded().map(ApprovalContext::toJson).orElse(null);
        }
        return null;
    }

    // Retires a draft a newer one replaced; its agent hears so unless it is the one that wrote the newer.
    private boolean replace(ToolApprovalRequest old, ToolApprovalRequest newer) {
        if (requests.decide(old.getId(), ToolApprovalRequest.REPLACED, Instant.now(), null, null) == 0) return false;
        leases.remove(old.getId());
        log.info("[Approvals] requestId={} REPLACED by requestId={} subject={}", old.getId(), newer.getId(), old.getSubject());
        boolean sameAsker = old.getConversationId().equals(newer.getConversationId());
        retired(old, "replaced", sameAsker ? null : "Your " + old.getToolName() + " (request " + old.getId()
                + ") was REPLACED by a newer draft for the same thing (request " + newer.getId()
                + (newer.getAgentTitle() == null ? "" : ", by " + newer.getAgentTitle())
                + "), so it will not be made and the person decides the newer one. Nothing was changed. Do not draft it again.");
        return true;
    }

    /** What an agent's {@link #WITHDRAW_TOOL} came to. */
    public record Withdrawal(boolean withdrawn, String message) {}

    /**
     * Withdraws a request still waiting, for an agent working for the same person: {@code roots} are the
     * agent trees whose requests it may withdraw (a Constellations project's), null for any of that
     * person's. It leaves the Waiting list for Decided, as WITHDRAWN, and the agent that asked hears so
     * when it is another conversation than the one withdrawing.
     */
    public Withdrawal withdraw(String requestId, String userId, Collection<String> roots, String conversationId,
                               String reason) {
        Optional<ToolApprovalRequest> found = find(requestId);
        if (found.isEmpty() || !java.util.Objects.equals(found.get().getUserId(), blankToNull(userId))
                || (roots != null && !roots.contains(found.get().getRootConversationId()))) {
            return new Withdrawal(false, "No request " + requestId + " of yours waits for approval.");
        }
        ToolApprovalRequest r = found.get();
        if (!r.isPending()) {
            return new Withdrawal(false, "Request " + requestId + " no longer waits: it is " + r.getStatus() + ".");
        }
        if (beingEdited(r.getId())) return new Withdrawal(false, EDITING);
        String why = reason == null || reason.isBlank() ? null : AgentInbox.clip(reason.strip(), ToolApprovalRequest.NOTE_MAX);
        if (requests.decide(r.getId(), ToolApprovalRequest.WITHDRAWN, Instant.now(), null, why) == 0) {
            return new Withdrawal(false, "Request " + requestId + " was decided meanwhile.");
        }
        leases.remove(r.getId());
        log.info("[Approvals] requestId={} WITHDRAWN conversationId={}", r.getId(), r.getConversationId());
        retired(r, "withdrawn", r.getConversationId().equals(conversationId) ? null
                : "Your " + r.getToolName() + " (request " + r.getId() + ") was WITHDRAWN by a teammate"
                + (why == null ? "" : ": " + why) + ". It will not be made. Nothing was changed.");
        return new Withdrawal(true, "Withdrawn: it left the approvals inbox and will not be made.");
    }

    /** How many requests one withdrawal names at most - two calls clear a hundred and fifty. */
    public static final int WITHDRAW_MAX = 100;

    /** What a withdrawal of several came to: how many left the inbox, and why each other one did not. */
    public record Withdrawals(int withdrawn, List<String> notWithdrawn) {}

    /**
     * Withdraws several waiting requests at once (2026-11-05, the maintainer: a lead asked him for request ids it could
     * not find, to clear one channel's drafts), as {@link #withdraw} does each - the same person's, in {@code roots},
     * logged, the agent that asked told. A reference is a request's id, or what names its subject
     * (ApprovalContextProvider.subjectNamed - a YouTube comment's id), which withdraws every one of the person's
     * requests waiting for that subject. At most {@link #WITHDRAW_MAX} references; the same request counted once.
     */
    public Withdrawals withdrawAll(Collection<String> references, String userId, Collection<String> roots,
                                   String conversationId, String reason) {
        List<String> not = new java.util.ArrayList<>();
        int done = 0;
        Set<String> seen = new java.util.HashSet<>();
        int n = 0;
        for (String raw : references == null ? List.<String>of() : references) {
            if (raw == null || raw.isBlank()) continue;
            if (n++ >= WITHDRAW_MAX) {
                not.add("Only " + WITHDRAW_MAX + " at a time: the rest were not withdrawn; call again with them.");
                break;
            }
            String ref = raw.strip();
            List<String> ids = new java.util.ArrayList<>();
            if (find(ref).isPresent()) {
                ids.add(ref);
            } else {
                String subject = contexts == null ? null : contexts.subjectNamed(ref).orElse(null);
                if (subject != null) {
                    for (ToolApprovalRequest r : waitingBySubject(subject, userId)) {
                        if (roots == null || roots.contains(r.getRootConversationId())) ids.add(r.getId().toString());
                    }
                }
                if (ids.isEmpty()) {
                    not.add(ref + ": nothing of yours waits for approval under that id.");
                    continue;
                }
            }
            for (String id : ids) {
                if (!seen.add(id)) continue;
                Withdrawal w = withdraw(id, userId, roots, conversationId, reason);
                if (w.withdrawn()) done++;
                else not.add(ref + ": " + w.message());
            }
        }
        return new Withdrawals(done, List.copyOf(not));
    }

    // This person's requests waiting for this subject, of any tool, newest first.
    private List<ToolApprovalRequest> waitingBySubject(String subject, String userId) {
        String user = blankToNull(userId);
        try {
            return user == null
                    ? requests.findBySubjectAndUserIdIsNullAndStatusOrderByRequestedAtDesc(subject, ToolApprovalRequest.PENDING)
                    : requests.findBySubjectAndUserIdAndStatusOrderByRequestedAtDesc(subject, user, ToolApprovalRequest.PENDING);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read the requests waiting for '{}': {}", subject, e.getMessage());
            return List.of();
        }
    }

    // A request ended without a decision (replaced, withdrawn): the conversation that asked no longer
    // waits on it, and hears why when {@code told} is given.
    private void retired(ToolApprovalRequest r, String outcome, String told) {
        if (told != null) {
            settle(r, outcome, told, false);
            return;
        }
        if (requests.countByConversationIdAndStatus(r.getConversationId(), ToolApprovalRequest.PENDING) == 0) {
            pending.remove(r.getConversationId());
            // Nothing of it waits any more: a run kept owed for it may settle (2026-11-11 review - a scheduled run whose
            // last draft was withdrawn as done elsewhere stayed RUNNING, and its trigger skipped every slot).
            if (events != null) events.publishEvent(new ai.proxyma.core.chat.event.ApprovalOutcomeNotedEvent(r.getConversationId()));
        }
        if (record != null) {
            record.write(r.getRootConversationId(), r.getConversationId(),
                    tree.node(r.getConversationId()).map(AgentNode::getId).orElse(null),
                    ai.proxyma.core.chat.model.AgentEvent.APPROVAL_DECIDED, outcome.toUpperCase(Locale.ROOT), null,
                    r.getToolName());
        }
    }

    // What the write is about, as JSON, or null. Bounded by ApprovalContexts: a slow or failing lookup
    // costs this request its context for now - fillMissingContexts asks again - and nothing else.
    private String contextOf(String conversationId, String userId, String tool, String arguments, Duration limit) {
        if (contexts == null) return null;
        try {
            return (limit == null ? contexts.resolve(tool, arguments, conversationId, blankToNull(userId))
                    : contexts.resolve(tool, arguments, conversationId, blankToNull(userId), limit))
                    .map(ApprovalContext::toJson).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // -- A context missing when the request was kept -----------------------------------------------

    /**
     * Looks up, again, what the waiting requests without a context are about - the oldest first, a few at a
     * time, one after another so a burst of drafts is not a burst of lookups - each within
     * {@link #FILL_TIMEOUT}, each at most {@link #CONTEXT_ATTEMPTS} times in all. Every minute; a page
     * listing such a request asks for it at once (fillLater).
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void fillMissingContexts() {
        if (contexts == null) return;
        try {
            for (ToolApprovalRequest r : requests.findTop20ByStatusAndContextIsNullAndContextAttemptsLessThanOrderByRequestedAtAsc(
                    ToolApprovalRequest.PENDING, CONTEXT_ATTEMPTS)) {
                if (filling.add(r.getId())) {
                    try {
                        fillContext(r);
                    } finally {
                        filling.remove(r.getId());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not fill in the missing contexts: {}", e.getMessage());
        }
    }

    // Whether a listed request is still worth a lookup: one without a context, of a tool a provider
    // describes, with attempts left - WHATEVER its status (2026-11-03, the maintainer's review: a draft
    // withdrawn while YouTube failed stayed a line of raw arguments among cards, because only a waiting
    // request was ever looked up again). A decided one is looked up only while a page lists it, never by
    // the minute's pass, so old history costs no lookups nobody reads; and its attempts are the same few.
    private boolean wantsContext(ToolApprovalRequest r) {
        return contexts != null && r.getContext() == null && r.getContextAttempts() < CONTEXT_ATTEMPTS
                && contexts.describes(r.getToolName());
    }

    // Asks for a listed request's context in the background, once at a time per request.
    private void fillLater(ToolApprovalRequest r) {
        if (contexts == null || !filling.add(r.getId())) return;
        try {
            executor.execute(() -> {
                try {
                    fillContext(r);
                } finally {
                    filling.remove(r.getId());
                }
            });
        } catch (RuntimeException e) {
            filling.remove(r.getId());
        }
    }

    private void fillContext(ToolApprovalRequest r) {
        try {
            String context = contexts.describes(r.getToolName())
                    ? contextOf(r.getConversationId(), r.getUserId(), r.getToolName(), r.getArguments(), FILL_TIMEOUT) : null;
            if (context == null) context = borrowed(r);
            requests.fillContext(r.getId(), context);
            log.info("[Approvals] requestId={} context {}", r.getId(), context == null ? "still missing" : "filled in");
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not fill in the context of request {}: {}", r.getId(), e.getMessage());
        }
    }

    // What another request of the same person about the same subject was found to be about - the same
    // video, viewer and comment - with this one's own words; null when none has a context. A database read,
    // no lookup: a draft withdrawn or replaced while the service failed shows what its revision shows.
    private String borrowed(ToolApprovalRequest r) {
        if (r.getSubject() == null) return null;
        try {
            Optional<ToolApprovalRequest> other = r.getUserId() == null
                    ? requests.findFirstByToolNameAndSubjectAndUserIdIsNullAndContextIsNotNullOrderByRequestedAtDesc(
                            r.getToolName(), r.getSubject())
                    : requests.findFirstByToolNameAndSubjectAndUserIdAndContextIsNotNullOrderByRequestedAtDesc(
                            r.getToolName(), r.getSubject(), r.getUserId());
            return other.map(o -> carried(List.of(o), r.getArguments())).orElse(null);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read another request about '{}': {}", r.getSubject(), e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> arguments(String json) {
        try {
            Map<String, Object> m = JSON.readValue(json == null ? "{}" : json,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Whether a call id is one this inbox approved - true once, for the call that runs it. */
    public boolean consumeApproved(String callId) {
        return callId != null && preApproved.remove(callId);
    }

    /** Whether a request of this conversation's still waits - its report or reply waits with it. Cheap. */
    public boolean hasPending(String conversationId) {
        return conversationId != null && pending.contains(conversationId);
    }

    // -- The person's side --------------------------------------------------------------------------

    /** A page of one person's requests (Desktop: everyone's - there is one person). */
    public Page<View> forUser(String userId, Show show, int page, int size) {
        PageRequest p = PageRequest.of(Math.max(0, page), clampSize(size), order(show));
        Collection<String> statuses = statuses(show);
        Page<ToolApprovalRequest> rows = blankToNull(userId) == null
                ? requests.findByUserIdIsNullAndStatusIn(statuses, p)
                : requests.findByUserIdAndStatusIn(userId, statuses, p);
        return listed(rows);
    }

    /** A page of the requests of these trees - a module's project, a conversation's agents. */
    public Page<View> forTrees(Collection<String> rootConversationIds, Show show, int page, int size) {
        if (rootConversationIds == null || rootConversationIds.isEmpty()) return Page.empty(PageRequest.of(0, clampSize(size)));
        return listed(requests.findByRootConversationIdInAndStatusIn(rootConversationIds,
                statuses(show), PageRequest.of(Math.max(0, page), clampSize(size), order(show))));
    }

    /**
     * A page of the DECIDED requests of these trees that {@code filter} keeps, in the Decided order ({@link #order}) -
     * filtered by the database, so a page of twenty is twenty of the filter's and the total is the filter's.
     */
    public Page<View> decided(Collection<String> rootConversationIds, DecidedFilter filter, int page, int size) {
        return decided(rootConversationIds, filter, null, page, size);
    }

    /**
     * The same, about these subjects only ({@code subjects} null: any) - a team page's filters by what a request is
     * about. An empty set keeps nothing.
     */
    public Page<View> decided(Collection<String> rootConversationIds, DecidedFilter filter, Collection<String> subjects,
                              int page, int size) {
        PageRequest p = PageRequest.of(Math.max(0, page), clampSize(size), order(Show.DECIDED));
        if (rootConversationIds == null || rootConversationIds.isEmpty()) return Page.empty(p);
        if (subjects != null && subjects.isEmpty()) return Page.empty(p);
        DecidedFilter f = filter == null ? DecidedFilter.ALL : filter;
        if (subjects != null) {
            Collection<String> about = subjects;
            return listed(switch (f) {
                case ALL -> requests.findByRootConversationIdInAndSubjectInAndStatusIn(rootConversationIds, about,
                        statuses(Show.DECIDED), p);
                case PERSON -> requests.approvedByAPersonAbout(rootConversationIds, about, p);
                case AUTO -> requests.approvedAutomaticallyAbout(rootConversationIds, about, p);
                case DENIED, WITHDRAWN, REPLACED, EXPIRED -> requests.findByRootConversationIdInAndSubjectInAndStatusIn(
                        rootConversationIds, about, List.of(f.name()), p);
                case FAILED -> requests.approvedAndRanAbout(rootConversationIds, about,
                        List.of(ToolApprovalRequest.RAN_FAILED), p);
                case HELD -> requests.approvedAndRanAbout(rootConversationIds, about,
                        List.of(ToolApprovalRequest.RAN_HELD, ToolApprovalRequest.RAN_RETRYING), p);
            });
        }
        Page<ToolApprovalRequest> rows = switch (f) {
            case ALL -> requests.findByRootConversationIdInAndStatusIn(rootConversationIds, statuses(Show.DECIDED), p);
            case PERSON -> requests.approvedByAPerson(rootConversationIds, p);
            case AUTO -> requests.approvedAutomatically(rootConversationIds, p);
            case DENIED, WITHDRAWN, REPLACED, EXPIRED ->
                    requests.findByRootConversationIdInAndStatusIn(rootConversationIds, List.of(f.name()), p);
            case FAILED -> requests.approvedAndRan(rootConversationIds, List.of(ToolApprovalRequest.RAN_FAILED), p);
            case HELD -> requests.approvedAndRan(rootConversationIds,
                    List.of(ToolApprovalRequest.RAN_HELD, ToolApprovalRequest.RAN_RETRYING), p);
        };
        return listed(rows);
    }

    /** How many decided requests of these trees each {@link DecidedFilter} keeps - two grouped queries; zeros on a failure. */
    public Map<DecidedFilter, Long> decidedCounts(Collection<String> rootConversationIds) {
        return decidedCounts(rootConversationIds, null);
    }

    /** The same, about these subjects only ({@code subjects} null: any; empty: zeros). */
    public Map<DecidedFilter, Long> decidedCounts(Collection<String> rootConversationIds, Collection<String> subjects) {
        Map<DecidedFilter, Long> out = new java.util.EnumMap<>(DecidedFilter.class);
        for (DecidedFilter f : DecidedFilter.values()) out.put(f, 0L);
        if (rootConversationIds == null || rootConversationIds.isEmpty()) return out;
        if (subjects != null && subjects.isEmpty()) return out;
        try {
            long approved = 0;
            List<Object[]> grouped = subjects == null
                    ? requests.countByStatusAndOutcome(rootConversationIds, statuses(Show.DECIDED))
                    : requests.countByStatusAndOutcomeAbout(rootConversationIds, subjects, statuses(Show.DECIDED));
            for (Object[] row : grouped) {
                String status = String.valueOf(row[0]);
                String ran = row[1] == null ? null : String.valueOf(row[1]);
                long n = row[2] instanceof Number num ? num.longValue() : 0;
                out.merge(DecidedFilter.ALL, n, Long::sum);
                switch (status) {
                    case ToolApprovalRequest.APPROVED -> {
                        approved += n;
                        if (ToolApprovalRequest.RAN_FAILED.equals(ran)) out.merge(DecidedFilter.FAILED, n, Long::sum);
                        if (ToolApprovalRequest.RAN_HELD.equals(ran) || ToolApprovalRequest.RAN_RETRYING.equals(ran)) {
                            out.merge(DecidedFilter.HELD, n, Long::sum);
                        }
                    }
                    case ToolApprovalRequest.DENIED -> out.merge(DecidedFilter.DENIED, n, Long::sum);
                    case ToolApprovalRequest.WITHDRAWN -> out.merge(DecidedFilter.WITHDRAWN, n, Long::sum);
                    case ToolApprovalRequest.REPLACED -> out.merge(DecidedFilter.REPLACED, n, Long::sum);
                    case ToolApprovalRequest.EXPIRED -> out.merge(DecidedFilter.EXPIRED, n, Long::sum);
                    default -> { }
                }
            }
            long auto = approved == 0 ? 0 : subjects == null ? requests.countApprovedAutomatically(rootConversationIds)
                    : requests.countApprovedAutomaticallyAbout(rootConversationIds, subjects);
            out.put(DecidedFilter.AUTO, auto);
            out.put(DecidedFilter.PERSON, Math.max(0, approved - auto));
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not count the decided requests of {} tree(s): {}", rootConversationIds.size(),
                    e.getMessage());
        }
        return out;
    }

    /** A page of the waiting list, and the order it is really in - DATE when there were too many to sort otherwise. */
    public record Sorted(Page<View> page, WaitingSort sort) {}

    /**
     * A page of the requests of these trees still WAITING, in {@code sort}'s order. By date, the database orders and
     * pages them. By video, viewer or agent, what each is about is in its stored context, so the waiting requests -
     * at most {@link #SORT_READ_MAX}, read newest first - are ordered here by that key, its case ignored and a request
     * without one last, keeping the newest-first order (and the id) within one key; past that many, by date.
     * {@code agentNames}: for AGENT, the name of the agent each tree root belongs to (the trees' owner knows), asked
     * once for the roots read; null for none.
     */
    public Sorted waitingInTrees(Collection<String> rootConversationIds, WaitingSort sort,
                                 java.util.function.Function<Collection<String>, Map<String, String>> agentNames,
                                 int page, int size) {
        return waitingInTrees(rootConversationIds, sort, agentNames, null, page, size);
    }

    /**
     * The same, keeping only the waiting requests {@code keep} accepts (null: every one) - a team page's filters by
     * what a request is about, which the trees' owner decides. Filtered, at most {@link #SORT_READ_MAX} waiting
     * requests are read, newest first, and kept or not here; past that many, the newest of them only.
     */
    public Sorted waitingInTrees(Collection<String> rootConversationIds, WaitingSort sort,
                                 java.util.function.Function<Collection<String>, Map<String, String>> agentNames,
                                 java.util.function.Predicate<ToolApprovalRequest> keep, int page, int size) {
        WaitingSort s = sort == null ? WaitingSort.DATE : sort;
        if (rootConversationIds == null || rootConversationIds.isEmpty()
                || (keep == null && (s == WaitingSort.DATE || pendingInTrees(rootConversationIds) > SORT_READ_MAX))) {
            return new Sorted(forTrees(rootConversationIds, Show.PENDING, page, size), WaitingSort.DATE);
        }
        if (s != WaitingSort.DATE && pendingInTrees(rootConversationIds) > SORT_READ_MAX) s = WaitingSort.DATE;
        List<ToolApprovalRequest> all = waitingRequests(rootConversationIds, SORT_READ_MAX);
        if (keep != null) all = all.stream().filter(keep).toList();
        WaitingSort by = s;
        Map<String, String> names = by != WaitingSort.AGENT || agentNames == null ? Map.of()
                : agentNames.apply(all.stream().map(ToolApprovalRequest::getRootConversationId).distinct().toList());
        List<ToolApprovalRequest> sorted = by == WaitingSort.DATE ? all : sortedBy(all, by, names == null ? Map.of() : names);
        PageRequest p = PageRequest.of(Math.max(0, page), clampSize(size));
        int from = (int) Math.min(p.getOffset(), sorted.size());
        int to = Math.min(from + p.getPageSize(), sorted.size());
        Page<ToolApprovalRequest> rows = new org.springframework.data.domain.PageImpl<>(sorted.subList(from, to), p, sorted.size());
        return new Sorted(listed(rows), s);
    }

    // The waiting requests ordered by a key of theirs, those without one last; a stable sort, so the newest-first
    // order they were read in (and the id after it) holds within one key.
    static List<ToolApprovalRequest> sortedBy(List<ToolApprovalRequest> rows, WaitingSort sort, Map<String, String> agentNames) {
        Map<UUID, String> keys = new java.util.HashMap<>();
        for (ToolApprovalRequest r : rows) keys.put(r.getId(), sortKey(r, sort, agentNames));
        java.text.Collator collator = java.text.Collator.getInstance(Locale.ROOT);
        collator.setStrength(java.text.Collator.SECONDARY);
        List<ToolApprovalRequest> out = new java.util.ArrayList<>(rows);
        out.sort(java.util.Comparator.comparing((ToolApprovalRequest r) -> keys.get(r.getId()),
                java.util.Comparator.nullsLast(collator::compare)));
        return out;
    }

    // What a waiting request is sorted by: its context's video or viewer, or its agent's name (and the deployed
    // agent's title under it); null when it has none.
    private static String sortKey(ToolApprovalRequest r, WaitingSort sort, Map<String, String> agentNames) {
        String key = switch (sort) {
            case VIDEO -> contextValue(r, "video");
            case VIEWER -> contextValue(r, "viewer");
            case AGENT -> {
                String root = agentNames.get(r.getRootConversationId());
                String deployed = blankToNull(r.getAgentTitle());
                yield root == null ? deployed : deployed == null ? root : root + " / " + deployed;
            }
            case DATE -> null;
        };
        return blankToNull(key);
    }

    private static String contextValue(ToolApprovalRequest r, String key) {
        return ApprovalContext.fromJson(r.getContext()).flatMap(c -> c.fields() == null ? Optional.empty()
                : c.fields().stream().filter(f -> key.equals(f.key())).map(ApprovalContext.Field::value).findFirst())
                .orElse(null);
    }

    /**
     * The order a page lists requests in, the same on every read (2026-11-03, the maintainer: "the list
     * items jump around, up down, randomly like every 5-10s"): newest first by the time a row SHOWS - when
     * it was decided on the Decided tab, when it was asked otherwise - and then by id, so two requests of
     * the same instant, a burst of drafts, never trade places between one poll and the next.
     */
    static Sort order(Show show) {
        return show == Show.DECIDED
                ? Sort.by(Sort.Order.desc("decidedAt"), Sort.Order.desc("requestedAt"), Sort.Order.desc("id"))
                : Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"));
    }

    // A page as it is shown, asking in the background for what a row shown without a context is about.
    private Page<View> listed(Page<ToolApprovalRequest> rows) {
        return rows.map(r -> {
            boolean wants = wantsContext(r);
            if (wants) fillLater(r);
            return View.of(r, wants);
        });
    }

    public long pendingInTrees(Collection<String> rootConversationIds) {
        if (rootConversationIds == null || rootConversationIds.isEmpty()) return 0;
        return requests.countByRootConversationIdInAndStatus(rootConversationIds, ToolApprovalRequest.PENDING);
    }

    /** The requests of these trees still waiting, newest first, at most {@code max} (a team page's filters). */
    public List<ToolApprovalRequest> waitingRequests(Collection<String> rootConversationIds, int max) {
        if (rootConversationIds == null || rootConversationIds.isEmpty() || max <= 0) return List.of();
        return requests.findByRootConversationIdInAndStatusIn(rootConversationIds, List.of(ToolApprovalRequest.PENDING),
                PageRequest.of(0, Math.min(max, SORT_READ_MAX), order(Show.PENDING))).getContent();
    }

    /** How many decided requests one tree has about one subject (null: about nothing a filter names). */
    public record DecidedGroup(String rootConversationId, String subject, long count) {}

    /**
     * The DECIDED requests of these trees counted by tree and subject together - a team page's filter counts, where
     * choosing an agent narrows the comments' choices and a comment's the agents'. Empty on a failure.
     */
    public List<DecidedGroup> decidedGroups(Collection<String> rootConversationIds) {
        List<DecidedGroup> out = new java.util.ArrayList<>();
        if (rootConversationIds == null || rootConversationIds.isEmpty()) return out;
        try {
            for (Object[] row : requests.countByRootAndSubject(rootConversationIds, statuses(Show.DECIDED))) {
                if (row[0] == null) continue;
                out.add(new DecidedGroup(String.valueOf(row[0]), row[1] == null ? null : String.valueOf(row[1]),
                        row[2] instanceof Number n ? n.longValue() : 0L));
            }
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not count the decided requests by tree and subject: {}", e.getMessage());
        }
        return out;
    }

    public Optional<ToolApprovalRequest> find(String id) {
        try {
            return id == null ? Optional.empty() : requests.findById(UUID.fromString(id.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Decides one request the caller has been checked to own. False when it was no longer waiting
     * (decided by somebody else meanwhile, or expired). An approval runs the call in the background
     * and posts its result; a denial posts at once.
     */
    /**
     * Which of these subjects (ApprovalContextProvider.subjectOf - "youtube-comment:<id>") have a write of this
     * person's waiting for approval: what a listing marks as already drafted, so a draft is not written twice.
     * One query, whatever the number; empty for none, or on a failure, which costs only the mark.
     */
    public java.util.Set<String> waitingSubjects(String userId, java.util.Collection<String> subjects) {
        if (subjects == null || subjects.isEmpty()) return java.util.Set.of();
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            String user = blankToNull(userId);
            List<String> found = user == null
                    ? requests.subjectsWaitingForNobody(subjects, ToolApprovalRequest.PENDING)
                    : requests.subjectsWaiting(subjects, user, ToolApprovalRequest.PENDING);
            if (found != null) out.addAll(found);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read which subjects wait for approval: {}", e.getMessage());
        }
        // An approved write held for a limit is taken too (2026-11-05): drafting it again would ask the person again.
        out.addAll(heldSubjects(userId, subjects));
        return java.util.Set.copyOf(out);
    }

    /**
     * {@link #waitingSubjects}, counting only the requests under these root conversations - a project's own (2026-11-11
     * release review: a channel's comment queue read a draft left in a conversation the project no longer holds as
     * waiting, so the comment was never drafted again while its Approvals page, which lists by the project's
     * conversations, showed nothing to decide; 87 comments were stuck that way). An approved write held for a limit is
     * still taken wherever it is: it will be made. A new draft for the same subject replaces the old waiting one (park).
     */
    public java.util.Set<String> waitingSubjects(String userId, java.util.Collection<String> subjects,
                                                 java.util.Collection<String> roots) {
        if (subjects == null || subjects.isEmpty()) return java.util.Set.of();
        java.util.Set<String> out = new java.util.HashSet<>();
        if (roots != null && !roots.isEmpty()) {
            try {
                String user = blankToNull(userId);
                List<String> found = user == null
                        ? requests.subjectsWaitingUnderForNobody(subjects, ToolApprovalRequest.PENDING, roots)
                        : requests.subjectsWaitingUnder(subjects, user, ToolApprovalRequest.PENDING, roots);
                if (found != null) out.addAll(found);
            } catch (RuntimeException e) {
                log.warn("[Approvals] could not read which subjects wait for approval: {}", e.getMessage());
            }
        }
        out.addAll(heldSubjects(userId, subjects));
        return java.util.Set.copyOf(out);
    }

    /** The outcomes of an APPROVED write that waits for a limit to lift: held, or being retried now. */
    static final List<String> HELD_OUTCOMES = List.of(ToolApprovalRequest.RAN_HELD, ToolApprovalRequest.RAN_RETRYING);

    /**
     * Which of these subjects have a write of this person's APPROVED and HELD for a limit (2026-11-05, the maintainer:
     * "I approved, but youtube API reach limits. Those approved replies should be queued to retry, not to ask for approval
     * again") - held or being retried (retryHeld). One query; empty on a failure.
     */
    public java.util.Set<String> heldSubjects(String userId, java.util.Collection<String> subjects) {
        if (subjects == null || subjects.isEmpty()) return java.util.Set.of();
        try {
            String user = blankToNull(userId);
            List<ToolApprovalRequest> found = user == null
                    ? requests.findBySubjectInAndUserIdIsNullAndStatusAndRunOutcomeIn(subjects, ToolApprovalRequest.APPROVED,
                            HELD_OUTCOMES)
                    : requests.findBySubjectInAndUserIdAndStatusAndRunOutcomeIn(subjects, user, ToolApprovalRequest.APPROVED,
                            HELD_OUTCOMES);
            java.util.Set<String> out = new java.util.HashSet<>();
            if (found != null) for (ToolApprovalRequest r : found) if (r.getSubject() != null) out.add(r.getSubject());
            return out;
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read which subjects are approved and held: {}", e.getMessage());
            return java.util.Set.of();
        }
    }

    /**
     * What an agent reads INSTEAD of a new request when this write's subject already has one the person APPROVED that is
     * held for a limit (2026-11-05): nothing is kept and nobody is asked again - the held one is made by the server once
     * the limit lifts (retryHeld) - or empty when there is none. Pure apart from one read; the subject is the provider's
     * (subjectOf), so a write with no subject is never refused here.
     */
    public Optional<String> alreadyHeld(String tool, String arguments, String userId) {
        if (contexts == null || tool == null) return Optional.empty();
        String subject;
        try {
            subject = contexts.subjectOf(tool, arguments == null || arguments.isBlank() ? "{}" : arguments).orElse(null);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (subject == null) return Optional.empty();
        List<ToolApprovalRequest> held;
        try {
            String user = blankToNull(userId);
            held = user == null
                    ? requests.findByToolNameAndSubjectAndUserIdIsNullAndStatusAndRunOutcomeIn(tool, subject,
                            ToolApprovalRequest.APPROVED, HELD_OUTCOMES)
                    : requests.findByToolNameAndSubjectAndUserIdAndStatusAndRunOutcomeIn(tool, subject, user,
                            ToolApprovalRequest.APPROVED, HELD_OUTCOMES);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read whether '{}' is approved and held: {}", subject, e.getMessage());
            return Optional.empty();
        }
        if (held == null || held.isEmpty()) return Optional.empty();
        ToolApprovalRequest r = held.get(0);
        log.info("[Approvals] write '{}' for {} refused: requestId={} is approved and held for a limit", tool, subject, r.getId());
        String service = ai.proxyma.core.chat.approval.ApprovalContext.fromJson(r.getContext())
                .map(ai.proxyma.core.chat.approval.ApprovalContext::title).filter(t -> !t.isBlank()).orElse("the service");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("status", "ALREADY_APPROVED");
        out.put("requestId", r.getId().toString());
        if (r.getRetryAt() != null) out.put("retryAt", r.getRetryAt().toString());
        out.put("error", "Already approved: the person approved this write for the same thing, and it is made by itself "
                + "once " + service + (service.endsWith("s") ? "'" : "'s") + " daily limit resets - no new approval is "
                + "asked, nothing was kept or changed, and you must not make it again.");
        return Optional.of(write(out));
    }

    public boolean decide(ToolApprovalRequest r, Decision decision, String note, String decidedBy) {
        return decide(r, decision, note, decidedBy, Map.of());
    }

    /**
     * Decides one request, approving it with the person's own text in the arguments its context marks
     * editable ({@code edits}: argument to text; ignored on a denial). Refused with an
     * IllegalArgumentException, before anything is decided, when an edit names an argument the context
     * does not offer, or is empty or too long.
     */
    public boolean decide(ToolApprovalRequest r, Decision decision, String note, String decidedBy, Map<String, String> edits) {
        return decide(r, decision, note, decidedBy, edits, FOLLOW_MAX);
    }

    /** How many revisions in a row an approval with an edit follows to the draft now waiting. */
    static final int FOLLOW_MAX = 3;

    private boolean decide(ToolApprovalRequest r, Decision decision, String note, String decidedBy, Map<String, String> edits,
                           int follow) {
        Map<String, String> changes = decision == Decision.APPROVE && edits != null ? edits : Map.of();
        if (!changes.isEmpty()) edit(r, changes);
        String status = decision == Decision.APPROVE ? ToolApprovalRequest.APPROVED : ToolApprovalRequest.DENIED;
        String cleanNote = note == null || note.isBlank() ? null : AgentInbox.clip(note.strip(), ToolApprovalRequest.NOTE_MAX);
        if (requests.decide(r.getId(), status, Instant.now(), blankToNull(decidedBy), cleanNote) == 0) {
            // THE PERSON'S TEXT WINS a revision that crossed their edit (their hold lapsed, an agent's newer
            // draft replaced the one they edited, then they approved): the draft now waiting in its place is
            // approved with their text. Without an edit, the person approved words that are gone: nothing.
            if (changes.isEmpty() || follow <= 0) return false;
            Optional<ToolApprovalRequest> next = requests.findById(r.getId()).flatMap(this::successor);
            if (next.isEmpty()) return false;
            log.info("[Approvals] requestId={} was replaced; the person's edit goes to requestId={}", r.getId(), next.get().getId());
            try {
                return decide(next.get(), decision, note, decidedBy, changes, follow - 1);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        leases.remove(r.getId());
        // Claimed: read it again, as it stands in the database now.
        ToolApprovalRequest claimed = requests.findById(r.getId()).orElse(r);
        if (decision == Decision.DENY) {
            log.info("[Approvals] requestId={} DENIED conversationId={}", r.getId(), claimed.getConversationId());
            settle(claimed, "denied", "A person denied " + claimed.getToolName() + " (request " + claimed.getId() + "). "
                    + "Nothing was changed." + (cleanNote == null ? "" : "\nTheir note: " + cleanNote)
                    + lesson(claimed, null, false)
                    + "\nDo not try the same write again; carry on without it, or report what you would need.", false);
            announce(claimed, ToolApprovalRequest.DENIED, null, claimed.getArguments(), claimed.getContext(), cleanNote, false, false);
            return true;
        }
        // The edit applies to the request as it now stands, so a draft replaced meanwhile is edited as replaced.
        Edited edited = changes.isEmpty() ? null : edit(claimed, changes);
        if (edited != null) requests.edited(claimed.getId(), edited.arguments(), edited.original(), edited.context());
        log.info("[Approvals] requestId={} APPROVED{} conversationId={}", r.getId(), edited == null ? "" : " (edited)",
                claimed.getConversationId());
        executor.execute(() -> run(claimed, cleanNote, edited));
        return true;
    }

    /**
     * The draft waiting now in the place of one a newer draft REPLACED - the same person's, for the same tool
     * and subject - or empty: for any other request, or when none waits.
     */
    public Optional<ToolApprovalRequest> successor(ToolApprovalRequest r) {
        if (r == null || !ToolApprovalRequest.REPLACED.equals(r.getStatus()) || r.getSubject() == null) return Optional.empty();
        return waitingFor(r.getToolName(), r.getSubject(), r.getUserId()).stream().findFirst();
    }

    // -- The person's hold on a draft they are editing ------------------------------------------------

    /** What {@link #holdEditing} came to. */
    public enum Hold { HELD, NOT_FOUND, NOT_WAITING }

    /**
     * The person opens - or keeps open - the editor of a draft of theirs still waiting: held until
     * {@link #EDIT_LEASE} from now. NOT_FOUND for another person's request (the caller has checked the
     * conversation is reachable), NOT_WAITING once it no longer waits.
     */
    public Hold holdEditing(ToolApprovalRequest r, String userId) {
        if (r == null || !java.util.Objects.equals(r.getUserId(), blankToNull(userId))) return Hold.NOT_FOUND;
        if (!r.isPending()) return Hold.NOT_WAITING;
        Instant now = clock.get();
        if (leases.size() >= LEASES_MAX && !leases.containsKey(r.getId())) {
            leases.entrySet().removeIf(e -> !e.getValue().until().isAfter(now));
            if (leases.size() >= LEASES_MAX) return Hold.NOT_WAITING;
        }
        leases.put(r.getId(), new Lease(r.getUserId(), now.plus(EDIT_LEASE)));
        return Hold.HELD;
    }

    /** The person closed the editor: their hold goes. Only their own; false when there was none. */
    public boolean releaseEditing(ToolApprovalRequest r, String userId) {
        if (r == null) return false;
        Lease l = leases.get(r.getId());
        return l != null && java.util.Objects.equals(l.userId(), blankToNull(userId)) && leases.remove(r.getId(), l);
    }

    /** Whether the person is editing this request now: held, and the hold not lapsed. */
    public boolean beingEdited(UUID requestId) {
        Lease l = requestId == null ? null : leases.get(requestId);
        if (l == null) return false;
        if (l.until().isAfter(clock.get())) return true;
        leases.remove(requestId, l);
        return false;
    }

    /** A request still waiting, and whether the person is editing it (what an agent revising drafts reads). */
    public record Waiting(View view, boolean beingEdited) {}

    /**
     * This person's requests of one tool still waiting, newest first, at most {@code limit} (1-100) - each
     * marked when the person is editing it, so an agent revising the drafts leaves that one alone.
     */
    public List<Waiting> waiting(String userId, String toolName, int limit) {
        PageRequest p = PageRequest.of(0, Math.max(1, Math.min(limit, PAGE_MAX)), order(Show.PENDING));
        String user = blankToNull(userId);
        Page<ToolApprovalRequest> rows = user == null
                ? requests.findByToolNameAndUserIdIsNullAndStatus(toolName, ToolApprovalRequest.PENDING, p)
                : requests.findByToolNameAndUserIdAndStatus(toolName, user, ToolApprovalRequest.PENDING, p);
        return rows.getContent().stream().map(r -> new Waiting(View.of(r, false), beingEdited(r.getId()))).toList();
    }

    /**
     * This person's requests of one tool still waiting, newest first, a page of {@code size} (1-{@link #PAGE_MAX}) at
     * {@code page} (0-based), with how many wait in all - so an agent says the total, never one page's count.
     */
    public WaitingPage waiting(String userId, String toolName, int page, int size) {
        int s = clampSize(size);
        int p = Math.max(0, page);
        PageRequest pr = PageRequest.of(p, s, order(Show.PENDING));
        String user = blankToNull(userId);
        Page<ToolApprovalRequest> rows = user == null
                ? requests.findByToolNameAndUserIdIsNullAndStatus(toolName, ToolApprovalRequest.PENDING, pr)
                : requests.findByToolNameAndUserIdAndStatus(toolName, user, ToolApprovalRequest.PENDING, pr);
        List<Waiting> items = rows.getContent().stream().map(r -> new Waiting(View.of(r, false), beingEdited(r.getId()))).toList();
        return new WaitingPage(items, rows.getTotalElements(), p, s);
    }

    /** One page of the waiting list an agent reads, and how many wait in all. */
    public record WaitingPage(List<Waiting> items, long total, int page, int size) {}

    /**
     * ONE PERSON'S REQUESTS WAITING IN THESE TREES (a Constellations project's), every tool, newest first, a page of
     * {@code size} (1-{@link #PAGE_MAX}) at {@code page} (0-based), with the total the inbox counts - what an agent
     * reads to say how many wait, and to withdraw them (2026-11-05: a lead counted 76, then 66, from a list cut at
     * fifty, while the page said 94). Each marked when the person is editing it.
     */
    public WaitingPage waitingIn(Collection<String> rootConversationIds, String userId, int page, int size) {
        int s = clampSize(size);
        int p = Math.max(0, page);
        if (rootConversationIds == null || rootConversationIds.isEmpty()) return new WaitingPage(List.of(), 0, p, s);
        String user = blankToNull(userId);
        PageRequest pr = PageRequest.of(p, s, order(Show.PENDING));
        Page<ToolApprovalRequest> rows = user == null
                ? requests.findByRootConversationIdInAndUserIdIsNullAndStatus(rootConversationIds, ToolApprovalRequest.PENDING, pr)
                : requests.findByRootConversationIdInAndUserIdAndStatus(rootConversationIds, user, ToolApprovalRequest.PENDING, pr);
        List<Waiting> items = rows.getContent().stream().map(r -> new Waiting(View.of(r, false), beingEdited(r.getId()))).toList();
        return new WaitingPage(items, rows.getTotalElements(), p, s);
    }

    /**
     * Refuses, with an IllegalArgumentException saying why, edits this request does not offer - an argument
     * its context marks no field for, an empty text, one past {@link #EDIT_MAX} - and changes nothing.
     */
    public void checkEdits(ToolApprovalRequest r, Map<String, String> edits) {
        if (edits != null && !edits.isEmpty()) edit(r, edits);
    }

    /** The arguments and context with the person's text in place, and what each edited argument said before. */
    private record Edited(String arguments, String context, Map<String, String> draft, Map<String, String> sent,
                          String original) {}

    // Null when the person's text is what the agent wrote already: nothing was edited.
    private Edited edit(ToolApprovalRequest r, Map<String, String> changes) {
        ApprovalContext context = ApprovalContext.fromJson(r.getContext())
                .orElseThrow(() -> new IllegalArgumentException("This request offers nothing to edit."));
        Map<String, ApprovalContext.Field> offered = context.editable();
        Map<String, Object> args;
        try {
            args = JSON.readValue(r.getArguments() == null ? "{}" : r.getArguments(),
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("This request's arguments cannot be edited.");
        }
        if (args == null) args = new LinkedHashMap<>();
        Map<String, String> draft = new LinkedHashMap<>();
        Map<String, String> sent = new LinkedHashMap<>();
        ApprovalContext shown = context;
        for (Map.Entry<String, String> c : changes.entrySet()) {
            if (!offered.containsKey(c.getKey())) throw new IllegalArgumentException("\"" + c.getKey() + "\" cannot be edited.");
            String text = c.getValue() == null ? "" : c.getValue().strip();
            if (text.isEmpty()) throw new IllegalArgumentException("The edited text is empty.");
            if (text.length() > EDIT_MAX) {
                throw new IllegalArgumentException("The edited text is longer than " + EDIT_MAX + " characters.");
            }
            Object before = args.get(c.getKey());
            String was = before == null ? "" : String.valueOf(before).strip();
            if (text.equals(was)) continue;
            draft.put(c.getKey(), was);
            sent.put(c.getKey(), text);
            args.put(c.getKey(), text);
            shown = shown.withArgument(c.getKey(), text);
        }
        if (sent.isEmpty()) return null;
        return new Edited(write(args), shown.bounded().map(ApprovalContext::toJson).orElse(r.getContext()), draft, sent,
                r.getArguments());
    }

    // Runs an approved call as its person, in its conversation, and tells the agent how it went - or, refused for a
    // limit that lifts (a spent daily quota), holds it for a retry (retryHeld).
    private void run(ToolApprovalRequest r, String note, Edited edited) {
        String arguments = edited == null ? r.getArguments() : edited.arguments();
        String clipped = call(r, arguments);
        Instant now = clock.get();
        boolean succeeded = succeeded(clipped);
        Optional<Instant> until = succeeded ? Optional.empty() : heldUntil(r, clipped, now);
        if (until.isPresent() && withinBound(r, until.get(), 1, now)) {
            hold(r.getId(), clipped, until.get(), 1, now);
            log.info("[Approvals] requestId={} APPROVED but refused for a limit: held until {}", r.getId(), until.get());
            settle(r, "held", "A person approved " + r.getToolName() + " (request " + r.getId() + ")"
                    + (edited == null ? "" : " after EDITING it") + ", but it was refused for a limit: "
                    + ApprovalContexts.errorOf(clipped).orElse("") + "\nNothing was made yet. The server makes it itself after "
                    + stamp(until.get()) + " and tells you then. Nothing for you to do.", false);
            announce(r, ToolApprovalRequest.APPROVED, edited == null ? null : edited.original(), arguments,
                    edited == null ? r.getContext() : edited.context(), note, false, true);
            return;
        }
        requests.findById(r.getId()).ifPresent(row -> {
            row.setResult(clipped);
            if (succeeded) row.setContext(linked(row.getContext(), clipped));
            ran(row, succeeded ? ToolApprovalRequest.RAN_DONE : ToolApprovalRequest.RAN_FAILED, now, 1);
            requests.save(row);
        });
        settle(r, "granted", "A person approved " + r.getToolName() + " (request " + r.getId() + ")"
                + (edited == null ? "" : " after EDITING it") + " and it ran."
                + (note == null ? "" : "\nTheir note: " + note)
                + (succeeded ? lesson(r, edited, true) : "")
                + "\nIts result:\n" + clipped, !succeeded);
        announce(r, ToolApprovalRequest.APPROVED, edited == null ? null : edited.original(), arguments,
                edited == null ? r.getContext() : edited.context(), note, succeeded, false);
    }

    // Makes an approved call as its person, in its conversation: its result, clipped - a failure as a result too.
    private String call(ToolApprovalRequest r, String arguments) {
        String callId = CALL_PREFIX + r.getId();
        String result;
        SearchContext.setUserId(r.getUserId());
        SearchContext.setConversationId(r.getConversationId());
        SpendContext.set(SpendSubject.user(r.getUserId()));
        try {
            preApproved.add(callId);
            ToolResponseMessage response = chat.getObject().callTools(r.getConversationId(),
                    List.of(new ToolCall(callId, "function", r.getToolName(), arguments)), new AtomicBoolean());
            result = response.getResponses().isEmpty() ? "" : response.getResponses().get(0).responseData();
        } catch (RuntimeException e) {
            log.warn("[Approvals] approved call {} ({}) failed: {}", r.getId(), r.getToolName(), e.getMessage());
            result = "{\"success\":false,\"error\":" + quote(String.valueOf(e.getMessage())) + "}";
        } finally {
            preApproved.remove(callId);
            SearchContext.clear();
            SpendContext.clear();
        }
        return AgentInbox.clip(result == null ? "" : result, ToolApprovalRequest.RESULT_MAX);
    }

    private static void ran(ToolApprovalRequest row, String outcome, Instant at, int attempts) {
        row.setRunOutcome(outcome);
        row.setRanAt(at);
        row.setRetryAt(null);
        row.setRunAttempts(attempts);
    }

    // -- Approved writes held for a limit -----------------------------------------------------------

    /** How many times in all a held write is tried - the first run and its retries - before it fails. */
    public static final int RETRY_MAX = 6;
    /** How long after its approval a held write is still tried; a retry due later fails it instead. */
    public static final Duration RETRY_WINDOW = Duration.ofHours(48);
    private static final java.time.format.DateTimeFormatter STAMP = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(java.time.ZoneOffset.UTC);

    static String stamp(Instant at) {
        return STAMP.format(at);
    }

    // When a failed call may be tried again (ApprovalContexts.heldUntil), or empty.
    private Optional<Instant> heldUntil(ToolApprovalRequest r, String result, Instant now) {
        if (contexts == null) {
            return ApprovalContexts.errorOf(result).filter(e -> e.contains(" is limiting how often it is asked."))
                    .map(e -> now.plus(Duration.ofMinutes(30)));
        }
        return contexts.heldUntil(r.getToolName(), result, now);
    }

    // Whether a write tried {@code attempts} times may be tried again at {@code until}: tries left, and within
    // RETRY_WINDOW of its approval.
    private static boolean withinBound(ToolApprovalRequest r, Instant until, int attempts, Instant now) {
        Instant from = r.getDecidedAt() != null ? r.getDecidedAt() : r.getRequestedAt() != null ? r.getRequestedAt() : now;
        return attempts < RETRY_MAX && !until.isAfter(from.plus(RETRY_WINDOW));
    }

    // Keeps an approved write HELD for its retry at {@code until}: still APPROVED, nothing made.
    private void hold(UUID id, String result, Instant until, int attempts, Instant now) {
        requests.findById(id).ifPresent(row -> {
            row.setResult(result);
            row.setRunOutcome(ToolApprovalRequest.RAN_HELD);
            row.setRanAt(now);
            row.setRetryAt(until);
            row.setRunAttempts(attempts);
            requests.save(row);
        });
    }

    /**
     * QUOTA-HELD WRITES MAKE THEMSELVES LATER (2026-11-04, the maintainer: sixteen approved replies "not posted
     * because YouTube's quota is exhausted, they need re-posting after the reset", and nothing re-posted them). An
     * approved write refused for a limit that lifts is HELD (run, ApprovalContexts.heldUntil) and tried again here
     * once due: the oldest due first, ONE AT A TIME, each first asked once more whether it was done another way
     * meanwhile (a comment the person answered himself, or one deleted - then it is withdrawn, never made twice). A
     * second refusal holds it again and holds the rest of that person's due writes of that tool until the same time,
     * so a spent quota is asked once a pass, not once a write. Past {@link #RETRY_MAX} tries or {@link #RETRY_WINDOW}
     * after its approval it FAILS with the reason. Every five minutes; never throws.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 180_000)
    public void retryHeld() {
        Instant now = clock.get();
        List<ToolApprovalRequest> due;
        try {
            due = requests.findTop20ByRunOutcomeAndRetryAtLessThanEqualOrderByRetryAtAsc(ToolApprovalRequest.RAN_HELD, now);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read the held writes due a retry: {}", e.getMessage());
            return;
        }
        Map<String, Instant> stopped = new java.util.HashMap<>();
        for (ToolApprovalRequest r : due) {
            String key = personKey(r.getUserId()) + "\n" + r.getToolName();
            Instant again = stopped.get(key);
            try {
                if (again != null) {
                    postpone(r.getId(), again);
                    continue;
                }
                if (requests.claimRetry(r.getId()) == 0) continue;
                retryOne(r.getId(), now).ifPresent(t -> stopped.put(key, t));
            } catch (RuntimeException e) {
                log.warn("[Approvals] the retry of held request {} failed: {}", r.getId(), e.getMessage());
                postpone(r.getId(), now.plus(Duration.ofMinutes(30)));
            }
        }
    }

    // A held write's next try moved to {@code at}, whether or not a retry claimed it.
    private void postpone(UUID id, Instant at) {
        requests.findById(id).ifPresent(row -> {
            if (!ToolApprovalRequest.RAN_HELD.equals(row.getRunOutcome())
                    && !ToolApprovalRequest.RAN_RETRYING.equals(row.getRunOutcome())) return;
            row.setRunOutcome(ToolApprovalRequest.RAN_HELD);
            row.setRetryAt(at);
            requests.save(row);
        });
    }

    // One held write tried again; when it is refused for a limit again, the time it is held until.
    private Optional<Instant> retryOne(UUID id, Instant now) {
        ToolApprovalRequest r = requests.findById(id).orElse(null);
        if (r == null || !ToolApprovalRequest.APPROVED.equals(r.getStatus())) return Optional.empty();
        if (contexts != null && contexts.settles(r.getToolName())) {
            String reason = contexts.settled(r.getToolName(), waitingOf(List.of(r)), blankToNull(r.getUserId()),
                    APPROVE_CHECK_TIMEOUT).get(r.getId().toString());
            if (reason != null) {
                String why = AgentInbox.clip(reason.strip(), ToolApprovalRequest.NOTE_MAX);
                r.setStatus(ToolApprovalRequest.WITHDRAWN);
                r.setNote(why);
                r.setRunOutcome(null);
                r.setRetryAt(null);
                r.setRanAt(now);
                requests.save(r);
                log.info("[Approvals] held requestId={} WITHDRAWN at its retry: {}", r.getId(), why);
                settle(r, "withdrawn", "The approved " + r.getToolName() + " (request " + r.getId()
                        + "), held for a limit, was not made: " + why + " Nothing was changed.", false);
                return Optional.empty();
            }
        }
        String result = call(r, r.getArguments());
        int attempts = r.getRunAttempts() + 1;
        boolean succeeded = succeeded(result);
        Optional<Instant> until = succeeded ? Optional.empty() : heldUntil(r, result, now);
        if (until.isPresent() && withinBound(r, until.get(), attempts, now)) {
            hold(r.getId(), result, until.get(), attempts, now);
            log.info("[Approvals] held requestId={} refused for a limit again: held until {}", r.getId(), until.get());
            return until;
        }
        String outcome = result;
        if (until.isPresent()) {
            // Past its tries or its window: a failure, with the reason it kept being refused.
            outcome = "{\"success\":false,\"error\":" + quote(ApprovalContexts.errorOf(result).orElse("Refused for a limit.")
                    + " Still refused after " + attempts + " tries over " + RETRY_WINDOW.toHours() + " hours, so it was not made.")
                    + "}";
        }
        String clipped = AgentInbox.clip(outcome, ToolApprovalRequest.RESULT_MAX);
        r.setResult(clipped);
        if (succeeded) r.setContext(linked(r.getContext(), clipped));
        ran(r, succeeded ? ToolApprovalRequest.RAN_DONE : ToolApprovalRequest.RAN_FAILED, now, attempts);
        requests.save(r);
        log.info("[Approvals] held requestId={} {} at its retry", r.getId(), succeeded ? "MADE" : "FAILED");
        settle(r, succeeded ? "granted" : "failed", "The approved " + r.getToolName() + " (request " + r.getId() + "), held for "
                + "a limit, " + (succeeded ? "was made now." : "could not be made.") + "\nIts result:\n" + clipped, !succeeded);
        announce(r, ToolApprovalRequest.APPROVED, r.getDraftArguments(), r.getArguments(), r.getContext(), r.getNote(),
                succeeded, false);
        return until;
    }

    // What the agent is asked to take from a decision on words a person reads - a request whose context
    // has an editable field, a reply: the person's own words when they edited it, else whether it went
    // as written or was refused. Nothing for any other write.
    /**
     * What the agent is told of the lesson: that it is kept, and that nothing needs forwarding - the
     * server records each decision itself (a Constellations project's DecisionLessons, on
     * ApprovalDecidedEvent), so an agent told to "record the lesson" wrote the same thing twice.
     */
    static final String LESSON_KEPT = "It is kept as a lesson for the team; nothing needs forwarding.";

    static String lesson(ToolApprovalRequest r, Edited edited, boolean approved) {
        boolean personal = ApprovalContext.fromJson(r.getContext()).map(c -> !c.editable().isEmpty()).orElse(false);
        if (!personal && edited == null) return "";
        StringBuilder out = new StringBuilder();
        if (edited != null) {
            for (Map.Entry<String, String> e : edited.sent().entrySet()) {
                out.append("\nYour draft (").append(e.getKey()).append("): ").append(quoteText(edited.draft().get(e.getKey())))
                        .append("\nWhat the person sent instead: ").append(quoteText(e.getValue()));
            }
            out.append("\nThis is how the person writes here. " + LESSON_KEPT);
        } else if (approved) {
            out.append("\nThe person sent your draft as written. " + LESSON_KEPT);
        } else {
            out.append("\nThe person refused this draft. " + LESSON_KEPT);
        }
        return out.toString();
    }

    private static String quoteText(String s) {
        return "\"" + (s == null ? "" : AgentInbox.clip(s, 2_000)) + "\"";
    }

    /**
     * The context of a write that ran, its editable field - the words a person approved - linked to where
     * the result says they now are (a result's {@code link}: the posted YouTube reply), so the page links
     * the words rather than printing the result. The context as it was when there is no such link.
     */
    static String linked(String context, String result) {
        Optional<ApprovalContext> c = ApprovalContext.fromJson(context);
        if (c.isEmpty() || c.get().editable().isEmpty() || result == null) return context;
        try {
            com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(result);
            String link = n == null ? "" : n.path("link").asText("");
            if (link.isBlank()) return context;
            String argument = c.get().editable().keySet().iterator().next();
            return c.get().withLink(argument, link.strip()).bounded().map(ApprovalContext::toJson).orElse(context);
        } catch (Exception e) {
            return context;
        }
    }

    // Whether a call's result reads as done: not {"success": false}, and no "error" of its own.
    static boolean succeeded(String result) {
        if (result == null || result.isBlank()) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(result);
            if (n == null || !n.isObject()) return true;
            if (n.has("success") && !n.get("success").asBoolean(true)) return false;
            return !n.hasNonNull("error");
        } catch (Exception e) {
            return true;
        }
    }

    private void announce(ToolApprovalRequest r, String outcome, String draftArguments, String arguments, String context,
                          String note, boolean succeeded, boolean held) {
        announce(r, outcome, draftArguments, arguments, context, note, succeeded, held, automatic(r.getDecidedBy()));
    }

    private void announce(ToolApprovalRequest r, String outcome, String draftArguments, String arguments, String context,
                          String note, boolean succeeded, boolean held, boolean automatic) {
        if (events == null) return;
        try {
            events.publishEvent(new ApprovalDecidedEvent(r.getId().toString(), r.getConversationId(),
                    r.getRootConversationId(), r.getUserId(), r.getToolName(), outcome, draftArguments, arguments, context,
                    note, succeeded, held, automatic));
        } catch (RuntimeException e) {
            log.warn("[Approvals] what request {} taught could not be kept: {}", r.getId(), e.getMessage());
        }
    }

    /**
     * The request is over: the agent hears how, and its report or reply may go once nothing else waits.
     *
     * <p>HOW IT HEARS (2026-11-04, the maintainer's log: seventeen approved replies woke the Reply Writer's long
     * conversation, which spent five model calls - 22k output tokens over 80k cached input each - rewriting notes
     * that then went stale): as mail that STARTS A TURN, unless a module says its agent has nothing to do about it
     * (ChatModule.approvalOutcomeQuiet - a saved Constellations agent's reply) and it owes no report. Then it is a
     * NOTE written into the conversation for its next turn (ChatService.noteForNextTurn), and whatever waited on the
     * request is told it may settle (ApprovalOutcomeNotedEvent). {@code failed}: approved and its call failed for good.
     */
    private void settle(ToolApprovalRequest r, String outcome, String text, boolean failed) {
        if (requests.countByConversationIdAndStatus(r.getConversationId(), ToolApprovalRequest.PENDING) == 0) {
            pending.remove(r.getConversationId());
        }
        String from = outcome + ": " + r.getToolName();
        if (!(quiet(r, failed) && noted(r.getConversationId(), from, text))) {
            inbox.post(r.getConversationId(), AgentInbox.Kind.APPROVAL, null, from, text);
        }
        if (record != null) {
            record.write(r.getRootConversationId(), r.getConversationId(),
                    tree.node(r.getConversationId()).map(AgentNode::getId).orElse(null),
                    ai.proxyma.core.chat.model.AgentEvent.APPROVAL_DECIDED, outcome.toUpperCase(Locale.ROOT), null,
                    r.getToolName());
        }
    }

    // Whether this outcome is a note rather than a turn: a module says so, and no report waits on it.
    private boolean quiet(ToolApprovalRequest r, boolean failed) {
        try {
            if (tree.node(r.getConversationId()).map(n -> !n.isEnded() && n.isReportOwed()).orElse(false)) return false;
            ChatService c = chat.getIfAvailable();
            return c != null && c.approvalOutcomeQuiet(r.getConversationId(), r.getToolName(), failed);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not tell whether request {}'s outcome needs its agent: {}", r.getId(), e.getMessage());
            return false;
        }
    }

    // Writes the outcome into the conversation for its next turn; false when it could not, so it is posted instead.
    private boolean noted(String conversationId, String from, String text) {
        try {
            ChatService c = chat.getIfAvailable();
            String note = AgentInbox.render(List.of(new AgentInbox.Taken(null, AgentInbox.Kind.APPROVAL, null, from, text,
                    Instant.now())));
            if (c == null || !c.noteForNextTurn(conversationId, note)) return false;
            if (events != null) events.publishEvent(new ai.proxyma.core.chat.event.ApprovalOutcomeNotedEvent(conversationId));
            return true;
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not write an outcome into conversation {}: {}", conversationId, e.getMessage());
            return false;
        }
    }

    // -- Drafts done another way meanwhile ----------------------------------------------------------

    /** How often one person's waiting requests are checked against the world, at most; and each request. */
    public static final Duration RECHECK_EVERY = Duration.ofMinutes(5);
    /** How many of one person's waiting requests one check looks at, newest first. */
    public static final int RECHECK_MAX = 200;
    /** What one check may take in all - nobody waits on it but a scheduled run about to start. */
    static final Duration RECHECK_TIMEOUT = Duration.ofSeconds(30);
    /** What the check just before an approved write may take; past it the write goes as the person decided. */
    static final Duration APPROVE_CHECK_TIMEOUT = Duration.ofSeconds(15);
    // People and requests remembered at most for the throttle - past that the lapsed ones are swept.
    static final int CHECKED_KEPT = 10_000;
    // When each person's requests, and each request, were last checked (recheck); the people being checked now.
    private final ConcurrentHashMap<String, Instant> peopleChecked = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Instant> requestsChecked = new ConcurrentHashMap<>();
    private final Set<String> checking = ConcurrentHashMap.newKeySet();

    /**
     * THE DRAFTS DONE ANOTHER WAY (2026-11-04, the maintainer: "Many comments I already replied but still asked
     * to approve"): a person who answers a comment on YouTube himself leaves the team's draft for it waiting -
     * listYouTubeComments leaves answered comments out, but only for drafts not yet made. So this person's
     * waiting requests of a tool a provider checks (ApprovalContextProvider.settled - the YouTube reply's) are
     * asked about, and one done meanwhile is WITHDRAWN with the provider's reason ("Already answered on
     * YouTube.", "The comment is gone."), its agent NOT woken for it - nothing is left for it to do.
     *
     * <p>In the background, at most once every {@link #RECHECK_EVERY} per person, and only requests not checked
     * in that window; a draft the person is editing is left for the check at approval. Called when a page lists
     * the waiting requests. Never throws.
     */
    public void recheckLater(String userId) {
        if (contexts == null) return;
        String key = personKey(userId);
        if (!due(key) || !checking.add(key)) return;
        try {
            executor.execute(() -> {
                try {
                    recheck(userId);
                } finally {
                    checking.remove(key);
                }
            });
        } catch (RuntimeException e) {
            checking.remove(key);
        }
    }

    /**
     * The same on the calling thread, within {@link #RECHECK_TIMEOUT} a tool - before a scheduled pass of a team,
     * so it starts from what still waits. Throttled as {@link #recheckLater}. How many were withdrawn.
     */
    public int recheckWaiting(String userId) {
        if (contexts == null) return 0;
        String key = personKey(userId);
        if (!due(key) || !checking.add(key)) return 0;
        try {
            return recheck(userId);
        } finally {
            checking.remove(key);
        }
    }

    /**
     * THE SAME CHECK AT ONCE, AFTER A CHANGE THE WORLD DID NOT MAKE (2026-11-05: a project moved to another YouTube
     * channel, and every draft made for the old one has to leave the Waiting list): every one of this person's waiting
     * requests is asked about now, whatever the throttle says, in the background; {@code then} hears how many were
     * withdrawn (-1 when the check could not run). Never throws.
     */
    public void recheckAfterChange(String userId, java.util.function.IntConsumer then) {
        if (contexts == null) {
            if (then != null) then.accept(-1);
            return;
        }
        String key = personKey(userId);
        try {
            executor.execute(() -> {
                int withdrawn = -1;
                try {
                    // A check under way was asked before the change: this one runs after it, never beside it.
                    boolean claimed = checking.add(key);
                    for (int i = 0; i < 300 && !claimed; i++) {
                        Thread.sleep(100);
                        claimed = checking.add(key);
                    }
                    if (claimed) {
                        try {
                            peopleChecked.put(key, clock.get());
                            withdrawn = recheck(userId, true);
                        } finally {
                            checking.remove(key);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    log.warn("[Approvals] could not check the waiting requests after a change: {}", e.getMessage());
                } finally {
                    if (then != null) {
                        try {
                            then.accept(withdrawn);
                        } catch (RuntimeException e) {
                            log.warn("[Approvals] could not report the check after a change: {}", e.getMessage());
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            if (then != null) then.accept(-1);
        }
    }

    // Whether this person's requests are due a check, claiming the window when they are.
    private boolean due(String key) {
        Instant now = clock.get();
        Instant last = peopleChecked.get(key);
        if (last != null && last.plus(RECHECK_EVERY).isAfter(now)) return false;
        if (peopleChecked.size() >= CHECKED_KEPT) peopleChecked.entrySet().removeIf(e -> !e.getValue().plus(RECHECK_EVERY).isAfter(now));
        return last == null ? peopleChecked.putIfAbsent(key, now) == null : peopleChecked.replace(key, last, now);
    }

    private int recheck(String userId) {
        return recheck(userId, false);
    }

    // {@code all}: every waiting request, those checked a moment ago too (recheckAfterChange).
    private int recheck(String userId, boolean all) {
        String user = blankToNull(userId);
        Instant now = clock.get();
        List<ToolApprovalRequest> rows;
        try {
            PageRequest p = PageRequest.of(0, RECHECK_MAX, order(Show.PENDING));
            List<String> waiting = List.of(ToolApprovalRequest.PENDING);
            rows = (user == null ? requests.findByUserIdIsNullAndStatusIn(waiting, p)
                    : requests.findByUserIdAndStatusIn(user, waiting, p)).getContent();
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read the requests waiting to check them: {}", e.getMessage());
            return 0;
        }
        if (requestsChecked.size() >= CHECKED_KEPT) {
            requestsChecked.entrySet().removeIf(e -> !e.getValue().plus(RECHECK_EVERY).isAfter(now));
        }
        Map<String, List<ToolApprovalRequest>> byTool = new LinkedHashMap<>();
        List<ToolApprovalRequest> incomplete = new java.util.ArrayList<>();
        Map<String, Set<String>> required = new java.util.HashMap<>();
        for (ToolApprovalRequest r : rows) {
            Instant last = requestsChecked.get(r.getId());
            if (!all && last != null && last.plus(RECHECK_EVERY).isAfter(now)) continue;
            if (beingEdited(r.getId())) continue;
            if (lacksFields(r, required)) incomplete.add(r);
            if (!contexts.settles(r.getToolName())) continue;
            byTool.computeIfAbsent(r.getToolName(), t -> new java.util.ArrayList<>()).add(r);
        }
        int withdrawn = 0;
        Set<UUID> gone = new java.util.HashSet<>();
        for (Map.Entry<String, List<ToolApprovalRequest>> e : byTool.entrySet()) {
            e.getValue().forEach(r -> requestsChecked.put(r.getId(), now));
            Map<String, String> done = contexts.settled(e.getKey(), waitingOf(e.getValue()), user, RECHECK_TIMEOUT);
            for (ToolApprovalRequest r : e.getValue()) {
                String reason = done.get(r.getId().toString());
                // Edited since it was read: left for the check at approval.
                if (reason != null && !beingEdited(r.getId()) && withdrawDone(r, reason)) {
                    withdrawn++;
                    gone.add(r.getId());
                }
            }
        }
        if (withdrawn > 0) log.info("[Approvals] {} waiting request(s) were done another way and withdrawn", withdrawn);
        incomplete.removeIf(r -> gone.contains(r.getId()));
        return withdrawn + completeOrWithdraw(incomplete, now);
    }

    // -- A card that lacks a field it must carry ------------------------------------------------------------

    /** How many waiting cards lacking a field one check looks up again, at most. */
    static final int INCOMPLETE_MAX = 20;

    // Whether a waiting request's card lacks a field its tool requires (ApprovalContexts.missing). {@code required}
    // remembers each tool's answer for one check.
    private boolean lacksFields(ToolApprovalRequest r, Map<String, Set<String>> required) {
        Set<String> needs = required.computeIfAbsent(r.getToolName(), contexts::requiredFields);
        if (needs.isEmpty()) return false;
        return !ApprovalContexts.missing(needs, ApprovalContext.fromJson(r.getContext()).orElse(null)).isEmpty();
    }

    /**
     * THE WAITING CARDS THAT LACK A FIELD (2026-11-05, the maintainer: "Some requests are lack information. The cards
     * must always have all the fields."): drafts kept before a card had to be whole - with no video, a video named by
     * its id, or nothing but the comment's id - are looked up again, one after another, each within
     * {@link #FILL_TIMEOUT} and all within {@link #RECHECK_TIMEOUT}, at most {@link #INCOMPLETE_MAX} a check. A card
     * made whole replaces the old; one the service says DEFINITELY cannot be (the comment or the video is not there
     * to read) is WITHDRAWN with the provider's reason, its agent told once; one that failed for now (a spent quota,
     * an outage) stays as it is for the next check. How many were withdrawn.
     */
    private int completeOrWithdraw(List<ToolApprovalRequest> incomplete, Instant now) {
        int withdrawn = 0;
        long deadline = System.nanoTime() + RECHECK_TIMEOUT.toNanos();
        int asked = 0;
        for (ToolApprovalRequest r : incomplete) {
            long left = deadline - System.nanoTime();
            if (asked >= INCOMPLETE_MAX || left <= 0) break;
            if (!filling.add(r.getId())) continue;
            asked++;
            requestsChecked.put(r.getId(), now);
            try {
                Duration limit = Duration.ofNanos(Math.min(left, FILL_TIMEOUT.toNanos()));
                ApprovalContexts.Prepared p = contexts.complete(r.getToolName(), r.getArguments(), r.getConversationId(),
                        blankToNull(r.getUserId()), limit);
                if (!p.refused() && p.context() != null) {
                    String json = p.context().toJson();
                    if (json != null && requests.completeContext(r.getId(), json) == 1) {
                        log.info("[Approvals] requestId={} card made whole", r.getId());
                    }
                } else if (p.definite() && !beingEdited(r.getId()) && withdrawIncomplete(r, p)) {
                    withdrawn++;
                }
            } catch (RuntimeException e) {
                log.warn("[Approvals] could not look up request {} again: {}", r.getId(), e.getMessage());
            } finally {
                filling.remove(r.getId());
            }
        }
        if (withdrawn > 0) log.info("[Approvals] {} waiting request(s) lacked details that cannot be read and were withdrawn", withdrawn);
        return withdrawn;
    }

    // Withdraws a waiting request whose card cannot be made whole: Decided, with the provider's reason, and the agent
    // that asked told why - once, as the withdrawal happens once.
    private boolean withdrawIncomplete(ToolApprovalRequest r, ApprovalContexts.Prepared p) {
        String why = AgentInbox.clip(p.reason(), ToolApprovalRequest.NOTE_MAX);
        if (requests.decide(r.getId(), ToolApprovalRequest.WITHDRAWN, Instant.now(), null, why) == 0) return false;
        leases.remove(r.getId());
        requestsChecked.remove(r.getId());
        log.info("[Approvals] requestId={} WITHDRAWN: {} conversationId={}", r.getId(), why, r.getConversationId());
        retired(r, "withdrawn", "Your " + r.getToolName() + " (request " + r.getId() + ") was WITHDRAWN: " + why + " "
                + p.refusal() + " It will not be made.");
        return true;
    }

    private static List<ai.proxyma.core.chat.approval.ApprovalContextProvider.Waiting> waitingOf(List<ToolApprovalRequest> rows) {
        return rows.stream().map(r -> new ai.proxyma.core.chat.approval.ApprovalContextProvider.Waiting(
                r.getId().toString(), r.getConversationId(), arguments(r.getArguments()))).toList();
    }

    // Withdraws a request done another way: Decided, with the reason; nobody is told, nothing waits on it.
    private boolean withdrawDone(ToolApprovalRequest r, String reason) {
        String why = AgentInbox.clip(reason.strip(), ToolApprovalRequest.NOTE_MAX);
        if (requests.decide(r.getId(), ToolApprovalRequest.WITHDRAWN, Instant.now(), null, why) == 0) return false;
        leases.remove(r.getId());
        requestsChecked.remove(r.getId());
        log.info("[Approvals] requestId={} WITHDRAWN: {} conversationId={}", r.getId(), why, r.getConversationId());
        retired(r, "withdrawn", null);
        return true;
    }

    private static String personKey(String userId) {
        String user = blankToNull(userId);
        return user == null ? "" : user;
    }

    /** What a person's decision on several requests came to; {@code withdrawn}: done another way, so not made. */
    public record Decided(int decided, int skipped, int withdrawn, List<String> reasons) {}

    /**
     * Decides these requests (each checked by the caller to be the person's) as {@link #decide} does - and, for
     * an APPROVAL, first asks once more whether each was done another way meanwhile: a YouTube comment the channel
     * answered since, one deleted (2026-11-04). Such a request is not made: it is withdrawn with the reason, a
     * draft the person is editing included, and counted apart. One check for them all, batched by the provider,
     * within {@link #APPROVE_CHECK_TIMEOUT}; a check that cannot answer leaves the decision as it was.
     */
    public Decided decideAll(List<ToolApprovalRequest> rs, Decision decision, String note, String decidedBy,
                             Map<String, Map<String, String>> edits) {
        Map<String, String> done = decision == Decision.APPROVE ? doneBeforeApproval(rs) : Map.of();
        int decided = 0, skipped = 0, withdrawn = 0;
        java.util.LinkedHashSet<String> reasons = new java.util.LinkedHashSet<>();
        for (ToolApprovalRequest r : rs) {
            String reason = done.get(r.getId().toString());
            if (reason != null) {
                // A draft replaced while the person edited it: the one waiting in its place is the one withdrawn.
                ToolApprovalRequest target = r.isPending() ? r : successor(r).orElse(null);
                if (target != null && withdrawDone(target, reason)) {
                    withdrawn++;
                    reasons.add(reason.strip());
                    continue;
                }
            }
            Map<String, String> edit = edits == null ? Map.of() : edits.getOrDefault(r.getId().toString(), Map.of());
            if (decide(r, decision, note, decidedBy, edit)) decided++;
            else skipped++;
        }
        return new Decided(decided, skipped, withdrawn, List.copyOf(reasons));
    }

    // The requests among these done another way: id to the reason. Grouped by person and tool, one check each.
    private Map<String, String> doneBeforeApproval(List<ToolApprovalRequest> rs) {
        if (contexts == null || rs == null || rs.isEmpty()) return Map.of();
        Map<String, List<ToolApprovalRequest>> groups = new LinkedHashMap<>();
        for (ToolApprovalRequest r : rs) {
            if (!contexts.settles(r.getToolName())) continue;
            groups.computeIfAbsent(personKey(r.getUserId()) + "\n" + r.getToolName(), k -> new java.util.ArrayList<>()).add(r);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (List<ToolApprovalRequest> group : groups.values()) {
            ToolApprovalRequest first = group.get(0);
            out.putAll(contexts.settled(first.getToolName(), waitingOf(group), blankToNull(first.getUserId()),
                    APPROVE_CHECK_TIMEOUT));
        }
        return out;
    }

    // -- What the world says, for a module ------------------------------------------------------------

    /**
     * Of these subjects, those with an APPROVED write of this person whose call went {@code outcome} (DONE, FAILED) - an
     * approved one still being made is in neither (2026-11-11 review). Empty on a failure.
     */
    public java.util.Set<String> subjectsRan(String userId, java.util.Collection<String> subjects, String outcome) {
        if (subjects == null || subjects.isEmpty() || outcome == null) return java.util.Set.of();
        try {
            String user = blankToNull(userId);
            List<String> found = user == null ? requests.subjectsRanForNobody(subjects, outcome)
                    : requests.subjectsRan(subjects, user, outcome);
            return found == null ? java.util.Set.of() : java.util.Set.copyOf(found);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read which subjects were made: {}", e.getMessage());
            return java.util.Set.of();
        }
    }

    /**
     * Of these subjects (ApprovalContextProvider.subjectOf - "youtube-comment:<id>"), those with a request of this
     * person in {@code status} - APPROVED, WITHDRAWN. One query a status; empty on a failure.
     */
    public java.util.Set<String> subjectsIn(String userId, java.util.Collection<String> subjects, String status) {
        if (subjects == null || subjects.isEmpty() || status == null) return java.util.Set.of();
        try {
            String user = blankToNull(userId);
            List<String> found = user == null ? requests.subjectsWaitingForNobody(subjects, status)
                    : requests.subjectsWaiting(subjects, user, status);
            return found == null ? java.util.Set.of() : java.util.Set.copyOf(found);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read which subjects are {}: {}", status, e.getMessage());
            return java.util.Set.of();
        }
    }

    /**
     * Of these writes of one tool - made up by a module, not kept here - those done another way (a YouTube
     * comment the channel answered, or one gone): id to the reason, as {@link #recheckLater} asks of waiting
     * requests. Bounded by {@link #RECHECK_TIMEOUT}; empty when nothing checks the tool or the check fails.
     */
    public Map<String, String> doneElsewhere(String toolName,
                                             List<ai.proxyma.core.chat.approval.ApprovalContextProvider.Waiting> items,
                                             String userId) {
        if (contexts == null || items == null || items.isEmpty() || !contexts.settles(toolName)) return Map.of();
        return contexts.settled(toolName, items, blankToNull(userId), RECHECK_TIMEOUT);
    }

    // -- The live figures ---------------------------------------------------------------------------------

    /** How far back "lately" reaches in the figures: what was posted, failed or withdrawn in the last day. */
    public static final Duration FIGURES_WINDOW = Duration.ofHours(24);
    /** How many failures and withdrawals the reasons are read from, newest first. */
    static final int REASONS_READ = 60;
    /** How many reasons each kind names, the commonest first. */
    public static final int REASONS_SHOWN = 3;
    /** How long a reason is, at most, as the figures carry it. */
    static final int REASON_MAX = 72;

    /**
     * ONE TOOL'S APPROVALS AS THEY STAND (2026-11-04, the maintainer: "Status seems outdated between agents and actual
     * status" - a lead told the person "37 items waiting", "16 approved drafts not posted", counted from its team's own
     * notes): {@code waiting} now (of which {@code beingEdited} by the person), {@code running} approved and being made
     * now, {@code held} approved and refused for a limit, to be retried from {@code nextRetry}; and in the last
     * {@link #FIGURES_WINDOW}: {@code posted}, {@code failed}, {@code withdrawn} (done another way), {@code refused}
     * (denied) and {@code expired}, with the commonest reasons of the failures and withdrawals.
     */
    public record Figures(String toolName, long waiting, long beingEdited, long running, long held, Instant nextRetry,
                          long posted, long failed, long withdrawn, long refused, long expired,
                          Map<String, Long> failures, Map<String, Long> withdrawals) {}

    /**
     * The live figures of one person's requests in these agent trees (a Constellations project's), by tool, the tool
     * with the most first. A fixed few queries; empty on a failure or with no trees.
     */
    public List<Figures> figures(Collection<String> rootConversationIds, String userId) {
        if (rootConversationIds == null || rootConversationIds.isEmpty()) return List.of();
        Instant now = clock.get();
        Instant since = now.minus(FIGURES_WINDOW);
        String user = blankToNull(userId);
        List<Object[]> rows;
        List<Object[]> why;
        try {
            rows = user == null ? requests.figuresForNobody(rootConversationIds, since)
                    : requests.figures(rootConversationIds, user, since);
            PageRequest p = PageRequest.of(0, REASONS_READ);
            why = user == null ? requests.reasonsForNobody(rootConversationIds, since, p)
                    : requests.reasons(rootConversationIds, user, since, p);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not count the approvals of {} tree(s): {}", rootConversationIds.size(), e.getMessage());
            return List.of();
        }
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, Instant> next = new java.util.HashMap<>();
        for (Object[] row : rows) {
            String tool = String.valueOf(row[0]);
            String status = String.valueOf(row[1]);
            String ran = row[2] == null ? null : String.valueOf(row[2]);
            long n = row[3] instanceof Number num ? num.longValue() : 0;
            long[] c = counts.computeIfAbsent(tool, t -> new long[8]);
            switch (status) {
                case ToolApprovalRequest.PENDING -> c[0] += n;
                case ToolApprovalRequest.APPROVED -> {
                    if (ToolApprovalRequest.RAN_DONE.equals(ran)) c[3] += n;
                    else if (ToolApprovalRequest.RAN_FAILED.equals(ran)) c[4] += n;
                    else if (ToolApprovalRequest.RAN_HELD.equals(ran) || ToolApprovalRequest.RAN_RETRYING.equals(ran)) {
                        c[2] += n;
                        if (row[4] instanceof Instant at) next.merge(tool, at, (a, b) -> a.isBefore(b) ? a : b);
                    } else {
                        c[1] += n;
                    }
                }
                case ToolApprovalRequest.WITHDRAWN -> c[5] += n;
                case ToolApprovalRequest.DENIED -> c[6] += n;
                case ToolApprovalRequest.EXPIRED -> c[7] += n;
                default -> { }
            }
        }
        Map<String, Map<String, Long>> failures = new java.util.HashMap<>();
        Map<String, Map<String, Long>> withdrawals = new java.util.HashMap<>();
        for (Object[] row : why) {
            String tool = String.valueOf(row[0]);
            boolean withdrawn = ToolApprovalRequest.WITHDRAWN.equals(String.valueOf(row[1]));
            String reason = withdrawn ? (row[2] == null ? null : String.valueOf(row[2]))
                    : ApprovalContexts.errorOf(row[3] == null ? null : String.valueOf(row[3])).orElse(null);
            if (reason == null || reason.isBlank()) reason = withdrawn ? "Withdrawn by an agent." : "No reason given.";
            reason = oneLine(reason, REASON_MAX);
            (withdrawn ? withdrawals : failures).computeIfAbsent(tool, t -> new java.util.HashMap<>()).merge(reason, 1L, Long::sum);
        }
        Map<String, Long> editing = editingByTool(rootConversationIds, user);
        List<Figures> out = new java.util.ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            long[] c = e.getValue();
            out.add(new Figures(e.getKey(), c[0], Math.min(c[0], editing.getOrDefault(e.getKey(), 0L)), c[1], c[2],
                    next.get(e.getKey()), c[3], c[4], c[5], c[6], c[7], commonest(failures.get(e.getKey())),
                    commonest(withdrawals.get(e.getKey()))));
        }
        out.sort(java.util.Comparator.comparingLong((Figures f) -> -(f.waiting() + f.held() + f.posted() + f.failed()
                + f.withdrawn() + f.refused() + f.running() + f.expired())).thenComparing(Figures::toolName));
        return out;
    }

    // The waiting requests of these trees the person is editing now, by tool: the holds are few, read each.
    private Map<String, Long> editingByTool(Collection<String> roots, String user) {
        Map<String, Long> out = new java.util.HashMap<>();
        for (UUID id : List.copyOf(leases.keySet())) {
            if (!beingEdited(id)) continue;
            try {
                requests.findById(id).filter(ToolApprovalRequest::isPending)
                        .filter(r -> roots.contains(r.getRootConversationId()) && java.util.Objects.equals(r.getUserId(), user))
                        .ifPresent(r -> out.merge(r.getToolName(), 1L, Long::sum));
            } catch (RuntimeException e) {
                // Not counted as being edited.
            }
        }
        return out;
    }

    private static Map<String, Long> commonest(Map<String, Long> reasons) {
        if (reasons == null || reasons.isEmpty()) return Map.of();
        Map<String, Long> out = new LinkedHashMap<>();
        reasons.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(REASONS_SHOWN).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static String oneLine(String s, int max) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : Utf16.cut(t, max - 3).stripTrailing() + "...";
    }

    // -- How often a person stepped in -----------------------------------------------------------------

    /** The IN list of one step-in query; more trees are asked in parts. */
    static final int DECIDED_IDS_PER_QUERY = 500;

    /**
     * One decided request as the step-in figures read it (a Constellations project's Overview and team status):
     * {@code automatic} when the server approved it, not a person; {@code noted} when the decision carried a note;
     * {@code withdrawalReason} a WITHDRAWN request's reason (null otherwise); {@code draftArguments} and
     * {@code arguments} the agent's draft and what was sent, both null unless the person edited it; {@code subject}
     * what it was about, when it says.
     */
    public record DecidedRow(String toolName, String status, boolean automatic, Instant decidedAt,
                             String rootConversationId, boolean noted, String withdrawalReason,
                             String draftArguments, String arguments, String subject) {

        /** A row with no subject read. */
        public DecidedRow(String toolName, String status, boolean automatic, Instant decidedAt,
                          String rootConversationId, boolean noted, String withdrawalReason,
                          String draftArguments, String arguments) {
            this(toolName, status, automatic, decidedAt, rootConversationId, noted, withdrawalReason, draftArguments,
                    arguments, null);
        }

        public boolean edited() {
            return draftArguments != null;
        }
    }

    /**
     * The requests of these agent trees approved, denied or withdrawn since {@code since}, newest first, at most
     * {@code max} - every person's. Asked in parts of {@link #DECIDED_IDS_PER_QUERY} trees, the newest {@code max}
     * kept. Empty with no trees; throws nothing - empty on a failure.
     */
    public List<DecidedRow> decidedSince(Collection<String> rootConversationIds, Instant since, int max) {
        if (rootConversationIds == null || rootConversationIds.isEmpty() || since == null || max <= 0) return List.of();
        List<String> all = List.copyOf(new java.util.LinkedHashSet<>(rootConversationIds));
        List<DecidedRow> out = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < all.size(); i += DECIDED_IDS_PER_QUERY) {
                List<String> part = all.subList(i, Math.min(all.size(), i + DECIDED_IDS_PER_QUERY));
                for (Object[] row : requests.decidedSince(part, since, PageRequest.of(0, max))) {
                    String decidedBy = row[2] == null ? null : String.valueOf(row[2]);
                    out.add(new DecidedRow(String.valueOf(row[0]), String.valueOf(row[1]), automatic(decidedBy),
                            row[3] instanceof Instant at ? at : null, String.valueOf(row[4]),
                            row[5] instanceof Number n && n.intValue() != 0, text(row[6]), text(row[7]), text(row[8]),
                            row.length > 9 ? text(row[9]) : null));
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read the decisions of {} tree(s): {}", all.size(), e.getMessage());
            return List.of();
        }
        if (all.size() <= DECIDED_IDS_PER_QUERY) return out;
        out.sort(java.util.Comparator.comparing(DecidedRow::decidedAt, java.util.Comparator.nullsLast(
                java.util.Comparator.<Instant>reverseOrder())));
        return out.size() <= max ? out : List.copyOf(out.subList(0, max));
    }

    // A text column as read: a String, or a Clob on a database that hands one back.
    private static String text(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Clob clob) {
            try {
                return clob.getSubString(1, (int) Math.min(Integer.MAX_VALUE, clob.length()));
            } catch (java.sql.SQLException e) {
                return null;
            }
        }
        return String.valueOf(value);
    }

    // -- Housekeeping -----------------------------------------------------------------------------

    /** A request nobody decided for {@link #EXPIRY} expires, and its agent is told. Hourly. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void expire() {
        try {
            for (ToolApprovalRequest r : requests.findByStatusAndRequestedAtBefore(ToolApprovalRequest.PENDING,
                    Instant.now().minus(EXPIRY))) {
                if (requests.decide(r.getId(), ToolApprovalRequest.EXPIRED, Instant.now(), null, null) == 1) {
                    leases.remove(r.getId());
                    settle(r, "expired", "Nobody decided on " + r.getToolName() + " (request " + r.getId()
                            + ") within " + EXPIRY.toDays() + " days, so it was not made. Nothing was changed.", false);
                    announce(r, ToolApprovalRequest.EXPIRED, null, r.getArguments(), r.getContext(), null, false, false);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not expire old requests: {}", e.getMessage());
        }
    }

    @EventListener
    public void onConversationsDeleted(ConversationsDeletedEvent event) {
        if (event.conversationIds().isEmpty()) return;
        try {
            requests.deleteByConversationIdIn(event.conversationIds());
            event.conversationIds().forEach(pending::remove);
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not remove the requests of deleted conversations: {}", e.getMessage());
        }
    }

    // -- Helpers ------------------------------------------------------------------------------------

    /** Reads a decision word: approve or deny, any case. */
    public static Optional<Decision> parseDecision(String raw) {
        String d = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        return switch (d) {
            case "APPROVE", "APPROVED", "ALLOW" -> Optional.of(Decision.APPROVE);
            case "DENY", "DENIED", "REJECT" -> Optional.of(Decision.DENY);
            default -> Optional.empty();
        };
    }

    private static Collection<String> statuses(Show show) {
        return switch (show == null ? Show.ALL : show) {
            case PENDING -> List.of(ToolApprovalRequest.PENDING);
            case DECIDED -> List.of(ToolApprovalRequest.APPROVED, ToolApprovalRequest.DENIED, ToolApprovalRequest.EXPIRED,
                    ToolApprovalRequest.REPLACED, ToolApprovalRequest.WITHDRAWN);
            case ALL -> List.of(ToolApprovalRequest.PENDING, ToolApprovalRequest.APPROVED, ToolApprovalRequest.DENIED,
                    ToolApprovalRequest.EXPIRED, ToolApprovalRequest.REPLACED, ToolApprovalRequest.WITHDRAWN);
        };
    }

    private static int clampSize(int size) {
        return size <= 0 ? 20 : Math.min(size, PAGE_MAX);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"success\":false,\"status\":\"WAITING_FOR_APPROVAL\"}";
        }
    }

    private static String quote(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
