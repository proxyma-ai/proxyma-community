// Run: node --test apps/app/frontend/src/features/chat/utils/chatStream.test.ts
//
// The page's one chat stream. The hook around it needs EventSource and React; which stream is
// followed, and which events are handled, is decided here.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import {
  REPLAY_KEPT_MS, accept, carriedOnReload, ended, follow, keepClose, missed, newChatStream, opened, parseOpen, pause,
  resumeAfter, resumePoint, stop,
} from './chatStream.ts'

describe('one stream per page', () => {
  test('following again closes the stream opened before - whichever hook instance opened it', () => {
    // The 0.27.0 feedback: the instance before a remount opened the first, the next one the second.
    const page = newChatStream()
    let closed = 0
    const first = follow(page, 'c1', -1)
    keepClose(page, first, () => { closed += 1 })
    follow(page, 'c1', 7)
    assert.equal(closed, 1)
  })

  test('an event from the replaced stream is not handled, the new one\'s is', () => {
    const page = newChatStream()
    const first = follow(page, 'c1', -1)
    const second = follow(page, 'c1', 4)
    assert.equal(accept(page, first, 5), false)
    assert.equal(accept(page, second, 5), true)
  })

  test('a subscription that arrives after its stream was replaced is closed at once', () => {
    const page = newChatStream()
    const first = follow(page, 'c1', -1)
    follow(page, 'c2', -1)
    let closed = false
    keepClose(page, first, () => { closed = true })
    assert.equal(closed, true)
    assert.equal(page.close, null)
  })

  test('stopping closes the stream and ignores anything still in flight from it', () => {
    const page = newChatStream()
    let closed = false
    const g = follow(page, 'c1', -1)
    keepClose(page, g, () => { closed = true })
    stop(page)
    assert.equal(closed, true)
    assert.equal(accept(page, g, 3), false)
  })
})

describe('where a new stream starts', () => {
  test('after the last event handled, when the conversation is still followed', () => {
    // A send while deployed agents work in the background: the server kept the sink, and replaying it
    // from the start would put the previous turn on screen a second time.
    const page = newChatStream()
    const g = follow(page, 'c1', -1)
    accept(page, g, 41)
    assert.equal(resumeAfter(page, 'c1'), 41)
  })

  test('from the start for another conversation, or once the stream has ended', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', -1)
    accept(page, g, 41)
    assert.equal(resumeAfter(page, 'c2'), -1)
    ended(page, g)
    assert.equal(resumeAfter(page, 'c1'), -1)
  })

  test('an unnumbered event is handled and moves nothing', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', 9)
    assert.equal(accept(page, g, undefined), true)
    assert.equal(page.lastSeq, 9)
  })

  test('the end of a replaced stream does not end the one that replaced it', () => {
    const page = newChatStream()
    const first = follow(page, 'c1', -1)
    follow(page, 'c1', 3)
    ended(page, first)
    assert.equal(page.convId, 'c1')
  })
})

// Since the key-account chat panel (docs/architecture.html#spec-11-8): a page can show more
// than one chat, and each chat instance holds a stream of its own (features/chat/instance).
describe('one stream per chat instance', () => {
  test('following on one instance neither closes nor replaces another instance\'s stream', () => {
    const main = newChatStream()
    const panel = newChatStream()
    let mainClosed = false
    const g = follow(main, 'c1', -1)
    keepClose(main, g, () => { mainClosed = true })
    const p = follow(panel, 'k1', -1)
    keepClose(panel, p, () => {})
    stop(panel)
    assert.equal(mainClosed, false)
    assert.equal(accept(main, g, 3), true, 'the chat page still handles its own events')
    assert.equal(resumeAfter(main, 'c1'), 3)
  })

  test('the same conversation followed in two instances is resumed by each from its own place', () => {
    const one = newChatStream()
    const two = newChatStream()
    const a = follow(one, 'c1', -1)
    const b = follow(two, 'c1', -1)
    accept(one, a, 10)
    accept(two, b, 4)
    assert.equal(resumeAfter(one, 'c1'), 10)
    assert.equal(resumeAfter(two, 'c1'), 4)
  })
})

