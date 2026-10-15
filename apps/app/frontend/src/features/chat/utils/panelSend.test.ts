// Run: node --test apps/app/frontend/src/features/chat/utils/panelSend.test.ts
//
// A chat panel beside a page (the key-account panel): its skill goes with every message, and the
// page binds a conversation the panel starts BEFORE its first message is sent.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { bindsBeforeSend, withPanelSkills } from './panelSend.ts'

describe('the panel\'s skill goes with every message', () => {
  test('the user\'s picks first, then the panel\'s own', () => {
    assert.deepEqual(withPanelSkills(['Summarize'], ['Key account review']), ['Summarize', 'Key account review'])
  })

  test('a skill picked twice is sent once, whatever its case', () => {
    assert.deepEqual(withPanelSkills(['key account review'], ['Key account review']), ['key account review'])
  })

  test('nothing picked and no skill of its own is no list at all', () => {
    assert.equal(withPanelSkills(undefined, undefined), undefined)
    assert.equal(withPanelSkills([], []), undefined)
  })
})

describe('a conversation the panel starts is bound before its first message', () => {
  const fresh = {
    pageConversationId: undefined, pageBinds: true, messageCount: 0, streaming: false,
    conversationId: 'c1', announcedId: null,
  }

  test('the first message of a conversation the panel minted waits for the page', () => {
    assert.equal(bindsBeforeSend(fresh), true)
  })

  test('not when the page named the conversation - it bound it itself', () => {
    assert.equal(bindsBeforeSend({ ...fresh, pageConversationId: 'c1' }), false)
  })

  test('not when the page does not ask', () => {
    assert.equal(bindsBeforeSend({ ...fresh, pageBinds: false }), false)
  })

  test('not once anything has been said, or while a turn runs', () => {
    assert.equal(bindsBeforeSend({ ...fresh, messageCount: 2 }), false)
    assert.equal(bindsBeforeSend({ ...fresh, streaming: true }), false)
  })

  test('once per id: a send after the binding does not bind again, a new id does', () => {
    assert.equal(bindsBeforeSend({ ...fresh, announcedId: 'c1' }), false)
    assert.equal(bindsBeforeSend({ ...fresh, announcedId: 'c0' }), true)
  })
})
