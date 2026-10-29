import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowUpRight, Pause, Play, Square } from 'lucide-react'
import { Caption, SubAgentPanel as KitAgentPanel, Tooltip, iconButton } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { MarkdownRenderer } from './MarkdownRenderer'
import { useChatStore } from '../instance/ChatInstanceContext'
import { useAgentControl, useTreeAgent } from '../hooks/useAgentTree'
import { useElapsedText } from '../hooks/useServerNow'
import { controlsFor, deployResult, isBusy, isEnded, isWorking } from '../utils/agentTree'
import { agentStatusLabel, agentWorkState, lifetimeLabel } from '../utils/agentState'
import { extractErrorSummary } from '../utils/toolErrors'
import { isFailedCall } from '../utils/toolActivity'
import { toolIcon } from '../utils/toolIcon'
import type { AgentLifetime, AgentStatus, ToolCallInfo } from '../types/chat.types'

// AN AGENT THE AGENT DEPLOYED, where its `deployAgent` call stands in the thread (since 2026-10-28,
// the tree of agents). It works in a conversation of its own, so the panel does not draw its steps:
// it says who it is, where it stands - live, from the conversation's 'agent-status' events laid over
// the agent tree, else what the call returned - how long it lives, and offers Open (its own
// conversation, where the person can talk to it), Pause or Resume, and Stop. Its last report folds
// out under it.
//
// The kit's panel, as the sub-agents' was: the mark in the color of how it stands (agentWorkState),
// the name waving while it works, a dimmed word for where it stands, the time.

interface Props {
  call: ToolCallInfo
  /** The conversation the panel is in - absent on a read-only page, which offers no control. */
  conversationId?: string
  onOpenDetail: (tc: ToolCallInfo) => void
}

function argumentsOf(call: ToolCallInfo): { title?: string; lifetime?: AgentLifetime } {
  try {
    const a = JSON.parse(call.arguments) as { title?: unknown; lifetime?: unknown }
    return {
      title: typeof a.title === 'string' && a.title.trim() ? a.title.trim() : undefined,
      lifetime: a.lifetime === 'LONG' ? 'LONG' : a.lifetime === 'SHORT' ? 'SHORT' : undefined,
    }
  } catch {
    return {}
  }
}

export function AgentPanel({ call, conversationId, onOpenDetail }: Props) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [userExpanded, setUserExpanded] = useState<boolean | null>(null)
  const args = argumentsOf(call)
  const result = deployResult(call.done ? call.result : undefined)
  const refused = call.done && (isFailedCall(call) || result.success === false)
  const agentId = result.agentId
  const fromTree = useTreeAgent(conversationId, agentId)
  const live = useChatStore(st => (agentId ? st.agentStatus[agentId] : undefined))
  const { control, busy } = useAgentControl(conversationId)

  const status: AgentStatus = refused && !agentId ? 'FAILED'
    : fromTree?.status ?? live?.status ?? result.status ?? 'RUNNING'
  const title = fromTree?.title || live?.title || args.title || t('Deployed agent')
  const lifetime = fromTree?.lifetime ?? live?.lifetime ?? args.lifetime ?? 'SHORT'
  const childConversation = fromTree?.conversationId || live?.conversationId || result.conversationId
  const working = !call.done || (fromTree ? isWorking(fromTree) || isBusy(status) : isBusy(status))
  const state = working ? 'running' : agentWorkState(status)
  const report = fromTree?.lastReport ?? undefined
  const failure = refused ? (result.error ?? extractErrorSummary(call.result ?? '', t)) : undefined

  // Its own clock: from when it was deployed to when it ended, counting while it works.
  const startedAt = fromTree?.createdAt ? Date.parse(fromTree.createdAt) : call.startedAt
  const endedAt = fromTree?.endedAt ? Date.parse(fromTree.endedAt) : undefined
  const elapsed = useElapsedText([{
    callId: call.callId, toolName: call.toolName, arguments: '{}',
    done: !isBusy(status) && call.done, startedAt, endedAt: isEnded(status) ? endedAt : undefined,
  }])

  const hasBody = !!report || !!failure
  // Folded, unless deploying it failed: then why is the one thing to read.
  const expanded = userExpanded ?? !!failure
  const controls = controlsFor({ status, lifetime })
  const canControl = !!conversationId && !!agentId
  const held = busy === agentId

  const words = lifetime === 'LONG'
    ? `${agentStatusLabel(status, t)} - ${lifetimeLabel(lifetime, t)}`
    : agentStatusLabel(status, t)

  const actions = (canControl || (conversationId && childConversation)) ? (
    <>
      {conversationId && childConversation && (
        <Tooltip label={t('Open its conversation')}>
          <button type="button" onClick={() => navigate(`/chat/${childConversation}`)} aria-label={t('Open its conversation')} className={iconButton('sm')}>
            <ArrowUpRight size={14} aria-hidden="true" />
          </button>
        </Tooltip>
      )}
      {canControl && controls.pause && (
        <Tooltip label={t('Pause this agent')}>
          <button type="button" onClick={() => control(agentId!, 'pause')} disabled={held} aria-label={t('Pause this agent')} className={iconButton('sm')}>
            <Pause size={12} aria-hidden="true" />
          </button>
        </Tooltip>
      )}
      {canControl && controls.resume && (
        <Tooltip label={t('Resume this agent')}>
          <button type="button" onClick={() => control(agentId!, 'resume')} disabled={held} aria-label={t('Resume this agent')} className={iconButton('sm')}>
            <Play size={12} aria-hidden="true" />
          </button>
        </Tooltip>
      )}
      {canControl && controls.stop && (
        <Tooltip label={t('Stop this agent')}>
          <button type="button" onClick={() => control(agentId!, 'stop')} disabled={held} aria-label={t('Stop this agent')} className={iconButton('sm', 'danger')}>
            <Square size={12} aria-hidden="true" />
          </button>
        </Tooltip>
      )}
    </>
  ) : undefined

  return (
    // Marked with its agent's id, which the thread scrolls to when something sends the reader here.
    <div data-agent={agentId}>
      <KitAgentPanel
        icon={toolIcon(call.toolName)}
        title={title}
        state={state}
        backgroundLabel={words}
        elapsed={elapsed}
        actions={actions}
        onOpenTitle={() => onOpenDetail(call)}
        open={hasBody && expanded}
        onToggle={hasBody ? () => setUserExpanded(!expanded) : undefined}
        toggleLabels={{ show: t('Show its last report'), hide: t('Hide its last report') }}
      >
        {hasBody ? (
          failure
            ? <Caption tone="danger" className="whitespace-normal break-words">{failure}</Caption>
            : <MarkdownRenderer content={report ?? ''} className="prose-doc-sm text-ink-secondary break-words" />
        ) : undefined}
      </KitAgentPanel>
    </div>
  )
}
