import { test } from 'node:test'
import assert from 'node:assert/strict'
import { OPENROUTER_WAIT_MS, openRouterWaitOver, pickOpenRouterModel } from './openRouterSignIn.ts'

test('the agent model is the newest of the first family listed, never a free one', () => {
  assert.equal(pickOpenRouterModel([
    'openai/gpt-5-mini', 'google/gemini-2.5-flash', 'google/gemini-3.0-flash', 'google/gemini-3.0-flash:free',
    'google/gemini-3.0-flash-lite', 'anthropic/claude-haiku-4.5',
  ]), 'google/gemini-3.0-flash')
  assert.equal(pickOpenRouterModel(['google/gemini-3.0-flash-preview-09', 'anthropic/claude-haiku-4.5']), 'anthropic/claude-haiku-4.5')
  assert.equal(pickOpenRouterModel(['openai/gpt-5-mini', 'openai/gpt-4.1-mini']), 'openai/gpt-5-mini')
  assert.equal(pickOpenRouterModel(['google/gemini-3.0-flash:free']), null)
  assert.equal(pickOpenRouterModel([]), null)
})

test('the wait ends when the sign-in does, or after ten minutes', () => {
  const at = '2026-11-07T08:00:00Z'
  assert.equal(openRouterWaitOver({ state: 'PENDING', providerId: null, at }, 1000), false)
  assert.equal(openRouterWaitOver(undefined, 1000), false)
  assert.equal(openRouterWaitOver({ state: 'SIGNED_IN', providerId: 'p', at }, 1000), true)
  assert.equal(openRouterWaitOver({ state: 'DENIED', providerId: null, at }, 1000), true)
  assert.equal(openRouterWaitOver({ state: 'PENDING', providerId: null, at }, OPENROUTER_WAIT_MS), true)
})
