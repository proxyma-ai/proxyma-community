#!/usr/bin/env node
/**
 * Proxyma UI kit sync.
 *
 *   node libs/ui-kit/sync.mjs           copy the kit into every consumer
 *   node libs/ui-kit/sync.mjs --check   exit 1 if any consumer has drifted (for CI)
 *   node libs/ui-kit/sync.mjs --only=apps/landing
 *
 * The four frontends live in one repository but build separately, with no shared package
 * between them, so this is a copy rather than an import. That is deliberate: an npm package
 * would need a registry, a release cycle and a version bump in four package.json files for
 * every change, and unlike a wire contract, a button does not need to be versioned
 * independently of its callers.
 *
 * A FILE THE KIT NO LONGER HAS IS REMOVED from every consumer (since 2026-10-14) - only a file
 * that carries the generated header, so nothing an app wrote itself can be touched. It used to
 * stay behind, compiling and importable, until someone remembered to delete four copies by hand
 * (StatCard.tsx in the 2026-10 rebuild); `--check` reports one as drift.
 *
 * What makes the copy safe is that it is MECHANICAL. Every generated file carries a header
 * saying so, `--check` fails a build if someone edited one, and the source of truth is a
 * single directory. The previous arrangement - "copy the component file out of
 * apps/app/frontend and edit the copy" - had none of those, which is how the consoles
 * drifted the first time.
 */
import { createHash } from 'node:crypto'
import { constants } from 'node:fs'
import { access, mkdir, readFile, readdir, unlink, writeFile } from 'node:fs/promises'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const KIT_ROOT = dirname(fileURLToPath(import.meta.url))
const WORKSPACE = resolve(KIT_ROOT, '..', '..')

/**
 * `react` is where the components land, `styles` takes the CSS and the Tailwind preset.
 *
 * `clientDirective` decides what happens to the `'use client'` line at the top of the
 * interactive components. Next.js REQUIRES it; Rollup warns "Module level directives cause
 * errors when bundled, 'use client' was ignored" on every one of them, once per build, in
 * the three Vite apps. Stripping it there keeps those builds quiet without weakening the
 * landing site - the directive is meaningless in a client-only SPA bundle anyway.
 */
// `root` is the directory the manifest's paths are relative to - the one containing src/. It
// differs because two consumers keep their SPA under frontend/. verify-kit.mjs resolves the same
// directory by walking three levels up from itself, so the manifest reads identically in all four.
const TARGETS = [
  { repo: 'apps/app',       root: 'frontend', react: 'frontend/src/components/ui/kit', styles: 'frontend/src/styles/proxyma', i18n: 'frontend/src/i18n/kit', clientDirective: false },
  { repo: 'apps/license',   root: 'frontend', react: 'frontend/src/components/ui/kit', styles: 'frontend/src/styles/proxyma', i18n: 'frontend/src/i18n/kit', clientDirective: false },
  { repo: 'apps/telemetry', root: 'frontend', react: 'frontend/src/components/ui/kit', styles: 'frontend/src/styles/proxyma', i18n: 'frontend/src/i18n/kit', clientDirective: false },
  // The landing site KEEPS the 'use client' directive, and the i18n runtime is the reason it
  // matters most here: I18nProvider holds state and reads localStorage, so Next.js has to see it
  // as a client component or the build fails on useState in a server component.
  { repo: 'apps/landing',   root: '',         react: 'src/components/ui/kit',          styles: 'src/styles/proxyma',          i18n: 'src/i18n/kit',          clientDirective: true },
]

const HEADER_LINES = [
  'GENERATED FILE - DO NOT EDIT.',
  '',
  'Source: libs/ui-kit/%SRC%',
  'Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)',
  '',
  'Edits made here are silently reverted by the next sync. Change the source instead;',
  'a change that only one surface wants does not belong in the shared kit at all.',
]

function banner(srcRel, isCss) {
  const body = HEADER_LINES.map(l => l.replace('%SRC%', srcRel.replace(/\\/g, '/')))
  return isCss
    ? `/* ${'='.repeat(85)}\n   ${body.join('\n   ')}\n   ${'='.repeat(85)} */\n\n`
    : `/* ${'='.repeat(85)}\n * ${body.join('\n * ')}\n * ${'='.repeat(85)} */\n\n`
}

/**
 * The directive must remain the very first thing in the file for Next.js to honour it, so
 * the banner is inserted AFTER it rather than before.
 */
function render(source, srcRel, keepClientDirective) {
  const isCss = srcRel.endsWith('.css')
  const head = banner(srcRel, isCss)
  const directive = /^'use client'\r?\n\r?\n?/

  if (directive.test(source)) {
    return keepClientDirective
      ? source.replace(directive, m => `${m.trimEnd()}\n\n${head}`)
      : head + source.replace(directive, '')
  }
  return head + source
}

/**
 * Compare on content, never on line endings.
 *
 * git's core.autocrlf is true on Windows, so a fresh checkout writes CRLF into the working
 * tree - in the kit source AND in every consumer. render() emits LF. Comparing the raw
 * strings therefore reported all 92 files as drifted on any Windows machine immediately
 * after cloning, and a plain sync rewrote every one of them to LF for no reason.
 *
 * Writing LF is still correct: with autocrlf on, git normalises on `git add`, so a
 * LF-on-disk file against a CRLF checkout is not a diff as far as git is concerned.
 */
