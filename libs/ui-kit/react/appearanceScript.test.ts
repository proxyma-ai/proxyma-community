import { test } from 'node:test'
import assert from 'node:assert/strict'
import { appearanceInitScript, THEME_COLOR } from './appearanceScript.ts'
import { parseAppearance, resolveAppearance } from './appearance.ts'

// A document with just what the script touches: attributes on <html>, one meta, a head.
function run(script: string, stored: string | null, osDark: boolean, opts: { storageThrows?: boolean; noMatchMedia?: boolean } = {}) {
  const attrs = new Map<string, string>()
  let meta: { name: string; content?: string } | null = null
  const documentElement = {
    setAttribute: (n: string, v: string) => { attrs.set(n, v) },
    removeAttribute: (n: string) => { attrs.delete(n) },
  }
  const document = {
    documentElement,
    head: { appendChild: (m: { name: string }) => { meta = m } },
    querySelector: () => meta,
    createElement: () => {
      const m: { name: string; content?: string; setAttribute: (n: string, v: string) => void } = {
        name: '',
        setAttribute(n, v) { if (n === 'name') m.name = v; else m.content = v },
      }
      return m
    },
  }
  const localStorage = { getItem: () => { if (opts.storageThrows) throw new Error('blocked'); return stored } }
  const window = opts.noMatchMedia ? {} : { matchMedia: () => ({ matches: osDark }) }
  new Function('localStorage', 'window', 'document', script)(localStorage, window, document)
  return { mode: attrs.get('data-mode'), shade: attrs.get('data-shade') ?? null, color: (meta as { content?: string } | null)?.content }
}

const STORED = [null, '', 'system', 'light', 'gray', 'black', 'dark', 'neon']

test('the script resolves every stored word and OS setting exactly as appearance.ts does', () => {
  for (const fallback of ['system', 'gray'] as const) {
    const script = appearanceInitScript('proxyma-test', fallback)
    for (const stored of STORED) {
      for (const osDark of [false, true]) {
        const expected = resolveAppearance(parseAppearance(stored, fallback), osDark)
        const got = run(script, stored, osDark)
        assert.equal(got.mode, expected.mode, `${fallback} ${stored} ${osDark}`)
        assert.equal(got.shade, expected.shade, `${fallback} ${stored} ${osDark}`)
      }
    }
  }
})

test('the browser chrome takes the canvas of the resolved appearance', () => {
  const script = appearanceInitScript('k')
  assert.equal(run(script, 'light', true).color, THEME_COLOR.light)
  assert.equal(run(script, 'gray', false).color, THEME_COLOR.gray)
  assert.equal(run(script, 'black', false).color, THEME_COLOR.black)
  assert.equal(run(script, 'system', true).color, THEME_COLOR.black)
})

test('blocked storage and a browser without matchMedia still paint an appearance', () => {
  const script = appearanceInitScript('k')
  assert.deepEqual(run(script, 'light', false, { storageThrows: true }).mode, 'light')
  assert.deepEqual(run(script, null, false, { noMatchMedia: true }).shade, 'black')
})

test('the storage key is quoted, never spliced in raw', () => {
  const script = appearanceInitScript("it's")
  assert.ok(script.includes(JSON.stringify("it's")))
})
