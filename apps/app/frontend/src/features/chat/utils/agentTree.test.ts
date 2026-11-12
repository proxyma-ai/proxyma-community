import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  agentFigures, agentStatusOf, applyStatus, buildTree, childrenBusy, compactTokens, controlsFor, deployResult,
  effectiveDeadline, figuresLine, flattenTree, hms, isBusy, isEnded, isWorking, limitsShown, mainAgentStatus,
  mainFigures, overLimit, parseAgentMail, parseAgentStatus, scoreOf, stopReasonOf, totalCost, withLive, workingCount,
  type AgentTreeData, type MainAgent, type MainAgentSignals, type TreeAgent,
} from './agentTree.ts'
import { agentStatusTitle, mainAgentStatusTitle, mainAgentWorkState } from './agentState.ts'
import type { AgentStatusUpdate } from '../types/chat.types'

const ROOT = 'root'

function agent(agentId: string, parent: string, extra: Partial<TreeAgent> = {}): TreeAgent {
  return {
    agentId,
    conversationId: `c-${agentId}`,
    parentConversationId: parent,
    title: agentId.toUpperCase(),
    status: 'DONE',
    lifetime: 'SHORT',
    depth: 1,
    owesReport: false,
    lastReport: null,
    model: null,
    createdAt: '2026-10-28T10:00:00Z',
    endedAt: null,
    working: false,
    inputTokens: 0,
    outputTokens: 0,
    costUsd: 0,
    ...extra,
  }
}

function update(agentId: string, extra: Partial<AgentStatusUpdate> = {}): AgentStatusUpdate {
  return { agentId, title: '', status: 'RUNNING', lifetime: 'SHORT', depth: 1, owesReport: false, conversationId: `c-${agentId}`, ...extra }
}

test('the flat list nests under the conversation each agent was deployed from, in the order deployed', () => {
  const agents = [
    agent('b', ROOT, { createdAt: '2026-10-28T10:02:00Z' }),
    agent('a', ROOT, { createdAt: '2026-10-28T10:01:00Z' }),
    agent('a1', 'c-a', { depth: 2 }),
    agent('a1x', 'c-a1', { depth: 3 }),
  ]
  const tree = buildTree(agents, ROOT)
  assert.deepEqual(tree.map(n => n.agent.agentId), ['a', 'b'])
  assert.deepEqual(tree[0].children.map(n => n.agent.agentId), ['a1'])
  assert.deepEqual(tree[0].children[0].children.map(n => n.agent.agentId), ['a1x'])
  assert.deepEqual(flattenTree(tree).map(r => [r.node.agent.agentId, r.depth]), [['a', 1], ['a1', 2], ['a1x', 3], ['b', 1]])
})

test('an agent whose parent is not in the tree is listed at the top rather than lost', () => {
  const tree = buildTree([agent('orphan', 'c-gone', { depth: 2 })], ROOT)
  assert.deepEqual(tree.map(n => n.agent.agentId), ['orphan'])
})

test('a folded agent hides the agents under it, and only those', () => {
  const tree = buildTree([agent('a', ROOT), agent('a1', 'c-a'), agent('b', ROOT)], ROOT)
  assert.deepEqual(flattenTree(tree, new Set(['a'])).map(r => r.node.agent.agentId), ['a', 'b'])
})

test('a subtree adds up its own cost and every descendant\'s, and counts who works in it', () => {
  const tree = buildTree([
    agent('a', ROOT, { costUsd: 0.1, status: 'WAITING' }),
    agent('a1', 'c-a', { costUsd: 0.25, status: 'RUNNING' }),
    agent('a2', 'c-a', { costUsd: 0.05, working: true, status: 'IDLE' }),
    agent('b', ROOT, { costUsd: 1 }),
  ], ROOT)
  assert.ok(Math.abs(tree[0].subtreeCostUsd - 0.4) < 1e-9)
  assert.equal(tree[0].subtreeWorking, 2)
  assert.equal(tree[1].subtreeCostUsd, 1)
  assert.equal(tree[1].subtreeWorking, 0)
})

