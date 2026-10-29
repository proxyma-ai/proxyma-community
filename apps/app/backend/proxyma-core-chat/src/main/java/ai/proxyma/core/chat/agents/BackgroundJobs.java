package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.search.domain.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TOOL CALLS RUN IN THE BACKGROUND - a long command, a slow fetch, a sync - while the agent carries
 * on, the way a person starts a build and goes back to their desk. The call goes through the very
 * path a turn's own calls do (ChatService.callTools: the permission mode, the approval prompt, the
 * guardrail, the offload of a large result), on a worker thread; its result reaches the agent's
 * inbox (kind BACKGROUND), which starts a turn for it if none is running.
 *
 * <p>A background call may run for up to half an hour (ChatService.BACKGROUND_TOOL_TIMEOUT_SECONDS),
 * where a call the agent waits on has a minute. Held in memory: a restart ends what was running, and the agent is told so at its next turn
 * rather than left waiting. At most {@value #MAX_RUNNING} per conversation.
 */
@Service
public class BackgroundJobs {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobs.class);

    static final int MAX_RUNNING = 6;
    /** Tools that make no sense in the background: they wait, ask, or start background work already. */
    static final Set<String> NOT_IN_BACKGROUND = Set.of("background", "awaitAgents", "askUser", "deployAgent",
            "deployAgents", "verifyReport", "deliberate");

    record Job(String id, String conversationId, String tool, Instant startedAt, AtomicBoolean abort) {}

    private final ObjectProvider<ChatService> chat;
    private final AgentInbox inbox;
    private final ConcurrentHashMap<String, Job> running = new ConcurrentHashMap<>();

    public BackgroundJobs(ObjectProvider<ChatService> chat, AgentInbox inbox) {
        this.chat = chat;
        this.inbox = inbox;
    }

    /** Starts {@code tool} with {@code arguments} (JSON) in the background of this conversation. */
    public Map<String, Object> run(String conversationId, String tool, String arguments) {
        if (conversationId == null) return AgentTree.failure("Background work runs only inside a conversation.");
        if (tool == null || tool.isBlank()) return AgentTree.failure("tool must name the tool to run.");
        String name = tool.strip();
        if (NOT_IN_BACKGROUND.contains(name)) {
            return AgentTree.failure(name + " cannot run in the background" + ("deployAgent".equals(name)
                    ? ": a deployed agent already works on its own." : "."));
        }
        if (runningIn(conversationId).size() >= MAX_RUNNING) {
            return AgentTree.failure("At most " + MAX_RUNNING + " background jobs run at once here. Wait for one, "
                    + "or cancel one.");
        }
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, conversationId, name, Instant.now(), new AtomicBoolean());
        running.put(id, job);
        String userId = SearchContext.getUserId();
        SpendSubject subject = SpendContext.current();
        String args = arguments == null || arguments.isBlank() ? "{}" : arguments;
        BlockingWork.executor().execute(() -> {
            String result;
            SearchContext.setUserId(userId);
            SearchContext.setConversationId(conversationId);
            SpendContext.set(subject);
            try {
                ToolResponseMessage response = chat.getObject().callTools(conversationId,
                        List.of(new ToolCall(ChatService.BACKGROUND_CALL_PREFIX + id, "function", name, args)), job.abort());
                result = response.getResponses().isEmpty() ? "" : response.getResponses().get(0).responseData();
            } catch (RuntimeException e) {
                log.warn("[Agents] background job {} ({}) failed: {}", id, name, e.getMessage());
                result = "{\"success\":false,\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
            } finally {
                SearchContext.clear();
                SpendContext.clear();
                running.remove(id);
            }
            if (job.abort().get()) result = "Canceled before it finished.";
            inbox.post(conversationId, AgentInbox.Kind.BACKGROUND, null, name + " (job " + id + ")", result);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("jobId", id);
        out.put("note", "Running. Its result arrives as a message beginning \"[Background result " + name
                + "\"; carry on meanwhile, or end your turn.");
        return out;
    }

    public Map<String, Object> list(String conversationId) {
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (Job j : runningIn(conversationId)) {
            jobs.add(Map.of("jobId", j.id(), "tool", j.tool(), "startedAt", j.startedAt().toString()));
        }
        return Map.of("success", true, "jobs", jobs);
    }

    public Map<String, Object> cancel(String conversationId, String jobId) {
        Job j = jobId == null ? null : running.get(jobId.strip());
        if (j == null || !j.conversationId().equals(conversationId)) return AgentTree.failure("No running job " + jobId + " here.");
        j.abort().set(true);
        return Map.of("success", true, "note", "Asked to stop; its result says how far it got.");
    }

    /** Whether background work is running in this conversation - it keeps a child from reporting early. */
    public boolean hasRunning(String conversationId) {
        return conversationId != null && running.values().stream().anyMatch(j -> j.conversationId().equals(conversationId));
    }

    private List<Job> runningIn(String conversationId) {
        return running.values().stream().filter(j -> j.conversationId().equals(conversationId)).toList();
    }
}
