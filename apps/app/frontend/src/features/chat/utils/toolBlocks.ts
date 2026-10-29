import type { ToolCallInfo } from '../types/chat.types'

// How a run of tool calls is laid out: as BLOCKS, in the order the agent worked.
//
// An agent the agent DEPLOYED is a block of its own, at the level of the tool groups around it -
// not a row inside one (since 2026-10-28, the tree of agents: a `deployAgent` call is the agent's
// panel). The agent may call tools, deploy agents, then call tools again, and the thread shows
// exactly that: a group, the agents, a group. What a deployed agent does is in its own
// conversation, which its panel opens - nothing of it is nested here.
//
// A call of a tool that no longer exists - `delegate` and the sub-agent tools of conversations
// stored before then - is an ordinary call in its group, as is anything a stored delegation once
// nested under itself.
//
// Plain .ts with a type-only import, so `node --test` reaches it - toolBlocks.test.ts.

export const DEPLOY_AGENT = 'deployAgent'

export type ToolBlock =
  // Consecutive calls the agent made itself.
  | { kind: 'tools'; calls: ToolCallInfo[] }
  // One agent it deployed.
  | { kind: 'agent'; call: ToolCallInfo }

/** Splits `toolCalls` - one message's, or the turn's still unclaimed ones - into blocks. */
export function toolBlocks(toolCalls: ToolCallInfo[]): ToolBlock[] {
  const blocks: ToolBlock[] = []
  for (const tc of toolCalls) {
    if (tc.toolName === DEPLOY_AGENT) {
      blocks.push({ kind: 'agent', call: tc })
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
