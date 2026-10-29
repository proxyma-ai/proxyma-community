export type MessageRole = 'user' | 'assistant'

export interface ChatSource {
  source: string
  score: number
  type: string
}

export interface ToolCallInfo {
  callId: string
  toolName: string
  arguments: string
  result?: string
  done: boolean
  // Set only on calls made inside a running workflow STEP: the step's synthetic call id, which nests
  // them under its row. Absent for everything the agent does on its own.
  parentCallId?: string
  // When the call started and ended, in epoch milliseconds on the SERVER's clock - the `at` of its
  // tool-start and tool-result events live, and what the result row recorded once reloaded. Absent
  // on a call stored before 2026-10-01, and endedAt on one that never reported back.
  startedAt?: number
  endedAt?: number
}

// THE TREE OF AGENTS (since 2026-10-28). Every agent - the one in the conversation the user opened
// included - can deploy agents of its own, each of them a conversation of its own run by the same
// turn loop (AgentNode on the backend). Status and lifetime are the backend's words, as it sends them.
export type AgentStatus = 'RUNNING' | 'WAITING' | 'IDLE' | 'PAUSED' | 'DONE' | 'STOPPED' | 'FAILED'
export type AgentLifetime = 'SHORT' | 'LONG'

// The 'agent-status' event's data: one agent this conversation deployed has changed where it stands.
export interface AgentStatusUpdate {
  agentId: string
  title: string
  status: AgentStatus
  lifetime: AgentLifetime
  depth: number
  owesReport: boolean
  conversationId: string
}

// What reached a running turn from its inbox - one line each in the thread, never a user bubble.
// `agentId` and `title` are null for what no agent sent (an alarm).
export type AgentMailKind = 'report' | 'message' | 'alarm' | 'background' | 'user_talk' | 'approval' | 'context' | 'memory'
export interface AgentMailEntry {
  agentId: string | null
  title: string | null
  kind: AgentMailKind
}

// One invocation of a workflow: the plan and the live state of every step, as ONE object. The
// backend sends this identical shape twice over - as the `data` of every 'workflow-run' SSE event,
// and as the body of GET .../workflow-runs - so a table watched live and a table rebuilt after a
// refresh are produced by this one parser rather than by two that have to agree. Never a delta: a
// dropped event self-heals on the next one instead of leaving a row spinning forever.
export interface WorkflowRunSnapshot {
  runId: string
  workflowId: string
  workflowName: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  steps: WorkflowRunStep[]
  startedAt: number
  finishedAt?: number | null
  failureMessage?: string | null
  // The user message this run's table is drawn under - see WorkflowRun.triggerMessageId on the
  // backend. Null only for a run whose anchor was rewound away.
  triggerMessageId?: number | null
}

export interface WorkflowRunStep {
  index: number
  title: string
  instruction: string
  // SKIPPED means never attempted, because an earlier step failed or the run was stopped - drawn
  // as the same empty circle as a step that has not started, greyed, NOT as a failure.
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED'
  // The step's synthetic tool-call id. Tool calls made inside the step carry it as parentCallId,
  // which is what nests them under this row.
  callId: string
  // The step's own assistant text, rendered inside the expanded row rather than as its own bubble
  // in the thread. Null until the step finishes.
  output?: string | null
  failureMessage?: string | null
}

export interface ChatMessage {
  id?: number
  role: MessageRole
  content: string
  sources?: ChatSource[]
  toolCalls?: ToolCallInfo[]
  createdAt?: string
  error?: boolean
  // True while this user message is waiting for the current turn to finish before it's actually
  // sent - see useChat's send()/processQueue().
  queued?: boolean
  // The page's id for a queued message, and whether the running turn has taken it to read at its
  // next step (ChatService.inject, since 2026-09-29) rather than waiting for the turn to end.
  clientId?: string
  injectAccepted?: boolean
  // Display-only file names for a user message's attachments (never the server-side path - see
  // ChatMessageResponse.attachmentNames on the backend for the same reasoning). Set from the
  // live ChatAttachment[] state at send time for the current session, or from the backend's own
  // sanitized field once a conversation is fetched/reloaded - either way, rendered by
  // MessageBubble below the message text.
  attachmentNames?: string[]
  // Document/skill names the user explicitly picked via @/skill mentions in this message - same
  // dual source as attachmentNames above (live state at send time; ChatMessageResponse.mentions/
  // skillMentions once reloaded). MessageBubble uses these to render the matching "@Name"/
  // "/Name" tokens still present in `content` as badges instead of plain text.
  mentions?: string[]
  skillMentions?: string[]
  // Set on the USER message that invoked a workflow: the run whose step table is drawn directly
  // below this bubble. The run itself lives in chatStore.workflowRuns, keyed by this id - the
  // message carries only the pointer, so a live snapshot update re-renders the table without
  // rewriting the message list.
  workflowRunId?: string
  // Set when a guardrail withheld this turn or part of it. Distinct from `error` above, which is
  // styled as a failure and offers Retry: this is the product doing what its operator configured,
  // and retrying the identical message would produce the identical result. Carries the CATEGORIES
  // that fired and never the text that matched - see GuardrailFinding on the backend for why that
  // distinction is load-bearing rather than tidy.
  guardrail?: { stage: string; verdict: string; categories: string[] }
  // Set on the message a "stopped at the step limit" notice is drawn under: the limit the turn
  // reached. From the 'step-limit' event live, and from the transcript after a reload.
  stepLimit?: number
  // Set on a row that stands for what reached the agent from its inbox while it worked - a deployed
  // agent's report or message, an alarm, a background job's result, what the user told a deployed
  // agent. The row has no text of its own.
  agentMail?: AgentMailEntry[]
}

