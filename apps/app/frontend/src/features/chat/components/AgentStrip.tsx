import { useNavigate } from 'react-router-dom'
import { Bot, CornerLeftUp, Pause, Play, Square } from 'lucide-react'
import { Presence, StateMark, THREAD_COLUMN, Tooltip, cardClass, cn, iconButton, workingMotion } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { useAgentControl, useAgentTree } from '../hooks/useAgentTree'
import { controlsFor, isWorking } from '../utils/agentTree'
import { agentStatusLabel, agentWorkState, lifetimeLabel } from '../utils/agentState'

// A DEPLOYED AGENT'S OWN CONVERSATION says so, in a slim strip over its thread (since 2026-10-28,
// the tree of agents): "Agent: <title>", where it stands, who deployed it - a way back to that
// conversation - and Pause or Resume, and Stop. The person can type to it as in any conversation;
// what they say is forwarded to the agent that deployed it, so nothing here asks them to.
//
// Nothing is drawn for a conversation that is not a deployed agent's - the one the person opened.

export function AgentStrip({ conversationId, className }: { conversationId?: string; className?: string }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { self, agents, rootConversationId } = useAgentTree(conversationId)
  const { control, busy } = useAgentControl(conversationId)

  const parent = self ? agents.find(a => a.conversationId === self.parentConversationId) : undefined
  const deployedBy = !self ? ''
    : self.parentConversationId === rootConversationId || !parent
      ? t('the main agent')
      : parent.title || t('a deployed agent')
  const controls = self ? controlsFor(self) : null
  const held = !!self && busy === self.agentId
  const state = self ? (isWorking(self) ? 'running' : agentWorkState(self.status)) : 'done'
  const words = self
    ? (self.lifetime === 'LONG' ? `${agentStatusLabel(self.status, t)} - ${lifetimeLabel(self.lifetime, t)}` : agentStatusLabel(self.status, t))
    : ''

  return (
    // Eases in once the tree says this is a deployed agent's conversation.
    <Presence show={!!self} className={cn('shrink-0', className)}>
      {self && (
        <div className={cardClass({ padding: 'none', className: cn(THREAD_COLUMN, 'flex items-center gap-2 min-h-9 px-3 text-xs') })}>
          <StateMark icon={Bot} state={state} blink={false} className="shrink-0" />
          <span className={cn('min-w-0 truncate font-medium text-ink-primary', workingMotion('text', state === 'running'))}>
            {t('Agent: {title}', { title: self.title || t('Deployed agent') })}
          </span>
          <span className="shrink-0 text-ink-caption">{words}</span>
          <span className="flex-1" />
          <Tooltip label={t('Back to the conversation that deployed it')}>
            <button
              type="button"
              onClick={() => navigate(`/chat/${self.parentConversationId}`)}
              className="hidden sm:flex min-w-0 items-center gap-1 text-ink-caption mouse:hover:text-ink-primary transition-colors focus-ring rounded-sm"
            >
              <CornerLeftUp size={12} aria-hidden="true" className="shrink-0" />
              <span className="truncate">{t('Deployed by {name}', { name: deployedBy })}</span>
            </button>
          </Tooltip>
          {/* On a phone the way back is the icon alone. */}
          <Tooltip label={t('Deployed by {name}', { name: deployedBy })}>
            <button
              type="button"
              onClick={() => navigate(`/chat/${self.parentConversationId}`)}
              aria-label={t('Deployed by {name}', { name: deployedBy })}
              className={cn(iconButton('sm'), 'sm:hidden')}
            >
              <CornerLeftUp size={13} aria-hidden="true" />
            </button>
          </Tooltip>
          {controls?.pause && (
            <Tooltip label={t('Pause this agent')}>
              <button type="button" onClick={() => control(self.agentId, 'pause')} disabled={held} aria-label={t('Pause this agent')} className={iconButton('sm')}>
                <Pause size={12} aria-hidden="true" />
              </button>
            </Tooltip>
          )}
          {controls?.resume && (
            <Tooltip label={t('Resume this agent')}>
              <button type="button" onClick={() => control(self.agentId, 'resume')} disabled={held} aria-label={t('Resume this agent')} className={iconButton('sm')}>
                <Play size={12} aria-hidden="true" />
              </button>
            </Tooltip>
          )}
          {controls?.stop && (
            <Tooltip label={t('Stop this agent')}>
              <button type="button" onClick={() => control(self.agentId, 'stop')} disabled={held} aria-label={t('Stop this agent')} className={iconButton('sm', 'danger')}>
                <Square size={12} aria-hidden="true" />
              </button>
            </Tooltip>
          )}
        </div>
      )}
    </Presence>
  )
}