// Since the 0.36.0 feedback: tabs left open in the background held a chat stream each, and with
// the page-wide event stream beside them used up the browser's six connections to the site - the
// next tab's requests, its index.html included, waited for ever. A hidden tab now lets its stream go
// and keeps its place (chatInstance.ts, hiddenPause.ts).
describe('a hidden tab releases its stream and keeps its place', () => {
  test('pausing closes the connection and keeps the conversation and the last event', () => {
    const page = newChatStream()
    let closed = false
    const g = follow(page, 'c1', -1)
    keepClose(page, g, () => { closed = true })
    accept(page, g, 12)
    assert.equal(pause(page, 0), true)
    assert.equal(closed, true)
    assert.equal(page.close, null)
    assert.deepEqual(resumePoint(page, 1000), { convId: 'c1', afterSeq: 12, reload: false })
  })

  test('an event still in flight from the paused stream is not handled', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', -1)
    keepClose(page, g, () => {})
    pause(page, 0)
    assert.equal(accept(page, g, 13), false)
    assert.equal(page.lastSeq, -1, 'nothing recorded from it')
  })

  test('nothing to pause when nothing is followed, or the stream already ended', () => {
    const idle = newChatStream()
    assert.equal(pause(idle, 0), false)
    assert.equal(resumePoint(idle, 1000), null)
    const done = newChatStream()
    const g = follow(done, 'c1', -1)
    keepClose(done, g, () => {})
    ended(done, g)
    assert.equal(pause(done, 0), false)
    assert.equal(resumePoint(done, 1000), null)
  })

  test('paused longer than the server surely keeps the missed events, the conversation is loaded whole', () => {
    const page = newChatStream()
    keepClose(page, follow(page, 'c1', 4), () => {})
    pause(page, 1_000)
    assert.equal(resumePoint(page, 1_000 + REPLAY_KEPT_MS)?.reload, true)
    const brief = newChatStream()
    keepClose(brief, follow(brief, 'c1', 4), () => {})
    pause(brief, 1_000)
    assert.equal(resumePoint(brief, REPLAY_KEPT_MS)?.reload, false)
  })

  test('a paused stream is picked up once', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', 4)
    keepClose(page, g, () => {})
    pause(page, 0)
    assert.notEqual(resumePoint(page, 1000), null)
    assert.equal(resumePoint(page, 1000), null)
  })

  test('following or stopping meanwhile leaves nothing to pick up', () => {
    const page = newChatStream()
    keepClose(page, follow(page, 'c1', 4), () => {})
    pause(page, 0)
    follow(page, 'c2', -1)
    assert.equal(resumePoint(page, 1000), null)

    const other = newChatStream()
    keepClose(other, follow(other, 'c1', 4), () => {})
    pause(other, 0)
    stop(other)
    assert.equal(resumePoint(other, 1000), null)
  })

  test('a send while paused resumes from the place kept, as a send beside background deployed agents does', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', -1)
    keepClose(page, g, () => {})
    accept(page, g, 30)
    pause(page, 0)
    assert.equal(resumeAfter(page, 'c1'), 30)
  })

  test("pausing one chat instance leaves another's stream open", () => {
    const main = newChatStream()
    const panel = newChatStream()
    let mainClosed = false
    keepClose(main, follow(main, 'c1', -1), () => { mainClosed = true })
    keepClose(panel, follow(panel, 'k1', -1), () => {})
    pause(panel, 0)
    assert.equal(mainClosed, false)
    assert.equal(resumePoint(main, 1000), null)
  })
})

describe('what a stream picked up after a pause missed', () => {
  test('nothing, when its first new event is the next one', () => {
    // The server's buffer holds the turn from its start: events at or below the place kept are
    // skipped, and the turn goes on from the next number.
    const page = newChatStream()
    const g = follow(page, 'c1', 12, { resumed: true })
    assert.equal(missed(page, g, 13), false)
  })

  test('the events between, when the first new one is further on - a newer turn replaced the buffer', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', 12, { resumed: true })
    assert.equal(missed(page, g, 40), true)
  })

  test('asked once: later events are the stream going on', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', 12, { resumed: true })
    assert.equal(missed(page, g, 13), false)
    accept(page, g, 13)
    assert.equal(missed(page, g, 15), false)
  })

  test('an event already handled, or an unnumbered one, decides nothing', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', 12, { resumed: true })
    assert.equal(missed(page, g, 10), false)
    assert.equal(missed(page, g, undefined), false)
    assert.equal(missed(page, g, 14), true, 'the first new one still decides')
  })

  test('never for a stream that did not resume, or resumed from no known place', () => {
    const fresh = newChatStream()
    const g = follow(fresh, 'c1', 12)
    assert.equal(missed(fresh, g, 40), false)
    // -1 is "whatever the buffer holds", whose first event can carry any number.
    const unknown = newChatStream()
    const u = follow(unknown, 'c1', -1, { resumed: true })
    assert.equal(missed(unknown, u, 40), false)
  })

  test('never for an event of a replaced stream', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', 12, { resumed: true })
    follow(page, 'c1', 12, { resumed: true })
    assert.equal(missed(page, g, 40), false)
  })
})

