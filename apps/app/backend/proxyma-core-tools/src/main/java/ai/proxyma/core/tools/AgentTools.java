package ai.proxyma.core.tools;

import ai.proxyma.core.chat.agents.AgentAlarms;
import ai.proxyma.core.chat.agents.AgentDeliberations;
import ai.proxyma.core.chat.agents.AgentFanOuts;
import ai.proxyma.core.chat.agents.AgentFiles;
import ai.proxyma.core.chat.agents.AgentVerifier;
import ai.proxyma.core.chat.agents.AgentTree;
import ai.proxyma.core.chat.agents.BackgroundJobs;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

// What an agent does besides its own work, as a person would: hand part of it to someone (deploy
// an agent), talk to them, wait for them, direct them; set an alarm to come back to something; and
// start something slow and go on with other work until it is done.
//
// Every agent is a conversation of its own (AgentTree): nothing here runs another agent's loop on
// this call's thread. Chat-only (ChatOnlyTools): over MCP and in a workflow there is no
// conversation for an agent, an alarm or a background job to belong to.
@Component
public class AgentTools {

    public static final String DEPLOY = "deployAgent";
    public static final String TELL = "tellAgent";
    public static final String AWAIT = "awaitAgents";
    public static final String CONTROL = "controlAgent";
    public static final String ALARM = "alarm";
    public static final String BACKGROUND = "background";
    public static final String DEPLOY_MANY = "deployAgents";
    public static final String READ_REPORT = "readAgentReport";
    public static final String VERIFY = "verifyReport";
    public static final String DELIBERATE = "deliberate";
    public static final String DECIDE = "recordDecision";
    public static final String HAND_FILE = "handFile";

    private final AgentTree tree;
    private final AgentAlarms alarms;
    private final BackgroundJobs jobs;
    private final AgentFanOuts fanOuts;
    private final AgentVerifier verifier;
    private final AgentDeliberations deliberations;
    private final AgentFiles files;

    public AgentTools(AgentTree tree, AgentAlarms alarms, BackgroundJobs jobs, AgentFanOuts fanOuts,
                      AgentVerifier verifier, AgentDeliberations deliberations, AgentFiles files) {
        this.files = files;
        this.tree = tree;
        this.alarms = alarms;
        this.jobs = jobs;
        this.fanOuts = fanOuts;
        this.verifier = verifier;
        this.deliberations = deliberations;
    }

