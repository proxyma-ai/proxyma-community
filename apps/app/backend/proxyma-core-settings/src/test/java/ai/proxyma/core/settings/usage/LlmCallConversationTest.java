package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.common.usage.CostSource;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-call log says WHICH CONVERSATION each call was for, and names its agent (2026-11-04): so one
 * pass of a Constellations team - every agent a conversation of its own - can be measured by agent.
 *
 * <p>What it pins: the id is written with the call and nothing else of the conversation; the log is
 * filtered by it without loosening a user's scope; the agent label comes through the port, asked once
 * per distinct conversation on a page, and a port that fails leaves the calls readable; the download
 * carries Conversation, Agent and Trigger, and can be grouped by conversation.
 */
class LlmCallConversationTest {

    private static final SpendSubject USER = SpendSubject.user("u-1");

    private LlmCallRepository repository;
    private LlmCallsController controller;
    private final List<Collection<String>> asked = new ArrayList<>();

    private static LlmCall call(String conversationId, double cost) {
        LlmCall c = new LlmCall();
        c.setRecordedAt(Instant.parse("2026-11-04T10:00:00Z"));
        c.setSubjectKind(SpendSubject.Kind.USER);
        c.setSubjectId("u-1");
        c.setRole("CHAT");
        c.setProvider("Anthropic");
        c.setModel("claude-haiku-5");
        c.setInputTokens(100);
        c.setOutputTokens(10);
        c.setCostUsd(cost);
        c.setConversationId(conversationId);
        return c;
    }