const normalise = s => s.replace(/\r\n/g, '\n')
const sha = s => createHash('sha256').update(normalise(s), 'utf8').digest('hex')

async function collect(dir) {
  const out = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.isFile()) out.push(entry.name)
  }
  return out.sort()
}

async function exists(p) {
  try {
    await access(p, constants.F_OK)
    return true
  } catch {
    return false
  }
}

const args = process.argv.slice(2)
const checkOnly = args.includes('--check')
const onlyArg = args.find(a => a.startsWith('--only='))
const only = onlyArg ? onlyArg.slice('--only='.length) : null

// A `.test.ts` beside a component source stays in the kit for the same reason as the i18n one
// below: appearance.test.ts runs once from here, not four times from four copies of itself.
const reactFiles = (await collect(join(KIT_ROOT, 'react'))).filter(f => !f.endsWith('.test.ts'))
const stylesFiles = await collect(join(KIT_ROOT, 'tokens'))
// The i18n RUNTIME is shared; the CATALOGS are not. Each app names its own strings and holds
// them in its own tree - a shared catalog would put the telemetry console's table headers in the
// desktop app's bundle, and force a rename in one surface to be a rename in four.
//
// Only the RUNTIME is copied. `check-catalogs.mjs` is a repository-level CI script that reads
// every app's catalogs at once, so four copies of it inside the apps it inspects would be four
// copies that can never be the one that runs; and a `.test.ts` belongs beside its source, not
// shipped into consumers that would then each run the same assertions against the same file.
const i18nFiles = (await collect(join(KIT_ROOT, 'i18n')))
  .filter(f => !f.endsWith('.test.ts') && f !== 'check-catalogs.mjs')

let written = 0
let drifted = 0
let skipped = 0

for (const target of TARGETS) {
  if (only && target.repo !== only) continue

  const repoRoot = join(WORKSPACE, target.repo)
  if (!(await exists(repoRoot))) {
    console.warn(`  skip ${target.repo} - not checked out at ${repoRoot}`)
    skipped++
    continue
  }

  const jobs = [
    ...reactFiles.map(f => ({ src: join('react', f), dest: join(target.react, f) })),
    ...stylesFiles.map(f => ({ src: join('tokens', f), dest: join(target.styles, f) })),
    ...i18nFiles.map(f => ({ src: join('i18n', f), dest: join(target.i18n, f) })),
    // The verifier ships with the kit so a consumer's CI needs nothing checked out beside it.
    { src: 'verify-kit.mjs', dest: join(target.styles, 'verify-kit.mjs') },
  ]

  // Render everything first, so the manifest can record what is about to be written rather
  // than what happens to be on disk - otherwise a run that half-fails leaves a manifest
  // describing a state that never existed.
  const pending = []
  const files = {}
  for (const job of jobs) {
    const source = await readFile(join(KIT_ROOT, job.src), 'utf8')
    const rendered = render(source, job.src, target.clientDirective)
    pending.push({ destPath: join(repoRoot, job.dest), rendered })
    // verify-kit.mjs is excluded: it is the thing doing the checking, and a file cannot
    // meaningfully attest to itself.
    if (job.src !== 'verify-kit.mjs') {
      files[relative(join(repoRoot, target.root), join(repoRoot, job.dest)).replace(/\\/g, '/')] = sha(rendered)
    }
  }

  pending.push({
    destPath: join(repoRoot, target.styles, 'kit-manifest.json'),
    rendered: JSON.stringify(
      { note: 'Generated by libs/ui-kit/sync.mjs. Verified by verify-kit.mjs.', files },
      null,
      2,
    ) + '\n',
  })

  // Generated files the kit no longer has: in a directory this script writes, carrying the header,
  // and not about to be written.
  const expected = new Set(pending.map(p => p.destPath))
  for (const dir of new Set(jobs.map(j => dirname(join(repoRoot, j.dest))))) {
    if (!(await exists(dir))) continue
    for (const name of await collect(dir)) {
      const path = join(dir, name)
      if (expected.has(path)) continue
      const text = await readFile(path, 'utf8')
      if (!text.includes(HEADER_LINES[0])) continue
      const shown = relative(WORKSPACE, path).replace(/\\/g, '/')
      if (checkOnly) {
        console.error(`  ORPHAN ${shown}`)
        drifted++
        continue
      }
      await unlink(path)
      console.log(`  removed ${shown}`)
      written++
    }
  }

  for (const { destPath, rendered } of pending) {
    const current = (await exists(destPath)) ? await readFile(destPath, 'utf8') : null
    if (current !== null && normalise(current) === normalise(rendered)) continue

    if (checkOnly) {
      console.error(`  DRIFT ${relative(WORKSPACE, destPath).replace(/\\/g, '/')}`)
      drifted++
      continue
    }

    await mkdir(dirname(destPath), { recursive: true })
    await writeFile(destPath, rendered, 'utf8')
    console.log(`  wrote ${relative(WORKSPACE, destPath).replace(/\\/g, '/')}`)
    written++
  }
}

if (checkOnly) {
  if (drifted > 0) {
    console.error(`\n${drifted} file(s) differ from libs/ui-kit. Run: node libs/ui-kit/sync.mjs`)
    process.exit(1)
  }
  console.log('ui-kit: all consumers in sync')
} else {
  console.log(`\nui-kit: ${written} file(s) written${skipped ? `, ${skipped} repo(s) skipped` : ''}`)
}
