import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  SAVE_IDLE, SAVED_SHOWN_MS, combinedSaveWord, commaList, createSaveQueue, decimalProblem, sameSet, saveSettled, saveStarted,
  saveWord, wholeNumberProblem,
} from './autosave.ts'

interface Item { name: string; url: string; on: boolean }
const BASE: Item = { name: 'Work', url: 'https://a.example', on: false }

// A send whose answers the test hands out by hand, in any order.
function manualSend() {
  const calls: { next: Item; resolve: (v?: Item) => void; reject: (e: unknown) => void }[] = []
  const send = (next: Item) => new Promise<Item | void>((resolve, reject) => { calls.push({ next, resolve, reject }) })
  return { calls, send }
}
const tick = () => new Promise(r => setTimeout(r, 0))

test('changes are sent one at a time, each over what the server confirmed before it', async () => {
  const { calls, send } = manualSend()
  const q = createSaveQueue(BASE, send)
  const first = q.save({ name: 'Home' })
  const second = q.save({ url: 'https://b.example' })
  await tick()
  assert.equal(calls.length, 1, 'the second waits for the first')
  assert.deepEqual(calls[0].next, { ...BASE, name: 'Home' })
  calls[0].resolve()
  assert.deepEqual(await first, { ...BASE, name: 'Home' })
  await tick()
  assert.equal(calls.length, 2)
  assert.deepEqual(calls[1].next, { name: 'Home', url: 'https://b.example', on: false }, 'the first change rides along')
  calls[1].resolve()
  await second
  assert.deepEqual(q.saved(), { name: 'Home', url: 'https://b.example', on: false })
  assert.equal(q.busy(), false)
})

test('what is shown carries every change on its way, and a refused one goes from both the page and the next send', async () => {
  const { calls, send } = manualSend()
  const shownLog: Item[] = []
  const q = createSaveQueue(BASE, send, s => shownLog.push(s))
  const refused = q.save({ name: 'Taken' })
  const kept = q.save({ on: true })
  assert.deepEqual(q.shown(), { name: 'Taken', url: BASE.url, on: true })
  await tick()
  calls[0].reject(new Error('There is already a connection called Taken'))
  await assert.rejects(refused)
  assert.deepEqual(q.shown(), { ...BASE, on: true }, 'the refused name is gone from the page')
  await tick()
  assert.deepEqual(calls[1].next, { ...BASE, on: true }, 'and from the next request')
  calls[1].resolve()
  await kept
  assert.deepEqual(q.saved(), { ...BASE, on: true })
  assert.deepEqual(shownLog.at(-1), { ...BASE, on: true })
})

test('the server answer is what is kept when it gives one', async () => {
  const q = createSaveQueue(BASE, async next => ({ ...next, url: next.url.replace(/\/+$/, '') }))
  await q.save({ url: 'https://c.example///' })
  assert.equal(q.saved().url, 'https://c.example')
})

test('a fresher state from a refetch is taken only while nothing is on its way', async () => {
  const { calls, send } = manualSend()
  const q = createSaveQueue(BASE, send)
  const pending = q.save({ name: 'Home' })
  assert.equal(q.rebase({ ...BASE, url: 'https://elsewhere.example' }), false)
  await tick()
  calls[0].resolve()
  await pending
  assert.equal(q.rebase({ ...q.saved(), url: 'https://elsewhere.example' }), true)
  assert.deepEqual(q.shown(), { name: 'Home', url: 'https://elsewhere.example', on: false })
})

test('a comma list is trimmed words, no empties, no repeats; a set compares in any order', () => {
  assert.deepEqual(commaList(' jira, reporting ,, jira,'), ['jira', 'reporting'])
  assert.deepEqual(commaList(''), [])
  assert.equal(sameSet(['a', 'b'], ['b', 'a']), true)
  assert.equal(sameSet(['a'], ['a', 'b']), false)
})

test('numbers are checked before they are saved', () => {
  assert.equal(wholeNumberProblem('3', 0, 50), null)
  assert.deepEqual(wholeNumberProblem('51', 0, 50), { code: 'whole', min: 0, max: 50 })
  assert.deepEqual(wholeNumberProblem('2.5', 0, 50), { code: 'whole', min: 0, max: 50 })
  assert.deepEqual(wholeNumberProblem('', 1, 10), { code: 'whole', min: 1, max: 10 })
  assert.equal(decimalProblem(''), null)
  assert.equal(decimalProblem('0.15'), null)
  assert.equal(decimalProblem('3'), null)
  assert.equal(decimalProblem('.5'), null)
  assert.deepEqual(decimalProblem('-1'), { code: 'decimal' })
  assert.deepEqual(decimalProblem('abc'), { code: 'decimal' })
})

test('idle settles once every change made so far has, a refused one included', async () => {
  const { calls, send } = manualSend()
  const q = createSaveQueue(BASE, send)
  q.save({ name: 'Home' }).catch(() => undefined)
  q.save({ url: 'https://b.example' }).catch(() => undefined)
  let settled = false
  const idle = q.idle().then(() => { settled = true })
  await tick()
  calls[0].reject(new Error('refused'))
  await tick()
  assert.equal(settled, false, 'the second change is still on its way')
  calls[1].resolve()
  await idle
  assert.equal(settled, true)
})

test('the dialog says Saving... while a change is on its way, then Saved. for a moment', () => {
  let s = saveStarted(SAVE_IDLE)
  assert.equal(saveWord(s, 0), 'saving')
  s = saveStarted(s)
  s = saveSettled(s, true, 1000)
  assert.equal(saveWord(s, 1000), 'saving', 'one is still on its way')
  s = saveSettled(s, true, 1200)
  assert.equal(saveWord(s, 1200), 'saved')
  assert.equal(saveWord(s, 1200 + SAVED_SHOWN_MS - 1), 'saved')
  assert.equal(saveWord(s, 1200 + SAVED_SHOWN_MS), null, 'and then nothing')
})

test('a refusal says nothing here, and takes away a Saved. an earlier change left', () => {
  let s = saveSettled(saveStarted(SAVE_IDLE), true, 0)
  assert.equal(saveWord(s, 10), 'saved')
  s = saveSettled(saveStarted(s), false, 20)
  assert.equal(saveWord(s, 30), null)
  assert.equal(saveSettled(SAVE_IDLE, true, 0).inFlight, 0, 'never below zero')
})

test('a dialog with two queues says saving while either is, else saved while either says so', () => {
  assert.equal(combinedSaveWord('saved', 'saving'), 'saving')
  assert.equal(combinedSaveWord(null, 'saved'), 'saved')
  assert.equal(combinedSaveWord(null, null), null)
})
