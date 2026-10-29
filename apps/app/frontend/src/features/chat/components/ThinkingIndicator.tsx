import { useEffect, useState, type ReactNode } from 'react'
import { Brain, SatelliteDish } from 'lucide-react'
import { ActivityLine, Caption } from '@/components/ui/kit'
import { useAgentModel } from '../hooks/useAgentModel'
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
  const agent = useAgentModel()
  const retryAttempt = useChatStore(s => s.retryAttempt)
  const seconds = useSecondsSinceMount()
  // A reader who may not see the chat settings (a user on Enterprise Server) gets the unnamed form.
  const label = agent
    ? t('Waiting for {model}', { model: agent.provider ? `${agent.provider} / ${agent.model}` : agent.model })
    : t('Waiting for the model')
  return (
    // The kit's ActivityLine, as the thinking line and a tool group's header are: the mark in the
    // label's caption ink, a wave across the words - one line, one color. The retry (the line's `note`, in
    // warning ink) and the seconds follow the words. NOT a live region: its seconds change every
    // second, and a screen reader was told each one (CHAT-06). The thread says when an answer is
    // ready (ChatWindow's status line).
    <div>
      <NamesModel named={!!agent}>
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
  const agent = useAgentModel()
  // Mounted when thinking starts, so mount time is when it started. Stops ticking once there is a
  // count to show instead.
  const seconds = useSecondsSinceMount(count === 0)
  // A reader who may not see the chat settings (a user on Enterprise Server) gets the unnamed form.
  const label = agent
    ? t('{model} is thinking', { model: agent.provider ? `${agent.provider} / ${agent.model}` : agent.model })
    : t('Thinking')
  return (
    // The kit's ActivityLine: the mark and the words are one line, one color, a wave across the words.
    // Not a live region, for the reason above.
    <div>
      <NamesModel named={!!agent}>
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
    </div>
  )
}

// A row that names the model opens Settings > Agents on a click (AgentSettingsLink); one that does
// not - the reader may not see those settings - is the row alone.
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
