import type { AgentLifetime, AgentMailEntry, AgentMailKind, AgentStatus, AgentStatusUpdate } from '../types/chat.types'

// THE TREE OF AGENTS, as the page reads it (since 2026-10-28). Every agent - the one in the
// conversation the user opened included - can deploy agents of its own, down to the deployment's
// maximum depth; each deployed agent is a conversation of its own. The server hands the whole tree
// over flat (GET .../agent-tree); this file builds it into nested rows, adds up what each subtree
// spent, says which agents work, and merges in what the conversation's stream said since.
//
// Plain .ts with type-only imports, so `node --test` reaches it - agentTree.test.ts.

/** One deployed agent, as GET /chat/conversations/{id}/agent-tree lists it. */
export interface TreeAgent {
  agentId: string
  conversationId: string
  parentConversationId: string
  title: string
  status: AgentStatus
  lifetime: AgentLifetime
  depth: number
  owesReport: boolean
  lastReport: string | null
  /** The model it runs on when one was chosen for it - null for the chat's own. */
  model: string | null
  /** That model's provider, by name, when it can be named. */
  providerName?: string | null
  createdAt: string | null
  /** When its status last changed - where an idle or paused agent's time stops. */
  updatedAt?: string | null
  endedAt: string | null
  /** A turn is running in its conversation right now. */
  working: boolean
  inputTokens: number
  outputTokens: number
  costUsd: number
  /** The tokens its conversation holds now, and the window every conversation is condensed against. */
  contextTokens?: number
  contextWindow?: number | null
  /** What it - with the agents under it - may spend, and when it must be done; null for none. */
  budgetUsd?: number | null
  deadlineAt?: string | null
  /** VERIFIER for an agent that checks another's report. */
  role?: string | null
  /** Its verifier's scorecard (JSON: supported, unsupported, uncited, claims), when it was checked. */
  verification?: string | null
  /** Why it ended other than by finishing, as a code (stopReasonOf); null when it finished or has not ended. */
  stopReason?: string | null
}

/** A scorecard's three counts, or null when there is none or it cannot be read. */
export function scoreOf(json: string | null | undefined): { supported: number; unsupported: number; uncited: number } | null {
  if (!json) return null
  try {
    const v = JSON.parse(json) as { supported?: unknown; unsupported?: unknown; uncited?: unknown }
    const n = (x: unknown) => (typeof x === 'number' && Number.isFinite(x) ? x : 0)
    const s = { supported: n(v.supported), unsupported: n(v.unsupported), uncited: n(v.uncited) }
    return s.supported + s.unsupported + s.uncited > 0 ? s : null
  } catch {
    return null
  }
}

/**
 * The main agent - the conversation the user opened, at the top of the tree - as the agents map reads
 * it (AgentTreeView.main on the server, 2026-11-03). What it is doing comes from the chat's in-memory
 * state; what it spent is its OWN conversation's, since every deployed agent spends on a conversation
 * of its own; its time worked is the wall clock of its turns.
 */
export interface MainAgent {
  /** A turn runs in its conversation. */
  working: boolean
  /** A write of its waits for a person: a prompt in the page, or the approvals inbox. */
  awaitingApproval: boolean
  /** Its running turn is waiting out a provider's rate limit. */
  rateLimited: boolean
  /** No turn runs, and work it started - its agents, a background job - is still out. */
  waiting: boolean
  inputTokens: number
  outputTokens: number
  costUsd: number
  contextTokens: number
  contextWindow: number | null
  /** The wall clock of its finished turns. */
  workedMs: number
  /** When the turn running now started; null while none runs. */
  turnStartedAt: string | null
}

/** The whole tree the asked conversation belongs to. `self` is set when that conversation is a deployed agent's. */
export interface AgentTreeData {
  rootConversationId: string
  maxDepth: number
  self?: TreeAgent | null
  agents: TreeAgent[]
  /** The main agent; absent from a server older than 2026-11-03. */
  main?: MainAgent | null
}

/** One agent with the agents it deployed, and what the two together spent. */
export interface TreeNode {
  agent: TreeAgent
  children: TreeNode[]
  /** Its own cost plus every descendant's. */
  subtreeCostUsd: number
  /** How many agents in its subtree work now, itself included. */
  subtreeWorking: number
}

