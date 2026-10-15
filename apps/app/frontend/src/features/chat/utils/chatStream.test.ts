// Run: node --test apps/app/frontend/src/features/chat/utils/chatStream.test.ts
//
// The page's one chat stream. The hook around it needs EventSource and React; which stream is
// followed, and which events are handled, is decided here.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { accept, ended, follow, keepClose, newChatStream, resumeAfter, stop } from './chatStream.ts'

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
    // A send while sub-agents work in the background: the server kept the sink, and replaying it
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
