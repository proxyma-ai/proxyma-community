#!/usr/bin/env node
// The sentences the application's backend can say to a person, read out of its source.
//
//   node tools/i18n/server-messages.mjs           write the list the page translates from
//   node tools/i18n/server-messages.mjs --check   exit 1 when that list is out of date
//   node tools/i18n/server-messages.mjs --print   list them with where each was found
//
// WHY THIS EXISTS. The backend explains itself in English - a refusal's reason, a failed sync, a
// limit reached - and the page shows those sentences as they come. They are translated ON THE
// PAGE, by the page's own catalog, because that is where the reader's language is known: a sync
// fails on a background thread with no request and no locale, and its message is stored and read
// days later by somebody else.
//
// For that the page has to know the sentences, and a list kept by hand would be wrong within a
// week. So the list is GENERATED from the Java source. A message that is put together from
// parts - "File exceeds maximum allowed size of " + mb + "MB" - becomes a template with numbered
// gaps, "File exceeds maximum allowed size of {0}MB", which the page matches the text it was sent
// against and translates as a whole sentence, the values carried over (serverMessage.ts).
//
// Rewording a message here changes the list; `--check` in the pipeline fails until it is
// regenerated; and the catalog checker then reports the old translation as orphaned and the new
// sentence as untranslated. Nothing has to be remembered.
//
// WHAT IT READS. The first string argument of every exception constructed, the value beside an
// "error" / "message" / "detail" key, and what a document or a tool call is marked failed with.
// And the sentences a connector RETURNS rather than throws - a registration probe's
// AccessCheck.failed, a connection Test's ConnectionTestResult (and the older testers' Result), a
// listing that failed (Listings.failed) or is partial (the reasons inside Listings.partial and
// partialIf, which are usually a lambda's arms), and a connector's sentence held in a constant
// (static final String NO_CONNECTION = "..."). And a sentence marked notice("...") where none of
// those applies - the license re-check's (LicenseRecheckService). A Test result whose template names its values
// ({user}) is not one of these: connectionTestMessage.ts lists those by hand.
// What it cannot see stays English, which is what every message was before: a sentence built in a
// variable first, or assembled by a helper. English is always the fallback - nothing here can
// make a message disappear.

