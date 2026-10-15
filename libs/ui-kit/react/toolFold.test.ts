// Run: node --test libs/ui-kit/react/toolFold.test.ts
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { toolFold } from './toolFold.ts'

describe('which tool calls a group draws', () => {
  test('every running call is drawn, however many run at once, and none can be folded away', () => {
    assert.deepEqual(toolFold([false, false, false, false, false], false), { shown: [true, true, true, true, true], foldable: false })
  })

  test('a call folds away as it finishes, while the others run', () => {
    assert.deepEqual(toolFold([false, true, false, true], false), { shown: [true, false, true, false], foldable: true })
  })

  test('once all are finished, the last one alone is left, with the chevron', () => {
    assert.deepEqual(toolFold([true, true, true], false), { shown: [false, false, true], foldable: true })
  })

  test('opened, every call is drawn', () => {
    assert.deepEqual(toolFold([true, false, true], true).shown, [true, true, true])
  })

  test('one call has nothing to fold, finished or not', () => {
    assert.deepEqual(toolFold([true], false), { shown: [true], foldable: false })
    assert.deepEqual(toolFold([true], true), { shown: [true], foldable: false })
    assert.deepEqual(toolFold([], false), { shown: [], foldable: false })
  })
})