test('working is a running task or a turn in progress; busy also counts waiting on its own agents', () => {
  assert.equal(isWorking({ status: 'RUNNING', working: false }), true)
  assert.equal(isWorking({ status: 'IDLE', working: true }), true)
  assert.equal(isWorking({ status: 'WAITING', working: false }), false)
  assert.equal(isBusy('WAITING'), true)
  assert.equal(isBusy('PAUSED'), false)
  assert.deepEqual(['DONE', 'STOPPED', 'FAILED', 'IDLE'].map(s => isEnded(agentStatusOf(s))), [true, true, true, false])
  assert.equal(workingCount([agent('a', ROOT, { status: 'RUNNING' }), agent('b', ROOT), agent('c', ROOT, { working: true })]), 2)
  assert.equal(totalCost([agent('a', ROOT, { costUsd: 0.5 }), agent('b', ROOT, { costUsd: 0.25 })]), 0.75)
})

test('a status the page does not know reads as failed, never as working', () => {
  assert.equal(agentStatusOf('running'), 'RUNNING')
  assert.equal(agentStatusOf('SLEEPING'), 'FAILED')
  assert.equal(agentStatusOf(undefined), 'FAILED')
})

test('the controls follow where an agent stands', () => {
  assert.deepEqual(controlsFor({ status: 'RUNNING', lifetime: 'SHORT' }), { pause: true, resume: false, stop: true, dismiss: false })
  assert.deepEqual(controlsFor({ status: 'PAUSED', lifetime: 'LONG' }), { pause: false, resume: true, stop: true, dismiss: true })
  assert.deepEqual(controlsFor({ status: 'DONE', lifetime: 'LONG' }), { pause: false, resume: false, stop: false, dismiss: false })
})

test('an agent-status event is read whole, and a broken one is not read at all', () => {
  const u = parseAgentStatus(JSON.stringify({ agentId: 'a', title: 'Prices', status: 'WAITING', lifetime: 'LONG', depth: 2, owesReport: true, conversationId: 'c-a' }))
  assert.deepEqual(u, { agentId: 'a', title: 'Prices', status: 'WAITING', lifetime: 'LONG', depth: 2, owesReport: true, conversationId: 'c-a' })
  assert.equal(parseAgentStatus('{}'), null)
  assert.equal(parseAgentStatus('not json'), null)
})

test('agent mail is read from the event and from the transcript, an unknown kind left out', () => {
  const entries = [{ agentId: 'a', title: ' Prices ', kind: 'report' }, { agentId: null, title: null, kind: 'alarm' }, { kind: 'telepathy' }]
  assert.deepEqual(parseAgentMail(JSON.stringify({ entries })), [
    { agentId: 'a', title: 'Prices', kind: 'report' },
    { agentId: null, title: null, kind: 'alarm' },
  ])
  assert.deepEqual(parseAgentMail(entries).length, 2)
  assert.deepEqual(parseAgentMail('nope'), [])
})

test('a teammate\'s reply and what the agent told the person are mail, with their words', () => {
  const entries = [
    { agentId: null, title: 'Reply Writer', kind: 'reply', text: '  Done: 2 drafts.  ' },
    { agentId: null, title: '(from your scheduled run)', kind: 'told', text: 'Needs your decision.' },
    { agentId: null, title: 'Scout', kind: 'message', text: '   ' },
  ]
  assert.deepEqual(parseAgentMail({ entries }), [
    { agentId: null, title: 'Reply Writer', kind: 'reply', text: 'Done: 2 drafts.' },
    { agentId: null, title: '(from your scheduled run)', kind: 'told', text: 'Needs your decision.' },
    { agentId: null, title: 'Scout', kind: 'message' },
  ])
})

test('a module\'s own messages are mail of their own kinds, never a header for the model', () => {
  const entries = [
    { agentId: null, title: null, kind: 'run_schedule', text: 'Check the new comments.' },
    { agentId: null, title: null, kind: 'lead_note' },
    { agentId: null, title: 'Scout', kind: 'reply_failed' },
    { agentId: null, title: null, kind: 'something_new', text: '[Scheduled run, 2026-11-05 09:00 UTC]' },
  ]
  assert.deepEqual(parseAgentMail({ entries }), [
    { agentId: null, title: null, kind: 'run_schedule', text: 'Check the new comments.' },
    { agentId: null, title: null, kind: 'lead_note' },
    { agentId: null, title: 'Scout', kind: 'reply_failed' },
  ])
})

test('a deployAgent result names the agent and its conversation', () => {
  assert.deepEqual(
    deployResult(JSON.stringify({ success: true, agentId: 'a', conversationId: 'c-a', status: 'RUNNING', note: 'Started' })),
    { success: true, agentId: 'a', conversationId: 'c-a', status: 'RUNNING', note: 'Started', error: undefined },
  )
  assert.deepEqual(deployResult(JSON.stringify({ success: false, error: 'Too deep' })).error, 'Too deep')
  assert.deepEqual(deployResult(undefined), {})
})

