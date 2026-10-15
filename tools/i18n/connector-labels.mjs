#!/usr/bin/env node
// The words a connector's DESCRIPTOR puts on the page, read out of its source.
//
//   node tools/i18n/connector-labels.mjs           write the list the page translates from
//   node tools/i18n/connector-labels.mjs --check   exit 1 when that list is out of date
//   node tools/i18n/connector-labels.mjs --print   list them with where each was found
//
// WHY THIS EXISTS. The Sources page, the source dialog and the connection dialog are drawn from the
// connector catalog (GET /api/v1/connectors/catalog), so a type's name, its fields' labels and help,
// its sign-in methods and its category arrive from the server in English and reach `t()` as
// VARIABLES - which the catalog checker cannot see. This list is what it reads instead: the strings
// a descriptor states, generated from the Java source the way server-messages.mjs reads the
// backend's sentences, so a connector added on the server - or a label reworded - is reported by
// `I18N_STRICT=1 node libs/ui-kit/i18n/check-catalogs.mjs` until its Vietnamese is written.
//
// WHAT IT READS, in files that build a descriptor (ConnectorDescriptor.builder, ProtocolDescriptor
// .builder, FieldSpec, AuthMethod.of) and in Category.java: the string arguments of displayName,
// description, noun (both), addTitle and help; a FieldSpec factory's label (its second argument);
// an AuthMethod's label; an Option's label; and each Category's label. A placeholder is an EXAMPLE
// of a value and is shown as it is, so it is not read. An argument that is not a literal (or a
// concatenation of literals) is skipped - write descriptor text as literals.

