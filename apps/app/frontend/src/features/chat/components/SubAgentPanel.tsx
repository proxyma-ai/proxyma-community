import { useEffect, useRef, useState } from 'react'
import { Brain, SatelliteDish } from 'lucide-react'
import { ActivityLine, Caption, Presence, SubAgentPanel as KitSubAgentPanel, cn } from '@/components/ui/kit'
import { useToastStore } from '@/store/toastStore'
import { withReason } from '@/services/apiError'
import { chatApi } from '../services/chatApi'
import { useElapsedText, useServerNow } from '../hooks/useServerNow'
import { MarkdownRenderer } from './MarkdownRenderer'
import { ToolCallRow } from './ToolCallRow'
import { extractErrorSummary } from '../utils/toolErrors'
import { compactTokens } from '../utils/agentsGraph'
import { useSubAgentModel } from '../hooks/useAgentModel'
import { useFollowingBox } from '../hooks/useFollowingBox'
import { isFailedCall } from '../utils/toolActivity'
import { agentAsTimedCall, itemAsCall } from '../utils/subAgent'
import { revealsAgent } from '../utils/threadReveal'
import { useChatStore } from '../instance/ChatInstanceContext'
import { agentWorkState } from '../utils/agentState'
import { toolIcon } from '../utils/toolIcon'
import type { SubAgentInfo, ToolCallInfo } from '../types/chat.types'
import { useTranslation } from '@/i18n'

// One sub-agent: its whole run, in a box of its own.
//
// At the level of the tool groups around it, full width, and OPEN while it works (maintainer's
// feedback, 2026-10-01). Open is safe here because the body has a fixed ceiling - five rows, since
// the 0.29.2 feedback - and scrolls inside itself: however much arrives, the thread under the reader moves by at most that ceiling, once.
// It folds to its header when it ends, however it ended - see `expanded`.
//
// SINCE 2026-10-02 A SUB-AGENT IS THE MAIN AGENT'S EQUAL, and its panel is a small thread of its
// own: what it says and the tools it calls, in the order it works, the same rows the main agent's
// calls are; under them, whichever is true now - waiting on its model, thinking, or a step
// running (the row itself spins); and, when it has ended, its report. One that works in the
// BACKGROUND says so in its header and stays where it was started, however far the conversation
// has moved on since; it has a Stop of its own, because the conversation's Stop is the turn's.
//
// Its approval prompts and questions are NOT in here. They are drawn at the foot of the thread,
// named, where every other prompt is and where the reader is looking (ChatWindow).

interface Props {
  call: ToolCallInfo
  // A delegation stored before 2026-10-02 has no sub-agent on its call - only these, which the
  // live stream of that time nested under it, and its result.
  steps: ToolCallInfo[]
  conversationId?: string
  onOpenDetail: (tc: ToolCallInfo) => void
}

// How long a panel stays lit once the agents map sent the reader to it - the length of the kit's
// `.animate-reveal`, which eases the light in and fades it out within it.
const REVEAL_MS = 2000

function titleOf(call: ToolCallInfo): string | undefined {
  if (call.agent?.title?.trim()) return call.agent.title.trim()
  try {
    const title = JSON.parse(call.arguments)?.title
    return typeof title === 'string' && title.trim() ? title.trim() : undefined
  } catch {
    return undefined
  }
}

// What a delegation with no sub-agent on it has to show: the old JSON report's verdict and summary.
function legacyReport(result?: string): { status?: string; summary?: string } {
  try {
    const obj = JSON.parse(result ?? '')
    return obj && typeof obj === 'object' ? obj as { status?: string; summary?: string } : {}
  } catch {
    return {}
  }
}

