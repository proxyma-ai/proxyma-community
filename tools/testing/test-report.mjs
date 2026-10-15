// Turns the XML nobody reads into one HTML page somebody will.
//
// Surefire and failsafe both write TEST-*.xml per class, and between them they hold everything
// worth knowing about a run - and in a form that is unreadable by design: one file per class,
// attributes for the counts, stack traces inline. `mvn` prints a summary that scrolls past, and
// GitLab's JUnit tab shows failures well and passes not at all, so "what does this suite actually
// cover" has never had an answer anybody could look at.
//
//   node tools/testing/test-report.mjs
//   node tools/testing/test-report.mjs --out reports/tests --title "0.14.2 release check"
//
// WHAT IT READS. Every **/target/surefire-reports/TEST-*.xml and **/target/failsafe-reports/
// TEST-*.xml under the repository, so one page covers the unit suite AND the functional suite.
// A module that was not run simply is not there.
//
// WHERE THE READABLE NAMES COME FROM. The XML carries the CLASS-level @DisplayName (as
// `classname`, confusingly) and not the method-level one - so `aRateLimitSurfaces` is all it has,
// while the source says "a rate limit reaches the user as an error, not as a silent stall". The
// sentences are the point of writing them, so the source is scanned for @DisplayName and the
// method name is the fallback, de-camel-cased.
//
// STYLE. It mirrors the landing site's API docs page - the same token file inlined verbatim, the
// same 13rem sticky left panel, the same heading scale and content width. That is not decoration:
// this is a page the maintainer reads beside the docs, and a report that looked like a different
// product would read as somebody else's tool.

import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync, mkdirSync, readdirSync, statSync, existsSync } from 'node:fs'
import { join, dirname, basename, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const args = process.argv.slice(2)
const flag = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : fallback
}

const REPO = (() => {
  let dir = dirname(fileURLToPath(import.meta.url))
  while (dir && !existsSync(join(dir, 'CLAUDE.md'))) dir = dirname(dir)
  if (!dir) throw new Error('could not find the repository root')
  return dir
})()

const OUT_DIR = join(REPO, flag('out', 'reports/tests'))
const TITLE = flag('title', 'Test report')

// ---------------------------------------------------------------------------------------------
// Finding the reports
// ---------------------------------------------------------------------------------------------

// Walks the tree for report directories, skipping the places that are large and cannot contain
// them. node_modules is the expensive one: without it this walk takes seconds rather than
// milliseconds, on a repository with four frontends.
const SKIP = new Set(['node_modules', '.git', '.next', 'out', 'dist', 'browsers', 'scratch'])

function findReportDirs(dir, found = []) {
  let entries
  try {
    entries = readdirSync(dir, { withFileTypes: true })
  } catch {
    return found
  }
  for (const entry of entries) {
    if (!entry.isDirectory() || SKIP.has(entry.name)) continue
    const full = join(dir, entry.name)
    if (entry.name === 'surefire-reports' || entry.name === 'failsafe-reports') {
      found.push(full)
      continue
    }
    findReportDirs(full, found)
  }
  return found
}

// ---------------------------------------------------------------------------------------------
// Reading the XML
//
// Hand-parsed rather than with a dependency, because the shape is fixed and narrow: surefire has
// written this schema for twenty years and it is one element deep. What it is NOT is general XML
// parsing - a stack trace can contain anything, so the only structure trusted here is the tag
// boundaries the writer itself emits.
// ---------------------------------------------------------------------------------------------