test('what the stream said wins over the tree as it was read, and an unlisted agent is not invented', () => {
  const agents = [agent('a', ROOT, { status: 'RUNNING', title: 'Old' })]
  const merged = withLive(agents, { a: update('a', { status: 'DONE', title: 'New', owesReport: true }), z: update('z') })
  assert.equal(merged.length, 1)
  assert.equal(merged[0].status, 'DONE')
  assert.equal(merged[0].title, 'New')
  assert.equal(merged[0].owesReport, true)
})

test('an update lands in the tree and in its self row, and leaves a tree without the agent as it was', () => {
  const a = agent('a', ROOT, { status: 'RUNNING' })
  const data: AgentTreeData = { rootConversationId: ROOT, maxDepth: 2, self: a, agents: [a] }
  const next = applyStatus(data, update('a', { status: 'PAUSED' }))
  assert.equal(next?.agents[0].status, 'PAUSED')
  assert.equal(next?.self?.status, 'PAUSED')
  assert.equal(applyStatus(data, update('z')), data)
  assert.equal(applyStatus(undefined, update('a')), undefined)
})

test('a conversation keeps listening while an agent of its own works or owes it a report', () => {
  const agents = [agent('a', ROOT, { status: 'DONE' }), agent('a1', 'c-a', { status: 'RUNNING' })]
  // A grandchild at work is its child's business, not the root's.
  assert.equal(childrenBusy(ROOT, agents, {}), false)
  assert.equal(childrenBusy('c-a', agents, {}), true)
  assert.equal(childrenBusy(ROOT, agents, { a: update('a', { status: 'DONE', owesReport: true }) }), true)
  assert.equal(childrenBusy(ROOT, [agent('a', ROOT, { status: 'RUNNING' })], { a: update('a', { status: 'STOPPED' }) }), false)
  // One the stream told of before the tree listed it.
  assert.equal(childrenBusy(ROOT, [], { n: update('n', { status: 'RUNNING' }) }), true)
  assert.equal(childrenBusy(ROOT, undefined, {}), false)
})

test('token counts read short', () => {
  assert.equal(compactTokens(950), '950')
  assert.equal(compactTokens(12_345), '12.3k')
  assert.equal(compactTokens(200_000), '200k')
  assert.equal(compactTokens(5_090_600), '5.1M')
  assert.equal(compactTokens(0), '0')
})

test('a verifier\'s scorecard reads as its three counts, and an empty or unreadable one as none', () => {
  assert.deepEqual(scoreOf('{"supported":7,"unsupported":1,"uncited":2,"claims":[]}'), { supported: 7, unsupported: 1, uncited: 2 })
  assert.equal(scoreOf('{"supported":0,"unsupported":0,"uncited":0,"note":"no claim"}'), null)
  assert.equal(scoreOf('not json'), null)
  assert.equal(scoreOf(null), null)
})

// -- The status and the figures under it in the agents map (2026-11-02 feedback) -----------------

test('a stop reason is read only for a stopped or failed agent, and one this page does not know is none', () => {
  assert.equal(stopReasonOf({ status: 'STOPPED', stopReason: 'DEADLINE' }), 'DEADLINE')
  assert.equal(stopReasonOf({ status: 'FAILED', stopReason: 'rate_limited' }), 'RATE_LIMITED')
  assert.equal(stopReasonOf({ status: 'DONE', stopReason: 'USER' }), null)
  assert.equal(stopReasonOf({ status: 'RUNNING', stopReason: 'BUDGET' }), null)
  assert.equal(stopReasonOf({ status: 'STOPPED', stopReason: 'SOMETHING_NEW' }), null)
  assert.equal(stopReasonOf({ status: 'STOPPED' }), null)
})

