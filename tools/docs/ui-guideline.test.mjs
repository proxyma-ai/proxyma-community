import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { kitExports } from './ui-guideline/sources.mjs'

// docs/ui-guideline.html is generated from the kit (build-ui-guideline.mjs, whose --check runs where
// the app's node_modules are, in build:frontend). This half needs nothing installed, so it runs in
// test:checks on every push: the COMMITTED page has to carry every export of the kit's index.

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const HTML = readFileSync(join(REPO, 'docs', 'ui-guideline.html'), 'utf8')

test('every export of the kit has its anchor in the committed guideline', () => {
  const exports = kitExports(REPO)
  assert.ok(exports.filter(e => e.kind === 'component').length > 150, 'the export parser found too few components to be reading index.ts')
  const missing = exports.filter(e => !HTML.includes(`id="${e.name}"`)).map(e => `${e.name} (${e.kind})`)
  assert.deepEqual(missing, [], `in libs/ui-kit/react/index.ts but not in docs/ui-guideline.html - run: node tools/docs/build-ui-guideline.mjs\n  ${missing.join('\n  ')}`)
})

test('every component is drawn, not only named', () => {
  for (const e of kitExports(REPO).filter(x => x.kind === 'component')) {
    const at = HTML.indexOf(`<h3 id="${e.name}">`)
    assert.ok(at >= 0, `${e.name} has no section`)
    const next = HTML.indexOf('<h3 id="', at + 1)
    const section = HTML.slice(at, next < 0 ? undefined : next)
    assert.ok(section.includes('class="kit '), `${e.name}'s section has no rendered specimen`)
  }
})

test('the guideline is generated and ASCII', () => {
  assert.ok(HTML.includes('name="generator" content="tools/docs'), 'not the generated file')
  const bad = [...HTML].findIndex(c => c.charCodeAt(0) > 126 || (c.charCodeAt(0) < 32 && !'\n\r\t'.includes(c)))
  assert.equal(bad, -1, `a character outside ASCII at ${bad}: ${JSON.stringify(HTML.slice(Math.max(0, bad - 40), bad + 10))}`)
})
