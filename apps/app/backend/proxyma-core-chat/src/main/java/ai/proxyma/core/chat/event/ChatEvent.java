package ai.proxyma.core.chat.event;

// `sequence` is assigned synchronously (ChatEventSink.nextSequence) at the exact call site that
// constructs each event - before it is handed to the async event bus - so it reflects true
// chronological publish order even though parallel tool-call threads and the @Async listener can
// race afterward. ChatEventSink uses it to re-serialize emission order per conversation.
// `parentCallId` is null for everything the main agent does. It is set on everything a sub-agent
// does or asks (see SubAgentService), carrying that sub-agent's id, so the client shows it in the
// sub-agent's own panel instead of interleaving it with the main agent's; and on a workflow step's
// tool calls, carrying the step's call id. Declared as a secondary constructor below rather than
// added to every existing call site: the main agent never has a parent, so defaulting to null is
// the correct behavior at all of them.
// `at` is when the event was PUBLISHED, in epoch milliseconds - stamped here, at construction, for
// the same reason `sequence` is. A client reads how long a tool call or a sub-agent took off the
// difference between two of them, and a turn replayed to a page that reconnected has to give the
// same answer as the turn watched live; the moment an event happens to be DELIVERED says nothing.
public record ChatEvent(
        String conversationId,
        long sequence,
        String streamType,
        String callId,
        String toolName,
        String data,
        String parentCallId,
        long at) {

    public ChatEvent(String conversationId, long sequence, String streamType,
                     String callId, String toolName, String data) {
        this(conversationId, sequence, streamType, callId, toolName, data, null);
    }

    public ChatEvent(String conversationId, long sequence, String streamType,
                     String callId, String toolName, String data, String parentCallId) {
        this(conversationId, sequence, streamType, callId, toolName, data, parentCallId,
                System.currentTimeMillis());
    }

    public enum Type {
        TEXT("text"),
        // A reasoning model is THINKING: data is the estimated number of thinking tokens so far
        // in this model call, as a decimal string. Never the thinking text - the reader is shown
        // that the model is working and how much, not what it said to itself, and the answer
        // stream stays exactly the answer. See ModelThinking for where each protocol puts it.
        THINKING("thinking"),
        TOOL_START("tool-start"),
        TOOL_RESULT("tool-result"),
        TOOL_APPROVAL_REQUEST("tool-approval-request"),
        // Fired instead of TOOL_APPROVAL_REQUEST when a write-capable tool call is blocked solely
        // by the user's own missing personal consent (not org-mode/org-wide-blocked, which still
        // rejects immediately with no pause) - see ChatService.awaitEnableDecision. `data` is a
        // JSON object {connectorField, connectorLabel} (WriteToolRegistry.Connector) telling the
        // client which settings field to flip and what to call the connector in its consent
        // modal. Resolved via POST .../tool-enable-decision, same shape as tool-approval-request.
        TOOL_ENABLE_REQUEST("tool-enable-request"),
        // The assistant asks the user multiple-choice questions ('askUser'). callId is the call's
        // tracking id, data its arguments JSON ({questions: [...]}). Answered through POST
        // .../question-answer, the same pause a tool approval takes. Since 2026-09-29.
        USER_QUESTION("user-question"),
        // A message the user typed while the turn ran has just been folded into it (ChatService
        // .inject). callId is the page's own id for the message, data its text as the model got it.
        USER_INJECTED("user-injected"),
        // The turn ran out of steps (ChatSettings.maxIterations) and stopped there. data is the
        // limit, as a decimal string. Published once, after whatever the last step produced, so the
        // reader is told the answer above it was cut short rather than left to work that out from
        // an answer that trails off - or from no answer at all, when the last step asked for more
        // tools instead of writing one. Since 2026-10-01.
        STEP_LIMIT("step-limit"),
        // A prompt that was open is closed, and no tool result will say so: callId is the prompt's.
        // An approval or an askUser question ends with its call's TOOL_RESULT; the question a turn
        // asks at its step limit (ChatService.askToContinue) belongs to no tool call. Since
        // 2026-10-03.
        PROMPT_CLOSED("prompt-closed"),
        // An agent this conversation deployed (AgentTree) changed where it stands. callId is the
        // agent's id; data is a JSON object {agentId, title, status, lifetime, depth, owesReport,
        // conversationId}. Its panel and the agents map follow it.
        AGENT_STATUS("agent-status"),
        // A turn has just read its inbox (AgentInbox): data is a JSON object {entries: [{agentId,
        // title, kind}]}, kind report | message | alarm | background | user_talk. The thread draws
        // a line per entry; the agent's next words are its reaction.
        AGENT_MAIL("agent-mail"),
        // A turn has started that nobody sent a message to start: something reached the agent's
        // inbox (AgentInbox) - a report, a message, an alarm, a background result - and it answers it. data is empty. A page following the conversation
        // shows it as a turn in progress from here on.
        TURN_START("turn-start"),
        // Brackets a (potentially slow) LLM condensation call - see SmartChatMemory.condense() -
        // so the chat UI can show a "Condensing conversation..." indicator instead of looking
        // stalled while older messages are folded into a handoff summary.
        CONDENSE_START("condense-start"),
        CONDENSE_END("condense-end"),
        // Fired once per LLM response (see ChatService.publishUsageUpdate()) - data is a JSON
        // object matching ConversationDetail's usage fields (inputTokens/outputTokens/
        // cacheReadTokens/cacheWriteTokens/contextTokens/contextTokenLimit), so the frontend usage
        // meter can update live through a turn instead of only refreshing once 'done' fires.
        USAGE("usage"),
        // A brand-new conversation's generated title, pushed as soon as it resolves (see
        // ConversationTitleService, fired at the start of the first turn) so the history list
        // renames itself mid-turn instead of showing the truncated placeholder until the next
        // refetch. `data` is the title text itself - no JSON wrapper, nothing else to carry.
        TITLE("title"),
        // One event for a workflow run's whole lifecycle, fired on every transition: the run
        // starting, a step starting, a step finishing, the run ending. `callId` is the run id;
        // `data` is the COMPLETE run snapshot as JSON (see WorkflowRunSnapshot), never a delta.
        //
        // Carrying the whole snapshot rather than four delta events is what makes the step table
        // survive a refresh. Run state is authoritative on the server and readable over REST, and
        // this event is a notification that it changed rather than the record of the change - so
        // the wire shape here and the shape the client fetches on load are the same object, parsed
        // by the same code. A missed or out-of-order event self-heals on the next one instead of
        // leaving a row stuck "running" forever, which is the failure the tool-call rows already
        // had once (see LESSONS_LEARNED, "Chat Tool Call UI Stuck In Progress").
        //
        // A snapshot is ~20 step rows of titles and statuses. Against the message text already
        // streaming through this channel that is not a size worth optimizing into deltas.
        WORKFLOW_RUN("workflow-run"),
        RETRY("retry"),
        // A guardrail withheld something. EVERYTHING travels in `data`, as a JSON object
        // {stage, verdict, categories, message}, and the sentence to show the user is
        // `data.message` - inside that JSON string, so it has to be parsed again.
        //
        // This said "`text` is the sentence to show the user" until 2026-09-24. THERE IS NO `text`
        // FIELD - not on this record, not on the wire. A ChatEvent is
        // (conversationId, sequence, streamType, callId, toolName, data, parentCallId), and
        // publishGuardrail puts the message in `data` precisely because there is nowhere else for
        // it to go. Twenty minutes each time somebody believed the comment; see
        // recurring-failure-classes.md section 26, which collected it, and section 11 for the rule
        // it is an instance of - read the write path, not the sentence describing it.
        //
        // Its own type rather than an ERROR, deliberately. An error means the product failed, and
        // a client that renders it as one invites the user to retry the identical message; this
        // means the product did what its operator configured it to do, and retrying unchanged will
        // do the same thing again.
        //
        // Carries CATEGORIES and never the matched text. The reason a secret rule fires is that
        // the span it matched is a credential, so an event quoting it would be the control leaking
        // the thing it exists to contain - see GuardrailFinding, which refuses to carry it either.
        GUARDRAIL("guardrail"),
        ERROR("error"),
        // Caller's session lapsed mid-conversation (see ConversationAccessDeniedException) -
        // distinct from ERROR so the frontend can attempt silent token refresh instead of just
        // displaying the raw error text.
        SESSION_EXPIRED("session-expired"),
        DONE("done");

        private final String value;
        Type(String value) { this.value = value; }
        public String value() { return this.value; }
    }
}
