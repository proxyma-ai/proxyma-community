// The agents graph's arithmetic - what each agent spent, its share of the whole, how long it ran.
// Plain functions with no imports, so `node --test` reaches them (agentsGraph.test.ts).
//
// The shapes mirror ConversationAgents on the backend. The main agent's figure is DERIVED there:
// the conversation's total less what its sub-agents recorded, so the three always add up.

export interface Spend {
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  costUsd: number
}

export type SubAgentRunStatus = 'running' | 'completed' | 'partial' | 'failed' | 'stopped'

export interface SubAgentSpend extends Spend {
  id: string
  title: string
  mode: 'sync' | 'async'
  status: SubAgentRunStatus
  startedAt: number | null
  endedAt: number | null
  provider: string | null
  model: string | null
  // How full its context was at its last call, and the window it ran against; 0 where unmeasured.
  contextTokens?: number
  contextWindow?: number
}

/** One turn of the main agent, server clock; `endedAt` null while it still runs. */
export interface Turn {
  startedAt: number
  endedAt: number | null
}

export interface ConversationAgents {
  main: Spend
  subAgents: SubAgentSpend[]
  total: Spend
  // The main agent's turns - absent from a server older than 0.29.2.
  turns?: Turn[]
}

/** Every token an agent's calls carried, cached ones included. */
export function tokensOf(s: Spend): number {
  return (s.inputTokens ?? 0) + (s.outputTokens ?? 0) + (s.cacheReadTokens ?? 0) + (s.cacheWriteTokens ?? 0)
}

/** The cache's part: tokens read from it and written to it. */
export function cachedOf(s: Spend): number {
  return (s.cacheReadTokens ?? 0) + (s.cacheWriteTokens ?? 0)
}

/** A token count as a short readout: 950, 12.3k, 200k, 5.1M - a long conversation runs into millions. */
export function compactTokens(n: number): string {
  if (!Number.isFinite(n) || n < 1000) return String(Math.max(0, Math.round(n || 0)))
  const [value, unit] = n < 1_000_000 ? [n / 1000, 'k'] : [n / 1_000_000, 'M']
  return `${value.toFixed(1).replace(/\.0$/, '')}${unit}`
}

/**
 * An agent's share of the conversation, 0 to 1 - by COST where anything was priced, and by tokens
 * where nothing was (a local model with no rates set costs $0 and still did the work).
 */
export function shareOf(s: Spend, total: Spend): number {
  if (total.costUsd > 0) return clamp(s.costUsd / total.costUsd)
  const all = tokensOf(total)
  return all > 0 ? clamp(tokensOf(s) / all) : 0
}

/** How long a sub-agent ran, or has been running as of `now`; null before it has a start. */
export function elapsedMs(a: Pick<SubAgentSpend, 'startedAt' | 'endedAt'>, now: number): number | null {
  if (a.startedAt == null) return null
  return Math.max(0, (a.endedAt ?? now) - a.startedAt)
}

/**
 * Every span during which an agent worked, as [start, end] on the server's clock: the main agent's
 * turns and each sub-agent's run, an open one ending at `now`.
 */
export function workSpans(data: ConversationAgents, now: number): Array<[number, number]> {
  const spans: Array<[number, number]> = []
  for (const turn of data.turns ?? []) spans.push([turn.startedAt, turn.endedAt ?? now])
  for (const a of data.subAgents) {
    if (a.startedAt != null) spans.push([a.startedAt, a.endedAt ?? (a.status === 'running' ? now : a.startedAt)])
  }
  return spans
}

/**
 * How long the conversation's agents worked, as a clock on the wall would say: the time during
 * which AT LEAST ONE of them - the main agent or any sub-agent - was working (0.29.1 feedback). Not
 * the sum of every agent's time: twenty sub-agents working side by side for a minute are a minute.
 */
export function activeMs(spans: Array<[number, number]>): number {
  const sorted = spans.filter(([s, e]) => Number.isFinite(s) && Number.isFinite(e) && e > s)
    .sort((a, b) => a[0] - b[0])
  let total = 0
  let start = -Infinity
  let end = -Infinity
  for (const [s, e] of sorted) {
    if (s > end) {
      if (end > start) total += end - start
      start = s
      end = e
    } else if (e > end) {
      end = e
    }
  }
  if (end > start) total += end - start
  return total
}

/** Whether a sub-agent is still working - the figures go on changing while one is. */
export function anyRunning(data: ConversationAgents | undefined): boolean {
  return !!data?.subAgents.some(a => a.status === 'running')
}

/** How many sub-agents are working now - the number on the agents button while any is. */
export function runningCount(data: ConversationAgents | undefined): number {
  return data?.subAgents.filter(a => a.status === 'running').length ?? 0
}

/**
 * Whether the conversation's figures are moving: its own turn is running, or a sub-agent is. The
 * figures are polled, and shown beside the composer, while they are (2026-10-05, maintainer's
 * request: usage and cost in real time while the agents work).
 */
export function isLive(data: ConversationAgents | undefined, turnRunning: boolean): boolean {
  return turnRunning || anyRunning(data)
}

/**
 * The sub-agents that ended badly. No longer what colors a mark: both views of an agent read
 * agentWorkState (agentState.ts) since the 2026-10 kit rebuild, where a stopped one is quiet rather
 * than red. Kept, and pinned by agentsGraph.test.ts, as the plain question it answers.
 */
export function endedBadly(a: Pick<SubAgentSpend, 'status'>): boolean {
  return a.status === 'failed' || a.status === 'stopped'
}

function clamp(n: number): number {
  return Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : 0
}
