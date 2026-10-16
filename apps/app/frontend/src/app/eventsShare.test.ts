// Run: node --test apps/app/frontend/src/app/eventsShare.test.ts
//
// One /api/v1/events stream per browser: what travels between tabs, which tab's listeners an event
// reaches, and when the leader's stream is opened again. SSEProvider holds the EventSource, the Web
// Lock and the BroadcastChannel.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import {
  accepts, canShare, createStreamSession, eventMessage, eventType, identityMessage, probedIdentity, readMessage,
  streamIdentity,
} from './eventsShare.ts'

describe('the channel messages', () => {
  test('an event and an identity read back as they were written', () => {
    assert.deepEqual(readMessage(eventMessage('{"type":"connector-synced"}', 'u1')), eventMessage('{"type":"connector-synced"}', 'u1'))
    assert.deepEqual(readMessage(eventMessage('{"type":"connector-synced"}', null)), eventMessage('{"type":"connector-synced"}', null))
    assert.deepEqual(readMessage(identityMessage('u1')), identityMessage('u1'))
    assert.deepEqual(readMessage(identityMessage(null)), identityMessage(null))
  })

  test('anything else is not ours', () => {
    assert.equal(readMessage(null), null)
    assert.equal(readMessage('{"type":"x"}'), null)
    assert.equal(readMessage({ v: 2, kind: 'event', data: 'x' }), null)
    assert.equal(readMessage({ v: 1, kind: 'event', data: 3 }), null)
    assert.equal(readMessage({ v: 1, kind: 'identity', id: 7 }), null)
    assert.equal(readMessage({ v: 1, kind: 'reload' }), null)
  })

  test("an event that does not say whose stream it came from is no one's", () => {
    assert.equal(readMessage({ v: 1, kind: 'event', data: 'x' })?.id, undefined)
    assert.equal(readMessage({ v: 1, kind: 'event', data: 'x', id: 7 })?.id, undefined)
  })
})

describe('an event\'s type', () => {
  test('is the type its body names', () => {
    assert.equal(eventType('{"type":"connector-update","connectorId":"1"}'), 'connector-update')
  })

  test('is "message" for a body naming none, or not JSON', () => {
    assert.equal(eventType('{"connectorId":"1"}'), 'message')
    assert.equal(eventType('keepalive'), 'message')
    assert.equal(eventType('null'), 'message')
    assert.equal(eventType('{"type":3}'), 'message')
  })
})

test('one stream is shared only with both Web Locks and BroadcastChannel', () => {
  assert.equal(canShare({ locks: true, broadcast: true }), true)
  // Plain http on a LAN address is not a secure context: no navigator.locks.
  assert.equal(canShare({ locks: false, broadcast: true }), false)
  assert.equal(canShare({ locks: true, broadcast: false }), false)
})

describe('who the session probe says is signed in', () => {
  test('the user, or nobody on a 401', () => {
    assert.equal(probedIdentity({ ok: true, user: { id: 'u1' } }), 'u1')
    assert.equal(probedIdentity({ ok: true, user: null }), null)
    assert.equal(probedIdentity({ ok: false, status: 401 }), null)
  })

  test('nothing either way for a probe that failed otherwise', () => {
    assert.equal(probedIdentity({ ok: false, status: 429 }), undefined)
    assert.equal(probedIdentity({ ok: false, status: 503 }), undefined)
    assert.equal(probedIdentity({ ok: false, status: undefined }), undefined)
  })
})

describe('whose a stream is', () => {
  test('its first event names the user, or nobody', () => {
    assert.equal(streamIdentity('{"type":"stream-identity","userId":"u1"}'), 'u1')
    assert.equal(streamIdentity('{"type":"stream-identity","userId":null}'), null)
    assert.equal(streamIdentity('{"type":"stream-identity"}'), null)
  })

  test('any other event names no one', () => {
    assert.equal(streamIdentity('{"type":"connector-synced","userId":"u1"}'), undefined)
    assert.equal(streamIdentity('keepalive'), undefined)
    assert.equal(streamIdentity('null'), undefined)
  })
})

