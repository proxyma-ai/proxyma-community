import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  backInTrail, backTarget, deployedAgentRedirect, mapPick, openInTrail, shownInTrail, threadAffordances,
} from './agentTranscript.ts'

test('a deployed agent opens as the first step of a closed dialog', () => {
  assert.deepEqual(openInTrail([], 'c-a'), ['c-a'])
  assert.equal(shownInTrail(['c-a']), 'c-a')
  assert.equal(shownInTrail([]), undefined)
})

test('its own agents open one step deeper in the same dialog, never a dialog per level', () => {
  let trail = openInTrail([], 'c-a')
  trail = openInTrail(trail, 'c-a1')
  trail = openInTrail(trail, 'c-a1x')
  assert.deepEqual(trail, ['c-a', 'c-a1', 'c-a1x'])
  assert.equal(shownInTrail(trail), 'c-a1x')
})

test('opening the one shown changes nothing; opening one walked through goes back to it', () => {
  const trail = ['c-a', 'c-a1', 'c-a1x']
  assert.deepEqual(openInTrail(trail, 'c-a1x'), trail)
  assert.deepEqual(openInTrail(trail, 'c-a'), ['c-a'])
  assert.deepEqual(openInTrail(trail, ''), trail)
})

test('Back goes one step up, is offered only past the first step, and closes after it', () => {
  const trail = ['c-a', 'c-a1']
  assert.equal(backTarget(trail), 'c-a')
  assert.deepEqual(backInTrail(trail), ['c-a'])
  assert.equal(backTarget(['c-a']), undefined)
  assert.deepEqual(backInTrail(['c-a']), [])
})

test('in the agents map a deployed agent opens its transcript, never its conversation', () => {
  assert.deepEqual(mapPick({ target: 'c-a', openConversationId: 'root', rootConversationId: 'root' }),
    { kind: 'transcript', conversationId: 'c-a' })
  // Even one the page somehow has open - it is read, not talked to.
  assert.deepEqual(mapPick({ target: 'c-a', openConversationId: 'c-a', rootConversationId: 'root' }),
    { kind: 'transcript', conversationId: 'c-a' })
})

test('the main agent goes to its latest turn when it is open, else to its conversation', () => {
  assert.deepEqual(mapPick({ target: 'root', openConversationId: 'root', rootConversationId: 'root' }), { kind: 'reveal' })
  assert.deepEqual(mapPick({ target: 'root', openConversationId: 'c-a', rootConversationId: 'root' }),
    { kind: 'navigate', to: 'root' })
})

test('a link to a deployed agent goes to its root with the transcript over it', () => {
  assert.deepEqual(deployedAgentRedirect({ conversationId: 'c-a', self: { conversationId: 'c-a' }, rootConversationId: 'root' }),
    { to: 'root', transcript: 'c-a' })
})

test('any other conversation, or one the tree has not answered for yet, stays where it is', () => {
  assert.equal(deployedAgentRedirect({ conversationId: 'root', self: null, rootConversationId: 'root' }), null)
  assert.equal(deployedAgentRedirect({ conversationId: 'c-a', self: undefined, rootConversationId: undefined }), null)
  assert.equal(deployedAgentRedirect({ conversationId: undefined, self: { conversationId: 'c-a' }, rootConversationId: 'root' }), null)
  // The tree of the conversation loaded before - not this one's.
  assert.equal(deployedAgentRedirect({ conversationId: 'c-b', self: { conversationId: 'c-a' }, rootConversationId: 'root' }), null)
  assert.equal(deployedAgentRedirect({ conversationId: 'c-a', self: { conversationId: 'c-a' }, rootConversationId: 'c-a' }), null)
})

test('a transcript is read-only - no message actions or questions - and still decides a write it waits on', () => {
  assert.deepEqual(threadAffordances('transcript'), { messageActions: false, questions: false, approvals: true, agentControls: true })
  assert.deepEqual(threadAffordances('chat'), { messageActions: true, questions: true, approvals: true, agentControls: true })
  assert.deepEqual(threadAffordances('shared'), { messageActions: false, questions: false, approvals: false, agentControls: false })
})
