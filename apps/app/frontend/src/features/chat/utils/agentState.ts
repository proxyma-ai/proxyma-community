import type { WorkState } from '@/components/ui/kit'
import type { SubAgentStatus } from '../types/chat.types'

/**
 * How a sub-agent stands, as the kit's `StateMark` colors it - ONE mapping for every view of the
 * same agent: its panel in the thread (SubAgentPanel), its row in the agents graph
 * (AgentsGraphDialog) and the line where its report reached the main agent (AgentPrompts).
 *
 * RUNNING OR FINISHED, nothing else (maintainer's feedback, 2026-10-07). It drew a partial report
 * amber, a failed one red and a stopped one quiet, with the word beside it; what a sub-agent found,
 * or did not, is in its report, which the main agent reads and answers from.
 */
export function agentWorkState(status: SubAgentStatus): WorkState {
  return status === 'running' ? 'running' : 'done'
}
