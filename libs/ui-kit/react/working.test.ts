// Run: node --test libs/ui-kit/react/working.test.ts
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { WORKING_BLINK, WORKING_WAVE, workingMotion } from './working.ts'

describe('how something at work says so', () => {
  test('an icon alone blinks', () => {
    assert.equal(workingMotion('icon', true), WORKING_BLINK)
  })

  test('words wave instead of blinking', () => {
    assert.equal(workingMotion('text', true), WORKING_WAVE)
    assert.notEqual(WORKING_WAVE, WORKING_BLINK)
  })

  test('nothing moves once the work is done', () => {
    assert.equal(workingMotion('icon', false), '')
    assert.equal(workingMotion('text', false), '')
  })
})
