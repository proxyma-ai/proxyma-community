import { FEATURES } from '@/config/features'
import { useCallback } from 'react'
import { useStore } from 'zustand'
import { useQueryClient } from '@tanstack/react-query'
import type { InfiniteData } from '@tanstack/react-query'
import { AxiosError } from 'axios'
import { keys } from '@/services/queryKeys'
import { settleUnfinishedToolCalls } from '../utils/settleUnfinishedToolCalls'
import { appendSettled, applyStepLimit, applyText, claimToolCalls, idlessTail, lastSettled, placeInjected, withServerIds } from '../utils/threadOps'
import { parseQuestions, stepLimitOf } from '../utils/askUser'
import {
  accept, carriedOnReload, ended, follow, keepClose, missed, opened, parseOpen, resumeAfter, resumePoint,
  stop as stopFollowing,
} from '../utils/chatStream'
import { serverText } from '@/i18n/serverText'
import {
  agentDone, agentText, agentToolFinished, agentToolStarted, anyAgentRunning, findAgent, startAgent,
  withAgent, withAgentInMessages,
} from '../utils/subAgent'
import { chatApi } from '../services/chatApi'
import { useChatInstance } from '../instance/ChatInstanceContext'
import { useAuthStore } from '@/store/authStore'
import { useToastStore } from '@/store/toastStore'
import { useTranslation, type Translate } from '@/i18n'
import { refreshAccessToken } from '@/services/httpClient'
import type {
  ChatSseEvent, ConversationUsage, ChatMessage, PendingPrompt, SubAgentInfo, SubAgentStatus,
  ToolCallInfo, WorkflowRunSnapshot,
} from '../types/chat.types'
import type { ConversationsPage } from '../services/chatApi'

function stripHtml(html: string, t: Translate): string {
  const clean = html.replace(/<!--[\s\S]*?-->/g, '')
  const title = clean.match(/<title>(.*?)<\/title>/i)
  if (title?.[1]?.trim()) return title[1].trim()
  const text = clean.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim()
  return text || t('Server error')
}

function extractErrorMessage(e: unknown, t: Translate, locale?: string): string {
  if (e instanceof AxiosError) {
    const data = e.response?.data
    if (typeof data === 'string' && data.trim()) {
      const trimmed = data.trim()
      if (trimmed.startsWith('<')) return stripHtml(trimmed, t)
      return trimmed
    }
    if (data && typeof data === 'object') {
      const obj = data as Record<string, unknown>
      // ChatQuotaExceededException's ProblemDetail carries `window`/`resetsAt` extension
      // properties alongside `detail` (see ChatExceptionHandler/ChatQuotaExceededException on
      // the backend) - build a clearer, localized message from those instead of surfacing the
      // backend's raw sentence (which embeds an unformatted ISO instant).
      if (e.response?.status === 429 && typeof obj.window === 'string' && typeof obj.resetsAt === 'string') {
        // The guest allowance is the exception: its own sentence embeds no ISO instant and ends
        // with the thing a signed-out visitor actually needs to be told ("Sign in to keep going"),
        // which rewriting it here would throw away. See GuestChatQuotaService.checkAllowed.
        if (obj.window === 'guest' && typeof obj.detail === 'string' && obj.detail.trim()) {
          return obj.detail.trim()
        }
        // In the app's language, not the browser's (CHAT-32).
        const time = new Date(obj.resetsAt).toLocaleString(locale)
        if (obj.window === '5h') return t("You've reached your session usage limit. Try again after {time}.", { time })
        if (obj.window === 'weekly') return t("You've reached your weekly usage limit. Try again after {time}.", { time })
        if (obj.window === 'monthly') return t("You've reached your monthly usage limit. Try again after {time}.", { time })
        return t("You've reached your usage limit. Try again after {time}.", { time })
      }
      if (typeof obj.message === 'string' && obj.message.trim()) return obj.message.trim()
      if (typeof obj.error === 'string' && obj.error.trim()) return obj.error.trim()
      if (typeof obj.detail === 'string' && obj.detail.trim()) return obj.detail.trim()
    }
    const status = e.response?.status
    if (status) {
      // Two whole sentences rather than one with a fallback word dropped into a gap. `statusText`
      // is the server's own reason phrase and stays as it arrives - a wire value, not prose.
      const statusText = e.response?.statusText
      return statusText
        ? t('Server returned {status} ({statusText})', { status, statusText })
        : t('Server returned {status}', { status })
    }
  }
  if (e instanceof Error && e.message) return e.message
  return t('An unexpected error occurred.')
}

// A fetched history row as this store holds it. Extracted because setConversationId and
// resumeConversation below load the same thing for different reasons and were already keeping two
// identical copies of this mapping - a third field to add to both is a good moment to stop.
function toStoreMessage(m: ChatMessage): ChatMessage {
  return {
    role: (m.role as string).toLowerCase() as 'user' | 'assistant',
    content: m.content,
    id: m.id,
    // A call read back from storage cannot still be running - see the util.
    toolCalls: settleUnfinishedToolCalls(m.toolCalls),
    attachmentNames: m.attachmentNames,
    mentions: m.mentions,
    skillMentions: m.skillMentions,
    // Set by the backend on the user message that invoked a workflow, so a reloaded conversation
    // puts the step table back exactly where the live one had it - see WorkflowRun.triggerMessageId.
    workflowRunId: m.workflowRunId,
    stepLimit: m.stepLimit ?? undefined,
    subAgentReports: m.subAgentReports ?? undefined,
  }
}

// THE CHAT'S STREAM, and the state its handlers keep, belong to the CHAT INSTANCE this hook is
// rendered in (features/chat/instance) - not to refs of one useChat call, since the 0.27.0 feedback
// (messages and tool rows shown twice): AppShell remounts the chat page on every pathname change,
// and a stream opened before the remount went on following the conversation beside the one the
// next mount opened. They were module-level for that reason until the key-account panel put a
// second chat on a page; an instance outlives a remount the same way. See chatStream.ts.
//
// newSegmentPending, in particular: the backend's ReAct loop can run several LLM iterations per
// turn (text -> tool calls -> text -> tool calls -> ... -> final text), each streamed as its own run
// of 'text' deltas, but the SSE stream carries no explicit "new iteration" event. A tool call only
// ever starts once its iteration's full text has already streamed, so "a tool-start happened since
// the current message's text was last appended" is what marks the next 'text' event as belonging to
// a NEW iteration rather than a continuation - see newSegmentPendingRef's use below.

const AGENT_STATUSES: SubAgentStatus[] = ['running', 'completed', 'partial', 'failed', 'stopped']
const agentStatus = (value: unknown): SubAgentStatus =>
  AGENT_STATUSES.includes(value as SubAgentStatus) ? value as SubAgentStatus : 'failed'