    @Tool(name = DEPLOY, description = """
            Deploy an agent for one self-contained part of the work. It sees nothing of this \
            conversation: give the goal, exact ids/paths/URLs, and what to report. Returns at once; the report \
            arrives as a message beginning "[Report from". Use awaitAgents only if your next step needs it.""")
    @ToolResult({
            @ToolResultField(name = "agentId", type = ToolFieldType.STRING,
                    description = "The new agent's id: its reports name it, and tellAgent, awaitAgents and controlAgent take it."),
            @ToolResultField(name = "status", type = ToolFieldType.STRING, description = "RUNNING once it has started."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happens next.")
    })
    public Map<String, Object> deployAgent(
            @ToolParam(required = true, description = "2-6 word title the user sees") String title,
            @ToolParam(required = true, description = "The whole task: goal, exact identifiers, what to report") String task,
            @ToolParam(required = false, description = "SHORT (default) ends with its report; LONG stays for more work until dismissed") String lifetime,
            @ToolParam(required = false, description = "Only if the user asked for one: a model name") String model,
            @ToolParam(required = false, description = "Spend cap in USD, for it and its agents") Double budgetUsd,
            @ToolParam(required = false, description = "Time cap in minutes, for it and its agents") Integer minutes,
            @ToolParam(required = false, description = "true: its claims are checked against their sources first") Boolean verify,
            @ToolParam(required = false, description = "Names of your files (outputs/, attachments/) to copy to it") List<String> files) {
        return tree.deploy(SearchContext.getConversationId(), SearchContext.getUserId(),
                new AgentTree.Deploy(title, task, lifetime, null, model, budgetUsd, minutes, Boolean.TRUE.equals(verify), files));
    }

    @Tool(name = HAND_FILE, description = """
            Copy a file of yours (outputs/ or attachments/) into another agent's attachments, and \
            tell it: "parent", or an agent you deployed. Use it instead of pasting a file into a report.""")
    @ToolResult({
            @ToolResultField(name = "name", type = ToolFieldType.STRING, description = "Its name in the recipient's attachments."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happened.")
    })
    public Map<String, Object> handFile(
            @ToolParam(required = true, description = "Its name in your folder, e.g. exposure.csv") String name,
            @ToolParam(required = true, description = "\"parent\", or an agentId you deployed") String to,
            @ToolParam(required = false, description = "One sentence: what it is") String note) {
        return files.hand(SearchContext.getConversationId(), SearchContext.getUserId(), name, to, note);
    }

    @Tool(name = DEPLOY_MANY, description = """
            Run one task over a list: one agent per item, up to 200 items, maxParallel at a \
            time. Put {item} in the task where each item goes. Returns a fanOutId at once; ONE report with every \
            item arrives as a message beginning "[Report from". readAgentReport gives one item's report in full.""")
    @ToolResult({
            @ToolResultField(name = "fanOutId", type = ToolFieldType.STRING, description = "The fan-out's id, for awaitAgents and controlAgent."),
            @ToolResultField(name = "total", type = ToolFieldType.INTEGER, description = "How many items."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happens next.")
    })
    public Map<String, Object> deployAgents(
            @ToolParam(required = true, description = "2-6 word title for the run") String title,
            @ToolParam(required = true, description = "The task for every item, with {item} where the item goes") String task,
            @ToolParam(required = true, description = "The items, each as its agent should see it") List<String> items,
            @ToolParam(required = false, description = "1-8 at once (default 4)") Integer maxParallel,
            @ToolParam(required = false, description = "Spend cap in USD per item") Double budgetUsdEach,
            @ToolParam(required = false, description = "Time cap in minutes per item") Integer minutesEach,
            @ToolParam(required = false, description = "true: each report is checked first") Boolean verify,
            @ToolParam(required = false, description = "Only if the user asked for one: a model name") String model) {
        String badModel = tree.modelRefusal(model);
        if (badModel != null) return Map.of("success", false, "error", badModel);
        List<AgentFanOuts.Item> list = new java.util.ArrayList<>();
        if (items != null) {
            for (String raw : items) {
                if (raw == null || raw.isBlank()) continue;
                String item = raw.strip();
                String one = item.replaceAll("\\s+", " ");
                list.add(new AgentFanOuts.Item(one.length() <= 60 ? one : one.substring(0, 57) + "...",
                        task == null ? "" : (task.contains("{item}") ? task.replace("{item}", item) : task + "\n\nYour item: " + item)));
            }
        }
        return fanOuts.start(SearchContext.getConversationId(), SearchContext.getUserId(), title, list, maxParallel,
                budgetUsdEach, minutesEach, model, Boolean.TRUE.equals(verify));
    }

    @Tool(name = READ_REPORT, description = """
            An agent's latest report in full - yours or one your agents deployed - with its \
            verifier's scorecard if it was checked.""")
    @ToolResult({
            @ToolResultField(name = "report", type = ToolFieldType.STRING, description = "The report, whole."),
            @ToolResultField(name = "verification", type = ToolFieldType.STRING, description = "The scorecard, as JSON, when checked.")
    })
    public Map<String, Object> readAgentReport(
            @ToolParam(required = true, description = "The agent's agentId") String agentId) {
        return tree.readReport(SearchContext.getConversationId(), agentId);
    }

    @Tool(name = VERIFY, description = """
            Have a verifier check a delivered report's claims against their cited sources. Returns \
            at once; the scorecard arrives as a message beginning "[Report from Check:".""")
    @ToolResult({
            @ToolResultField(name = "agentId", type = ToolFieldType.STRING, description = "The verifier's id, for awaitAgents."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happens next.")
    })
    public Map<String, Object> verifyReport(
            @ToolParam(required = true, description = "The agentId whose report to check") String agentId) {
        return verifier.verifyReport(SearchContext.getConversationId(), agentId);
    }

    @Tool(name = DELIBERATE, description = """
            Put a hard question to a panel of 2-7 agents with different briefs; they argue from \
            evidence and vote, round by round. Returns a deliberationId at once; the tally arrives as a message \
            beginning "[Report from Deliberation". Then decide yourself with recordDecision.""")
    @ToolResult({
            @ToolResultField(name = "deliberationId", type = ToolFieldType.STRING, description = "For awaitAgents and recordDecision."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happens next.")
    })
    public Map<String, Object> deliberate(
            @ToolParam(required = true, description = "The whole question: what is decided, the evidence, identifiers") String question,
            @ToolParam(required = true, description = "One entry per panelist: \"Name: brief\"") List<String> panel,
            @ToolParam(required = false, description = "Choices to vote among; none for a free answer") List<String> options,
            @ToolParam(required = false, description = "1-3 rounds (default 2)") Integer rounds,
            @ToolParam(required = false, description = "Only if the user asked for one: a model name") String model) {
        String badModel = tree.modelRefusal(model);
        if (badModel != null) return Map.of("success", false, "error", badModel);
        return deliberations.start(SearchContext.getConversationId(), SearchContext.getUserId(), question, options, panel,
                rounds, model);
    }

    @Tool(name = DECIDE, description = """
            Record your decision on a finished deliberation, and why. Returns who voted otherwise.""")
    @ToolResult({
            @ToolResultField(name = "decision", type = ToolFieldType.STRING, description = "The decision as kept."),
            @ToolResultField(name = "dissent", type = ToolFieldType.STRING_ARRAY, description = "Who voted otherwise, and how.")
    })
    public Map<String, Object> recordDecision(
            @ToolParam(required = true, description = "The deliberationId") String deliberationId,
            @ToolParam(required = true, description = "The decision, in a few words") String decision,
            @ToolParam(required = true, description = "Why: the evidence and arguments") String rationale) {
        return deliberations.decide(SearchContext.getConversationId(), deliberationId, decision, rationale);
    }

    @Tool(name = TELL, description = """
            Message "parent" (the agent that deployed you) or an agent you deployed: more information, \
            a change of course, an answer, new work for a LONG agent. Its reply arrives as a message. Do not ask \
            for status: reports come by themselves.""")
    @ToolResult({
            @ToolResultField(name = "status", type = ToolFieldType.STRING, description = "Where the recipient stands."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happens next.")
    })
    public Map<String, Object> tellAgent(
            @ToolParam(required = true, description = "\"parent\", or an agentId you deployed") String to,
            @ToolParam(required = true, description = "The message, written to it") String message) {
        return tree.tell(SearchContext.getConversationId(), to, message);
    }

    @Tool(name = AWAIT, description = """
            Wait for your agents' reports - only when your next step needs them. Waits at most \
            timeoutSeconds (default and most 600); later reports still arrive as messages. No agentIds: waits for \
            all that owe a report, fan-outs and deliberations included.""")
    @ToolResult({
            @ToolResultField(name = "reports", type = ToolFieldType.OBJECT_ARRAY,
                    description = "One entry per agent that reported: agentId, title, status, report."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "How many are still working.")
    })
    public Map<String, Object> awaitAgents(
            @ToolParam(required = false, description = "Comma-separated agentIds; none for all that owe a report") String agentIds,
            @ToolParam(required = false, description = "Seconds to wait, at most 600") Integer timeoutSeconds) {
        List<String> ids = agentIds == null || agentIds.isBlank() ? List.of()
                : Arrays.stream(agentIds.split("[,\\s]+")).filter(s -> !s.isBlank()).toList();
        return tree.await(SearchContext.getConversationId(), ids, timeoutSeconds);
    }

    @Tool(name = CONTROL, description = """
            Direct your agents and theirs. action: list (where each stands), pause (after its \
            current step), resume, stop (it and all it deployed, now), dismiss (end a LONG agent).""")
    @ToolResult({
            @ToolResultField(name = "status", type = ToolFieldType.STRING, description = "Where the agent stands now."),
            @ToolResultField(name = "agents", type = ToolFieldType.OBJECT_ARRAY, description = "For list: your agents.")
    })
    public Map<String, Object> controlAgent(
            @ToolParam(required = true, description = "list, pause, resume, stop or dismiss") String action,
            @ToolParam(required = false, description = "The agentId (for stop, also a fanOutId or deliberationId); not for list") String agentId) {
        String conversationId = SearchContext.getConversationId();
        if (conversationId == null) return Map.of("success", false, "error", "There are no agents outside a conversation.");
        if ("list".equalsIgnoreCase(action == null ? "" : action.strip())) return tree.list(conversationId);
        return tree.control(conversationId, agentId, action);
    }

    @Tool(name = ALARM, description = """
            Wake yourself later. action set: inMinutes or at (ISO-8601 with offset), and a note; then \
            the note arrives as a message beginning "[Alarm". 1 minute to 30 days ahead. list: yours. cancel: by \
            alarmId. Set one and end your turn instead of waiting.""")
    @ToolResult({
            @ToolResultField(name = "alarmId", type = ToolFieldType.STRING, description = "For set: the alarm's id."),
            @ToolResultField(name = "dueAt", type = ToolFieldType.STRING, description = "For set: when it goes off."),
            @ToolResultField(name = "alarms", type = ToolFieldType.OBJECT_ARRAY, description = "For list: the pending alarms.")
    })
    public Map<String, Object> alarm(
            @ToolParam(required = true, description = "set, list or cancel") String action,
            @ToolParam(required = false, description = "set: minutes from now") Integer inMinutes,
            @ToolParam(required = false, description = "set: ISO-8601 time with offset, instead of inMinutes") String at,
            @ToolParam(required = false, description = "set: what to do then, written to yourself") String note,
            @ToolParam(required = false, description = "cancel: the alarmId") String alarmId) {
        String conversationId = SearchContext.getConversationId();
        String a = action == null ? "" : action.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (a) {
            case "set" -> alarms.set(conversationId, at, inMinutes, note);
            case "list" -> conversationId == null ? Map.of("success", true, "alarms", List.of()) : alarms.list(conversationId);
            case "cancel" -> alarms.cancel(conversationId, alarmId);
            default -> Map.of("success", false, "error", "action is set, list or cancel.");
        };
    }

    @Tool(name = BACKGROUND, description = """
            Run one of your tools in the background and carry on. action run: tool (its name) and \
            arguments (its JSON arguments); returns a jobId, and the result arrives as a message beginning \
            "[Background result". Writes still need approval. list: what runs. cancel: by jobId.""")
    @ToolResult({
            @ToolResultField(name = "jobId", type = ToolFieldType.STRING, description = "For run: the job's id."),
            @ToolResultField(name = "jobs", type = ToolFieldType.OBJECT_ARRAY, description = "For list: what runs.")
    })
    public Map<String, Object> background(
            @ToolParam(required = true, description = "run, list or cancel") String action,
            @ToolParam(required = false, description = "run: the tool's name") String tool,
            @ToolParam(required = false, description = "run: the tool's arguments as a JSON object") String arguments,
            @ToolParam(required = false, description = "cancel: the jobId") String jobId) {
        String conversationId = SearchContext.getConversationId();
        String a = action == null ? "" : action.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (a) {
            case "run" -> jobs.run(conversationId, tool, arguments);
            case "list" -> conversationId == null ? Map.of("success", true, "jobs", List.of()) : jobs.list(conversationId);
            case "cancel" -> jobs.cancel(conversationId, jobId);
            default -> Map.of("success", false, "error", "action is run, list or cancel.");
        };
    }
}