const STATUSES: AgentStatus[] = ['RUNNING', 'WAITING', 'IDLE', 'PAUSED', 'DONE', 'STOPPED', 'FAILED']
/** Every kind of mail line this page words (mailLabel). */
export const MAIL_KINDS: AgentMailKind[] = ['report', 'message', 'reply', 'told', 'alarm', 'background', 'user_talk', 'approval', 'context', 'memory',
  'run_schedule', 'run_webhook', 'run_source', 'run_member', 'learning', 'revising', 'lead_note', 'reply_failed']

/** A status as the server sent it, or FAILED for anything this page does not know. */
export function agentStatusOf(value: unknown): AgentStatus {
  const upper = typeof value === 'string' ? value.toUpperCase() : ''
  return STATUSES.includes(upper as AgentStatus) ? upper as AgentStatus : 'FAILED'
}

/** Whether an agent is at work: running a task, or waiting on work of its own (its agents, a job). */
export function isBusy(status: AgentStatus): boolean {
  return status === 'RUNNING' || status === 'WAITING'
}

/** Whether an agent has ended for good - it takes nothing more. */
export function isEnded(status: AgentStatus): boolean {
  return status === 'DONE' || status === 'STOPPED' || status === 'FAILED'
}

/** Whether an agent is working this moment: a turn running in its conversation, or a task in hand. */
export function isWorking(agent: Pick<TreeAgent, 'status' | 'working'>): boolean {
  return agent.working || agent.status === 'RUNNING'
}

/** Which controls an agent offers the person: Pause or Resume, Stop, and Dismiss for one that stays on. */
export function controlsFor(agent: Pick<TreeAgent, 'status' | 'lifetime'>): { pause: boolean; resume: boolean; stop: boolean; dismiss: boolean } {
  const ended = isEnded(agent.status)
  return {
    pause: !ended && agent.status !== 'PAUSED',
    resume: agent.status === 'PAUSED',
    stop: !ended,
    dismiss: !ended && agent.lifetime === 'LONG',
  }
}

/** The 'agent-status' event's data, or null when it is not one this page can read. */
export function parseAgentStatus(data: string): AgentStatusUpdate | null {
  try {
    const d = JSON.parse(data) as Partial<AgentStatusUpdate>
    if (typeof d.agentId !== 'string' || !d.agentId) return null
    return {
      agentId: d.agentId,
      title: typeof d.title === 'string' ? d.title : '',
      status: agentStatusOf(d.status),
      lifetime: d.lifetime === 'LONG' ? 'LONG' : 'SHORT',
      depth: typeof d.depth === 'number' ? d.depth : 1,
      owesReport: d.owesReport === true,
      conversationId: typeof d.conversationId === 'string' ? d.conversationId : '',
    }
  } catch {
    return null
  }
}

/** The 'agent-mail' event's entries, or the transcript row's - each one this page can read. */
export function parseAgentMail(value: unknown): AgentMailEntry[] {
  let raw: unknown = value
  if (typeof value === 'string') {
    try {
      raw = JSON.parse(value)
    } catch {
      return []
    }
  }
  const entries = Array.isArray(raw) ? raw : (raw as { entries?: unknown } | null)?.entries
  if (!Array.isArray(entries)) return []
  return entries.flatMap(e => {
    const entry = e as Partial<AgentMailEntry> | null
    if (!entry || !MAIL_KINDS.includes(entry.kind as AgentMailKind)) return []
    const text = typeof entry.text === 'string' && entry.text.trim() ? entry.text.trim() : undefined
    return [{
      agentId: typeof entry.agentId === 'string' && entry.agentId ? entry.agentId : null,
      title: typeof entry.title === 'string' && entry.title.trim() ? entry.title.trim() : null,
      kind: entry.kind as AgentMailKind,
      ...(text ? { text } : {}),
    }]
  })
}

