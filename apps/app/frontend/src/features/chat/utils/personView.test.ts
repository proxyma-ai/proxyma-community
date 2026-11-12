import { test } from 'node:test'
import assert from 'node:assert/strict'
import { personView } from './personView.ts'
import type { ChatMessage, ToolCallInfo } from '../types/chat.types'

const call = (callId: string, done = false): ToolCallInfo => ({ callId, toolName: 'messageAgent', arguments: '{}', done })

test('the person, the answers and the tool calls they came with stay', () => {
  const thread: ChatMessage[] = [
    { role: 'user', content: 'Fix the drafts waiting for approval.', id: 1 },
    { role: 'assistant', content: 'Asked the Reply Writer.', id: 2, toolCalls: [call('c1', true)] },
    { role: 'assistant', content: 'Two drafts are fixed.', id: 3 },
  ]
  const shown = personView(thread)
  assert.deepEqual(shown.map(m => m.content), ['Fix the drafts waiting for approval.', 'Asked the Reply Writer.', 'Two drafts are fixed.'])
  assert.equal(shown[1].toolCalls?.length, 1, 'what the lead did is drawn (2026-11-07)')
})

test('a teammate\'s reply or message, a report and the workspace memory are not drawn', () => {
  const thread: ChatMessage[] = [
    { role: 'user', content: 'Any news?' },
    { role: 'assistant', content: '', agentMail: [{ agentId: null, title: 'Reply Writer', kind: 'reply', text: 'Done.' }] },
    { role: 'assistant', content: '', agentMail: [{ agentId: null, title: 'Scout (Collects), your peer', kind: 'message', text: 'Hi' }] },
    { role: 'assistant', content: '', agentMail: [{ agentId: 'a1', title: 'helper', kind: 'report' }] },
    { role: 'assistant', content: '', agentMail: [{ agentId: null, title: null, kind: 'memory' }] },
    { role: 'assistant', content: 'Nothing new.' },
  ]
  assert.deepEqual(personView(thread).map(m => m.content), ['Any news?', 'Nothing new.'])
})

test('a row of tool calls alone is drawn - the lead acted, and the person sees what it did - without its teammates\' mail', () => {
  const thread: ChatMessage[] = [{ role: 'assistant', content: '', toolCalls: [call('c1')] }]
  assert.equal(personView(thread).length, 1)
  const withMail: ChatMessage[] = [{
    role: 'assistant', content: '', toolCalls: [call('c2')],
    agentMail: [{ agentId: null, title: 'Reply Writer', kind: 'reply', text: 'Done.' }],
  }]
  const shown = personView(withMail)
  assert.equal(shown[0].toolCalls?.length, 1)
  assert.equal(shown[0].agentMail, undefined, 'the teammate\'s reply is still not drawn')
  assert.equal(withMail[0].agentMail?.length, 1, 'the store\'s own row is left as it was')
})

test('what the agent told the person from a run is drawn, with its words, and nothing else of that row', () => {
  const thread: ChatMessage[] = [
    { role: 'assistant', content: '', agentMail: [
      { agentId: null, title: 'Reply Writer', kind: 'reply', text: 'Done.' },
      { agentId: null, title: '(from your scheduled run)', kind: 'told', text: 'Needs your decision: 8 items.' },
    ] },
    { role: 'assistant', content: '', agentMail: [{ agentId: null, title: null, kind: 'told' }] },
  ]
  const shown = personView(thread)
  assert.equal(shown.length, 1, 'a told entry with no words is nothing to read')
  assert.deepEqual(shown[0].agentMail?.map(e => e.kind), ['told'])
  assert.equal(shown[0].agentMail?.[0].text, 'Needs your decision: 8 items.')
})

test('a failure and a step-limit notice stay: they are the person\'s to act on', () => {
  const thread: ChatMessage[] = [
    { role: 'assistant', content: 'The provider refused.', error: true },
    { role: 'assistant', content: '', stepLimit: 30, toolCalls: [call('c1', true)] },
  ]
  assert.equal(personView(thread).length, 2)
})

test('a queued message of the person stays at the end', () => {
  const thread: ChatMessage[] = [
    { role: 'assistant', content: '', toolCalls: [call('c1')] },
    { role: 'user', content: 'And the third one?', queued: true, clientId: 'q1' },
  ]
  assert.equal(personView(thread).at(-1)?.clientId, 'q1')
})
