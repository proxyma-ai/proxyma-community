// Run: node --test apps/app/frontend/src/features/settings/utils/settingNumbers.test.ts
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { DAYS, DOLLARS, RESET_DAY, normalizedNumber, numberProblem } from './settingNumbers.ts'

describe('a number typed into a setting', () => {
  test('a word, an exponent, a comma or a bare point is not a number', () => {
    for (const s of ['abc', '1e3', '12abc', '1,5', '', ' ', '.5', '5.', '--1', 'Infinity', 'NaN']) {
      assert.deepEqual(numberProblem(s, DOLLARS), { kind: 'notNumber' }, s)
    }
  })

  test('a count takes no fraction', () => {
    assert.deepEqual(numberProblem('7.5', DAYS), { kind: 'notWhole' })
    assert.equal(numberProblem('7', DAYS), null)
    assert.equal(numberProblem(' 7 ', DAYS), null)
  })

  test('an amount does', () => {
    assert.equal(numberProblem('12.50', DOLLARS), null)
    assert.equal(numberProblem('0', DOLLARS), null)
  })

  test('below the range is too small, past it too large', () => {
    assert.deepEqual(numberProblem('-1', DAYS), { kind: 'tooSmall', min: 0 })
    assert.deepEqual(numberProblem('0', RESET_DAY), { kind: 'tooSmall', min: 1 })
    assert.deepEqual(numberProblem('29', RESET_DAY), { kind: 'tooLarge', max: 28 })
    assert.equal(numberProblem('28', RESET_DAY), null)
    assert.equal(numberProblem('1', RESET_DAY), null)
    assert.deepEqual(numberProblem('36501', DAYS), { kind: 'tooLarge', max: 36500 })
  })

  test('a rule with no ceiling takes any size', () => {
    assert.equal(numberProblem('99999999', { min: 60, whole: true }), null)
    assert.deepEqual(numberProblem('59', { min: 60, whole: true }), { kind: 'tooSmall', min: 60 })
  })

  test('is stored without padding', () => {
    assert.equal(normalizedNumber('007'), '7')
    assert.equal(normalizedNumber('1.50'), '1.5')
    assert.equal(normalizedNumber(' 30 '), '30')
    assert.equal(normalizedNumber('0'), '0')
  })
})
