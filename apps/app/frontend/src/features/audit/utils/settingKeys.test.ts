import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { settingFieldLabel, settingGroup, words } from './auditLabels.ts'

// Every setting a change can be recorded under, read from the backend's own source: the settings
// DTOs SettingsSecrets.PREFIX_CLASSES lists (and, where ee/ is in the tree, the ones the Enterprise
// modules contribute), each field by its @JsonProperty name - the `prefix.field` SettingsController
// records. A field added to a DTO tomorrow fails here until the Logs page has a name for it, so the
// page never falls back to the key made readable ("Detect pii") for a setting somebody can change.

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..', '..', '..', '..')
const SETTINGS = join(ROOT, 'apps', 'app', 'backend', 'proxyma-core-settings', 'src', 'main', 'java', 'ai', 'proxyma', 'core', 'settings')
const CONTRIBUTORS = [
  join(ROOT, 'ee', 'backend', 'proxyma-ee-microsoft365', 'src', 'main', 'java', 'ai', 'proxyma', 'ee', 'microsoft365', 'settings', 'MicrosoftSettingsPrefixes.java'),
]

function javaFile(dir: string, name: string): string | null {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry)
    if (entry === 'target' || entry === 'node_modules' || entry === 'test') continue
    if (statSync(path).isDirectory()) {
      const found = javaFile(path, name)
      if (found) return found
    } else if (entry === `${name}.java`) return path
  }
  return null
}

/** The classes a `m.put(X.PREFIX, X.class)` list registers, each read for its prefix and fields. */
function keysRegisteredIn(file: string, tree: string): string[] {
  const classes = [...readFileSync(file, 'utf8').matchAll(/m\.put\((\w+)\.PREFIX,\s*(\w+)\.class\)/g)].map(m => m[2])
  assert.ok(classes.length > 0, `no settings classes found in ${file}`)
  const keys: string[] = []
  for (const name of classes) {
    const path = javaFile(tree, name)
    assert.ok(path, `${name}.java not found under ${tree}`)
    const src = readFileSync(path, 'utf8')
    const prefix = /\bPREFIX\s*=\s*"([^"]+)"/.exec(src)?.[1]
    assert.ok(prefix, `${name} declares no PREFIX`)
    for (const m of src.matchAll(/@JsonProperty\("([^"]+)"\)/g)) keys.push(`${prefix}.${m[1]}`)
  }
  return keys
}

// Prefixes SettingsController never records (the product tour seen).
const unaudited = new Set(
  [...(/UNAUDITED_PREFIXES\s*=\s*Set\.of\(([^)]*)\)/.exec(readFileSync(join(SETTINGS, 'controller', 'SettingsController.java'), 'utf8'))?.[1] ?? '')
    .matchAll(/"([^"]+)"/g)].map(m => m[1]))

const keys = [
  ...keysRegisteredIn(join(SETTINGS, 'dto', 'SettingsSecrets.java'), join(ROOT, 'apps', 'app', 'backend')),
  ...CONTRIBUTORS.filter(existsSync).flatMap(file => keysRegisteredIn(file, join(ROOT, 'ee', 'backend'))),
].filter(key => !unaudited.has(key.slice(0, key.indexOf('.'))))

const t = (s: string) => s

test('the backend declares the settings this test reads', () => {
  assert.ok(unaudited.has('onboarding'))
  assert.ok(keys.includes('guardrail.detect-pii'))
  assert.ok(keys.length > 100, `only ${keys.length} settings found - is the DTO list still where this test reads it?`)
})

test('every setting a change is recorded under has a name on the Logs page', () => {
  const missing = keys.filter(key => settingFieldLabel(key, t) === null)
  assert.deepEqual(missing, [], 'name these in settingFieldLabel (auditLabels.ts), as their Settings tab does')
})

test('every settings group has a name of its own, not its prefix made readable', () => {
  const prefixes = [...new Set(keys.map(key => key.slice(0, key.indexOf('.'))))]
  // A product's name is the same in every language, and here also its prefix made readable.
  const products = new Set(['jira', 'confluence', 'bitbucket', 'svn'])
  const marked = (s: string) => `<${s}>`
  const unnamed = prefixes.filter(prefix => !products.has(prefix) && settingGroup(prefix, marked) === words(prefix))
  assert.deepEqual(unnamed, [], 'name these in settingGroup (auditLabels.ts)')
})
