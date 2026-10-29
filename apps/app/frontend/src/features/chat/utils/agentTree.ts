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
  model: string | null
  createdAt: string | null
  endedAt: string | null
  /** A turn is running in its conversation right now. */
  working: boolean
  inputTokens: number
  outputTokens: number
  costUsd: number
  /** What it - with the agents under it - may spend, and when it must be done; null for none. */
  budgetUsd?: number | null
  deadlineAt?: string | null
  /** VERIFIER for an agent that checks another's report. */
  role?: string | null
  /** Its verifier's scorecard (JSON: supported, unsupported, uncited, claims), when it was checked. */
  verification?: string | null
  /** Why the tree stopped it - its budget or deadline, or an ancestor's. */
  stopNote?: string | null
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

/** The whole tree the asked conversation belongs to. `self` is set when that conversation is a deployed agent's. */
export interface AgentTreeData {
  rootConversationId: string
  maxDepth: number
  self?: TreeAgent | null
  agents: TreeAgent[]
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
const MAIL_KINDS: AgentMailKind[] = ['report', 'message', 'alarm', 'background', 'user_talk', 'approval', 'context', 'memory']

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
    return [{
      agentId: typeof entry.agentId === 'string' && entry.agentId ? entry.agentId : null,
      title: typeof entry.title === 'string' && entry.title.trim() ? entry.title.trim() : null,
      kind: entry.kind as AgentMailKind,
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

/** The tree as rows, top to bottom, each with how deep it sits (1 for an agent the root deployed). */
export function flattenTree(nodes: TreeNode[], folded: ReadonlySet<string> = new Set()): { node: TreeNode; depth: number }[] {
  const rows: { node: TreeNode; depth: number }[] = []
  const walk = (list: TreeNode[], depth: number) => {
    for (const node of list) {
      rows.push({ node, depth })
      if (!folded.has(node.agent.agentId)) walk(node.children, depth + 1)
    }
  }
  walk(nodes, 1)
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