/** What a `deployAgent` call returned: the agent it made and where it stood then. Empty when it made none. */
export function deployResult(result: string | undefined): { success?: boolean; agentId?: string; conversationId?: string; status?: AgentStatus; note?: string; error?: string } {
  try {
    const r = JSON.parse(result ?? '') as Record<string, unknown> | null
    if (!r || typeof r !== 'object') return {}
    return {
      success: typeof r.success === 'boolean' ? r.success : undefined,
      agentId: typeof r.agentId === 'string' && r.agentId ? r.agentId : undefined,
      conversationId: typeof r.conversationId === 'string' && r.conversationId ? r.conversationId : undefined,
      status: typeof r.status === 'string' ? agentStatusOf(r.status) : undefined,
      note: typeof r.note === 'string' && r.note.trim() ? r.note.trim() : undefined,
      error: typeof r.error === 'string' && r.error.trim() ? r.error.trim() : undefined,
    }
  } catch {
    return {}
  }
}

/**
 * The tree's agents with what the stream said since it was read laid over them: an agent's status,
 * title and whether it owes a report. An update for an agent the tree does not list yet is left out
 * - the tree is read again when one arrives, and a row with no conversation behind it cannot open.
 */
export function withLive(agents: TreeAgent[], live: Record<string, AgentStatusUpdate>): TreeAgent[] {
  if (Object.keys(live).length === 0) return agents
  return agents.map(a => {
    const u = live[a.agentId]
    if (!u) return a
    return { ...a, status: u.status, title: u.title || a.title, owesReport: u.owesReport, lifetime: u.lifetime }
  })
}

/** One update laid over a tree as the server sent it - the same array when the agent is not in it. */
export function applyStatus(data: AgentTreeData | undefined, u: AgentStatusUpdate): AgentTreeData | undefined {
  if (!data || !data.agents.some(a => a.agentId === u.agentId)) return data
  const agents = withLive(data.agents, { [u.agentId]: u })
  const self = data.self && data.self.agentId === u.agentId ? agents.find(a => a.agentId === u.agentId) : data.self
  return { ...data, agents, self }
}

/**
 * The agents nested under the conversation each deployed them from, starting at `rootConversationId`,
 * each list in the order they were deployed. An agent whose parent is not in the tree (an older row,
 * a parent dismissed and gone) is put at the top rather than lost.
 */
export function buildTree(agents: TreeAgent[], rootConversationId: string): TreeNode[] {
  const byParent = new Map<string, TreeAgent[]>()
  const known = new Set(agents.map(a => a.conversationId))
  for (const a of agents) {
    const parent = a.parentConversationId === rootConversationId || !known.has(a.parentConversationId)
      ? rootConversationId
      : a.parentConversationId
    const list = byParent.get(parent)
    if (list) list.push(a)
    else byParent.set(parent, [a])
  }
  const order = (list: TreeAgent[]) => [...list].sort((x, y) => (x.createdAt ?? '').localeCompare(y.createdAt ?? ''))
  const seen = new Set<string>()
  const nodesOf = (conversationId: string): TreeNode[] => order(byParent.get(conversationId) ?? []).flatMap(agent => {
    // A loop in the data would nest for ever; an agent is drawn once.
    if (seen.has(agent.conversationId)) return []
    seen.add(agent.conversationId)
    const children = agent.conversationId === rootConversationId ? [] : nodesOf(agent.conversationId)
    return [{
      agent,
      children,
      subtreeCostUsd: (agent.costUsd || 0) + children.reduce((sum, c) => sum + c.subtreeCostUsd, 0),
      subtreeWorking: (isWorking(agent) ? 1 : 0) + children.reduce((sum, c) => sum + c.subtreeWorking, 0),
    }]
  })
  return nodesOf(rootConversationId)
}

/**
 * One row of the map's tree, with what its TREE LINES need (the maintainer's feedback, 2026-11-03:
 * "should have the tree lines", like the Constellations timeline's - which this app cannot import
 * across the open-source boundary). Column k of a row is where the line of its ancestor at depth k
 * runs, the main agent's at 0:
 *
 * - `guides`, one per column 0..depth-2: true where that ancestor's line runs on past this row,
 *   because the ancestor's child on this row's way down has a later sibling;
 * - column depth-1 is the row's own: a tee, or an elbow where it is the last of its parent's agents
 *   (`last`);
 * - `stem`: its own agents are shown under it, so a line drops from its mark to theirs.
 */
