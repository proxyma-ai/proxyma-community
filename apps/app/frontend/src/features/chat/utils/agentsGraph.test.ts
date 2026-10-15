import { test } from 'node:test'
import assert from 'node:assert/strict'
import { activeMs, anyRunning, cachedOf, compactTokens, elapsedMs, endedBadly, isLive, runningCount, shareOf, tokensOf, workSpans, type ConversationAgents, type Spend, type SubAgentSpend } from './agentsGraph.ts'

const spend = (inputTokens: number, outputTokens: number, costUsd: number, cacheReadTokens = 0): Spend =>
  ({ inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens: 0, costUsd })

const sub = (status: SubAgentSpend['status'], startedAt: number | null, endedAt: number | null): SubAgentSpend => ({
  ...spend(0, 0, 0), id: 'a', title: 'Task', mode: 'async', status, startedAt, endedAt, provider: null, model: null,
})

test('tokens count every kind, cached reads included', () => {
  assert.equal(tokensOf(spend(1000, 200, 0, 500)), 1700)
})

test('a share is by cost where anything was priced', () => {
  assert.equal(shareOf(spend(10, 10, 0.25), spend(1000, 1000, 1)), 0.25)
})

test('a share is by tokens where nothing was priced, and zero of nothing', () => {
  assert.equal(shareOf(spend(300, 100, 0), spend(600, 200, 0)), 0.5)
  assert.equal(shareOf(spend(0, 0, 0), spend(0, 0, 0)), 0)
})

test('a share never leaves 0..1', () => {
  assert.equal(shareOf(spend(0, 0, 2), spend(0, 0, 1)), 1)
})

test('a running sub-agent has run until now; an ended one until it ended', () => {
  assert.equal(elapsedMs(sub('running', 1_000, null), 4_000), 3_000)
  assert.equal(elapsedMs(sub('completed', 1_000, 2_500), 9_000), 1_500)
  assert.equal(elapsedMs(sub('running', null, null), 9_000), null)
})

test('the graph is live while any sub-agent runs', () => {
  const data: ConversationAgents = { main: spend(0, 0, 0), total: spend(0, 0, 0), subAgents: [sub('completed', 1, 2)] }
  assert.equal(anyRunning(data), false)
  assert.equal(anyRunning({ ...data, subAgents: [...data.subAgents, sub('running', 3, null)] }), true)
  assert.equal(anyRunning(undefined), false)
})

test('the figures are live while the turn runs, even with no sub-agent', () => {
  const idle: ConversationAgents = { main: spend(0, 0, 0), total: spend(0, 0, 0), subAgents: [sub('completed', 1, 2)] }
  assert.equal(isLive(idle, false), false)
  assert.equal(isLive(idle, true), true)
  assert.equal(isLive(undefined, true), true)
  assert.equal(isLive({ ...idle, subAgents: [sub('running', 3, null)] }, false), true)
})

test('the button counts the sub-agents working now, not the finished ones', () => {
  const data: ConversationAgents = {
    main: spend(0, 0, 0), total: spend(0, 0, 0),
    subAgents: [sub('running', 1, null), sub('completed', 1, 2), sub('running', 3, null), sub('failed', 1, 2)],
  }
  assert.equal(runningCount(data), 2)
  assert.equal(runningCount({ ...data, subAgents: [] }), 0)
  assert.equal(runningCount(undefined), 0)
})

test('failed and stopped ended badly; partial did not', () => {
  assert.equal(endedBadly(sub('failed', 1, 2)), true)
  assert.equal(endedBadly(sub('stopped', 1, 2)), true)
  assert.equal(endedBadly(sub('partial', 1, 2)), false)
})

test('a token count reads short, past a million too', () => {
  assert.equal(compactTokens(950), '950')
  assert.equal(compactTokens(12_345), '12.3k')
  assert.equal(compactTokens(5_090_600), '5.1M')
  assert.equal(compactTokens(200_000), '200k')
  assert.equal(compactTokens(2_000_000), '2M')
  assert.equal(compactTokens(0), '0')
})

test('the cache is what was read from it and written to it', () => {
  assert.equal(cachedOf({ inputTokens: 1, outputTokens: 1, cacheReadTokens: 800, cacheWriteTokens: 9, costUsd: 0 }), 809)
})

test('total time counts a moment once however many agents worked in it', () => {
  // Three agents side by side for the same minute are a minute, not three.
  assert.equal(activeMs([[0, 60_000], [0, 60_000], [10_000, 50_000]]), 60_000)
  // Overlapping and touching spans merge; a gap between them is not counted.
  assert.equal(activeMs([[0, 10], [5, 20], [20, 30], [100, 110]]), 40)
  // Order does not matter, and an empty or reversed span adds nothing.
  assert.equal(activeMs([[100, 110], [0, 10], [50, 50], [70, 60]]), 20)
  assert.equal(activeMs([]), 0)
})

test("the spans are the main agent's turns and the sub-agents' runs, open ones until now", () => {
  const data: ConversationAgents = {
    main: spend(0, 0, 0), total: spend(0, 0, 0),
    turns: [{ startedAt: 0, endedAt: 100 }, { startedAt: 1_000, endedAt: null }],
    subAgents: [sub('completed', 50, 400), sub('running', 900, null), sub('stopped', null, null)],
  }
  assert.deepEqual(workSpans(data, 1_500), [[0, 100], [1_000, 1_500], [50, 400], [900, 1_500]])
  // 0-400 while the main agent's first turn and the first sub-agent overlap, then 900-1500.
  assert.equal(activeMs(workSpans(data, 1_500)), 400 + 600)
})

test('a server that sends no turns still totals its sub-agents', () => {
  const data: ConversationAgents = { main: spend(0, 0, 0), total: spend(0, 0, 0), subAgents: [sub('completed', 10, 70)] }
  assert.equal(activeMs(workSpans(data, 1_000)), 60)
})
