import { test } from 'node:test'
import assert from 'node:assert/strict'
import { startupDialogs, type StartupInput } from './startupDialogs.ts'

const ACTIVE = { accountStatus: 'ACTIVE', consentStale: false }

function server(overrides: Partial<StartupInput> = {}): StartupInput {
  return { accounts: true, user: ACTIVE, deletionGateDismissed: false, whatsNewPending: false, ...overrides }
}

test('an account that has agreed to the current terms gets the walkthrough and nothing else', () => {
  assert.deepEqual(startupDialogs(server()), { deletionGate: false, reConsent: false, whatsNew: false, tour: true })
})

test('an account that must accept the terms gets the consent gate and NOT the walkthrough', () => {
  // A first sign-in of an account an administrator created (it has accepted nothing) and an
  // account asked again after the terms changed both arrive as consentStale.
  const d = startupDialogs(server({ user: { accountStatus: 'ACTIVE', consentStale: true } }))
  assert.equal(d.reConsent, true)
  assert.equal(d.tour, false)
})

test('accepting the terms is what lets the walkthrough start', () => {
  const before = startupDialogs(server({ user: { accountStatus: 'ACTIVE', consentStale: true } }))
  const after = startupDialogs(server({ user: { accountStatus: 'ACTIVE', consentStale: false } }))
  assert.deepEqual([before.reConsent, before.tour], [true, false])
  assert.deepEqual([after.reConsent, after.tour], [false, true])
})

test('nobody signed in, or the account still loading, gets no walkthrough on a server', () => {
  // Signing out from the consent gate reloads the page signed out: this is the "declined" case.
  assert.equal(startupDialogs(server({ user: null })).tour, false)
  assert.equal(startupDialogs(server({ user: undefined })).tour, false)
  assert.equal(startupDialogs(server({ user: null })).reConsent, false)
})

test('the deletion gate comes before the consent gate, and a pending deletion gets neither later', () => {
  const pending = { accountStatus: 'PENDING_DELETION', consentStale: true }
  assert.deepEqual(startupDialogs(server({ user: pending })),
    { deletionGate: true, reConsent: false, whatsNew: false, tour: false })
  // "Keep waiting" closes the gate, but the account still cannot record consent or settings.
  assert.deepEqual(startupDialogs(server({ user: pending, deletionGateDismissed: true })),
    { deletionGate: false, reConsent: false, whatsNew: false, tour: false })
})

test('"What\'s new" waits for the gates, and the walkthrough waits for "What\'s new"', () => {
  const stale = startupDialogs(server({ user: { accountStatus: 'ACTIVE', consentStale: true }, whatsNewPending: true }))
  assert.deepEqual([stale.reConsent, stale.whatsNew, stale.tour], [true, false, false])
  const agreed = startupDialogs(server({ whatsNewPending: true }))
  assert.deepEqual([agreed.whatsNew, agreed.tour], [true, false])
  const closed = startupDialogs(server({ whatsNewPending: false }))
  assert.deepEqual([closed.whatsNew, closed.tour], [false, true])
})

test('Desktop and Home Server have no accounts: no gate ever, the walkthrough as before', () => {
  const local = { accounts: false, user: null, deletionGateDismissed: false, whatsNewPending: false }
  assert.deepEqual(startupDialogs(local), { deletionGate: false, reConsent: false, whatsNew: false, tour: true })
  // Whatever a stray user object would say, a build without accounts has no gate to raise.
  assert.deepEqual(startupDialogs({ ...local, user: { accountStatus: 'PENDING_DELETION', consentStale: true } }),
    { deletionGate: false, reConsent: false, whatsNew: false, tour: true })
  assert.deepEqual(startupDialogs({ ...local, whatsNewPending: true }),
    { deletionGate: false, reConsent: false, whatsNew: true, tour: false })
})