export interface TreeRow {
  node: TreeNode
  /** How deep it sits: 1 for an agent the main agent deployed. */
  depth: number
  guides: boolean[]
  last: boolean
  stem: boolean
}

/** The tree as rows, top to bottom; a folded agent keeps its row and hides everything under it. */
export function flattenTree(nodes: TreeNode[], folded: ReadonlySet<string> = new Set()): TreeRow[] {
  const rows: TreeRow[] = []
  const walk = (list: TreeNode[], depth: number, guides: boolean[]) => {
    list.forEach((node, i) => {
      const last = i === list.length - 1
      const open = !folded.has(node.agent.agentId)
      rows.push({ node, depth, guides, last, stem: open && node.children.length > 0 })
      if (open) walk(node.children, depth + 1, [...guides, !last])
    })
  }
  walk(nodes, 1, [])
  return rows
}

/** How many agents of the tree work now - the number on the agents button. */
export function workingCount(agents: TreeAgent[]): number {
  return agents.filter(isWorking).length
}

/** What every deployed agent of the tree spent together. */
export function totalCost(agents: TreeAgent[]): number {
  return agents.reduce((sum, a) => sum + (a.costUsd || 0), 0)
}

/**
 * Whether anything the conversation deployed may still speak on its stream: an agent of its own at
 * work, or one that owes it a report. The stream is kept while so - the report starts a turn there.
 * `live` is what the stream said, which wins over the tree as it was read.
 */
export function childrenBusy(conversationId: string, agents: TreeAgent[] | undefined, live: Record<string, AgentStatusUpdate>): boolean {
  const listed = new Set<string>()
  for (const a of agents ?? []) {
    if (a.parentConversationId !== conversationId) continue
    listed.add(a.agentId)
    const u = live[a.agentId]
    const status = u?.status ?? a.status
    const owes = u?.owesReport ?? a.owesReport
    if (isBusy(status) || owes) return true
  }
  // An agent the stream told of and the tree did not list yet: a child of this conversation, since
  // a conversation's stream speaks of its own agents only.
  return Object.values(live).some(u => !listed.has(u.agentId) && (isBusy(u.status) || u.owesReport))
}

/** A token count as a short readout: 950, 12.3k, 200k, 5.1M - a long conversation runs into millions. */
export function compactTokens(n: number): string {
  if (!Number.isFinite(n) || n < 1000) return String(Math.max(0, Math.round(n || 0)))
  const [value, unit] = n < 1_000_000 ? [n / 1000, 'k'] : [n / 1_000_000, 'M']
  return `${value.toFixed(1).replace(/\.0$/, '')}${unit}`
}

// -- Under an agent's status in the map (the maintainer's feedback, 2026-11-02) --------------------
//
// The status says how it stands and, for one that ended other than by finishing, why - in words, from
// a code the server keeps (AgentNode.REASON_*), never the sentence the agents read, which is English
// and carries an ISO time. Under it its figures, each set against its limit where it has one: the
// context its conversation holds against the window, what it spent against its budget, and how long
// it has run against its time limit - 29.8k/1M, $0.03/$3.00 and 00:01:20/00:10:00 on one line, apart
// by middle dots, the way an agent's card on the Constellations canvas reads.

/** Why an agent ended other than by finishing (AgentNode.REASON_*). */
export type StopReason = 'DEADLINE' | 'BUDGET' | 'RUN_LIMIT' | 'PARENT' | 'USER' | 'RATE_LIMITED' | 'ERROR'

const STOP_REASONS: StopReason[] = ['DEADLINE', 'BUDGET', 'RUN_LIMIT', 'PARENT', 'USER', 'RATE_LIMITED', 'ERROR']

/** The reason a STOPPED or FAILED agent carries, or null - for any other status, none, or one this page does not know. */
export function stopReasonOf(agent: Pick<TreeAgent, 'status' | 'stopReason'>): StopReason | null {
  if (agent.status !== 'STOPPED' && agent.status !== 'FAILED') return null
  const code = typeof agent.stopReason === 'string' ? agent.stopReason.toUpperCase() : ''
  return STOP_REASONS.includes(code as StopReason) ? code as StopReason : null
}