test('the status says why it ended in words, and is the status word alone without a reason', () => {
  const t = (s: string) => s
  assert.equal(agentStatusTitle('STOPPED', 'DEADLINE', t), 'Stopped - time limit')
  assert.equal(agentStatusTitle('STOPPED', 'BUDGET', t), 'Stopped - budget spent')
  assert.equal(agentStatusTitle('STOPPED', 'RUN_LIMIT', t), 'Stopped - run limit reached')
  assert.equal(agentStatusTitle('STOPPED', 'USER', t), 'Stopped by you')
  assert.equal(agentStatusTitle('STOPPED', 'PARENT', t), 'Stopped by its parent agent')
  assert.equal(agentStatusTitle('FAILED', 'RATE_LIMITED', t), 'Failed - rate limited')
  assert.equal(agentStatusTitle('FAILED', 'ERROR', t), 'Failed')
  assert.equal(agentStatusTitle('STOPPED', null, t), 'Stopped')
  assert.equal(agentStatusTitle('RUNNING', null, t), 'Working')
})

test('the deadline an agent works to is its own or the nearest earlier one above it', () => {
  const agents = [
    agent('a', ROOT, { deadlineAt: '2026-11-02T12:30:00Z' }),
    agent('a1', 'c-a', { deadlineAt: '2026-11-02T13:00:00Z' }),
    agent('a1x', 'c-a1'),
    agent('b', ROOT),
  ]
  const by = (id: string) => agents.find(a => a.agentId === id)!
  assert.deepEqual(effectiveDeadline(by('a'), agents), { at: '2026-11-02T12:30:00Z', inherited: false })
  assert.deepEqual(effectiveDeadline(by('a1'), agents), { at: '2026-11-02T12:30:00Z', inherited: true })
  assert.deepEqual(effectiveDeadline(by('a1x'), agents), { at: '2026-11-02T12:30:00Z', inherited: true })
  assert.equal(effectiveDeadline(by('b'), agents), null)
})

test('the figures: context against the window, spend against a budget its agents share, time against its limit', () => {
  const now = Date.parse('2026-11-02T10:05:00Z')
  const agents = [
    agent('a', ROOT, {
      status: 'RUNNING', createdAt: '2026-11-02T10:00:00Z', contextTokens: 29_800, contextWindow: 1_000_000,
      costUsd: 0.02, budgetUsd: 3, deadlineAt: '2026-11-02T10:10:00Z',
    }),
    agent('a1', 'c-a', { costUsd: 0.01, createdAt: '2026-11-02T10:01:00Z' }),
  ]
  const [node] = buildTree(agents, ROOT)
  const f = agentFigures(node, agents, now)
  assert.deepEqual(f.context, { used: 29_800, window: 1_000_000 })
  assert.equal(f.cost?.budget, 3)
  assert.ok(Math.abs((f.cost?.spent ?? 0) - 0.03) < 1e-9, 'a budget is for the agent and what it deployed')
  assert.equal(f.cost?.withAgents, true)
  assert.deepEqual(f.time, { seconds: 300, limitSeconds: 600, deadlineAt: '2026-11-02T10:10:00Z', inherited: false })
  const tokens = (n: number) => (n >= 1_000_000 ? `${n / 1_000_000}M` : `${(n / 1000).toFixed(1)}k`)
  const money = (n: number) => `$${n.toFixed(2)}`
  assert.equal(figuresLine(f, tokens, money), '29.8k/1M\u2009\u00b7\u2009$0.03/$3.00\u2009\u00b7\u200900:05:00/00:10:00')
})

test('each figure is shown only where known, and its limit only where it has one', () => {
  const now = Date.parse('2026-11-02T12:00:00Z')
  // Not started yet: nothing counted, nothing spent - only its time.
  const fresh = agent('a', ROOT, { status: 'RUNNING', createdAt: '2026-11-02T11:59:58Z' })
  const f = agentFigures(buildTree([fresh], ROOT)[0], [fresh], now)
  assert.equal(f.context, null)
  assert.equal(f.cost, null)
  assert.deepEqual(f.time, { seconds: 2, limitSeconds: null, deadlineAt: null, inherited: false })
  assert.equal(figuresLine(f, String, String), '00:00:02')
  // Spent without a budget: its own spend, and no limit beside it.
  const spent = agent('b', ROOT, { costUsd: 0.5, createdAt: null })
  const g = agentFigures(buildTree([spent], ROOT)[0], [spent], now)
  assert.deepEqual(g.cost, { spent: 0.5, budget: null, withAgents: false })
  assert.equal(g.time, null)
})