export function SubAgentPanel({ call, steps, conversationId, onOpenDetail }: Props) {
  const { t } = useTranslation()
  // Null until the reader works the chevron, after which their choice stands.
  const [userExpanded, setUserExpanded] = useState<boolean | null>(null)
  const [stopping, setStopping] = useState(false)
  const agent = call.agent
  const legacy = agent ? {} : legacyReport(call.done && !isFailedCall(call) ? call.result : undefined)
  const status = agent?.status
    ?? (!call.done ? 'running' : isFailedCall(call) ? 'failed' : legacy.status === 'PARTIAL' ? 'partial' : 'completed')
  const running = status === 'running'
  // Open while it works, and FOLDED TO ITS HEADER ONCE IT HAS ENDED (maintainer's feedback,
  // 2026-10-02): five finished sub-agents were five open boxes between the reader and the answer
  // made from them. The header says how it went and how long it took; the report is one click
  // away. One that failed, stopped or came back partial folds too, since 2026-10-03 (maintainer's
  // feedback) - its header says how it ended, and the main agent says what went wrong.
  const expanded = userExpanded ?? running
  const AgentIcon = toolIcon(call.toolName)
  const items = agent?.items ?? []
  const lastText = items.length > 0 ? items[items.length - 1].text?.length ?? 0 : 0
  const elapsed = useElapsedText([agentAsTimedCall(call)])

  // SENT HERE FROM THE AGENTS MAP (maintainer's request, 2026-10-14): its name there goes to this
  // panel, which opens - the reader's choice, as a click on the chevron is - and lights up for a
  // moment (a fade in and out, not a flash). The thread does the scrolling (ChatWindow). A request
  // made before this panel was mounted is an old one.
  const reveal = useChatStore(st => st.reveal)
  const seenReveal = useRef(reveal)
  const [lit, setLit] = useState(false)
  useEffect(() => {
    if (reveal === seenReveal.current) return
    seenReveal.current = reveal
    if (!revealsAgent(reveal, agent?.id)) { setLit(false); return }
    setUserExpanded(true)
    setLit(true)
    const timer = window.setTimeout(() => setLit(false), REVEAL_MS)
    return () => window.clearTimeout(timer)
  }, [reveal, agent?.id])

  // The body follows its newest line while the sub-agent works, by the thread's rule: a scroll in
  // it holds it where the reader put it for a while, then it catches up (useFollowingBox).
  const body = useFollowingBox([items.length, lastText, steps.length, agent?.awaitingModelSince, agent?.thinkingTokens, status, expanded])

  const stop = async () => {
    if (!agent || !conversationId || stopping) return
    setStopping(true)
    try {
      await chatApi.stopSubAgent(conversationId, agent.id)
    } catch (e) {
      // 404 is one that finished first - its panel is about to say so, and there is nothing to add.
      const gone = (e as { response?: { status?: number } }).response?.status === 404
      if (!gone) useToastStore.getState().addToast(withReason(t('Could not stop the sub-agent'), e), 'danger')
      setStopping(false)
    }
  }

  return (
    // The kit's panel, shared with the site's product scenes. The agent's mark says how it stands,
    // as a tool row's does: caption ink while it works, brand ink once it finished, however it ended
    // (agentWorkState - the agents graph reads the same mapping). It replaced a spinner, check or cross at the other end of the
    // line. The name opens what it was asked, as a tool row's name opens its arguments, and waves
    // while the agent works (0.27.2 feedback; a wave since 2026-10-14); "Background" beside it is a dimmed
    // word rather than a badge.
    //
    // The wrapper marks it with its agent's id, which the thread scrolls to when the agents map
    // sends the reader here (ChatWindow).
    <div data-sub-agent={agent?.id}>
      <KitSubAgentPanel
        icon={AgentIcon}
        title={titleOf(call) ?? t('Sub-agent')}
        state={agentWorkState(status)}
        backgroundLabel={agent?.mode === 'async' ? t('Background') : undefined}
        elapsed={elapsed}
        onOpenTitle={() => onOpenDetail(call)}
        onStop={agent && conversationId ? stop : undefined}
        stopLabel={t('Stop this sub-agent')}
        stopping={stopping}
        open={expanded}
        highlight={lit}
        onToggle={() => setUserExpanded(!expanded)}
        toggleLabels={{ show: t('Show steps'), hide: t('Hide steps') }}
        bodyRef={body.ref}
        onBodyScroll={body.onScroll}
      >
        <SubAgentSteps call={call} steps={steps} onOpenDetail={onOpenDetail} />
      </KitSubAgentPanel>
    </div>
  )
}

/**
 * What a sub-agent did, as its panel's body shows it: what it said and the tools it called, in
 * order; under them what it is doing now; and, once it has ended, its report - or, for a
 * delegation stored before 2026-10-02, its steps and verdict. Only the thread draws it since the
 * 0.36.0 feedback - the agents map, which drew it under a sub-agent's row, now sends the reader to
 * this panel instead.
 */
