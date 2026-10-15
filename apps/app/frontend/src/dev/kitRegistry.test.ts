import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { KIT_GROUPS, KIT_REGISTRY } from './kitRegistry.ts'

// The gallery is the kit's catalogue and the guideline is written from it, so a component the kit
// exports with no entry and no specimen is a component nobody can find. These read the files as
// text: the kit's index (its PascalCase VALUE exports - components) and the specimens file (its
// `  Name: [` keys and their `title: '...'` lines).

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..', '..', '..', '..', '..')
const INDEX = readFileSync(join(ROOT, 'libs', 'ui-kit', 'react', 'index.ts'), 'utf8')
const SPECIMENS = readFileSync(join(HERE, 'kitSpecimens.tsx'), 'utf8')
const GALLERY = readFileSync(join(HERE, 'KitGallery.tsx'), 'utf8')

function kitComponents(): string[] {
  const names: string[] = []
  for (const m of INDEX.matchAll(/export\s+(type\s+)?\{([^}]*)\}\s+from\s+['"]\.\/[^'"]+['"]/g)) {
    if (m[1]) continue
    for (const raw of m[2].split(',')) {
      const part = raw.trim()
      if (!part || part.startsWith('type ')) continue
      const name = part.split(/\s+as\s+/).pop()!.trim()
      // PascalCase: a capital first and a lower-case letter somewhere - HScroll as well as Button,
      // never a CONSTANT_NAME.
      if (/^[A-Z][A-Za-z0-9]*$/.test(name) && /[a-z]/.test(name)) names.push(name)
    }
  }
  return names
}

function specimenTitles(): Map<string, string[]> {
  const body = SPECIMENS.slice(SPECIMENS.indexOf('export const SPECIMENS'))
  const keys = [...body.matchAll(/^ {2}([A-Z][A-Za-z0-9]*): \[/gm)]
  const out = new Map<string, string[]>()
  keys.forEach((m, i) => {
    const chunk = body.slice(m.index, i + 1 < keys.length ? keys[i + 1].index : undefined)
    out.set(m[1], [...chunk.matchAll(/title: '((?:[^'\\]|\\.)*)'/g)].map(t => t[1]))
  })
  return out
}

test('every component the kit exports has a registry entry, and every entry is an export', () => {
  const exported = new Set(kitComponents())
  const listed = new Set(KIT_REGISTRY.map(e => e.name))
  const missing = [...exported].filter(n => !listed.has(n))
  const stray = [...listed].filter(n => !exported.has(n))
  assert.deepEqual(missing, [], `exported by the kit with no entry in kitRegistry.ts: ${missing.join(', ')}`)
  assert.deepEqual(stray, [], `in kitRegistry.ts but not exported by the kit: ${stray.join(', ')}`)
  assert.ok(exported.size > 150, 'the export parser found too few components to be reading index.ts')
})

test('an entry is listed once', () => {
  const seen = new Set<string>()
  const twice = KIT_REGISTRY.map(e => e.name).filter(n => (seen.has(n) ? true : (seen.add(n), false)))
  assert.deepEqual(twice, [])
})

test('every entry has specimens in kitSpecimens.tsx, and the registry names them as the file does', () => {
  const titles = specimenTitles()
  for (const e of KIT_REGISTRY) {
    const drawn = titles.get(e.name)
    assert.ok(drawn && drawn.length > 0, `${e.name} has no specimen in kitSpecimens.tsx`)
    assert.deepEqual(e.specimens, drawn, `${e.name}'s specimens in the registry differ from kitSpecimens.tsx`)
  }
  const stray = [...titles.keys()].filter(k => !KIT_REGISTRY.some(e => e.name === k))
  assert.deepEqual(stray, [], `specimens for something the registry does not list: ${stray.join(', ')}`)
})

test('the gallery draws the registry and its specimens', () => {
  assert.ok(GALLERY.includes('KIT_REGISTRY') && GALLERY.includes('SPECIMENS'), 'KitGallery.tsx no longer renders from the registry')
})

test('every entry is complete, in a known group, and ASCII', () => {
  const groups = new Set(KIT_GROUPS.map(g => g.id))
  for (const e of KIT_REGISTRY) {
    assert.ok(groups.has(e.group), `${e.name}: unknown group ${e.group}`)
    assert.ok(e.purpose.length > 10, `${e.name}: no purpose`)
    assert.ok(e.use.length > 0 && e.avoid.length > 0, `${e.name}: say when to use it and when not`)
    // The source rule: ASCII only.
    assert.ok(/^[\x20-\x7e]*$/.test(JSON.stringify(e)), `${e.name}: non-ASCII text`)
  }
})