const unescapeXml = s => String(s ?? '')
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
  .replace(/&apos;/g, "'").replace(/&#10;/g, '\n').replace(/&#13;/g, '\r')
  .replace(/&#9;/g, '\t').replace(/&amp;/g, '&')

const attr = (tag, name) => {
  const m = tag.match(new RegExp(`\\b${name}="([^"]*)"`))
  return m ? unescapeXml(m[1]) : null
}

function parseSuite(file) {
  const xml = readFileSync(file, 'utf8')
  const openTag = xml.match(/<testsuite\b[^>]*>/)
  if (!openTag) return null

  const suite = {
    className: attr(openTag[0], 'name') ?? basename(file),
    displayName: null,
    time: Number(attr(openTag[0], 'time') ?? 0),
    file,
    tests: [],
  }

  // Each <testcase>, self-closing or with children. The lazy body match stops at the next
  // </testcase>, which is the only nesting this schema has.
  const caseRe = /<testcase\b([^>]*?)(\/>|>([\s\S]*?)<\/testcase>)/g
  let m
  while ((m = caseRe.exec(xml)) !== null) {
    const head = m[1]
    const body = m[3] ?? ''
    const name = attr(head, 'name') ?? '?'
    // `classname` carries the class-level @DisplayName when there is one, and the FQCN when
    // there is not. Either way it is the same for every case in the file.
    suite.displayName ??= attr(head, 'classname')

    let status = 'passed'
    let message = null
    let detail = null
    for (const kind of ['failure', 'error', 'skipped']) {
      const re = new RegExp(`<${kind}\\b([^>]*?)(\\/>|>([\\s\\S]*?)<\\/${kind}>)`)
      const hit = body.match(re)
      if (!hit) continue
      status = kind === 'skipped' ? 'skipped' : 'failed'
      message = attr(hit[1], 'message')
      detail = unescapeXml((hit[3] ?? '').replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')).trim()
      break
    }
    suite.tests.push({ name, time: Number(attr(head, 'time') ?? 0), status, message, detail })
  }
  return suite
}

// ---------------------------------------------------------------------------------------------
// The readable method names, from the source
// ---------------------------------------------------------------------------------------------

function collectDisplayNames() {
  const byKey = new Map()
  const walk = dir => {
    let entries
    try {
      entries = readdirSync(dir, { withFileTypes: true })
    } catch {
      return
    }
    for (const entry of entries) {
      if (SKIP.has(entry.name) || entry.name === 'target') continue
      const full = join(dir, entry.name)
      if (entry.isDirectory()) { walk(full); continue }
      if (!entry.name.endsWith('.java')) continue
      if (!full.includes(`${'src'}${'/'}test`) && !full.includes('src\\test')) continue
      const source = readFileSync(full, 'utf8')
      const simple = entry.name.replace(/\.java$/, '')
      // @DisplayName("...") then, allowing annotations and whitespace between, `void method(`.
      // THE GAP IS TEMPERED, and that is the whole correctness of this. A class carries its own
      // @DisplayName, and the next `void` after it is the class's FIRST TEST METHOD - so a naive
      // gap hands every suite's class name to its first test, AND consumes that test's real
      // @DisplayName on the way, so the right one never matches at all. It showed up as one row
      // per suite labelled with the suite's own title, which is exactly the kind of wrong that
      // reads as intentional. Refusing class/interface/another @DisplayName inside the gap stops
      // the match at whatever the annotation was really attached to.
      // The quoted argument is escape-aware, because two of these really do contain an escaped
      // quote - SensitivePathsTest's "[redacted]" and GeminiJsonToolResponsesTest's "". A plain
      // [^"]* stops at the first inner quote and truncates the sentence mid-word.
      const re = /@DisplayName\(\s*"((?:[^"\\]|\\.)*)"\s*\)((?:(?!\bclass\b|\binterface\b|@DisplayName)[\s\S]){0,400}?)\bvoid\s+(\w+)\s*\(/g
      let m
      while ((m = re.exec(source)) !== null) {
        byKey.set(`${simple}#${m[3]}`, m[1].replace(/\\"/g, '"').replace(/\\n/g, ' '))
      }
    }
  }
  walk(join(REPO, 'tests'))
  walk(join(REPO, 'apps'))
  walk(join(REPO, 'libs'))
  return byKey
}

// `aRateLimitSurfaces` -> `a rate limit surfaces`. The fallback, and a decent one: these names are
// written as sentences already, which is why they read at all once the humps are spaces.
const humanize = name => name
  .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
  .replace(/_/g, ' ')
  .split(' ')
  // Lowercase what the humps created, but leave an all-caps word alone: these names carry real
  // acronyms - SPA, MCP, DPA, JWKS, BM25 - and "the dpa inside the jar" reads as a typo.
  .map(word => (word.length > 1 && word === word.toUpperCase()) ? word : word.toLowerCase())
  .join(' ')

// ---------------------------------------------------------------------------------------------
// HTML
// ---------------------------------------------------------------------------------------------

const esc = s => String(s ?? '')
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;')

const slug = s => 's-' + createHash('sha1').update(String(s)).digest('hex').slice(0, 10)

const duration = seconds => {
  if (seconds < 1) return `${Math.round(seconds * 1000)} ms`
  if (seconds < 60) return `${seconds.toFixed(1)} s`
  const m = Math.floor(seconds / 60)
  return `${m}m ${Math.round(seconds - m * 60)}s`
}

function main() {
  const dirs = findReportDirs(REPO)
  const suites = []
  for (const dir of dirs) {
    for (const file of readdirSync(dir)) {
      if (!file.startsWith('TEST-') || !file.endsWith('.xml')) continue
      const suite = parseSuite(join(dir, file))
      // A suite with NO testcases is kept, not dropped. It means every test in it was aborted
      // before it ran - a missing prerequisite, usually - and a report that silently omitted it
      // would read as if the class did not exist. Absence is the one thing a report must never
      // express by being absent.
      if (!suite) continue
      suite.kind = dir.endsWith('failsafe-reports') ? 'Functional' : 'Unit'
      suite.module = relative(REPO, dir).split(/[\\/]/).slice(0, -2).join('/') || '.'
      suites.push(suite)
    }
  }

  if (suites.length === 0) {
    console.error('No TEST-*.xml anywhere under ' + REPO
      + '\nRun the tests first: mvn -f tests/pom.xml verify, or mvn test')
    process.exit(1)
  }

  const names = collectDisplayNames()
  for (const suite of suites) {
    const simple = suite.className.split('.').pop()
    for (const test of suite.tests) {
      test.label = names.get(`${simple}#${test.name}`) ?? humanize(test.name)
    }
    // A suite whose classname attribute was the FQCN has no class-level @DisplayName; show the
    // simple name rather than the package, which is noise in a heading.
    if (!suite.displayName || suite.displayName.includes('.')) suite.displayName = simple
  }

  // Functional first: it is the one somebody is reading the report FOR. Within a group, the
  // suites with failures come first - a report that buries them under forty green ones is a
  // report that gets skimmed.
  const order = { Functional: 0, Unit: 1 }
  suites.sort((a, b) =>
    (order[a.kind] - order[b.kind])
    || (failed(b).length - failed(a).length)
    || a.displayName.localeCompare(b.displayName))

  const total = suites.reduce((n, s) => n + s.tests.length, 0)
  const failures = suites.reduce((n, s) => n + failed(s).length, 0)
  const skipped = suites.reduce((n, s) => n + s.tests.filter(t => t.status === 'skipped').length, 0)
  const passed = total - failures - skipped
  const seconds = suites.reduce((n, s) => n + s.time, 0)

  const groups = [...new Set(suites.map(s => s.kind))]
    .map(kind => ({ kind, suites: suites.filter(s => s.kind === kind) }))

  const tokens = readFileSync(
    join(REPO, 'apps/landing/src/styles/proxyma/proxyma-tokens.css'), 'utf8')

  mkdirSync(OUT_DIR, { recursive: true })
  const out = join(OUT_DIR, 'index.html')
  writeFileSync(out, page({ TITLE, tokens, groups, total, passed, failures, skipped, seconds }), 'utf8')

  console.log(`${out}`)
  console.log(`  ${total} tests - ${passed} passed, ${failures} failed, ${skipped} skipped`
    + ` in ${duration(seconds)} across ${suites.length} suites`)
}

const failed = suite => suite.tests.filter(t => t.status === 'failed')

function page({ TITLE, tokens, groups, total, passed, failures, skipped, seconds }) {
  const verdict = failures > 0 ? 'failed' : 'passed'
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${esc(TITLE)} - Proxyma</title>
<style>
/* ---------------------------------------------------------------------------------------------
   The design tokens, INLINED VERBATIM from apps/landing/src/styles/proxyma/proxyma-tokens.css.
   Copied at generation time rather than transcribed, so the colours here can never drift from the
   ones the product and the site use. That file is plain CSS with :root blocks and no Tailwind
   directives, which is the whole reason this works in a standalone page.
   --------------------------------------------------------------------------------------------- */
${tokens}

/* The report's own styles, mirroring the API docs page: same heading scale (globals.css
   .docs-prose), same 13rem sticky left panel, same max-w-5xl content width. */
*, *::before, *::after { box-sizing: border-box; }
html { color-scheme: dark; }
html[data-mode="light"] { color-scheme: light; }
body {
  margin: 0;
  background: rgb(var(--canvas));
  color: rgb(var(--ink-primary));
  font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  line-height: 1.6;
}

/* The header's inner width is the content width - the one rule every Proxyma page follows. */
.bar {
  position: sticky; top: 0; z-index: 10;
  background: rgb(var(--canvas) / 0.92);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid rgb(var(--line));
}
.bar-inner, .wrap { max-width: 64rem; margin: 0 auto; padding-left: 1rem; padding-right: 1rem; }
@media (min-width: 640px) { .bar-inner, .wrap { padding-left: 1.5rem; padding-right: 1.5rem; } }
.bar-inner { height: 3.5rem; display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.bar strong { font-size: 0.875rem; font-weight: 600; }
.bar .meta { font-size: 0.75rem; color: rgb(var(--ink-caption)); }

.wrap { padding-top: 3.5rem; padding-bottom: 5rem; }
@media (min-width: 1024px) {
  .layout { display: grid; grid-template-columns: 13rem minmax(0, 1fr); gap: 2.5rem; }
}

/* Left panel - DocToc.tsx: sticky under the 3.5rem header, its own scroll, collapsible sections. */
.toc { display: none; }
@media (min-width: 1024px) {
  .toc {
    display: block; position: sticky; top: 5rem; align-self: start;
    max-height: calc(100vh - 6rem); overflow-y: auto; padding-right: 1rem;
  }
}
.toc-label {
  font-size: 0.625rem; text-transform: uppercase; letter-spacing: 0.08em;
  color: rgb(var(--ink-caption)); margin: 0 0 0.75rem;
}
.toc ul { list-style: none; padding: 0; margin: 0; font-size: 0.875rem; }
.toc li { margin: 0 0 0.125rem; }
.toc a {
  display: block; padding: 0.25rem 0; text-decoration: none; overflow-wrap: break-word;
  color: rgb(var(--ink-secondary)); border-radius: 0.125rem;
}
.toc a:hover { color: rgb(var(--brand-legible)); }
.toc .sub { padding-left: 1.125rem; margin-left: 0.375rem; border-left: 1px solid rgb(var(--line) / 0.7); }
.toc .sub a { padding-left: 0.75rem; font-size: 0.8125rem; color: rgb(var(--ink-caption)); }
.toc details > summary {
  list-style: none; cursor: pointer; display: flex; align-items: center; gap: 0.25rem;
  color: rgb(var(--ink-secondary)); font-size: 0.875rem; padding: 0.25rem 0;
}
.toc details > summary::-webkit-details-marker { display: none; }
.toc details > summary::before {
  content: ""; width: 0; height: 0; flex: none;
  border-left: 4px solid currentColor; border-top: 3.5px solid transparent; border-bottom: 3.5px solid transparent;
  transition: transform 0.15s;
}
.toc details[open] > summary::before { transform: rotate(90deg); }
.toc details > summary:hover { color: rgb(var(--brand-legible)); }
.toc .bad { color: rgb(var(--danger)); }

/* Headings - globals.css .docs-prose */
h1 { font-size: 1.875rem; font-weight: 700; margin: 0 0 0.5rem; }
h2 { font-size: 1.25rem; font-weight: 700; margin: 2.5rem 0 0.75rem; scroll-margin-top: 5rem; }
h3 { font-size: 1rem; font-weight: 600; margin: 1.5rem 0 0.5rem; scroll-margin-top: 5rem; }
p { color: rgb(var(--ink-secondary)); font-size: 0.875rem; margin: 0 0 1rem; }
.lede { font-size: 1rem; color: rgb(var(--ink-caption)); margin-bottom: 2.5rem; }
code {
  font-size: 0.85em; background: rgb(var(--surface)); border: 1px solid rgb(var(--line) / 0.6);
  border-radius: 0.25rem; padding: 0.125rem 0.375rem; color: rgb(var(--ink-primary));
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
pre {
  background: rgb(var(--surface)); border: 1px solid rgb(var(--line) / 0.6);
  border-radius: 0.375rem; padding: 1rem; margin: 0 0 1rem; overflow-x: auto;
  font-size: 0.8125rem; line-height: 1.5;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: rgb(var(--ink-secondary)); white-space: pre-wrap; overflow-wrap: anywhere;
}

/* The verdict strip, and the four counts. */
.verdict {
  display: flex; align-items: baseline; gap: 0.75rem; flex-wrap: wrap;
  border-left: 2px solid rgb(var(--brand-solid)); background: rgb(var(--brand-solid) / 0.07);
  border-radius: 0 0.375rem 0.375rem 0; padding: 1rem 1.25rem; margin-bottom: 2rem;
}
.verdict.bad { border-left-color: rgb(var(--danger)); background: rgb(var(--danger) / 0.07); }
.verdict .headline { font-size: 1rem; font-weight: 600; color: rgb(var(--ink-primary)); }
.counts { display: flex; gap: 1.5rem; flex-wrap: wrap; margin: 0 0 2rem; padding: 0; list-style: none; }
.counts li { font-size: 0.875rem; color: rgb(var(--ink-caption)); }
.counts b { display: block; font-size: 1.5rem; font-weight: 700; color: rgb(var(--ink-primary)); line-height: 1.2; }

table { width: 100%; font-size: 0.875rem; border-collapse: collapse; margin-bottom: 1.5rem; }
th { text-align: left; font-weight: 600; color: rgb(var(--ink-primary));
     border-bottom: 1px solid rgb(var(--line)); padding: 0.5rem 0.75rem; white-space: nowrap; }
td { color: rgb(var(--ink-secondary)); border-bottom: 1px solid rgb(var(--line) / 0.5);
     padding: 0.5rem 0.75rem; vertical-align: top; }
td.num { text-align: right; white-space: nowrap; color: rgb(var(--ink-caption)); font-variant-numeric: tabular-nums; }

.pill { display: inline-block; font-size: 0.6875rem; font-weight: 600; letter-spacing: 0.02em;
        border-radius: 999px; padding: 0.0625rem 0.5rem; white-space: nowrap; }
.pill.ok   { color: rgb(var(--success)); background: rgb(var(--success) / 0.14); }
.pill.bad  { color: rgb(var(--danger));  background: rgb(var(--danger) / 0.14); }
.pill.skip { color: rgb(var(--warning)); background: rgb(var(--warning) / 0.14); }

.suite-meta { font-size: 0.8125rem; color: rgb(var(--ink-caption)); margin: -0.25rem 0 0.75rem; }
details.fail { border: 1px solid rgb(var(--line)); border-radius: 0.375rem; margin: 0 0 0.75rem; background: rgb(var(--surface) / 0.5); }
details.fail > summary { cursor: pointer; padding: 0.625rem 0.875rem; font-size: 0.875rem;
                         font-weight: 600; color: rgb(var(--ink-primary)); }
details.fail > div { padding: 0 0.875rem 0.875rem; }
.msg { color: rgb(var(--ink-primary)); font-size: 0.875rem; margin: 0 0 0.5rem; overflow-wrap: anywhere; }

@media print {
  .bar, .toc { display: none; }
  details { open: true; }
  body { background: #fff; color: #000; }
}
</style>
</head>
<body>
<script>
  // System appearance, the same axis the product's own System setting resolves on. Inline and
  // before paint, so the page does not flash the wrong mode on a light desktop.
  try {
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) {
      document.documentElement.setAttribute('data-mode', 'light')
    }
  } catch (e) { /* a browser without matchMedia gets the default */ }
</script>

<header class="bar">
  <div class="bar-inner">
    <strong>Proxyma &middot; ${esc(TITLE)}</strong>
    <span class="meta">${esc(new Date().toISOString().replace('T', ' ').slice(0, 19))} UTC</span>
  </div>
</header>

<div class="wrap">
  <div class="layout">
    <nav class="toc" aria-label="On this page">
      <p class="toc-label">On this page</p>
      <ul>
        <li><a href="#summary">Summary</a></li>
        ${groups.map(group => `
        <li>
          <details open>
            <summary>${esc(group.kind)}</summary>
            <ul class="sub">
              ${group.suites.map(s => `<li><a href="#${slug(s.className)}"${failed(s).length ? ' class="bad"' : ''}>${esc(s.displayName)}</a></li>`).join('\n              ')}
            </ul>
          </details>
        </li>`).join('')}
      </ul>
    </nav>

    <main>
      <h1 id="summary">${esc(TITLE)}</h1>
      <p class="lede">Every automated check this repository ran, in one page: the unit suite that
      leads each pipeline and the functional suite that drives the packaged apps over HTTP and
      through a browser.</p>

      <div class="verdict${failures > 0 ? ' bad' : ''}">
        <span class="headline">${failures > 0
          ? `${failures} test${failures === 1 ? '' : 's'} failed`
          : 'Everything passed'}</span>
        <span style="font-size:0.875rem;color:rgb(var(--ink-caption))">
          ${total} tests across ${groups.reduce((n, g) => n + g.suites.length, 0)} suites, ${esc(duration(seconds))}
        </span>
      </div>

      <ul class="counts">
        <li><b>${total}</b>tests</li>
        <li><b>${passed}</b>passed</li>
        <li><b>${failures}</b>failed</li>
        <li><b>${skipped}</b>skipped</li>
      </ul>

      ${failures > 0 ? `
      <h2 id="failures">What failed</h2>
      ${groups.flatMap(g => g.suites).flatMap(s => failed(s).map(t => `
      <details class="fail" open>
        <summary>${esc(s.displayName)} &mdash; ${esc(t.label)}</summary>
        <div>
          ${t.message ? `<p class="msg">${esc(t.message)}</p>` : ''}
          ${t.detail ? `<pre>${esc(t.detail)}</pre>` : ''}
        </div>
      </details>`)).join('')}` : ''}

      ${groups.map(group => `
      <h2 id="${slug(group.kind)}">${esc(group.kind)}</h2>
      <p>${group.kind === 'Functional'
          ? 'Boots the packaged jars and drives all four apps the way a user does - over HTTP and through a real Chromium, against a fake OpenAI-compatible provider. Runs nightly, on every release tag before the deploys, and on a button.'
          : 'Pure tests beside the code they cover, with no application started. Runs on every push, in about thirty seconds.'}</p>
      ${group.suites.map(s => suiteSection(s)).join('')}
      `).join('')}

      <h2 id="about">How this page was made</h2>
      <p>Generated by <code>tools/testing/test-report.mjs</code> from the
      <code>TEST-*.xml</code> that surefire and failsafe write, plus the
      <code>@DisplayName</code> sentences read out of the test sources - the XML carries only the
      class-level ones. It styles itself from the landing site's own token file, inlined verbatim,
      so the colors cannot drift from the product's.</p>
      <p><code>docs/development.html</code>, "Test", is what the suites are for, how to run them and
      what they deliberately do not cover.</p>
    </main>
  </div>
</div>
</body>
</html>
`
}

function suiteSection(suite) {
  const bad = failed(suite)
  const skips = suite.tests.filter(t => t.status === 'skipped')
  return `
      <h3 id="${slug(suite.className)}">${esc(suite.displayName)}</h3>
      <p class="suite-meta">
        <code>${esc(suite.className)}</code> &middot; ${suite.tests.length} test${suite.tests.length === 1 ? '' : 's'}
        &middot; ${esc(duration(suite.time))}${bad.length ? ` &middot; ${bad.length} failed` : ''}${skips.length ? ` &middot; ${skips.length} skipped` : ''}
      </p>
      ${suite.tests.length === 0 ? `<p>Nothing ran. Every test in this suite was aborted before
      it started, which means a prerequisite was missing - Docker, a built jar, or the landing
      site's static export. <code>PROXYMA_IT_CI=1</code> turns that into a failure in the
      pipeline, so this can only be seen on a developer machine.</p>` : `
      <table>
        <thead><tr><th>What it checks</th><th>Result</th><th class="num">Time</th></tr></thead>
        <tbody>
        ${suite.tests.map(t => `<tr>
          <td>${esc(t.label)}</td>
          <td><span class="pill ${t.status === 'passed' ? 'ok' : t.status === 'skipped' ? 'skip' : 'bad'}">${t.status}</span></td>
          <td class="num">${esc(duration(t.time))}</td>
        </tr>`).join('\n        ')}
        </tbody>
      </table>`}`
}

main()
