import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  GUARDRAIL_NOTE_ORDER, guardrailNotes, isWithheldCall, sameWithheld, withheldByAnswer, withheldCategories, withheldSources,
} from './guardrailNotes.ts'
import type { ChatMessage, ToolCallInfo } from '../types/chat.types'

// English passes through with its {placeholders} filled, as the runtime does for a key the catalog
// lacks - so these assert the English a reader sees.
const t = (s: string, p?: Record<string, string | number>) =>
  s.replace(/\{(\w+)\}/g, (_, k) => String(p?.[k] ?? `{${k}}`))

// GuardrailService.withheldToolResult's shape, today and before the `categories` list.
const withheld = (categories?: string[]) => JSON.stringify({
  success: false,
  guardrailBlocked: true,
  ...(categories ? { categories } : {}),
  error: 'This result was withheld by the configured guardrails (prompt-injection). The content is not available.',
})
const SENTENCE_ONLY = withheld()
const call = (result: string | undefined, done = true): ToolCallInfo =>
  ({ callId: Math.random().toString(36), toolName: 'searchIndexedSources', arguments: '{}', result, done })
const user = (content: string): ChatMessage => ({ role: 'user', content })
const titles = (...args: Parameters<typeof guardrailNotes>) => guardrailNotes(...args).map(n => n.title)
const answer = (content: string, toolCalls?: ToolCallInfo[], extra: Partial<ChatMessage> = {}): ChatMessage =>
  ({ role: 'assistant', content, toolCalls, ...extra })

test('a withheld result names its categories from the list the server sends', () => {
  assert.deepEqual(withheldCategories(withheld(['prompt-injection', 'secret'])), ['prompt-injection', 'secret'])
})

test('a withheld result stored before the list names them from its sentence', () => {
  assert.deepEqual(withheldCategories(SENTENCE_ONLY), ['prompt-injection'])
  const two = JSON.stringify({ success: false, guardrailBlocked: true,
    error: 'This result was withheld by the configured guardrails (pii, secret). The content is not available.' })
  assert.deepEqual(withheldCategories(two), ['pii', 'secret'])
})

test('a withheld result with no category is still withheld, with none to name', () => {
  assert.deepEqual(withheldCategories(withheld([])), [])
  const bare = JSON.stringify({ success: false, guardrailBlocked: true,
    error: 'This result was withheld by the configured guardrails. The content is not available.' })
  assert.deepEqual(withheldCategories(bare), [])
  // A list wins over the sentence, and what is not a string in it is ignored.
  assert.deepEqual(withheldCategories(JSON.stringify({ guardrailBlocked: true, categories: [3, null, 'pii'],
    error: 'withheld by the configured guardrails (secret)' })), ['pii'])
})

test('anything else is not withheld: a real result, another failure, malformed or not JSON', () => {
  assert.equal(withheldCategories(JSON.stringify({ results: [] })), null)
  assert.equal(withheldCategories(JSON.stringify({ success: false, error: 'Not found' })), null)
  assert.equal(withheldCategories(JSON.stringify({ success: false, guardrailBlocked: 'true' })), null)
  assert.equal(withheldCategories('{"success":false,"guardrailBlocked":true'), null)
  assert.equal(withheldCategories('This result was withheld by the configured guardrails (secret).'), null)
  assert.equal(withheldCategories('[{"guardrailBlocked":true}]'), null)
  assert.equal(withheldCategories('null'), null)
  assert.equal(withheldCategories(''), null)
  assert.equal(withheldCategories(undefined), null)
})

test('a call is withheld only once it has its result', () => {
  assert.equal(isWithheldCall(call(withheld(['pii']))), true)
  assert.equal(isWithheldCall(call(withheld(['pii']), false)), false)
  assert.equal(isWithheldCall(call('{"results":[]}')), false)
})

test('several withheld results in one turn are counted, their categories merged once each', () => {
  const held = withheldSources([
    call(withheld(['prompt-injection'])),
    call('{"results":[]}'),
    call(withheld(['secret', 'prompt-injection'])),
    call(SENTENCE_ONLY),
    call(withheld(['pii']), false),
  ])
  assert.deepEqual(held, { count: 3, categories: ['prompt-injection', 'secret'] })
  assert.equal(withheldSources([call('{"results":[]}')]), null)
  assert.equal(withheldSources([]), null)
})

test('the note goes once, on the turn\'s final answer, never on the narration before it', () => {
  const messages = [
    user('What changed?'),
    answer('Let me look.', [call(withheld(['prompt-injection']))]),
    answer('Here is what changed.', [call(withheld(['secret']))]),
  ]
  const notes = withheldByAnswer(messages, messages.length, false)
  assert.deepEqual([...notes.keys()], [2])
  assert.deepEqual(notes.get(2), { count: 2, categories: ['prompt-injection', 'secret'] })
})

