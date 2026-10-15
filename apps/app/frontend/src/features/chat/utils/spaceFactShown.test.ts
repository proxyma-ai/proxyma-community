import { test } from 'node:test'
import assert from 'node:assert/strict'
import { DEFAULT_FACT_SOURCE, shownSpaceFact } from './spaceFactShown.ts'

const DEFAULT = 'Proxima Centauri is the nearest star to the Sun.'
const generated = {
  text: 'Venus turns once in 243 days.', sourceTitle: 'Wikipedia - Venus',
  sourceUrl: 'https://en.wikipedia.org/wiki/Venus', generatedAt: 1,
}

test('nothing while the reader has the fact off, whatever the server has', () => {
  assert.equal(shownSpaceFact({ enabled: false, status: 'success', data: generated }, DEFAULT), null)
  assert.equal(shownSpaceFact({ enabled: false, status: 'pending', data: undefined }, DEFAULT), null)
})

test('nothing while the first answer is on its way - the default is not painted and then swapped', () => {
  assert.equal(shownSpaceFact({ enabled: true, status: 'pending', data: undefined }, DEFAULT), null)
})

test("the deployment's own fact when it has one", () => {
  assert.equal(shownSpaceFact({ enabled: true, status: 'success', data: generated }, DEFAULT), generated)
})

test('the Proxima Centauri default when the deployment has none, or the request failed', () => {
  for (const state of [
    { enabled: true, status: 'success' as const, data: null },
    { enabled: true, status: 'error' as const, data: undefined },
  ]) {
    const fact = shownSpaceFact(state, DEFAULT)
    assert.equal(fact?.text, DEFAULT)
    assert.equal(fact?.sourceUrl, DEFAULT_FACT_SOURCE.sourceUrl)
    assert.equal(fact?.sourceTitle, 'Wikipedia - Proxima Centauri')
  }
})

test("the default's source is a page the subject can be linked to", () => {
  assert.match(DEFAULT_FACT_SOURCE.sourceUrl, /^https:\/\/en\.wikipedia\.org\/wiki\/Proxima_Centauri$/)
})