import { readdirSync, readFileSync, writeFileSync, existsSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')

// TWO LISTS, one per side of the Enterprise boundary (docs/architecture.html#oss-boundary), as server-messages.mjs
// writes. The Community descriptors' words go to the app's own list; the Enterprise modules' (ee/,
// private - the Microsoft 365 types and service) to a list beside the Enterprise frontend, holding
// only what the Community list does not already have. A tree without ee/ - the public Community
// repository - writes and checks the first alone, and its catalog then holds no translation for a
// type it does not have.
const SIDES = [
  {
    name: 'community',
    backend: join(ROOT, 'apps', 'app', 'backend'),
    output: join(ROOT, 'apps', 'app', 'frontend', 'src', 'i18n', 'connectorLabels.generated.ts'),
  },
  {
    name: 'enterprise',
    backend: join(ROOT, 'ee', 'backend'),
    output: join(ROOT, 'ee', 'frontend', 'src', 'i18n', 'connectorLabels.generated.ts'),
  },
].filter(side => existsSync(side.backend))

// A file is read when it builds descriptor text at all.
const MARKERS = ['ConnectorDescriptor.builder(', 'ProtocolDescriptor.builder(', 'FieldSpec.', 'AuthMethod.of(']

// Where descriptor text starts, and which of the call's arguments carry it (0-based).
const CALLS = [
  { re: /\.(?:displayName|description|addTitle|help)\(/g, args: [0] },
  { re: /\.noun\(/g, args: [0, 1] },
  { re: /\bFieldSpec\.(?:text|url|secret|number|bool|select|multiSelect|list|textarea|of)\(/g, args: [1] },
  { re: /\bAuthMethod\.of\(/g, args: [1] },
  { re: /\bnew\s+FieldSpec\.Option\(|\bOption\.of\(/g, args: [1] },
]

function javaFiles(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (name === 'target' || name === 'node_modules' || name === 'test') continue
    if (statSync(path).isDirectory()) javaFiles(path, out)
    else if (name.endsWith('.java') && path.includes(join('src', 'main'))) out.push(path)
  }
  return out
}

// The top-level arguments of the call whose '(' is at src[open - 1], as raw source text each.
function callArguments(src, open) {
  const args = []
  let depth = 0
  let start = open
  for (let i = open; i < src.length; i++) {
    const c = src[i]
    if (c === '"') {
      // Skip a literal, escapes included. A text block (""") is not descriptor text.
      i++
      while (i < src.length && src[i] !== '"') { if (src[i] === '\\') i++; i++ }
      continue
    }
    if (c === '\'') {
      i++
      while (i < src.length && src[i] !== '\'') { if (src[i] === '\\') i++; i++ }
      continue
    }
    if (c === '(' || c === '[' || c === '{') depth++
    else if (c === ')' || c === ']' || c === '}') {
      if (depth === 0) { args.push(src.slice(start, i)); return args }
      depth--
    } else if (c === ',' && depth === 0) {
      args.push(src.slice(start, i))
      start = i + 1
    }
  }
  return args
}

// An argument that is a string literal, or literals joined by `+`, as the TEXT of its source - escapes
// kept as Java wrote them (`\\` stays two characters, which is how a TS literal spells one too),
// only `\"` read as a quote. Null for anything else: a constant, a call, a value put together.
function literalText(arg) {
  const parts = arg.trim().split(/\s*\+\s*(?=")/)
  let out = ''
  for (const part of parts) {
    const m = part.trim().match(/^"((?:[^"\\]|\\.)*)"$/s)
    if (!m) return null
    out += m[1].replace(/\\"/g, '"')
  }
  return out
}

export function extract(backend = SIDES[0].backend) {
  const found = new Map()
  const add = (text, where) => {
    if (!text || !text.trim()) return
    if (!found.has(text)) found.set(text, [])
    found.get(text).push(where)
  }
  for (const file of javaFiles(backend)) {
    const src = readFileSync(file, 'utf8')
    const rel = relative(ROOT, file).replaceAll('\\', '/')
    if (file.endsWith(join('spi', 'Category.java'))) {
      for (const m of src.matchAll(/^\s*[A-Z_]+\("((?:[^"\\]|\\.)*)"\)[,;]/gm)) add(m[1], rel)
      continue
    }
    if (!MARKERS.some(marker => src.includes(marker))) continue
    for (const { re, args } of CALLS) {
      for (const m of src.matchAll(re)) {
        const list = callArguments(src, m.index + m[0].length)
        for (const n of args) {
          const text = list[n] === undefined ? null : literalText(list[n])
          if (text !== null) add(text, `${rel}:${src.slice(0, m.index).split('\n').length}`)
        }
      }
    }
  }
  return new Map([...found.entries()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)))
}

// One line per string, in whichever quote it does not contain - the catalog checker reads these
// lines as they are written (check-catalogs.mjs, the connectorLabels entry), so nothing is escaped
// but the backslashes Java already doubled.
function quote(text) {
  if (!text.includes('\'')) return `'${text}'`
  if (!text.includes('"')) return `"${text}"`
  throw new Error(`connector-labels: "${text}" holds both quote characters; reword it`)
}

export function render(labels) {
  const lines = [...labels.keys()].map(text => `  ${quote(text)},`)
  return `// GENERATED by tools/i18n/connector-labels.mjs from the connector descriptors. DO NOT EDIT - re-run it.
//
// Every word a connector's descriptor puts on the page - type names, field labels and help, sign-in
// methods, categories. They arrive from the connector catalog and reach t() as variables, so this
// list is what tells the catalog checker they are asked for. Translations are ordinary catalog
// entries keyed by these lines (catalogs/vi.ts).

export const CONNECTOR_LABELS: readonly string[] = [
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
    const labels = new Map([...all].filter(([label]) => !seen.has(label)))
    for (const label of all.keys()) seen.add(label)
    const text = render(labels)
    const out = relative(ROOT, side.output).replaceAll('\\', '/')
    if (process.argv.includes('--print')) {
      for (const [label, where] of labels) console.log(`${label}\n    ${where.join('\n    ')}`)
      console.log(`\n${labels.size} label(s) - ${side.name}\n`)
    } else if (process.argv.includes('--check')) {
      const current = existsSync(side.output) ? readFileSync(side.output, 'utf8').replaceAll('\r\n', '\n') : ''
      if (current !== text) {
        console.error(`connector-labels: ${out} is out of date.`)
        stale = true
      } else {
        console.log(`connector-labels: ${labels.size} label(s) - ${side.name}, list is current`)
      }
    } else {
      writeFileSync(side.output, text)
      console.log(`connector-labels: wrote ${labels.size} label(s) to ${out}`)
    }
  }
  if (stale) {
    console.error('  A connector descriptor\'s text was added, removed or reworded. Run: node tools/i18n/connector-labels.mjs')
    console.error('  then: I18N_STRICT=1 node libs/ui-kit/i18n/check-catalogs.mjs  (it names the translations to add)')
    process.exit(1)
  }
}
