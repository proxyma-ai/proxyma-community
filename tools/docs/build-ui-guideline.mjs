// Builds docs/ui-guideline.html - Proxyma's UI design guideline - from the UI kit itself.
//
//   node tools/docs/build-ui-guideline.mjs           write docs/ui-guideline.html
//   node tools/docs/build-ui-guideline.mjs --check   exit 1 if the committed file is out of date
//
// Needs `npm ci` in apps/app/frontend: the kit is rendered with the app's own React, Tailwind and
// esbuild, and nothing is added to any package.json.
//
// Sources, all read at build time:
//   apps/app/frontend/src/dev/kitRegistry.ts    every component: purpose, use, avoid, props
//   apps/app/frontend/src/dev/kitSpecimens.tsx  the gallery's specimens (/__kit), drawn here too
//   apps/app/frontend/src/dev/kitTokens.ts      the token tables
//   libs/ui-kit/tokens/proxyma-tokens.css       the token values, per appearance
//   libs/ui-kit/react/index.ts + sources        the API index; BrandMark.tsx's sources and terms
//   tools/docs/ui-guideline/pictures.tsx        the guideline's own pictures, and dialogs drawn open
//   tools/docs/ui-guideline/sections.mjs        the words
//
// EVERY SPECIMEN IS THE KIT'S OWN MARKUP, rendered with react-dom/server and styled by the kit's
// compiled CSS (Tailwind over exactly that markup, scoped under `.kit`) - a live page in all four
// appearances, not screenshots. The page around it is tools/docs/lib/doc-page.mjs, the shell the
// architecture document uses, with its theme in a cascade layer so the kit's CSS always wins
// inside a specimen and never reaches outside one.
//
// The output is GENERATED and committed, like docs/architecture.html. Edit the sources, run this,
// commit both.

import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { REPO, renderDocPage } from './lib/doc-page.mjs'
import { APP, SCOPE, bundleKit, kitCss } from './lib/kit-render.mjs'
import { apiIndex, componentEntry, componentsIntro, esc, extending, guidelineBody, md } from './ui-guideline/sections.mjs'
import { kitExports, tokenBlocks } from './ui-guideline/sources.mjs'

const OUT = join(REPO, 'docs', 'ui-guideline.html')
const check = process.argv.includes('--check')
const DEV = join(APP, 'src', 'dev')

const kit = await bundleKit({
  REGISTRY: join(DEV, 'kitRegistry.ts'),
  SPECIMENS: join(DEV, 'kitSpecimens.tsx'),
  TOKENS: join(DEV, 'kitTokens.ts'),
  GUIDE: join(REPO, 'tools', 'docs', 'ui-guideline', 'pictures.tsx'),
  TAILWIND: join(APP, 'tailwind.config.ts'),
})

// -- rendering ------------------------------------------------------------------------------------

const rendered = []   // markup, in placeholder order
const failures = []

function renderNode(node, label) {
  const prefix = `k${rendered.length}-`
  const errors = []
  const original = console.error
  console.error = (...args) => { errors.push(args.map(String).join(' ')) }
  try {
    return kit.render(node, prefix)
  } catch (err) {
    failures.push(`${label}: ${err.message}`)
    return ''
  } finally {
    console.error = original
    for (const e of new Set(errors)) console.warn(`warning: ${label}: ${e.split('\n')[0]}`)
  }
}

/** Renders a picture now and returns the placeholder the page carries until the end. */
function place(markup) {
  // ASCII out, like every other file here: a middle dot or a no-break space as a character reference.
  rendered.push(markup.replace(/[^\x00-\x7f]/gu, c => `&#${c.codePointAt(0)};`))
  return `<!--kit:${rendered.length - 1}-->`
}

