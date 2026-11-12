import { test } from 'node:test'
import assert from 'node:assert/strict'
import { EDITION_MENTIONS_SHOWN, editionMentionNamed, matchEditionMentions } from './editionMentions.ts'

const projects = [
  { name: 'Release readiness' },
  { name: 'Channel care' },
  { name: 'Pre-release checks' },
  { name: 'release notes' },
]

test('what was typed after the @ narrows the list, ignoring case, names starting with it first', () => {
  assert.deepEqual(matchEditionMentions(projects, 'rel').map(p => p.name),
    ['release notes', 'Release readiness', 'Pre-release checks'])
  assert.deepEqual(matchEditionMentions(projects, 'CARE').map(p => p.name), ['Channel care'])
  assert.deepEqual(matchEditionMentions(projects, 'nothing like it'), [])
})

test('an empty query lists them in name order, and never more than the list shows', () => {
  assert.deepEqual(matchEditionMentions(projects, '').map(p => p.name),
    ['Channel care', 'Pre-release checks', 'release notes', 'Release readiness'])
  const many = Array.from({ length: 20 }, (_, i) => ({ name: `Project ${String(i).padStart(2, '0')}` }))
  assert.equal(matchEditionMentions(many, '').length, EDITION_MENTIONS_SHOWN)
  assert.equal(matchEditionMentions(many, '', 2).length, 2)
})

test('a chip finds its mention by its exact name only', () => {
  assert.equal(editionMentionNamed(projects, 'Channel care')?.name, 'Channel care')
  assert.equal(editionMentionNamed(projects, 'channel care'), undefined)
  assert.equal(editionMentionNamed([], 'Channel care'), undefined)
})
