import { FEATURES } from '@/config/features'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from '@/i18n'
import { MessageBubble } from './MessageBubble'
import { ThinkingIndicator } from './ThinkingIndicator'
import { QuestionPrompt } from './QuestionPrompt'
import { StepLimitPrompt } from './StepLimitPrompt'
import { AgentPrompts, SubAgentReportedNotice } from './AgentPrompts'
import { findAgent } from '../utils/subAgent'
import { QUIET_MS, waitFace } from '../utils/waitState'
import { settledLength } from '../utils/threadOps'
import { revealScrollTop } from '../utils/threadReveal'
import { catchUpIn, followsChange, holdAfterScroll, keyPushesDown, promptScrollTop, pushesPastEnd } from '../utils/threadFollow'
import { useChatStore } from '../instance/ChatInstanceContext'
import { ToolCallList } from './ToolCallList'
import { WorkflowStepTable } from './WorkflowStepTable'
import { ChatThreadSkeleton } from '@/components/ui/Skeleton'
import { useLeavingItems } from '../hooks/useLeavingItems'
import { MessageErrorBoundary } from '@/components/ui/ErrorBoundaries'
import { Presence, THREAD_COLUMN, cn, prefersReducedMotion } from '@/components/ui/kit'
import type { ChatMessage, ToolCallInfo, PendingApproval, PendingEnableRequest, WorkflowRunSnapshot } from '../types/chat.types'

interface Props {
  messages: ChatMessage[]
  streaming: boolean
  toolCalls?: ToolCallInfo[]
  loading?: boolean
  onRetry?: () => void
  conversationId?: string
  // One entry per write-capable tool call currently awaiting the user's Accept/Deny - a turn can
  // request several at once (e.g. "close issues A and B"), each needs its own visible prompt.
  pendingApprovals?: PendingApproval[]
  // One entry per write-capable tool call currently paused awaiting the user's Enable/Keep
  // Disabled decision - see ToolEnablePrompt's docs.
  pendingEnableRequests?: PendingEnableRequest[]
  // True while the backend is folding older messages into a handoff summary
  // (SmartChatMemory.condense()) - see the 'condense-start'/'condense-end' SSE events. Nothing is
  // rendered for it HERE any more; the transcript is for messages, and a progress line among them
  // scrolled out of view exactly when the user went looking for it. It is reported by the
  // context-window indicator in the input toolbar instead (see ChatComposer's ContextWindowIndicator),
  // which is both where the number it is about to change already lives and always on screen. This
  // flag survives only to suppress the "thinking" indicator below, which would otherwise claim the
  // assistant is working on the request when it is doing housekeeping.
  condensing?: boolean
  // Rewinds the conversation back to (and re-editing) a given user message - only offered while
  // no turn is in flight, see MessageBubble's showRewind.
  onRewind?: (messageId: number) => void
  // How many of the CURRENT turn's `toolCalls` entries have already been claimed by a rendered
  // message - turn-scoped (see chatStore/useChat), NOT derivable from `messages` alone, since
  // `messages` spans every turn this session while `toolCalls` resets every turn. Slicing
  // `toolCalls` by a whole-conversation count would go stale/too-large from the second turn
  // onward and silently hide any pending tool call (including approval prompts) still in
  // progress.
  attachedToolCallCount?: number
  // Set by the /shared/:token viewer page - strips every interactive affordance off each message
  // (like/dislike, feedback, rewind, re-share), leaving read + copy. See MessageBubble's readOnly.
  readOnly?: boolean
  // Every workflow run in this conversation, keyed by runId. A run is drawn under the user message
  // carrying its id (see ChatMessage.workflowRunId) - under, not over, because the message is the
  // instruction and the table is the plan that followed from it.
  workflowRuns?: Record<string, WorkflowRunSnapshot>
  // A chat in a narrow column beside a page (ChatPanel, ~380-480px): the thread's gutters are the
  // panel's own rather than the page's, which step up at `sm` - a breakpoint of the WINDOW, wide
  // long before the panel is.
  compact?: boolean
}