test('an ended agent\'s time stops at its end, and an idle or paused one\'s at its last change', () => {
  const now = Date.parse('2026-11-02T12:00:00Z')
  const ended = agent('a', ROOT, { status: 'STOPPED', createdAt: '2026-11-02T11:00:00Z', endedAt: '2026-11-02T11:01:30Z' })
  assert.equal(agentFigures(buildTree([ended], ROOT)[0], [ended], now).time?.seconds, 90)
  const idle = agent('b', ROOT, { status: 'IDLE', lifetime: 'LONG', createdAt: '2026-11-02T11:00:00Z', updatedAt: '2026-11-02T11:10:00Z' })
  assert.equal(agentFigures(buildTree([idle], ROOT)[0], [idle], now).time?.seconds, 600)
  // A turn running in its conversation counts to now, whatever its status says.
  const talking = agent('c', ROOT, { status: 'IDLE', working: true, createdAt: '2026-11-02T11:00:00Z', updatedAt: '2026-11-02T11:10:00Z' })
  assert.equal(agentFigures(buildTree([talking], ROOT)[0], [talking], now).time?.seconds, 3600)
})

test('a run time reads as hh:mm:ss, the way an agent card counts its time', () => {
  assert.equal(hms(52), '00:00:52')
  assert.equal(hms(3600 * 101 + 61), '101:01:01')
  assert.equal(hms(-5), '00:00:00')
  assert.equal(hms(Number.NaN), '00:00:00')
  assert.equal(overLimit('1', null), '1')
  assert.equal(overLimit('1', '2'), '1/2')
})

// -- 2026-11-03: tree lines, limits only while an agent can still spend, the main agent's row --------

test('tree lines: each row knows which ancestor lines run past it, whether it closes its parent\'s line, and whether a line drops from it', () => {
  const tree = buildTree([
    agent('a', ROOT, { createdAt: '2026-10-28T10:01:00Z' }),
    agent('a1', 'c-a', { depth: 2, createdAt: '2026-10-28T10:02:00Z' }),
    agent('a1x', 'c-a1', { depth: 3 }),
    agent('a2', 'c-a', { depth: 2, createdAt: '2026-10-28T10:03:00Z' }),
    agent('b', ROOT, { createdAt: '2026-10-28T10:04:00Z' }),
    agent('b1', 'c-b', { depth: 2 }),
  ], ROOT)
  const rows = flattenTree(tree).map(r => [r.node.agent.agentId, r.depth, r.guides, r.last, r.stem])
  assert.deepEqual(rows, [
    ['a', 1, [], false, true],
    // The main agent's line runs on past a's agents to b; a's runs on past a1's to a2.
    ['a1', 2, [true], false, true],
    ['a1x', 3, [true, true], true, false],
    ['a2', 2, [true], true, false],
    // b is the main agent's last: nothing of its line runs past b's agents.
    ['b', 1, [], true, true],
    ['b1', 2, [false], true, false],
  ])
  // Folded, a keeps its row and drops no line; the rows under it go.
  const folded = flattenTree(tree, new Set(['a'])).map(r => [r.node.agent.agentId, r.stem])
  assert.deepEqual(folded, [['a', false], ['b', true], ['b1', false]])
})

test('limits are shown while an agent can still spend against them, and never once it has ended', () => {
  for (const s of ['RUNNING', 'WAITING', 'PAUSED', 'IDLE'] as const) assert.equal(limitsShown(s), true, s)
  for (const s of ['DONE', 'STOPPED', 'FAILED'] as const) assert.equal(limitsShown(s), false, s)
})

test('an ended agent shows its figures alone: no window, no budget, no time limit, and its own spend', () => {
  const now = Date.parse('2026-11-02T12:00:00Z')
  const shape = {
    createdAt: '2026-11-02T11:00:00Z', endedAt: '2026-11-02T11:00:11Z', contextTokens: 13_300, contextWindow: 1_000_000,
    costUsd: 0.03, budgetUsd: 0.2, deadlineAt: '2026-11-02T11:03:00Z',
  }
  const tokens = (n: number) => (n >= 1_000_000 ? `${n / 1_000_000}M` : `${(n / 1000).toFixed(1)}k`)
  const money = (n: number) => `$${n.toFixed(2)}`
  const done = agent('a', ROOT, { status: 'DONE', ...shape })
  const kid = agent('a1', 'c-a', { costUsd: 0.05 })
  const f = agentFigures(buildTree([done, kid], ROOT)[0], [done, kid], now)
  assert.deepEqual(f.cost, { spent: 0.03, budget: null, withAgents: false })
  assert.deepEqual(f.time, { seconds: 11, limitSeconds: null, deadlineAt: null, inherited: false })
  assert.equal(figuresLine(f, tokens, money), '13.3k\u2009\u00b7\u2009$0.03\u2009\u00b7\u200900:00:11')
  // A LONG agent idle after its report can be given more work: its limits stay.
  const idle = agent('b', ROOT, { status: 'IDLE', lifetime: 'LONG', ...shape, endedAt: null, updatedAt: '2026-11-02T11:00:11Z' })
  assert.equal(figuresLine(agentFigures(buildTree([idle], ROOT)[0], [idle], now), tokens, money),
    '13.3k/1M\u2009\u00b7\u2009$0.03/$0.20\u2009\u00b7\u200900:00:11/00:03:00')
  // A done agent its parent messages again is RUNNING, and its limits are back.
  const again = { ...done, status: 'RUNNING' as const, endedAt: null }
  assert.equal(agentFigures(buildTree([again], ROOT)[0], [again], now).cost?.budget, 0.2)
})