export interface QueuedMessage {
  // Set for a message offered to the running turn - see useChat's send() and 'user-injected'.
  clientId?: string
  content: string
  mentions?: string[]
  attachmentFiles?: string[]
  attachmentNames?: string[]
  skillMentions?: string[]
}

// One file attached to the next outgoing message via ChatComposer's "+" button or drag-and-drop.
// Kept as a PARALLEL array alongside the textarea's text value (mirroring how `mentions` already
// works), not encoded into the message text itself. `id` is a client-local key for the chip's
// React key/removal, unrelated to any server id. `filePath` (set once `status` reaches 'done') is
// the server-side absolute path returned by the upload endpoint - this is what actually gets sent
// as one entry of the chat request's `attachmentPaths`, not the `File` object itself.
export interface ChatAttachment {
  id: string
  file: File
  status: 'uploading' | 'done' | 'failed'
  progress: number
  // Set once uploaded: the stored name a send refers to it by (ChatAttachmentUploadResult).
  fileName?: string
  error?: string
}

export interface Conversation {
  id: string
  title: string
  messageCount: number
  updatedAt: string
  pinned: boolean
}

// A conversation as seen through a share link (GET /api/v1/chat/shared/{token}). Carries no
// conversation id and no token/cost figures by design - see SharedConversationResponse on the
// backend. `messages` stops at the message whose Share button was clicked.
export interface SharedConversation {
  title: string
  sharedByName?: string | null
  sharedAt: string
  messages: ChatMessage[]
}

// Cumulative provider-reported token usage for a conversation, plus the live context-window
// budget - see backend ConversationDetail's javadoc for what each field means (contextTokens is
// a snapshot that drops after condensation, the rest only ever grow).
export interface ConversationUsage {
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  contextTokens: number
  contextTokenLimit: number
  /**
   * The conversation's prompt cache while it is warm (PromptCacheFields on the server): until when,
   * how long each write or read keeps it, and the seconds left when the server answered - which
   * the composer's timer counts down from. All null once it went cold.
   */
  cacheWarmUntil?: string | null
  cacheLifetimeSeconds?: number | null
  cacheSecondsLeft?: number | null
}

// Server-mode-only per-user monthly + rolling 5h/weekly $ quota snapshot (see
// GET /api/v1/users/me/quota, UserTokenQuotaService/RollingWindowChatQuotaGate) - Enterprise-only
// feature (same gate as storage quota - see UserQuotaService.effectiveQuotaBytes), hence the
// discriminated union: `enabled: false` on lower tiers carries no other fields at all (not zeros)
// so a consumer can't accidentally read a quota number without first checking `enabled`.
// effectiveQuotaUsd is the user's admin override if set, else the global default;
// currentCycleSpendUsd resets on the configured quota-reset-day, lifetimeSpendUsd never resets
// (same rollup Admin > Users already shows).
export type UserQuotaStatus =
  | { enabled: false }
  | {
      enabled: true
      effectiveQuotaUsd: number
      currentCycleSpendUsd: number
      lifetimeSpendUsd: number
      cycleResetsAt: string
      fiveHourSpendUsd: number
      fiveHourLimitUsd: number
      fiveHourResetsAt: string
      weeklySpendUsd: number
      weeklyLimitUsd: number
      weeklyResetsAt: string
    }

export interface ChatRequest {
  message: string
  conversationId?: string
  provider?: string
  attachmentFiles?: string[]
}

/**
 * How much the agent may change without asking - chosen beside the model in the composer, sent with
 * every message (ChatRequest.permissionMode). The backend enforces it; see PermissionMode.java. Each
 * chat instance holds its own (chatStore's `permissionMode`).
 */
export type PermissionMode = 'ASK' | 'AUTO' | 'READ_ONLY'

