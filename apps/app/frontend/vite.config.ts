import { defineConfig, loadEnv, normalizePath, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import license, { type Dependency } from 'rollup-plugin-license'
import { existsSync, readdirSync, readFileSync } from 'fs'
import path from 'path'
import { appearanceInitScript } from './src/components/ui/kit/appearanceScript'

// THE WEB HALF OF THIRD-PARTY-NOTICES.txt (apps/app/third-party/notices.xml). Every npm package that
// ends up in the bundle - not every package in package.json, and not the build tools - with its
// version, license and, where the package ships them, its LICENSE and NOTICE text. Minification
// strips the copyright comments those licenses require to travel with the code; this file is
// where they travel instead. The Maven build copies dist/ into the jar, and notices.xml joins this
// file with the Java and Python lists.
//
// THE BUILD FAILS on a package with no license, and on an AGPL one or a GPL one without an
// exception. LGPL is allowed (nothing in the bundle is LGPL today); deciding to ship a plainly
// GPL package is not a thing a build should let through quietly.
function allowedLicense(dependency: Dependency): boolean {
  const spdx = (dependency.license ?? '').trim()
  if (!spdx) return false
  // An OR is a choice, so one acceptable alternative is enough: "(MIT OR GPL-3.0)" passes,
  // "(AGPL-3.0 OR GPL-3.0)" does not. An alternative with an exception ("GPL-2.0 WITH
  // Classpath-exception-2.0") is acceptable; LGPL is not GPL for this purpose.
  return spdx.replace(/[()]/g, ' ').split(/\s+OR\s+/i).some(alternative =>
    /\bWITH\b/i.test(alternative) || !/(^|[^L])GPL/i.test(alternative))
}

// THE FONTS ARE NOT IN THE MODULE GRAPH. They arrive through CSS @import, so the license plugin,
// which reads the JavaScript chunks, never sees them - while their OFL says the license must
// travel with them. So every @fontsource package a stylesheet under src/ imports is added here by
// hand, from its own package.json and LICENSE.
type Notice = Pick<Dependency, 'name' | 'version' | 'license' | 'homepage' | 'repository' | 'licenseText' | 'noticeText'>
function bundledFonts(): Notice[] {
  const src = path.resolve(__dirname, 'src')
  const names = new Set<string>()
  for (const file of readdirSync(src, { recursive: true }) as string[]) {
    if (!file.endsWith('.css')) continue
    for (const m of readFileSync(path.join(src, file), 'utf-8').matchAll(/@fontsource\/([a-z0-9-]+)/g)) names.add(m[1])
  }
  return [...names].map(name => {
    const dir = path.resolve(__dirname, 'node_modules/@fontsource', name)
    const pkg = JSON.parse(readFileSync(path.join(dir, 'package.json'), 'utf-8'))
    const licenseFile = path.join(dir, 'LICENSE')
    return {
      name: pkg.name, version: pkg.version, license: pkg.license ?? null,
      homepage: pkg.homepage ?? null, repository: null, noticeText: null,
      licenseText: existsSync(licenseFile) ? readFileSync(licenseFile, 'utf-8') : null,
    }
  })
}

function webNotices(dependencies: Dependency[]): string {
  const rows: Notice[] = [...dependencies, ...bundledFonts()]
    .sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))
  const repo = (d: Notice) =>
    typeof d.repository === 'string' ? d.repository : d.repository?.url ?? null
  const blocks = rows.map(d => {
    const lines = [`${d.name ?? '(unnamed)'} ${d.version ?? ''}`.trim()]
    const url = d.homepage || repo(d)
    if (url) lines.push(`  ${url}`)
    lines.push(`  License: ${d.license ?? 'not stated'}`)
    if (d.licenseText) lines.push('', d.licenseText.trim())
    if (d.noticeText) lines.push('', d.noticeText.trim())
    return lines.join('\n')
  })
  return [
    'WEB INTERFACE',
    '=============',
    '',
    `The web interface carries code or fonts from ${rows.length} npm packages. Each is listed with its`,
    'version, its project page and its license, followed by the license text and notice the',
    'package itself ships, where it ships one.',
    '',
    blocks.join('\n\n----------------------------------------------------------------------\n\n'),
    '',
  ].join('\n')
}

