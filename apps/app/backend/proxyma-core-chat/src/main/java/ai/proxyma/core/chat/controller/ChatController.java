package ai.proxyma.core.chat.controller;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.common.conversation.ConversationPaths;
import ai.proxyma.core.chat.domain.ConversationSurface;
import ai.proxyma.core.chat.domain.PermissionMode;

import ai.proxyma.core.chat.dto.ChatMessageResponse;
import ai.proxyma.core.chat.dto.ChatRequest;
import ai.proxyma.core.chat.dto.ConversationResponse;
import ai.proxyma.core.chat.dto.ConversationDetail;
import ai.proxyma.core.chat.event.ChatEvent;
import ai.proxyma.core.chat.exception.ChatQuotaExceededException;
import ai.proxyma.core.chat.exception.ConversationAccessDeniedException;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.question.QuestionAnswers;
import ai.proxyma.core.chat.quota.ChatQuotaGate;
import ai.proxyma.core.chat.quota.GuestChatQuotaService;
import ai.proxyma.core.chat.service.ChatEventSink;
import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.chat.service.SmartChatMemory;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.system.util.SensitivePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    // Must match ai.proxyma.core.auth.JwtAuthFilter.USER_DISPLAY_NAME_KEY exactly - inlined
    // rather than importing JwtAuthFilter itself so this module has no compile-time dependency
    // on proxyma-core-auth, only a wire-level contract (the request attribute key JwtAuthFilter
    // populates on every authenticated request). Always absent in desktop mode (no auth filter
    // there at all), so the agent simply has no name to use - never a hard failure.
    private static final String USER_DISPLAY_NAME_KEY = "PROXYMA_USER_DISPLAY_NAME";

    private final ChatService chatService;
    private final ChatEventSink chatEventSink;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Optional<ChatQuotaGate> quotaGate;
    // Setter-injected and optional, so a controller built by hand keeps today's behavior.
    private ai.proxyma.core.settings.usage.CostCenterService costCenters;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCostCenters(ai.proxyma.core.settings.usage.CostCenterService costCenters) {
        this.costCenters = costCenters;
    }
    private final GuestChatQuotaService guestQuota;
    private final SmartChatMemory chatMemory;

    public ChatController(ChatService chatService, ChatEventSink chatEventSink, ObjectMapper objectMapper,
                          ApplicationEventPublisher eventPublisher, Optional<ChatQuotaGate> quotaGate,
                          GuestChatQuotaService guestQuota, SmartChatMemory chatMemory) {
        this.chatService = chatService;
        this.chatEventSink = chatEventSink;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.quotaGate = quotaGate;
        this.guestQuota = guestQuota;
        this.chatMemory = chatMemory;
    }

    @PostMapping("/chat")
    public Mono<ResponseEntity<Map<String, String>>> chat(@RequestBody ChatRequest request,
                                                           ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> {
            String uid = userId.isEmpty() ? null : userId;

            // THE ONLY PLACE ANONYMOUS CHAT IS REFUSED. /api/v1/chat/** is permitAll (SecurityConfig
            // orders it deliberately, so a guest can hold a conversation without an account), which
            // leaves this line as the whole of the door: without it PROXYMA_GUEST_CHAT_ENABLED=false
            // left anonymous turns not merely uncounted but UNLIMITED, since checkAllowed() below
            // reports "allowed" whenever the meter is inactive. Refused before the cookie is minted
            // and before any quota is consulted - there is nothing to meter on a deployment that
            // does not accept the turn. Always true on desktop; see isAnonymousChatAllowed().
            if (uid == null && !guestQuota.isAnonymousChatAllowed()) {
                return Mono.just(ResponseEntity.status(401).body(Map.of(
                        "error", "Sign in to start a conversation.")));
            }

            // Where the conversation is held, if not on the chat page (ConversationSurface). Refused
            // here, before a guest cookie is minted or a turn is counted, when it is not a surface.
            String requestedSurface;
            try {
                requestedSurface = ConversationSurface.normalize(request.surface());
            } catch (IllegalArgumentException e) {
                return Mono.just(ResponseEntity.badRequest().body(Map.of("error", e.getMessage())));
            }
            // tools-core-01: the id a client names its conversation with becomes that conversation's
            // folder, so one that could be a path is refused here, before anything is counted or
            // stored. And the request is bounded, since all of it is stored and sent to the model.
            String invalid = invalidRequest(request);
            if (invalid != null) {
                return Mono.just(ResponseEntity.badRequest().body(Map.of("error", invalid)));
            }

            // Only ever consulted for anonymous conversations (see ensureConversation()), but
            // resolved/minted unconditionally so a guest's very first message already gets one.
            // Hoisted above the quota checks because the guest allowance below is counted against
            // this id - a guest arriving with no cookie must be given one and then metered on it,
            // not waved through because they had nothing to meter.
            String guestSessionId = resolveGuestSessionId(exchange, true);

            // Two ceilings, one per kind of caller, and never both. A signed-in user is metered on
            // $ spend per rolling window (RollingWindowChatQuotaGate, ENTERPRISE-gated); an
            // anonymous visitor is metered on turns (GuestChatQuotaService), which is the only
            // meter that works on a deployment that has priced no models. Desktop has neither -
            // no ChatQuotaGate bean at all, and the guest service reports itself inactive.
            // A signed-in user's quota applies only while chat is charged to the user
            // (CostCenterSettings); a super admin who moves chat to the platform lifts it.
            boolean chatIsUsers = costCenters == null || costCenters.chargedToUser("CHAT");
            // A guest's turn is counted by the same call that admits it, so turns sent together at
            // the last free message cannot all read the same count and all pass.
            var rejection = uid != null
                    ? (chatIsUsers ? quotaGate.flatMap(g -> g.checkAllowed(uid)) : java.util.Optional.<ai.proxyma.core.chat.quota.QuotaRejection>empty())
                    : guestQuota.admit(guestSessionId);
            if (rejection.isPresent()) {
                // The chat page's own refusal, before any model call - so never the same entry as
                // the gateway's budget.user-exceeded, which refuses calls made elsewhere.
                var r = rejection.get();
                AuditEntry.of(AuditCategory.LIMITS, "chat-quota.reached")
                        .user(uid)
                        .outcome(AuditEntry.DENIED)
                        .target(r.window())
                        .summary(uid != null
                                ? "A chat message was refused: the user's " + r.window() + " quota is used up"
                                : "A guest's chat message was refused: the guest allowance is used up")
                        .detail("window", r.window())
                        .detail("resetsAt", r.resetsAt() != null ? r.resetsAt().toString() : null)
                        .record();
                return Mono.<ResponseEntity<Map<String, String>>>error(new ChatQuotaExceededException(r));
            }
            // A guest's turn was counted above, on acceptance rather than on completion: a guest
            // allowance is about how much of an open server one browser may use, and a turn that
            // fails on the provider's side has still used it.

            boolean named = request.conversationId() != null && !request.conversationId().isBlank();
            String conversationId = named ? request.conversationId() : UUID.randomUUID().toString();
            String displayName = resolveUserDisplayName(exchange);

            // SEC-42/43: a turn posted into a conversation the caller may not open is refused HERE,
            // before it touches anything of the owner's. It used to be refused only deep inside
            // the turn (ensureConversation), by which time ensureSink had REPLACED the owner's live
            // event sink - cutting off the answer they were watching - and the turn had overwritten
            // their abort flag. And a visitor's conversation was not refused at all: any other
            // visitor, or any signed-in user, could write into it.
            //
            // The refusal looks exactly like a lapsed session, which is the common innocent cause:
            // the caller gets its conversation id back as usual, and its own stream answers
            // SESSION_EXPIRED (see chatEvents), which the client already handles by refreshing.
            //
            // The surface the turn is recorded under is the STORED one once the conversation exists -
            // a later message cannot move it (see ConversationSurface) - and the request's for a
            // conversation this message creates.
            return Mono.fromCallable(() -> named ? chatMemory.findConversation(conversationId) : Optional.<Conversation>empty())
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(stored -> {
                        if (stored.isPresent() && !canAccessConversation(stored.get(), uid, guestSessionId)) {
                            log.warn("[Chat] refused a turn into conversation {} the caller may not open", conversationId);
                        } else {
                            String surface = stored.map(Conversation::getSurface).orElse(requestedSurface);
                            dispatch(request, conversationId, uid, displayName, guestSessionId, surface);
                        }
                        return ResponseEntity.ok(Map.of("conversationId", conversationId));
                    });
        });
    }

    // The longest message a turn accepts - the same as a message typed into a running turn (inject).
    static final int MAX_MESSAGE_CHARS = 100_000;
    // Mentions, attachments and picked skills: far more than a composer can hold, far less than a
    // server should store and prompt with on a stranger's say-so.
    static final int MAX_LIST_ENTRIES = 100;
    static final int MAX_LIST_ENTRY_CHARS = 2_000;

    // Why a chat request is refused, or null when it is not. A visitor can send one, and every field
    // is stored and reaches the model.
    static String invalidRequest(ChatRequest request) {
        if (request.conversationId() != null && !request.conversationId().isBlank()
                && !ConversationPaths.isValidId(request.conversationId())) {
            return "Not a valid conversation id.";
        }
        if (request.message() != null && request.message().length() > MAX_MESSAGE_CHARS) {
            return "The message is longer than " + MAX_MESSAGE_CHARS + " characters.";
        }
        for (List<String> list : java.util.Arrays.asList(request.mentions(), request.attachments(),
                request.attachmentPaths(), request.skillMentions())) {
            if (list == null) continue;
            if (list.size() > MAX_LIST_ENTRIES) return "Too many entries in the request.";
            for (String entry : list) {
                if (entry != null && entry.length() > MAX_LIST_ENTRY_CHARS) return "An entry in the request is too long.";
            }
        }
        return null;
    }

    private void dispatch(ChatRequest request, String conversationId, String uid, String displayName,
                          String guestSessionId, String surface) {
        // A fresh sink per turn - unless something is still publishing into the one there is, which
        // a page is subscribed to: a sub-agent in the background, or a turn still running (this one
        // then waits for it - see SmartChatMemory.lockMemory). See ChatEventSink.startTurn.
        chatEventSink.startTurn(conversationId,
                chatService.hasBackgroundWork(conversationId) || chatService.isActive(conversationId));
        chatService.setPermissionMode(conversationId, PermissionMode.parse(request.permissionMode()));
        chatService.recordTurnStart(conversationId, turnOwner(uid, guestSessionId), surface);

        Mono.fromCallable(() -> chatService.chat(conversationId, request.message(),
                        request.mentions(), attachmentPaths(request, conversationId, uid), uid, displayName,
                        guestSessionId, request.skillMentions(), surface))
                .subscribeOn(ChatService.TURNS)
                .subscribe(
                        result -> {},
                        err -> {
                            String detail = Failures.describe(err);
                            // Full stack trace, not just the message: provider SDK exceptions
                            // carry the status code and response body on the CAUSE, and losing
                            // them left "Failed to generate content" as the only trace of a
                            // reproducible provider rejection.
                            log.error("[Chat] error conversationId={}: {}", conversationId, detail, err);
                            // This endpoint is fire-and-forget (returns 200 before the async chat
                            // work above even runs), so without this the caller never learns the
                            // turn failed. A ConversationAccessDeniedException means the caller's
                            // session lapsed mid-conversation - publish a distinct event type so
                            // the frontend can attempt a silent token refresh instead of just
                            // showing the raw exception text.
                            if (err instanceof ConversationAccessDeniedException) {
                                eventPublisher.publishEvent(new ChatEvent(conversationId,
                                        chatEventSink.nextSequence(conversationId),
                                        ChatEvent.Type.SESSION_EXPIRED.value(), null, null,
                                        "Your session has expired. Please sign in again."));
                            } else {
                                eventPublisher.publishEvent(new ChatEvent(conversationId,
                                        chatEventSink.nextSequence(conversationId),
                                        ChatEvent.Type.ERROR.value(), null, null, detail));
                            }
                        }
                );
    }

    /**
     * How much of the free guest allowance this browser has left - what the sign-in banner reads.
     *
     * <p>Under the permitAll /api/v1/chat/ prefix because the only caller is a visitor with no
     * session: an endpoint that 401ed would be answering nobody. It reads memory and one cookie,
     * and reports {@code enabled: false} where nothing is metered (desktop, or a deployment that
     * has turned the allowance off) so the client can say so rather than invent a number.
     *
     * <p>Does NOT mint a guest cookie - deliberately, matching every other non-POST-/chat call
     * site. A visitor who has never sent a message has used nothing, which this can say without
     * needing to name them first, and minting here would set a cookie on every anonymous page view.
     */
    @GetMapping("/chat/guest-allowance")
    public Mono<ResponseEntity<GuestChatQuotaService.GuestAllowance>> guestAllowance(ServerWebExchange exchange) {
        return Mono.fromSupplier(() ->
                ResponseEntity.ok(guestQuota.allowance(resolveGuestSessionId(exchange, false))));
    }

    @GetMapping(value = "/chat/events/{conversationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatEvents(@PathVariable String conversationId,
                                                     ServerWebExchange exchange) {
        String guestSessionId = resolveGuestSessionId(exchange, false);
        // The lookup is a database read, so it is made off the event loop - every page load and
        // every reconnect of the stream asks it.
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() ->
                        !mayReachPending(conversationId, userId.isEmpty() ? null : userId, guestSessionId))
                .subscribeOn(Schedulers.boundedElastic())).flatMapMany(denied -> {
            if (denied) {
                // SEC-42: the caller may not read this conversation, so it gets ONE event of its own
                // and nothing of the owner's. This used to publish SESSION_EXPIRED into the owner's
                // sink - their own stream told them their session had expired - and then subscribe
                // the caller to that sink anyway, which replays the whole turn: anyone holding a
                // conversation id, a visitor included, watched someone else's answer arrive.
                return Flux.just(formatSseEvent(new ChatEvent(conversationId, 0,
                        ChatEvent.Type.SESSION_EXPIRED.value(), null, null,
                        "Your session has expired. Please sign in again.")));
            }
            // Asked in this order - see ChatService.isBusy.
            boolean busy = chatService.isBusy(conversationId);
            long next = chatEventSink.currentSequence(conversationId);
            Flux<ServerSentEvent<String>> stream = chatEventSink.subscribe(conversationId)
                    .map(this::formatSseEvent)
                    .startWith(openEvent(chatEventSink.boot(), busy, next))
                    .mergeWith(Flux.interval(Duration.ofSeconds(15))
                            .map(i -> ServerSentEvent.<String>builder().comment("keepalive").build()));

            return stream;
        });
    }

    // Paginated - a long-running session can accumulate hundreds of conversations, so the
    // history dialog shows them a page at a time (twenty, numbered - every list does since
    // 2026-10-14), with the total that numbers the pages. The chat page's conversations by default;
    // `surface` lists the ones another page holds (ConversationSurface) instead.
    @GetMapping("/chat/conversations")
    public Mono<ResponseEntity<Map<String, Object>>> listConversations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String surface,
            ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            var result = chatService.listConversationsPaged(uid, ConversationSurface.normalize(surface),
                    conversationPage(page, size));
            List<ConversationResponse> dtos = result.getContent().stream()
                    .map(c -> new ConversationResponse(c.getId(), c.getTitle(),
                            c.getCreatedAt(), c.getUpdatedAt(), c.getMessageCount(), c.isPinned()))
                    .toList();
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("items", dtos);
            body.put("page", result.getNumber());
            body.put("hasMore", result.hasNext());
            body.put("totalItems", result.getTotalElements());
            body.put("totalPages", result.getTotalPages());
            return ResponseEntity.ok(body);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * The page of the history asked for: never before the first, and one to a hundred at a time (the
     * default twenty is what the history dialog asks for). A negative page or size was a 500 from
     * PageRequest; it is the nearest valid one now.
     */
    static org.springframework.data.domain.PageRequest conversationPage(int page, int size) {
        return org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
    }

    @GetMapping("/chat/conversations/{id}")
    public Mono<ResponseEntity<ConversationDetail>> getConversation(@PathVariable String id,
                                                                     ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<ConversationDetail>build();
            ChatService.Transcript transcript = chatService.getTranscript(id);
            ConversationDetail detail = new ConversationDetail(
                    conv.getId(), conv.getTitle(),
                    conv.getCreatedAt(), conv.getUpdatedAt(), transcript.messages(),
                    conv.getInputTokens(), conv.getOutputTokens(),
                    conv.getCacheReadTokens(), conv.getCacheWriteTokens(),
                    conv.getContextTokens(), chatService.getContextTokenLimit(),
                    transcript.eventSeq(), transcript.prompts(), transcript.background());
            return ResponseEntity.ok(detail);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // THE IDS ALONE, of the transcript's last `tail` messages, in order (2026-10-17) - what a page
    // needs once a turn has streamed: the message that started background sub-agents carries every
    // one of their steps, and even its tail was tens of kilobytes. Null where a row stands for no
    // single stored message, as in the transcript.
    @GetMapping("/chat/conversations/{id}/message-ids")
    public Mono<ResponseEntity<List<Long>>> conversationMessageIds(@PathVariable String id,
                                                                   @RequestParam(required = false) Integer tail,
                                                                   ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<List<Long>>build();
            List<Long> ids = tailOf(chatService.getTranscript(id).messages(), tail).stream()
                    .map(ai.proxyma.core.chat.dto.ChatMessageResponse::id)
                    .collect(java.util.stream.Collectors.toList());
            return ResponseEntity.ok(ids);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** The last `tail` items, or all of them when `tail` is absent, not positive or not smaller. */
    static <T> List<T> tailOf(List<T> items, Integer tail) {
        if (tail == null || tail <= 0 || tail >= items.size()) return items;
        return items.subList(items.size() - tail, items.size());
    }

    // The MCP servers the composer's + menu offers: the skills ("MCP: name") this person may read,
    // their descriptions as the system prompt has them. Picking one sends its name as a skill
    // mention, which ChatService loads as the agent's readSkill would.
    @org.springframework.beans.factory.annotation.Value("${proxyma.multi-user:false}")
    private boolean mcpMultiUser;

    @GetMapping("/chat/mcp-servers")
    public Mono<ResponseEntity<List<Map<String, String>>>> mcpServers(ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            // A visitor with no account on a multi-user server has no servers to be offered.
            if (uid == null && mcpMultiUser) return ResponseEntity.ok(List.<Map<String, String>>of());
            List<Map<String, String>> out = chatService.mcpServerSkills(uid).stream()
                    .map(s -> Map.of("name", s.name(), "description", s.description() == null ? "" : s.description()))
                    .toList();
            return ResponseEntity.ok(out);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // WHAT THE CONVERSATION HAS SPENT, and how full its context is - the composer's meter, which
    // refetched the WHOLE transcript after every turn to read these six numbers (2026-10-17). Same
    // ownership check as the conversation itself.
    @GetMapping("/chat/conversations/{id}/usage")
    public Mono<ResponseEntity<Map<String, Object>>> conversationUsage(@PathVariable String id,
                                                                       ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<Map<String, Object>>build();
            Map<String, Object> usage = usageOf(conv, chatService.getContextTokenLimit());
            ai.proxyma.core.chat.service.PromptCacheFields.put(usage, chatService.promptCache(id));
            return ResponseEntity.ok(usage);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    static Map<String, Object> usageOf(Conversation conv, long contextTokenLimit) {
        Map<String, Object> usage = new java.util.LinkedHashMap<>();
        usage.put("inputTokens", conv.getInputTokens());
        usage.put("outputTokens", conv.getOutputTokens());
        usage.put("cacheReadTokens", conv.getCacheReadTokens());
        usage.put("cacheWriteTokens", conv.getCacheWriteTokens());
        usage.put("contextTokens", conv.getContextTokens());
        usage.put("contextTokenLimit", contextTokenLimit);
        return usage;
    }

    // -- The agent tree, alarms (ai.proxyma.core.chat.agents) --------------------------------------

    private ai.proxyma.core.chat.agents.AgentTree agentTree;
    private ai.proxyma.core.chat.agents.AgentAlarms agentAlarms;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAgentTree(ai.proxyma.core.chat.agents.AgentTree agentTree) {
        this.agentTree = agentTree;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAgentAlarms(ai.proxyma.core.chat.agents.AgentAlarms agentAlarms) {
        this.agentAlarms = agentAlarms;
    }

    // Every agent of the tree this conversation is in - its root, every child at every depth, where
    // each stands and what it spent - for the agents map, the panels and the "back to" link of a
    // child's page. Same ownership check as the conversation itself.
    @GetMapping("/chat/conversations/{id}/agent-tree")
    public Mono<ResponseEntity<Map<String, Object>>> agentTree(@PathVariable String id, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            Conversation conv = chatService.getConversation(id);
            if (agentTree == null || !canAccessConversation(conv, uid, resolveGuestSessionId(exchange, false)))
                return ResponseEntity.status(404).<Map<String, Object>>build();
            return ResponseEntity.ok(agentTreeView(id));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Map<String, Object> agentTreeView(String conversationId) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        String rootId = agentTree.rootOf(conversationId);
        out.put("rootConversationId", rootId);
        out.put("maxDepth", agentTree.maxDepth());
        List<Map<String, Object>> agents = new java.util.ArrayList<>();
        Map<String, Conversation> convs = new java.util.HashMap<>();
        List<ai.proxyma.core.chat.model.AgentNode> nodes = agentTree.treeOf(conversationId);
        for (Conversation c : chatMemory.findConversations(nodes.stream()
                .map(ai.proxyma.core.chat.model.AgentNode::getConversationId).toList())) {
            convs.put(c.getId(), c);
        }
        for (ai.proxyma.core.chat.model.AgentNode n : nodes) {
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("agentId", n.getId().toString());
            v.put("conversationId", n.getConversationId());
            v.put("parentConversationId", n.getParentConversationId());
            // Titles and reports the model wrote, crossing to the browser - screened as every other
            // agent text is.
            v.put("title", ai.proxyma.core.system.util.SensitivePaths.scrub(n.getTitle()));
            v.put("status", n.getStatus());
            v.put("lifetime", n.getLifetime());
            v.put("depth", n.getDepth());
            v.put("owesReport", n.isReportOwed());
            v.put("lastReport", n.getLastReport() == null ? null : ai.proxyma.core.system.util.SensitivePaths.scrub(n.getLastReport()));
            v.put("model", n.getModel());
            v.put("createdAt", n.getCreatedAt() == null ? null : n.getCreatedAt().toString());
            v.put("endedAt", n.getEndedAt() == null ? null : n.getEndedAt().toString());
            v.put("working", chatService.isActive(n.getConversationId()));
            Conversation c = convs.get(n.getConversationId());
            v.put("inputTokens", c == null ? 0 : c.getInputTokens());
            v.put("outputTokens", c == null ? 0 : c.getOutputTokens());
            v.put("costUsd", c == null ? 0 : c.getEstimatedCostUsd());
            // Its limits, what checked it, and why the tree stopped it - for the agents map.
            v.put("budgetUsd", n.getBudgetUsd());
            v.put("deadlineAt", n.getDeadlineAt() == null ? null : n.getDeadlineAt().toString());
            v.put("role", n.getRole());
            v.put("verification", n.getVerification() == null ? null
                    : ai.proxyma.core.system.util.SensitivePaths.scrub(n.getVerification()));
            v.put("stopNote", n.getStopNote() == null ? null : ai.proxyma.core.system.util.SensitivePaths.scrub(n.getStopNote()));
            agents.add(v);
            if (n.getConversationId().equals(conversationId)) out.put("self", v);
        }
        out.put("agents", agents);
        return out;
    }

    // The person can do anything to any agent of a tree they own: pause, resume, stop, dismiss.
    @PostMapping("/chat/conversations/{id}/agent-tree/{agentId}/{action}")
    public Mono<ResponseEntity<Map<String, Object>>> controlAgent(@PathVariable String id, @PathVariable String agentId,
                                                                 @PathVariable String action, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            Conversation conv = chatService.getConversation(id);
            if (agentTree == null || !canAccessConversation(conv, uid, resolveGuestSessionId(exchange, false)))
                return ResponseEntity.status(404).<Map<String, Object>>build();
            Map<String, Object> result = agentTree.controlInTree(id, agentId, action);
            return Boolean.TRUE.equals(result.get("success")) ? ResponseEntity.ok(result)
                    : ResponseEntity.badRequest().body(result);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // -- The approvals inbox (ApprovalInbox) ----------------------------------------------------------

    private ai.proxyma.core.chat.agents.ApprovalInbox approvalInbox;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setApprovalInbox(ai.proxyma.core.chat.agents.ApprovalInbox approvalInbox) {
        this.approvalInbox = approvalInbox;
    }

    // The caller's own requests, newest first: waiting only, or every one with its outcome. Nobody
    // signed in on a multi-user server has none.
    @GetMapping("/chat/approvals")
    public Mono<ResponseEntity<Map<String, Object>>> approvals(@RequestParam(defaultValue = "true") boolean pending,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size,
                                                              ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            if (approvalInbox == null || (uid == null && chatMemory.isMultiUser())) {
                return ResponseEntity.ok(approvalPage(org.springframework.data.domain.Page.empty()));
            }
            return ResponseEntity.ok(approvalPage(approvalInbox.forUser(uid, pending, page, size)));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // The requests of every agent of the tree this conversation is in.
    @GetMapping("/chat/conversations/{id}/approvals")
    public Mono<ResponseEntity<Map<String, Object>>> conversationApprovals(@PathVariable String id,
                                                                          @RequestParam(defaultValue = "true") boolean pending,
                                                                          @RequestParam(defaultValue = "0") int page,
                                                                          @RequestParam(defaultValue = "20") int size,
                                                                          ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            Conversation conv = chatService.getConversation(id);
            if (approvalInbox == null || agentTree == null
                    || !canAccessConversation(conv, uid, resolveGuestSessionId(exchange, false)))
                return ResponseEntity.status(404).<Map<String, Object>>build();
            return ResponseEntity.ok(approvalPage(approvalInbox.forTrees(List.of(agentTree.rootOf(id)), pending, page, size)));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Decides up to a hundred requests at once: {ids, decision: APPROVE|DENY, note}. Each is decided
    // only when the caller may answer that conversation's prompts - the person it works for - and only
    // while it still waits; the answer counts what was decided and what was not.
    @PostMapping("/chat/approvals/decide")
    public Mono<ResponseEntity<Map<String, Object>>> decideApprovals(@RequestBody Map<String, Object> body,
                                                                    ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            if (approvalInbox == null) return ResponseEntity.status(404).<Map<String, Object>>build();
            Optional<ai.proxyma.core.chat.agents.ApprovalInbox.Decision> decision =
                    ai.proxyma.core.chat.agents.ApprovalInbox.parseDecision(String.valueOf(body.get("decision")));
            Object rawIds = body.get("ids");
            if (decision.isEmpty() || !(rawIds instanceof List<?> ids) || ids.isEmpty()
                    || ids.size() > ai.proxyma.core.chat.agents.ApprovalInbox.DECIDE_MAX) {
                return ResponseEntity.badRequest().<Map<String, Object>>build();
            }
            Object rawNote = body.get("note");
            String note = rawNote instanceof String s ? s : null;
            String guest = resolveGuestSessionId(exchange, false);
            int decided = 0;
            int skipped = 0;
            for (Object raw : ids) {
                Optional<ai.proxyma.core.chat.model.ToolApprovalRequest> r = approvalInbox.find(String.valueOf(raw));
                if (r.isEmpty() || !r.get().isPending() || !mayReachPending(r.get().getConversationId(), uid, guest)) {
                    skipped++;
                    continue;
                }
                if (approvalInbox.decide(r.get(), decision.get(), note, uid)) decided++;
                else skipped++;
            }
            return ResponseEntity.ok(Map.<String, Object>of("decided", decided, "skipped", skipped));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private static Map<String, Object> approvalPage(org.springframework.data.domain.Page<ai.proxyma.core.chat.agents.ApprovalInbox.View> page) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (ai.proxyma.core.chat.agents.ApprovalInbox.View v : page.getContent()) items.add(approvalView(v));
        out.put("items", items);
        out.put("totalItems", page.getTotalElements());
        out.put("totalPages", page.getTotalPages());
        out.put("page", page.getNumber());
        return out;
    }

    /** One request for a page - what the model wrote, screened at the boundary like every agent text. */
    public static Map<String, Object> approvalView(ai.proxyma.core.chat.agents.ApprovalInbox.View v) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", v.id());
        m.put("conversationId", v.conversationId());
        m.put("rootConversationId", v.rootConversationId());
        m.put("agentTitle", v.agentTitle() == null ? null : ai.proxyma.core.system.util.SensitivePaths.scrub(v.agentTitle()));
        m.put("toolName", v.toolName());
        m.put("arguments", v.arguments() == null ? null : ai.proxyma.core.system.util.SensitivePaths.scrub(v.arguments()));
        m.put("status", v.status());
        m.put("requestedAt", v.requestedAt() == null ? null : v.requestedAt().toString());
        m.put("decidedAt", v.decidedAt() == null ? null : v.decidedAt().toString());
        m.put("note", v.note());
        m.put("result", v.result() == null ? null : ai.proxyma.core.system.util.SensitivePaths.scrub(v.result()));
        return m;
    }

    // The alarms the conversation's agent set, soonest first, each with a Cancel on the page.
    @GetMapping("/chat/conversations/{id}/alarms")
    public Mono<ResponseEntity<List<Map<String, Object>>>> alarms(@PathVariable String id, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            Conversation conv = chatService.getConversation(id);
            if (agentAlarms == null || !canAccessConversation(conv, uid, resolveGuestSessionId(exchange, false)))
                return ResponseEntity.status(404).<List<Map<String, Object>>>build();
            List<Map<String, Object>> out = agentAlarms.pending(id).stream().map(a -> {
                Map<String, Object> v = new java.util.LinkedHashMap<>();
                v.put("alarmId", a.getId().toString());
                v.put("dueAt", a.getDueAt().toString());
                v.put("note", ai.proxyma.core.system.util.SensitivePaths.scrub(a.getNote()));
                return v;
            }).toList();
            return ResponseEntity.ok(out);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @DeleteMapping("/chat/conversations/{id}/alarms/{alarmId}")
    public Mono<ResponseEntity<Void>> cancelAlarm(@PathVariable String id, @PathVariable String alarmId,
                                                  ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            Conversation conv = chatService.getConversation(id);
            if (agentAlarms == null || !canAccessConversation(conv, uid, resolveGuestSessionId(exchange, false)))
                return ResponseEntity.status(404).<Void>build();
            boolean ok = Boolean.TRUE.equals(agentAlarms.cancel(id, alarmId).get("success"));
            return ok ? ResponseEntity.noContent().<Void>build() : ResponseEntity.status(404).<Void>build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // The request's attachments as the server paths the model is given, each resolved by NAME inside
    // the sender's own attachments folder (SmartChatMemory.resolveOwnAttachment). A name that does not
    // resolve is dropped rather than failing the message - the upload may have been removed - and
    // never reaches the model as a path of the client's choosing, which the old `attachmentPaths`
    // field allowed. That field is still read, by file name only, for API clients written against it.
    private List<String> attachmentPaths(ChatRequest request, String conversationId, String uid) {
        Stream<String> names = Stream.concat(
                request.attachments() == null ? Stream.empty() : request.attachments().stream(),
                request.attachmentPaths() == null ? Stream.empty() : request.attachmentPaths().stream()
                        .map(p -> p.substring(Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\')) + 1)));
        return names.distinct()
                .map(name -> chatMemory.resolveOwnAttachment(conversationId, uid, name))
                .flatMap(Optional::stream)
                .map(Path::toString)
                .toList();
    }

    // Where an uploaded attachment is served back - see getAttachment below. One place, because the
    // upload response and the frontend's history chips must build the same URL.
    static String attachmentUrl(String conversationId, String fileName) {
        return "/api/v1/chat/conversations/" + conversationId + "/attachments/"
                + UriUtils.encodePathSegment(fileName, StandardCharsets.UTF_8);
    }

    // An uploaded attachment, for the chat's file preview. Same ownership check as the web media
    // below, and like it no escape hatch for a conversation not yet persisted: before the first
    // message is sent the preview reads the file from the browser, which still holds it.
    // `attachment` disposition so that a direct visit downloads rather than renders - the preview
    // fetches it as a blob, and an uploaded .html or .svg must never execute on this origin.
    @GetMapping("/chat/conversations/{id}/attachments/{fileName}")
    public Mono<ResponseEntity<Resource>> getAttachment(@PathVariable String id,
                                                        @PathVariable String fileName,
                                                        ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Resource>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<Resource>build();
            Optional<Path> file = chatMemory.resolveStoredAttachment(id, fileName);
            if (file.isEmpty())
                return ResponseEntity.status(404).<Resource>build();
            return storedFile(file.get(), fileName,
                    MediaTypeFactory.getMediaType(fileName).orElse(MediaType.APPLICATION_OCTET_STREAM), false);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // How every stored file of a conversation is served - an upload, a runPython output, fetched
    // web media. Never as a document of this origin: `sandbox` withholds script and the origin from
    // anything a browser renders (an .svg or .html saved from somebody's web page), nosniff keeps a
    // type from being guessed into one that runs, and only a picture, a video or a sound - when
    // `inlineMedia` - is shown in place; anything else is a download. The media endpoint served a
    // fetched .svg inline with none of this, so opening its URL ran the page's script here.
    static ResponseEntity<Resource> storedFile(Path file, String fileName, MediaType type, boolean inlineMedia) {
        boolean inline = inlineMedia && isPlainMedia(type);
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }

    // A raster image, a video or a sound - what a browser shows without running anything. SVG is an
    // image type that is also a document with script.
    static boolean isPlainMedia(MediaType type) {
        if (type == null) return false;
        String main = type.getType().toLowerCase(java.util.Locale.ROOT);
        String sub = type.getSubtype().toLowerCase(java.util.Locale.ROOT);
        if ("image".equals(main)) return !sub.contains("svg");
        return "video".equals(main) || "audio".equals(main);
    }

    // A file the agent made with runPython, from the conversation's outputs/ folder. Same shape and
    // same ownership check as getAttachment above, and served as a download for the same reason.
    @GetMapping("/chat/conversations/{id}/files/{fileName}")
    public Mono<ResponseEntity<Resource>> getOutputFile(@PathVariable String id,
                                                        @PathVariable String fileName,
                                                        ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Resource>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<Resource>build();
            Optional<Path> file = chatMemory.resolveOutputFile(id, fileName);
            if (file.isEmpty())
                return ResponseEntity.status(404).<Resource>build();
            return storedFile(file.get(), fileName,
                    MediaTypeFactory.getMediaType(fileName).orElse(MediaType.APPLICATION_OCTET_STREAM), false);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Serves media WebFetchTool/WebSearchTool (proxyma-core-tools) downloaded and saved under
    // this conversation's web-media/ folder - see WebMediaStorage and the mediaUrl those tools
    // return, which points here. Same ownership check as every other conversation-scoped GET
    // above; SmartChatMemory.resolveWebMediaFile does the path-jailing against traversal.
    @GetMapping("/chat/conversations/{id}/media/{fileName}")
    public Mono<ResponseEntity<Resource>> getConversationMedia(@PathVariable String id,
                                                                @PathVariable String fileName,
                                                                ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Resource>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            // Deliberately NO "conversation not yet persisted (first turn still in flight) - allow"
            // escape hatch here, unlike chatEvents()/conversationStatus()/stopChat()/
            // resolveToolApproval()/resolveToolEnableDecision(). Those are opened by the client the
            // moment POST /chat returns its conversationId, which genuinely can beat the async
            // chat() call's ensureConversation() insert. This endpoint cannot: a media URL only
            // exists once the model has written it into an assistant message, which is many steps
            // after ensureConversation() - ChatService.chat() persists the row before the first LLM
            // call, let alone the tool call that downloads the media in the first place.
            // An unknown id therefore means an unknown conversation, not a pending one, and the
            // NoSuchElementException it throws is already rendered as a clean 404 by
            // GlobalExceptionHandler (proxyma-core-system) - same as getConversation() above, which
            // has never needed a catch either. Swallowing it here would do nothing but skip the
            // ownership gate on the one code path whose whole job is enforcing it.
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<Resource>build();
            Optional<Path> file = chatMemory.resolveWebMediaFile(id, fileName);
            if (file.isEmpty())
                return ResponseEntity.status(404).<Resource>build();
            // Derive the Content-Type from the file name via Spring's bundled MIME table first,
            // NOT from Files.probeContentType: the latter depends on an OS-level MIME database that
            // is routinely absent on a slim Linux container (returns null there -> octet-stream).
            // Served under Spring Security's default X-Content-Type-Options: nosniff, an octet-stream
            // image is then refused by the browser instead of being sniffed back to an image, so a
            // correctly-downloaded image renders as a broken-image glyph in the chat. probeContentType
            // stays only as a fallback for extensions the bundled table doesn't cover.
            MediaType contentType = MediaTypeFactory.getMediaType(fileName).orElse(null);
            if (contentType == null) {
                try {
                    String probed = Files.probeContentType(file.get());
                    contentType = probed != null ? MediaType.parseMediaType(probed) : MediaType.APPLICATION_OCTET_STREAM;
                } catch (Exception e) {
                    contentType = MediaType.APPLICATION_OCTET_STREAM;
                }
            }
            return storedFile(file.get(), fileName, contentType, true);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Lets the frontend tell, on mount/refresh, whether a turn is still running server-side for
    // this conversation - see the resume flow in useChat.ts/ChatPage.tsx. isActive() is a pure
    // in-memory abortFlags lookup (see ChatService), independent of DB persistence, so this is safe
    // to call even for a conversation whose first turn hasn't been persisted yet.
    @GetMapping("/chat/conversations/{id}/status")
    public Mono<ResponseEntity<Map<String, Boolean>>> conversationStatus(@PathVariable String id,
                                                                          ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Map<String, Boolean>>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            // A conversation whose first turn is still in flight is not stored yet - see mayReachPending.
            if (!mayReachPending(id, uid, guestSessionId))
                return ResponseEntity.status(404).<Map<String, Boolean>>build();
            // `condensing` is reported separately rather than folded into `active`: the two are
            // both "busy", but only one of them has a name the UI can show, and a client that
            // reloaded mid-condensation has no other way to learn it is in one. Replaying the
            // event stream alone is not enough - the sink is reaped after 45 idle minutes, and a
            // condensation that started before the client ever connected has no start event left
            // to replay. See SmartChatMemory.isCondensingNow().
            // `background`: sub-agents are running there, or a report is on its way to the main
            // agent. Not a turn - the composer stays the user's - but a reason to follow the
            // stream, since 2026-10-02.
            return ResponseEntity.ok(Map.of(
                    "active", chatService.isActive(id),
                    "condensing", chatMemory.isCondensingNow(id),
                    "background", chatService.hasBackgroundWork(id)));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Which of the caller's conversations have a turn running - the history list's dots and the
    // Chat badge, since 2026-09-28, when conversations started running side by side. Keyed by who
    // STARTED each turn (ChatService.recordTurnStart), so it is right for a first turn that has not
    // been persisted yet and can never list somebody else's conversation.
    // The chat page's own by default - the dots point back to /chat/{id} - or one surface's.
    @GetMapping("/chat/conversations/active")
    public Mono<ResponseEntity<Map<String, List<String>>>> activeConversations(
            @RequestParam(required = false) String surface, ServerWebExchange exchange) {
        return resolveUserId(exchange).map(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            String owner = turnOwner(uid, resolveGuestSessionId(exchange, false));
            return ResponseEntity.ok(Map.of("ids",
                    chatService.activeConversationsOf(owner, ConversationSurface.normalize(surface))));
        });
    }

    // A signed-in user by id, a visitor by their guest session, and Desktop's single local user.
    private static String turnOwner(String uid, String guestSessionId) {
        if (uid != null) return "user:" + uid;
        if (guestSessionId != null) return "guest:" + guestSessionId;
        return "local";
    }

    @PostMapping("/chat/conversations/{id}/stop")
    public Mono<ResponseEntity<Void>> stopChat(@PathVariable String id, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            if (!mayReachPending(id, uid, guestSessionId))
                return ResponseEntity.status(404).<Void>build();
            chatService.stop(id);
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Manual "condense now" - the frontend shows this action once context usage crosses its own
    // earlier warning threshold (60%, well before SmartChatMemory's own 80% auto-trigger inside
    // get()). Rejected with 409 while a turn is active, matching rewind()'s identical guard below:
    // condense() itself isn't safe to run concurrently with a live chat turn mutating the same
    // conversation's memory.
    //
    // Fire-and-forget, exactly like POST /chat above, rather than the blocking call this used to
    // be. A condensation is a summarizer call that can legitimately run for minutes, and holding
    // the request open for it had three consequences, all of which the user saw:
    //
    //  - Its progress was invisible. condense() publishes CONDENSE_START/CONDENSE_END, but a
    //    conversation whose last turn had already finished has a COMPLETED sink (see
    //    ChatEventSink.emit), which silently rejects further emissions - so for the manual case
    //    those events reached nobody and the UI showed nothing at all. ensureSink() below installs
    //    a fresh one first, which is precisely what POST /chat does for a turn.
    //  - Stop could not reach it, and now can: chatService.condenseNow() registers an abort flag
    //    under the same key stop() looks up (see its javadoc).
    //  - There was no terminal event, so the client had nothing to key "finished" off besides the
    //    HTTP response - which meant a reload mid-condense lost track of it entirely. Publishing
    //    DONE at the end closes the sink the same way a turn does, and the client drains whatever
    //    the user typed while waiting off the back of it.
    @PostMapping("/chat/conversations/{id}/condense")
    public Mono<ResponseEntity<Void>> condenseNow(@PathVariable String id, ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<Void>build();
            // Answered synchronously so a busy conversation is still a plain 409 the caller can act
            // on, rather than a 202 followed by an error event for work that never started.
            if (chatService.isActive(id))
                return ResponseEntity.status(409).<Void>build();

            chatEventSink.ensureSink(id);
            Mono.fromCallable(() -> chatService.condenseNow(id))
                    .subscribeOn(ChatService.TURNS)
                    .subscribe(
                            started -> {
                                // condenseNow() returns false when a turn claimed the conversation
                                // in the window between the isActive() check above and the lock -
                                // the same "busy, try again" answer, delivered as an event because
                                // the response has already gone.
                                if (!Boolean.TRUE.equals(started)) {
                                    eventPublisher.publishEvent(new ChatEvent(id,
                                            chatEventSink.nextSequence(id),
                                            ChatEvent.Type.ERROR.value(), null, null,
                                            "Could not condense - this conversation is busy. Try again in a moment."));
                                    return;
                                }
                                eventPublisher.publishEvent(new ChatEvent(id,
                                        chatEventSink.nextSequence(id),
                                        ChatEvent.Type.DONE.value(), null, null, ""));
                            },
                            err -> {
                                log.error("[Chat] condense error conversationId={}", id, err);
                                eventPublisher.publishEvent(new ChatEvent(id,
                                        chatEventSink.nextSequence(id),
                                        ChatEvent.Type.ERROR.value(), null, null, Failures.describe(err)));
                            });

            return ResponseEntity.accepted().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Takes back the thumbs rating on one message (see ChatService.retractRating). Lives here
    // rather than under /api/v1/feedback so it can reuse the conversation-ownership check every
    // other conversation-scoped write uses - the rating is anonymous, so the feedback row itself
    // carries no owner to check against.
    @DeleteMapping("/chat/conversations/{id}/messages/{messageId}/rating")
    public Mono<ResponseEntity<Void>> retractRating(@PathVariable String id, @PathVariable Long messageId,
                                                     ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<Void>build();
            chatService.retractRating(id, messageId);
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // A message typed while this conversation's turn is running, folded into that turn instead of
    // waiting for it - see ChatService.inject. Body: {text, clientId}. 202 when the turn took it,
    // 409 when there is no turn to join (the page then sends it normally), 422 with the refusal
    // when the input guardrail withholds it. Same ownership check as tool-approval, and the chat
    // rate bucket's sibling: it is a user message reaching the model.
    @PostMapping("/chat/conversations/{id}/inject")
    public Mono<ResponseEntity<Map<String, String>>> injectMessage(@PathVariable String id,
                                                                    @RequestBody Map<String, String> body,
                                                                    ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Map<String, String>>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            try {
                Conversation conv = chatService.getConversation(id);
                if (!canAccessConversation(conv, uid, guestSessionId))
                    return ResponseEntity.status(404).<Map<String, String>>build();
            } catch (NoSuchElementException e) {
                // No conversation row yet means no turn this caller could join.
                return ResponseEntity.status(409).<Map<String, String>>build();
            }
            String text = body.get("text");
            if (text == null || text.isBlank() || text.length() > 100_000) {
                return ResponseEntity.badRequest().<Map<String, String>>build();
            }
            ChatService.InjectResult result = chatService.inject(id, text, body.get("clientId"), uid);
            return switch (result.outcome()) {
                case ACCEPTED -> ResponseEntity.accepted().<Map<String, String>>build();
                case NOT_RUNNING -> ResponseEntity.status(409).<Map<String, String>>build();
                case BLOCKED -> ResponseEntity.unprocessableEntity().body(Map.of("error", result.message()));
            };
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // The user's answers to the assistant's askUser questions - see ChatService.awaitUserAnswers.
    // Body: {callId, answers: [{question, answer}], skipped?}, an answer being text or - for a
    // question that took several picks - a list of text. Same ownership check as tool-approval.
    @PostMapping("/chat/conversations/{id}/question-answer")
    public Mono<ResponseEntity<Void>> answerQuestion(@PathVariable String id,
                                                     @RequestBody Map<String, Object> body,
                                                     ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            if (!mayReachPending(id, uid, guestSessionId))
                return ResponseEntity.status(404).<Void>build();
            Object callId = body.get("callId");
            if (!(callId instanceof String cid) || cid.isBlank()) return ResponseEntity.badRequest().<Void>build();
            chatService.resolveQuestion(id, cid, QuestionAnswers.read(body.get("answers")),
                    Boolean.TRUE.equals(body.get("skipped")));
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/chat/conversations/{id}/tool-approval")
    public Mono<ResponseEntity<Void>> resolveToolApproval(@PathVariable String id,
                                                           @RequestBody Map<String, String> body,
                                                           ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            if (!mayReachPending(id, uid, guestSessionId))
                return ResponseEntity.status(404).<Void>build();
            try {
                chatService.resolveApproval(id, body.get("callId"), body.get("decision"));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().<Void>build();
            }
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/chat/conversations/{id}/tool-enable-decision")
    public Mono<ResponseEntity<Void>> resolveToolEnableDecision(@PathVariable String id,
                                                                 @RequestBody Map<String, String> body,
                                                                 ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            if (!mayReachPending(id, uid, guestSessionId))
                return ResponseEntity.status(404).<Void>build();
            try {
                chatService.resolveEnableDecision(id, body.get("callId"), body.get("decision"));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().<Void>build();
            }
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/chat/conversations/{id}/rewind")
    public Mono<ResponseEntity<Void>> rewind(@PathVariable String id,
                                              @RequestBody Map<String, Long> body,
                                              ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<Void>build();
            Long messageId = body.get("messageId");
            if (messageId == null)
                return ResponseEntity.badRequest().<Void>build();
            try {
                chatService.rewind(id, messageId);
            } catch (IllegalStateException e) {
                return ResponseEntity.status(409).<Void>build();
            }
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PatchMapping("/chat/conversations/{id}/pin")
    public Mono<ResponseEntity<Void>> setPinned(@PathVariable String id,
                                                 @RequestBody Map<String, Boolean> body,
                                                 ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<Void>build();
            chatService.setPinned(id, Boolean.TRUE.equals(body.get("pinned")));
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Mints a read-only share link for this conversation up to (and including) `messageId` - the
    // assistant message whose Share button was clicked. Owner-gated by the same
    // canAccessConversation() check as every other conversation-scoped write; the returned token is
    // what the frontend puts in the /shared/{token} URL.
    @PostMapping("/chat/conversations/{id}/share")
    public Mono<ResponseEntity<Map<String, String>>> shareConversation(@PathVariable String id,
                                                                        @RequestBody Map<String, Long> body,
                                                                        ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Map<String, String>>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<Map<String, String>>build();
            Long messageId = body.get("messageId");
            if (messageId == null)
                return ResponseEntity.badRequest().<Map<String, String>>build();
            try {
                var share = chatService.createShare(id, messageId, uid, resolveUserDisplayName(exchange));
                AuditEntry.of(AuditCategory.ACCESS, "conversation.shared")
                        .user(uid)
                        .conversation(id)
                        .target(conv.getTitle())
                        .summary("Share link created" + (conv.getTitle() == null ? "" : " for " + conv.getTitle()))
                        .record();
                return ResponseEntity.ok(Map.of("token", share.getToken()));
            } catch (IllegalArgumentException e) {
                // messageId belongs to a different conversation - see createShare()'s own guard.
                return ResponseEntity.badRequest().<Map<String, String>>build();
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // The viewer's side of a share link: read-only, no conversation id, no usage figures, and no
    // way to continue/react to the conversation (there is simply no endpoint that accepts this
    // token for anything else). 404 covers both an unknown token and a deleted conversation
    // indistinguishably - see ChatService.getSharedConversation().
    //
    // NOTE: this is the one /api/v1/chat/** path that is NOT permitAll in server mode - see
    // proxyma-core-auth's SecurityConfig, which requires an authenticated session here so a
    // forwarded link is useless outside the deployment. Desktop mode has no auth filter at all,
    // so it stays open there, matching every other endpoint on this controller.
    @GetMapping("/chat/shared/{token}")
    public Mono<ResponseEntity<ai.proxyma.core.chat.dto.SharedConversationResponse>> getSharedConversation(
            @PathVariable String token) {
        return Mono.fromCallable(() -> chatService.getSharedConversation(token)
                        .map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.status(404).build()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/chat/conversations/{id}")
    public Mono<ResponseEntity<Void>> deleteConversation(@PathVariable String id,
                                                          ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            String guestSessionId = resolveGuestSessionId(exchange, false);
            Conversation conv = chatService.getConversation(id);
            if (!canAccessConversation(conv, uid, guestSessionId))
                return ResponseEntity.status(404).<Void>build();
            chatService.deleteConversation(id);
            AuditEntry.of(AuditCategory.DATA, "conversation.deleted")
                    .user(uid)
                    .conversation(id)
                    .target(conv.getTitle())
                    .summary("Conversation deleted" + (conv.getTitle() == null ? "" : ": " + conv.getTitle()))
                    .record();
            return ResponseEntity.noContent().build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Every chat event crosses to the browser through here, which makes this the one place that
    // has to screen `data` for server-local filesystem paths (see SensitivePaths) - it carries
    // streamed assistant text, tool-call arguments and tool results alike, and a delegated call's
    // arguments leaked a full per-user data path this way. Scrubbing here rather than at each
    // producer keeps the rule "nothing crossing this boundary carries a path" true for tools that
    // don't exist yet.
    //
    // Known gap: TEXT events are streamed fragments, so a path split across two chunks isn't
    // matched by either. Tool arguments and results - the realistic leak, and the observed one -
    // arrive as whole documents in a single event and are fully covered, as is any path in a
    // reloaded transcript (ChatService.getMessagesWithToolCalls screens the persisted text).
    private ServerSentEvent<String> formatSseEvent(ChatEvent event) {
        try {
            Map<String, Object> payload = Map.of(
                    "type", event.streamType(),
                    "callId", event.callId() != null ? event.callId() : "",
                    "toolName", event.toolName() != null ? event.toolName() : "",
                    "data", event.data() != null ? SensitivePaths.scrub(event.data()) : "",
                    // Empty for the main agent; the delegate call's id for anything a sub-agent
                    // did, which is what lets the client nest those rows - see ChatEvent.
                    "parentCallId", event.parentCallId() != null ? event.parentCallId() : "",
                    // When it happened, and this server's clock at the moment it is SENT. The two
                    // differ on a replay, and the second is what lets a client whose own clock is
                    // off still count a running call's seconds from the first.
                    "at", event.at(),
                    "now", System.currentTimeMillis(),
                    // A reconnecting page is replayed the whole turn, and tells the events it has
                    // already handled from the new ones by this.
                    "seq", event.sequence());
            return ServerSentEvent.<String>builder()
                    .event("chat")
                    .data(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (Exception e) {
            // Built without the mapper that just failed, so every character of the message has to
            // be escaped by hand - a quote or a newline in it made this fallback invalid JSON.
            return ServerSentEvent.<String>builder()
                    .event("chat")
                    .data("{\"type\":\"error\",\"data\":" + jsonString(SensitivePaths.scrub(String.valueOf(e.getMessage()))) + "}")
                    .build();
        }
    }

    /**
     * THE FIRST EVENT OF EVERY CHAT STREAM, since 2026-10-16: which server this is and where the
     * conversation stands on it. {@code data} is a JSON object {boot, busy, next} - the process's id
     * (ChatEventSink.boot), whether anything is still to publish here (ChatService.isBusy), and the
     * number the next event will carry. It is no event of the conversation's and is not replayed:
     * it carries no {@code seq}, so a page's count of what it has handled never moves for it.
     *
     * <p>A page could not tell a restarted server from the one it had been following. The new one
     * numbers the conversation from zero and replays nothing, so the reconnected page heard
     * nothing, skipped nothing, and showed "Waiting for ..." for a turn that had died with the old
     * process - and sent the next message into it as an injection, refused with 409 and left
     * "Queued". Told another boot, or that nothing is running and it already holds every number,
     * the page loads the conversation again instead (chatStream.ts's {@code opened}).
     */
    static ServerSentEvent<String> openEvent(String boot, boolean busy, long next) {
        String data = "{\"boot\":" + jsonString(boot) + ",\"busy\":" + busy + ",\"next\":" + next + "}";
        return ServerSentEvent.<String>builder()
                .event("chat")
                .data("{\"type\":\"open\",\"callId\":\"\",\"toolName\":\"\",\"data\":" + jsonString(data)
                        + ",\"parentCallId\":\"\",\"now\":" + System.currentTimeMillis() + "}")
                .build();
    }

    static String jsonString(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    // Both rules now live in ConversationAccess, which WorkflowRunController shares - see that
    // class for why. This one stays as a delegate so the call sites above read unchanged. (A turn
    // into a conversation that EXISTS and belongs to someone else is refused in chat() - a new id is
    // nobody's yet.)
    private boolean canAccessConversation(Conversation conv, String userId, String guestSessionId) {
        return ConversationAccess.canAccess(conv, userId, guestSessionId, chatMemory.isMultiUser());
    }

    // The check of an endpoint a page calls while its conversation's FIRST turn may still be running,
    // before the row exists: the event stream, the status, Stop, and the answers to the turn's
    // prompts. A conversation that is not stored yet is open only to whoever started its turn, or
    // to anyone while nobody has - there is nothing of anybody's in it to reach.
    //
    // ONLY "NOT FOUND" IS LENIENT. This used to catch every exception as "not stored yet", so a
    // database that timed out or a pool that was exhausted skipped the ownership check - for the
    // event stream, a subscription to someone else's replaying turn. Anything else now propagates,
    // and an id that cannot name a conversation is a 400 (ChatService.getConversation).
    private boolean mayReachPending(String conversationId, String userId, String guestSessionId) {
        try {
            return canAccessConversation(chatService.getConversation(conversationId), userId, guestSessionId);
        } catch (NoSuchElementException notStoredYet) {
            return !chatMemory.isMultiUser()
                    || !chatService.startedByAnother(conversationId, turnOwner(userId, guestSessionId));
        }
    }

    private Mono<String> resolveUserId(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("");
    }

    // Synchronous (unlike resolveUserId) because JwtAuthFilter populates request attributes
    // directly, not the reactive security context - see USER_DISPLAY_NAME_KEY's javadoc above.
    private static String resolveUserDisplayName(ServerWebExchange exchange) {
        Object raw = exchange.getAttributes().get(USER_DISPLAY_NAME_KEY);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    // Minted only where something reads it: a multi-user server, whose visitors it keeps apart and
    // whose guest meter counts by it. On Desktop and Home Server ConversationAccess
    // ignores it, and the Cookie Policy says so - a cookie that does nothing is not "strictly
    // necessary".
    private String resolveGuestSessionId(ServerWebExchange exchange, boolean mint) {
        return ConversationAccess.guestSessionId(exchange,
                mint && (chatMemory.isMultiUser() || guestQuota.isActive()));
    }
}
