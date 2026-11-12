#!/usr/bin/env node
// The names of the tools the application's backend offers the model, read out of its source.
//
//   node tools/i18n/tool-names.mjs           write the lists the page's tool names are held to
//   node tools/i18n/tool-names.mjs --check   exit 1 when a list is out of date
//   node tools/i18n/tool-names.mjs --print   list them with where each was found
//
// WHY THIS EXISTS. A person sees a tool by its NAME in many places - the row of a tool call in a
// transcript, the detail dialog's title, an approval card, a mail row - and every one of them must
// read in the person's language. A name derived from the code name at runtime ("replyToYouTubeComment"
// -> "Reply to YouTube comment") can never be translated, because no catalog has a key for words
// that only exist once the code runs. So every tool the product offers has a written English title
// and written running/done phrases (toolDisplayName.ts, toolActivity.ts), each a catalog key.
//
// For that the page has to know every tool, and a list kept by hand would be wrong within a week.
// So the list is GENERATED from the Java source, the way server-messages.mjs reads the backend's
// sentences, and toolNames.test.ts fails while a tool in it has no title or no phrases. A tool added
// on the server tomorrow fails the pipeline until somebody names it.
//
// WHAT IT READS. Every @Tool annotation in main sources: its `name` attribute when it has one - a
// literal, a constant of the same file, or a qualified constant (Class.CONSTANT or a fully
// qualified one) found in the same side's tree - else the Java method's name, which is what Spring
// AI advertises. A third-party MCP server's tools are the server's own names, data, and not here.

import { readdirSync, readFileSync, writeFileSync, existsSync, statSync, mkdirSync } from 'node:fs'
import { dirname, join, relative, resolve, basename } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')

// TWO LISTS, one per side of the Enterprise boundary (docs/architecture.html#oss-boundary), as
// server-messages.mjs writes. The Enterprise list leaves out a name the Community list has. A tree
// without ee/ - the public Community repository - writes and checks the first alone.
const SIDES = [
  {
    name: 'community',
    backend: join(ROOT, 'apps', 'app', 'backend'),
    output: join(ROOT, 'apps', 'app', 'frontend', 'src', 'i18n', 'toolNames.generated.ts'),
  },
  {
    name: 'enterprise',
    backend: join(ROOT, 'ee', 'backend'),
    output: join(ROOT, 'ee', 'frontend', 'src', 'i18n', 'toolNames.generated.ts'),
  },
].filter(side => existsSync(side.backend))

function javaFiles(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (name === 'target' || name === 'node_modules' || name === 'test') continue
    if (statSync(path).isDirectory()) javaFiles(path, out)
    else if (name.endsWith('.java') && path.includes(join('src', 'main'))) out.push(path)
  }
  return out
}

// The index of the bracket closing the one opened at src[i], skipping strings, text blocks and
// comments - an annotation's description is usually a text block full of parentheses.
function closing(src, i) {
  const open = src[i]
  const close = open === '(' ? ')' : open === '{' ? '}' : ']'
  let depth = 0
  for (let j = i; j < src.length; j++) {
    if (src.startsWith('"""', j)) { j = src.indexOf('"""', j + 3) + 2; continue }
    const c = src[j]
    if (c === '"') {
      for (j++; j < src.length && src[j] !== '"'; j++) if (src[j] === '\\') j++
      continue
    }
    if (src.startsWith('//', j)) { j = src.indexOf('\n', j); continue }
    if (src.startsWith('/*', j)) { j = src.indexOf('*/', j + 2) + 1; continue }
    if (c === open) depth++
    else if (c === close && --depth === 0) return j
  }
  return -1
}

// Blank every comment, string and text block's CONTENT, keeping offsets - so `@Tool` in a javadoc
// or a description is never read as an annotation.
function code(src) {
  let out = ''
  for (let j = 0; j < src.length; j++) {
    if (src.startsWith('"""', j)) {
      const end = src.indexOf('"""', j + 3) + 3
      out += '"""' + src.slice(j + 3, end - 3).replace(/[^\n]/g, ' ') + '"""'
      j = end - 1
    } else if (src[j] === '"') {
      let k = j + 1
      for (; k < src.length && src[k] !== '"'; k++) if (src[k] === '\\') k++
      out += '"' + src.slice(j + 1, k) + '"'
      j = k
    } else if (src.startsWith('//', j)) {
      const end = src.indexOf('\n', j)
      const stop = end < 0 ? src.length : end
      out += ' '.repeat(stop - j)
      j = stop - 1
    } else if (src.startsWith('/*', j)) {
      const end = src.indexOf('*/', j + 2) + 2
      out += src.slice(j, end).replace(/[^\n]/g, ' ')
      j = end - 1
    } else out += src[j]
  }
  return out
}

// `static final String NAME = <expression>;` in one file - the expression, a literal or another
// constant (`NAME = ChatOnlyTools.ASK_USER`), which resolveName follows.
function constantIn(src, name) {
  const m = new RegExp(`\\bstatic\\s+final\\s+String\\s+${name}\\s*=\\s*([^;]+);`).exec(src)
  return m ? m[1].trim() : null
}