export interface ChatSseEvent {
  type: 'text' | 'thinking' | 'tool-start' | 'tool-result' | 'tool-approval-request' | 'tool-enable-request' | 'user-question' | 'user-injected' | 'condense-start' | 'condense-end'
    | 'usage' | 'title' | 'workflow-run' | 'retry' | 'guardrail' | 'step-limit' | 'prompt-closed' | 'error' | 'session-expired' | 'done'
    | 'agent-status' | 'agent-mail' | 'turn-start'
    // The stream's own first event, no event of the conversation's: which server this is and
    // whether anything still runs there (ChatController.openEvent, chatStream.ts's `opened`).
    | 'open'
  callId: string
  toolName: string
  data: string
  // '' for every event the agent produces itself, and the running workflow STEP's call id on events
  // emitted from inside one. Always
  // present on the wire (the backend defaults it to ''), so this is non-optional here and
  // normalized to undefined where it's consumed.
  parentCallId: string
  // When the event happened, and the server's clock when it was sent - both epoch milliseconds.
  // They differ on a replay, which is why a duration is read off `at` and never off arrival time.
  at?: number
  now?: number
  // The event's place in the conversation's stream, ever increasing. A reconnect replays the turn
  // from its start, and this is how the events already handled are told from the new ones.
  seq?: number
}

export type ApprovalChoice = 'accept_once' | 'accept_all' | 'deny'

// The assistant's multiple-choice questions ('askUser', since 2026-09-29) - the call's arguments.
export interface AskUserOption { label: string; description?: string }
export interface AskUserQuestion { question: string; header?: string; options: AskUserOption[]; multiSelect?: boolean }

/** The steps a turn has used, and how many more a "Continue" gives it. */
export interface StepLimitQuestion { limit: number; more: number }

// Queued by the 'user-question' SSE event; the turn waits server-side until it is answered through
// chatApi.answerQuestion, and the call's 'tool-result' removes it. `sent` is held in the store for
// the same reason PendingApproval's decision is: collapsing a list must not bring back live buttons.
export interface PendingQuestion {
  callId: string
  questions: AskUserQuestion[]
  sent?: boolean
  // Set when this is not the assistant's question but the turn's own: it has used its steps and
  // asks whether to go on (ChatService.askToContinue). Shown by StepLimitPrompt, in the reader's
  // language, and closed by 'prompt-closed' since no tool call stands behind it.
  stepLimit?: StepLimitQuestion
  // The workflow step asking, when it is asked from inside one.
  parentCallId?: string
}

export interface PendingApproval {
  callId: string
  toolName: string
  arguments: string
  // What the user picked, and whether the server has taken it. Held HERE, in the store, rather than
  // in ToolApprovalPrompt's own state: collapsing the tool list unmounts the prompt, and a choice
  // kept in the component came back as live buttons - so the user could not tell whether the click
  // had registered (maintainer's report, 2026-09-26). The row stays until the call's result arrives.
  decision?: ApprovalChoice
  decisionSent?: boolean
  // The workflow step whose call this is, when it is made from inside one.
  parentCallId?: string
  // Wall-clock moment (Date.now()) this approval was queued - set once in chatStore's
  // addPendingApproval (not by the SSE payload itself), and used as the anchor for the five-minute
  // auto-deny countdown (see useApprovalCountdown). Anchoring to this instead of the countdown
  // component's own mount time keeps the remaining time correct even if the row is reconstructed
  // mid-flight (e.g. a resumed conversation with a still-pending approval).
  requestedAt: number
}

// Queued by the backend's 'tool-enable-request' SSE event - fired when a write-capable tool call
// is blocked solely by the calling user's own personal "write tool consent" setting being off
// (not an org-wide admin flag, which rejects immediately with no pause). The backend pauses the
// tool call server-side (same up-to-30-min mechanism as PendingApproval) until the frontend
// resolves it via chatApi.resolveToolEnableDecision. `connectorLabel` is the display name; which
// switch to turn on is one of two shapes. `connectorField` names a connector-tools SETTING - only
// 'connector-management' now. A Jira, Confluence or Bitbucket write names the CONNECTION the
// conversation writes through instead, whose own Write switch is what is off.
export interface PendingEnableRequest {
  callId: string
  toolName: string
  connectorLabel: string
  connectorField?: string
  connectionId?: string
  connectionName?: string
  // The workflow step whose call this is, when it is made from inside one.
  parentCallId?: string
}

// A prompt that was already waiting when the conversation was loaded - the event that asked it,
// as GET /chat/conversations/{id} hands it over (ConversationDetail.PendingPrompt).
export interface PendingPrompt {
  type: 'tool-approval-request' | 'tool-enable-request' | 'user-question'
  callId: string
  toolName?: string | null
  data?: string | null
  parentCallId?: string | null
}

// A conversation as the server hands it to a page that is about to follow its event stream: the
// thread, the stream position it is true as of (handle only events numbered above it), the
// prompts open at that position, and whether work goes on there in the background (agents it
// deployed, background jobs).
export interface ConversationSnapshot extends ConversationUsage {
  messages: ChatMessage[]
  eventSeq?: number
  prompts?: PendingPrompt[]
  background?: boolean
}