function specimen({ title, render, wide, stage }, label) {
  const markup = renderNode(render(), label)
  const body = stage
    ? `<div class="${SCOPE} gl-stage" style="height:${Number(stage)}px">${markup}</div>`
    : `<div class="${SCOPE} gl-frame"><div class="flex flex-wrap items-start gap-3 min-w-0">${markup}</div></div>`
  return `<div class="gl-spec${wide ? ' gl-wide' : ''}"><p class="gl-spec-title">${esc(title)}</p>${place(body)}</div>`
}

/** The callback sections.mjs places pictures with: a guide picture, or one brand mark inline. */
function picture(pic) {
  if (pic && pic.mark) return place(`<span class="${SCOPE} gl-inline">${renderNode(kit.GUIDE.markPicture(pic.mark), `mark ${pic.mark}`)}</span>`)
  if (!pic) throw new Error('a section names a picture pictures.tsx does not have')
  return specimen(pic, pic.title)
}

// Components whose picture is a closed trigger: what the reader is told under them.
const OPENS = new Set(['Select', 'Combobox', 'DateTimePicker', 'Tooltip', 'SubmenuRow', 'AppearanceMenuRow', 'LanguageMenuRow', 'ActionMenu',
  'IconMenu', 'MobileMenu', 'UserMenu', 'AppearanceMenu', 'LanguageMenu', 'FadeSwap', 'Presence', 'ErrorBoundary', 'FilePickerButton'])
const SHELL_OF = { ActionMenu: 'Popover', IconMenu: 'MenuSurface', MobileMenu: 'MenuSurface', UserMenu: 'MenuSurface', AppearanceMenu: 'MenuSurface', LanguageMenu: 'MenuSurface', AppearanceMenuRow: 'MenuSurface', LanguageMenuRow: 'MenuSurface' }

function noteFor(name) {
  if (name === 'AppHeader' || name === 'AutoHideHeader') return 'Its padding follows the window, not its box: from `xl` the edge controls sit in the page margin, which a frame narrower than the window does not have.'
  if (kit.GUIDE.OPEN[name]) return 'Opens on use: the first picture is its closed trigger; the last is drawn open.'
  if (SHELL_OF[name]) return `Opens on use: the picture is its trigger. Its panel is ${SHELL_OF[name]}'s, drawn open under [${SHELL_OF[name]}](#${SHELL_OF[name]}); try it live in the gallery, \`/__kit#${name}\`.`
  if (OPENS.has(name)) return `Interactive: the picture is its resting state. Try it live in the gallery, \`/__kit#${name}\`.`
  return null
}

// -- the body -----------------------------------------------------------------------------------