    @BeforeEach
    void setUp() {
        repository = mock(LlmCallRepository.class);
        controller = new LlmCallsController(repository, mock(ModelPricingService.class));
        ReflectionTestUtils.setField(controller, "multiUser", false);
        asked.clear();
        controller.setCallLabels(ids -> {
            asked.add(List.copyOf(ids));
            return ids.contains("conv-a")
                    ? Map.of("conv-a", new LlmCallLabels.Label("YouTube / Comment triage", LlmCallLabels.TRIGGER_SCHEDULE))
                    : Map.of();
        });
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(call("conv-a", 0.1), call("conv-a", 0.2), call("conv-b", 0.3),
                        call(null, 0.0))));
    }

    @Test
    @DisplayName("a call is written with the conversation it was made for, and one made for none with none")
    void writesTheConversation() {
        LlmCallLogService service = new LlmCallLogService(repository, true);
        TokenUsage usage = new TokenUsage(10, 2, 0, 0, 10);

        service.record(USER, "CHAT", "Anthropic", "claude-haiku-5", usage, 0.1, CostSource.RATE, null, null, "conv-1");
        service.record(USER, "EMBEDDING", "Ollama", "bge-m3", usage, 0.0, CostSource.NONE, null, null, null);
        service.record(USER, "CHAT", "Anthropic", "claude-haiku-5", usage, 0.1);
        service.record(USER, "CHAT", "Anthropic", "claude-haiku-5", usage, 0.1, CostSource.RATE, null, null,
                "x".repeat(LlmCall.CONVERSATION_ID_MAX + 1));

        ArgumentCaptor<LlmCall> saved = ArgumentCaptor.forClass(LlmCall.class);
        verify(repository, org.mockito.Mockito.times(4)).save(saved.capture());
        assertEquals("conv-1", saved.getAllValues().get(0).getConversationId());
        assertNull(saved.getAllValues().get(1).getConversationId());
        assertNull(saved.getAllValues().get(2).getConversationId());
        // Longer than any conversation id: not stored rather than refused by the column.
        assertNull(saved.getAllValues().get(3).getConversationId());
    }

    @Test
    @DisplayName("the conversation filter reaches the query, and a user's filtered log is still only theirs")
    void filterKeepsTheScope() {
        ReflectionTestUtils.setField(controller, "multiUser", true);
        var auth = new UsernamePasswordAuthenticationToken("u-7", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        controller.calls(30, 0, 50, null, null, null, null, null, null, null, "log", " conv-a ")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();

        verify(repository).search(any(), any(), eq("u-7"), any(), any(), any(), any(), eq("conv-a"), any(Pageable.class));
    }

    @Test
    @DisplayName("each call on a page carries its conversation and its agent, asked once for the page's distinct conversations")
    @SuppressWarnings("unchecked")
    void pageCarriesLabels() {
        ResponseEntity<Map<String, Object>> response =
                controller.calls(30, 0, 50, null, null, null, null, null, null, null, "log", null).block();

        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertEquals("conv-a", items.get(0).get("conversationId"));
        assertEquals("YouTube / Comment triage", items.get(0).get("agent"));
        assertEquals("schedule", items.get(0).get("trigger"));
        assertEquals("conv-b", items.get(2).get("conversationId"));
        assertNull(items.get(2).get("agent"));
        assertNull(items.get(3).get("conversationId"));
        assertEquals(1, asked.size());
        assertEquals(List.of("conv-a", "conv-b"), asked.get(0));
    }

    @Test
    @DisplayName("a label port that fails leaves the log readable, with no agent named")
    @SuppressWarnings("unchecked")
    void failingPortIsDecorationOnly() {
        controller.setCallLabels(ids -> { throw new IllegalStateException("down"); });

        ResponseEntity<Map<String, Object>> response =
                controller.calls(30, 0, 50, null, null, null, null, null, null, null, "log", null).block();

        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertEquals(4, items.size());
        assertNull(items.get(0).get("agent"));
    }

    @Test
    @DisplayName("the download carries Conversation, Agent and Trigger, empty for a call with none")
    void exportCarriesTheConversation() {
        ResponseEntity<byte[]> file = controller.export(30, null, null, null, null, null, null, "csv", null, null).block();

        String[] lines = new String(file.getBody(), StandardCharsets.UTF_8).replace("﻿", "").split("\r\n");
        assertTrue(lines[0].endsWith(",Priced,Conversation,Agent,Trigger"), lines[0]);
        assertTrue(lines[1].endsWith(",conv-a,YouTube / Comment triage,schedule"), lines[1]);
        assertTrue(lines[3].endsWith(",conv-b,,"), lines[3]);
        assertTrue(lines[4].endsWith(",,,"), lines[4]);
    }

    @Test
    @DisplayName("grouped by conversation: a row per conversation with its agent, totals and first and last call, in the caller's scope")
    void exportGroupedByConversation() {
        ReflectionTestUtils.setField(controller, "multiUser", true);
        Instant first = Instant.parse("2026-11-04T10:00:00Z");
        Instant last = Instant.parse("2026-11-04T10:04:00Z");
        List<Object[]> grouped = List.of(
                new Object[] {"conv-a", 7L, 7000L, 700L, 5000L, 1200L, 0.42, first, last},
                new Object[] {null, 3L, 900L, 0L, 0L, 0L, 0.01, first, first});
        when(repository.totalsByConversation(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(grouped);
        var auth = new UsernamePasswordAuthenticationToken("u-7", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        ResponseEntity<byte[]> file = controller.export(1, null, null, null, null, null, null, "csv", null, "conversation")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();

        verify(repository).totalsByConversation(any(), any(), eq("u-7"), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class));
        verify(repository, never()).search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
        String[] lines = new String(file.getBody(), StandardCharsets.UTF_8).replace("﻿", "").split("\r\n");
        assertEquals(String.join(",", LlmCallsController.CONVERSATION_COLUMNS), lines[0]);
        assertEquals("conv-a,YouTube / Comment triage,schedule,7,7000,700,1200,5000,0.42,"
                + "2026-11-04T10:00:00Z,2026-11-04T10:04:00Z", lines[1]);
        assertEquals(",,,3,900,0,0,0,0.01,2026-11-04T10:00:00Z,2026-11-04T10:00:00Z", lines[2]);
    }

    @Test
    @DisplayName("the summary lists the period's costliest conversations for the filter, named, without the calls made for none")
    @SuppressWarnings("unchecked")
    void summaryListsConversations() {
        Instant at = Instant.parse("2026-11-04T10:00:00Z");
        List<Object[]> grouped = List.of(
                new Object[] {"conv-a", 2L, 200L, 20L, 0L, 0L, 0.3, at, at},
                new Object[] {null, 1L, 100L, 0L, 0L, 0L, 0.0, at, at});
        when(repository.totalsByConversation(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(grouped);

        ResponseEntity<Map<String, Object>> response =
                controller.calls(30, 0, 50, null, null, null, null, null, null, null, "summary", null).block();

        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getBody().get("byConversation");
        assertEquals(1, rows.size());
        assertEquals("conv-a", rows.get(0).get("conversationId"));
        assertEquals("YouTube / Comment triage", rows.get(0).get("agent"));
    }
}
