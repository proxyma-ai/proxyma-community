import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Brain, SatelliteDish } from 'lucide-react'
import { ActivityLine, Caption } from '@/components/ui/kit'
import { useAgentModel } from '../hooks/useAgentModel'
import { modelWords } from '../utils/conversationModel'
import { AgentSettingsLink } from './AgentSettingsLink'
import { useEscalatingMessage } from '../hooks/useEscalatingMessage'
import { useScrambleText } from '../hooks/useScrambleText'
import { useChatStore } from '../instance/ChatInstanceContext'
import { useTranslation } from '@/i18n'
import type { WaitFace } from '../utils/waitState'
import { compactTokens } from '../utils/agentTree'

/**
 * Which wait the row under the conversation is showing - decided by ChatWindow's waitState, which
 * holds the full table of states.
 *
 * THREE FACES, since 2026-09-29 (the maintainer's rule):
 *  - `model`: a model call is out and the provider has not answered. A satellite dish,
 *    "Waiting for <provider> / <model>" and the seconds, plus the attempt when it is being retried.
 *    Removed on 2026-09-28 and put back the next day: a slow answer is almost always a slow
 *    provider, and this says which.
 *  - `thinking`: the model is thinking. A brain, "<provider> / <model> is thinking", and the token
 *    count as 1.2k or 3.4M - or the seconds, while no count has arrived.
 *  - `idle`: the turn is running and waiting on NOTHING the row can name - no tool call, no model
 *    call, no thinking, no text for a moment. The star names, typed out one scramble at a time.
 *
 * Each wait mounts it afresh, so its numbers count that wait alone.
 *
 * NO TEXT, NO COUNT. Claude Sonnet 5, Opus 4.7 and later stream their thinking blocks EMPTY unless
 * a request asks for display "summarized" (Anthropic's default there is "omitted": an empty
 * thinking_delta, then the signature), and no protocol streams a running thinking-token count. So
 * while the count is still zero it shows how long the model has been thinking instead - the one
 * honest number there is. A count takes over the moment there is text to count.
 */

export function ThinkingIndicator({ face }: { face: WaitFace }) {
  const thinkingTokens = useChatStore(s => s.thinkingTokens)
  if (face === 'thinking') return <ThinkingTokens count={thinkingTokens ?? 0} />
  if (face === 'model') return <WaitingForModel />
  return <WaitingMessage />
}

/** Seconds since this row mounted, ticking once a second while `running`. */
function useSecondsSinceMount(running = true): number {
  const [startedAt] = useState(() => Date.now())
  const [now, setNow] = useState(startedAt)
  useEffect(() => {
    if (!running) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [running])
  return Math.floor((now - startedAt) / 1000)
}

function WaitingForModel() {
  const { t } = useTranslation()
  // The model the open conversation runs on - an agent's own, in a lead chat - not always the chat's.
  const agent = useAgentModel(useChatStore(s => s.conversationId))
  const retryAttempt = useChatStore(s => s.retryAttempt)
  const seconds = useSecondsSinceMount()
  // A reader who may not see the chat settings (a user on Enterprise Server) gets the unnamed form.
  const label = agent
    ? t('Waiting for {model}', { model: modelWords(agent) })
    : t('Waiting for the model')
  return (
    // The kit's ActivityLine, as the thinking line and a tool group's header are: the mark in the
    // label's caption ink, a wave across the words - one line, one color. The retry (the line's `note`, in
    // warning ink) and the seconds follow the words. NOT a live region: its seconds change every
    // second, and a screen reader was told each one (CHAT-06). The thread says when an answer is
    // ready (ChatWindow's status line).
    <div>
      <NamesModel named={!!agent && !agent.own}>
        <ActivityLine
          icon={SatelliteDish}
          state="running"
          label={label}
          maxWidth="max-w-full"
          note={retryAttempt !== null ? t('retry {n}', { n: retryAttempt }) : undefined}
          elapsed={seconds > 0 ? t('{seconds}s', { seconds }) : undefined}
        />
      </NamesModel>
    </div>
  )
}

function ThinkingTokens({ count }: { count: number }) {
  const { t } = useTranslation()
  const agent = useAgentModel(useChatStore(s => s.conversationId))
  // Mounted when thinking starts, so mount time is when it started. Stops ticking once there is a
  // count to show instead.
  const seconds = useSecondsSinceMount(count === 0)
  // A reader who may not see the chat settings (a user on Enterprise Server) gets the unnamed form.
  const label = agent
    ? t('{model} is thinking', { model: modelWords(agent) })
    : t('Thinking')
  return (
    // The kit's ActivityLine: the mark and the words are one line, one color, a wave across the words.
    // Not a live region, for the reason above.
    <div>
      <NamesModel named={!!agent && !agent.own}>
      <ActivityLine
        icon={Brain}
        state="running"
        label={label}
        maxWidth="max-w-full"
        elapsed={count > 0
          // The one token readout the chat uses (CHAT-23): 12.3k here as in the agents map.
          ? t('{count} tokens', { count: compactTokens(count) })
          : seconds > 0 ? t('{seconds}s', { seconds }) : undefined}
      />
      </NamesModel>
      <ThinkingStream />
    </div>
  )
}

/**
 * WHAT THE MODEL THINKS, as it streams (2026-11-11): its newest lines under the thinking row, the box following the end
 * while the reader has not scrolled it up. Nothing while the provider sends no thinking text (most models other than
 * Claude's, which the server asks for its summary - AnthropicThinkingDisplay).
 */
function ThinkingStream() {
  const { t } = useTranslation()
  const text = useChatStore(s => s.thinkingText)
  const box = useRef<HTMLDivElement>(null)
  const follow = useRef(true)
  useEffect(() => {
    const el = box.current
    if (el && follow.current) el.scrollTop = el.scrollHeight
  }, [text])
  if (!text) return null
  return (
    <div ref={box} aria-label={t('What the model is thinking')}
      onScroll={e => {
        const el = e.currentTarget
        follow.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24
      }}
      className="mt-1.5 ml-6 max-h-28 overflow-y-auto whitespace-pre-wrap break-words border-l-2 border-line pl-3 text-xs leading-5 text-ink-caption">
      {text}
    </div>
  )
}

// A row that names the chat's model opens Settings > Agents on a click (AgentSettingsLink); one that
// does not - the reader may not see those settings, or the model is an agent's own, chosen elsewhere -
// is the row alone.
function NamesModel({ named, children }: { named: boolean; children: ReactNode }) {
  return named ? <AgentSettingsLink>{children}</AgentSettingsLink> : <>{children}</>
}

function WaitingMessage() {
  const message = useEscalatingMessage(undefined, true)
  const { display, done } = useScrambleText(message ?? '')
  if (!message) return null

  return (
    // Words, so the UI face - not the readout face, which is for figures. The scramble rewrites the
    // text every few milliseconds, so it is hidden from assistive technology, which reads the name
    // once (CHAT-06).
    <Caption as="div" className="font-medium">
      <span aria-hidden="true">{display}</span>
      <span className="sr-only">{message}</span>
      <span className={done ? 'animate-blink' : 'opacity-0'} aria-hidden="true">_</span>
    </Caption>
  )
}
