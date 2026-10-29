// What the UI guideline reads straight out of the kit's own files, as text - so a renamed export,
// a new mark or a changed token reaches the guideline on the next build, and the --check fails
// until it does. Plain Node, no dependencies: ui-guideline.test.mjs uses kitExports() too.

import { readFileSync } from 'node:fs'
import { join } from 'node:path'

export function kitDir(repo) {
  return join(repo, 'libs', 'ui-kit', 'react')
}

/**
 * Every name libs/ui-kit/react/index.ts exports, in order: { name, file, kind } where kind is
 * 'component' (PascalCase value), 'value' (a function, hook or constant) or 'type'.
 */
export function kitExports(repo) {
  const index = readFileSync(join(kitDir(repo), 'index.ts'), 'utf8')
  const out = []
  for (const m of index.matchAll(/export\s+(type\s+)?\{([^}]*)\}\s+from\s+['"]\.\/([^'"]+)['"]/g)) {
    for (const raw of m[2].split(',')) {
      const part = raw.trim()
      if (!part) continue
      const isType = !!m[1] || part.startsWith('type ')
      const name = part.replace(/^type\s+/, '').split(/\s+as\s+/).pop().trim()
      const kind = isType ? 'type' : (/^[A-Z][A-Za-z0-9]*$/.test(name) && /[a-z]/.test(name) ? 'component' : 'value')
      out.push({ name, file: m[3], kind })
    }
  }
  return out
}

function sourceOf(repo, file) {
  for (const ext of ['.ts', '.tsx']) {
    try { return readFileSync(join(kitDir(repo), file + ext), 'utf8') } catch { /* next */ }
  }
  return ''
}

/** First sentence of a comment, as plain text. */
function firstSentence(text) {
  const flat = text.replace(/<\/?p>|<\/?ul>|<\/?li>/g, ' ').replace(/\s+/g, ' ').trim()
  const m = /^(.+?[.:])(\s|$)/.exec(flat)
  const s = (m ? m[1] : flat).replace(/:$/, '.')
  return s.length > 240 ? s.slice(0, 237).replace(/\s+\S*$/, '') + '...' : s
}

/**
 * The comment directly above `export const|function NAME` in the kit source - a JSDoc block or a
 * run of line comments - reduced to its first sentence. Empty when there is none.
 */
export function exportDoc(repo, name, file) {
  const lines = sourceOf(repo, file).split('\n')
  const decl = new RegExp('^export\\s+(?:const|function|let|async\\s+function|class)\\s+' + name + '\\b')
  const i = lines.findIndex(l => decl.test(l))
  if (i < 0) return ''
  let j = i - 1
  const doc = []
  if (/\*\/\s*$/.test(lines[j] || '')) {
    while (j >= 0 && !/\/\*/.test(lines[j])) doc.unshift(lines[j--])
    if (j >= 0) doc.unshift(lines[j])
  } else {
    while (j >= 0 && /^\s*\/\//.test(lines[j])) doc.unshift(lines[j--])
  }
  const text = doc.join('\n')
    .replace(/\/\*\*?|\*\//g, ' ')
    .replace(/^\s*\*\s?/gm, ' ')
    .replace(/^\s*\/\/\s?/gm, ' ')
  return text.trim() ? firstSentence(text) : ''
}

/**
 * BrandMark.tsx's header records each mark's source and terms, one paragraph per mark or group of
 * marks. Returns id -> { vendor: boolean, note } in MarkId order.
 */
export function brandMarkTerms(repo) {
  const src = readFileSync(join(kitDir(repo), 'BrandMark.tsx'), 'utf8')
  const header = src.slice(src.indexOf('SOURCES AND TERMS'), src.indexOf('Adding a mark:'))
  const blocks = []
  for (const line of header.split('\n')) {
    const head = /^ \* {3}([a-z0-9][a-z0-9, ]*?)(?:\s+-\s+(.*))?$/.exec(line)
    const cont = /^ \* {7}(.*)$/.exec(line)
    if (head) blocks.push({ ids: head[1].split(',').map(s => s.trim()), text: head[2] || '' })
    else if (cont && blocks.length) blocks[blocks.length - 1].text += ' ' + cont[1]
  }
  const ids = [...src.matchAll(/^ {2}([a-z0-9]+): '/gm)].map(m => m[1])
  const out = new Map()
  for (const id of ids) {
    const b = blocks.find(x => x.ids.includes(id))
    const text = b ? b.text.replace(/\s+/g, ' ').trim() : ''
    const vendor = /^Simple Icons/.test(text)
    let note
    if (vendor) note = firstSentence(text)
    else {
      const glyph = /A generic glyph drawn for Proxyma:?\s*([^.]*\.)/.exec(text) || /generic glyphs? drawn for Proxyma[^.]*\./i.exec(text)
      const why = /^(NO [A-Z0-9 .]+ MARK)/.exec(text)
      note = [why ? why[1].replace(/ MARK$/, ' mark') + '.' : '', glyph ? `Generic glyph: ${(glyph[1] || glyph[0]).trim()}` : 'Generic glyph drawn for Proxyma.']
        .filter(Boolean).join(' ')
    }
    out.set(id, { vendor, note, terms: text })
  }
  return out
}

/**
 * proxyma-tokens.css as { light, dark, overridden }: every variable's raw value in each appearance.
 * Light is the bare :root block; Dark is :root[data-mode="dark"] over it; `overridden` names what
 * Dark re-points (since 2026-10-28 - there were three blocks while the Proxyma appearance existed).
 */
export function tokenBlocks(repo) {
  const css = readFileSync(join(repo, 'libs', 'ui-kit', 'tokens', 'proxyma-tokens.css'), 'utf8')
  const block = selector => {
    const m = selector.exec(css)
    if (!m) return {}
    const start = m.index + m[0].length
    const end = css.indexOf('\n}', start)
    const out = {}
    for (const v of css.slice(start, end).replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/--([\w-]+):\s*([^;]+);/g)) out[v[1]] = v[2].trim()
    return out
  }
  const light = block(/^:root \{/m)
  const dark = block(/^:root\[data-mode="dark"\] \{/m)
  return { light, dark: { ...light, ...dark }, overridden: Object.keys(dark) }
}

/** A token's value with var() references resolved in the same appearance; "R G B" stays as is. */
export function resolveToken(values, name, depth = 0) {
  const v = values[name]
  if (v === undefined || depth > 8) return v
  return v.replace(/var\(--([\w-]+)\)/g, (_, ref) => resolveToken(values, ref, depth + 1) ?? '')
}

/** "22 22 23" -> "#161617"; anything else unchanged. */
export function hex(value) {
  const m = /^(\d+)\s+(\d+)\s+(\d+)$/.exec(value || '')
  if (!m) return value || ''
  return '#' + [m[1], m[2], m[3]].map(n => Number(n).toString(16).padStart(2, '0')).join('')
}
