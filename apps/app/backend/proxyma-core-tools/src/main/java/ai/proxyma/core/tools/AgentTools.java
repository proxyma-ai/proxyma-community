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
            Deploy an agent to work on a task on its own, in a conversation of its own, with your tools \
            and your access (never more). It sees none of this conversation: write the task so it can \
            act without asking - the goal, exact identifiers (keys, ids, paths, URLs copied character \
            for character), what its report must contain. Returns at once; its report arrives later as \
            a message beginning "[Report from". Carry on or end your turn; call awaitAgents only when \
            your next step needs the report. lifetime SHORT (default) ends with its report; LONG stays \
            after it for more work you give it with tellAgent, until you dismiss it. budgetUsd and minutes \
            bound what it and the agents it deploys may spend and how long they may take: past either, \
            they stop and you are told. verify true has a verifier check every claim of its report \
            against the cited sources before the report reaches you, with the scorecard under it. For the \
            same task over many items, use deployAgents.""")
    @ToolResult({
            @ToolResultField(name = "agentId", type = ToolFieldType.STRING,
                    description = "The new agent's id: its reports name it, and tellAgent, awaitAgents and controlAgent take it."),
            @ToolResultField(name = "status", type = ToolFieldType.STRING, description = "RUNNING once it has started."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happens next.")
    })
    public Map<String, Object> deployAgent(
            @ToolParam(required = true, description = "2-6 word title the user sees, e.g. 'Compare release notes'") String title,
            @ToolParam(required = true, description = "Its task, complete: goal, identifiers, context, what to report and in what shape") String task,
            @ToolParam(required = false, description = "SHORT (default): done after its report. LONG: stays for more work until dismissed") String lifetime,
            @ToolParam(required = false, description = "A model of its own, only when the user asked for one: a name a provider lists and Settings allow - a name it does not know is refused with the ones you may use. Left out, it runs on the default") String model,
            @ToolParam(required = false, description = "What it and the agents it deploys may spend, in US dollars") Double budgetUsd,
            @ToolParam(required = false, description = "Minutes it and the agents it deploys have before they are stopped") Integer minutes,
            @ToolParam(required = false, description = "true: a verifier checks its report's claims against their sources first") Boolean verify,
            @ToolParam(required = false, description = "Files of yours it needs - names in your outputs/ or attachments/ - copied into its attachments") List<String> files) {
        return tree.deploy(SearchContext.getConversationId(), SearchContext.getUserId(),
                new AgentTree.Deploy(title, task, lifetime, null, model, budgetUsd, minutes, Boolean.TRUE.equals(verify), files));
    }

    @Tool(name = HAND_FILE, description = """
            Hand a file you made (outputs/) or were given (attachments/) to another agent: to "parent" - \
            your table, chart or draft for the agent that deployed you, instead of pasting it into your \
            report - or to an agent you deployed, by its agentId. It is copied into their attachments, \
            where their scripts read it, and they are told. Name it as it is in your folder.""")
    @ToolResult({
            @ToolResultField(name = "name", type = ToolFieldType.STRING, description = "Its name in the recipient's attachments."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happened.")
    })
    public Map<String, Object> handFile(
            @ToolParam(required = true, description = "The file's name, e.g. exposure.csv") String name,
            @ToolParam(required = true, description = "\"parent\", or the agentId of an agent you deployed") String to,
            @ToolParam(required = false, description = "What it is and how to read it, in a sentence") String note) {
        return files.hand(SearchContext.getConversationId(), SearchContext.getUserId(), name, to, note);
    }

    @Tool(name = DEPLOY_MANY, description = """
            Run the same task over a list: one agent per item (an ECU, an account, a contract, a \
            repository), at most maxParallel at a time (default 4, most 8) and queued past every cap, \
            up to 200 items. Write the task once with {item} where each item goes; each agent sees only \
            its own. Returns a fanOutId at once; ONE report with every item's result arrives later as a \
            message beginning "[Report from <title>" - each item's report cut short, readAgentReport \
            gives one in full. awaitAgents with the fanOutId waits for it; controlAgent stop stops it.""")
    @ToolResult({
            @ToolResultField(name = "fanOutId", type = ToolFieldType.STRING, description = "The fan-out's id, for awaitAgents and controlAgent."),
            @ToolResultField(name = "total", type = ToolFieldType.INTEGER, description = "How many items."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happens next.")
    })
    public Map<String, Object> deployAgents(
            @ToolParam(required = true, description = "2-6 word title for the whole run, e.g. 'Check each ECU'") String title,
            @ToolParam(required = true, description = "The task for every item, complete, with {item} where the item goes") String task,
            @ToolParam(required = true, description = "The items, one entry each - written exactly as each agent should see it") List<String> items,
            @ToolParam(required = false, description = "How many work at once, 1-8 (default 4)") Integer maxParallel,
            @ToolParam(required = false, description = "What each item's agent may spend, in US dollars") Double budgetUsdEach,
            @ToolParam(required = false, description = "Minutes each item's agent has") Integer minutesEach,
            @ToolParam(required = false, description = "true: each item's report is checked by a verifier first") Boolean verify,
            @ToolParam(required = false, description = "A model for them, only when the user asked for one: a name a provider lists and Settings allow") String model) {
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
            The latest report of an agent you deployed, or one yours deployed, in full - with its \
            verifier's scorecard when it was checked, and why it stopped when the tree stopped it.""")
    @ToolResult({
            @ToolResultField(name = "report", type = ToolFieldType.STRING, description = "The report, whole."),
            @ToolResultField(name = "verification", type = ToolFieldType.STRING, description = "The scorecard, as JSON, when checked.")
    })
    public Map<String, Object> readAgentReport(
            @ToolParam(required = true, description = "The agent's agentId") String agentId) {
        return tree.readReport(SearchContext.getConversationId(), agentId);
    }

    @Tool(name = VERIFY, description = """
            Have a verifier check a report already delivered - every factual claim against the source it \
            cites: supported, unsupported or uncited. Returns at once; the scorecard arrives as a message \
            beginning "[Report from Check:", and is kept on the agent's card.""")
    @ToolResult({
            @ToolResultField(name = "agentId", type = ToolFieldType.STRING, description = "The verifier's id, for awaitAgents."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happens next.")
    })
    public Map<String, Object> verifyReport(
            @ToolParam(required = true, description = "The agentId of the agent whose report to check") String agentId) {
        return verifier.verifyReport(SearchContext.getConversationId(), agentId);
    }

    @Tool(name = DELIBERATE, description = """
            Put a hard question to a panel of 2-7 agents, each with a brief of its own (a skeptic, the \
            safety engineer, the customer's counsel): each argues from evidence and votes; from round 2 \
            each reads the others and keeps or changes its vote. Returns a deliberationId at once; the \
            tally and the final arguments arrive as a message beginning "[Report from Deliberation". Then \
            YOU decide and call recordDecision - whoever voted otherwise is kept on record as dissent.""")
    @ToolResult({
            @ToolResultField(name = "deliberationId", type = ToolFieldType.STRING, description = "For awaitAgents and recordDecision."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happens next.")
    })
    public Map<String, Object> deliberate(
            @ToolParam(required = true, description = "The question, complete: what is decided, the evidence to use, identifiers") String question,
            @ToolParam(required = true, description = "The panelists, one entry each: \"Name: brief\"") List<String> panel,
            @ToolParam(required = false, description = "The choices they vote among; none for a free answer") List<String> options,
            @ToolParam(required = false, description = "Rounds, 1-3 (default 2)") Integer rounds,
            @ToolParam(required = false, description = "A model for the panel, only when the user asked for one: a name a provider lists and Settings allow") String model) {
        String badModel = tree.modelRefusal(model);
        if (badModel != null) return Map.of("success", false, "error", badModel);
        return deliberations.start(SearchContext.getConversationId(), SearchContext.getUserId(), question, options, panel,
                rounds, model);
    }

    @Tool(name = DECIDE, description = """
            Record your decision on a finished deliberation, and why. The panelists whose final vote \
            differs are kept on record as dissent, and returned.""")
    @ToolResult({
            @ToolResultField(name = "decision", type = ToolFieldType.STRING, description = "The decision as kept."),
            @ToolResultField(name = "dissent", type = ToolFieldType.STRING_ARRAY, description = "Who voted otherwise, and how.")
    })
    public Map<String, Object> recordDecision(
            @ToolParam(required = true, description = "The deliberationId") String deliberationId,
            @ToolParam(required = true, description = "The decision, in a few words") String decision,
            @ToolParam(required = true, description = "Why - the evidence and the arguments that carried it") String rationale) {
        return deliberations.decide(SearchContext.getConversationId(), deliberationId, decision, rationale);
    }

    @Tool(name = TELL, description = """
            Message another agent: to "parent" (the agent that deployed you - a question it must answer, \
            or progress it needs now), or an agent you deployed by its agentId (more information, a \
            change of course, the answer to its question, new work for a LONG agent). It reads it at \
            its next step; its answer arrives as a message to you. Do not ask how it is doing - its \
            report comes by itself.""")
    @ToolResult({
            @ToolResultField(name = "status", type = ToolFieldType.STRING, description = "Where the recipient stands."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = "What happens next.")
    })
    public Map<String, Object> tellAgent(
            @ToolParam(required = true, description = "\"parent\", or the agentId of an agent you deployed") String to,
            @ToolParam(required = true, description = "What to tell it, written to it") String message) {
        return tree.tell(SearchContext.getConversationId(), to, message);
    }

    @Tool(name = AWAIT, description = """
            Wait for reports from agents you deployed - only when your very next step needs them. Waits \
            at most timeoutSeconds (default and most 600); reports not in by then still arrive later \
            as messages. With no agentIds, waits for every agent of yours that owes a report, and every \
            fan-out and deliberation of yours still running; their ids may be named too.""")
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
            Direct the agents you deployed, and theirs. action "list": your agents and where each \
            stands. "pause": it stops after its current step and holds what reaches it. "resume": it \
            carries on. "stop": it and everything it deployed end now. "dismiss": a LONG agent's work \
            is done - it ends.""")
    @ToolResult({
            @ToolResultField(name = "status", type = ToolFieldType.STRING, description = "Where the agent stands now."),
            @ToolResultField(name = "agents", type = ToolFieldType.OBJECT_ARRAY, description = "For list: your agents.")
    })
    public Map<String, Object> controlAgent(
            @ToolParam(required = true, description = "list, pause, resume, stop or dismiss") String action,
            @ToolParam(required = false, description = "The agent's agentId (or, for stop, a fanOutId or deliberationId); not needed for list") String agentId) {
        String conversationId = SearchContext.getConversationId();
        if (conversationId == null) return Map.of("success", false, "error", "There are no agents outside a conversation.");
        if ("list".equalsIgnoreCase(action == null ? "" : action.strip())) return tree.list(conversationId);
        return tree.control(conversationId, agentId, action);
    }

    @Tool(name = ALARM, description = """
            Set an alarm to wake yourself later - to check on something, follow up, or continue work at \
            a time. action "set" with inMinutes or at (ISO-8601 with offset) and a note saying what to \
            do then: at that time the note reaches you as a message beginning "[Alarm", even if the \
            user is gone. At least 1 minute and at most 30 days ahead. "list" shows yours; "cancel" \
            takes an alarmId. Set one and end your turn rather than waiting.""")
    @ToolResult({
            @ToolResultField(name = "alarmId", type = ToolFieldType.STRING, description = "For set: the alarm's id."),
            @ToolResultField(name = "dueAt", type = ToolFieldType.STRING, description = "For set: when it goes off."),
            @ToolResultField(name = "alarms", type = ToolFieldType.OBJECT_ARRAY, description = "For list: the pending alarms.")
    })
    public Map<String, Object> alarm(
            @ToolParam(required = true, description = "set, list or cancel") String action,
            @ToolParam(required = false, description = "For set: minutes from now") Integer inMinutes,
            @ToolParam(required = false, description = "For set: an ISO-8601 time with offset, instead of inMinutes") String at,
            @ToolParam(required = false, description = "For set: what to do when it goes off, written to yourself") String note,
            @ToolParam(required = false, description = "For cancel: the alarmId") String alarmId) {
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
            Run one of your tools in the background and carry on: a long command, a slow fetch, a big \
            read. action "run" with tool (its name) and arguments (its JSON arguments, as you would \
            call it): returns a jobId at once, and the result arrives as a message beginning \
            "[Background result". Writes still ask for approval as usual. "list" shows what runs; \
            "cancel" takes a jobId.""")
    @ToolResult({
            @ToolResultField(name = "jobId", type = ToolFieldType.STRING, description = "For run: the job's id."),
            @ToolResultField(name = "jobs", type = ToolFieldType.OBJECT_ARRAY, description = "For list: what runs.")
    })
    public Map<String, Object> background(
            @ToolParam(required = true, description = "run, list or cancel") String action,
            @ToolParam(required = false, description = "For run: the tool's name") String tool,
            @ToolParam(required = false, description = "For run: the tool's arguments as a JSON object") String arguments,
            @ToolParam(required = false, description = "For cancel: the jobId") String jobId) {
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