describe("which tab's listeners an event reaches", () => {
  test("every tab's, where there are no accounts", () => {
    assert.equal(accepts(false, undefined, undefined), true)
    assert.equal(accepts(false, undefined, null), true)
  })

  test("with accounts, only a tab whose own session is the stream's", () => {
    assert.equal(accepts(true, 'alice', 'alice'), true)
    assert.equal(accepts(true, null, null), true, "a guest, on a guest's stream")
    assert.equal(accepts(true, 'alice', 'bob'), false, 'a tab still showing alice, on a stream opened for bob')
    assert.equal(accepts(true, null, 'alice'), false, "a signed-out tab, on alice's stream")
    assert.equal(accepts(true, 'bob', null), false)
  })

  test('nothing while either side is not known', () => {
    assert.equal(accepts(true, undefined, 'alice'), false, 'a tab whose probe has not answered')
    assert.equal(accepts(true, 'alice', undefined), false, 'a stream that has not said whose it is')
    assert.equal(accepts(true, undefined, undefined), false)
  })
})

describe('when the leader reopens its stream', () => {
  // The session's answers drive SSEProvider: true means reopen, and a reopen calls opened().
  const reopenWhen = (session: ReturnType<typeof createStreamSession>, reopen: boolean) => {
    if (reopen) session.opened()
    return reopen
  }

  test("never while the session is the stream's", () => {
    const session = createStreamSession()
    session.opened()
    assert.equal(session.identified('alice'), false)
    assert.equal(session.announced('alice'), false)
    assert.equal(session.announced('alice'), false, 'another tab on the same account')
  })

  test('when another tab signs in as someone else, and the stale tab is handed none of it', () => {
    // A1 leads on alice's stream; A2 signs out and in as bob. A1 still shows alice.
    const session = createStreamSession()
    session.opened()
    session.identified('alice')
    assert.equal(reopenWhen(session, session.announced('bob')), true)
    assert.equal(session.identified('bob'), false)
    assert.equal(accepts(true, 'alice', session.id), false, 'A1, still showing alice')
    assert.equal(accepts(true, 'bob', session.id), true, 'A2, signed in as bob')
  })

  test('when the stream was opened on the sign-in page and the sign-in comes after', () => {
    const session = createStreamSession()
    session.opened()
    session.identified(null)
    assert.equal(session.announced('bob'), true)
  })

  test('when a sign-in is announced before the stream has said whose it is', () => {
    const session = createStreamSession()
    session.opened()
    assert.equal(session.announced('bob'), false, 'not known yet - compared when the stream says')
    assert.equal(reopenWhen(session, session.identified(null)), true)
    assert.equal(session.identified('bob'), false)
  })

  test('on a sign-out', () => {
    const session = createStreamSession()
    session.opened()
    session.identified('alice')
    assert.equal(session.announced(null), true)
  })

  test('once per announcement - a stale answer cannot make it reopen for ever', () => {
    const session = createStreamSession()
    session.opened()
    session.identified('bob')
    // A probe sent before the switch answers alice after it: one reopen, and the stream, opened
    // again under the browser's session, still says bob - which is no reason to reopen again.
    assert.equal(reopenWhen(session, session.announced('alice')), true)
    assert.equal(session.identified('bob'), false)
    assert.equal(session.identified('bob'), false)
  })

  test('a stream that never says whose it is - an older server - is the session last found', () => {
    const session = createStreamSession()
    session.announced('alice')
    session.opened()
    session.unnamed()
    assert.equal(session.id, 'alice')
    assert.equal(accepts(true, 'alice', session.id), true)
    // A sign-in elsewhere reopens it, and the reopened stream is taken as the new session's.
    assert.equal(reopenWhen(session, session.announced('bob')), true)
    session.unnamed()
    assert.equal(session.id, 'bob')
  })

  test("a stream that never says, before anyone has found a session, is no one's", () => {
    const session = createStreamSession()
    session.opened()
    session.unnamed()
    assert.equal(session.id, undefined)
    assert.equal(accepts(true, 'alice', session.id), false)
    assert.equal(accepts(false, undefined, session.id), true, "without accounts every event is the one user's")
  })

  test('a stream that said whose it is keeps that', () => {
    const session = createStreamSession()
    session.announced('alice')
    session.opened()
    session.identified('bob')
    session.unnamed()
    assert.equal(session.id, 'bob')
  })
})
