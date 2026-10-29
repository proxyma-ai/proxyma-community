import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  APPEARANCE_PREFERENCES,
  applyAppearance,
  parseAppearance,
  resolveAppearance,
  themeColor,
} from './appearance.ts'

// Run with `node --test libs/ui-kit/react/appearance.test.ts` (Node 22.6+ strips the types). Not
// shipped by sync.mjs - it belongs beside its source, like translate.test.ts.

test('every current preference reads back as itself', () => {
  for (const p of APPEARANCE_PREFERENCES) assert.equal(parseAppearance(p, 'light'), p)
})

test('every word an older build stored for a dark page is Dark', () => {
  // `dark` the two-way flip's, `black` Dark's 0.13.0 name, `gray` the removed Proxyma appearance.
  for (const old of ['dark', 'black', 'gray']) assert.equal(parseAppearance(old, 'system'), 'dark')
  assert.equal(parseAppearance('light', 'system'), 'light')
})

test('nothing stored, or something unreadable, is the fallback', () => {
  assert.equal(parseAppearance(null, 'dark'), 'dark')
  assert.equal(parseAppearance(undefined, 'system'), 'system')
  assert.equal(parseAppearance('', 'light'), 'light')
  assert.equal(parseAppearance('Dark', 'system'), 'system')
  assert.equal(parseAppearance('sepia', 'light'), 'light')
})

test('explicit choices ignore the OS', () => {
  for (const dark of [true, false]) {
    assert.deepEqual(resolveAppearance('light', dark), { mode: 'light' })
    assert.deepEqual(resolveAppearance('dark', dark), { mode: 'dark' })
  }
})

test('System follows the OS', () => {
  assert.deepEqual(resolveAppearance('system', false), { mode: 'light' })
  assert.deepEqual(resolveAppearance('system', true), { mode: 'dark' })
})

test('the menu order is System, Light, Dark - three choices', () => {
  assert.deepEqual([...APPEARANCE_PREFERENCES], ['system', 'light', 'dark'])
})

test('applying stamps data-mode and clears the data-shade an older build left', () => {
  const attrs = new Map<string, string>([['data-shade', 'black']])
  const el = {
    setAttribute: (n: string, v: string) => { attrs.set(n, v) },
    removeAttribute: (n: string) => { attrs.delete(n) },
  }

  applyAppearance(el, resolveAppearance('dark', false))
  assert.equal(attrs.get('data-mode'), 'dark')
  assert.equal(attrs.has('data-shade'), false)

  applyAppearance(el, resolveAppearance('light', true))
  assert.equal(attrs.get('data-mode'), 'light')
  assert.equal(attrs.has('data-shade'), false)
})

test('the chrome colour of each appearance is its canvas, as the pre-paint script writes it', async () => {
  const { THEME_COLOR } = await import('./appearanceScript.ts')
  assert.equal(themeColor(resolveAppearance('light', true)), THEME_COLOR.light)
  assert.equal(themeColor(resolveAppearance('dark', false)), THEME_COLOR.dark)
})
