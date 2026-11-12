import { test } from 'node:test'
import assert from 'node:assert/strict'
import { SIGN_IN_WAIT_MS, signInRoute, signInWaitState, type SignInWait } from './signInFlow.ts'

test('Desktop and Home Server sign in through the browser; a server with accounts and an edition flow navigate', () => {
  assert.equal(signInRoute({ multiUser: false, editionOwn: false }), 'browser')
  assert.equal(signInRoute({ multiUser: true, editionOwn: false }), 'navigate')
  assert.equal(signInRoute({ multiUser: false, editionOwn: true }), 'navigate')
  assert.equal(signInRoute({ multiUser: true, editionOwn: true }), 'navigate')
})

const wait = (since: string | null): SignInWait => ({ startedAt: 1_000, since, url: 'https://accounts.example/auth' })

test('a new connection is done the moment it reads signed in', () => {
  assert.equal(signInWaitState(wait(null), { status: 'CONNECTED', connectedAt: '2026-10-31T08:00:00Z' }, 2_000), 'done')
  assert.equal(signInWaitState(wait(null), { status: null, connectedAt: null }, 2_000), 'waiting')
  assert.equal(signInWaitState(wait(null), undefined, 2_000), 'waiting')
})

test('a sign-in refused on the consent screen, or failed, ends the wait at once - only one newer than the wait', () => {
  const at = '2026-11-09T09:00:00Z'
  assert.equal(signInWaitState(wait(null), { status: null, connectedAt: null, signInFailure: { outcome: 'DENIED', at } }, 2_000), 'refused')
  assert.equal(signInWaitState(wait(null), { status: null, connectedAt: null, signInFailure: { outcome: 'FAILED', at } }, 2_000), 'failed')
  assert.equal(signInWaitState(wait(null), { status: null, connectedAt: null, signInFailure: { outcome: 'NOT_CONFIGURED', at } }, 2_000), 'failed')
  // The one there when the wait began is an older sign-in's, not this one's.
  const older: SignInWait = { ...wait(null), failedSince: at }
  assert.equal(signInWaitState(older, { status: null, connectedAt: null, signInFailure: { outcome: 'DENIED', at } }, 2_000), 'waiting')
  // A sign-in that went through wins over a failure remembered from before it.
  assert.equal(signInWaitState(wait(null), { status: 'CONNECTED', connectedAt: at, signInFailure: { outcome: 'DENIED', at } }, 2_000), 'done')
})

test('signing in again is done only once the sign-in time changes', () => {
  const before = '2026-10-30T08:00:00Z'
  assert.equal(signInWaitState(wait(before), { status: 'CONNECTED', connectedAt: before }, 2_000), 'waiting')
  assert.equal(signInWaitState(wait(before), { status: 'CONNECTED', connectedAt: '2026-10-31T09:00:00Z' }, 2_000), 'done')
  // A connection that still needs a sign-in is not done: a new sign-in time with that status is a sign-in
  // the person allowed only part of (2026-11-08) - the wait ends and says so; the old time is still waiting.
  assert.equal(signInWaitState(wait(before), { status: 'NEEDS_RECONNECT', connectedAt: '2026-10-31T09:00:00Z' }, 2_000), 'incomplete')
  assert.equal(signInWaitState(wait(before), { status: 'NEEDS_RECONNECT', connectedAt: before }, 2_000), 'waiting')
  assert.equal(signInWaitState(wait(null), { status: 'NEEDS_RECONNECT', connectedAt: '2026-10-31T09:00:00Z' }, 2_000), 'incomplete')
})

test('the wait gives up after five minutes', () => {
  assert.equal(signInWaitState(wait(null), { status: null }, 1_000 + SIGN_IN_WAIT_MS), 'waiting')
  assert.equal(signInWaitState(wait(null), { status: null }, 1_000 + SIGN_IN_WAIT_MS + 1), 'timeout')
})