/** An agent's figures. A part is null where nothing is known of it; a limit is null where it has none. */
export interface AgentFigures {
  /** The tokens its conversation holds, and the window it is condensed against. */
  context: { used: number; window: number | null } | null
  /** What it spent; with a budget, what it AND the agents it deployed spent, since the budget is for all of them. */
  cost: { spent: number; budget: number | null; withAgents: boolean } | null
  /** How long it has run, and how long it may: to its deadline, or the nearest of its ancestors' when that comes first. */
  time: { seconds: number; limitSeconds: number | null; deadlineAt: string | null; inherited: boolean } | null
}

function ms(iso: string | null | undefined): number | null {
  if (!iso) return null
  const t = Date.parse(iso)
  return Number.isFinite(t) ? t : null
}

/**
 * The deadline an agent works to: its own, or an ancestor's that comes first - stopping one stops
 * everything under it. `inherited` when it is an ancestor's.
 */
export function effectiveDeadline(agent: TreeAgent, agents: TreeAgent[]): { at: string; inherited: boolean } | null {
  const byConversation = new Map(agents.map(a => [a.conversationId, a]))
  let best: { at: string; inherited: boolean; t: number } | null = null
  const seen = new Set<string>()
  for (let cur: TreeAgent | undefined = agent; cur && !seen.has(cur.conversationId); cur = byConversation.get(cur.parentConversationId)) {
    seen.add(cur.conversationId)
    const t = ms(cur.deadlineAt)
    if (t !== null && (best === null || t < best.t)) best = { at: cur.deadlineAt!, inherited: cur !== agent, t }
  }
  return best ? { at: best.at, inherited: best.inherited } : null
}

/**
 * WHETHER AN AGENT'S LIMITS ARE SHOWN (the maintainer's feedback, 2026-11-03: "after the sub agents
 * are finished, no need to show max allowance of run time and cost - unless it's possible for the
 * main agent to ask the sub agent to continue to work after finished"). Its window, budget and time
 * limit are shown while it can still spend against them: working, waiting on its own agents, paused
 * (it can be resumed), and IDLE - a LONG agent that has reported and stays on, which its parent can
 * give more work (AgentTree.giveWork). Once DONE, STOPPED or FAILED it has ended, and its figures
 * stand alone.
 *
 * Read from the status as it is NOW, never from what the agent was: an ancestor can still message a
 * DONE agent (AgentTree.tell refuses only a stopped or failed one), and that makes it RUNNING again -
 * so its limits come back the moment it has work, and go again when it is done.
 */
export function limitsShown(status: AgentStatus): boolean {
  return !isEnded(status)
}

/**
 * Its figures at `now`. Its time runs from its deployment to its end - to now while it works, and to
 * its last change of status while it is idle or paused, which is when it stopped working. An agent
 * that has ended carries no limit (limitsShown), and what it spent is then its own: a budget's total
 * with its agents was only there to be set against the budget.
 */
export function agentFigures(node: TreeNode, agents: TreeAgent[], now: number): AgentFigures {
  const a = node.agent
  const limits = limitsShown(a.status)
  const used = a.contextTokens ?? 0
  const context = used > 0 ? { used, window: limits && a.contextWindow && a.contextWindow > 0 ? a.contextWindow : null } : null

  const budget = limits && a.budgetUsd && a.budgetUsd > 0 ? a.budgetUsd : null
  const spent = budget !== null ? node.subtreeCostUsd : a.costUsd || 0
  const cost = budget !== null || spent > 0
    ? { spent, budget, withAgents: budget !== null && node.children.length > 0 }
    : null

  const start = ms(a.createdAt)
  let time: AgentFigures['time'] = null
  if (start !== null) {
    const end = isEnded(a.status)
      ? ms(a.endedAt) ?? ms(a.updatedAt) ?? now
      : isWorking(a) || isBusy(a.status) ? now : ms(a.updatedAt) ?? now
    const deadline = limits ? effectiveDeadline(a, agents) : null
    const due = deadline ? ms(deadline.at) : null
    time = {
      seconds: Math.max(0, Math.floor((end - start) / 1000)),
      limitSeconds: due !== null && due > start ? Math.floor((due - start) / 1000) : null,
      deadlineAt: deadline?.at ?? null,
      inherited: deadline?.inherited ?? false,
    }
  }
  return { context, cost, time }
}

