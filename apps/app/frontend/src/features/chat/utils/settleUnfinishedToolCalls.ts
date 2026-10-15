import { agentDone } from './subAgent.ts'
import type { ToolCallInfo } from '../types/chat.types'

/**
 * The one tool-result error this CLIENT writes rather than receives.
 *
 * Exported so the row that renders it can translate it against the same constant instead of a
 * retyped copy of the sentence - every other value in a result is the backend's own English and is
 * shown as it arrives.
 */
export const INTERRUPTED = 'Interrupted - this turn ended before the tool reported back'

/**
 * Marks every tool call that never reported back as finished.
 *
 * A tool row leaves its "running" animation on a 'tool-result' event and on nothing else, so any
 * call that never produced one animates forever. Two boundaries produce such rows:
 *
 * - HISTORY. A call read back from a stored conversation cannot still be running: the turn that
 *   issued it ended and whatever was executing it is long gone. But `done` is persisted as
 *   whatever it was at the last write, so a turn that was aborted - Stop, a closed browser, a
 *   server restart mid-call - leaves rows with `done: false` in the database forever. Reopening
 *   that conversation rendered them exactly like live calls: a trace header spinning as though
 *   work were happening, reporting on a process that had not existed for days.
 *
 * - THE END OF A TURN. Stop makes the backend hand the model a synthetic result for each tool it
 *   stopped waiting on, and it publishes a 'tool-result' for those - but a turn can still end with
 *   a row unaccounted for (a dropped SSE connection, an older build on the other end). The turn is
 *   over either way, so nothing in this transcript will ever move again.
 *
 * They are settled as FAILED rather than succeeded. We do not know what became of the call, and
 * the two possible readings are not equally safe: showing a green check on a write tool whose
 * result was never recorded claims an action completed when it may never have run at all.
 * `success: false` is the shape ToolCallList's own isFailedResult() looks for, so these render
 * with the same red marker and summary as any other failure.
 *
 * Applied at those two boundaries only. Calls DURING a streaming turn are genuinely in flight and
 * must keep their pulse.
 */
export function settleUnfinishedToolCalls(toolCalls?: ToolCallInfo[]): ToolCallInfo[] | undefined {
  if (!toolCalls?.length) return toolCalls
  if (toolCalls.every(tc => tc.done)) return toolCalls   // the common case, unchanged
  return toolCalls.map(tc =>
    tc.done
      ? tc
      : {
          ...tc,
          // The sub-agent the call was waiting for ended with it. (One working in the BACKGROUND
          // is never here: its call returned the moment it started.)
          agent: tc.agent?.status === 'running' ? agentDone(tc.agent, 'stopped', INTERRUPTED) : tc.agent,
          done: true,
          // Only when nothing was recorded. A call that stored a result and was never flagged done
          // keeps the result it has, which is more informative than this placeholder.
          result: tc.result ?? JSON.stringify({
            success: false,
            error: INTERRUPTED,
          }),
        },
  )
}
