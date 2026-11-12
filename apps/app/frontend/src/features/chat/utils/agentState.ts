import type { WorkState } from '@/components/ui/kit'
import type { Translate } from '@/i18n'
import type { AgentLifetime, AgentStatus } from '../types/chat.types'
import type { MainAgentStatus, StopReason } from './agentTree'

/**
 * How a deployed agent stands, as the kit's `StateMark` colors it - ONE mapping for every view of
 * the same agent: its panel in the thread (AgentPanel), its row in the agents map
 * (AgentsGraphDialog) and the header of its transcript (AgentTranscriptDialog).
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

/**
 * Where a deployed agent stands with why it ended, where the server says why (stopReasonOf) - the
 * agents map's status (the maintainer's feedback, 2026-11-02: "the title keeps the status, e.g.
 * working, finished, stopped - timeout/max cost"). Without a reason it is the status word alone.
 */
export function agentStatusTitle(status: AgentStatus, reason: StopReason | null, t: Translate): string {
  switch (reason) {
    case 'DEADLINE': return t('Stopped - time limit')
    case 'BUDGET': return t('Stopped - budget spent')
    case 'RUN_LIMIT': return t('Stopped - run limit reached')
    case 'PARENT': return t('Stopped by its parent agent')
    case 'USER': return t('Stopped by you')
    case 'RATE_LIMITED': return t('Failed - rate limited')
    default: return agentStatusLabel(status, t)
  }
}

/** How long a deployed agent lives: until its one task is reported, or on, taking more work. */
export function lifetimeLabel(lifetime: AgentLifetime, t: Translate): string {
  return lifetime === 'LONG' ? t('Stays on') : t('One task')
}

/**
 * Where the main agent stands, in words (mainAgentStatus) - its row in the agents map, which said
 * nothing while it was idle (the maintainer's feedback, 2026-11-03).
 */
export function mainAgentStatusTitle(status: MainAgentStatus, t: Translate): string {
  switch (status) {
    case 'APPROVAL': return t('Waiting for approval')
    case 'QUESTION': return t('Waiting for your answer')
    case 'RATE_LIMITED': return t('Rate limited, waiting to retry')
    case 'WORKING': return t('Working')
    case 'WAITING': return t('Waiting on its agents')
    default: return t('Idle')
  }
}

/** The main agent's mark: at work while it works or waits on its own work, held while it waits for the person. */
export function mainAgentWorkState(status: MainAgentStatus): WorkState {
  if (status === 'APPROVAL' || status === 'QUESTION') return 'pending'
  if (status === 'IDLE') return 'done'
  return 'running'
}
