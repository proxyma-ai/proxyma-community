import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  agentDone, agentText, agentToolFinished, agentToolStarted, anyAgentRunning, delegationCall, findAgent, runningAgents, startAgent,
  withAgent, withAgentInMessages,
} from './subAgent.ts'
import type { ChatMessage, SubAgentInfo, ToolCallInfo } from '../types/chat.types'

const delegate = (callId: string, extra: Partial<ToolCallInfo> = {}): ToolCallInfo =>
  ({ callId, toolName: 'delegate', arguments: '{}', done: false, ...extra })
const agent = (id: string, extra: Partial<SubAgentInfo> = {}): SubAgentInfo =>
  ({ id, mode: 'sync', status: 'running', items: [], ...extra })
const tool = (callId: string) => ({ kind: 'tool' as const, callId, toolName: 'searchIndexedSources', arguments: '{}', done: false })

test('a sub-agent starts on the delegate call that started it', () => {
  const calls = startAgent([delegate('d1'), delegate('d2')], 'd2', agent('a2'))
  assert.equal(calls[0].agent, undefined)
  assert.equal(calls[1].agent?.id, 'a2')
})

test('with a call id reused, it is the newest delegation that has just started', () => {
  const first = startAgent([delegate('delegate:0')], 'delegate:0', agent('a1'))
  const both = startAgent([...first, delegate('delegate:0')], 'delegate:0', agent('a2'))
  assert.deepEqual(both.map(c => c.agent?.id), ['a1', 'a2'])
})

test('a start heard twice changes nothing', () => {
  const once = startAgent([delegate('d1')], 'd1', agent('a1'))
  assert.equal(startAgent(once, 'd1', agent('a1')), once)
})

test('steps are listed in the order they happen, text between tool calls included', () => {
  let a = agent('a1')
  a = agentText(a, 1, 'Let me look')
  a = agentText(a, 1, 'Let me look at the page.')
  a = agentToolStarted(a, tool('c1'))
  a = agentToolFinished(a, 'c1', '{"results":[]}', 2_000)
  a = agentText(a, 2, 'Nothing there.')
  assert.deepEqual(a.items.map(i => i.kind === 'text' ? i.text : `${i.callId}:${i.done}`),
    ['Let me look at the page.', 'c1:true', 'Nothing there.'])
})

test('a tool start heard twice is one row; the same id on a later round is a new one', () => {
  let a = agentToolStarted(agent('a1'), tool('search:0'))
  a = agentToolStarted(a, tool('search:0'))
  assert.equal(a.items.length, 1)
  a = agentToolFinished(a, 'search:0', '{}', 1_000)
  a = agentToolStarted(a, tool('search:0'))
  assert.deepEqual(a.items.map(i => i.done), [true, false])
  // And its result lands on the one still running, not on the finished one.
  a = agentToolFinished(a, 'search:0', '{"second":true}', 2_000)
  assert.equal(a.items[1].result, '{"second":true}')
  assert.equal(a.items[0].result, '{}')
})

test('working clears the wait and the thinking count', () => {
  const waiting = agent('a1', { awaitingModelSince: 1_000, thinkingTokens: 40 })
  assert.equal(agentToolStarted(waiting, tool('c1')).awaitingModelSince, undefined)
  assert.equal(agentText(waiting, 1, 'x').thinkingTokens, undefined)
})

test('the paragraph it was writing when it ended gives way to the report', () => {
  let a = agentToolStarted(agent('a1'), tool('c1'))
  a = agentToolFinished(a, 'c1', '{}', 1_000)
  a = agentText(a, 2, 'Sonnet is $3.\nSTATUS: COMPLETED')
  a = agentDone(a, 'completed', 'Sonnet is $3.', 5_000)
  assert.equal(a.status, 'completed')
  assert.equal(a.report, 'Sonnet is $3.')
  assert.deepEqual(a.items.map(i => i.kind), ['tool'])
})

test('a stopped sub-agent keeps what it had said, and its open call is closed', () => {
  let a = agentText(agent('a1'), 1, 'Half a sentence')
  a = agentToolStarted(a, tool('c1'))
  a = agentDone(a, 'stopped', 'Stopped by the user.', 5_000)
  assert.deepEqual(a.items.map(i => i.kind), ['text', 'tool'])
  assert.equal(a.items[1].done, true)
  assert.match(a.items[1].result ?? '', /"success":false/)
})

test('a sub-agent is found and changed wherever its call is held', () => {
  const held: ChatMessage[] = [
    { role: 'user', content: 'q' },
    { role: 'assistant', content: 'Started it.', toolCalls: [delegate('d1', { done: true, agent: agent('a1', { mode: 'async' }) })] },
    { role: 'user', content: 'meanwhile' },
  ]
  assert.equal(findAgent(held, [], 'a1')?.mode, 'async')
  assert.equal(findAgent(held, [], 'nobody'), undefined)
  assert.equal(anyAgentRunning(held, []), true)

  const after = withAgentInMessages(held, 'a1', a => agentDone(a, 'completed', 'Done.', 9_000))
  assert.equal(after[1].toolCalls?.[0].agent?.status, 'completed')
  assert.equal(after[0], held[0], 'messages that do not hold it are untouched')
  assert.equal(anyAgentRunning(after, []), false)
  // Not in a list: the same list back, so nothing re-renders.
  const turn = [delegate('d9')]
  assert.equal(withAgent(turn, 'a1', a => a), turn)
  assert.equal(withAgentInMessages(held, 'nobody', a => a), held)
})

test('a sub-agent started in the running turn is found before any message claims its call', () => {
  // The running turn's calls wait in the store's list until text claims them into a message - the
  // agents map looked in the messages only and said a running sub-agent's steps were not loaded.
  const earlier = delegate('d1', { done: true, agent: agent('a1', { status: 'completed' }) })
  const held: ChatMessage[] = [{ role: 'assistant', content: 'Started it.', toolCalls: [earlier] }]
  const live = [delegate('d2', { agent: agent('a2') })]
  assert.equal(delegationCall(held, live, 'a2')?.callId, 'd2')
  assert.equal(delegationCall(held, live, 'a1')?.callId, 'd1')
  assert.equal(delegationCall(held, live, 'nobody'), undefined)
  assert.equal(findAgent(held, live, 'a2')?.status, 'running')
})

test('the sub-agents still working are listed in thread order, each once', () => {
  const held: ChatMessage[] = [
    { role: 'assistant', content: '', toolCalls: [
      delegate('d1', { done: true, agent: agent('a1', { mode: 'async' }) }),
      delegate('d2', { done: true, agent: agent('a2', { status: 'completed' }) }),
    ] },
    { role: 'user', content: 'meanwhile' },
    { role: 'assistant', content: '', toolCalls: [delegate('d3', { done: true, agent: agent('a3', { mode: 'async' }) })] },
  ]
  // The running turn's own, and one a late join also found in a message.
  const live = [delegate('d4', { agent: agent('a4') }), delegate('d1', { agent: agent('a1') })]
  assert.deepEqual(runningAgents(held, live).map(a => a.id), ['a1', 'a3', 'a4'])
  assert.deepEqual(runningAgents(held.slice(1, 2), []), [])
})