function SubAgentSteps({ call, steps, onOpenDetail }: {
  call: ToolCallInfo
  steps: ToolCallInfo[]
  onOpenDetail: (tc: ToolCallInfo) => void
}) {
  const { t } = useTranslation()
  const agent = call.agent
  const items = agent?.items ?? []
  const legacy = agent ? {} : legacyReport(call.done && !isFailedCall(call) ? call.result : undefined)
  const status = agent?.status
    ?? (!call.done ? 'running' : isFailedCall(call) ? 'failed' : legacy.status === 'PARTIAL' ? 'partial' : 'completed')
  const running = status === 'running'
  return (
    <>
      {agent
        ? items.map((item, i) => item.kind === 'text' ? (
            // As the markdown it is, WHILE it is written - the main agent's answer is, and this
            // was raw `**` and `###` until the sub-agent ended and its last paragraph became the
            // report below (maintainer's feedback, 2026-10-02). At the panel's own 13px
            // (`prose-doc-sm`), beside the 13px steps it sits among.
            <MarkdownRenderer
              key={`text-${item.segment ?? i}`}
              content={item.text ?? ''}
              className="prose-doc-sm text-ink-secondary break-words"
            />
          ) : (
            <ToolCallRow key={`tool-${i}`} tc={itemAsCall(item, i)} onOpenDetail={() => onOpenDetail(itemAsCall(item, i))} />
          ))
        : steps.map(step => (
            <ToolCallRow key={step.callId} tc={step} onOpenDetail={() => onOpenDetail(step)} />
          ))}
      {/* What it is doing now eases in and out as it starts and stops waiting (CHAT-08). */}
      <Presence show={!!agent && running}>
        {agent && running && <Working agent={agent} />}
      </Presence>
      {agent ? <Report agent={agent} separated={items.length > 0} /> : (
        <>
          <Presence show={status === 'failed'}>
            {status === 'failed' && <Caption tone="danger" className="whitespace-normal break-words">{extractErrorSummary(call.result!, t)}</Caption>}
          </Presence>
          <Presence show={status !== 'failed' && !!legacy.summary}>
            <Caption className={cn('whitespace-pre-wrap break-words', steps.length > 0 && 'pt-1')}>
              {status === 'partial' && <span className="text-warning">{t('Partial result')}: </span>}
              {legacy.summary}
            </Caption>
          </Presence>
        </>
      )}
    </>
  )
}

// What it is doing when no step of its own is on screen saying so: waiting on its model, or
// thinking. A step running says it itself, and so does text arriving.
function Working({ agent }: { agent: SubAgentInfo }) {
  const { t } = useTranslation()
  const model = useSubAgentModel()
  const waiting = agent.awaitingModelSince !== undefined
  const now = useServerNow(waiting)
  if (agent.thinkingTokens !== undefined) {
    return (
      <ActivityLine
        icon={Brain}
        state="running"
        label={t('Thinking')}
        elapsed={agent.thinkingTokens > 0 ? t('{count} tokens', { count: compactTokens(agent.thinkingTokens) }) : undefined}
        maxWidth="max-w-full"
      />
    )
  }
  if (!waiting || agent.items.some(i => i.kind === 'tool' && !i.done)) return null
  const seconds = Math.max(0, Math.floor((now - agent.awaitingModelSince!) / 1000))
  // "Waiting for <provider> / <model>" - the same line the main agent's wait shows under the
  // thread, for the sub-agent's own model and counted from when ITS call went out.
  const label = model
    ? t('Waiting for {model}', { model: model.provider ? `${model.provider} / ${model.model}` : model.model })
    : t('Waiting for the model')
  return (
    <ActivityLine
      icon={SatelliteDish}
      state="running"
      label={label}
      elapsed={seconds > 0 ? t('{seconds}s', { seconds }) : undefined}
      maxWidth="max-w-full"
    />
  )
}

// What it reported, once it has ended - the same text the main agent is given. Rendered as the
// markdown it is, at the panel's own size, with no verdict above it: a sub-agent is running or
// finished (maintainer's feedback, 2026-10-07), and the report says what it found.
function Report({ agent, separated }: { agent: SubAgentInfo; separated: boolean }) {
  // Eases in when the sub-agent ends with its report.
  return (
    <Presence show={agent.status !== 'running' && !!agent.report}>
      <div className={cn('text-xs', separated && 'pt-1')}>
        <MarkdownRenderer content={agent.report ?? ''} className="prose-doc-sm text-ink-secondary break-words" />
      </div>
    </Presence>
  )
}
