import { test } from 'node:test'
import assert from 'node:assert/strict'
import { conversationModel, modelWords } from './conversationModel.ts'

const chat = { provider: 'Anthropic', model: 'claude-sonnet-5-5' }

test("a conversation on an agent's own model names that model, never the chat's", () => {
  assert.deepEqual(conversationModel(chat, { model: 'claude-haiku-5-5', providerName: 'Anthropic' }),
    { provider: 'Anthropic', model: 'claude-haiku-5-5', own: true })
})

test('an ordinary conversation names the chat settings\' model', () => {
  assert.equal(conversationModel(chat, {}), chat)
  assert.equal(conversationModel(chat, undefined), chat)
  assert.equal(conversationModel(chat, { model: '  ' }), chat)
  assert.equal(conversationModel(null, null), null)
})

test("the agent's model is named to a reader who may not read the chat settings", () => {
  assert.deepEqual(conversationModel(null, { model: 'small' }), { model: 'small', own: true })
})

test('a provider the server could not name is left out', () => {
  assert.deepEqual(conversationModel(chat, { model: 'small', providerName: null }), { model: 'small', own: true })
  assert.equal(modelWords({ model: 'small' }), 'small')
  assert.equal(modelWords({ provider: 'Anthropic', model: 'small' }), 'Anthropic / small')
  assert.equal(modelWords({ provider: 'Anthropic', model: 'small' }, ' - '), 'Anthropic - small')
})
