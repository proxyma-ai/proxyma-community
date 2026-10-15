import { test } from 'node:test'
import assert from 'node:assert/strict'
import { appendSettled, applyStepLimit, applyText, claimToolCalls, placeInjected, settledLength } from './threadOps.ts'
import type { ChatMessage, ToolCallInfo } from '../types/chat.types'

const user = (content: string, extra: Partial<ChatMessage> = {}): ChatMessage => ({ role: 'user', content, ...extra })
const agent = (content: string, extra: Partial<ChatMessage> = {}): ChatMessage => ({ role: 'assistant', content, ...extra })
const queued = (content: string, clientId: string): ChatMessage => user(content, { queued: true, clientId })
const call = (callId: string): ToolCallInfo => ({ callId, toolName: 'searchIndexedSources', arguments: '{}', done: true })

// What the thread reads as, top to bottom.
const shape = (messages: ChatMessage[]) => messages.map(m =>
  `${m.role === 'user' ? 'U' : 'A'}:${m.content}`
  + (m.toolCalls ? `[${m.toolCalls.map(tc => tc.callId).join(',')}]` : '')
  + (m.queued ? ' (queued)' : '')
  + (m.stepLimit !== undefined ? ` (limit ${m.stepLimit})` : ''))

test('text arriving after a message was queued extends the answer in progress, above the queued message', () => {
  // The report: queue a message mid-answer and the answer started again below it, leaving its
  // first half frozen above.
  const before = [user('q'), agent('one two'), queued('later', 'c1')]
  const { messages, opened } = applyText(before, 'one two three', false, [])
  assert.deepEqual(shape(messages), ['U:q', 'A:one two three', 'U:later (queued)'])
  assert.equal(opened, false)
})

test('a new answer opens above the queued message, and claims the steps that led to it', () => {
  const before = [user('q'), queued('later', 'c1')]
  const { messages, opened } = applyText(before, 'Found it.', true, [call('a')])
  assert.deepEqual(shape(messages), ['U:q', 'A:Found it.[a]', 'U:later (queued)'])
  assert.equal(opened, true)
})

test('the first text of a turn never extends the previous turn\'s answer', () => {
  const before = [user('q1'), agent('answer 1'), user('q2')]
  assert.deepEqual(shape(applyText(before, 'answer 2', false, []).messages),
    ['U:q1', 'A:answer 1', 'U:q2', 'A:answer 2'])
})

test('text never extends an error bubble', () => {
  const before = [user('q'), agent('Provider failed', { error: true })]
  assert.deepEqual(shape(applyText(before, 'hello', false, []).messages), ['U:q', 'A:Provider failed', 'A:hello'])
})

test('a message the turn takes lands after the steps made before it, and ahead of what still waits', () => {
  const before = [user('q'), agent('Let me look.'), queued('first', 'c1'), queued('second', 'c2')]
  const withSteps = claimToolCalls(before, [call('a'), call('b')])
  const messages = placeInjected(withSteps, 'c1', 'first')
  assert.deepEqual(shape(messages), ['U:q', 'A:Let me look.', 'A:[a,b]', 'U:first', 'U:second (queued)'])
  // The second one, taken at the same step, lands after the first - not before it.
  assert.deepEqual(shape(placeInjected(messages, 'c2', 'second')),
    ['U:q', 'A:Let me look.', 'A:[a,b]', 'U:first', 'U:second'])
})

test('a message already in the thread proper is not moved by being taken again', () => {
  // Sent as a turn of its own (no longer queued), the turn has spoken and taken steps under it -
  // and then the same message is reported taken: a replayed event, or a copy the server held on to.
  const thread = [user('q'), agent('Done.'), user('search wider', { clientId: 'c1' }), agent('Searching.', { toolCalls: [call('a')] })]
  assert.equal(placeInjected(thread, 'c1', 'search wider'), thread)
  // Placed once, then the event again: it stays where it was placed.
  const placed = placeInjected([user('q'), agent('Let me look.'), queued('first', 'c1')], 'c1', 'first')
  assert.deepEqual(shape(placeInjected([...placed, agent('Answering it.')], 'c1', 'first')),
    ['U:q', 'A:Let me look.', 'U:first', 'A:Answering it.'])
})

test('a taken message this page never queued is shown from the server\'s text', () => {
  assert.deepEqual(shape(placeInjected([user('q')], 'unknown', 'typed in another tab')),
    ['U:q', 'U:typed in another tab'])
})

test('steps no answer followed get a row of their own rather than vanishing', () => {
  assert.deepEqual(shape(claimToolCalls([user('q')], [call('a')])), ['U:q', 'A:[a]'])
  const unchanged = [user('q')]
  assert.equal(claimToolCalls(unchanged, []), unchanged)
})

test('the step-limit notice goes under the answer the last step wrote', () => {
  const messages = applyStepLimit([user('q'), agent('As far as I got.')], 10, [], true)
  assert.deepEqual(shape(messages), ['U:q', 'A:As far as I got. (limit 10)'])
})

test('with no answer in progress it goes on a row of its own, after the steps not yet claimed', () => {
  // The last step asked for tools instead of answering: the answer above belongs to an earlier
  // step, and the notice follows the calls that were not run.
  assert.deepEqual(shape(applyStepLimit([user('q'), agent('Looking.')], 10, [call('b')], true)),
    ['U:q', 'A:Looking.', 'A:[b] (limit 10)'])
  assert.deepEqual(shape(applyStepLimit([user('q'), agent('Looking.')], 10, [], false)),
    ['U:q', 'A:Looking.', 'A: (limit 10)'])
  assert.deepEqual(shape(applyStepLimit([user('q')], 5, [], false)), ['U:q', 'A: (limit 5)'])
})

test('queued messages always trail the thread', () => {
  const before = [user('q'), queued('one', 'c1'), queued('two', 'c2')]
  assert.equal(settledLength(before), 1)
  assert.deepEqual(shape(appendSettled(before, agent('x'))), ['U:q', 'A:x', 'U:one (queued)', 'U:two (queued)'])
  assert.equal(settledLength([user('q')]), 1)
})