import { readdirSync, readFileSync, writeFileSync, existsSync, statSync, mkdirSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')

// TWO LISTS, one per side of the Enterprise boundary (docs/architecture.html#oss-boundary). The Community backend's
// sentences go to the app's own list; the Enterprise modules' (ee/, private) to a list beside the
// Enterprise frontend, holding only what the Community list does not already have. A tree without
// ee/ - the public Community repository - writes and checks the first alone, and its catalog then
// holds no translation for a sentence it cannot say.
const SIDES = [
  {
    name: 'community',
    backend: join(ROOT, 'apps', 'app', 'backend'),
    output: join(ROOT, 'apps', 'app', 'frontend', 'src', 'i18n', 'serverMessages.generated.ts'),
  },
  {
    name: 'enterprise',
    backend: join(ROOT, 'ee', 'backend'),
    output: join(ROOT, 'ee', 'frontend', 'src', 'i18n', 'serverMessages.generated.ts'),
  },
].filter(side => existsSync(side.backend))

// Where a sentence for a person starts. Each matches up to the opening of the expression.
const ANCHORS = [
  // A nested refusal class counts too - `new KeyAccountException.Invalid(` - so a module that
  // groups its refusals under one parent (proxyma-core-keyaccount) is read like any other.
  /\bnew\s+[A-Z][A-Za-z0-9]*(?:Exception|Error)(?:\.[A-Z][A-Za-z0-9]*)?\s*\(/g,
  /"(?:error|message|detail)"\s*,/g,
  /\bmarkFailed\s*\(\s*[A-Za-z_][\w.()]*\s*,/g,
  // notice("...") marks a sentence that reaches the page by none of these - the license re-check's.
  /\b(?:failure|refusal|errorResult|toolError|notice)\s*\(/g,
  // Returned, not thrown: a probe's verdict, a connection Test's, an older tester's, a failed listing.
  /\b(?:AccessCheck|ConnectionTestResult|Result|Listings)\s*\.\s*(?:failed|ok)\s*\(/g,
]

// Calls whose sentences sit anywhere inside their arguments - a partial listing's reason, usually
// the arms of a lambda's ternary. Every literal there that starts an expression is read as one.
const REGION_ANCHORS = [
  /\bListings\s*\.\s*partial(?:If)?\s*\(/g,
]

// A connector's sentence kept in a constant, read where connectors live - a module-wide rule would
// sweep in every constant that happens to hold two words.
const CONSTANT_ANCHOR = /\bstatic\s+final\s+String\s+[A-Z][A-Z0-9_]*\s*=\s*/g
const CONSTANT_MODULES = [
  join(ROOT, 'apps', 'app', 'backend', 'proxyma-connectors'),
  join(ROOT, 'apps', 'app', 'backend', 'proxyma-core-connector'),
  join(ROOT, 'ee', 'backend', 'proxyma-ee-connector-microsoft365'),
].map(dir => dir + (process.platform === 'win32' ? '\\' : '/'))

function javaFiles(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (name === 'target' || name === 'node_modules' || name === 'test') continue
    if (statSync(path).isDirectory()) javaFiles(path, out)
    else if (name.endsWith('.java') && path.includes(join('src', 'main'))) out.push(path)
  }
  return out
}

// One Java string literal starting at src[i] === '"'. Null for a text block, which is prose of
// another kind (a prompt, a query) and never a one-line message.
function readLiteral(src, i) {
  if (src.startsWith('"""', i)) return null
  let out = ''
  for (let j = i + 1; j < src.length; j++) {
    const c = src[j]
    if (c === '\\') {
      const n = src[++j]
      if (n === 'n') out += '\n'
      else if (n === 't') out += '\t'
      else if (n === 'u') { out += String.fromCharCode(parseInt(src.slice(j + 1, j + 5), 16)); j += 4 }
      else out += n
    } else if (c === '"') {
      return { text: out, end: j + 1 }
    } else if (c === '\n') {
      return null
    } else out += c
  }
  return null
}

function skipSpace(src, i) {
  for (;;) {
    while (i < src.length && /\s/.test(src[i])) i++
    if (src.startsWith('//', i)) { while (i < src.length && src[i] !== '\n') i++; continue }
    if (src.startsWith('/*', i)) { i = src.indexOf('*/', i) + 2; continue }
    return i
  }
}

// Anything that is not a literal: a variable, a call, a parenthesised expression. Runs to the
// next `+`, `,` or closing bracket at its own depth.
function readOther(src, i) {
  let depth = 0
  for (let j = i; j < src.length; j++) {
    const c = src[j]
    if (c === '"') { const lit = readLiteral(src, j); if (!lit) return null; j = lit.end - 1; continue }
    if (c === "'") { j = src.indexOf("'", j + (src[j + 1] === '\\' ? 3 : 2)); continue }
    if (c === '(' || c === '[' || c === '{') depth++
    else if (c === ')' || c === ']' || c === '}') { if (depth === 0) return j; depth-- }
    else if (depth === 0 && (c === ',' || c === ';')) return j
    else if (depth === 0 && c === '+' && src[j + 1] !== '+' && src[j - 1] !== '+') return j
    // A lambda or a ternary's arms hold their own literals; the whole of it is one gap.
  }
  return null
}

// %s, %d, %,d, %.1f ... as numbered gaps, continuing from `next`.
function formatSpecifiers(text, state) {
  return text.replace(/%%|%[-,+0 #(]*\d*(?:\.\d+)?[sdfxXcb]/g, m => (m === '%%' ? '%' : `{${state.next++}}`))
}

/** The message expression starting at src[i], as a template - or null when it is not one. */
function readExpression(src, i) {
  const state = { next: 0 }
  let template = ''
  let literals = 0
  i = skipSpace(src, i)
  for (;;) {
    if (src[i] === '"') {
      const lit = readLiteral(src, i)
      if (!lit) return null
      i = lit.end
      let text = lit.text
      if (src.startsWith('.formatted(', i)) {
        const end = readOther(src, i + '.formatted('.length)
        if (end === null) return null
        text = formatSpecifiers(text, state)
        i = end + 1
      }
      template += text
      literals++
    } else {
      const formatCall = /^String\s*\.\s*format\s*\(\s*/.exec(src.slice(i, i + 40))
      if (formatCall && src[i + formatCall[0].length] === '"') {
        const lit = readLiteral(src, i + formatCall[0].length)
        if (!lit) return null
        const end = readOther(src, lit.end)
        if (end === null) return null
        template += formatSpecifiers(lit.text, state)
        literals++
        i = end + 1
      } else {
        const end = readOther(src, i)
        if (end === null || end === i) return null
        // Two gaps side by side cannot be told apart in the text; they are one.
        if (!/\{\d+\}$/.test(template)) template += `{${state.next++}}`
        i = end
      }
    }
    i = skipSpace(src, i)
    if (src[i] === '+') { i = skipSpace(src, i + 1); continue }
    break
  }
  return literals > 0 ? template : null
}

// A sentence for a person: words, in the ordinary sense. What it rules out is a key, a header
// name, a log tag, a fragment that is all gap, and three things the page's translator cannot
// carry - a line break, a brace that is not a gap, and the bar it reads as a plural.
function isSentence(template) {
  if (/[\n\t|]/.test(template)) return false
  const fixed = template.replace(/\{\d+\}/g, '')
  if (/[{}]/.test(fixed)) return false
  const words = fixed.trim().split(/\s+/).filter(w => /[A-Za-z]{2,}/.test(w))
  if (words.length < 2) return false
  if ((fixed.match(/[A-Za-z]/g) ?? []).length < 10) return false
  return true
}

// The index of the bracket closing the one opened just before `i`, or -1.
function closingBracket(src, i) {
  let depth = 0
  for (let j = i; j < src.length; j++) {
    const c = src[j]
    if (c === '"') { const lit = readLiteral(src, j); if (!lit) { j = src.indexOf('"""', j + 3) + 2; continue } j = lit.end - 1; continue }
    if (c === "'") { j = src.indexOf("'", j + (src[j + 1] === '\\' ? 3 : 2)); continue }
    if (c === '(' || c === '[' || c === '{') depth++
    else if (c === ')' || c === ']' || c === '}') { if (depth === 0) return j; depth-- }
  }
  return -1
}

// The starts of the literals in src[from, to) that begin an expression - not those continuing one
// after a `+`, which readExpression reaches from the literal before.
function expressionStarts(src, from, to) {
  const starts = []
  for (let j = from; j < to; j++) {
    const c = src[j]
    if (c === "'") { j = src.indexOf("'", j + (src[j + 1] === '\\' ? 3 : 2)); continue }
    if (c !== '"') continue
    const lit = readLiteral(src, j)
    if (!lit) { j = src.indexOf('"""', j + 3) + 2; continue }
    let k = j - 1
    while (k >= from && /\s/.test(src[k])) k--
    if (src[k] !== '+') starts.push(j)
    j = lit.end - 1
  }
  return starts
}

export function extract(backend = SIDES[0].backend) {
  const found = new Map()
  const record = (template, file, src, at) => {
    if (!template || !isSentence(template)) return
    const text = template.trim()
    const where = `${relative(ROOT, file).replaceAll('\\', '/')}:${src.slice(0, at).split('\n').length}`
    if (!found.has(text)) found.set(text, [])
    found.get(text).push(where)
  }
  for (const file of javaFiles(backend)) {
    const src = readFileSync(file, 'utf8')
    for (const anchor of ANCHORS) {
      anchor.lastIndex = 0
      for (let m; (m = anchor.exec(src));) record(readExpression(src, m.index + m[0].length), file, src, m.index)
    }
    for (const anchor of REGION_ANCHORS) {
      anchor.lastIndex = 0
      for (let m; (m = anchor.exec(src));) {
        const from = m.index + m[0].length
        const to = closingBracket(src, from)
        if (to < 0) continue
        for (const start of expressionStarts(src, from, to)) record(readExpression(src, start), file, src, start)
      }
    }
    if (CONSTANT_MODULES.some(dir => file.startsWith(dir))) {
      CONSTANT_ANCHOR.lastIndex = 0
      for (let m; (m = CONSTANT_ANCHOR.exec(src));) {
        // A constant is a sentence only when it ends like one; a name ("your OneDrive") or a header
        // value is a fragment the page never shows alone.
        const template = readExpression(src, m.index + m[0].length)
        if (template && /[.!?]$/.test(template.trim())) record(template, file, src, m.index)
      }
    }
  }
  return new Map([...found.entries()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)))
}

const quote = text => `'${text.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`

export function render(messages) {
  const lines = [...messages.keys()].map(text => `  ${quote(text)},`)
  return `// GENERATED by tools/i18n/server-messages.mjs from the backend's source. DO NOT EDIT - re-run it.
//
// Every sentence the backend can say to a person, as the page needs them: to translate one, the
// page has to recognise it (serverMessage.ts). A numbered gap - {0} - is where the backend puts a
// value in. Translations are ordinary catalog entries keyed by these lines (catalogs/vi.ts).

export const SERVER_MESSAGES: readonly string[] = [
${lines.join('\n')}
]
`
}

const invoked = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (invoked) {
  // Each side's list; the Enterprise one leaves out what the Community one already says.
  const seen = new Set()
  let stale = false
  for (const side of SIDES) {
    const all = extract(side.backend)
    const messages = new Map([...all].filter(([message]) => !seen.has(message)))
    for (const message of all.keys()) seen.add(message)
    const text = render(messages)
    const out = relative(ROOT, side.output).replaceAll('\\', '/')
    if (process.argv.includes('--print')) {
      for (const [message, where] of messages) console.log(`${message}\n    ${where.join('\n    ')}`)
      console.log(`\n${messages.size} sentence(s) - ${side.name}\n`)
    } else if (process.argv.includes('--check')) {
      const current = existsSync(side.output) ? readFileSync(side.output, 'utf8').replaceAll('\r\n', '\n') : ''
      if (current !== text) {
        console.error(`server-messages: ${out} is out of date.`)
        stale = true
      } else {
        console.log(`server-messages: ${messages.size} sentence(s) - ${side.name}, list is current`)
      }
    } else {
      mkdirSync(dirname(side.output), { recursive: true })
      writeFileSync(side.output, text)
      console.log(`server-messages: wrote ${messages.size} sentence(s) to ${out}`)
    }
  }
  if (stale) {
    console.error('  A backend message was added, removed or reworded. Run: node tools/i18n/server-messages.mjs')
    console.error('  then: node libs/ui-kit/i18n/check-catalogs.mjs  (it names the translations to add or drop)')
    process.exit(1)
  }
}
