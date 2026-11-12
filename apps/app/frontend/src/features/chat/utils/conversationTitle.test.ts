import { test } from 'node:test'
import assert from 'node:assert/strict'
import { conversationTitle } from './conversationTitle.ts'

// A marker around every string that went through the translation function, so a test can tell a
// translated sentence from English pasted around it.
const t = (s: string, p?: Record<string, string | number>) =>
  `<${s.replace(/\{(\w+)\}/g, (_, k) => String(p?.[k] ?? `{${k}}`))}>`

test("a person's conversation is titled with its own words", () => {
  assert.equal(conversationTitle({ title: 'Kafka retention' }, t), 'Kafka retention')
  assert.equal(conversationTitle({ title: 'Kafka retention', titleKind: null }, t), 'Kafka retention')
  assert.equal(conversationTitle({ title: '  ' }, t), '<New conversation>')
})

test("a run is worded whole, in the reader's language, with its task as the data", () => {
  assert.equal(conversationTitle({ title: 'Get the new comments handled.', titleKind: 'run_schedule' }, t),
    '<Scheduled run: Get the new comments handled.>')
  assert.equal(conversationTitle({ title: 'Go', titleKind: 'run_webhook' }, t), '<Run started by a webhook: Go>')
  assert.equal(conversationTitle({ title: 'Go', titleKind: 'run_source' }, t), '<Run started by new documents: Go>')
  assert.equal(conversationTitle({ title: 'Go', titleKind: 'run_member' }, t), '<Run started by a project member: Go>')
  assert.equal(conversationTitle({ title: null, titleKind: 'run_schedule' }, t), '<Scheduled run>')
})

test("the server's notes and a teammate's message read as their mail lines", () => {
  assert.equal(conversationTitle({ title: null, titleKind: 'lead_note' }, t), "<Update on the team's work>")
  assert.equal(conversationTitle({ title: null, titleKind: 'learning' }, t), '<Learning from your decisions>')
  assert.equal(conversationTitle({ title: null, titleKind: 'revising' }, t), '<Revising the drafts waiting for approval>')
  assert.equal(conversationTitle({ title: 'Ana (Writer)', titleKind: 'message' }, t), '<Message from Ana (Writer)>')
  assert.equal(conversationTitle({ title: null, titleKind: 'message' }, t), '<Message from an agent>')
})

test('a kind this page does not know shows the title as sent', () => {
  assert.equal(conversationTitle({ title: 'Something', titleKind: 'from_the_future' }, t), 'Something')
  assert.equal(conversationTitle({ title: null, titleKind: 'from_the_future' }, t), '<New conversation>')
})
