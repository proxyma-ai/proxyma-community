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
  for (const p of APPEARANCE_PREFERENCES) assert.equal(parseAppearance(p, 'gray'), p)
})

test('the old two-way values migrate: dark is Gray, light stays Light', () => {
  assert.equal(parseAppearance('dark', 'system'), 'gray')
  assert.equal(parseAppearance('light', 'system'), 'light')
})

test('nothing stored, or something unreadable, is the fallback', () => {
  assert.equal(parseAppearance(null, 'gray'), 'gray')
  assert.equal(parseAppearance(undefined, 'system'), 'system')
  assert.equal(parseAppearance('', 'gray'), 'gray')
  assert.equal(parseAppearance('Dark', 'gray'), 'gray')
  assert.equal(parseAppearance('sepia', 'light'), 'light')
})

test('explicit choices ignore the OS', () => {
  for (const dark of [true, false]) {
    assert.deepEqual(resolveAppearance('light', dark), { mode: 'light', shade: null })
    assert.deepEqual(resolveAppearance('gray', dark), { mode: 'dark', shade: null })
    assert.deepEqual(resolveAppearance('black', dark), { mode: 'dark', shade: 'black' })
  }
})

test('System follows the OS: light to Light, dark to Dark (black) and never Proxyma', () => {
  assert.deepEqual(resolveAppearance('system', false), { mode: 'light', shade: null })
  assert.deepEqual(resolveAppearance('system', true), { mode: 'dark', shade: 'black' })
})

test('the menu order is System, Light, Dark, Proxyma', () => {
  assert.deepEqual([...APPEARANCE_PREFERENCES], ['system', 'light', 'black', 'gray'])
})

test('applying stamps data-mode and adds or removes data-shade', () => {
  const attrs = new Map<string, string>()
  const el = {
    setAttribute: (n: string, v: string) => { attrs.set(n, v) },
    removeAttribute: (n: string) => { attrs.delete(n) },
  }

  applyAppearance(el, resolveAppearance('black', false))
  assert.equal(attrs.get('data-mode'), 'dark')
  assert.equal(attrs.get('data-shade'), 'black')

  // Leaving Black must clear the shade, or Gray would keep painting black.
  applyAppearance(el, resolveAppearance('gray', false))
  assert.equal(attrs.get('data-mode'), 'dark')
  assert.equal(attrs.has('data-shade'), false)

  applyAppearance(el, resolveAppearance('light', true))
  assert.equal(attrs.get('data-mode'), 'light')
  assert.equal(attrs.has('data-shade'), false)
})

test('the chrome colour of each appearance is its canvas, as the pre-paint script writes it', async () => {
  const { THEME_COLOR } = await import('./appearanceScript.ts')
  assert.equal(themeColor(resolveAppearance('light', true)), THEME_COLOR.light)
  assert.equal(themeColor(resolveAppearance('gray', false)), THEME_COLOR.gray)
  assert.equal(themeColor(resolveAppearance('black', false)), THEME_COLOR.black)
})