const R = kit.REGISTRY
const parts = [guidelineBody({ repo: REPO, kit, picture })]
parts.push(componentsIntro(kit))
let specimenCount = 0
for (const g of R.KIT_GROUPS) {
  parts.push(`<h2 id="group-${g.id}">${esc(g.title)}</h2>`)
  parts.push(`<p class="lede-sm">${md(g.summary)} <span class="badge">${esc(g.level)}</span></p>`)
  for (const e of R.KIT_REGISTRY.filter(x => x.group === g.id)) {
    const list = [...(kit.SPECIMENS.SPECIMENS[e.name] ?? []), ...(kit.GUIDE.OPEN[e.name] ?? [])]
    specimenCount += list.length
    const html = list.map((s, i) => specimen(s, `${e.name} #${i + 1}`)).join('')
    let note = noteFor(e.name)
    // A markdown-ish link in a note: [text](#anchor).
    parts.push(componentEntry(e, html, { note }).replace(/\[([^\]]+)\]\((#[\w-]+)\)/g, '<a href="$2">$1</a>'))
  }
}
parts.push(apiIndex(REPO, kit))
parts.push(extending())
const body = parts.join('\n\n')

if (failures.length) {
  console.error(`${failures.length} specimen(s) failed to render:\n  ${failures.join('\n  ')}`)
  process.exit(1)
}

// -- CSS ----------------------------------------------------------------------------------------

const tokens = tokenBlocks(REPO)
const appearanceBoxes = ['light', 'gray', 'black'].map(ap =>
  `.gl-ap-${ap}{${tokens.overridden.map(v => `--${v}:${tokens[ap][v]}`).join(';')}}`).join('\n')

const GUIDE_CSS = `
/* The guideline's own layout. Its rules name gl-* classes only, so none reaches into a specimen. */
/* The column is the theme's - the site's page column, every block its full width (doc-theme.css). */
.lede-sm{color:rgb(var(--ink-caption)); font-size:14px}
.gl-entry{margin:0 0 56px}
.gl-meta{margin:0 0 8px; font-size:13px; color:rgb(var(--ink-caption)); display:flex; flex-wrap:wrap; gap:6px; align-items:center}
.gl-purpose{font-size:16px; line-height:1.625; color:rgb(var(--ink-primary)); max-width:92ch}
.gl-useavoid{display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:4px 32px; max-width:120ch}
.gl-useavoid ul{font-size:14px}
.gl-label{font-size:14px; line-height:20px; font-weight:500; color:rgb(var(--ink-primary)); margin:0 0 4px}
.gl-related,.gl-note{font-size:13px; color:rgb(var(--ink-caption))}
.gl-note{border-left:2px solid rgb(var(--brand-accent)); padding-left:10px}
.gl-req{color:rgb(var(--danger)); margin-left:2px}
.gl-props td:nth-child(2) code{white-space:normal; overflow-wrap:anywhere}
.gl-props td:first-child{white-space:nowrap}
.gl-specs{display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:22px 28px; margin-top:18px}
.gl-spec{min-width:0; margin:0 0 22px}
.gl-specs > .gl-spec{margin:0}
.gl-spec.gl-wide{grid-column:1 / -1}
.gl-spec-title{margin:0 0 6px; font-size:13px; line-height:18px; font-weight:500; color:rgb(var(--ink-caption))}
.gl-spec > .gl-frame{border:1px solid rgb(var(--line) / .7); border-radius:8px; padding:18px; overflow-x:auto; min-width:0}
.gl-spec > .gl-stage{position:relative; transform:translateZ(0); overflow:hidden; border:1px solid rgb(var(--line) / .7); border-radius:8px; padding:0}
.gl-two{display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 28px}
.gl-two > .gl-spec{min-width:0}
.gl-sw{display:inline-block; width:26px; height:18px; border-radius:4px; border:1px solid rgb(var(--ink-caption) / .45); vertical-align:middle}
.gl-sw-lg{display:block; width:100%; height:44px; border-radius:6px; margin-bottom:4px}
.gl-hex{font-family:var(--font-readout); font-size:12px; color:rgb(var(--ink-caption)); margin-left:6px}
.gl-tokens td{white-space:nowrap}
.gl-tokens td:last-child{white-space:normal; min-width:16em}
.gl-ramp{display:grid; grid-template-columns:repeat(auto-fill,minmax(84px,1fr)); gap:12px; margin:0 0 22px}
.gl-ramp > div{display:flex; flex-direction:column; font-size:13px; min-width:0}
.gl-ramp .gl-hex{margin:0}
.gl-role{color:rgb(var(--ink-caption)); font-size:12px; line-height:1.4}
.gl-marks{display:flex; flex-wrap:wrap; gap:6px 14px; align-items:center}
.gl-inline{display:inline-flex; vertical-align:middle; margin-right:4px; background:none}
.gl-markterms td:first-child{min-width:11em}
.gl-markterms td:last-child{font-size:13px}
.gl-index{display:grid; grid-template-columns:repeat(auto-fill,minmax(250px,1fr)); gap:14px 24px; margin:0 0 24px}
.gl-index p{margin:4px 0 0; font-size:13px; line-height:1.9}
.gl-index p a{margin-right:8px; white-space:nowrap}
.gl-index-head{font-weight:600}
.gl-api td:first-child{white-space:nowrap}
.gl-types{font-size:13px; line-height:2.1}
.gl-types code{margin-right:4px}
/* On a phone the wide tables stack, each row a block and each cell a labeled line. */
@media (max-width:640px){
  .gl-stack table, .gl-stack tbody, .gl-stack tr, .gl-stack td{display:block; width:auto}
  .gl-stack thead{display:none}
  .gl-stack tr{padding:10px 12px; border-bottom:1px solid rgb(var(--line))}
  .gl-stack tbody tr:last-child{border-bottom:0}
  .gl-stack td{padding:2px 0; border:0; white-space:normal}
  .gl-stack td:empty{display:none}
  .gl-stack td + td::before{content:attr(data-label) ": "; color:rgb(var(--ink-caption)); font-size:13px; margin-right:4px}
  .gl-stack td code{white-space:normal; overflow-wrap:anywhere}
  .gl-tokens td:last-child{min-width:0}
}
@media (max-width:900px){
  .gl-specs,.gl-two,.gl-useavoid{grid-template-columns:minmax(0,1fr)}
  .gl-spec > .gl-frame{padding:12px}
}
/* One appearance's tokens on a box, so the three can sit side by side. */
${appearanceBoxes}
/* What the document's theme sets on bare elements goes back to the browser's defaults inside a
   specimen; the kit's own CSS below then styles it exactly as in the app. SVG, images
   and anything sized by an attribute keep their presentation attributes. */
@layer doc-reset{.${SCOPE}, .${SCOPE} *:not(svg, svg *, img, video, canvas, iframe, [width], [height]){all:revert}}
/* A specimen is the app, in the app's own face - restated here because the reset above reverts it. */
.${SCOPE}{font-family:var(--font-ui)}
`

const allMarkup = rendered.join('\n')
const css = await kitCss(allMarkup, kit.TAILWIND.default)

// Transitions are held off until the page has painted, as installMotion() does in the apps.
const MOTION_READY = `<script>requestAnimationFrame(function(){requestAnimationFrame(function(){document.documentElement.setAttribute('data-motion-ready','')})})</script>`

const componentCount = R.KIT_REGISTRY.length
let html = renderDocPage({
  title: 'Proxyma UI design guideline',
  doctype: 'Design - UI guideline',
  description: 'How Proxyma looks, behaves and speaks: principles, appearance, color, type, space, motion, responsive rules, icons, the logo and brand assets, words, copy and accessibility - and every component of the UI kit, drawn live.',
  lede: `How every Proxyma surface looks and behaves, and every part of the UI kit (<code>libs/ui-kit</code>) drawn live by the kit itself - ${componentCount} components. Switch between Light and Dark beside the logo; everything below follows it.`,
  body,
  themeLayer: 'doc',
  extraCss: GUIDE_CSS + '\n' + css,
  extraHead: MOTION_READY,
  footer: `Generated from the UI kit by <code>tools/docs/build-ui-guideline.mjs</code>. ${componentCount} components, ${specimenCount} specimens. Live in the gallery: <code>/__kit</code>.`,
})
html = html.replace(/<!--kit:(\d+)-->/g, (_, i) => rendered[Number(i)])

// Every kit export has its anchor - the same promise ui-guideline.test.mjs checks on the file.
const missing = kitExports(REPO).filter(e => !html.includes(`id="${e.name}"`)).map(e => e.name)
if (missing.length) {
  console.error(`Kit exports with no anchor in the guideline: ${missing.join(', ')}`)
  process.exit(1)
}

if (check) {
  const current = existsSync(OUT) ? readFileSync(OUT, 'utf8') : ''
  if (current !== html) {
    console.error('docs/ui-guideline.html is out of date - run: node tools/docs/build-ui-guideline.mjs')
    process.exit(1)
  }
  console.log('docs/ui-guideline.html is up to date')
} else {
  writeFileSync(OUT, html)
  console.log(`Wrote docs/ui-guideline.html - ${componentCount} components, ${specimenCount} specimens, ${Math.round(html.length / 1024)} KB`)
}