export function useChat() {
  const qc = useQueryClient()
  const { t, locale } = useTranslation()
  // The chat this hook drives - the chat page's, or a panel's (ChatInstanceProvider).
  const instance = useChatInstance()
  const {
    stream: page, textAcc: textAccRef, manualCondense: manualCondenseRef,
    newSegmentPending: newSegmentPendingRef, reload: reloadRef, resume: resumeRef, offered: offeredRef,
  } = instance
  const getState = instance.store.getState
  const store = useStore(instance.store)
  // What the last turn was sent with lives in the store, not in a ref here - see chatStore's
  // `lastSend`. A ref did not survive the remounts AppShell performs on a pathname change, which
  // is every first message (/ -> /chat -> /chat/{id}), so Retry rendered against nothing.

  // The SSE event-handling logic for one turn - shared by doSend (a turn this tab itself just
  // started) and resumeConversation (reattaching to a turn already running server-side, e.g.
  // after a mid-turn refresh - see ../proxyma-docs/architecture/chat-reactivity.md). Defined ahead of doSend
  // but still able to call it in the session-expired branch below: neither is actually invoked
  // until well after this render's synchronous setup completes, by which point both `const`
  // bindings exist - the same forward-reference pattern doSend/processQueue already relies on.
  // `resumed`: picking up a stream a hidden tab paused - its first new event says whether anything
  // was missed meanwhile (chatStream.ts's `missed`).
  const attachStream = useCallback((convId: string, afterSeq = -1, resumed = false) => {
    // One change to a sub-agent, wherever the call that started it is held: the turn's list, or
    // the message that claimed it - several turns up, for one working in the background.
    const updateAgent = (agentId: string, change: (agent: SubAgentInfo) => SubAgentInfo) => {
      store.setToolCalls(prev => withAgent(prev, agentId, change))
      store.setMessages(prev => withAgentInMessages(prev, agentId, change))
    }
    const knownAgent = (agentId: string | undefined) => {
      if (!agentId) return undefined
      const state = getState()
      return findAgent(state.messages, state.toolCalls, agentId)
    }
    // Nothing left to listen for: no turn, and no sub-agent working. The stream is closed here
    // rather than by the 'done' that ended the last turn, which said more was coming.
    const closeIfIdle = () => {
      const state = getState()
      if (state.streaming || anyAgentRunning(state.messages, state.toolCalls)) return
      stopFollowing(page)
      qc.invalidateQueries({ queryKey: keys.activeConversations() })
    }
    // Run at the END of a turn, from both the done and the error path. A tool row leaves its
    // running animation on a 'tool-result' event and on nothing else, so a call the turn never
    // heard back about goes on pulsing after the input has been re-enabled - reporting on work
    // that nothing will ever report on. Reloading the conversation was the only thing that
    // settled it, because the history path already did exactly this.
    //
    // FIRST, the calls no message ever claimed get a row of their own. They are drawn from the
    // turn's own list until then, and that list is emptied when the next turn starts - so the last
    // steps of a turn that ended without an answer (stopped, out of steps, a provider error)
    // disappeared the moment the user sent anything else.
    const settleToolActivity = () => {
      const unclaimed = takeUnclaimedToolCalls()
      store.setMessages(prev => claimToolCalls(prev, unclaimed))
      store.setToolCalls(prev => settleUnfinishedToolCalls(prev) ?? prev)
      store.setMessages(prev => prev.map(m => (
        m.toolCalls ? { ...m, toolCalls: settleUnfinishedToolCalls(m.toolCalls) } : m
      )))
    }
    // The turn's tool calls that no message has claimed yet, marked claimed from here on.
    const takeUnclaimedToolCalls = () => {
      const all = getState().toolCalls
      const unclaimed = all.slice(getState().attachedToolCallCount)
      store.setAttachedToolCallCount(all.length)
      return unclaimed
    }
    // One change to a RUNNING tool call wherever it is held: the turn's list, and the message that
    // has claimed it by now, if any.
    //
    // Running only, because a call id is not unique for long. Some OpenAI-compatible servers number
    // their calls ("search:0") and reuse the number on the next round, so matching on the id alone
    // wrote a later call's result over an earlier call's row - a finished search showing the
    // answer to a different one. Every event that lands here is about a call still in flight.
    const updateToolCall = (callId: string, change: (tc: ToolCallInfo) => ToolCallInfo) => {
      const apply = (tc: ToolCallInfo) => (tc.callId === callId && !tc.done ? change(tc) : tc)
      store.setToolCalls(prev => prev.map(apply))
      store.setMessages(prev => prev.map(m => (
        m.toolCalls?.some(tc => tc.callId === callId && !tc.done) ? { ...m, toolCalls: m.toolCalls.map(apply) } : m
      )))
    }
    // ONE stream at a time, and only ever the open conversation's. Conversations run side by side
    // since 2026-09-28: a turn left running goes on server-side while another is open. This used to
    // replace the stored unsubscribe without calling it, so the turn left behind kept writing its
    // text, tool calls and "done" into whichever conversation was on screen. The page's ONE stream,
    // whichever instance of this hook opened the last - see chatStream.ts.
    const generation = follow(page, convId, afterSeq, { resumed })
    const unsub = chatApi.subscribeChatEvents(
      convId,
      (event: ChatSseEvent) => {
          // Belt and braces for the same thing: an event already in flight when the stream was
          // closed must not land in the conversation that replaced it.
          if (getState().conversationId !== convId) return
          // The stream's first event, every time it connects: which server this is, and whether
          // anything still runs there. When what this page follows is gone - the server restarted
          // under a turn, or nothing runs and every event is already here - nothing more will ever
          // come, and the conversation is loaded as the server has it: the turn ends there as a
          // stopped one does, and what was typed meanwhile is sent as a turn of its own.
          if (event.type === 'open') {
            const open = parseOpen(event.data)
            if (open && opened(page, generation, open)) {
              stopFollowing(page)
              qc.invalidateQueries({ queryKey: keys.conversation(convId) })
              qc.invalidateQueries({ queryKey: keys.conversationUsage(convId) })
              reloadRef.current?.(convId, { lost: true })
            }
            return
          }
          // Picked up after the tab was hidden, and the server no longer holds what came between:
          // nothing of this stream is applied to a thread that lacks it - the conversation is loaded
          // whole instead, as a page coming back to it would.
          if (missed(page, generation, event.seq)) {
            stopFollowing(page)
            qc.invalidateQueries({ queryKey: keys.conversation(convId) })
            qc.invalidateQueries({ queryKey: keys.conversationUsage(convId) })
            reloadRef.current?.(convId)
            return
          }
          // Nor an event from a stream that has been replaced.
          if (!accept(page, generation, event.seq)) return
          // What a sub-agent does in the background is not the turn speaking: it must not reset
          // the turn's own quiet clock (which decides what the row under the thread says).
          if (!event.type.startsWith('sub-agent-') || event.type === 'sub-agent-reported') store.setLastEventAt(Date.now())
          if (typeof event.now === 'number') store.setClockSkewMs(Date.now() - event.now)
          // Any reply from the provider ends a retry; a 'retry' event below starts one.
          if (event.type === 'thinking' || event.type === 'text' || event.type === 'tool-start') {
            store.setRetryAttempt(null)
          }
          switch (event.type) {
            case 'retry': {
              // "Retry 2" - the provider failed and the same call is being made again, so the wait
              // is on the provider once more.
              const n = Number.parseInt(event.data.replace(/\D+/g, ''), 10)
              store.setRetryAttempt(Number.isFinite(n) ? n : 1)
              store.setThinkingTokens(null)
              store.setAwaitingModel(true)
              break
            }
            case 'thinking': {
              // The model call in progress is thinking; data is its estimated token count so far.
              // The thinking TEXT never reaches the client - only that it is happening, and how much.
              const tokens = Number.parseInt(event.data, 10)
              store.setAwaitingModel(false)
              store.setThinkingTokens(Number.isFinite(tokens) ? tokens : 0)
              break
            }
            case 'text': {
              store.setAwaitingModel(false)
              store.setThinkingTokens(null)
              // A tool call fired since we last appended text - this delta belongs to a new
              // ReAct iteration, so it opens a new assistant message instead of extending the
              // previous one (see newSegmentPendingRef's declaration above for why).
              const isNewSegment = newSegmentPendingRef.current
              textAccRef.current = isNewSegment ? event.data : textAccRef.current + event.data
              // Starting a message (the turn's first, or a new iteration's) picks up whatever tool
              // calls happened since the previous message claimed its own, so they render attached
              // above this message rather than vanishing or double-counting. Placed by applyText,
              // which keeps the answer above any message queued meanwhile - see threadOps.
              const state = getState()
              const unclaimed = state.toolCalls.slice(state.attachedToolCallCount)
              const applied = applyText(state.messages, textAccRef.current, isNewSegment, unclaimed)
              store.setMessages(applied.messages)
              if (applied.opened) store.setAttachedToolCallCount(state.toolCalls.length)
              newSegmentPendingRef.current = false
              break
            }
            case 'tool-start': {
              // A sub-agent's own call goes into its panel, in the order it works - never into the
              // turn's list, which is the main agent's and is emptied when the next turn starts.
              if (knownAgent(event.parentCallId)) {
                updateAgent(event.parentCallId, agent => agentToolStarted(agent, {
                  kind: 'tool', callId: event.callId, toolName: event.toolName, arguments: event.data,
                  done: false, startedAt: event.at,
                }))
                break
              }
              store.setAwaitingModel(false)
              store.setThinkingTokens(null)
              // parentCallId arrives as '' for everything the main agent does; normalized to
              // undefined here so the rendering layer only has to check for presence.
              const newCall = {
                callId: event.callId, toolName: event.toolName, arguments: event.data, done: false,
                parentCallId: event.parentCallId || undefined,
                startedAt: event.at,
              }
              store.setToolCalls(prev => [...prev, newCall])
              // Never attach a tool call to the message that's currently open - a tool call
              // always fires as a *consequence* of that message's text (the ReAct loop only
              // learns of it after that iteration's full text has streamed), so attaching it
              // there would show the checklist above text that hasn't caused it yet. It stays
              // unflushed in the global list (rendered live by ChatWindow's "pending" section)
              // until the next 'text' event creates a new message and claims it.
              newSegmentPendingRef.current = true
              break
            }
            case 'tool-result': {
              store.removePendingApproval(event.callId)
              store.removePendingEnableRequest(event.callId)
              store.removePendingQuestion(event.callId)
              if (knownAgent(event.parentCallId)) {
                updateAgent(event.parentCallId, agent => agentToolFinished(agent, event.callId, event.data, event.at))
                break
              }
              // On the turn's list AND on whichever message ended up claiming this call (any
              // message, not just the last one - a turn can span several).
              updateToolCall(event.callId, tc => ({ ...tc, result: event.data, done: true, endedAt: event.at }))
              // The last call still running has returned: the loop now hands the results back to
              // the model, so the wait is on the provider again. Not while any call still runs - a
              // tool row with its own spinner is what that wait looks like.
              if (getState().toolCalls.every(tc => tc.done)) store.setAwaitingModel(true)
              break
            }
            case 'tool-approval-request': {
              store.addPendingApproval({
                callId: event.callId, toolName: event.toolName, arguments: event.data,
                parentCallId: event.parentCallId || undefined,
              })
              break
            }
            case 'tool-enable-request': {
              try {
                const payload = JSON.parse(event.data) as {
                  connectorLabel: string; connectorField?: string; connectionId?: string; connectionName?: string
                }
                store.addPendingEnableRequest({
                  callId: event.callId, toolName: event.toolName,
                  connectorLabel: payload.connectorLabel, connectorField: payload.connectorField,
                  connectionId: payload.connectionId, connectionName: payload.connectionName,
                  parentCallId: event.parentCallId || undefined,
                })
              } catch { /* ignore parse errors, mirrors the 'usage' case's own try/catch style */ }
              break
            }
            case 'user-injected': {
              // The running turn has just taken a message the user typed while it worked. It moves
              // to this point in the thread - after what the agent has said so far - and the
              // agent's next words open a new message below it.
              const clientId = event.callId
              store.removeQueued(clientId)
              // The steps the agent took before reading it belong ABOVE it. Tool calls wait
              // unclaimed until the next text opens a message (see 'text'), which would be after
              // the injected bubble - so they are claimed here, by a row of their own.
              const unclaimed = takeUnclaimedToolCalls()
              store.setMessages(prev => placeInjected(claimToolCalls(prev, unclaimed), clientId, event.data))
              newSegmentPendingRef.current = true
              break
            }
            case 'step-limit': {
              // The turn ran out of steps and stopped. The notice goes under the answer the last
              // step wrote, or on a row of its own after whatever it did last - see applyStepLimit.
              const limit = Number.parseInt(event.data, 10)
              if (!Number.isFinite(limit)) break
              store.setAwaitingModel(false)
              const unclaimed = takeUnclaimedToolCalls()
              const answering = !newSegmentPendingRef.current && textAccRef.current !== ''
              store.setMessages(prev => applyStepLimit(prev, limit, unclaimed, answering))
              break
            }
            case 'sub-agent-start': {
              // callId is the `delegate` call that started it; from here on it is known by its own
              // id (parentCallId), which every later event about it carries.
              try {
                const d = JSON.parse(event.data) as { agentId: string; mode?: string; title?: string; timeoutAt?: number }
                const agent: SubAgentInfo = {
                  id: d.agentId, mode: d.mode === 'async' ? 'async' : 'sync', status: 'running',
                  title: d.title, startedAt: event.at, timeoutAt: d.timeoutAt, items: [],
                }
                store.setToolCalls(prev => startAgent(prev, event.callId, agent))
                // A page that joined late may hold the call in a message already.
                store.setMessages(prev => {
                  if (findAgent(prev, getState().toolCalls, agent.id)) return prev
                  return prev.map(m => {
                    if (!m.toolCalls) return m
                    const next = startAgent(m.toolCalls, event.callId, agent)
                    return next === m.toolCalls ? m : { ...m, toolCalls: next }
                  })
                })
              } catch { /* ignore parse errors, mirrors the 'usage' case's own try/catch style */ }
              break
            }
            case 'sub-agent-wait': {
              // Its model call is out.
              updateAgent(event.parentCallId, agent => ({ ...agent, awaitingModelSince: event.at, thinkingTokens: undefined }))
              break
            }
            case 'sub-agent-thinking': {
              const tokens = Number.parseInt(event.data, 10)
              updateAgent(event.parentCallId, agent => ({
                ...agent, awaitingModelSince: undefined, thinkingTokens: Number.isFinite(tokens) ? tokens : 0,
              }))
              break
            }
            case 'sub-agent-text': {
              // The WHOLE of that paragraph so far - see agentText.
              try {
                const d = JSON.parse(event.data) as { segment: number; text: string }
                updateAgent(event.parentCallId, agent => agentText(agent, d.segment, d.text))
              } catch { /* ignore parse errors, mirrors the 'usage' case's own try/catch style */ }
              break
            }
            case 'sub-agent-done': {
              try {
                const d = JSON.parse(event.data) as { status?: string; report?: string; endedAt?: number; wakes?: boolean }
                updateAgent(event.parentCallId, agent =>
                  agentDone(agent, agentStatus(d.status), d.report ?? '', d.endedAt ?? event.at))
                // The main agent is about to be told ('wakes'): a turn follows, or the running one
                // takes it. Otherwise this may have been the last thing worth listening for.
                if (!d.wakes) closeIfIdle()
              } catch { /* ignore parse errors, mirrors the 'usage' case's own try/catch style */ }
              break
            }
            case 'sub-agent-reported': {
              // A background sub-agent's report has just been put in front of the main agent. The
              // thread says so at this point: what the agent says next is its reaction to it. As
              // with a message typed mid-turn, the steps taken before it stay above it.
              try {
                const d = JSON.parse(event.data) as { title?: string; status?: string; kind?: string }
                const unclaimed = takeUnclaimedToolCalls()
                store.setMessages(prev => appendSettled(claimToolCalls(prev, unclaimed), {
                  role: 'assistant', content: '',
                  subAgentReports: [{ agentId: event.callId, title: d.title ?? '', status: agentStatus(d.status),
                    ...(d.kind === 'message' ? { kind: 'message' as const } : {}) }],
                }))
                newSegmentPendingRef.current = true
              } catch { /* ignore parse errors, mirrors the 'usage' case's own try/catch style */ }
              break
            }
            case 'turn-start': {
              // A turn nobody sent a message to start: a sub-agent reported while the conversation
              // was idle, and the main agent is answering it. Everything doSend sets up for a turn
              // this page started, minus the message.
              if (getState().streaming) break
              manualCondenseRef.current = false
              store.setStreaming(true)
              store.setThinkingTokens(null)
              store.setAwaitingModel(true)
              store.setToolCalls([])
              store.setRetryAttempt(null)
              store.setCondensing(false)
              store.setAttachedToolCallCount(0)
              textAccRef.current = ''
              newSegmentPendingRef.current = false
              qc.invalidateQueries({ queryKey: keys.activeConversations() })
              break
            }
            case 'user-question': {
              // The assistant asks the user (askUser). data is the call's arguments: {questions}.
              // The turn waits server-side until QuestionPrompt answers it; the call's
              // 'tool-result' removes it.
              const questions = parseQuestions(event.data)
              if (questions.length > 0) {
                store.addPendingQuestion({
                  callId: event.callId, questions, parentCallId: event.parentCallId || undefined,
                  // The turn asking to go on past its step limit, not the assistant asking.
                  stepLimit: stepLimitOf(event.data) ?? undefined,
                })
              }
              break
            }
            case 'prompt-closed': {
              // A prompt no tool result will close - the turn's own question at its step limit.
              store.removePendingApproval(event.callId)
              store.removePendingEnableRequest(event.callId)
              store.removePendingQuestion(event.callId)
              break
            }
            case 'guardrail': {
              // A guardrail withheld the message or the answer. `data` is a JSON object
              // {stage, verdict, categories, message} - see ChatService.publishGuardrail.
              //
              // NOT routed through the error path, deliberately. That path closes the stream and
              // offers Retry, and retrying an identical message past a deterministic rule produces
              // an identical refusal. 'done' still follows this from the backend and ends the turn
              // normally.
              try {
                const g = JSON.parse(event.data) as {
                  stage: string; verdict: string; categories: string[]; message: string
                }
                // INPUT and OUTPUT only. The RETRIEVAL stage does not publish this event, and the
                // backend says why beside the check (ChatService.guardRetrieved) - but the guard
                // is here as well, because the failure if one ever arrives is silent and ugly: a
                // retrieval block happens mid-stream, so the branch below would replace a
                // half-written answer with a refusal notice and take the message's actions away,
                // over one tool call that the turn recovered from. A withheld source shows up in
                // its own tool row instead.
                if (g.stage === 'retrieval') break
                store.setAwaitingModel(false)
                // An INPUT refusal has no assistant message yet and starts one. An OUTPUT one
                // lands on the message currently streaming and REPLACES its text, which is the
                // point: the backend has already rewritten what it persisted, and leaving the
                // pre-guardrail text on screen would be the one copy of it left anywhere.
                textAccRef.current = g.message
                store.setMessages(prev => {
                  // The last message of the thread proper - a message queued meanwhile sits after it.
                  const last = lastSettled(prev)
                  const bubble = {
                    role: 'assistant' as const,
                    content: g.message,
                    guardrail: { stage: g.stage, verdict: g.verdict, categories: g.categories ?? [] },
                  }
                  if (last?.message.role === 'assistant') {
                    return prev.map((m, i) => (i === last.index ? { ...m, ...bubble } : m))
                  }
                  return appendSettled(prev, bubble)
                })
              } catch { /* ignore parse errors, mirrors the 'usage' case's own try/catch style */ }
              break
            }
            case 'condense-start': {
              store.setCondensing(true)
              // Clears any previous run's "N tokens freed" note so the indicator can never show
              // the last condensation's result next to this one's spinner.
              store.setCondenseTokensFreed(null)
              break
            }
            case 'condense-end': {
              if (manualCondenseRef.current) {
                const freedNow = Number(event.data)
                if (!(Number.isFinite(freedNow) && freedNow > 0)) {
                  useToastStore.getState().addToast(t('Nothing to condense yet - the conversation needs a few more exchanges.'), 'info')
                }
              } else {
                store.setCondensing(false)
              }
              // `data` is the freed-token estimate as a plain integer string (see
              // SmartChatMemory.publishCondensing). Zero means the condensation bailed out with
              // nothing to fold - reported as null so the indicator says nothing rather than
              // announcing "0 tokens freed", which reads as a failure the user can't act on.
              const freed = Number(event.data)
              store.setCondenseTokensFreed(Number.isFinite(freed) && freed > 0 ? freed : null)
              break
            }
            case 'usage': {
              // Pushed once per LLM response (see ChatService.publishUsageUpdate()), not just
              // once at 'done' - merged into whatever's already cached for this conversation so
              // the usage meter moves live through a turn instead of jumping once at the end.
              // Only updates an already-populated cache entry (leaves it alone if nothing has
              // fetched this conversation yet) - the query's own fetch/invalidation elsewhere
              // covers that case instead of this synthesizing a partial object missing `messages`.
              try {
                const usage = JSON.parse(event.data) as ConversationUsage
                qc.setQueryData(keys.conversation(convId), (prev: ({ messages: unknown[] } & ConversationUsage) | undefined) =>
                  prev ? { ...prev, ...usage } : prev)
                qc.setQueryData(keys.conversationUsage(convId), (prev: ConversationUsage | undefined) =>
                  prev ? { ...prev, ...usage } : prev)
              } catch { /* ignore parse errors */ }
              break
            }
            case 'workflow-run': {
              // The COMPLETE run, every time - never a delta (see WorkflowRunSnapshot). Last write
              // wins with nothing to merge, so a dropped or out-of-order event self-heals on the
              // next one rather than leaving a row spinning forever.
              try {
                const run = JSON.parse(event.data) as WorkflowRunSnapshot
                store.putWorkflowRun(run)
                // The very first snapshot is the whole plan, all rows pending, and it arrives
                // BEFORE any step has run - which is the entire point of a workflow over a skill.
                // The user message it hangs under was appended locally by doSend with no server id
                // yet, so it is matched here by being the last user message rather than by id;
                // reloading later gets the same link properly, from the server (see below).
                store.setMessages(prev => {
                  if (prev.some(m => m.workflowRunId === run.runId)) return prev
                  const idx = prev.map(m => m.role).lastIndexOf('user')
                  if (idx === -1) return prev
                  return prev.map((m, i) => (i === idx ? { ...m, workflowRunId: run.runId } : m))
                })
              } catch { /* ignore parse errors, as the 'usage' case does */ }
              break
            }
            case 'title': {
              // A new conversation's generated title, pushed mid-turn now that it's requested as
              // soon as the first user message lands (see ConversationTitleService). `data` is the
              // plain title string. The conversation list is an infinite query (see
              // ChatSessionList), so this patches the title into whichever cached page holds this
              // conversation - it renames in place, with no refetch and no wait for 'done'. Each
              // cached page of the history is one ConversationsPage since the list became numbered
              // pages (2026-10-14); an infinite query's pages are patched the same way.
              if (!event.data) break
              let patched = false
              // Every history list cached under 'conversations' - the chat page's, or a panel's own
              // (ConversationListSource). The same prefix holds single conversations, which have no
              // pages and are left as they are.
              const retitle = (p: ConversationsPage): ConversationsPage => ({
                ...p,
                items: p.items.map(c => {
                  if (c.id !== convId) return c
                  patched = true
                  return { ...c, title: event.data }
                }),
              })
              qc.setQueriesData({ queryKey: keys.conversations() }, (prev: InfiniteData<ConversationsPage> | ConversationsPage | undefined) => {
                if (!prev) return prev
                if ('pages' in prev && Array.isArray(prev.pages)) return { ...prev, pages: prev.pages.map(retitle) }
                if ('items' in prev && Array.isArray(prev.items)) return retitle(prev)
                return prev
              })
              // A brand-new conversation usually isn't in any cached page yet (the list was
              // fetched before it existed), so there is nothing to rename - refetch instead, which
              // brings it in already carrying the generated title rather than the placeholder.
              if (!patched) qc.invalidateQueries({ queryKey: keys.conversations() })
              break
            }
          }
        },
        (more: boolean) => {
          if (generation !== page.generation) return
          manualCondenseRef.current = false
          store.setStreaming(false)
          store.setCondensing(false)
          settleToolActivity()
          // The turn is over; sub-agents may not be (`more`). The stream then stays open for them
          // and for the turn their reports will start - unless, by this page's own count, none is
          // left and none is owed.
          if (!more) ended(page, generation)
          qc.invalidateQueries({ queryKey: keys.conversations() })
          // Also drop the cached message history for this conversation - setConversationId()
          // reads it via fetchQuery(), which serves the 30s-stale cached snapshot rather than
          // refetching. Without this, reselecting a just-chatted-in conversation from history
          // can show messages from before this turn completed.
          qc.invalidateQueries({ queryKey: keys.conversation(convId) })
          qc.invalidateQueries({ queryKey: keys.conversationUsage(convId) })
          // A brand-new conversation's real title normally arrives live, via the 'title' event
          // handled above: the backend now requests it as soon as the first user message lands, so
          // it resolves mid-turn while this sink is still open (see ConversationTitleService).
          // This delayed invalidate is the fallback for the case that push can't cover - a title
          // call slower than the turn itself, which finishes after 'done' has already closed the
          // sink and so can never reach this client as an event.
          setTimeout(() => qc.invalidateQueries({ queryKey: keys.conversations() }), 4000)
          // The just-streamed messages have no server `id` yet (that only exists once persisted) -
          // fetch the now-final history and zip server ids onto the live, richer in-memory messages
          // (which still carry toolCalls the history endpoint doesn't return).
          //
          // Aligned from the END, not the start. The two arrays are the same append-only sequence,
          // but they are not always the same LENGTH: a condensation replaces the whole persisted
          // range with a handoff summary that the transcript endpoint then hides, so after one the
          // fetched history is strictly shorter than what this tab is still displaying. Zipping
          // head-first then walked off the end and left the newest messages - the ones whose
          // like/dislike/share/rewind controls all key off `id` - with no id at all, so those
          // controls silently vanished until a reload rebuilt the transcript from the server. It
          // also mismatched every id before that point, pointing each control at a different
          // message's row. Anchoring at the tail keeps the newest messages correct, which is where
          // both the controls and the user's attention are; anything ahead of the offset is a
          // message the server no longer has, and correctly gets no id.
          //
          // Since 2026-09-24 a condensation keeps every row and its id, so the two arrays line up
          // again; the tail alignment stays for anything else the transcript hides. A message with
          // no counterpart LOSES its id rather than keeping a stale one - a rewind aimed at a stale
          // id used to delete the whole conversation. And when no id changed, the same array is
          // kept, so nothing re-renders and the view does not scroll.
          //
          // ONLY THE IDS OF THE TAIL since 2026-10-17: of the messages with no id yet, plus one, from
          // the end of the transcript. The whole conversation after every turn was most of what a page
          // received while background sub-agents reported, each report being a turn - and the message
          // that started them carries all their steps. Nothing without an id, nothing asked for.
          const missing = idlessTail(getState().messages)
          if (missing > 0) {
            chatApi.getMessageIds(convId, missing + 1).then(ids => {
              store.setMessages(prev => withServerIds(prev, ids.map(id => ({ id: id ?? undefined })), true))
            }).catch(() => {})
          }
          processQueue()
        },
        (err: string, connectionLost = false) => {
          if (generation !== page.generation) return
          const wasStreaming = getState().streaming
          ended(page, generation)
          store.setStreaming(false)
          store.setCondensing(false)
          settleToolActivity()
          // No turn was running: the stream was open for sub-agents in the background, and what
          // failed is the connection to it. Their panels say what is known; nothing was being
          // answered that an error bubble could belong to.
          if (!wasStreaming) {
            if (connectionLost) return
          }
          // The TURN failed and sub-agents are still working: go on following them.
          if (!connectionLost) {
            const state = getState()
            if (anyAgentRunning(state.messages, state.toolCalls)) {
              setTimeout(() => {
                if (getState().conversationId === convId && !page.close) {
                  attachStream(convId, page.lastSeq)
                }
              }, 0)
            }
          }
          if (manualCondenseRef.current) {
            // A condensation's own failure - "this conversation is busy", a summarizer error -
            // reported as what it is. Not an assistant bubble: there is no reply to have failed,
            // and its Retry would resend the previous message.
            manualCondenseRef.current = false
            useToastStore.getState().addToast(serverText(err) || t('Failed to condense the conversation.'), 'danger')
          } else if (!connectionLost || !textAccRef.current) {
            // The turn FAILED, and the server said why: that goes under whatever the turn had
            // written, as its own row. This branch used to be taken only when nothing had been
            // written yet - with text on screen every failure became the notice below, so a
            // provider that could not be reached, or refused the request, was reported as a lost
            // connection to the server with nothing saying what had gone wrong.
            store.setMessages(prev => appendSettled(prev, { role: 'assistant', content: serverText(err) || t('An unexpected error occurred.'), error: true }))
          } else {
            // The STREAM was lost with an answer half written. The turn runs on server-side and
            // its answer is stored whole, so the notice offers to load it - one click, because the
            // desktop window has no reload of its own.
            const toastId = useToastStore.getState().addToast(
              t('Connection to the server was interrupted.'),
              'danger',
              {
                persist: true,
                link: {
                  label: t('Load the response'),
                  // The notice stays until the load has worked: it is the only way back to it.
                  action: () => {
                    if (getState().conversationId !== convId) {
                      useToastStore.getState().removeToast(toastId)
                      return
                    }
                    reloadRef.current?.(convId).then(loaded => {
                      if (loaded) useToastStore.getState().removeToast(toastId)
                    })
                  },
                },
              },
            )
          }
          qc.invalidateQueries({ queryKey: keys.conversations() })
          qc.invalidateQueries({ queryKey: keys.conversation(convId) })
          qc.invalidateQueries({ queryKey: keys.conversationUsage(convId) })
          processQueue()
        },
        () => {
          if (generation !== page.generation) return
          manualCondenseRef.current = false
          store.setStreaming(false)
          store.setCondensing(false)
          // The chat POST/SSE endpoints are permitAll (guest chat), so a lapsed session never
          // surfaces as an HTTP 401 - the backend instead detects it as a conversation-ownership
          // mismatch and publishes this distinct event. Try the same refresh the axios
          // interceptor uses for normal REST 401s; only fall back to "please sign in again" if
          // the refresh token itself is also gone.
          refreshAccessToken().then(refreshed => {
            if (refreshed) {
              qc.invalidateQueries({ queryKey: keys.me() })
              useToastStore.getState().addToast(t('Session refreshed, resending your message...'), 'info')
              // getState(), not the closed-over `store`: this fires from an SSE callback captured
              // when the stream was attached, so the snapshot in scope predates the send it is
              // trying to reissue.
              const last = getState().lastSend
              if (last) doSend(last.content, last.mentions, last.attachmentFiles, last.attachmentNames, false, last.skillMentions)
            } else {
              useAuthStore.getState().setUser(null)
              store.setMessages(prev => appendSettled(prev, {
                role: 'assistant',
                content: t('Your session has expired. Please sign in again to continue this conversation.'),
                error: true,
              }))
              useToastStore.getState().addToast(t('Your session has expired.'), 'danger', {
                persist: true,
                link: { label: t('Sign in'), to: '/login' },
              })
              qc.invalidateQueries({ queryKey: keys.conversations() })
              qc.invalidateQueries({ queryKey: keys.conversation(convId) })
              qc.invalidateQueries({ queryKey: keys.conversationUsage(convId) })
              processQueue()
            }
          })
        },
      t('Connection lost'),
      afterSeq,
    )
    keepClose(page, generation, unsub)
  }, [qc, store, t])

  const doSend = useCallback(async (content: string, mentions: string[] | undefined, attachmentFiles: string[] | undefined, attachmentNames: string[] | undefined, appendUserBubble: boolean, skillMentions?: string[]) => {
    // A turn is never a manual condensation, whatever an abandoned one left behind.
    manualCondenseRef.current = false
    store.setLastSend({ content, mentions, attachmentFiles, attachmentNames, skillMentions })
    if (appendUserBubble) {
      store.setMessages(prev => [...prev, { role: 'user', content, attachmentNames, mentions, skillMentions }])
    }
    store.setStreaming(true)
    store.setThinkingTokens(null)
    store.setAwaitingModel(true)
    store.setToolCalls([])
    store.setRetryAttempt(null)
    store.setLastEventAt(Date.now())
    store.setCondensing(false)
    store.setAttachedToolCallCount(0)
    textAccRef.current = ''
    newSegmentPendingRef.current = false

    // Where the send was made from. If the user has moved to another conversation by the time the
    // server answers, the turn runs on without them - the history's dot and the Chat badge show it -
    // and this must not drag the view back to it.
    const sentFrom = getState().conversationId
    try {
      // getState() rather than the render snapshot in `store`. MessageBubble's memo comparator
      // deliberately ignores onRetry, so the retry closure that reaches this can be several
      // renders old; ensureConversationId also mints an id straight into the store. Either way a
      // snapshot taken at capture time could be undefined here and start a brand-new conversation
      // instead of continuing the one the user is looking at.
      // The permission mode is read at send time, so the mode a message goes out under is the one on
      // screen when it did. The surface is this chat's (none for the chat page) - the server records
      // it from a conversation's first message only.
      const convId = await chatApi.startChat(content, getState().conversationId, mentions, attachmentFiles, skillMentions, {
        permissionMode: getState().permissionMode,
        surface: instance.surface,
      })
      qc.invalidateQueries({ queryKey: keys.activeConversations() })
      if (getState().conversationId !== sentFrom) return
      store.setConversationId(convId)
      // Already following this conversation - sub-agents were working in the background - so the
      // server kept its event sink and the new stream replays it: skip what this page holds.
      attachStream(convId, resumeAfter(page, convId))
      for (const q of getState().messageQueue) if (q.clientId) offerToTurn(q.clientId)
      // A guest's free turns are counted on acceptance, so this POST is the only thing that ever
      // moves the number the sign-in banner shows. Invalidated here rather than polled - see
      // useGuestAllowance. A no-op for a signed-in user, whose query is disabled.
      qc.invalidateQueries({ queryKey: keys.guestAllowance() })
    } catch (e: unknown) {
      store.setStreaming(false)
      store.setCondensing(false)
      store.setMessages(prev => appendSettled(prev, { role: 'assistant', content: extractErrorMessage(e, t, locale), error: true }))
      // Also on failure: a 429 here IS the allowance running out, and the banner must not go on
      // advertising turns that no longer exist.
      qc.invalidateQueries({ queryKey: keys.guestAllowance() })
      processQueue()
    }
  }, [store, attachStream, qc])

  // Drains one message from the queue (if any) once a turn has fully ended - see the terminal
  // SSE branches in doSend above. The bubble was already appended with `queued: true` when the
  // message was queued (see send() below), so this only needs to un-mark the oldest such bubble
  // and fire the actual API call for it.
  const processQueue = useCallback(() => {
    const next = store.dequeueMessage()
    if (!next) return
    store.setMessages(prev => {
      const idx = prev.findIndex(m => m.queued)
      if (idx === -1) return prev
      return prev.map((m, i) => (i === idx ? { ...m, queued: false } : m))
    })
    doSend(next.content, next.mentions, next.attachmentFiles, next.attachmentNames, false, next.skillMentions)
  }, [store, doSend])

  // OFFERS a queued message to the running turn, since 2026-09-29 (the maintainer's request): read
  // at the loop's next step instead of after the whole turn - see ChatService.inject. Plain text
  // only; a message carrying files, mentions or skills needs a turn of its own to set those up, and
  // waits as before. Anything the turn does not take - no turn to join, or it ended first - stays
  // queued and is sent by processQueue when the turn ends. A message queued before a NEW
  // conversation has its id is offered once startChat returns one (see doSend).
  const offerToTurn = useCallback((clientId: string) => {
    const state = getState()
    const convId = state.conversationId
    const queued = state.messageQueue.find(m => m.clientId === clientId)
    if (!convId || !queued || offeredRef.current.has(clientId)) return
    if (queued.attachmentFiles?.length || queued.mentions?.length || queued.skillMentions?.length) return
    offeredRef.current.add(clientId)
    chatApi.injectMessage(convId, queued.content, clientId)
      .then(() => store.setMessages(prev => prev.map(m => m.clientId === clientId ? { ...m, injectAccepted: true } : m)))
      .catch((e: unknown) => {
        const res = (e as { response?: { status?: number; data?: { error?: string } } }).response
        // No turn to join. Usually the turn has just ended and its 'done' is on the way, which sends
        // this message as the next turn. But the turn may be GONE - the server restarted under it -
        // and then no 'done' will ever come: the stream is opened again, and its first event says
        // which (`opened`), loading the conversation and sending the queue if it is gone.
        if (res?.status === 409) {
          if (getState().streaming && page.convId === convId && page.close) attachStream(convId, page.lastSeq)
          return
        }
        if (res?.status !== 422) return
        // Withheld by the input guardrail: taken back, with the reason, rather than sent later
        // into the same refusal.
        store.removeQueued(clientId)
        store.setMessages(prev => prev.filter(m => m.clientId !== clientId))
        if (res.data?.error) useToastStore.getState().addToast(res.data.error, 'danger')
      })
  }, [store, attachStream])

  const send = useCallback((content: string, mentions?: string[], attachmentFiles?: string[], attachmentNames?: string[], skillMentions?: string[]) => {
    if (store.streaming) {
      const clientId = `q-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
      store.enqueueMessage({ clientId, content, mentions, attachmentFiles, attachmentNames, skillMentions })
      store.setMessages(prev => [...prev, { role: 'user', content, queued: true, clientId, attachmentNames, mentions, skillMentions }])
      offerToTurn(clientId)
      return
    }
    doSend(content, mentions, attachmentFiles, attachmentNames, true, skillMentions)
  }, [doSend, store])

  // Starts a manual condensation and then follows it on the conversation's own event stream, the
  // same way doSend follows a turn. That shared treatment is what makes the rest fall out for free:
  // `streaming` is true for the duration, so send() queues anything typed meanwhile and drains it
  // from the terminal 'done' (see processQueue); the Stop button is shown and reaches it (the
  // backend registers an abort flag for a manual condense - see ChatService.condenseNow); and a
  // reload mid-run reattaches through resumeConversation like any other in-flight work.
  //
  // Throws nothing - a rejection is surfaced as a toast and the state is unwound, since this is
  // triggered by a single click on an indicator, not by a form the user is waiting on.
  const condense = useCallback(async () => {
    const convId = store.conversationId
    if (!convId || store.streaming) return
    store.setStreaming(true)
    store.setThinkingTokens(null)
    store.setAwaitingModel(false)
    store.setCondensing(true)
    store.setCondenseTokensFreed(null)
    // The previous turn's text is not this stream's - see the error handler, which reads it.
    textAccRef.current = ''
    manualCondenseRef.current = true
    try {
      await chatApi.condenseNow(convId)
      attachStream(convId)
    } catch (e) {
      manualCondenseRef.current = false
      store.setStreaming(false)
      store.setCondensing(false)
      const status = e instanceof AxiosError ? e.response?.status : undefined
      useToastStore.getState().addToast(
        status === 409
          ? t("Can't condense while a response is in progress.")
          : t('Failed to condense the conversation.'),
        'danger',
      )
    }
  }, [store, attachStream, t])

  // Whether there is anything to retry WITH. The button is hidden when there is not, rather than
  // rendered over a no-op: a Retry that does nothing at all when clicked - no spinner, no request,
  // no state change - is indistinguishable from the app being broken. False for a conversation
  // this tab did not send into (resumed from the URL or the history modal), whose turn payload was
  // never in this browser to begin with.
  const canRetry = store.lastSend !== null

  const retry = useCallback(() => {
    const last = getState().lastSend
    if (!last) return
    // Guarded like send()/condense()/rewind(): retry calls doSend directly rather than going
    // through send()'s queueing, so without this a click during a live turn would start a second
    // overlapping POST for the same conversation - the exact overlap ChatEventSink cannot survive
    // (the older turn's `done` closes the sink out from under the newer one). Every path that
    // appends an error bubble clears `streaming` first, so this never blocks a legitimate retry.
    if (getState().streaming) return
    // Error bubbles are only ever appended and nothing follows them - the trailing error is
    // always the very last message when present.
    store.setMessages(prev => {
      const lastMsg = prev[prev.length - 1]
      return lastMsg?.error ? prev.slice(0, -1) : prev
    })
    doSend(last.content, last.mentions, last.attachmentFiles, last.attachmentNames, false, last.skillMentions)
  }, [doSend, store])

  // Mints a client-side conversationId for a brand-new conversation that has no server-assigned
  // id yet - needed when a file is attached before the first message is typed/sent, since the
  // upload endpoint is scoped to a conversationId. Uses the store's own setConversationId
  // directly (a plain state setter with no side effects - see chatStore.ts) rather than this
  // hook's own setConversationId below, which additionally fetches conversation history and
  // resets messages: wrong here, since no conversation exists server-side yet. ChatController
  // already accepts a client-supplied conversationId and only mints its own if none is given, so
  // the eventual startChat() call transparently reuses this id.
  const ensureConversationId = useCallback((): string => {
    if (store.conversationId) return store.conversationId
    const minted = crypto.randomUUID()
    getState().setConversationId(minted)
    return minted
  }, [store])

  // Deliberately does NOT close the SSE connection or reset streaming/condensing itself - the
  // backend notices the abort within ~1s (its approval-wait and retry loops poll abort.get()
  // every second) and publishes a genuine 'done' (or 'error') event, which chatApi's internal
  // handler uses to close the connection and which onDone/onError (wired in doSend below)
  // already use to reset this state. Resetting eagerly here re-enabled the input before the
  // backend's turn had actually finished, so a fast follow-up message could start a SECOND,
  // overlapping chat() call for the same conversationId while the first was still running.
  // ChatEventSink's per-conversation (not per-turn) event sink can't safely handle two turns in
  // flight at once: the older turn's belated 'done' event closes the sink out from under the
  // newer turn (Sinks.Many.emitComplete() on ANY event for that conversationId), silently
  // dropping all of the newer turn's subsequent events - including tool-approval-request, which
  // is why a write-tool approval prompt could fail to appear at all with no error shown anywhere.
  const stop = useCallback(() => {
    if (store.conversationId) {
      chatApi.stopChat(store.conversationId).catch(() => {
        useToastStore.getState().addToast(t('Could not reach the server to stop this response.'), 'danger')
      })
    }
  }, [store, t])

  const resetChat = useCallback(() => {
    stopFollowing(page)
    // A condensation left mid-way has no done/error coming to clear this - see manualCondenseRef.
    manualCondenseRef.current = false
    store.reset()
  }, [store])

  // Deletes messageId and everything after it, both server-side (chat_messages table, which
  // also backs the LLM's own memory - see SmartChatMemory) and in the local transcript. Only
  // valid when no turn is in flight, matching the backend's own guard (ChatService.isActive).
  // Returns the rewound message's original content so the caller can drop it back into the
  // input for editing.
  const rewind = useCallback(async (messageId: number): Promise<string | undefined> => {
    if (!store.conversationId || store.streaming) return undefined
    const idx = store.messages.findIndex(m => m.id === messageId)
    if (idx === -1) return undefined
    const target = store.messages[idx]
    try {
      await chatApi.rewindConversation(store.conversationId, messageId)
    } catch (e) {
      // 409 = the backend still considers a turn "in flight" for this conversation (e.g. a
      // legitimately slow turn, or - before the chat-reactivity pass - one stuck on a now-fixed
      // silent hang) even though this client's own `streaming` flag says otherwise (most often
      // because this tab lost track of it, e.g. a refresh mid-turn). Explain it instead of
      // failing with no visible reason.
      const message = e instanceof AxiosError && e.response?.status === 409
        ? t("Can't rewind while a response is still in progress. Stop it first, then try again.")
        : extractErrorMessage(e, t, locale)
      useToastStore.getState().addToast(message, 'danger')
      return undefined
    }
    store.setMessages(prev => prev.slice(0, idx))
    qc.invalidateQueries({ queryKey: keys.conversation(store.conversationId!) })
    qc.invalidateQueries({ queryKey: keys.conversations() })
    return target.content
  }, [store, qc, t])

  // Loads a conversation's workflow runs into the store. Deliberately fire-and-forget with a
  // swallowed rejection: a step table that fails to load costs the reader the plan behind an
  // already-complete transcript, which is not worth an error bubble over the answer itself.
  const loadWorkflowRuns = useCallback((id: string) => {
    // Not asked at all when workflows are off: the endpoint does not exist then, and every
    // conversation opened logged a 404 to the console for a table that can never show.
    if (!FEATURES.workflows) return
    chatApi.getWorkflowRuns(id)
      .then((runs: WorkflowRunSnapshot[]) => store.setWorkflowRuns(runs))
      .catch(() => {})
  }, [store])

  // Stops FOLLOWING the open conversation's turn - never stops the turn. Called before another
  // conversation is shown, so each keeps to its own.
  const detach = useCallback(() => {
    stopFollowing(page)
    textAccRef.current = ''
    newSegmentPendingRef.current = false
  }, [page, textAccRef, newSegmentPendingRef])

  // Settles once the history has landed (or failed to).
  const setConversationId = useCallback((id: string | undefined): Promise<void> => {
    detach()
    if (!id) {
      store.reset()
      return Promise.resolve()
    }
    store.setConversationId(id)
    store.setToolCalls([])
    store.setStreaming(false)
    store.setCondensing(false)
    store.setCondenseTokensFreed(null)
    store.clearPendingApprovals()
    store.clearPendingEnableRequests()
    store.clearPendingQuestions()
    store.clearQueue()
    store.setMessages([])
    store.setWorkflowRuns([])
    store.setLoadingHistory(true)
    const loaded = qc.fetchQuery({ queryKey: keys.conversation(id), queryFn: () => chatApi.getConversation(id) })
      .then(data => {
        store.setMessages(data.messages.map(toStoreMessage))
      })
      .catch(() => {
        store.setMessages([])
      })
      .finally(() => store.setLoadingHistory(false))
    // Fetched separately rather than embedded in the conversation payload: a run is not a message,
    // and the endpoint returns the identical object the live SSE event carries, which is what makes
    // a reloaded table and a watched one the same table. Failure is silent - a missing step table
    // is a worse conversation, not a broken one, and the transcript above stands on its own.
    loadWorkflowRuns(id)
    return loaded
  }, [store, qc, loadWorkflowRuns, detach])

  // A prompt that was open when the conversation was loaded, put back as the event that asked it
  // would have put it - see ConversationDetail.PendingPrompt.
  const restorePrompt = useCallback((prompt: PendingPrompt) => {
    const parentCallId = prompt.parentCallId || undefined
    try {
      if (prompt.type === 'tool-approval-request') {
        store.addPendingApproval({
          callId: prompt.callId, toolName: prompt.toolName ?? '', arguments: prompt.data ?? '{}', parentCallId,
        })
      } else if (prompt.type === 'tool-enable-request') {
        const payload = JSON.parse(prompt.data ?? '{}') as {
          connectorLabel: string; connectorField?: string; connectionId?: string; connectionName?: string
        }
        store.addPendingEnableRequest({
          callId: prompt.callId, toolName: prompt.toolName ?? '', connectorLabel: payload.connectorLabel,
          connectorField: payload.connectorField, connectionId: payload.connectionId,
          connectionName: payload.connectionName, parentCallId,
        })
      } else if (prompt.type === 'user-question') {
        const questions = parseQuestions(prompt.data)
        if (questions.length > 0) {
          store.addPendingQuestion({
            callId: prompt.callId, questions, parentCallId, stepLimit: stepLimitOf(prompt.data) ?? undefined,
          })
        }
      }
    } catch { /* an unreadable prompt is one the user cannot answer here; the server times it out */ }
  }, [store])

  // Called on mount when the URL already names a conversation (see Router.tsx/ChatPage.tsx) -
  // checks whether a turn is still running server-side and, if so, reattaches to it live instead
  // of showing an empty/stale view (the actual fix for "refresh mid-turn loses everything and
  // rewind 409s forever" - see ../proxyma-docs/architecture/chat-reactivity.md). ChatEventSink's
  // per-conversation sink is replay-all and outlives any single SSE connection, so attachStream
  // still receives the whole current turn's events from its own start, not just what's left.
  // Falls back to the plain (inactive) history load otherwise.
  // Settles once the history has landed and, with work running, the stream is attached.
  const resumeConversation = useCallback(async (id: string): Promise<void> => {
    detach()
    manualCondenseRef.current = false
    let status = { active: false, condensing: false, background: false }
    try {
      status = await chatApi.getConversationStatus(id)
    } catch {
      status = { active: false, condensing: false, background: false }
    }
    if (!status.active && !status.condensing && !status.background) {
      return setConversationId(id)
    }
    // A turn is running, or only sub-agents in the background are. Either way the stream is
    // followed; only a turn makes the composer a Stop button.
    const turnRunning = status.active || status.condensing
    store.setConversationId(id)
    store.setToolCalls([])
    store.setStreaming(turnRunning)
    store.setThinkingTokens(null)
    // The sink replays the turn from its start, so this is corrected by the first replayed event.
    store.setAwaitingModel(status.active)
    store.setRetryAttempt(null)
    store.setLastEventAt(Date.now())
    // Seeded from the server's own answer rather than left false until a 'condense-start' arrives.
    // A condensation already under way when this tab loaded emitted that event before this client
    // existed; the sink replays, so it usually still arrives - but not once the sink has been
    // reaped, and not at all for the window between this fetch and the stream attaching below.
    // Without the seed the indicator flickered off and on across a reload, or stayed off entirely.
    store.setCondensing(status.condensing)
    store.setCondenseTokensFreed(null)
    store.clearPendingApprovals()
    store.clearPendingEnableRequests()
    store.clearPendingQuestions()
    store.clearQueue()
    store.setWorkflowRuns([])
    store.setAttachedToolCallCount(0)
    store.setLoadingHistory(true)
    textAccRef.current = ''
    newSegmentPendingRef.current = false
    // While a turn runs, the transcript stops at the message that opened it (ChatService's
    // turnFloors) - the turn itself arrives live via attachStream, which replays it from its start.
    //
    // THE STREAM IS ATTACHED AFTER THE HISTORY LANDS, since 2026-10-01. Attached beside the fetch,
    // the two raced: whatever the replay had already put in the thread when the history arrived
    // was overwritten by it, so answers and tool rows of the running turn went missing on a page
    // that came back to it. The sink replays everything, so attaching a moment later loses nothing.
    //
    // Not served from the query cache either: an entry cached while the turn was further back, or
    // before it began, would be the wrong starting point for that replay.
    //
    // AND ONLY FROM WHERE THE TRANSCRIPT STOPS (`eventSeq`), since 2026-10-02. With a turn running
    // that is where the turn began, as before. With only sub-agents running it is the last event
    // published: the sink still holds the turn that started them, which the transcript already
    // carries in full, and replaying it would say all of it twice.
    let eventSeq = -1
    const loaded = chatApi.getConversation(id)
      .then(data => {
        if (getState().conversationId !== id) return
        store.setMessages(data.messages.map(toStoreMessage))
        eventSeq = data.eventSeq ?? -1
        // What was already waiting for an answer. Nothing will ask it again.
        for (const prompt of data.prompts ?? []) restorePrompt(prompt)
      })
      .catch(() => { if (getState().conversationId === id) store.setMessages([]) })
      .finally(() => {
        if (getState().conversationId !== id) return
        store.setLoadingHistory(false)
        attachStream(id, eventSeq)
      })
    // Seeded from the server for the same reason `condensing` is above: a run already under way
    // when this tab loaded published its snapshots before this client existed. The sink replays,
    // so they usually still arrive - but not once it has been reaped, and not at all for the gap
    // between this fetch and the stream attaching below. Live events land on top of this seed and
    // are the same shape, so the two cannot disagree.
    loadWorkflowRuns(id)
    return loaded
  }, [store, attachStream, setConversationId, loadWorkflowRuns, detach, restorePrompt])

  // A tab shown again picks up the stream it let go of while hidden (chatInstance.ts): the server
  // replays the turn and the events this page already handled are skipped, so the thread goes on as
  // if the stream had never closed. Paused too long for the server to be sure to hold what was
  // missed, the conversation is loaded whole. Either way, what the stream's events would have
  // refreshed meanwhile is asked for again.
  resumeRef.current = () => {
    const point = resumePoint(page, Date.now())
    if (!point) return
    // The list, and with it (by prefix) every conversation's cached transcript.
    qc.invalidateQueries({ queryKey: keys.conversations() })
    qc.invalidateQueries({ queryKey: keys.activeConversations() })
    if (getState().conversationId !== point.convId) return
    if (point.reload) reloadRef.current?.(point.convId)
    else attachStream(point.convId, point.afterSeq, true)
  }

  // Asks the server FIRST. resumeConversation clears the thread before it fetches, so with the
  // server still out of reach it would trade a half-written answer for an empty page.
  //
  // AND KEEPS WHAT WAS TYPED, since 2026-10-16. The load clears the queue with the thread, so a
  // message typed while the page waited on a turn the server no longer had (`lost` - see the
  // 'open' event) vanished with it. It is put back under the loaded conversation and sent as the
  // queue always is: to the turn running there, or as the next turn.
  reloadRef.current = (id: string, options: { lost?: boolean } = {}) =>
    chatApi.getConversationStatus(id)
      .then(() => {
        const state = getState()
        const accepted = new Set(state.messages.filter(m => m.injectAccepted && m.clientId).map(m => m.clientId!))
        const carried = state.conversationId === id
          ? carriedOnReload(state.messageQueue, accepted, options.lost === true)
          : []
        resumeConversation(id).then(() => {
          if (carried.length === 0 || getState().conversationId !== id) return
          for (const q of carried) {
            if (q.clientId) offeredRef.current.delete(q.clientId)
            store.enqueueMessage(q)
            store.setMessages(prev => [...prev, {
              role: 'user', content: q.content, queued: true, clientId: q.clientId,
              attachmentNames: q.attachmentNames, mentions: q.mentions, skillMentions: q.skillMentions,
            }])
          }
          if (!getState().streaming) processQueue()
          else for (const q of carried) if (q.clientId) offerToTurn(q.clientId)
        })
        return true
      })
      .catch(() => {
        useToastStore.getState().addToast(t('Could not reach the server. Try again in a moment.'), 'danger')
        return false
      })

  return {
    // Which chat this is, and where its conversations are held (undefined for the chat page).
    instanceKey: instance.key,
    surface: instance.surface,
    messages: store.messages,
    streaming: store.streaming,
    toolCalls: store.toolCalls,
    loadingHistory: store.loadingHistory,
    pendingApprovals: store.pendingApprovals,
    pendingEnableRequests: store.pendingEnableRequests,
    condensing: store.condensing,
    condenseTokensFreed: store.condenseTokensFreed,
    attachedToolCallCount: store.attachedToolCallCount,
    workflowRuns: store.workflowRuns,
    send,
    retry,
    canRetry,
    condense,
    stop,
    rewind,
    conversationId: store.conversationId,
    setConversationId,
    resumeConversation,
    resetChat,
    ensureConversationId,
  }
}
