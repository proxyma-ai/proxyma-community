import { http } from '@/services/httpClient'
import { ENV } from '@/config/env'
import { LIST_PAGE_SIZE } from '@/components/ui/kit'
import type {
  ConversationUsage,
  Conversation, ConversationSnapshot, ChatSseEvent, PermissionMode, SharedConversation, WorkflowRunSnapshot,
} from '../types/chat.types'
import type { AgentTreeData } from '../utils/agentTree'
import type { PendingAlarm } from '../utils/alarms'
import { nextReconnectDelay } from '../utils/reconnect'

export interface ConversationsPage {
  items: Conversation[]
  page: number
  hasMore: boolean
  /** Every conversation on the list, so the history numbers its pages (twenty a page). */
  totalItems?: number
}

/**
 * What a history list lists: the chat page's conversations by default (ChatSessionList), or those a
 * page keeps its own record of - a key account's panel lists the conversations BOUND to the account.
 */
export interface ConversationListSource {
  /** Under `keys.conversations()`, so whatever refreshes the chat page's list refreshes this one. */
  queryKey: readonly unknown[]
  list: (page: number) => Promise<ConversationsPage>
  /** Where these conversations are held (ConversationSurface) - which "working" dots to ask for. */
  surface?: string
}

export const chatApi = {
  // The chat page's conversations - never one another page holds (ConversationSurface).
  listConversations: (page: number, size = LIST_PAGE_SIZE) =>
    http.get<ConversationsPage>('/api/v1/chat/conversations', { params: { page, size } }).then(r => r.data),
  getConversation: (id: string) =>
    http.get<ConversationSnapshot>(`/api/v1/chat/conversations/${id}`).then(r => r.data),
  // The ids alone of the transcript's last `tail` messages, in order - what a page needs after a turn.
  getMessageIds: (id: string, tail: number) =>
    http.get<(number | null)[]>(`/api/v1/chat/conversations/${id}/message-ids`, { params: { tail } }).then(r => r.data),
  // The MCP servers this person may give a message - their skills, named "MCP: <server>".
  listMcpServers: () =>
    http.get<{ name: string; description: string }[]>('/api/v1/chat/mcp-servers').then(r => r.data),
  // The usage meter's six numbers, without the transcript.
  getConversationUsage: (id: string) =>
    http.get<ConversationUsage>(`/api/v1/chat/conversations/${id}/usage`).then(r => r.data),
  deleteConversation: (id: string) =>
    http.delete(`/api/v1/chat/conversations/${id}`),
  setPinned: (id: string, pinned: boolean) =>
    http.patch(`/api/v1/chat/conversations/${id}/pin`, { pinned }),
  // Mints (or re-returns) the read-only share token covering this conversation up to `messageId`.
  // Idempotent per (conversation, message) on the backend, so re-sharing the same reply yields the
  // same token rather than a second live link.
  shareConversation: (id: string, messageId: number): Promise<string> =>
    http.post<{ token: string }>(`/api/v1/chat/conversations/${id}/share`, { messageId })
      .then(r => r.data.token),
  // The viewer's side - 404s once the underlying conversation is deleted, which is exactly how a
  // share link expires (see ConversationShare on the backend).
  getSharedConversation: (token: string) =>
    http.get<SharedConversation>(`/api/v1/chat/shared/${token}`).then(r => r.data),
  stopChat: (conversationId: string) =>
    http.post(`/api/v1/chat/conversations/${conversationId}/stop`),
  // The whole tree of agents this conversation belongs to - its root, every deployed agent at every
  // depth, where each stands and what it spent (utils/agentTree.ts). Asked of any conversation of
  // the tree; `self` is set when that conversation is a deployed agent's own.
  getAgentTree: (conversationId: string) =>
    http.get<AgentTreeData>(`/api/v1/chat/conversations/${conversationId}/agent-tree`).then(r => r.data),
  // The person's control over any agent of the tree. 400 with `error` when it is refused (an agent
  // already ended, a resume of one not paused).
  controlAgent: (conversationId: string, agentId: string, action: 'pause' | 'resume' | 'stop' | 'dismiss') =>
    http.post(`/api/v1/chat/conversations/${conversationId}/agent-tree/${agentId}/${action}`),
  // The alarms the conversation's agent set, still to go off.
  getAlarms: (conversationId: string) =>
    http.get<PendingAlarm[]>(`/api/v1/chat/conversations/${conversationId}/alarms`).then(r => r.data),
  cancelAlarm: (conversationId: string, alarmId: string) =>
    http.delete(`/api/v1/chat/conversations/${conversationId}/alarms/${alarmId}`),
  resolveToolApproval: (conversationId: string, callId: string, decision: 'accept_once' | 'accept_all' | 'deny') =>
    http.post(`/api/v1/chat/conversations/${conversationId}/tool-approval`, { callId, decision }),
  resolveToolEnableDecision: (conversationId: string, callId: string, decision: 'enabled' | 'keep_disabled') =>
    http.post(`/api/v1/chat/conversations/${conversationId}/tool-enable-decision`, { callId, decision }),
  // Hands a message to the running turn (ChatService.inject): 202 taken, 409 no turn to join, 422
  // withheld by the input guardrail (with the refusal as `error`).
  injectMessage: (conversationId: string, text: string, clientId: string) =>
    http.post(`/api/v1/chat/conversations/${conversationId}/inject`, { text, clientId }),
  // The user's answers to the assistant's questions (askUser) - or `skipped` to decline them.
  // An answer is a list where the question took several picks (utils/askUser.ts).
  answerQuestion: (conversationId: string, callId: string,
                   answers: { question: string; answer: string | string[] }[], skipped = false) =>
    http.post(`/api/v1/chat/conversations/${conversationId}/question-answer`, { callId, answers, skipped }),
  rewindConversation: (conversationId: string, messageId: number) =>
    http.post(`/api/v1/chat/conversations/${conversationId}/rewind`, { messageId }),
  // Manual "condense now" (see the chat input's condense button) - 409s while a turn is active,
  // matching rewindConversation's identical guard/status code.
  //
  // Returns 202 as soon as the condensation has been ACCEPTED, not once it has finished: the
  // summarizer call behind it can run for minutes, and the caller follows it on the conversation's
  // event stream (condense-start -> condense-end -> done) exactly as it follows a chat turn. See
  // ChatController.condenseNow.
  condenseNow: (conversationId: string) =>
    http.post(`/api/v1/chat/conversations/${conversationId}/condense`),
  // Deletes the anonymous thumbs rating auto-submitted for this message, when the user un-clicks
  // the thumb or flips to the other one. Never touches a feedback they actually wrote (those carry
  // a comment - see ChatService.retractRating).
  retractRating: (conversationId: string, messageId: number) =>
    http.delete(`/api/v1/chat/conversations/${conversationId}/messages/${messageId}/rating`),

  // Used on mount (see useChat's resumeConversation) to tell whether work is still running
  // server-side for a conversation id that arrived via the URL - see
  // ../proxyma-docs/architecture/chat-reactivity.md's refresh-resilience section.
  //
  // `condensing` is reported separately from `active` because a reloaded tab cannot recover it any
  // other way: the condense-start event it would otherwise have learned it from was emitted before
  // this client existed, and while the sink replays, it is reaped after 45 idle minutes. Both flags
  // are coalesced to false so a response from an older backend (which sends only `active`) still
  // reads correctly rather than yielding undefined.
  // Every workflow run in this conversation, for a tab that did not watch them happen - a reload,
  // a second browser, a conversation picked from history. Returns exactly the object the
  // 'workflow-run' SSE event carries, so both paths feed the same store and the same table.
  //
  // This is why run state lives on the server at all: reconstructing it from replayed events would
  // mostly work, until the event sink is reaped after 45 idle minutes, or the run outlives the tab.
  getWorkflowRuns: (conversationId: string) =>
    http.get<WorkflowRunSnapshot[]>(`/api/v1/chat/conversations/${conversationId}/workflow-runs`)
      .then(r => r.data),

  // The caller's conversations with a turn running now, the most recently started first - the chat
  // page's, or one surface's.
  getActiveConversations: (surface?: string): Promise<string[]> =>
    http.get<{ ids?: string[] }>('/api/v1/chat/conversations/active', { params: { surface } }).then(r => r.data.ids ?? []),

  // `background`: agents it deployed are working, a report is on its way to its agent, or a
  // background job runs - not a turn, but a reason to follow the conversation's event stream.
  getConversationStatus: (conversationId: string): Promise<{ active: boolean; condensing: boolean; background: boolean }> =>
    http.get<{ active?: boolean; condensing?: boolean; background?: boolean }>(`/api/v1/chat/conversations/${conversationId}/status`)
      .then(r => ({
        active: r.data.active === true, condensing: r.data.condensing === true, background: r.data.background === true,
      })),

  // skillMentions is sent per-message, exactly like `mentions` for documents, but has a lasting
  // effect: each picked skill's full instructions are loaded into the conversation's history and
  // apply from then on (see ChatService.applyPickedSkills on the backend).
  //
  // `conversationId` is the CLIENT's when it names one - the server uses it as given and mints its
  // own only when none is named (ChatController.chat). `surface` says where the conversation is held
  // when it is not the chat page (ConversationSurface on the backend) and counts only on the message
  // that creates the conversation; `permissionMode` is the chat's own (chatStore).
  startChat: (message: string, conversationId?: string, mentions?: string[], attachmentFiles?: string[], skillMentions?: string[],
              options: { permissionMode?: PermissionMode; surface?: string } = {}): Promise<string> =>
    http.post<{ conversationId: string }>('/api/v1/chat', {
      message, conversationId, mentions, attachments: attachmentFiles, skillMentions,
      permissionMode: options.permissionMode ?? 'ASK',
      surface: options.surface,
    }).then(r => r.data.conversationId),

  subscribeChatEvents: (
    conversationId: string,
    onEvent: (event: ChatSseEvent) => void,
    // `more`: the turn is over and the conversation is not - agents it deployed are still working,
    // or a report is on its way. The stream stays open, and stays the caller's to
    // close (the function this returns).
    onDone: (more: boolean) => void,
    // `connectionLost`: the stream itself could not be kept, as opposed to the turn failing.
    onError: (err: string, connectionLost?: boolean) => void,
    onSessionExpired: () => void,
    // Passed IN, already translated. This module has no React context and cannot call a hook, and
    // the string is the one thing here a reader sees - so the caller, which has `t`, supplies it.
    connectionLostMessage: string,
    // The last event the caller already holds: the transcript it loaded is true as of it, or it
    // was the last event of a stream this one replaces. Everything at or below it is skipped.
    afterSeq = -1,
  ): (() => void) => {
    // Native EventSource exposes no HTTP status on error (a 429 from the rate limiter looks
    // identical to a genuine network blip), so a failed connection is retried generically with
    // capped exponential backoff instead of failing hard on the very first error - only once
    // the budget is spent does the caller see "Connection lost". The budget outlasts an
    // application restart (nextReconnectDelay, reconnect.ts).

    let es: EventSource | null = null
    let retryTimer: ReturnType<typeof setTimeout> | null = null
    let attempt = 0
    // When the stream was last open, or first tried: the reconnect budget runs from here.
    let downSince = Date.now()
    let closed = false
    // The last event handed on. The server replays the whole turn to every connection, so a
    // RECONNECT - which the retry below makes by itself - delivers everything already handled a
    // second time: the answer's text appended to itself, every tool row twice. Each event carries
    // its sequence number, and one at or below the last seen has been handled.
    let lastSeq = afterSeq

    const connect = () => {
      es = new EventSource(
        `${ENV.apiBaseUrl}/api/v1/chat/events/${conversationId}`,
        { withCredentials: true },
      )

      es.addEventListener('chat', (e: MessageEvent) => {
        try {
          const parsed: ChatSseEvent = JSON.parse(e.data)
          if (typeof parsed.seq === 'number') {
            if (parsed.seq <= lastSeq) return
            lastSeq = parsed.seq
          }
          onEvent(parsed)
          if (parsed.type === 'done') {
            attempt = 0
            const more = parsed.data === 'more'
            if (!more) {
              closed = true
              es?.close()
            }
            onDone(more)
          } else if (parsed.type === 'error') {
            attempt = 0
            es?.close()
            onError(parsed.data)
          } else if (parsed.type === 'session-expired') {
            attempt = 0
            es?.close()
            onSessionExpired()
          }
        } catch {
          // ignore parse errors
        }
      })

      // A long-running turn can hit several ordinary transient reconnects (proxy blips, brief
      // network hiccups) that each succeed individually but, without this, would still count
      // toward the retry budget since the 'chat' listener above only resets `attempt` on a terminal
      // event - never just because the connection came back. onopen fires on the initial
      // connect AND on every successful reconnect, so it ties the retry budget to actual
      // connection health instead, preventing the budget from exhausting mid-stream on a
      // response that's simply taking a while.
      es.onopen = () => {
        attempt = 0
        downSince = Date.now()
      }

      es.onerror = () => {
        es?.close()
        if (closed) return
        if (attempt === 0) downSince = Date.now()
        const delay = nextReconnectDelay(attempt, downSince, Date.now())
        if (delay === null) {
          onError(connectionLostMessage, true)
          return
        }
        attempt += 1
        retryTimer = setTimeout(connect, delay)
      }
    }

    connect()

    return () => {
      closed = true
      if (retryTimer) clearTimeout(retryTimer)
      es?.close()
    }
  },
}
