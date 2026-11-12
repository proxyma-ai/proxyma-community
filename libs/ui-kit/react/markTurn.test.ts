import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { IDLE_DEG_PER_S } from './markTurn.ts'

// ONE SPEED FOR EVERY MARK (the maintainer, 2026-11-08: "Ensure the logo rotates at the same speed
// everywhere"). The token is the one statement of it; everything that turns a mark another way is
// held to it here - the frame loop's constant, the Tailwind utility, the kit class and the desktop
// launcher's Java splash.
const here = dirname(fileURLToPath(import.meta.url))
const repo = join(here, '..', '..', '..')
const read = (...p: string[]) => readFileSync(join(repo, ...p), 'utf8')

function tokenSeconds(): number {
  const m = read('libs', 'ui-kit', 'tokens', 'proxyma-tokens.css').match(/--mark-turn:\s*([\d.]+)s;/)
  assert.ok(m, '--mark-turn is declared in seconds in proxyma-tokens.css')
  return Number(m[1])
}

test('the token is one turn in twelve seconds', () => {
  assert.equal(tokenSeconds(), 12)
})

test('the frame loop turns at the token', () => {
  assert.equal(360 / IDLE_DEG_PER_S, tokenSeconds())
})

test('the kit class and the Tailwind utility read the token, with no duration of their own', () => {
  const primitives = read('libs', 'ui-kit', 'tokens', 'proxyma-primitives.css')
  assert.match(primitives, /\.mark-turn\s*\{[^}]*animation:\s*mark-turn var\(--mark-turn\) linear infinite;/)
  const preset = read('libs', 'ui-kit', 'tokens', 'tailwind-preset.ts')
  assert.match(preset, /'mark-idle':\s*'spin var\(--mark-turn\) linear infinite'/)
})

test("the desktop launcher's splash turns at the token", () => {
  const java = read('apps', 'app', 'backend', 'proxyma-desktop', 'src', 'main', 'java', 'ai', 'proxyma', 'desktop', 'launcher', 'SplashWindow.java')
  const m = java.match(/MARK_DEG_PER_MS\s*=\s*([\d._]+)\s*\/\s*([\d._]+)\s*;/)
  assert.ok(m, 'SplashWindow.MARK_DEG_PER_MS is a quotient of two literals')
  const degPerMs = Number(m[1].replace(/_/g, '')) / Number(m[2].replace(/_/g, ''))
  assert.equal(360 / (degPerMs * 1000), tokenSeconds())
})
