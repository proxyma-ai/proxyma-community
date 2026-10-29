import { createStore, type StoreApi } from 'zustand/vanilla'
import type {
  AgentStatusUpdate, ChatMessage, ToolCallInfo, PendingApproval, PendingEnableRequest, PendingQuestion, PermissionMode, QueuedMessage,
  WorkflowRunSnapshot,
} from '@/features/chat/types/chat.types'
import type { RevealRequest, RevealTarget } from '@/features/chat/utils/threadReveal'

/**
 * ONE CHAT'S STATE - a store per chat instance, since the key-account panel (docs/architecture.html#spec-11-8)
 * put a second chat on a page. It was one global store, and two chats on screen would
 * have written into each other. Made by `createChatStore` for each ChatInstance
 * (features/chat/instance/chatInstance.ts); a component reads the one it belongs to through
 * `useChatStore` in ChatInstanceContext.tsx, never a module-level store.
 */
export interface ChatState {
  conversationId: string | undefined
  messages: ChatMessage[]
  streaming: boolean
  toolCalls: ToolCallInfo[]
  // Messages submitted while a turn was already streaming - drained FIFO once that turn ends
  // (see useChat's processQueue()). Each entry mirrors a `queued: true` bubble already appended
  // to `messages` for immediate visual feedback.
  messageQueue: QueuedMessage[]
  // True while fetching a past conversation's message history (selecting it from the history
  // modal) - lets ChatWindow show a skeleton instead of freezing on stale/empty messages.
  loadingHistory: boolean
  // A write-capable tool call queues an entry here on the backend's 'tool-approval-request' SSE
  // event, removed once its matching 'tool-result' arrives (or on reset). A list rather than a
  // single value because one turn can request approval for several tool calls at once (e.g. "close
  // issues A and B" fires two transitionJiraIssue calls in parallel) - a single value would let a
  // second request silently clobber the first's prompt before the user ever saw it.
  pendingApprovals: PendingApproval[]
  // A write-capable tool call queues an entry here on the backend's 'tool-enable-request' SSE
  // event (the calling user's own personal connector-tools setting is off - a separate gate
  // ahead of pendingApprovals, not the org-wide admin flag which still rejects immediately),
  // removed once its matching 'tool-result' arrives (or on reset). A list for the same reason as
  // pendingApprovals - a turn can request several at once.
  pendingEnableRequests: PendingEnableRequest[]
  // The assistant's questions waiting on the user - see PendingQuestion.
  pendingQuestions: PendingQuestion[]
  // True between a 'condense-start' and 'condense-end' SSE event pair - the backend is folding
  // older messages into a handoff summary (SmartChatMemory.condense()) before/during this turn.
  condensing: boolean
  // The freed-token figure carried by the most recent 'condense-end' (see SmartChatMemory's
  // estimate), or null when there is nothing to report - either none has happened this session, a
  // condensation freed nothing, or the reporting window has elapsed. Held here rather than in the
  // indicator's own state because the event arrives in useChat, several components away from the
  // toolbar button that displays it, and because the indicator unmounts and remounts as the
  // context-usage threshold it normally keys off drops back under the line.
  condenseTokensFreed: number | null
  // How many of the CURRENT turn's `toolCalls` entries have already been claimed by a rendered
  // message (see useChat's 'text' handler) - turn-scoped, reset to 0 at the start of every
  // doSend. ChatWindow uses this (not a whole-conversation recount) to slice out the tool calls
  // still awaiting their own message, which is what makes a pending approval prompt visible.
  attachedToolCallCount: number
  // Estimated thinking tokens of the model call in progress, or null when it is not thinking. Set
  // by the 'thinking' event and cleared the moment its answer text or a tool call arrives - the
  // indicator shows "Thinking N tokens" only while the model is actually thinking.
  thinkingTokens: number | null
  setThinkingTokens: (n: number | null) => void
  // True while a model call has been made and the provider has not answered it yet - what
  // "Waiting for <provider> / <model>" means, and all it means. Set when a turn starts and when the
  // last running tool call returns (the next model call is then in flight); cleared by the
  // provider's first reply of any kind: thinking, text or a tool call. The stream has no "model
  // call started" event, so this is inferred from those - see useChat.
  awaitingModel: boolean
  setAwaitingModel: (v: boolean) => void
  // The provider failed and the call is being made again: the attempt number from the server's
  // 'retry' event, shown beside "Waiting for <model>". Null once the provider answers.
  retryAttempt: number | null
  setRetryAttempt: (n: number | null) => void
  // When the turn's stream last said anything. "Responding" is text that arrived moments ago; a
  // turn quiet for longer, with no tool running and no model call pending, is waiting on neither -
  // which is the only time the star names show (see ChatWindow's waitState).
  lastEventAt: number
  setLastEventAt: (t: number) => void
  // How far this page's clock runs ahead of the server's, in milliseconds - from the `now` every
  // chat event carries. Tool calls are timed on the server's clock (see ToolCallInfo.startedAt),
  // so a counter for one still running is Date.now() minus this, minus its start; without it a
  // laptop five minutes fast showed a call that had just started as five minutes old.
  clockSkewMs: number
  setClockSkewMs: (ms: number) => void
  // What the most recent turn was sent with, so Retry (and the session-expired auto-resend) can
  // reissue it. Lives here rather than in a useRef inside useChat because the error bubble it
  // belongs to lives here: AppShell keys the routed subtree on location.pathname, and a first
  // message navigates / -> /chat -> /chat/{id}, remounting ChatPage twice mid-send. A ref was
  // reset to null by those remounts while the bubble survived in this store, so Retry rendered
  // against a payload that no longer existed and its click returned immediately - no request, no
  // spinner, nothing. Null when this tab has not sent anything (a conversation resumed from the
  // URL or the history modal), which is what hides the button rather than showing a dead one.
  lastSend: QueuedMessage | null
  // Every workflow run visible in this conversation, keyed by runId - the whole run, not a delta,
  // because that is what both the 'workflow-run' event and the REST read carry (see
  // WorkflowRunSnapshot). Held in a map rather than on the message it belongs to so a status
  // change re-renders one table instead of rewriting the message list; the message carries only
  // the id. Populated from the live event stream AND from a fetch on conversation load, and the
  // two are the same object, so a mid-run refresh lands in exactly the state a watcher is in.
  workflowRuns: Record<string, WorkflowRunSnapshot>
  // How much the agent may change without asking - chosen beside the model in the composer, sent
  // with every message (ChatRequest.permissionMode); the backend enforces it (PermissionMode.java).
  // This chat's own: a panel beside the chat page has its own picker. In memory, so every start of
  // the app is back at Ask - a mode that lets the agent change things unasked should be chosen, not
  // inherited from a session somebody has forgotten. Not cleared by `reset`: a new conversation in
  // the same chat keeps the mode on screen.
  permissionMode: PermissionMode
  setPermissionMode: (mode: PermissionMode) => void
  // The last request to show a place in this chat's thread - an agent's name clicked in the agents
  // map (threadReveal.ts). The thread scrolls to it and a deployed agent's panel lights up.
  // A new object per request; a component mounted after it was made treats it as already seen.
  reveal: RevealRequest | null
  revealInThread: (target: RevealTarget) => void
  // What this conversation's stream last said of each agent it deployed ('agent-status'), by agent
  // id. Laid over the agent tree as it was read (agentTree.ts's withLive), so a panel and the agents
  // map move the moment an agent does, not at the next read.
  agentStatus: Record<string, AgentStatusUpdate>
  putAgentStatus: (u: AgentStatusUpdate) => void
  clearAgentStatus: () => void
  setConversationId: (id: string | undefined) => void
  setMessages: (msgs: ChatMessage[] | ((prev: ChatMessage[]) => ChatMessage[])) => void
  setStreaming: (s: boolean) => void
  setToolCalls: (tcs: ToolCallInfo[] | ((prev: ToolCallInfo[]) => ToolCallInfo[])) => void
  setLoadingHistory: (v: boolean) => void
  addPendingApproval: (p: Omit<PendingApproval, 'requestedAt'>) => void
  removePendingApproval: (callId: string) => void
  // Records a decision on one pending approval, or - `callId` null - on every one not yet decided.
  setApprovalDecision: (callId: string | null, decision: PendingApproval['decision'], sent: boolean) => void
  clearPendingApprovals: () => void
  addPendingEnableRequest: (r: PendingEnableRequest) => void
  removePendingEnableRequest: (callId: string) => void
  clearPendingEnableRequests: () => void
  addPendingQuestion: (q: PendingQuestion) => void
  markQuestionSent: (callId: string) => void
  removePendingQuestion: (callId: string) => void
  clearPendingQuestions: () => void
  setCondensing: (v: boolean) => void
  setCondenseTokensFreed: (n: number | null) => void
  setAttachedToolCallCount: (n: number) => void
  setLastSend: (m: QueuedMessage | null) => void
  putWorkflowRun: (run: WorkflowRunSnapshot) => void
  setWorkflowRuns: (runs: WorkflowRunSnapshot[]) => void
  enqueueMessage: (m: QueuedMessage) => void
  dequeueMessage: () => QueuedMessage | undefined
  // Drops a queued message the running turn has already taken - see 'user-injected'.
  removeQueued: (clientId: string) => void
  clearQueue: () => void
  reset: () => void
}

