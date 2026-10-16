package ai.proxyma.core.system.controller;

import ai.proxyma.core.system.event.EventSinkService;
import ai.proxyma.core.registry.event.ConnectorOperationEvent;
import ai.proxyma.core.registry.event.ConnectorRegisteredEvent;
import ai.proxyma.core.registry.event.ConnectorRemovedEvent;
import ai.proxyma.core.registry.event.ConnectorGraphEvent;
import ai.proxyma.core.registry.event.ConnectorSyncedEvent;
import ai.proxyma.core.registry.event.ConnectorUpdateEvent;
import ai.proxyma.core.registry.service.ConnectorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// Server-Sent Events endpoint. All processing events are forwarded here
// via the shared EventSinkService. Clients reconnect automatically via EventSource.
//
// Bodies are written by Jackson (platform-13). They were concatenated by hand with an escape that
// knew only backslash and quote, so a document title carrying a newline or a tab - a Teams message,
// a web page's title - made invalid JSON, which the page's SSEProvider read as a bare 'message' and
// every listener ignored.
//
// The listeners for the ingest module's FileProcessing/IssueProcessing/.../TeamsMessageProcessing
// events went on 2026-10-14 (platform-12): nothing publishes any of the 21, and the plain strings
// they emitted were never a type the page listened for.
@RestController
@RequestMapping("/api/v1")
public class EventStreamController {

    private static final Logger log = LoggerFactory.getLogger(EventStreamController.class);

    // An SSE comment - ignored by EventSource, and enough to keep a proxy from closing an idle
    // stream. It went out as a DATA line (": keepalive" through the string codec), which fired every
    // listener on the page every 30 seconds (platform-15).
    static final ServerSentEvent<String> KEEPALIVE = ServerSentEvent.<String>builder().comment("keepalive").build();

    private final EventSinkService eventSink;
    private final ConnectorService connectorService;
    private final ObjectMapper json;

    public EventStreamController(EventSinkService eventSink, ConnectorService connectorService, ObjectMapper json) {
        this.eventSink = eventSink;
        this.connectorService = connectorService;
        this.json = json;
    }

    // /api/v1/events is intentionally permitAll (anonymous users get live search/chat progress
    // too) - but every connector-scoped message is filtered per-subscriber against the caller's
    // own userId so registering a private connector never broadcasts its title/status to every
    // other connected client (SEC-D4). Legacy/global messages (Scope.GLOBAL) are unaffected.
    //
    // THE FIRST EVENT NAMES WHOSE STREAM IT IS (STREAM_IDENTITY): the user id the filter above uses,
    // or null for nobody. The page shares one stream between all its tabs (eventsShare.ts), and the
    // session can change in one tab while the stream was opened under another: without being told,
    // the tab holding the stream cannot know whose events it is relaying, and it relayed one
    // account's events into a tab still showing another, and kept an anonymous stream open after a
    // sign-in. It is the caller's own id, which /api/v1/auth/me already gives them.
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMapMany(userId -> {
            String uid = userId.isEmpty() ? null : userId;
            return eventSink.getFlux()
                    .filter(msg -> msg.scope().visibleTo(uid))
                    .map(msg -> ServerSentEvent.builder(msg.json()).build())
                    .startWith(identity(uid))
                    .mergeWith(Flux.interval(Duration.ofSeconds(30)).map(i -> KEEPALIVE));
        });
    }

    static final String STREAM_IDENTITY = "stream-identity";

    ServerSentEvent<String> identity(String uid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", STREAM_IDENTITY);
        body.put("userId", uid);
        try {
            return ServerSentEvent.builder(json.writeValueAsString(body)).build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write the stream's identity", e);
        }
    }

    private Mono<String> resolveUserId(ServerWebExchange exchange) {
        return exchange.getPrincipal().map(Principal::getName).defaultIfEmpty("");
    }

    // Live DB lookup at emit time (not per-subscriber at filter time) - resolved once per event
    // regardless of how many clients are connected, and correct for the whole window in which
    // these three event types fire (the connector row still exists; only ConnectorRemovedEvent
    // fires after deletion, which is why it carries its own userId/shared fields instead).
    // Fails open (Scope.GLOBAL) if the connector has already vanished by the time this runs -
    // an accepted, low-severity edge case since the payload is metadata only, never document
    // content.
    private EventSinkService.Scope scopeFor(UUID connectorId) {
        return connectorService.getOwnerScope(connectorId)
                .map(s -> new EventSinkService.Scope(s.userId(), s.shared()))
                .orElse(EventSinkService.Scope.GLOBAL);
    }

    @Async @EventListener
    public void on(ConnectorUpdateEvent e) {
        Map<String, Object> body = typed("connector-update", e.getConnectorId());
        body.put("documentId", String.valueOf(e.getDocumentId()));
        body.put("documentTitle", e.getDocumentTitle() == null ? "" : e.getDocumentTitle());
        body.put("status", String.valueOf(e.getStatus()));
        body.put("currentChunks", e.getCurrentChunks());
        body.put("totalChunks", e.getTotalChunks());
        emit(body, scopeFor(e.getConnectorId()));
    }

    @Async @EventListener
    public void on(ConnectorOperationEvent e) {
        Map<String, Object> body = typed("connector-operation", e.getConnectorId());
        body.put("operation", String.valueOf(e.getOperation()));
        body.put("phase", e.getPhase().name());
        emit(body, scopeFor(e.getConnectorId()));
    }

    // The knowledge graph opened or closed one of this source's items. Carries nothing but the id:
    // the source list and the item dialog re-read what they show.
    @Async @EventListener
    public void on(ConnectorGraphEvent e) {
        emit(typed("connector-graph", e.getConnectorId()), scopeFor(e.getConnectorId()));
    }

    @Async @EventListener
    public void on(ConnectorSyncedEvent e) {
        emit(typed("connector-synced", e.getConnectorId()), scopeFor(e.getConnectorId()));
    }

    // A new row in the source lists, whoever created it - the Sources page, the agent in chat, or an
    // MCP client. Scoped like removal, from the event: a just-registered source is its owner's alone.
    @Async @EventListener
    public void on(ConnectorRegisteredEvent e) {
        emit(typed("connector-registered", e.getConnectorId()), new EventSinkService.Scope(e.getUserId(), false));
    }

    @Async @EventListener
    public void on(ConnectorRemovedEvent e) {
        emit(typed("connector-removed", e.getConnectorId()), new EventSinkService.Scope(e.getUserId(), e.isShared()));
    }

    private static Map<String, Object> typed(String type, Object connectorId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("connectorId", String.valueOf(connectorId));
        return body;
    }

    void emit(Map<String, Object> body, EventSinkService.Scope scope) {
        try {
            eventSink.emit(json.writeValueAsString(body), scope);
        } catch (JsonProcessingException e) {
            log.warn("[Events] could not write a {} event: {}", body.get("type"), e.getMessage());
        }
    }
}