test('the main agent waits for the person first, then a rate limit, then works, waits on its agents, or is idle', () => {
  const none: MainAgentSignals = { working: false, awaitingApproval: false, awaitingAnswer: false, rateLimited: false, waiting: false }
  assert.equal(mainAgentStatus(none), 'IDLE')
  assert.equal(mainAgentStatus({ ...none, waiting: true }), 'WAITING')
  assert.equal(mainAgentStatus({ ...none, working: true, waiting: true }), 'WORKING')
  assert.equal(mainAgentStatus({ ...none, working: true, rateLimited: true }), 'RATE_LIMITED')
  // A rate limit with no turn running is a stale flag, not a wait.
  assert.equal(mainAgentStatus({ ...none, rateLimited: true }), 'IDLE')
  assert.equal(mainAgentStatus({ ...none, working: true, awaitingAnswer: true }), 'QUESTION')
  assert.equal(mainAgentStatus({ ...none, working: true, rateLimited: true, awaitingAnswer: true, awaitingApproval: true }), 'APPROVAL')
  const t = (s: string) => s
  assert.equal(mainAgentStatusTitle('IDLE', t), 'Idle')
  assert.equal(mainAgentStatusTitle('WORKING', t), 'Working')
  assert.equal(mainAgentStatusTitle('APPROVAL', t), 'Waiting for approval')
  assert.equal(mainAgentWorkState('IDLE'), 'done')
  assert.equal(mainAgentWorkState('WAITING'), 'running')
  assert.equal(mainAgentWorkState('QUESTION'), 'pending')
})

test('the main agent\'s figures: its context, its own spend where priced, and its time worked counting the turn that runs', () => {
  const main: MainAgent = {
    working: false, awaitingApproval: false, rateLimited: false, waiting: false, inputTokens: 301, outputTokens: 1300,
    costUsd: 0.04, contextTokens: 12_500, contextWindow: 1_000_000, workedMs: 95_400, turnStartedAt: null,
  }
  const now = Date.parse('2026-11-03T08:00:30Z')
  const idle = mainFigures(main, now)
  assert.deepEqual(idle, {
    context: { used: 12_500, window: 1_000_000 },
    cost: { spent: 0.04, budget: null, withAgents: false },
    time: { seconds: 95, limitSeconds: null, deadlineAt: null, inherited: false },
  })
  const tokens = (n: number) => (n >= 1_000_000 ? `${n / 1_000_000}M` : `${(n / 1000).toFixed(1)}k`)
  assert.equal(figuresLine(idle, tokens, n => `$${n.toFixed(2)}`), '12.5k/1M\u2009\u00b7\u2009$0.04\u2009\u00b7\u200900:01:35')
  // A turn running for thirty seconds counts on; the page's live meter wins over the poll's context.
  const busy = mainFigures({ ...main, working: true, turnStartedAt: '2026-11-03T08:00:00Z' }, now, { used: 14_000, window: null })
  assert.equal(busy.time?.seconds, 125)
  assert.deepEqual(busy.context, { used: 14_000, window: null })
  // Nothing priced is no cost at all, not $0; a server that sent no main agent tells no time.
  assert.equal(mainFigures({ ...main, costUsd: 0 }, now).cost, null)
  assert.equal(mainFigures(undefined, now, { used: 500, window: 200_000 }).time, null)
  assert.equal(mainFigures({ ...main, workedMs: 0 }, now).time, null)
})
