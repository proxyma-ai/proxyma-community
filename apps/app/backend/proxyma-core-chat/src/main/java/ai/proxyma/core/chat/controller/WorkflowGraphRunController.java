package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.model.WorkflowRunEvent;
import ai.proxyma.core.chat.workflow.WorkflowGraph;
import ai.proxyma.core.chat.workflow.WorkflowGraphStore;
import ai.proxyma.core.chat.workflow.run.RunEventSink;
import ai.proxyma.core.chat.workflow.run.WorkflowGraphRunService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * A run is a first-class object, and this is its surface.
 *
 * <p>THE CHANGE THIS REPRESENTS. Everything about a run used to hang off a conversation: the SSE
 * stream was {@code /chat/events/{conversationId}}, the abort flag was keyed by conversation, and
 * approval was a POST to a conversation. A design page that starts runs has no conversation, so
 * start/stop/pause and a log are not a set of buttons - they are this endpoint set. See
 * proxyma-docs/proposals/workflow-design-page.md.
 *
 * <p>Mounted under {@code /api/v1/workflows} rather than under {@code /api/v1/chat}: a run is not a
 * chat turn any more, and putting it there would say the opposite. A chat-invoked run still records
 * its conversation - that is what the field is for - but it is not what identifies it.
 */
@ConditionalOnProperty(name = "proxyma.features.workflows", havingValue = "true")
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowGraphRunController {

    /** Sent while a run is quiet so a proxy between the browser and here does not decide the
     *  connection is dead - the same reason ChatController's own stream carries one. */
    private static final Duration HEARTBEAT = Duration.ofSeconds(20);

    private final WorkflowGraphStore store;
    private final WorkflowGraphRunService runService;
    private final RunEventSink sink;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public WorkflowGraphRunController(WorkflowGraphStore store, WorkflowGraphRunService runService,
                                      RunEventSink sink) {
        this.store = store;
        this.runService = runService;
        this.sink = sink;
    }

    public record StartRequest(Map<String, Object> inputs) {}

    /** Starts a run and returns its id immediately. A run is minutes of work and this is an HTTP
     *  request; the id is what the client subscribes to. */
    @PostMapping("/{workflowId}/runs")
    public Mono<ResponseEntity<?>> start(@PathVariable String workflowId,
                                         @RequestBody(required = false) StartRequest request,
                                         ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> Mono.<ResponseEntity<?>>fromCallable(() -> {
                    WorkflowGraph graph = store.get(workflowId, orNull(uid));
                    UUID runId = runService.start(graph,
                            request == null || request.inputs() == null ? Map.of() : request.inputs(),
                            orNull(uid), null);
                    return ResponseEntity.accepted().body(Map.of("runId", runId.toString()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(NoSuchElementException.class,
                        e -> Mono.just(ResponseEntity.notFound().build()))
                // The quota gate and a malformed graph both refuse with a message written to be
                // read by the person who pressed Run, so it is passed through rather than flattened
                // into a bare 400.
                .onErrorResume(RuntimeException.class, e -> Mono.just(
                        ResponseEntity.badRequest().body(Map.of("error", describe(e))))));
    }

    @GetMapping("/runs/{runId}")
    public Mono<ResponseEntity<?>> snapshot(@PathVariable UUID runId, ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> superAdmin().flatMap(admin ->
                Mono.<ResponseEntity<?>>fromCallable(() -> runService.find(runId)
                                .filter(row -> runService.visibleTo(row, orNull(uid), admin))
                                .<ResponseEntity<?>>map(row -> ResponseEntity.ok(runService.snapshotOf(row)))
                                .orElseGet(() -> ResponseEntity.notFound().build()))
                        .subscribeOn(Schedulers.boundedElastic())));
    }

    @GetMapping("/runs")
    public Mono<ResponseEntity<?>> recent(ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> Mono.<ResponseEntity<?>>fromCallable(() ->
                        ResponseEntity.ok(runService.snapshots(runService.recent(orNull(uid)))))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * The execution log, from a cursor.
     *
     * <p>{@code after} is what makes the stream and the table one thing: a client holds the last
     * seq it saw and asks for the rest, rather than replaying a run with a long loop from the
     * beginning every time a connection drops.
     */
    @GetMapping("/runs/{runId}/log")
    public Mono<ResponseEntity<?>> logAfter(@PathVariable UUID runId,
                                            @RequestParam(defaultValue = "-1") long after,
                                            ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> superAdmin().flatMap(admin ->
                Mono.<ResponseEntity<?>>fromCallable(() -> {
                    if (runService.find(runId).filter(row -> runService.visibleTo(row, orNull(uid), admin)).isEmpty()) {
                        return ResponseEntity.notFound().build();
                    }
                    List<Map<String, Object>> rows = runService.logAfter(runId, after).stream()
                            .map(WorkflowGraphRunController::asJson).toList();
                    return ResponseEntity.ok(rows);
                }).subscribeOn(Schedulers.boundedElastic())));
    }

    /**
     * Live events for one run.
     *
     * <p>Per RUN, not per conversation. A dropped connection loses nothing: every event was written
     * to the log table before it was emitted, so a client reconnects and calls {@code /log?after=}.
     */
    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> events(@PathVariable UUID runId, ServerWebExchange exchange) {
        return userId(exchange).flatMapMany(uid -> superAdmin().flatMapMany(admin ->
                Mono.fromCallable(() -> runService.find(runId)
                                .filter(row -> runService.visibleTo(row, orNull(uid), admin)).isPresent())
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(allowed -> {
                            if (!allowed) return Flux.empty();
                            return sink.subscribe(runId).mergeWith(heartbeat());
                        })));
    }

    private Flux<Map<String, Object>> heartbeat() {
        return Flux.interval(HEARTBEAT, HEARTBEAT).map(tick -> Map.of("kind", "PING"));
    }

    // - controls -

    @PostMapping("/runs/{runId}/pause")
    public Mono<ResponseEntity<Void>> pause(@PathVariable UUID runId, ServerWebExchange exchange) {
        return control(runId, exchange, (uid, admin) -> runService.pause(runId, uid, admin));
    }

    @PostMapping("/runs/{runId}/resume")
    public Mono<ResponseEntity<Void>> resume(@PathVariable UUID runId, ServerWebExchange exchange) {
        return control(runId, exchange, (uid, admin) -> runService.resume(runId, uid, admin));
    }

    @PostMapping("/runs/{runId}/stop")
    public Mono<ResponseEntity<Void>> stop(@PathVariable UUID runId, ServerWebExchange exchange) {
        return control(runId, exchange, (uid, admin) -> runService.stop(runId, uid, admin));
    }

    public record ApprovalRequest(String nodeId, boolean approved) {}

    @PostMapping("/runs/{runId}/approve")
    public Mono<ResponseEntity<Void>> approve(@PathVariable UUID runId,
                                              @RequestBody ApprovalRequest request,
                                              ServerWebExchange exchange) {
        return control(runId, exchange,
                (uid, admin) -> runService.approve(runId, request.nodeId(), request.approved(), uid, admin));
    }

    /**
     * 404 covers three cases on purpose: no such run, a run this instance is not running, and a run
     * belonging to somebody else. Distinguishing them would tell a caller which runs exist and who
     * owns them, which is the same reason a workflow they cannot see is not found rather than
     * forbidden.
     */
    private Mono<ResponseEntity<Void>> control(UUID runId, ServerWebExchange exchange,
                                               java.util.function.BiPredicate<String, Boolean> action) {
        return userId(exchange).flatMap(uid -> superAdmin().flatMap(admin ->
                Mono.fromCallable(() -> action.test(orNull(uid), admin)
                                ? ResponseEntity.noContent().<Void>build()
                                : ResponseEntity.notFound().<Void>build())
                        .subscribeOn(Schedulers.boundedElastic())));
    }

    // - helpers -

    private static Map<String, Object> asJson(WorkflowRunEvent row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", row.getSeq());
        out.put("at", row.getAt() == null ? null : row.getAt().toString());
        out.put("kind", row.getKind());
        out.put("nodeId", row.getNodeId());
        if (row.getIteration() > 1) out.put("iteration", row.getIteration());
        out.put("detail", row.getDetail());
        out.put("payload", row.getPayloadJson());
        out.put("millis", row.getMillis());
        if (row.getInputTokens() > 0 || row.getOutputTokens() > 0 || row.getCostUsd() > 0) {
            out.put("inputTokens", row.getInputTokens());
            out.put("outputTokens", row.getOutputTokens());
            out.put("costUsd", row.getCostUsd());
        }
        return out;
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "This run could not be started."
                : e.getMessage();
    }

    /**
     * The caller's id, or "" when there is none - which is every desktop request, since that
     * edition has no security filter chain at all.
     *
     * <p>EMPTY STRING, NOT NULL, and the call sites convert with {@link #orNull}. A Reactor stream
     * cannot carry null: a mapper returning one throws
     * {@code NullPointerException: The mapper returned a null value}, which is a 500 on every
     * endpoint here in the edition that has no principal. Same shape as ChatSkillController and
     * WorkflowController, which resolve to "" for exactly this reason.
     */
    private Mono<String> userId(ServerWebExchange exchange) {
        return exchange.getPrincipal().map(Principal::getName).defaultIfEmpty("");
    }

    private static String orNull(String userId) {
        return userId == null || userId.isEmpty() ? null : userId;
    }

    private Mono<Boolean> superAdmin() {
        if (!multiUser) return Mono.just(true);
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch("ROLE_SUPER_ADMIN"::equals))
                .defaultIfEmpty(false);
    }
}