// THE PRE-PAINT APPEARANCE SCRIPT, from the kit (appearanceScript.ts, tested against the resolution
// the theme store runs) for the store's key - src/store/themeStore.ts's MODE_STORAGE_KEY. It stamps
// data-mode on <html> (and the theme-color meta) before the body exists, so the first frame is in
// the chosen appearance rather than the bare :root's Light.
//
// SERVED AS A FILE, /appearance-init.js, rather than inlined: the app's Content-Security-Policy
// allows scripts from its own origin only (SecurityHeadersConfig, swx-06), and an inline one would
// need a hash kept in step with every edit. It was a hand-written copy in public/ until 2026-10-28;
// the two consoles' prepaint plugin is the same shape.
function appearanceInit(storageKey: string): Plugin {
  const source = appearanceInitScript(storageKey) + '\n'
  return {
    name: 'proxyma-appearance-init',
    configureServer(server) {
      server.middlewares.use('/appearance-init.js', (_req, res) => {
        res.setHeader('Content-Type', 'text/javascript; charset=utf-8')
        res.end(source)
      })
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'appearance-init.js', source })
    },
  }
}

// PROXYMA ENTERPRISE'S PAGES live outside this directory, in ee/frontend/src at the repository root -
// closed source, in the private repository only (docs/architecture.html#oss-boundary). Server mode IS the Enterprise
// build, so it needs them; desktop mode (Desktop and the Home Server) never reads them, and a tree
// without ee/ - the public Community repository - builds it unchanged.
//
// ADDONS MODE (since 2026-10-30) is the third build: the OFFICIAL Desktop and Home Server, which is
// desktop mode in every respect (.env.addons) plus the add-ons sold for it, each activated by its own
// key - Constellations first. Their pages are in ee/ too, so it needs them as server mode does; the
// public AGPL build stays desktop mode, unchanged.
const EE_SRC = path.resolve(__dirname, '../../../ee/frontend/src')

