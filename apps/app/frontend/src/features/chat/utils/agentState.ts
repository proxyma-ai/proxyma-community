import type { WorkState } from '@/components/ui/kit'
import type { Translate } from '@/i18n'
import type { AgentLifetime, AgentStatus } from '../types/chat.types'

/**
 * How a deployed agent stands, as the kit's `StateMark` colors it - ONE mapping for every view of
 * the same agent: its panel in the thread (AgentPanel), its row in the agents map
 * (AgentsGraphDialog) and the strip over its own conversation (AgentStrip).
 *
 * AT WORK, HELD, OR FINISHED (maintainer's feedback, 2026-10-07, for the sub-agents this replaced:
 * running or finished, nothing else - a red or amber mark read as a verdict the report gives
 * better). At work is a task in hand or waiting on its own agents; held is paused by its parent or
 * the person, which is neither. How it ended is the status word beside the mark.
 */
export function agentWorkState(status: AgentStatus): WorkState {
  if (status === 'RUNNING' || status === 'WAITING') return 'running'
  if (status === 'PAUSED') return 'pending'
  return 'done'
}

/** Where a deployed agent stands, in a word or two - beside its mark everywhere it is shown. */
export function agentStatusLabel(status: AgentStatus, t: Translate): string {
  switch (status) {
    case 'RUNNING': return t('Working')
    case 'WAITING': return t('Waiting on its agents')
    case 'IDLE': return t('Idle')
    case 'PAUSED': return t('Paused')
    case 'DONE': return t('Done')
    case 'STOPPED': return t('Stopped')
    default: return t('Failed')
  }
}

/** How long a deployed agent lives: until its one task is reported, or on, taking more work. */
export function lifetimeLabel(lifetime: AgentLifetime, t: Translate): string {
  return lifetime === 'LONG' ? t('Stays on') : t('One task')
}
