import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  agentStatusOf, applyStatus, buildTree, childrenBusy, compactTokens, controlsFor, deployResult, flattenTree,
  isBusy, isEnded, isWorking, parseAgentMail, parseAgentStatus, scoreOf, totalCost, withLive, workingCount,
  type AgentTreeData, type TreeAgent,
} from './agentTree.ts'
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