export type ChatStore = StoreApi<ChatState>

export const createChatStore = (): ChatStore => createStore<ChatState>((set, get) => ({
  conversationId: undefined,
  messages: [],
  streaming: false,
  toolCalls: [],
  messageQueue: [],
  loadingHistory: false,
  pendingApprovals: [],
  pendingEnableRequests: [],
  pendingQuestions: [],
  condensing: false,
  condenseTokensFreed: null,
  attachedToolCallCount: 0,
  thinkingTokens: null,
  awaitingModel: false,
  retryAttempt: null,
  lastEventAt: 0,
  clockSkewMs: 0,
  lastSend: null,
  workflowRuns: {},
  permissionMode: 'ASK',
  setPermissionMode: (mode) => set({ permissionMode: mode }),
  reveal: null,
  revealInThread: (target) => set({ reveal: { target } }),
  agentStatus: {},
  putAgentStatus: (u) => set(state => ({ agentStatus: { ...state.agentStatus, [u.agentId]: u } })),
  clearAgentStatus: () => set({ agentStatus: {} }),
  setConversationId: (id) => set({ conversationId: id }),
  setMessages: (msgs) => set({ messages: typeof msgs === 'function' ? msgs(get().messages) : msgs }),
  setStreaming: (s) => set({ streaming: s }),
  setToolCalls: (tcs) => set({ toolCalls: typeof tcs === 'function' ? tcs(get().toolCalls) : tcs }),
  setLoadingHistory: (v) => set({ loadingHistory: v }),
  // Keyed by callId, like the questions below: a prompt open when the conversation was loaded
  // comes with the transcript, and must not become two if the stream then says it again.
  addPendingApproval: (p) => set(state => state.pendingApprovals.some(a => a.callId === p.callId)
    ? state
    : { pendingApprovals: [...state.pendingApprovals, { ...p, requestedAt: Date.now() }] }),
  removePendingApproval: (callId) => set(state => ({
    pendingApprovals: state.pendingApprovals.filter(p => p.callId !== callId),
  })),
  clearPendingApprovals: () => set({ pendingApprovals: [] }),
  setApprovalDecision: (callId, decision, sent) => set(state => ({
    pendingApprovals: state.pendingApprovals.map(p =>
      callId === null
        ? (p.decision ? p : { ...p, decision, decisionSent: sent })
        : p.callId === callId ? { ...p, decision, decisionSent: sent } : p),
  })),
  addPendingEnableRequest: (r) => set(state => ({
    pendingEnableRequests: [...state.pendingEnableRequests.filter(p => p.callId !== r.callId), r],
  })),
  removePendingEnableRequest: (callId) => set(state => ({
    pendingEnableRequests: state.pendingEnableRequests.filter(r => r.callId !== callId),
  })),
  clearPendingEnableRequests: () => set({ pendingEnableRequests: [] }),
  // Keyed by callId and replaced rather than duplicated: the event stream replays a turn from its
  // start when a page reconnects, so the same question can arrive twice.
  addPendingQuestion: (q) => set(state => ({
    pendingQuestions: [...state.pendingQuestions.filter(p => p.callId !== q.callId), q],
  })),
  markQuestionSent: (callId) => set(state => ({
    pendingQuestions: state.pendingQuestions.map(p => p.callId === callId ? { ...p, sent: true } : p),
  })),
  removePendingQuestion: (callId) => set(state => ({
    pendingQuestions: state.pendingQuestions.filter(p => p.callId !== callId),
  })),
  clearPendingQuestions: () => set({ pendingQuestions: [] }),
  setCondensing: (v) => set({ condensing: v }),
  setCondenseTokensFreed: (n) => set({ condenseTokensFreed: n }),
  setAttachedToolCallCount: (n) => set({ attachedToolCallCount: n }),
  setThinkingTokens: (n) => set({ thinkingTokens: n }),
  setAwaitingModel: (v) => set({ awaitingModel: v }),
  setRetryAttempt: (n) => set({ retryAttempt: n }),
  setLastEventAt: (t) => set({ lastEventAt: t }),
  setClockSkewMs: (ms) => set({ clockSkewMs: ms }),
  setLastSend: (m) => set({ lastSend: m }),
  // Last write wins, unconditionally: every snapshot is complete and the backend only ever sends a
  // newer one, so there is nothing to merge and no ordering to defend against.
  putWorkflowRun: (run) => set(state => ({ workflowRuns: { ...state.workflowRuns, [run.runId]: run } })),
  setWorkflowRuns: (runs) => set({ workflowRuns: Object.fromEntries(runs.map(r => [r.runId, r])) }),
  enqueueMessage: (m) => set(state => ({ messageQueue: [...state.messageQueue, m] })),
  dequeueMessage: () => {
    const [next, ...rest] = get().messageQueue
    if (next) set({ messageQueue: rest })
    return next
  },
  removeQueued: (clientId) => set(state => ({ messageQueue: state.messageQueue.filter(m => m.clientId !== clientId) })),
  clearQueue: () => set({ messageQueue: [] }),
  reset: () => set({ conversationId: undefined, messages: [], streaming: false, toolCalls: [], messageQueue: [], loadingHistory: false, pendingApprovals: [], pendingEnableRequests: [], pendingQuestions: [], condensing: false, condenseTokensFreed: null, attachedToolCallCount: 0, thinkingTokens: null, awaitingModel: false, retryAttempt: null, lastEventAt: 0, lastSend: null, workflowRuns: {}, reveal: null, agentStatus: {} }),
}))
