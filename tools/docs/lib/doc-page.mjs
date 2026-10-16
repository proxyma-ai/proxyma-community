// Assembles one self-contained HTML document - tokens, fonts, styles, behavior and inline SVG all
// in the file - so a generated document opens from a file:// URL, an artifact host or a GitHub
// "raw" view with no build step and no network.
//
// Colors are the product's: libs/ui-kit/tokens/proxyma-tokens.css is inlined as the first style
// block, so the document has exactly the Light, Proxyma and Dark appearances the apps have and
// follows any change to them on the next build.

import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { UML_THEME_CSS } from './plantuml.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
export const REPO = join(HERE, '..', '..', '..')

const read = p => readFileSync(join(REPO, p), 'utf8')
const b64 = p => readFileSync(join(REPO, p)).toString('base64')
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const stripTags = s => s.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim()

export function slugify(text) {
  return stripTags(text).toLowerCase().replace(/&[a-z]+;/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
}

function fontFaces() {
  return [
    // The reading face of the documents is the app's own (maintainer, 2026-10-16: "use Proxyma's
    // font"), as are the UI guideline's specimens.
    `@font-face{font-family:'Space Grotesk';font-style:normal;font-weight:300 700;font-display:swap;src:url(data:font/woff2;base64,${b64('design/docs-theme/fonts/space-grotesk-var-latin.woff2')}) format('woff2')}`,
    `@font-face{font-family:'Orbitron';font-style:normal;font-weight:700;font-display:swap;src:url(data:font/woff2;base64,${b64('design/docs-theme/fonts/orbitron-700-latin.woff2')}) format('woff2')}`,
  ].join('\n')
}

/** The mark, without its XML prolog and comment, with ids made unique to this use. */
export function logoSvg(idPrefix = 'logo') {
  return read('design/proxyma.svg')
    .replace(/<\?xml[^?]*\?>/, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\sid="([^"]+)"/g, ` id="${idPrefix}-$1"`)
    .replace(/url\(#([^)]+)\)/g, `url(#${idPrefix}-$1)`)
    .replace('<svg ', '<svg aria-hidden="true" focusable="false" ')
    .trim()
}

/**
 * THE APP'S SCROLLBAR, taken from the kit rather than restated: the global thin-scrollbar rules in
 * libs/ui-kit/tokens/proxyma-primitives.css, cut out of that file at build time, so a document's
 * page and contents panel scroll exactly as every region of the app does and follow any change to
 * it on the next build (maintainer, 2026-10-15). Fails the build if the block cannot be found -
 * a document quietly back on the browser's wide default bar is the thing this exists to prevent.
 */
export function scrollbarCss() {
  const css = read('libs/ui-kit/tokens/proxyma-primitives.css')
  const m = /\n\s*\* \{\s*scrollbar-width:[\s\S]*?\*::-webkit-scrollbar-thumb:hover \{[^}]*\}/.exec(css)
  if (!m) throw new Error('proxyma-primitives.css: the global scrollbar rules were not found - update scrollbarCss()')
  return m[0].trim().replace(/\n\s+/g, '\n')
}

/**
 * Gives every h2/h3 an id (kept when the source sets one) and returns the body plus a two-level
 * outline for the table of contents.
 */
export function outline(body) {
  const seen = new Set()
  const sections = []
  const html = body.replace(/<h([23])(\s[^>]*)?>([\s\S]*?)<\/h\1>/g, (m, level, attrs = '', inner) => {
    let id = (/\sid="([^"]+)"/.exec(attrs) || [])[1]
    if (!id) {
      id = slugify(inner) || `section-${seen.size + 1}`
      let n = 2
      while (seen.has(id)) id = `${slugify(inner)}-${n++}`
      attrs = `${attrs} id="${id}"`
    }
    seen.add(id)
    // A section number is its own span; keep a space between it and the title in the contents.
    const text = stripTags(inner.replace(/<\/span>/g, '</span> '))
    if (level === '2') sections.push({ id, text, children: [] })
    else if (sections.length) sections[sections.length - 1].children.push({ id, text })
    return `<h${level}${attrs}>${inner}</h${level}>`
  })
  return { html, sections }
}

const CARET = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 4l4 4-4 4"/></svg>'

function tocHtml(sections) {
  const items = sections.map((s, i) => {
    if (!s.children.length) return `<li><div class="toc-row"><a href="#${s.id}">${esc(s.text)}</a></div></li>`
    const subId = `sub-${i}`
    const kids = s.children.map(c => `<li><a href="#${c.id}">${esc(c.text)}</a></li>`).join('')
    return `<li><div class="toc-row"><a href="#${s.id}">${esc(s.text)}</a><button class="toc-caret" type="button" aria-expanded="false" aria-controls="${subId}" aria-label="Expand ${esc(s.text)}">${CARET}</button></div><ol id="${subId}" hidden>${kids}</ol></li>`
  })
  return `<ol>${items.join('')}</ol>`
}

// Runs before first paint, so a stored appearance never flashes the default one, nor a stored
// contents width the default width. Mirrors apply() and the TOC_* bounds in doc-theme.js; change
// both together.
const PREPAINT = `(function(){try{var p=localStorage.getItem('proxyma-docs-appearance')||'system';}catch(e){p='system'}var d=window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches;var m=p==='light'?'light':(p==='gray'||p==='black')?'dark':(d?'dark':'light');var s=(p==='black'||(p==='system'&&d))?'black':null;var r=document.documentElement;r.setAttribute('data-mode',m);if(s)r.setAttribute('data-shade',s);try{var w=parseInt(localStorage.getItem('proxyma-docs-toc-width'),10);if(w>=200&&w<=480)r.style.setProperty('--toc-width',w+'px');}catch(e){}})();`

/**
 * @param {object} o
 * @param {string} o.title        page title and h1
 * @param {string} o.doctype      the small caps line above the contents ("Architecture")
 * @param {string} o.description  meta description
 * @param {string} o.lede         one-paragraph introduction under the h1 (HTML)
 * @param {string} o.body         the document's sections (HTML with h2/h3)
 * @param {string} [o.extraCss]   page-specific CSS, after the theme
 * @param {string} [o.extraHead]  extra <head> markup (e.g. a component stylesheet)
 * @param {string} [o.footer]     HTML under the table of contents
 * @param {string} [o.themeLayer] puts the theme in this cascade layer, so unlayered CSS after it
 *                                (a component stylesheet scoped to a subtree) wins whatever its
 *                                specificity. Omitted, the theme is unlayered as it always was.
 */
export function renderDocPage({ title, doctype, description, lede = '', body, extraCss = '', extraHead = '', footer = '', themeLayer }) {
  const { html, sections } = outline(body)
  const tokens = read('libs/ui-kit/tokens/proxyma-tokens.css')
  const theme = readFileSync(join(HERE, 'doc-theme.css'), 'utf8') +
    `\n/* The app's scrollbar, from libs/ui-kit/tokens/proxyma-primitives.css (scrollbarCss) */\n${scrollbarCss()}\n`
  const behavior = readFileSync(join(HERE, 'doc-theme.js'), 'utf8')
  return `<!doctype html>
<html lang="en" data-mode="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<meta name="description" content="${esc(description)}">
<meta name="generator" content="tools/docs - do not edit this file by hand">
<script>${PREPAINT}</script>
<style>${fontFaces()}</style>
<style>${tokens}</style>
<style>${themeLayer ? `@layer ${themeLayer}{
${theme}}` : theme}</style>
<style>${UML_THEME_CSS}</style>
${extraCss ? `<style>${extraCss}</style>` : ''}
${extraHead}
</head>
<body>
<div class="appearance" role="group" aria-label="Appearance">
  <button type="button" data-pref="system" aria-pressed="false">System</button>
  <button type="button" data-pref="light" aria-pressed="false">Light</button>
  <button type="button" data-pref="gray" aria-pressed="false">Proxyma</button>
  <button type="button" data-pref="black" aria-pressed="false">Dark</button>
</div>
<div class="layout">
  <aside class="toc" id="toc">
    <div class="toc-head">
      <a class="brandmark" href="#top">${logoSvg('toc-logo')}<span>Proxyma</span></a>
      <p class="doctype">${esc(doctype)}</p>
      ${sections.some(s => s.children.length) ? `<div class="toc-tools" hidden><button type="button" data-toc-all="open">Expand all</button><button type="button" data-toc-all="close">Collapse all</button></div>` : ''}
    </div>
    <div class="toc-body">
      <nav aria-label="Contents">${tocHtml(sections)}</nav>
      ${footer ? `<div class="toc-foot">${footer}</div>` : ''}
    </div>
  </aside>
  <div class="toc-resize" role="separator" aria-orientation="vertical"></div>
  <main class="content" id="top">
    <h1>${esc(title)}</h1>
    ${lede ? `<p class="lede">${lede}</p>` : ''}
    ${html}
  </main>
</div>
<script>${behavior}</script>
</body>
</html>
`
}
