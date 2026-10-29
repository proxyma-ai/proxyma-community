package ai.proxyma.core.chat.agents;

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

    /** One request as a page shows it. */
    public record View(String id, String conversationId, String rootConversationId, String agentTitle, String toolName,
                       String arguments, String status, Instant requestedAt, Instant decidedAt, String note, String result) {
        static View of(ToolApprovalRequest r) {
            return new View(r.getId().toString(), r.getConversationId(), r.getRootConversationId(), r.getAgentTitle(),
                    r.getToolName(), r.getArguments(), r.getStatus(), r.getRequestedAt(), r.getDecidedAt(), r.getNote(),
                    r.getResult());
        }
    }

    private final ToolApprovalRequestRepository requests;
    private final AgentInbox inbox;
    private final AgentTree tree;
    private final ObjectProvider<ChatService> chat;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> preApproved = ConcurrentHashMap.newKeySet();
    private Executor executor = BlockingWork.executor();
    private AgentEvents record;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRecord(AgentEvents record) {
        this.record = record;
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

    @EventListener(ApplicationReadyEvent.class)
    public void loadPending() {
        try {
            pending.addAll(requests.conversationsWithPending());
        } catch (RuntimeException e) {
            log.warn("[Approvals] could not read the requests waiting from before the restart: {}", e.getMessage());
        }
    }

    // -- The turn's side --------------------------------------------------------------------------

    /**
     * Keeps a write for a person to decide and returns what the agent reads instead of its result.
     * Called by ChatService where it would otherwise wait for an answer.
     */
    public String park(String conversationId, String userId, ToolCall call) {
        ToolApprovalRequest r = new ToolApprovalRequest();
        r.setConversationId(conversationId);
        r.setRootConversationId(tree.rootOf(conversationId));
        r.setUserId(blankToNull(userId));
        r.setToolName(call.name());
        r.setArguments(AgentInbox.clip(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments(),
                ToolApprovalRequest.ARGUMENTS_MAX));
        r.setAgentTitle(tree.node(conversationId).map(AgentNode::getTitle).orElse(null));
        ToolApprovalRequest saved = requests.save(r);
        pending.add(conversationId);
        if (record != null) {
            record.write(saved.getRootConversationId(), conversationId,
                    tree.node(conversationId).map(AgentNode::getId).orElse(null),
                    ai.proxyma.core.chat.model.AgentEvent.APPROVAL_ASKED, ToolApprovalRequest.PENDING, null, call.name());
        }
        log.info("[Approvals] write '{}' kept for a decision requestId={} conversationId={}", call.name(), saved.getId(),
                conversationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("status", "WAITING_FOR_APPROVAL");
        out.put("requestId", saved.getId().toString());
        out.put("note", "Nothing was changed yet: nobody is watching this conversation, so the write waits in the "
                + "approvals inbox for a person to decide. The decision - and the result, if it is approved and made - "
                + "reaches you as a message beginning \"[Approval\". Do not call it again. Go on with the rest of "
                + "your work, and say in your answer what is waiting for approval.");
        return write(out);
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
    public Page<View> forUser(String userId, boolean pendingOnly, int page, int size) {
        PageRequest p = PageRequest.of(Math.max(0, page), clampSize(size));
        Collection<String> statuses = statuses(pendingOnly);
        Page<ToolApprovalRequest> rows = blankToNull(userId) == null
                ? requests.findByUserIdIsNullAndStatusInOrderByRequestedAtDesc(statuses, p)
                : requests.findByUserIdAndStatusInOrderByRequestedAtDesc(userId, statuses, p);
        return rows.map(View::of);
    }

    /** A page of the requests of these trees - a module's project, a conversation's agents. */
    public Page<View> forTrees(Collection<String> rootConversationIds, boolean pendingOnly, int page, int size) {
        if (rootConversationIds == null || rootConversationIds.isEmpty()) return Page.empty(PageRequest.of(0, clampSize(size)));
        return requests.findByRootConversationIdInAndStatusInOrderByRequestedAtDesc(rootConversationIds,
                statuses(pendingOnly), PageRequest.of(Math.max(0, page), clampSize(size))).map(View::of);
    }

    public long pendingInTrees(Collection<String> rootConversationIds) {
        if (rootConversationIds == null || rootConversationIds.isEmpty()) return 0;
        return requests.countByRootConversationIdInAndStatus(rootConversationIds, ToolApprovalRequest.PENDING);
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
    public boolean decide(ToolApprovalRequest r, Decision decision, String note, String decidedBy) {
        String status = decision == Decision.APPROVE ? ToolApprovalRequest.APPROVED : ToolApprovalRequest.DENIED;
        String cleanNote = note == null || note.isBlank() ? null : AgentInbox.clip(note.strip(), ToolApprovalRequest.NOTE_MAX);
        if (requests.decide(r.getId(), status, Instant.now(), blankToNull(decidedBy), cleanNote) == 0) return false;
        log.info("[Approvals] requestId={} {} conversationId={}", r.getId(), status, r.getConversationId());
        if (decision == Decision.DENY) {
            settle(r, "denied", "A person denied " + r.getToolName() + " (request " + r.getId() + "). "
                    + "Nothing was changed." + (cleanNote == null ? "" : "\nTheir note: " + cleanNote)
                    + "\nDo not try the same write again; carry on without it, or report what you would need.");
            return true;
        }
        executor.execute(() -> run(r, cleanNote));
        return true;
    }

    // Runs an approved call as its person, in its conversation, and tells the agent how it went.
    private void run(ToolApprovalRequest r, String note) {
        String callId = CALL_PREFIX + r.getId();
        String result;
        SearchContext.setUserId(r.getUserId());
        SearchContext.setConversationId(r.getConversationId());
        SpendContext.set(SpendSubject.user(r.getUserId()));
        try {
            preApproved.add(callId);
            ToolResponseMessage response = chat.getObject().callTools(r.getConversationId(),
                    List.of(new ToolCall(callId, "function", r.getToolName(), r.getArguments())), new AtomicBoolean());
            result = response.getResponses().isEmpty() ? "" : response.getResponses().get(0).responseData();
        } catch (RuntimeException e) {
            log.warn("[Approvals] approved call {} ({}) failed: {}", r.getId(), r.getToolName(), e.getMessage());
            result = "{\"success\":false,\"error\":" + quote(String.valueOf(e.getMessage())) + "}";
        } finally {
            preApproved.remove(callId);
            SearchContext.clear();
            SpendContext.clear();
        }
        String clipped = AgentInbox.clip(result == null ? "" : result, ToolApprovalRequest.RESULT_MAX);
        requests.findById(r.getId()).ifPresent(row -> {
            row.setResult(clipped);
            requests.save(row);
        });
        settle(r, "granted", "A person approved " + r.getToolName() + " (request " + r.getId() + ") and it ran."
                + (note == null ? "" : "\nTheir note: " + note) + "\nIts result:\n" + clipped);
    }

    // The request is over: the agent hears how, and its report or reply may go once nothing else waits.
    private void settle(ToolApprovalRequest r, String outcome, String text) {
        if (requests.countByConversationIdAndStatus(r.getConversationId(), ToolApprovalRequest.PENDING) == 0) {
            pending.remove(r.getConversationId());
        }
        inbox.post(r.getConversationId(), AgentInbox.Kind.APPROVAL, null, outcome + ": " + r.getToolName(), text);
        if (record != null) {
            record.write(r.getRootConversationId(), r.getConversationId(),
                    tree.node(r.getConversationId()).map(AgentNode::getId).orElse(null),
                    ai.proxyma.core.chat.model.AgentEvent.APPROVAL_DECIDED, outcome.toUpperCase(Locale.ROOT), null,
                    outcome + ": " + r.getToolName());
        }
    }

    // -- Housekeeping -----------------------------------------------------------------------------

    /** A request nobody decided for {@link #EXPIRY} expires, and its agent is told. Hourly. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void expire() {
        try {
            for (ToolApprovalRequest r : requests.findByStatusAndRequestedAtBefore(ToolApprovalRequest.PENDING,
                    Instant.now().minus(EXPIRY))) {
                if (requests.decide(r.getId(), ToolApprovalRequest.EXPIRED, Instant.now(), null, null) == 1) {
                    settle(r, "expired", "Nobody decided on " + r.getToolName() + " (request " + r.getId()
                            + ") within " + EXPIRY.toDays() + " days, so it was not made. Nothing was changed.");
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

    private static Collection<String> statuses(boolean pendingOnly) {
        return pendingOnly ? List.of(ToolApprovalRequest.PENDING)
                : List.of(ToolApprovalRequest.PENDING, ToolApprovalRequest.APPROVED, ToolApprovalRequest.DENIED,
                ToolApprovalRequest.EXPIRED);
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