// The Enterprise files import react, recharts and the rest like any page here, but sit outside this
// package, so Node's lookup from THEIR directory would never reach this node_modules. Resolve a bare
// import from ee/ as if this app had written it - one node_modules, one React.
function enterpriseBareImports(): Plugin {
  const appEntry = path.resolve(__dirname, 'src/main.tsx')
  // Vite hands importers over with forward slashes on every platform.
  const eeRoot = normalizePath(EE_SRC) + '/'
  return {
    name: 'proxyma-ee-bare-imports',
    enforce: 'pre',
    async resolveId(source, importer, options) {
      if (!importer || !normalizePath(importer).startsWith(eeRoot)) return null
      if (/^[./\\]|^@\/|^@ee\/|^@edition|^@settings-pages|^\0|^[A-Za-z]:/.test(source)) return null
      return this.resolve(source, appEntry, { ...options, skipSelf: true })
    },
  }
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const defaultPort = '4246'
  // Where the DEV SERVER proxies /api to. Deliberately NOT VITE_API_BASE_URL, which one variable
  // was doing both jobs with: that one is baked into the client bundle, so using it to point the
  // proxy at a backend also hardcoded that address into every request the page makes. The packaged
  // desktop UI then called :4246 whatever port it was served from, and the dev server made the
  // page call the backend cross-origin instead of through the proxy sitting right there.
  //
  // No VITE_ prefix, so Vite never exposes this to client code - which is the point. The client
  // now always calls its own origin (see .env.desktop and .env.server, both empty) and only this
  // build-time value knows a port.
  //
  // VITE_API_BASE_URL is still read as a fallback so an existing shell that exports it keeps
  // working rather than silently proxying somewhere else.
  const apiBase = env.PROXYMA_DEV_API_TARGET || env.VITE_API_BASE_URL || `http://127.0.0.1:${defaultPort}`
  // Which edition's settings pages get built. Desktop and server show genuinely different
  // settings, and the difference used to be expressed as FEATURES.auth conditionals INSIDE shared
  // page components - which meant every server-only control was still compiled into the desktop
  // bundle and kept out of sight by a runtime check. A missing conditional therefore showed
  // server UI on desktop, silently: the per-user quota fields did exactly that, rendering on
  // desktop where they do nothing.
  //
  // An alias makes it a build-time fact instead. Only one of these two files is in the module
  // graph, so a desktop build cannot render a server-only page even if something asks it to -
  // the code is not there to render. Resolved per mode, and `mode` is already how the two builds
  // are separated (npm run build vs build:server).
  //
  // The same alias, and two more (`@edition`, `@edition-i18n` - src/edition/types.ts), now decide
  // the whole edition: the server one resolves into ee/ (Proxyma Enterprise), the desktop one into
  // this app's own src/edition and settings pages.
  //
  // Addons mode resolves the same aliases into ee/ as well, to the add-ons edition: Desktop's
  // settings with an Add-ons tab, and the add-ons' pages.
  const enterprise = mode === 'server'
  const addons = mode === 'addons'
  const readsEe = enterprise || addons
  if (enterprise && !existsSync(EE_SRC)) {
    throw new Error('Server mode builds Proxyma Enterprise, whose pages (ee/frontend) are not in this tree. '
      + 'Desktop and the Home Server build with `npm run build`.')
  }
  if (addons && !existsSync(EE_SRC)) {
    throw new Error('Addons mode builds Desktop with its add-ons, whose pages (ee/frontend) are not in this tree. '
      + 'Desktop and the Home Server build with `npm run build`.')
  }
  const editionAliases: Record<string, string> = enterprise
    ? {
        '@settings-pages': path.join(EE_SRC, 'features/settings/pages/index.server.tsx'),
        '@edition-i18n': path.join(EE_SRC, 'i18n/index.ts'),
        '@edition': path.join(EE_SRC, 'edition.tsx'),
        '@ee': EE_SRC,
      }
    : addons
    ? {
        '@settings-pages': path.join(EE_SRC, 'features/settings/pages/index.addons.tsx'),
        '@edition-i18n': path.join(EE_SRC, 'i18n/index.ts'),
        '@edition': path.join(EE_SRC, 'edition.addons.tsx'),
        '@ee': EE_SRC,
      }
    : {
        '@settings-pages': path.resolve(__dirname, './src/features/settings/pages/index.desktop.tsx'),
        '@edition-i18n': path.resolve(__dirname, './src/edition/community.i18n.ts'),
        '@edition': path.resolve(__dirname, './src/edition/community.tsx'),
      }
  return {
    plugins: readsEe
      ? [enterpriseBareImports(), react(), appearanceInit('proxyma-theme-mode')]
      : [react(), appearanceInit('proxyma-theme-mode')],
    build: {
      rollupOptions: {
        plugins: [
          license({
            thirdParty: {
              allow: { test: allowedLicense, failOnUnlicensed: true, failOnViolation: true },
              output: {
                file: path.resolve(__dirname, 'dist/third-party/web.txt'),
                template: webNotices,
              },
            },
          }),
        ],
      },
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
        ...editionAliases,
      },
    },
    server: {
      // The dev server serves the Enterprise pages from outside this directory in server and addons mode.
      ...(readsEe ?{ fs: { allow: [__dirname, EE_SRC] } } : {}),
      // THE ORIGIN IS REWRITTEN TO THE BACKEND'S OWN, as well as the Host. The backend lets in a
      // CORS origin only when it is its own page (WebConfig.isOwnOrigin, swx-03 - it used to allow
      // every localhost port), and through this proxy the page's origin is the dev server's.
      proxy: {
        '/api': { target: apiBase, changeOrigin: true, headers: { origin: new URL(apiBase).origin } },
        '/oauth2': { target: apiBase, changeOrigin: true, headers: { origin: new URL(apiBase).origin } },
      },
    },
  }
})
