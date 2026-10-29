import { test } from 'node:test'
import assert from 'node:assert/strict'
import { elapsedMs, elapsedParts, toolBlocks } from './toolBlocks.ts'
import type { ToolCallInfo } from '../types/chat.types'

const call = (callId: string, extra: Partial<ToolCallInfo> = {}): ToolCallInfo =>
  ({ callId, toolName: 'searchIndexedSources', arguments: '{}', done: true, ...extra })
const deploy = (callId: string) => call(callId, { toolName: 'deployAgent' })

const shape = (calls: ToolCallInfo[]) => toolBlocks(calls).map(b => b.kind === 'tools'
  ? `tools(${b.calls.map(c => c.callId).join(',')})`
  : `agent ${b.call.callId}`)

test('tools, then deployed agents, then tools: each agent a block of its own, in the order they ran', () => {
  const calls = [call('t1'), call('t2'), deploy('d1'), deploy('d2'), call('t3')]
  assert.deepEqual(shape(calls), ['tools(t1,t2)', 'agent d1', 'agent d2', 'tools(t3)'])
})

test('a deployed agent is never a row inside a tool group', () => {
  assert.deepEqual(shape([call('t1'), deploy('d1'), call('t2')]), ['tools(t1)', 'agent d1', 'tools(t2)'])
})

test('a stored delegation and the steps it once nested are ordinary calls, never dropped', () => {
  const calls = [
    call('t1'),
    call('d1', { toolName: 'delegate' }),
    call('d1-a', { parentCallId: 'd1' }),
    call('s1', { toolName: 'steerSubAgent' }),
    call('m1', { toolName: 'messageMainAgent' }),
  ]
  assert.deepEqual(shape(calls), ['tools(t1,d1,d1-a,s1,m1)'])
})

test('no calls, no blocks', () => {
  assert.deepEqual(toolBlocks([]), [])
})

test('elapsed runs from the first start to the last end', () => {
  const calls = [
    call('a', { startedAt: 1_000, endedAt: 3_000 }),
    call('b', { startedAt: 1_000, endedAt: 9_500 }),
  ]
  assert.equal(elapsedMs(calls, 99_000), 8_500)
})

test('a call still running counts up to now', () => {
  const calls = [call('a', { startedAt: 1_000, endedAt: 2_000 }), call('b', { startedAt: 2_500, done: false })]
  assert.equal(elapsedMs(calls, 12_000), 11_000)
})

test('calls stored before they were timed have no elapsed time, rather than a made-up one', () => {
  assert.equal(elapsedMs([call('a')], 5_000), undefined)
  // Finished, with a start and no end - an interrupted call settled by the page.
  assert.equal(elapsedMs([call('a', { startedAt: 1_000 })], 5_000), undefined)
})

test('a clock that runs behind never yields a negative time', () => {
  assert.equal(elapsedMs([call('a', { startedAt: 5_000, done: false })], 4_000), 0)
})

test('durations split into hours, minutes and seconds', () => {
  assert.deepEqual(elapsedParts(192_400), { hours: 0, minutes: 3, seconds: 12 })
  assert.deepEqual(elapsedParts(3_725_000), { hours: 1, minutes: 2, seconds: 5 })
  assert.deepEqual(elapsedParts(900), { hours: 0, minutes: 0, seconds: 0 })
})
