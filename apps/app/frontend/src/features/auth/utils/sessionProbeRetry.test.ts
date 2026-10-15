import { test } from 'node:test'
import assert from 'node:assert/strict'
import { SESSION_PROBE_MAX_RETRIES, isTransientProbeFailure, retrySessionProbe, sessionProbeDelay } from './sessionProbeRetry.ts'

const failed = (status?: number, headers: Record<string, unknown> = {}) =>
  status === undefined ? new Error('Network Error') : { response: { status, headers } }

test('a rate limit, a failing server or no answer is asked again', () => {
  assert.equal(retrySessionProbe(0, failed(429)), true)
  assert.equal(retrySessionProbe(0, failed(503)), true)
  assert.equal(retrySessionProbe(0, failed(502)), true)
  assert.equal(retrySessionProbe(0, failed()), true)
})

test('"nobody is signed in" is an answer, and is not asked again', () => {
  assert.equal(retrySessionProbe(0, failed(401)), false)
  assert.equal(retrySessionProbe(0, failed(403)), false)
  assert.equal(isTransientProbeFailure(failed(404)), false)
})

test('it gives up after a bounded number of tries', () => {
  assert.equal(retrySessionProbe(SESSION_PROBE_MAX_RETRIES - 1, failed(429)), true)
  assert.equal(retrySessionProbe(SESSION_PROBE_MAX_RETRIES, failed(429)), false)
})

test('it waits as long as the limiter says, within reason', () => {
  assert.equal(sessionProbeDelay(0, failed(429, { 'retry-after': '7' })), 7000)
  assert.equal(sessionProbeDelay(0, failed(429, { 'retry-after': 12 })), 12_000)
  assert.equal(sessionProbeDelay(0, failed(429, { 'retry-after': '600' })), 30_000)
})

test('otherwise the pause doubles, up to half a minute', () => {
  assert.equal(sessionProbeDelay(0, failed(503)), 1000)
  assert.equal(sessionProbeDelay(1, failed(503)), 2000)
  assert.equal(sessionProbeDelay(3, failed()), 8000)
  assert.equal(sessionProbeDelay(10, failed(503)), 30_000)
  assert.equal(sessionProbeDelay(2, failed(429, { 'retry-after': 'soon' })), 4000)
})
