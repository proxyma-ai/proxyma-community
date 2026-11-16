import { test } from 'node:test'
import assert from 'node:assert/strict'
import { startupDialogs, type StartupInput } from './startupDialogs.ts'

const ACTIVE = { accountStatus: 'ACTIVE', consentStale: false }

function server(overrides: Partial<StartupInput> = {}): StartupInput {
  return { accounts: true, user: ACTIVE, deletionGateDismissed: false, whatsNewPending: false, ...overrides }
}

test('an account that has agreed to the current terms gets the walkthrough and nothing else', () => {
  assert.deepEqual(startupDialogs(server()), { legalGate: false, deletionGate: false, reConsent: false, whatsNew: false, tour: true })
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
    { legalGate: false, deletionGate: true, reConsent: false, whatsNew: false, tour: false })
  // "Keep waiting" closes the gate, but the account still cannot record consent or settings.
  assert.deepEqual(startupDialogs(server({ user: pending, deletionGateDismissed: true })),
    { legalGate: false, deletionGate: false, reConsent: false, whatsNew: false, tour: false })
})

test('"What\'s new" waits for the gates, and the walkthrough waits for "What\'s new"', () => {
  const stale = startupDialogs(server({ user: { accountStatus: 'ACTIVE', consentStale: true }, whatsNewPending: true }))
  assert.deepEqual([stale.reConsent, stale.whatsNew, stale.tour], [true, false, false])
  const agreed = startupDialogs(server({ whatsNewPending: true }))
  assert.deepEqual([agreed.whatsNew, agreed.tour], [true, false])
  const closed = startupDialogs(server({ whatsNewPending: false }))
  assert.deepEqual([closed.whatsNew, closed.tour], [false, true])
})

test('Desktop and Home Server have no accounts: no account gate, the walkthrough as before once the terms are accepted', () => {
  const local = { accounts: false, user: null, deletionGateDismissed: false, whatsNewPending: false, legalAccepted: true }
  assert.deepEqual(startupDialogs(local), { legalGate: false, deletionGate: false, reConsent: false, whatsNew: false, tour: true })
  // Whatever a stray user object would say, a build without accounts has no gate to raise.
  assert.deepEqual(startupDialogs({ ...local, user: { accountStatus: 'PENDING_DELETION', consentStale: true } }),
    { legalGate: false, deletionGate: false, reConsent: false, whatsNew: false, tour: true })
  assert.deepEqual(startupDialogs({ ...local, whatsNewPending: true }),
    { legalGate: false, deletionGate: false, reConsent: false, whatsNew: true, tour: false })
})

test('Desktop and Home Server: the legal gate first, and nothing else until the terms are accepted', () => {
  const local = { accounts: false, user: null, deletionGateDismissed: false, whatsNewPending: true }
  assert.deepEqual(startupDialogs({ ...local, legalAccepted: false }),
    { legalGate: true, deletionGate: false, reConsent: false, whatsNew: false, tour: false })
  // Still asking: no gate yet, and neither "What's new" nor the walkthrough starts under it.
  for (const unknown of [undefined, null]) {
    assert.deepEqual(startupDialogs({ ...local, legalAccepted: unknown }),
      { legalGate: false, deletionGate: false, reConsent: false, whatsNew: false, tour: false })
  }
  assert.deepEqual(startupDialogs({ ...local, legalAccepted: true }),
    { legalGate: false, deletionGate: false, reConsent: false, whatsNew: true, tour: false })
})

test('Enterprise Server has no legal gate of the install: its accounts accept the terms', () => {
  assert.equal(startupDialogs(server({ legalAccepted: false })).legalGate, false)
  assert.equal(startupDialogs(server({ legalAccepted: undefined })).tour, true)
})
