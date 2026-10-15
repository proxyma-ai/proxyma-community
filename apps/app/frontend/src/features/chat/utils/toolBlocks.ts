import type { ToolCallInfo } from '../types/chat.types'

// How a run of tool calls is laid out: as BLOCKS, in the order the agent worked.
//
// A sub-agent is a block of its own, at the level of the tool groups around it - not a row inside
// one. The agent may call tools, hand work to sub-agents, then call tools again, and the thread
// shows exactly that: a group, the sub-agents, a group. It used to be one collapsed list with the
// delegations as rows in it, each hiding its own work behind a second expander, so the longest
// thing a turn does was the least visible (maintainer's feedback, 2026-10-01).
//
// Plain .ts with a type-only import, so `node --test` reaches it - toolBlocks.test.ts.

export const DELEGATE = 'delegate'

export type ToolBlock =
  // Consecutive calls the main agent made itself.
  | { kind: 'tools'; calls: ToolCallInfo[] }
  // One delegation, with everything its sub-agent did.
  | { kind: 'agent'; call: ToolCallInfo; steps: ToolCallInfo[] }

/**
 * Splits `toolCalls` - one message's, or the turn's still unclaimed ones - into blocks.
 *
 * A call carrying a parentCallId belongs to that delegation and is listed in its `steps`. One
 * whose parent is not in this list falls back to an ordinary call: dropping it would hide work
 * that happened, which is the one thing this view must not do.
 */
export function toolBlocks(toolCalls: ToolCallInfo[]): ToolBlock[] {
  const ids = new Set(toolCalls.map(tc => tc.callId))
  const steps = new Map<string, ToolCallInfo[]>()
  for (const tc of toolCalls) {
    if (!tc.parentCallId || !ids.has(tc.parentCallId)) continue
    const list = steps.get(tc.parentCallId)
    if (list) list.push(tc)
    else steps.set(tc.parentCallId, [tc])
  }
  const blocks: ToolBlock[] = []
  for (const tc of toolCalls) {
    if (tc.parentCallId && ids.has(tc.parentCallId)) continue
    if (tc.toolName === DELEGATE) {
      blocks.push({ kind: 'agent', call: tc, steps: steps.get(tc.callId) ?? [] })
      continue
    }
    const last = blocks[blocks.length - 1]
    if (last?.kind === 'tools') last.calls.push(tc)
    else blocks.push({ kind: 'tools', calls: [tc] })
  }
  return blocks
}

/**
 * How long a set of calls took, start of the first to end of the last, in milliseconds.
 *
 * `now` is the server's clock as best this page knows it, and stands in for the end of a call
 * still running. Undefined when no call recorded a start - a conversation stored before calls
 * were timed - and for a finished set missing an end, since a guess there would be shown as fact.
 */
export function elapsedMs(calls: ToolCallInfo[], now: number): number | undefined {
  let start: number | undefined
  let end = 0
  for (const tc of calls) {
    if (tc.startedAt === undefined) continue
    start = start === undefined ? tc.startedAt : Math.min(start, tc.startedAt)
    if (!tc.done) end = Math.max(end, now)
    else if (tc.endedAt !== undefined) end = Math.max(end, tc.endedAt)
  }
  if (start === undefined || end === 0) return undefined
  return Math.max(0, end - start)
}

/** Whole seconds, minutes and hours of a duration - the parts formatElapsed lays out. */
export function elapsedParts(ms: number): { hours: number; minutes: number; seconds: number } {
  const total = Math.floor(ms / 1000)
  return { hours: Math.floor(total / 3600), minutes: Math.floor((total % 3600) / 60), seconds: total % 60 }
}