test('a turn still running has no note yet; one that ended does, each turn its own', () => {
  const messages = [
    user('one'), answer('First.', [call(withheld(['pii']))]),
    user('two'), answer('Working on it', [call(withheld(['secret']))]),
  ]
  assert.deepEqual([...withheldByAnswer(messages, messages.length, true)], [[1, { count: 1, categories: ['pii'] }]])
  assert.deepEqual([...withheldByAnswer(messages, messages.length, false).keys()], [1, 3])
})

test('a turn ending in steps with no text puts its note on the last bubble it drew', () => {
  const messages = [
    user('q'), answer('Looking.', [call('{"results":[]}')]), answer('', [call(withheld(['pii']))]),
  ]
  assert.deepEqual([...withheldByAnswer(messages, messages.length, false).keys()], [1])
  // ...and a turn that drew none has nowhere to put one.
  assert.equal(withheldByAnswer([user('q'), answer('', [call(withheld(['pii']))])], 2, false).size, 0)
})

test('a turn with nothing withheld has no note, and a queued message is not a turn of its own yet', () => {
  const messages = [user('q'), answer('Fine.', [call('{"results":[]}')]), { ...user('later'), queued: true }]
  assert.equal(withheldByAnswer(messages, 2, false).size, 0)
  const held = [user('q'), answer('Done.', [call(withheld(['pii']))]), { ...user('later'), queued: true }]
  assert.deepEqual([...withheldByAnswer(held, 2, false).keys()], [1])
})

test('the same note compares equal however often it is rebuilt', () => {
  assert.equal(sameWithheld({ count: 1, categories: ['pii'] }, { count: 1, categories: ['pii'] }), true)
  assert.equal(sameWithheld(undefined, undefined), true)
  assert.equal(sameWithheld({ count: 1, categories: ['pii'] }, { count: 2, categories: ['pii'] }), false)
  assert.equal(sameWithheld({ count: 1, categories: ['pii'] }, { count: 1, categories: ['secret'] }), false)
  assert.equal(sameWithheld({ count: 1, categories: [] }, undefined), false)
})

test('one source held back, and several, in the words a redaction uses for its reasons', () => {
  assert.deepEqual(titles(undefined, { count: 1, categories: ['prompt-injection'] }, t),
    ['A source was held back by a guardrail: prompt injection'])
  assert.deepEqual(titles(undefined, { count: 3, categories: ['prompt-injection', 'secret'] }, t),
    ['Some sources were held back by guardrails: prompt injection, credentials'])
  assert.deepEqual(titles(undefined, { count: 1, categories: [] }, t), ['A source was held back by a guardrail'])
  assert.deepEqual(titles(undefined, { count: 2, categories: [] }, t), ['Some sources were held back by guardrails'])
  assert.deepEqual(titles(undefined, undefined, t), [])
})

test('a redacted answer that also had a source held back gets both notes, the redaction first', () => {
  assert.deepEqual(titles(['secret'], { count: 1, categories: ['prompt-injection'] }, t), [
    'Redacted by a guardrail: credentials',
    'A source was held back by a guardrail: prompt injection',
  ])
  assert.deepEqual(titles([], undefined, t), ['Redacted by a guardrail'])
  assert.deepEqual(guardrailNotes(['secret'], { count: 2, categories: [] }, t).map(n => n.kind), [...GUARDRAIL_NOTE_ORDER])
  assert.deepEqual([...GUARDRAIL_NOTE_ORDER], ['redacted', 'withheld'])
})

test("the reasons are words, never the server's codes, and an unknown one still reads", () => {
  assert.deepEqual(titles(['secret', 'server-path', 'pii'], undefined, t),
    ['Redacted by a guardrail: credentials, server file paths, personal data'])
  assert.deepEqual(titles(undefined, { count: 1, categories: ['self-harm', 'Data Handling Policy'] }, t),
    ['A source was held back by a guardrail: self harm, Data Handling Policy'])
})

test('the reasons are translated with the sentence', () => {
  const vi: Record<string, string> = {
    'Redacted by a guardrail: {reasons}': 'Da duoc rao chan che bot: {reasons}',
    'Credentials': 'Thong tin dang nhap',
    'Prompt injection': 'Chen chi dan',
  }
  const tv = (s: string, p?: Record<string, string | number>) =>
    (vi[s] ?? s).replace(/\{(\w+)\}/g, (_, k) => String(p?.[k] ?? `{${k}}`))
  assert.deepEqual(titles(['secret', 'prompt-injection'], undefined, tv),
    ['Da duoc rao chan che bot: thong tin dang nhap, chen chi dan'])
})