/**
 * Seconds as hh:mm:ss, as an agent's card on the Constellations canvas counts its time worked
 * (workTime.ts there, which this app cannot import across the open-source boundary) - hours past 99
 * keep every digit, and nothing is ever negative.
 */
export function hms(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(Number.isFinite(totalSeconds) ? totalSeconds : 0))
  const two = (n: number) => String(n).padStart(2, '0')
  return `${two(Math.floor(s / 3600))}:${two(Math.floor((s % 3600) / 60))}:${two(s % 60)}`
}

/** A figure and its limit as one readout: "29.8k/1M", or the figure alone where there is no limit. */
export function overLimit(value: string, limit: string | null): string {
  return limit ? `${value}/${limit}` : value
}

/**
 * The figures as one line of readouts, the context, the cost and the time in that order, each part
 * only where it is known. `tokens` and `money` write a count and an amount as the page does.
 */
export function figuresLine(f: AgentFigures, tokens: (n: number) => string, money: (n: number) => string): string {
  return [
    f.context ? overLimit(tokens(f.context.used), f.context.window ? tokens(f.context.window) : null) : null,
    f.cost ? overLimit(money(f.cost.spent), f.cost.budget !== null ? money(f.cost.budget) : null) : null,
    f.time ? overLimit(hms(f.time.seconds), f.time.limitSeconds !== null ? hms(f.time.limitSeconds) : null) : null,
  ].filter((x): x is string => !!x).join('\u2009\u00b7\u2009')
}

// -- The main agent's row (the maintainer's feedback, 2026-11-03) ----------------------------------
//
// "Main agent must have Idle status. Its cost and run time must be shown." Its status says what the
// conversation the user opened is doing, as the chat knows it; under it the same line of figures as
// every other agent's: its context against the window, what its own conversation spent, and its time
// worked - the wall clock of its turns. It has no budget and no time limit, and never ends.

/** Where the main agent stands. */
export type MainAgentStatus = 'APPROVAL' | 'QUESTION' | 'RATE_LIMITED' | 'WORKING' | 'WAITING' | 'IDLE'

/** What is known of what the main agent is doing - the server's poll and, while its conversation is open, the page's own. */
export interface MainAgentSignals {
  working: boolean
  awaitingApproval: boolean
  /** An askUser question waits for the person - known only on the page that has the conversation open. */
  awaitingAnswer: boolean
  rateLimited: boolean
  waiting: boolean
}

/**
 * Where the main agent stands, the first of these that holds: a write waiting for approval, a
 * question waiting for an answer - nothing moves until the person acts, so these outrank the rest -
 * then a turn waiting out a rate limit, a turn running, work it started still out, else idle.
 */
export function mainAgentStatus(s: MainAgentSignals): MainAgentStatus {
  if (s.awaitingApproval) return 'APPROVAL'
  if (s.awaitingAnswer) return 'QUESTION'
  if (s.working && s.rateLimited) return 'RATE_LIMITED'
  if (s.working) return 'WORKING'
  if (s.waiting) return 'WAITING'
  return 'IDLE'
}

/**
 * The main agent's figures at `now`, in the same shape as a deployed agent's: its context against the
 * window, what its own conversation spent - only where something was priced, since $0 would read as
 * "free" - and its time worked, counting a turn that runs now up to `now`. `context` overrides the
 * server's figures for the conversation open on the page, whose meter is live.
 */
export function mainFigures(main: MainAgent | null | undefined, now: number,
  context?: { used: number; window: number | null } | null): AgentFigures {
  const used = context ? context.used : main?.contextTokens ?? 0
  const window = context ? context.window : main?.contextWindow ?? null
  const spent = main?.costUsd ?? 0
  const since = main?.working ? ms(main.turnStartedAt) : null
  const workedMs = (main?.workedMs ?? 0) + (since !== null ? Math.max(0, now - since) : 0)
  return {
    context: used > 0 ? { used, window: window && window > 0 ? window : null } : null,
    cost: spent > 0 ? { spent, budget: null, withAgents: false } : null,
    time: main && (workedMs > 0 || since !== null)
      ? { seconds: Math.floor(workedMs / 1000), limitSeconds: null, deadlineAt: null, inherited: false }
      : null,
  }
}
