import { test } from 'node:test'
import assert from 'node:assert/strict'
import { TOOL_NAMES } from '../../../i18n/toolNames.generated.ts'
import { PHRASES } from './toolActivity.ts'
import { TOOL_TITLES, toolDisplayName } from './toolDisplayName.ts'

// EVERY TOOL THE BACKEND CAN OFFER IS NAMED, IN WORDS A CATALOG CAN TRANSLATE (2026-11-05). The list
// is generated from the Java source (tools/i18n/tool-names.mjs), so a tool added on the server fails
// here until it has a title and phrases - and check-catalogs, under I18N_STRICT, until those have
// their Vietnamese. A name derived from the code name at runtime could never be translated: the
// Approvals page said "Reply to YouTube comment" on a Vietnamese interface.

test('every Community tool has a written title', () => {
  const missing = TOOL_NAMES.filter(name => !TOOL_TITLES[name])
  assert.deepEqual(missing, [], `no title in TOOL_TITLES (toolDisplayName.ts): ${missing.join(', ')}`)
})

test('every Community tool has written running and done phrases', () => {
  const missing = TOOL_NAMES.filter(name => !PHRASES[name])
  assert.deepEqual(missing, [], `no phrases in PHRASES (toolActivity.ts): ${missing.join(', ')}`)
})

test('the titles and the phrases name the same tools', () => {
  const titled = Object.keys(TOOL_TITLES).sort()
  const phrased = Object.keys(PHRASES).sort()
  assert.deepEqual(titled.filter(n => !PHRASES[n]), [], 'a title with no phrases')
  assert.deepEqual(phrased.filter(n => !TOOL_TITLES[n]), [], 'phrases with no title')
})

// One English key carries one translation, so a title spelled like a phrase would be translated as
// a finished call ("Read a file" - "Da doc mot tep") where it means an instruction, or the reverse.
test('no title is spelled like a phrase', () => {
  const phrases = new Set(Object.values(PHRASES).flatMap(([running, done]) => [running, done]))
  const clashes = Object.entries(TOOL_TITLES).filter(([, title]) => phrases.has(title))
  assert.deepEqual(clashes, [])
})

test('a title reaches the person through the translation function, never as a code name', () => {
  const t = (s: string) => `<${s}>`
  assert.equal(toolDisplayName('replyToYouTubeComment', t), '<Reply to a YouTube comment>')
  for (const name of TOOL_NAMES) assert.match(toolDisplayName(name, t), /^<.+>$/)
})
