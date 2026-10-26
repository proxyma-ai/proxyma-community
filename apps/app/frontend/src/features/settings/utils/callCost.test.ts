import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  cacheOutcome, cacheVerdict, isAvoidableMiss, costOrigin, EMPTY_RATE_FORM, hasLongTier, noCache, rateFormOf, ratePayload,
} from './callCost.ts'

test('a cost says where it came from', () => {
  assert.equal(costOrigin({ costSource: 'REPORTED', priced: true }), 'reported')
  assert.equal(costOrigin({ costSource: 'LIST', priced: true }), 'list')
  assert.equal(costOrigin({ costSource: 'RATE', priced: true }), 'rate')
  assert.equal(costOrigin({ costSource: 'NONE', priced: false }), 'unpriced')
})

test('a row from before the cost source is read by whether it was priced', () => {
  assert.equal(costOrigin({ costSource: null, priced: true }), 'rate')
  assert.equal(costOrigin({ costSource: null, priced: false }), 'unpriced')
  assert.equal(costOrigin({ priced: false }), 'unpriced')
})

test('a cache read is a hit, whatever was written beside it', () => {
  assert.deepEqual(cacheOutcome({ cacheReadTokens: 900, cacheWriteTokens: 0, longCacheWriteTokens: 0 }), { hit: true, write: 'none' })
  assert.deepEqual(cacheOutcome({ cacheReadTokens: 900, cacheWriteTokens: 50, longCacheWriteTokens: 0 }), { hit: true, write: 'short' })
})

test('a cache write is five minutes, an hour or some of each', () => {
  assert.equal(cacheOutcome({ cacheReadTokens: 0, cacheWriteTokens: 400, longCacheWriteTokens: 0 }).write, 'short')
  assert.equal(cacheOutcome({ cacheReadTokens: 0, cacheWriteTokens: 400, longCacheWriteTokens: 400 }).write, 'long')
  assert.equal(cacheOutcome({ cacheReadTokens: 0, cacheWriteTokens: 400, longCacheWriteTokens: 100 }).write, 'mixed')
})

test('a write recorded before the one-hour part existed has no lifetime to name', () => {
  assert.equal(cacheOutcome({ cacheReadTokens: 0, cacheWriteTokens: 400, longCacheWriteTokens: null }).write, 'unknown')
  assert.equal(cacheOutcome({ cacheReadTokens: 0, cacheWriteTokens: 400 }).write, 'unknown')
})

test('no read and no write is no cache', () => {
  assert.equal(noCache(cacheOutcome({ cacheReadTokens: 0, cacheWriteTokens: 0, longCacheWriteTokens: null })), true)
  assert.equal(noCache(cacheOutcome({ cacheReadTokens: 1, cacheWriteTokens: 0 })), false)
  assert.equal(noCache(cacheOutcome({ cacheReadTokens: 0, cacheWriteTokens: 1, longCacheWriteTokens: 0 })), false)
})

test('the form sends every field, blank optional ones as null', () => {
  assert.deepEqual(ratePayload({ ...EMPTY_RATE_FORM, input: '3', output: '15', cacheRead: '0.3', cacheWrite: '3.75' }), {
    inputPricePerMillion: 3,
    outputPricePerMillion: 15,
    cacheReadPricePerMillion: 0.3,
    cacheWritePricePerMillion: 3.75,
    cacheWriteLongPricePerMillion: null,
    longContextThresholdTokens: null,
    longInputPricePerMillion: null,
    longOutputPricePerMillion: null,
    longCacheReadPricePerMillion: null,
    longCacheWritePricePerMillion: null,
    pricingSchedule: null,
  })
})

test('a blank required price is 0, as it always was', () => {
  const sent = ratePayload(EMPTY_RATE_FORM)
  assert.equal(sent.inputPricePerMillion, 0)
  assert.equal(sent.cacheWritePricePerMillion, 0)
})

test('the long rates go only with a threshold, and a threshold of 0 is none', () => {
  const tiered = ratePayload({ ...EMPTY_RATE_FORM, threshold: '200000', longInput: '6', longOutput: '' })
  assert.equal(tiered.longContextThresholdTokens, 200000)
  assert.equal(tiered.longInputPricePerMillion, 6)
  // Blank is the standard rate.
  assert.equal(tiered.longOutputPricePerMillion, null)

  const untiered = ratePayload({ ...EMPTY_RATE_FORM, threshold: '0', longInput: '6' })
  assert.equal(untiered.longContextThresholdTokens, null)
  assert.equal(untiered.longInputPricePerMillion, null)
  assert.equal(ratePayload({ ...EMPTY_RATE_FORM, longInput: '6' }).longInputPricePerMillion, null)
})

test('the one-hour write and the schedule are sent as given', () => {
  const sent = ratePayload({ ...EMPTY_RATE_FORM, cacheWriteLong: '6', schedule: 'DEEPSEEK_OFF_PEAK' })
  assert.equal(sent.cacheWriteLongPricePerMillion, 6)
  assert.equal(sent.pricingSchedule, 'DEEPSEEK_OFF_PEAK')
})

test('a stored row reads back into the form it was sent from', () => {
  const form = {
    ...EMPTY_RATE_FORM,
    input: '1.25', output: '10', cacheRead: '0.125', cacheWrite: '0', cacheWriteLong: '',
    threshold: '200000', longInput: '2.5', longOutput: '15', schedule: 'DEEPSEEK_OFF_PEAK' as const,
  }
  assert.deepEqual(rateFormOf(ratePayload(form)), form)
  assert.equal(hasLongTier(form), true)
})

test('a row with no long tier shows none, whatever long rates it carries', () => {
  const form = rateFormOf({ inputPricePerMillion: 1, longContextThresholdTokens: 0, longInputPricePerMillion: 2 })
  assert.equal(form.threshold, '')
  assert.equal(form.longInput, '')
  assert.equal(hasLongTier(form), false)
  assert.deepEqual(rateFormOf(null), EMPTY_RATE_FORM)
})

test('a cache verdict the page knows is kept, anything else is nothing', () => {
  assert.equal(cacheVerdict('HIT'), 'HIT')
  assert.equal(cacheVerdict('CHANGED_TOOLS'), 'CHANGED_TOOLS')
  assert.equal(cacheVerdict('SOMETHING_NEWER'), null)
  assert.equal(cacheVerdict(null), null)
})

test('a changed prompt or a cache the provider did not keep is a miss worth acting on; a hit, a first write or an expiry is not', () => {
  assert.equal(isAvoidableMiss('CHANGED_SYSTEM'), true)
  assert.equal(isAvoidableMiss('NOT_KEPT'), true)
  assert.equal(isAvoidableMiss('EXPIRED'), false)
  assert.equal(isAvoidableMiss('FIRST'), false)
  assert.equal(isAvoidableMiss('HIT'), false)
  assert.equal(isAvoidableMiss(null), false)
})
