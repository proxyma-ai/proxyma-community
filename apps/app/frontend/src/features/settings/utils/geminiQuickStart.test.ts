import { test } from 'node:test'
import assert from 'node:assert/strict'
import { offersGeminiQuickStart, pickGeminiModels } from './geminiQuickStart.ts'

// A model list shaped like Google's own (names as GeminiAdapter returns them, "models/" removed).
const GOOGLE = [
  'aqa', 'embedding-001', 'gemini-2.0-flash', 'gemini-2.0-flash-001', 'gemini-2.0-flash-lite',
  'gemini-2.5-flash', 'gemini-2.5-flash-image', 'gemini-2.5-flash-lite', 'gemini-2.5-flash-preview-tts',
  'gemini-2.5-pro', 'gemini-embedding-001', 'gemini-embedding-exp-03-07', 'gemini-flash-latest',
  'gemini-live-2.5-flash-preview', 'gemma-3-27b-it', 'imagen-4.0-generate-001', 'learnlm-2.0-flash-experimental',
  'text-embedding-004', 'veo-3.0-generate-001',
]

test('the newest stable Flash answers, and gemini-embedding indexes', () => {
  assert.deepEqual(pickGeminiModels(GOOGLE), { chat: 'gemini-2.5-flash', embedding: 'gemini-embedding-001' })
})

test('a newer stable Flash wins over an older one; a preview of it does not', () => {
  const list = [...GOOGLE, 'gemini-3-flash', 'gemini-3.5-flash-preview-09-2027', 'gemini-embedding-002']
  assert.deepEqual(pickGeminiModels(list), { chat: 'gemini-3-flash', embedding: 'gemini-embedding-002' })
})

test('with no stable Flash, a preview Flash is better than nothing', () => {
  assert.equal(pickGeminiModels(['gemini-3-flash-preview', 'gemini-3-pro-preview']).chat, 'gemini-3-flash-preview')
})

test('with no Flash at all, another Gemini chat model; never an image, speech or embedding model', () => {
  assert.equal(pickGeminiModels(['gemini-2.5-pro', 'gemini-2.5-flash-image', 'gemini-embedding-001']).chat, 'gemini-2.5-pro')
  assert.equal(pickGeminiModels(['gemini-2.5-flash-image', 'gemini-2.5-flash-preview-tts']).chat, null)
})

test('the "models/" prefix Google may send is read past', () => {
  assert.deepEqual(pickGeminiModels(['models/gemini-2.5-flash', 'models/gemini-embedding-001']),
    { chat: 'gemini-2.5-flash', embedding: 'gemini-embedding-001' })
})

test('older embedding names are a fallback, the newest first; an experiment is never picked', () => {
  assert.equal(pickGeminiModels(['text-embedding-004', 'embedding-001', 'gemini-embedding-exp-03-07']).embedding, 'text-embedding-004')
  assert.equal(pickGeminiModels(['gemini-embedding-exp-03-07']).embedding, null)
})

test('an empty list is two nulls, which the dialog reports', () => {
  assert.deepEqual(pickGeminiModels([]), { chat: null, embedding: null })
})

test('the guide is offered on a single-user install with no provider, once the list has loaded', () => {
  assert.equal(offersGeminiQuickStart({ multiUser: false, providersLoaded: true, providerCount: 0 }), true)
  assert.equal(offersGeminiQuickStart({ multiUser: false, providersLoaded: false, providerCount: 0 }), false)
  assert.equal(offersGeminiQuickStart({ multiUser: false, providersLoaded: true, providerCount: 1 }), false)
  assert.equal(offersGeminiQuickStart({ multiUser: true, providersLoaded: true, providerCount: 0 }), false)
})
