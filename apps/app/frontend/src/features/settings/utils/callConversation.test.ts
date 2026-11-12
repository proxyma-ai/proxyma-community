import { test } from 'node:test'
import assert from 'node:assert/strict'
import { conversationOptions, triggerLabel } from './callConversation.ts'

const groups = [
  { conversationId: 'aaaaaaaa-1111', agent: 'YouTube / Lead' },
  { conversationId: 'bbbbbbbb-2222', agent: null },
  { conversationId: 'aaaaaaaa-1111', agent: 'YouTube / Lead' },
]

test('each conversation of the period is offered once, an unnamed one by its short id', () => {
  const options = conversationOptions(groups, '')
  assert.deepEqual(options, [
    { value: 'aaaaaaaa-1111', label: 'YouTube / Lead', shortId: 'aaaaaaaa' },
    { value: 'bbbbbbbb-2222', label: null, shortId: 'bbbbbbbb' },
  ])
})

test('a conversation chosen from a row that the list does not hold comes first, named as the row named it', () => {
  const options = conversationOptions(groups, 'cccccccc-3333', 'YouTube / Reply drafter')
  assert.equal(options.length, 3)
  assert.deepEqual(options[0], { value: 'cccccccc-3333', label: 'YouTube / Reply drafter', shortId: 'cccccccc' })
})

test('a chosen conversation already in the list is not offered twice', () => {
  assert.equal(conversationOptions(groups, 'aaaaaaaa-1111', 'x').length, 2)
})

test('a trigger reads in words, an unknown one as it came, none as nothing', () => {
  const t = (s: string) => s
  assert.equal(triggerLabel('schedule', t), 'a schedule')
  assert.equal(triggerLabel('note', t), 'a note from the server')
  assert.equal(triggerLabel('something new', t), 'something new')
  assert.equal(triggerLabel(null, t), null)
})
