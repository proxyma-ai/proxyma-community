#!/usr/bin/env node
// =================================================================================================
//  check-boundary.mjs - nothing outside ee/ depends on what is inside it.
//
//      node tools/oss/check-boundary.mjs
//
//  ee/ is Proxyma Enterprise: closed source, never exported to the public Community repository
//  (docs/architecture.html#oss-boundary). The Community code must build and run without it, so the dependency runs
//  ONE WAY - ee/ may use anything, nothing else may use ee/. The compiler already refuses most of it
//  (no Community module has an ee artifact in its reactor), which is why this checks the ways around
//  the compiler instead:
//
//    java      a Java package declared under ee/ named in Community code that is not a comment: an
//              import, a fully qualified name, or a string - `Class.forName("...")` reaches a class
//              the compiler never sees. Comments may mention an Enterprise class; code may not.
//    maven     a pom outside ee/ that declares a dependency on an artifact built under ee/.
//    frontend  a .ts/.tsx file outside ee/ whose import names ee/ or the `@ee/` alias. The app
//              reaches its Enterprise pages through `@edition` (src/edition/types.ts) and nothing
//              else; the build files that point that alias into ee/ (vite.config.ts,
//              tsconfig.server.json, tailwind.config.ts) are wiring, not imports, and not checked.
//
//  Exit 1 with every finding listed, 0 when there are none. Reads the working tree; needs no build.
//  Run by `oss:boundary` (.gitlab/ci/oss.yml) on every push.
// =================================================================================================
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs'
import { join, relative, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const EE = join(ROOT, 'ee')
// .claude holds an assistant's scratch worktrees - full copies of the tree, ee/ included, never
// committed - which read as hundreds of crossings on a developer's machine and none in CI.
const SKIP = new Set(['node_modules', 'target', 'dist', '.next', 'out', '.git', '.claude', 'ee'])

if (!existsSync(EE)) {
  console.log('boundary: no ee/ in this tree - nothing to keep out')
  process.exit(0)
}

function files(dir, test, out = [], skip = SKIP) {
  for (const name of readdirSync(dir)) {
    if (skip.has(name)) continue
    const path = join(dir, name)
    const st = statSync(path)
    if (st.isDirectory()) files(path, test, out, skip)
    else if (test(name)) out.push(path)
  }
  return out
}
const rel = p => relative(ROOT, p).replaceAll('\\', '/')

// -- what ee/ declares -----------------------------------------------------------------------
const EE_SKIP = new Set(['node_modules', 'target', 'dist'])
const eePackages = new Set()
for (const f of files(EE, n => n.endsWith('.java'), [], EE_SKIP)) {
  const m = readFileSync(f, 'utf8').match(/^\s*package\s+([\w.]+)\s*;/m)
  if (m) eePackages.add(m[1])
}
const eeArtifacts = new Set()
for (const f of files(EE, n => n === 'pom.xml', [], EE_SKIP)) {
  const pom = readFileSync(f, 'utf8').replace(/<parent>[\s\S]*?<\/parent>/, '')
  const m = pom.match(/<artifactId>([^<]+)<\/artifactId>/)
  if (m) eeArtifacts.add(m[1])
}

// A package's classes, by name: `pkg.Class`, `pkg.*`, or the package alone in a string.
const pkgPattern = new RegExp(
  `\\b(?:${[...eePackages].sort((a, b) => b.length - a.length).map(p => p.replaceAll('.', '\\.')).join('|')})(?=\\.[A-Z*]|"|;)`,
)

/** Java source with its comments blanked out and its strings kept - a string can name a class. */
function withoutComments(src) {
  let out = ''
  for (let i = 0; i < src.length; i++) {
    const c = src[i]
    if (c === '"' ) {
      // a text block or a plain literal, copied whole
      if (src.startsWith('"""', i)) {
        const end = src.indexOf('"""', i + 3)
        const stop = end < 0 ? src.length : end + 3
        out += src.slice(i, stop); i = stop - 1; continue
      }
      let j = i + 1
      while (j < src.length && src[j] !== '"' && src[j] !== '\n') { if (src[j] === '\\') j++; j++ }
      out += src.slice(i, j + 1); i = j; continue
    }
    if (c === '\'') {
      let j = i + 1
      while (j < src.length && src[j] !== '\'' && src[j] !== '\n') { if (src[j] === '\\') j++; j++ }
      out += src.slice(i, j + 1); i = j; continue
    }
    if (src.startsWith('//', i)) {
      const end = src.indexOf('\n', i)
      i = (end < 0 ? src.length : end) - 1; continue
    }
    if (src.startsWith('/*', i)) {
      const end = src.indexOf('*/', i + 2)
      const stop = end < 0 ? src.length : end + 2
      out += src.slice(i, stop).replace(/[^\n]/g, ' '); i = stop - 1; continue
    }
    out += c
  }
  return out
}

const findings = []

// -- java ------------------------------------------------------------------------------------
if (eePackages.size) {
  for (const f of files(ROOT, n => n.endsWith('.java'))) {
    const lines = withoutComments(readFileSync(f, 'utf8')).split('\n')
    lines.forEach((line, i) => {
      if (pkgPattern.test(line)) findings.push(`java      ${rel(f)}:${i + 1}: ${line.trim()}`)
    })
  }
}

// -- maven -----------------------------------------------------------------------------------
for (const f of files(ROOT, n => n === 'pom.xml')) {
  const pom = readFileSync(f, 'utf8').replace(/<!--[\s\S]*?-->/g, '')
  for (const m of pom.matchAll(/<dependency>[\s\S]*?<artifactId>([^<]+)<\/artifactId>[\s\S]*?<\/dependency>/g)) {
    if (eeArtifacts.has(m[1].trim())) findings.push(`maven     ${rel(f)}: depends on ${m[1].trim()}, which is built under ee/`)
  }
}

// -- frontend --------------------------------------------------------------------------------
const importSpec = /(?:\bfrom\s*|\bimport\s*\(\s*|\bimport\s+)(['"])([^'"]+)\1/g
for (const f of files(ROOT, n => /\.(ts|tsx|mts|js|mjs|jsx)$/.test(n))) {
  const r = rel(f)
  if (!/^(apps|libs|tests)\//.test(r)) continue
  if (/\/(vite|tailwind)\.config\.ts$/.test(r)) continue
  const src = readFileSync(f, 'utf8')
  for (const m of src.matchAll(importSpec)) {
    const spec = m[2]
    if (spec.startsWith('@ee/') || /(^|\/)ee\/frontend(\/|$)/.test(spec)) {
      findings.push(`frontend  ${r}:${src.slice(0, m.index).split('\n').length}: imports ${spec}`)
    }
  }
}

if (findings.length) {
  console.error(`boundary: ${findings.length} place(s) outside ee/ depend on Proxyma Enterprise (ee/):\n`)
  for (const f of findings) console.error(`  ${f}`)
  console.error('\nThe Community code reaches Enterprise only through its extension points - a Spring port with a')
  console.error('Community default, the frontend\'s @edition seam (apps/app/frontend/src/edition/types.ts).')
  console.error('See docs/architecture.html#oss-boundary.')
  process.exit(1)
}
console.log(`boundary: clean - ${eePackages.size} Enterprise package(s) and ${eeArtifacts.size} artifact(s), none used outside ee/`)
