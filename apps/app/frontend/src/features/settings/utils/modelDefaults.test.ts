import { test } from 'node:test'
import assert from 'node:assert/strict'
import { tokenCountLabel, unsetLimit } from './modelDefaults.ts'

test('a token count reads as every vendor writes it', () => {
  // Powers of two and round thousands are the same words.
  assert.equal(tokenCountLabel(8192), '8k')
  assert.equal(tokenCountLabel(64000), '64k')
  assert.equal(tokenCountLabel(65536), '64k')
  assert.equal(tokenCountLabel(131072), '128k')
  assert.equal(tokenCountLabel(200000), '200k')
  assert.equal(tokenCountLabel(262144), '256k')
  assert.equal(tokenCountLabel(1000000), '1M')
  assert.equal(tokenCountLabel(1048576), '1M')
  assert.equal(tokenCountLabel(2000000), '2M')
  // Neither: one decimal, and no more.
  assert.equal(tokenCountLabel(4500), '4.5k')
  assert.equal(tokenCountLabel(512), '512')
  assert.equal(tokenCountLabel(0), '')
})

test('nothing set: the number the provider states for the model', () => {
  assert.deepEqual(unsetLimit('output', 64000), { words: 'model', amount: '64k' })
  assert.deepEqual(unsetLimit('context', 1048576), { words: 'model', amount: '1M' })
})

test('nothing set and nothing stated: the assumed window is named, and no output ceiling is invented', () => {
  assert.deepEqual(unsetLimit('context', null), { words: 'default', amount: '200k' })
  // No ceiling is sent then, so there is no number to show.
  assert.deepEqual(unsetLimit('output', null), { words: 'provider' })
  assert.deepEqual(unsetLimit('output', undefined), { words: 'provider' })
  assert.deepEqual(unsetLimit('output', 0), { words: 'provider' })
})

test('a deployed agent takes the agent\'s setting where the agent has one, else its own model\'s', () => {
  // The agent set 32k: that is the deployed agents' too, whatever their model states.
  assert.deepEqual(unsetLimit('output', 8192, '32768'), { words: 'same', amount: '32k' })
  // The agent follows its model, so each deployed agent follows ITS model.
  assert.deepEqual(unsetLimit('output', 8192, ''), { words: 'model', amount: '8k' })
  assert.deepEqual(unsetLimit('context', null, ''), { words: 'default', amount: '200k' })
  // A stored value that is not a number is not a setting.
  assert.deepEqual(unsetLimit('context', 32768, '0'), { words: 'model', amount: '32k' })
})
