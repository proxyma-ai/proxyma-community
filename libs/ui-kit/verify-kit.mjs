/**
 * Verify that this repository's generated UI-kit files have not been hand-edited.
 *
 *   node src/styles/proxyma/verify-kit.mjs
 *
 * GENERATED FILE - see the header the sync script prepends. Edit libs/ui-kit/verify-kit.mjs and
 * run `node libs/ui-kit/sync.mjs` from the repository root.
 *
 * Why a manifest as well as `sync.mjs --check`:
 *
 * `--check` compares a consumer against the kit SOURCE. This verifies a different property, one
 * that needs nothing but the consumer itself: nobody edited a generated file in place. The digests
 * were recorded when the kit was last synced. If someone changes a component here rather than in
 * libs/ui-kit, this fails and names the file; the next sync would have silently reverted their
 * work anyway. (It dates from when the four frontends were separate repositories and --check could
 * not run in their CI; both run in test:checks now, and neither implies the other.)
 *
 * It cannot detect a consumer being STALE - that a newer kit exists upstream. Only running the
 * sync does that, and being a version behind is a normal state between syncs, not an error.
 */
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// This file sits at <project>/src/styles/proxyma/, so the project root is three levels up.
// Manifest paths are stored relative to that root, which makes them identical in all four
// consumers despite two of them keeping src/ under frontend/.
const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..', '..', '..')

// Line endings are not content. git's core.autocrlf rewrites these files to CRLF on Windows
// checkouts, so hashing the raw bytes would fail for every developer on Windows and pass in
// CI - the same trap that made `sync.mjs --check` report all 92 files as drifted.
const sha = s => createHash('sha256').update(s.replace(/\r\n/g, '\n'), 'utf8').digest('hex')

const manifest = JSON.parse(await readFile(join(HERE, 'kit-manifest.json'), 'utf8'))
const bad = []

for (const [rel, expected] of Object.entries(manifest.files)) {
  let actual
  try {
    actual = sha(await readFile(join(ROOT, rel), 'utf8'))
  } catch {
    bad.push([rel, 'missing'])
    continue
  }
  if (actual !== expected) bad.push([rel, 'edited'])
}

if (bad.length) {
  console.error('\nGenerated UI-kit files were changed in this repository:\n')
  for (const [rel, why] of bad) console.error(`  ${why.padEnd(8)} ${rel}`)
  console.error(`
These files are generated from libs/ui-kit and are overwritten by the next sync, so a change
made here is lost rather than shared. To change them for real:

  1. edit the component or token in libs/ui-kit/
  2. run  node libs/ui-kit/sync.mjs  from the repository root
  3. commit the regenerated files in every consumer with it

If the change only this surface wants, it does not belong in the shared kit - put it in this
repo's own stylesheet below the shared import, or in a local component.
`)
  process.exit(1)
}

console.log(`ui-kit: ${Object.keys(manifest.files).length} generated files verified unmodified`)
