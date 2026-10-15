// Renders PlantUML diagrams to SVG that follows the page's theme.
//
// WHY PLANTUML. The architecture document asks for real UML - class, sequence, state, component,
// deployment and activity diagrams with proper arrowheads and multiplicities - and for the layout
// to be computed rather than drawn: hand-placed SVG lines up on the day it is written and drifts the
// first time a label grows. PlantUML's MIT build runs on the JDK every build here already has, and
// its pure-Java layout engine (`!pragma layout smetana`, set in _theme.puml) needs no Graphviz.
//
// WHY SENTINEL COLORS. Every diagram includes tools/docs/architecture/diagrams/_theme.puml, which
// paints with fifteen placeholder colors (#FEFE01..#FEFE0F) instead of real ones. The page maps each
// placeholder to a theme token with the CSS in UML_THEME_CSS, so ONE inline SVG follows Light,
// Proxyma and Dark - the same three appearances the product has - without three renders. CSS beats
// SVG presentation attributes, and `!important` beats PlantUML's inline style attributes, so the
// SVG text is not rewritten color by color. A literal color anywhere else would stay fixed in
// every theme; findStrayColors() reports it and the build refuses the diagram.

import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))

export const PLANTUML_VERSION = '1.2026.8'
const GROUP_PATH = ['net', 'sourceforge', 'plantuml', 'plantuml-mit']

/** The PlantUML jar, fetched into the local Maven repository on first use. */
export function plantumlJar() {
  const jar = join(homedir(), '.m2', 'repository', ...GROUP_PATH, PLANTUML_VERSION, `plantuml-mit-${PLANTUML_VERSION}.jar`)
  if (!existsSync(jar)) {
    // shell: true because mvn is mvn.cmd on Windows, which Node refuses to spawn without a shell.
    execFileSync('mvn', ['-q', '-B', 'dependency:get', `-Dartifact=net.sourceforge.plantuml:plantuml-mit:${PLANTUML_VERSION}`], { stdio: 'inherit', shell: true })
  }
  if (!existsSync(jar)) throw new Error(`PlantUML ${PLANTUML_VERSION} could not be fetched into ${jar}`)
  return jar
}

/** Colors PlantUML may emit that are harmless: transparent hit areas and "no paint". */
const ALLOWED_LITERALS = new Set(['none', 'transparent'])

/**
 * Every literal color in the SVG that is not a sentinel, ignoring fully transparent fills (the
 * invisible hit areas PlantUML lays over sequence lifelines).
 */