export function extract(backend = SIDES[0].backend) {
  const files = javaFiles(backend)
  const byClass = new Map()
  for (const file of files) {
    const cls = basename(file, '.java')
    if (!byClass.has(cls)) byClass.set(cls, [])
    byClass.get(cls).push(file)
  }
  // A qualified constant may live on the other side's tree (an Enterprise tool naming a Community
  // class's constant), so the lookup also reads the Community tree.
  const community = backend === SIDES[0].backend ? byClass : (() => {
    const map = new Map()
    for (const file of javaFiles(SIDES[0].backend)) {
      const cls = basename(file, '.java')
      if (!map.has(cls)) map.set(cls, [])
      map.get(cls).push(file)
    }
    return map
  })()
  // A name expression as the string it stands for: a literal, a constant of `src`, or a qualified
  // constant of another class - each followed until it is a literal.
  const resolveName = (expr, src, depth = 0) => {
    if (depth > 5) return null
    if (/^"[^"]*"$/.test(expr)) return expr.slice(1, -1)
    if (/^[A-Z][A-Z0-9_]*$/.test(expr)) {
      const value = constantIn(src, expr)
      return value ? resolveName(value, src, depth + 1) : null
    }
    if (/^[\w.]+\.[A-Z][A-Z0-9_]*$/.test(expr)) {
      const parts = expr.split('.')
      const constant = parts.pop()
      const cls = parts.pop()
      for (const file of [...(byClass.get(cls) ?? []), ...(community.get(cls) ?? [])]) {
        const other = readFileSync(file, 'utf8')
        const value = constantIn(other, constant)
        if (value) return resolveName(value, other, depth + 1)
      }
    }
    return null
  }

  const found = new Map()
  const failures = []
  for (const file of files) {
    const src = readFileSync(file, 'utf8')
    const masked = code(src)
    const re = /@Tool\b(?![A-Za-z])/g
    for (let m; (m = re.exec(masked));) {
      const where = `${relative(ROOT, file).replaceAll('\\', '/')}:${src.slice(0, m.index).split('\n').length}`
      let after = m.index + m[0].length
      while (/\s/.test(masked[after])) after++
      let name = null
      if (masked[after] === '(') {
        const end = closing(src, after)
        const args = masked.slice(after + 1, end)
        const attr = /(?:^|[,(\s])name\s*=\s*([^,]+?)\s*(?:,|$)/s.exec(args)
        if (attr) {
          const expr = src.slice(after + 1 + attr.index, after + 1 + attr.index + attr[0].length)
            .replace(/^[,(\s]*name\s*=\s*/s, '').replace(/\s*,?\s*$/, '')
          name = resolveName(expr, src)
          if (!name) { failures.push(`${where}: cannot resolve name = ${expr}`); continue }
        }
        after = end + 1
      }
      if (!name) {
        // The method's name: the identifier before the first `(` after the annotation, past any
        // further annotations (with their arguments), modifiers and the return type.
        let j = after
        for (;;) {
          while (/\s/.test(masked[j])) j++
          if (masked[j] !== '@') break
          const ann = /^@[\w.]+/.exec(masked.slice(j))
          j += ann[0].length
          while (/\s/.test(masked[j])) j++
          if (masked[j] === '(') j = closing(src, j) + 1
        }
        const sig = /^[^(;{]*?\b([a-zA-Z_]\w*)\s*\(/.exec(masked.slice(j))
        if (!sig) { failures.push(`${where}: no method after @Tool`); continue }
        name = sig[1]
      }
      if (!found.has(name)) found.set(name, [])
      found.get(name).push(where)
    }
  }
  if (failures.length) {
    console.error(`tool-names: ${failures.length} @Tool name(s) could not be read:\n  ${failures.join('\n  ')}`)
    process.exit(1)
  }
  return new Map([...found.entries()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)))
}

export function render(names, side) {
  const lines = [...names.keys()].map(name => `  '${name}',`)
  const what = side === 'community'
    ? 'Every tool the Community backend offers the model'
    : 'Every tool the Enterprise backend (ee/) offers the model that the Community list does not have'
  return `// GENERATED by tools/i18n/tool-names.mjs from the backend's source. DO NOT EDIT - re-run it.
//
// ${what},
// by the name it is advertised under. Each has a written title and running/done phrases, each
// translated (toolDisplayName.ts, toolActivity.ts); toolNames.test.ts holds every name here to that.

export const TOOL_NAMES: readonly string[] = [
${lines.join('\n')}
]
`
}

const invoked = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (invoked) {
  const seen = new Set()
  let stale = false
  for (const side of SIDES) {
    const all = extract(side.backend)
    const names = new Map([...all].filter(([name]) => !seen.has(name)))
    for (const name of all.keys()) seen.add(name)
    const text = render(names, side.name)
    const out = relative(ROOT, side.output).replaceAll('\\', '/')
    if (process.argv.includes('--print')) {
      for (const [name, where] of names) console.log(`${name}\n    ${where.join('\n    ')}`)
      console.log(`\n${names.size} tool(s) - ${side.name}\n`)
    } else if (process.argv.includes('--check')) {
      const current = existsSync(side.output) ? readFileSync(side.output, 'utf8').replaceAll('\r\n', '\n') : ''
      if (current !== text) {
        console.error(`tool-names: ${out} is out of date.`)
        stale = true
      } else {
        console.log(`tool-names: ${names.size} tool(s) - ${side.name}, list is current`)
      }
    } else {
      mkdirSync(dirname(side.output), { recursive: true })
      writeFileSync(side.output, text)
      console.log(`tool-names: wrote ${names.size} tool(s) to ${out}`)
    }
  }
  if (stale) {
    console.error('  A backend tool was added, removed or renamed. Run: node tools/i18n/tool-names.mjs')
    console.error('  then give it a title and phrases (toolDisplayName.ts, toolActivity.ts) and their Vietnamese.')
    process.exit(1)
  }
}