export function ChatWindow({ messages, streaming, toolCalls = [], loading = false, onRetry, conversationId, pendingApprovals = [], pendingEnableRequests = [], condensing = false, onRewind, attachedToolCallCount = 0, readOnly = false, workflowRuns = {}, compact = false }: Props) {
  const { t } = useTranslation()
  // The thread's side gutter: 8px on a phone and 24px from `sm` up on the chat page, 12px in a panel.
  const gutter = compact ? 'px-3' : 'px-2 sm:px-6'
  const pendingQuestions = useChatStore(s => s.pendingQuestions)
  // NOT WHILE CONDENSING, nor on the render that ends it. A condensation adds nothing to read, and
  // following it to the bottom pulled a reader who had scrolled up back down two or three times
  // per click - on the click, on condense-end and on done.
  //
  // A PROMPT APPEARING COUNTS, since 2026-10-01 (maintainer's feedback). An approval, an "enable
  // this tool" or one of the assistant's own questions arrives AFTER the tool call it belongs to,
  // which had already scrolled the thread - so the card was added below the fold and the turn sat
  // waiting on a question nobody could see. Nothing else moves until it is answered, so it is
  // brought into view whole - or, when it is taller than the thread's window (two questions, one
  // of them a list to tick), from its first line: the end of such a prompt is its buttons, and the
  // question was scrolled off the top (promptScrollTop).
  //
  // AND NOT WHILE THE READER IS READING SOMETHING EARLIER, since 2026-10-02 - see threadFollow.ts.
  // A scroll up holds the thread where it is for a while; a prompt appearing and the reader's own
  // message still bring the end into view, and end the hold with it. When the while is over and
  // something arrived during it, the thread goes to its end by itself (catchUpIn).
  const wasCondensing = useRef(false)
  const heldUntil = useRef(0)
  const lastTop = useRef(0)
  const missed = useRef(false)
  const catchUpTimer = useRef(0)
  const catchUp = () => {
    window.clearTimeout(catchUpTimer.current)
    const wait = catchUpIn({ now: Date.now(), heldUntil: heldUntil.current, missed: missed.current })
    if (wait === null) return
    catchUpTimer.current = window.setTimeout(() => {
      // A scroll since pushed the hold on; wait for the new end of it.
      if (heldUntil.current > Date.now()) { catchUp(); return }
      missed.current = false
      scrollToEnd(scrollerRef.current)
    }, wait + 20)
  }
  useEffect(() => () => window.clearTimeout(catchUpTimer.current), [])
  const promptCount = pendingApprovals.length + pendingEnableRequests.length + pendingQuestions.length
  const lastPromptCount = useRef(promptCount)

  // WHAT A SCREEN READER IS TOLD (CHAT-06): once when an answer has finished, and once when the
  // turn starts waiting on the reader - an approval, a question. Not the stream itself: a live
  // region over streamed text announces every delta. A visually hidden status line, set and
  // cleared, rather than the thread as a log.
  const [announcement, setAnnouncement] = useState('')
  const announcedPrompts = useRef(promptCount)
  const wasStreamingForAnnounce = useRef(streaming)
  useEffect(() => {
    const ended = wasStreamingForAnnounce.current && !streaming
    wasStreamingForAnnounce.current = streaming
    const asked = promptCount > announcedPrompts.current
    announcedPrompts.current = promptCount
    if (asked) setAnnouncement(t('The assistant is waiting for your answer.'))
    else if (ended && messages[messages.length - 1]?.role === 'assistant') setAnnouncement(t('The answer is ready.'))
    else return
    const timer = window.setTimeout(() => setAnnouncement(''), 4000)
    return () => window.clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [streaming, promptCount])
  const lastLength = useRef(messages.length)
  useEffect(() => {
    const promptAppeared = promptCount > lastPromptCount.current
    const userSent = messages.length > lastLength.current && messages[messages.length - 1]?.role === 'user'
    lastPromptCount.current = promptCount
    lastLength.current = messages.length
    if (condensing) { wasCondensing.current = true; return }
    if (wasCondensing.current) { wasCondensing.current = false; return }
    if (!followsChange({ now: Date.now(), heldUntil: heldUntil.current, promptAppeared, userSent })) {
      missed.current = true
      catchUp()
      return
    }
    heldUntil.current = 0
    missed.current = false
    const scroller = scrollerRef.current
    const prompts = promptAppeared ? scroller?.querySelectorAll<HTMLElement>('[data-turn-prompt]') : undefined
    const prompt = prompts?.[prompts.length - 1]
    if (scroller && prompt) {
      const top = promptScrollTop({
        top: scroller.scrollTop + prompt.getBoundingClientRect().top - scroller.getBoundingClientRect().top,
        endTop: scroller.scrollHeight - scroller.clientHeight,
        clearTop: PIN_BAR_PX + 12,
      })
      if (top !== null) { scroller.scrollTo({ top, behavior: scrollBehavior() }); return }
    }
    scrollToEnd(scrollerRef.current)
  }, [messages, streaming, toolCalls, condensing, pendingApprovals, pendingEnableRequests, pendingQuestions])

  // A LOADED CONVERSATION OPENS AT ITS LATEST MESSAGE. The effect above cannot do it: the history
  // lands in the store one microtask BEFORE loadingHistory goes false (useChat sets the messages in
  // `.then` and clears the flag in `.finally`), so it ran while the skeleton was still up and there
  // was no bottom to scroll to - and when the thread then rendered, none of its dependencies had
  // changed. Every reload opened at the first message (found 2026-09-30, reproduced with a
  // ten-message conversation: scrollTop 0 of 1857). Instant rather than smooth: this is where the
  // thread starts, not a movement anyone needs to watch.
  const wasLoading = useRef(loading)
  useEffect(() => {
    const opened = wasLoading.current && !loading
    wasLoading.current = loading
    if (!opened) return
    const toEnd = () => {
      const scroller = scrollerRef.current
      if (scroller) scroller.scrollTop = scroller.scrollHeight
    }
    toEnd()
    // Once more after the next paint: the composer and the model label below settle a frame late
    // and shorten the scroller, which left the last lines of the answer under it.
    const frame = requestAnimationFrame(toEnd)
    return () => cancelAnimationFrame(frame)
  }, [loading])

  // Tool calls a workflow step owns are rendered inside that step's row, so they are taken out of
  // every flat list on this screen - otherwise a run's calls appear twice, once nested under the
  // step that made them and once again above the final answer. Only LIVE calls carry the link (the
  // persisted transcript has no parentCallId - see WorkflowStepTable), so on a reloaded
  // conversation this set is empty and the calls show flat, which is the honest fallback: the
  // grouping is lost, nothing else is.
  const stepCallIds = new Set(Object.values(workflowRuns).flatMap(r => r.steps.map(s => s.callId)))
  const notInAStep = (tc: ToolCallInfo) => !tc.parentCallId || !stepCallIds.has(tc.parentCallId)

  // Tool calls already attached to a message (any message, not just the last one - a turn can
  // span several messages, one per ReAct iteration) are rendered inline with that message; only
  // the trailing calls not yet claimed by a message belong in the transient "in progress" list.
  const pendingToolCalls = toolCalls.slice(attachedToolCallCount).filter(notInAStep)
  const showPendingTools = pendingToolCalls.length > 0
  // WHICH WAIT THE ROW UNDER THE TURN SHOWS - the full table is in utils/waitState.ts. The provider
  // wait and thinking name the model; a running tool and streaming text show nothing, since each is
  // its own proof of life; the star names are for a turn quiet for a moment and waiting on none of
  // those. `awaitingModel` is inferred from the stream - see chatStore.
  const awaitingModel = useChatStore(s => s.awaitingModel)
  const thinking = useChatStore(s => s.thinkingTokens !== null)
  const lastEventAt = useChatStore(s => s.lastEventAt)
  const quietFor = useQuietFor(lastEventAt, streaming)
  const face = waitFace({
    streaming, condensing, thinking, awaitingModel,
    awaitingUser: pendingApprovals.length > 0 || pendingEnableRequests.length > 0 || pendingQuestions.length > 0,
    toolRunning: toolCalls.some(tc => !tc.done),
    quietMs: quietFor,
  })

  // THE PINNED QUESTION. Reading down a long answer, the question it answers stays at the top of
  // the thread, in a one-line bar; clicking it scrolls back to the full question.
  //
  // A slim copy, not the message itself made sticky: a pasted prompt can be screens long, and a
  // sticky element keeps its full height. The bar sits over the top of the scroller with NO
  // BACKGROUND - the answer slides on underneath it into a dark gradient, near-opaque under the
  // question and fading out below it (.pin-fade) - the maintainer's requirement. The bar is a fixed
  // PIN_BAR_PX tall, which is what lets the gradient be a fixed height too: nothing is measured and
  // nothing moves when the bar appears.
  //
  // Which question: the last one whose element has scrolled up past the bar's bottom edge. That is
  // the sticky-header rule - a question holds the bar until the next one takes it - computed on
  // scroll rather than with a sticky element, because the bar is no longer inside the thread.
  const scrollerRef = useRef<HTMLDivElement>(null)
  const questionNodes = useRef(new Map<number, HTMLDivElement>())
  const [pinnedIndex, setPinnedIndex] = useState<number | null>(null)
  // What was last set, so the state is set only when the pinned question CHANGES. The recompute runs
  // after every change to the thread - each streamed delta - and React does not drop a set of an
  // equal value while the component has updates of its own pending, as it does on every delta: each
  // one cost a second render, and a turn's events arriving in one burst (a replay) made 50 commits
  // in a row schedule an update from their effects, which React's development build reports as
  // "Maximum update depth exceeded" although nothing loops.
  const pinnedNow = useRef<number | null>(null)
  // The callback is a new function every render, so React detaches the old one (null) and attaches
  // the new one (the same node) each time; only a genuinely new node is recorded.
  const questionRef = (i: number) => (node: HTMLDivElement | null) => {
    if (node && questionNodes.current.get(i) !== node) questionNodes.current.set(i, node)
  }

  const recomputePinned = useRef(() => {})
  recomputePinned.current = () => {
    const scroller = scrollerRef.current
    if (!scroller) return
    const edge = scroller.getBoundingClientRect().top + PIN_BAR_PX
    let found: number | null = null
    for (const [i, node] of [...questionNodes.current.entries()].sort((a, b) => a[0] - b[0])) {
      // A question a rewind removed keeps its entry; a node no longer in the page is not one.
      if (!node.isConnected || i >= messages.length) continue
      if (node.getBoundingClientRect().bottom <= edge) found = i
    }
    if (found === pinnedNow.current) return
    pinnedNow.current = found
    setPinnedIndex(found)
  }

  useEffect(() => {
    const scroller = scrollerRef.current
    if (!scroller) return
    let frame = 0
    // A conversation just opened starts out followed.
    heldUntil.current = 0
    lastTop.current = scroller.scrollTop
    const fromEnd = () => scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight
    // Following again: anything that arrived during the hold is brought into view at once, rather
    // than waiting for the next line to arrive (a turn that has ended sends none).
    const resume = () => {
      const wasHeld = heldUntil.current > 0
      heldUntil.current = 0
      if (wasHeld && missed.current) {
        missed.current = false
        scrollToEnd(scrollerRef.current)
      }
      missed.current = false
    }
    const onScroll = () => {
      const top = scroller.scrollTop
      const next = holdAfterScroll({
        top, lastTop: lastTop.current, fromEnd: fromEnd(), now: Date.now(), heldUntil: heldUntil.current,
      })
      if (next === 0) resume()
      else heldUntil.current = next
      lastTop.current = top
      if (frame) return
      frame = requestAnimationFrame(() => { frame = 0; recomputePinned.current() })
    }
    // A push towards the end while already there moves nothing, so no scroll event says so - see
    // pushesPastEnd. The wheel and the scrolling keys say it instead.
    const onWheel = (e: WheelEvent) => {
      if (pushesPastEnd({ delta: e.deltaY, fromEnd: fromEnd() })) resume()
    }
    const onKey = (e: KeyboardEvent) => {
      if (keyPushesDown(e.key, e.shiftKey) && pushesPastEnd({ delta: 1, fromEnd: fromEnd() })) resume()
    }
    scroller.addEventListener('scroll', onScroll, { passive: true })
    scroller.addEventListener('wheel', onWheel, { passive: true })
    scroller.addEventListener('keydown', onKey)
    return () => {
      scroller.removeEventListener('scroll', onScroll)
      scroller.removeEventListener('wheel', onWheel)
      scroller.removeEventListener('keydown', onKey)
      if (frame) cancelAnimationFrame(frame)
    }
  }, [loading])

  // A new message, or a streamed answer growing, moves questions without a scroll event.
  useEffect(() => { recomputePinned.current() }, [messages])

  // AN AGENT'S NAME CLICKED IN THE AGENTS MAP (threadReveal.ts): the latest turn, or the sub-agent's
  // panel - which opens and lights up by itself (SubAgentPanel). A frame late, so the map has
  // closed and the panel has begun to open. It is the reader's own scroll as far as following the
  // thread goes: a smooth scroll up holds the thread there by the thread's rule (threadFollow.ts).
  // A request made before this thread was mounted is not this thread's to act on.
  const reveal = useChatStore(s => s.reveal)
  const seenReveal = useRef(reveal)
  useEffect(() => {
    if (!reveal || reveal === seenReveal.current) return
    // Marked seen inside the frame, so a development build's second effect run still scrolls.
    const frame = requestAnimationFrame(() => {
      seenReveal.current = reveal
      const scroller = scrollerRef.current
      if (!scroller) return
      const target = reveal.target
      const el = target.kind === 'main'
        ? [...scroller.querySelectorAll<HTMLElement>('[data-turn]')].pop()
        : scroller.querySelector<HTMLElement>(`[data-sub-agent="${CSS.escape(target.agentId)}"]`)
      if (!el) return
      const box = el.getBoundingClientRect()
      scroller.scrollTo({
        top: revealScrollTop({
          kind: target.kind,
          top: box.top - scroller.getBoundingClientRect().top,
          height: box.height,
          scrollTop: scroller.scrollTop,
          viewHeight: scroller.clientHeight,
          endTop: scroller.scrollHeight - scroller.clientHeight,
          clearTop: PIN_BAR_PX + 12,
        }),
        behavior: scrollBehavior(),
      })
    })
    return () => cancelAnimationFrame(frame)
  }, [reveal])

  // The main agent's own questions; a sub-agent's are drawn by AgentPrompts.
  const ownQuestions = useLeavingItems(pendingQuestions.filter(q => !q.parentCallId), q => q.callId)

  if (loading) {
    return (
      <div className={cn('flex-1 overflow-y-auto py-5 short:py-3', gutter, !compact && 'sm:[scrollbar-gutter:stable_both-edges]')}>
        <ChatThreadSkeleton />
      </div>
    )
  }

  // Messages still waiting for the running turn sit at the END of the list (see threadOps) and
  // are drawn below everything the turn is doing - they have not been said to the agent yet.
  const settled = settledLength(messages)

  // Where each question starts a section - see the sticky header below. Anything before the first
  // question (a restored conversation that opens with the agent) is a section with no header.
  const sections: number[][] = []
  messages.slice(0, settled).forEach((m, i) => {
    if (m.role === 'user' || sections.length === 0) sections.push([i])
    else sections[sections.length - 1].push(i)
  })

  const renderMessage = (i: number, stickyQuestion: boolean) => {
    const m = messages[i]
    const ownToolCalls = m.toolCalls?.filter(notInAStep)
    const run = m.workflowRunId ? workflowRuns[m.workflowRunId] : undefined
    // A row that is tool calls and nothing else - the steps before a mid-turn message, or the last
    // steps of a turn that ended without an answer - has no bubble to draw.
    const stepsOnly = m.role === 'assistant' && !m.content && !m.error && !m.guardrail && m.stepLimit === undefined
    return (
      // Each row fades in as it joins the thread (opacity only - a transform here would hold every
      // fixed-position thing inside the row to the row).
      <div key={i} ref={stickyQuestion ? questionRef(i) : undefined} className="animate-fade-in">
        {/* One message that cannot be drawn says so in its own place and leaves the thread
            standing; a streamed message is tried again as its text grows. */}
        <MessageErrorBoundary resetKey={m.content}>
        {/* The tool calls belong to THIS message, and this message's own content is the answer
            they produced. The list is open, five rows tall at most - see ToolCallList.
            The conversation id is for a sub-agent's own Stop - one working in the background is
            held by the message that started it, however long ago. */}
        {ownToolCalls && ownToolCalls.length > 0 && (
          <ToolCallList toolCalls={ownToolCalls} conversationId={readOnly ? undefined : conversationId} />
        )}
        {m.subAgentReports && m.subAgentReports.length > 0 && <SubAgentReportedNotice reports={m.subAgentReports} />}
        {!stepsOnly && <MessageBubble message={m} onRetry={onRetry} isLast={i === messages.length - 1}
          endsTurn={i < messages.length - 1 ? messages[i + 1].role === 'user' : !streaming} conversationId={conversationId} allMessages={messages} messageIndex={i} streaming={streaming} onRewind={onRewind} readOnly={readOnly} />}
        {/* Below the message that invoked it, and therefore above the reply the run produces
            - the position the design asks for, reached by anchoring to the one message that
            is certain to exist. A run that fails in its first step never writes an assistant
            row at all, and the table has to survive exactly that. */}
        {FEATURES.workflows && run && <WorkflowStepTable run={run} toolCalls={toolCalls} />}
        </MessageErrorBoundary>
      </div>
    )
  }

  const live = (
    <>
        {showPendingTools && (
          <ToolCallList toolCalls={pendingToolCalls} pendingApprovals={pendingApprovals} pendingEnableRequests={pendingEnableRequests} conversationId={conversationId} />
        )}
        {/* The assistant's questions, on their own and always in view - the turn waits on them. One
            answered eases out (Presence, kept for its exit by useLeavingItems) rather than leaving
            the thread to jump up by its height. */}
        {conversationId && ownQuestions.map(({ item: q, key, leaving }) => (
          <Presence key={key} show={!leaving}>
            <div className="mb-5" data-turn-prompt="">
              {q.stepLimit
                ? <StepLimitPrompt pending={q} stepLimit={q.stepLimit} conversationId={conversationId} />
                : <QuestionPrompt pending={q} conversationId={conversationId} />}
            </div>
          </Presence>
        ))}
        {/* And what SUB-AGENTS are waiting on the user for - here rather than in their panels,
            which may be several turns up the thread. */}
        {conversationId && (
          <AgentPrompts
            conversationId={conversationId}
            approvals={pendingApprovals.filter(a => a.parentCallId)}
            enableRequests={pendingEnableRequests.filter(r => r.parentCallId)}
            questions={pendingQuestions.filter(q => q.parentCallId)}
            titleOf={agentId => findAgent(messages, toolCalls, agentId)?.title}
          />
        )}
        {/* The wait row eases in and out (Presence): it goes the moment the first words stream,
            and in one frame it pulled everything up by its height. */}
        <Presence show={!!face}>
          <div className="mb-5">
            {/* Keyed on the face, so each wait mounts afresh and its seconds count that wait alone. */}
            {face && <ThinkingIndicator key={face} face={face} />}
          </div>
        </Presence>
    </>
  )

  const pinned = pinnedIndex !== null ? messages[pinnedIndex] : undefined

  // Back to the whole question. Not scrollIntoView: that puts the question's first line at the very
  // top of the scroller, which is under the bar and its gradient - so the question came back with its
  // opening line unreadable. It lands PIN_BAR_PX plus a gap lower, clear of both.
  const showQuestion = (i: number) => {
    const scroller = scrollerRef.current
    const node = questionNodes.current.get(i)
    if (!scroller || !node) return
    const offset = node.getBoundingClientRect().top - scroller.getBoundingClientRect().top
    scroller.scrollTo({ top: scroller.scrollTop + offset - PIN_BAR_PX - 12, behavior: scrollBehavior() })
  }

  return (
    <div className="relative flex-1 min-h-0 flex flex-col">
      {/* The bar and its gradient come and go together, fading (Presence collapse={false}); the
          positioning is on the Presence box, so it is the one layer over the thread. Kept for its
          exit with the question it last showed. pointer-events-none on the gradient, so a click
          through it reaches the answer under it. */}
      <Presence show={!!pinned} collapse={false} className="absolute inset-x-0 top-0 z-raised pointer-events-none">
        <div aria-hidden="true" className="pin-fade absolute inset-x-0 top-0 h-16" />
        <div className={cn('relative', gutter)}>
          <div className={THREAD_COLUMN}>
            {pinned && pinnedIndex !== null && (
              <button
                type="button"
                data-pinned-question=""
                onClick={() => showQuestion(pinnedIndex)}
                // Its own name, short: the question itself can be screens long (CHAT-31).
                aria-label={t('Back to the question')}
                className="pointer-events-auto h-10 w-full flex items-center justify-end bg-transparent rounded-md focus-ring"
              >
                <span className="max-w-[78%] truncate text-sm font-medium text-ink-primary" aria-hidden="true">
                  {pinned.content}
                </span>
              </button>
            )}
          </div>
        </div>
      </Presence>
      {/* The gutter is reserved on BOTH sides, so the column stays centered on the same line as the
          composer and the pinned question, which sit outside this scroller. Reserved on one side only
          (the default), a 10px scrollbar moved every message 5px left of the composer - the
          maintainer's report, 2026-09-27.

          From `sm` up only. Below it the column is the whole width, with nothing to center it
          against, and two reserved gutters were margin a phone-width window does not have: the
          thread sat visibly further in than the composer under it (2026-10-02). The phone gutter
          is 8px, for the same reason - the messages are the page. */}
      <span role="status" className="sr-only">{announcement}</span>
      {/* RELATIVE, so the scroller is the containing block of every absolutely placed thing in the
          thread (0.36.0 feedback). Without it a screen-reader-only label in a tool row or a
          sub-agent's panel was placed against this component's root, outside the scroller, and
          the thread's whole height overflowed the chat page's own box - which a scroll to the end
          then scrolled (see scrollToEnd). */}
      <div ref={scrollerRef} className={cn('relative flex-1 min-h-0 overflow-y-auto py-5 short:py-3', gutter, !compact && 'sm:[scrollbar-gutter:stable_both-edges]')}>
        <div className={THREAD_COLUMN}>
          {sections.map((section, si) => (
            // One section per question: the user's message and every reply under it.
            <section key={section[0]} data-turn="">
              {section.map(i => renderMessage(i, i === section[0] && messages[i].role === 'user'))}
              {si === sections.length - 1 && live}
            </section>
          ))}
          {sections.length === 0 && live}
          {messages.slice(settled).map((_, k) => renderMessage(settled + k, false))}
        </div>
      </div>
    </div>
  )
}

// TO THE END OF THE THREAD, moving the thread's own scroller and nothing else (0.36.0 feedback,
// 2026-10-16: "after hit enter, the chat input jumped to the middle of the screen"). It was
// `scrollIntoView` on an empty marker after the last line, which scrolls EVERY scrollable ancestor
// until the marker is at its top - the chat page's own `overflow-hidden` box included, whenever
// anything overflowed it (the scroller's `relative` is the other half). That box cannot be
// scrolled back by the reader, so the composer stayed mid-screen over an empty band.
function scrollToEnd(scroller: HTMLElement | null) {
  scroller?.scrollTo({ top: scroller.scrollHeight, behavior: scrollBehavior() })
}

// A smooth scroll, except under reduced motion: an explicit `behavior` wins over the CSS that turns
// smooth scrolling off for it (CHAT-37).
function scrollBehavior(): ScrollBehavior {
  return prefersReducedMotion() ? 'auto' : 'smooth'
}

// The bar's height - h-10 is 40px - and the near-opaque band of .pin-fade in index.css. Change
// them together.
const PIN_BAR_PX = 40

// Milliseconds since `since`, re-rendering once the turn has been quiet for QUIET_MS - the one
// moment the answer can change - rather than ticking every second of a turn.
function useQuietFor(since: number, active: boolean): number {
  const [, force] = useState(0)
  useEffect(() => {
    if (!active) return
    const remaining = QUIET_MS - (Date.now() - since)
    if (remaining <= 0) return
    const timer = setTimeout(() => force(n => n + 1), remaining + 20)
    return () => clearTimeout(timer)
  }, [since, active])
  return Date.now() - since
}
