// Stages Proxyma's application window (apps/app/desktop-shell) as a ready-to-run folder.
//
//     node tools/build/stage-desktop-shell.mjs <dest-dir>
//
// Called by proxyma-desktop's pom on every Windows build, and by stage-linux.sh on Linux. It installs the shell's pinned Electron
// with `npm ci` (Electron's own installer downloads the runtime once and caches it, checksum
// verified), then lays out <dest-dir> as the `window` part (Windows) and the .deb (Linux) carry it.
// On Windows, resources/app also travels in the `app` part, and the launcher copies it into the
// window before starting it, so a change to those files does not download Electron again:
//
//     ProxymaWindow.exe            electron.exe, renamed, with Proxyma's icon and version info
//                                  (not Proxyma.exe: the launcher is proxyma.exe, and Windows
//                                  process names ignore case)
//     resources/app/               main.js, preload.js, package.json and the window icons - nothing else
//     locales/en-US.pak, vi.pak    the two languages Proxyma speaks; the other ~50 are dropped
//     ... the rest of Electron's runtime, unchanged, LICENSES.chromium.html included
//
// No electron-builder, deliberately: the Java launcher and Inno Setup already do the installing,
// updating and shortcut-making that it exists for. All this needs is Electron's folder with our
// two files in it.
import { execFileSync, execSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const SHELL = path.join(REPO, 'apps', 'app', 'desktop-shell')
const ASSEMBLY = path.join(REPO, 'apps', 'app', 'backend', 'proxyma-desktop', 'src', 'assembly')
const KEEP_LOCALES = new Set(['en-US.pak', 'vi.pak'])

const dest = process.argv[2]
if (!dest) {
  console.error('usage: node tools/build/stage-desktop-shell.mjs <dest-dir>')
  process.exit(2)
}
const windows = process.platform === 'win32'

// ELECTRON_RUN_AS_NODE is set in any terminal hosted by an Electron app (VS Code's, for one), and
// it turns every Electron binary into plain Node - including the one Electron's installer checks.
const env = { ...process.env }
delete env.ELECTRON_RUN_AS_NODE

// One fixed command string: npm is a .cmd on Windows, which Node only runs through a shell.
execSync('npm ci --no-audit --no-fund', { cwd: SHELL, stdio: 'inherit', env })
const dist = path.join(SHELL, 'node_modules', 'electron', 'dist')
// Electron downloads its runtime in a postinstall script, and npm does not always run it (seen
// with npm 11 on the release PC). Its installer is idempotent, so running it again is safe.
if (!existsSync(dist)) {
  execFileSync(process.execPath, [path.join(SHELL, 'node_modules', 'electron', 'install.js')],
    { cwd: SHELL, stdio: 'inherit', env })
}
if (!existsSync(dist)) throw new Error(`Electron runtime missing at ${dist} - did its download fail?`)

rmSync(dest, { recursive: true, force: true })
mkdirSync(dest, { recursive: true })
cpSync(dist, dest, { recursive: true })

// Electron's placeholder app, which would otherwise open when resources/app is missing.
rmSync(path.join(dest, 'resources', 'default_app.asar'), { force: true })

const app = path.join(dest, 'resources', 'app')
mkdirSync(app, { recursive: true })
for (const f of ['main.js', 'preload.js', 'package.json']) cpSync(path.join(SHELL, f), path.join(app, f))
cpSync(path.join(ASSEMBLY, 'installer', 'proxyma.ico'), path.join(app, 'icon.ico'))
cpSync(path.join(ASSEMBLY, 'linux', 'proxyma.png'), path.join(app, 'icon.png'))

const locales = path.join(dest, 'locales')
for (const f of readdirSync(locales)) if (!KEEP_LOCALES.has(f)) rmSync(path.join(locales, f))

if (windows) {
  const exe = path.join(dest, 'ProxymaWindow.exe')
  renameSync(path.join(dest, 'electron.exe'), exe)
  await brand(exe, path.join(ASSEMBLY, 'installer', 'proxyma.ico'))
} else {
  renameSync(path.join(dest, 'electron'), path.join(dest, 'proxyma-window'))
}

console.log(`stage-desktop-shell: ${dest}`)

// The icon Explorer, the taskbar and Alt-Tab show, and the name Task Manager lists. resedit is a
// plain-JavaScript resource editor; rcedit, the usual tool, was pulled from npm in 2026.
async function brand(exe, icoPath) {
  const entry = path.join(SHELL, 'node_modules', 'resedit', 'dist', 'index.js')
  const ResEdit = await import(pathToFileURL(entry).href)
  const image = ResEdit.NtExecutable.from(readFileSync(exe), { ignoreCert: true })
  const res = ResEdit.NtExecutableResource.from(image)
  const RT_GROUP_ICON = 14
  const group = res.entries.find(e => e.type === RT_GROUP_ICON)
  if (!group) throw new Error('no icon group in electron.exe to replace')
  const ico = ResEdit.Data.IconFile.from(readFileSync(icoPath))
  ResEdit.Resource.IconGroupEntry.replaceIconsForResource(
    res.entries, group.id, group.lang, ico.icons.map(i => i.data))
  const [version] = ResEdit.Resource.VersionInfo.fromEntries(res.entries)
  for (const lang of version.getAllLanguagesForStringValues()) {
    version.setStringValues(lang, {
      ProductName: 'Proxyma', FileDescription: 'Proxyma', CompanyName: 'Proxyma',
      OriginalFilename: 'ProxymaWindow.exe', InternalName: 'Proxyma',
    })
  }
  version.outputToResourceEntries(res.entries)
  res.outputResource(image)
  writeFileSync(exe, Buffer.from(image.generate()))
}
