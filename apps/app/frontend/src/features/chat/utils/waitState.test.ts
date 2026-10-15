import { test } from 'node:test'
import assert from 'node:assert/strict'
import { QUIET_MS, waitFace, type WaitSignals } from './waitState.ts'

const running: WaitSignals = {
  streaming: true, condensing: false, awaitingUser: false, thinking: false,
  awaitingModel: false, toolRunning: false, quietMs: 0,
}

test('nothing when the turn is not running, is condensing, or waits on the user', () => {
  assert.equal(waitFace({ ...running, streaming: false, awaitingModel: true }), null)
  assert.equal(waitFace({ ...running, condensing: true, awaitingModel: true }), null)
  assert.equal(waitFace({ ...running, awaitingUser: true, thinking: true }), null)
})

test('thinking outranks the provider wait', () => {
  assert.equal(waitFace({ ...running, thinking: true, awaitingModel: true }), 'thinking')
})

test('a model call out and unanswered is the provider wait, even after a quiet spell', () => {
  assert.equal(waitFace({ ...running, awaitingModel: true, quietMs: 60_000 }), 'model')
})

test('a running tool shows its own row, never the star names', () => {
  assert.equal(waitFace({ ...running, toolRunning: true, quietMs: 60_000 }), null)
})

test('text that just arrived is responding; a quiet turn waiting on nothing gets the star names', () => {
  assert.equal(waitFace({ ...running, quietMs: QUIET_MS - 1 }), null)
  assert.equal(waitFace({ ...running, quietMs: QUIET_MS }), 'idle')
})
