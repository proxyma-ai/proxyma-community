// Renders the UI kit to static HTML and CSS, for a generated document that shows the kit's own
// components rather than pictures of them (the UI design guideline, build-ui-guideline.mjs).
//
//   bundleKit()      esbuild-bundles the kit, the gallery's registry and specimens and the guideline's
//                    own pictures into one Node module, with React's server renderer
//   kitCss(html)     Tailwind over exactly the markup rendered, with the kit's primitives, scoped
//                    under `.kit` so it cannot reach the document around it
//
// Everything comes from apps/app/frontend/node_modules - the same React, Tailwind and kit copy the
// app builds with, and nothing added to package.json. A checkout without them fails here, by name.

import { createRequire } from 'node:module'
import { mkdirSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { REPO } from './doc-page.mjs'

export const APP = join(REPO, 'apps', 'app', 'frontend')
const SRC = join(APP, 'src')
const KIT_DIR = join(SRC, 'components', 'ui', 'kit')

/** The class every rendered part sits under; the kit's CSS reaches nothing outside it. */
export const SCOPE = 'kit'

function appRequire() {
  const req = createRequire(join(APP, 'package.json'))
  for (const dep of ['esbuild', 'tailwindcss', 'postcss', 'autoprefixer', 'react-dom/server']) {
    try { req.resolve(dep) } catch {
      throw new Error(`apps/app/frontend/node_modules has no ${dep} - run \`npm ci\` in apps/app/frontend first`)
    }
  }
  return req
}

const slash = p => p.replace(/\\/g, '/')

/**
 * Bundles `entry` (an object of export name -> absolute module path, re-exported whole) beside
 * React's server renderer, and imports the result. Inside the kit, `createPortal` returns its
 * children where they stand: a dialog drawn open lands inside its specimen's frame instead of on
 * a document.body the server renderer does not have (no effect ever runs, so nothing else asks
 * for one).
 */
export async function bundleKit(entry) {
  const req = appRequire()
  const esbuild = req('esbuild')
  const lines = [
    `import { renderToStaticMarkup } from 'react-dom/server'`,
    `export const render = (node, prefix) => renderToStaticMarkup(node, { identifierPrefix: prefix })`,
  ]
  for (const [name, path] of Object.entries(entry)) lines.push(`export * as ${name} from '${slash(path)}'`)

  const portalInPlace = {
    name: 'portal-in-place',
    setup(build) {
      build.onResolve({ filter: /^react-dom$/ }, args => (
        slash(args.importer).startsWith(slash(KIT_DIR)) ? { path: 'react-dom', namespace: 'portal-in-place' } : undefined
      ))
      build.onLoad({ filter: /.*/, namespace: 'portal-in-place' }, () => ({
        contents: `export * from 'react-dom'\nexport function createPortal(children) { return children }\n`,
        resolveDir: APP,
      }))
    },
  }

  const dir = join(tmpdir(), 'proxyma-docs')
  mkdirSync(dir, { recursive: true })
  const outfile = join(dir, `kit-${process.pid}.mjs`)
  await esbuild.build({
    stdin: { contents: lines.join('\n'), resolveDir: SRC, loader: 'tsx' },
    bundle: true,
    platform: 'node',
    format: 'esm',
    outfile,
    jsx: 'automatic',
    alias: { '@': SRC },
    nodePaths: [join(APP, 'node_modules')],
    plugins: [portalInPlace],
    // A picture may show a raster asset; it travels inside the document.
    loader: { '.png': 'dataurl' },
    define: {
      'process.env.NODE_ENV': '"production"',
      'import.meta.env.DEV': 'false',
      // The one browser global a component reaches during render: a dialog's portal target.
      'document.body': 'null',
    },
    banner: { js: "import { createRequire as __docsRequire } from 'node:module'; const require = __docsRequire(import.meta.url);" },
    logLevel: 'error',
  })
  return import(pathToFileURL(outfile).href)
}

// -- CSS ---------------------------------------------------------------------------------------

/** One selector, moved under the scope. The root and body rules become the scope element's own. */
export function scopeSelector(selector, scope = SCOPE) {
  const S = `.${scope}`
  const s = selector.trim()
  if (/^::view-transition/.test(s)) return s
  if (/^(html|:root|:host|body)$/.test(s)) return S
  if (/^(\*|::?[a-z-]+(\(.*\))?)$/.test(s)) return `${S} ${s}`
  // A rule keyed on the root's attributes (the appearance, motion-ready) keeps its root part.
  const root = /^((?:html|:root)(?:\[[^\]]*\]|:not\((?:[^()]|\([^)]*\))*\))*)\s*(>?)\s*(.*)$/.exec(s)
  if (root && root[3] !== undefined && (root[1] !== 'html' || root[3])) {
    const rest = root[3].replace(/^body\b\s*/, '')
    return rest ? `${root[1]} ${S} ${rest}` : `${root[1]} ${S}`
  }
  if (/^body\b/.test(s)) return `${S}${s.slice(4)}`
  return `${S} ${s}`
}

/**
 * The kit's compiled CSS for exactly the markup given: the primitives, then Tailwind's base,
 * components and utilities over that markup (the app's own config, content replaced), every rule
 * scoped under `.kit`, minified. The tokens are not included - the document inlines them once.
 */
export async function kitCss(markup, tailwindConfig) {
  const req = appRequire()
  const postcss = req('postcss')
  const tailwindcss = req('tailwindcss')
  const autoprefixer = req('autoprefixer')
  const esbuild = req('esbuild')
  const primitives = readFileSync(join(SRC, 'styles', 'proxyma', 'proxyma-primitives.css'), 'utf8')
  const input = `${primitives}\n@tailwind base;\n@tailwind components;\n@tailwind utilities;\n`
  const config = { ...tailwindConfig, content: [{ raw: markup, extension: 'html' }] }
  const result = await postcss([tailwindcss(config), autoprefixer()]).process(input, { from: undefined })
  result.root.walkRules(rule => {
    const parent = rule.parent
    if (parent && parent.type === 'atrule' && /keyframes$/i.test(parent.name)) return
    // The app shell pins <html> and <body>; a document scrolls, and has no shell.
    if (rule.selector.includes('data-app-shell')) { rule.remove(); return }
    rule.selectors = rule.selectors.map(sel => scopeSelector(sel))
  })
  const { code } = await esbuild.transform(result.root.toString(), { loader: 'css', minify: true })
  return code.trim()
}