// Since 2026-10-16: a server restarted mid-turn left the page showing "Waiting for ..." for ever.
// The restarted server numbers the conversation from zero and replays nothing, so the reconnected
// stream said nothing, and the next message went into the dead turn as an injection, refused with a
// 409 and left "Queued". Every stream now opens with which server it reached and whether anything
// runs there (ChatController.openEvent).
describe('a stream that opens on a server no longer running what the page follows', () => {
  const open = (boot: string, busy: boolean, next: number) => ({ boot, busy, next })

  test('another server process than the one followed: gone, whatever it says runs', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', -1)
    assert.equal(opened(page, g, open('boot-a', true, 0)), false, 'the first open only says which server it is')
    accept(page, g, 30)
    // The stream's own reconnect reaches the restarted process.
    assert.equal(opened(page, g, open('boot-b', true, 2)), true)
    assert.equal(opened(page, g, open('boot-b', true, 0)), false, 'and records it, so the next decides afresh')
  })

  test('a restart is seen even before the turn said anything', () => {
    // Waiting on the provider, the page may hold no event of the turn at all.
    const page = newChatStream()
    const g = follow(page, 'c1', -1)
    opened(page, g, open('boot-a', true, 0))
    assert.equal(opened(page, g, open('boot-b', false, 0)), true)
  })

  test('the same server, the turn running: nothing is gone', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', 12)
    assert.equal(opened(page, g, open('boot-a', true, 13)), false)
    accept(page, g, 20)
    assert.equal(opened(page, g, open('boot-a', true, 21)), false)
  })

  test('nothing running and every event already held: gone - nothing more will ever come', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', 12)
    assert.equal(opened(page, g, open('boot-a', false, 13)), true)
    // A restarted server that has published nothing for the conversation yet, met first by a
    // stream picking up from a transcript the old one served.
    const fresh = newChatStream()
    const f = follow(fresh, 'c1', 12)
    assert.equal(opened(fresh, f, open('boot-b', false, 0)), true)
  })

  test('nothing running but events still to replay: they come, the end of the turn among them', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', 12)
    assert.equal(opened(page, g, open('boot-a', false, 20)), false)
  })

  test('followed from the start, for work just accepted that may not have registered: not gone', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', -1)
    assert.equal(opened(page, g, open('boot-a', false, 0)), false)
  })

  test('the server is remembered while the same conversation is followed on - a resumed or reopened stream', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', -1)
    keepClose(page, g, () => {})
    opened(page, g, open('boot-a', true, 0))
    accept(page, g, 7)
    pause(page, 0)
    const point = resumePoint(page, 1000)!
    const r = follow(page, point.convId, point.afterSeq, { resumed: true })
    assert.equal(opened(page, r, open('boot-b', true, 0)), true, 'restarted while the tab was hidden')
    // Opened again for the same conversation (an inject refused with 409): still the same server.
    const again = newChatStream()
    const a = follow(again, 'c1', -1)
    opened(again, a, open('boot-a', true, 0))
    const b = follow(again, 'c1', again.lastSeq)
    assert.equal(opened(again, b, open('boot-c', true, 0)), true)
  })

  test('forgotten for another conversation, after stopping, and after the stream ended', () => {
    const other = newChatStream()
    opened(other, follow(other, 'c1', -1), open('boot-a', true, 0))
    assert.equal(opened(other, follow(other, 'c2', -1), open('boot-b', true, 0)), false)
    const stopped = newChatStream()
    opened(stopped, follow(stopped, 'c1', -1), open('boot-a', true, 0))
    stop(stopped)
    assert.equal(opened(stopped, follow(stopped, 'c1', -1), open('boot-b', true, 0)), false)
    const done = newChatStream()
    const d = follow(done, 'c1', -1)
    opened(done, d, open('boot-a', true, 0))
    ended(done, d)
    assert.equal(opened(done, follow(done, 'c1', -1), open('boot-b', true, 0)), false)
  })

  test('the first event of a replaced stream decides nothing', () => {
    const page = newChatStream()
    const g = follow(page, 'c1', -1)
    opened(page, g, open('boot-a', true, 0))
    follow(page, 'c1', 5)
    assert.equal(opened(page, g, open('boot-b', false, 0)), false)
    assert.equal(page.boot, 'boot-a')
  })

  test('the open event is read only when whole', () => {
    assert.deepEqual(parseOpen('{"boot":"b1","busy":true,"next":4}'), open('b1', true, 4))
    assert.equal(parseOpen('{"boot":"b1","busy":"yes","next":4}'), null)
    assert.equal(parseOpen('{"boot":"b1","busy":true}'), null)
    assert.equal(parseOpen('not json'), null)
  })
})

describe('what typed meanwhile a reload sends again', () => {
  const queue = [{ clientId: 'q-1', content: 'one' }, { clientId: 'q-2', content: 'two' }, { content: 'three' }]

  test('everything, when what the page followed is gone - no turn will place any of it', () => {
    assert.deepEqual(carriedOnReload(queue, new Set(['q-1']), true), queue)
  })

  test('only what no turn accepted, when events were missed - an accepted one may be in the conversation', () => {
    assert.deepEqual(carriedOnReload(queue, new Set(['q-1']), false).map(q => q.content), ['two', 'three'])
  })
})