export function findStrayColors(svg) {
  const stray = new Set()
  const tags = svg.match(/<[a-zA-Z][^>]*>/g) || []
  for (const tag of tags) {
    const transparent = /fill-opacity="0(\.0+)?"/.test(tag)
    const re = /(fill|stroke)(?:="|:)\s*(#[0-9a-fA-F]{3,8}|[a-zA-Z]+)/g
    let m
    while ((m = re.exec(tag))) {
      const [, prop, value] = m
      const v = value.toLowerCase()
      if (v.startsWith('#fefe')) continue
      if (ALLOWED_LITERALS.has(v)) continue
      if (prop === 'fill' && transparent) continue
      stray.add(`${prop}:${v}`)
    }
  }
  return [...stray]
}

const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

/**
 * Makes a PlantUML SVG fit to sit inline in a page: no XML prolog or comments, ids prefixed so two
 * diagrams on one page cannot collide, a fluid width between 80 % and 100 % of the drawn width, and
 * an accessible name.
 */
export function prepareSvg(svg, key, title) {
  let s = svg
    .replace(/<\?xml[^?]*\?>/g, '')
    .replace(/<\?plantuml[^?]*\?>/g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
  const vb = /viewBox="([\d.\s-]+)"/.exec(s)
  const width = vb ? Math.ceil(Number(vb[1].trim().split(/\s+/)[2])) : null
  s = s.replace(/<svg\b([^>]*)>/, (_, attrs) => {
    const kept = attrs
      .replace(/\s(width|height|style|preserveAspectRatio|zoomAndPan|contentStyleType|version)="[^"]*"/g, '')
    // Fluid down to 80 % of the drawn size and no further: below that an 11px label is unreadable,
    // so a narrower column scrolls the figure sideways (figure has overflow-x:auto) instead.
    const cap = width ? ` style="max-width:${width}px;min-width:${Math.round(width * 0.8)}px"` : ''
    return `<svg${kept} class="uml" role="img" aria-label="${esc(title)}"${cap} preserveAspectRatio="xMidYMin meet">`
  })
  s = s
    .replace(/\sid="([^"]+)"/g, ` id="${key}-$1"`)
    .replace(/url\(#([^)]+)\)/g, `url(#${key}-$1)`)
    .replace(/(xlink:)?href="#([^"]+)"/g, (_, x, id) => `${x || ''}href="#${key}-${id}"`)
  return s.trim()
}

/**
 * Renders every *.puml in `dir` (files starting with "_" are includes) and returns a Map from the
 * file's base name to prepared, theme-ready SVG. One JVM for the lot. Throws on a PlantUML error
 * (PlantUML draws its errors INTO the SVG and exits 0 for some of them, so the text is checked too)
 * and on any stray literal color.
 */
export function renderDiagrams(dir, { titles = {} } = {}) {
  const files = readdirSync(dir).filter(f => f.endsWith('.puml') && !f.startsWith('_')).sort()
  const out = new Map()
  if (!files.length) return out
  const tmp = mkdtempSync(join(tmpdir(), 'proxyma-uml-'))
  // RenderUml.java registers Space Grotesk before PlantUML measures a single label - see there.
  const run = extra => execFileSync('java', ['-Djava.awt.headless=true', '-cp', plantumlJar(), join(HERE, 'RenderUml.java'), join(HERE, '..', 'fonts'),
    '-tsvg', '-charset', 'UTF-8', ...extra, '-o', tmp, ...files.map(f => join(dir, f))], { stdio: ['ignore', 'pipe', 'pipe'] })
  try {
    run(['-failfast2'])
  } catch (e) {
    // -failfast2 says only "Some diagram description contains errors", not WHICH file. Render again
    // without it: PlantUML then draws each error into that diagram's SVG, and the checks below name
    // the file and the line.
    try { run([]) } catch (ignored) { /* the per-file checks report it */ }
  }
  const problems = []
  for (const f of files) {
    const name = basename(f, '.puml')
    const svgPath = join(tmp, `${name}.svg`)
    if (!existsSync(svgPath)) { problems.push(`${f}: no SVG produced (one @startuml per file, no name after it)`); continue }
    const raw = readFileSync(svgPath, 'utf8')
    // An error image: PlantUML's green-on-black (#33FF02) with the offending lines and the message.
    if (/Syntax Error\?|Please use CSS style instead|font-family="monospace"|#33ff02/i.test(raw)) {
      const lines = [...raw.matchAll(/<text[^>]*>([^<]*)<\/text>/g)].map(m => m[1].trim()).filter(Boolean)
      const msg = lines.length ? lines.slice(-4).join(' | ') : 'PlantUML reported a problem inside the image'
      problems.push(`${f}: ${msg}`)
      continue
    }
    const stray = findStrayColors(raw)
    if (stray.length) { problems.push(`${f}: literal colors outside the theme: ${stray.join(', ')}`); continue }
    out.set(name, prepareSvg(raw, `d-${name}`, titles[name] || name))
  }
  rmSync(tmp, { recursive: true, force: true })
  if (problems.length) throw new Error('Diagram problems:\n  ' + problems.join('\n  '))
  return out
}

/**
 * The page-level half of the theming: each sentinel to a token. Uses the kit's semantic tokens
 * (libs/ui-kit/tokens/proxyma-tokens.css), which the page inlines, so the diagrams re-color with
 * the rest of the page when the appearance changes.
 */
export const UML_THEME_CSS = (() => {
  const map = {
    FEFE01: 'rgb(var(--surface))',                  // node fill
    FEFE02: 'rgb(var(--ink-caption) / 0.55)',       // node border
    FEFE03: 'rgb(var(--ink-primary))',              // primary text
    FEFE04: 'rgb(var(--ink-caption))',              // arrows and lines
    FEFE05: 'rgb(var(--ink-secondary))',            // secondary text
    FEFE06: 'rgb(var(--brand-solid))',              // emphasis fill
    FEFE07: 'rgb(var(--brand-on-solid))',           // text on emphasis
    FEFE08: 'rgb(var(--surface-alt) / 0.45)',       // container fill
    FEFE09: 'rgb(var(--line))',                     // container border
    FEFE0A: 'rgb(var(--warning) / 0.10)',           // note fill
    FEFE0B: 'rgb(var(--warning) / 0.55)',           // note border
    FEFE0C: 'rgb(var(--ink-caption))',              // stereotype and caption text
    FEFE0D: 'rgb(var(--brand-accent))',             // emphasis border
    FEFE0E: 'rgb(var(--ink-caption) / 0.6)',        // lifeline
    FEFE0F: 'rgb(var(--canvas))',                   // external element fill
  }
  const rules = []
  for (const [hex, value] of Object.entries(map)) {
    const h = `#${hex}`
    rules.push(`.uml [fill="${h}" i]{fill:${value}!important}`)
    rules.push(`.uml [stroke="${h}" i]{stroke:${value}!important}`)
    rules.push(`.uml [style*="fill:${h}" i]{fill:${value}!important}`)
    rules.push(`.uml [style*="stroke:${h}" i]{stroke:${value}!important}`)
  }
  rules.push('.uml{display:block;width:100%;height:auto;margin:0 auto}')
  rules.push('.uml text{font-family:var(--font-ui)}')
  return rules.join('\n')
})()

export { mkdirSync }
