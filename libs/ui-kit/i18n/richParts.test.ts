import { test } from 'node:test'
import assert from 'node:assert/strict'
import { richParts } from './richParts.ts'

test('a sentence splits around its slots, in the order the translation put them', () => {
  assert.deepEqual(richParts('Write to {mail}, or read the {manual} first.', ['mail', 'manual']), [
    { text: 'Write to ' }, { slot: 'mail' }, { text: ', or read the ' }, { slot: 'manual' }, { text: ' first.' },
  ])
  assert.deepEqual(richParts('{manual} truoc, hoac viet cho {mail}.', ['mail', 'manual']), [
    { slot: 'manual' }, { text: ' truoc, hoac viet cho ' }, { slot: 'mail' }, { text: '.' },
  ])
})

test('a placeholder with no part stays as text, and a sentence without slots is one piece', () => {
  assert.deepEqual(richParts('Keep {count} of {mail}', ['mail']), [{ text: 'Keep {count} of ' }, { slot: 'mail' }])
  assert.deepEqual(richParts('Plain.', ['mail']), [{ text: 'Plain.' }])
})
