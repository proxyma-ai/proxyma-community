// Proxyma's application window - the Desktop interface in a window of its own, the way Claude
// Desktop and every other Electron app shows theirs, instead of a tab in the user's browser.
//
// ONLY THE WINDOW. The Java side owns everything else and keeps owning it: ProxymaLauncher starts
// the backend, installs Python, applies updates and puts the icon in the tray; the backend's
// DesktopIntegrationService starts THIS when it is ready, with the address to show. Closing the
// window therefore does not stop Proxyma - indexing carries on and the tray icon brings the window
// back - and a stopped Proxyma closes the window (see watchOwner).
//
// THE PAGE IS UNTRUSTED CONTENT with a trusted origin. It renders model output and indexed
// documents, so the renderer gets no Node, runs sandboxed and isolated, is granted no permission
// beyond writing the clipboard, and cannot navigate away from Proxyma's own origin: any other web or
// mail link opens in the system browser, and anything else - file:, smb:, an OS handler - nowhere.
//
// THE ACCESS CODE ARRIVES IN THE ENVIRONMENT (PROXYMA_WINDOW_ACCESS, AppWindow.ACCESS_ENV), never
// on the command line, which on Linux every account on the machine can read. Every API call of a
// Desktop install needs it (AccessGate); the window presents it once per load through the backend's
// handshake, /?access=<code>, which sets the cookie and redirects to the page without it.
//
// Plain CommonJS on purpose: this file runs as Electron's main process with no build step, so what
// is in the repository is exactly what ships (tools/build/stage-desktop-shell.mjs copies it).
'use strict'

const { app, BrowserWindow, Menu, ipcMain, net, session, shell, nativeTheme, screen } = require('electron')
const fs = require('node:fs')
const path = require('node:path')

const DEFAULT_URL = 'http://127.0.0.1:4246'
const url = new URL(argValue('--url') || DEFAULT_URL)
let ownerPid = parsePid(argValue('--owner-pid'))

// Read once and taken out of the environment, so no process this one starts inherits it.
const accessCode = process.env.PROXYMA_WINDOW_ACCESS || null
delete process.env.PROXYMA_WINDOW_ACCESS

// The address a load goes to: the handshake when there is a code, the page itself otherwise (a
// window started without one shows the backend's access screen).
function entryUrl() {
  if (!accessCode) return url.href
  const entry = new URL('/', url)
  entry.searchParams.set('access', accessCode)
  return entry.href
}

// Only these leave the window, and only to the system browser or mail client. A link the page
// should never have rendered - file:, smb:, ms-msdt:, search-ms: - is dropped rather than handed to
// the operating system's handler for it (editions-assembly-16).
const EXTERNAL_SCHEMES = new Set(['http:', 'https:', 'mailto:'])

function openOutside(target) {
  try {
    if (EXTERNAL_SCHEMES.has(new URL(target).protocol)) shell.openExternal(target)
    else logWindow(`refused to open a ${new URL(target).protocol} link`)
  } catch { /* not a URL at all */ }
}

// The same identity the backend's JVM gives itself (DesktopIntegrationService.WINDOWS_APP_ID), so
// Windows groups this window and Proxyma's notifications under one name.
app.setAppUserModelId('Proxyma')

// One window, ever. A second start - the tray's Open Proxyma, the Start menu shortcut while
// Proxyma runs - brings the existing window forward instead of opening another.
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', (_event, argv) => {
    // A window opened without an owner (the launcher's "already running" path) adopts the first
    // one a later start names.
    if (!ownerPid) ownerPid = parsePid(argValue('--owner-pid', argv))
    const win = BrowserWindow.getAllWindows()[0]
    if (!win) return
    // A page whose process died is a black window; bring it back loaded, not as it was left.
    if (win.webContents.isCrashed()) loadWhenReady(win)
    if (win.isMinimized()) win.restore()
    if (maximizeOnShow) {
      maximizeOnShow = false
      win.maximize()
    }
    win.show()
    win.focus()
  })
  app.whenReady().then(createWindow)
  app.on('window-all-closed', () => app.quit())
  app.on('before-quit', () => { quitting = true })
}

// Set once the app is really going - the owner exited, or Electron is quitting for any other
// reason - so the close handler below stops turning a close into a hide.
let quitting = false

// A window started hidden by an update (restartWindow) that was maximized is maximized when opened.
let maximizeOnShow = false

function argValue(name, argv = process.argv) {
  const prefix = name + '='
  const arg = argv.find(a => a.startsWith(prefix))
  return arg ? arg.slice(prefix.length) : null
}

function parsePid(value) {
  const pid = Number(value)
  return Number.isInteger(pid) && pid > 0 ? pid : null
}

// -- window state ---------------------------------------------------------------------------------

const stateFile = () => path.join(app.getPath('userData'), 'window-state.json')

const MIN_WIDTH = 720
const MIN_HEIGHT = 520

// The default size, fitted to the screen it opens on.
function defaultState() {
  const area = screen.getPrimaryDisplay().workArea
  return { width: Math.min(1280, area.width), height: Math.min(860, area.height) }
}

// THE SAVED SIZE IS CHECKED BEFORE IT IS BELIEVED (2026-09-26). The window kept opening at its
// minimum width - the maintainer's report - and the file on that machine read
// {"width":725,...,"maximized":true}: a maximized window whose NORMAL size had decayed to the
// minimum, restored as the size whenever the maximize did not take. A saved size too narrow to use,
// or no longer on any screen (a monitor unplugged since), now falls back to the default rather than
// being restored faithfully.
function readState() {
  try {
    const s = JSON.parse(fs.readFileSync(stateFile(), 'utf8'))
    if (!Number.isFinite(s.width) || !Number.isFinite(s.height)) return defaultState()
    const usable = s.width >= 960 && s.height >= MIN_HEIGHT
    const onScreen = Number.isFinite(s.x) && Number.isFinite(s.y) && screen.getAllDisplays().some(d => {
      const a = d.workArea
      return s.x < a.x + a.width - 100 && s.x + s.width > a.x + 100 && s.y >= a.y - 20 && s.y < a.y + a.height - 100
    })
    if (!usable || !onScreen) return { ...defaultState(), maximized: s.maximized === true }
    return s
  } catch { /* first run, or unreadable - use the defaults */ }
  return defaultState()
}

function saveState(win) {
  try {
    if (win.isMinimized()) return
    const bounds = win.getNormalBounds()
    fs.writeFileSync(stateFile(), JSON.stringify({ ...bounds, maximized: win.isMaximized() }))
  } catch { /* losing the window size is not worth an error */ }
}

// -- the window -----------------------------------------------------------------------------------

// Electron grants a page every permission it asks for unless told otherwise. The interface needs
// one: writing the clipboard, for its copy buttons. Full screen is the window's own F11.
const ALLOWED_PERMISSIONS = new Set(['clipboard-sanitized-write', 'fullscreen'])

function restrictPermissions() {
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
    callback(ALLOWED_PERMISSIONS.has(permission))
  })
  session.defaultSession.setPermissionCheckHandler((_wc, permission) => ALLOWED_PERMISSIONS.has(permission))
}

function createWindow() {
  // No menu bar: the interface has its own menu, and a File/Edit/View strip above it would be a
  // second one. The shortcuts people expect from a window are wired by hand below.
  Menu.setApplicationMenu(null)
  restrictPermissions()

  const state = readState()
  const win = new BrowserWindow({
    x: state.x,
    y: state.y,
    width: state.width,
    height: state.height,
    minWidth: MIN_WIDTH,
    minHeight: MIN_HEIGHT,
    title: 'Proxyma',
    // BORDERLESS, since 2026-10-29 (the maintainer's glass shell): the page draws the whole window -
    // the frosted frame edge to edge, and its own minimize, maximize and close (wireWindowControls,
    // preload.js). Windows keeps the resize edges of a frameless window and Windows 11 rounds its
    // corners; the page marks where the window is dragged from (`-webkit-app-region: drag`).
    frame: false,
    icon: path.join(__dirname, process.platform === 'win32' ? 'icon.ico' : 'icon.png'),
    // The field color of the appearance the OS asks for - System resolves a dark OS to Dark - so
    // the first frame before the page paints is not a white flash on a dark desktop. Matches
    // Dark's and Light's --field-base in proxyma-tokens.css.
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#101011' : '#e9ecee',
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      // Only the path of a dropped file or folder - see preload.js.
      preload: path.join(__dirname, 'preload.js'),
      // Not on Linux: there Chromium downloads its Hunspell dictionaries from Google, a request
      // to a third party the Privacy Policy does not name. Windows uses the OS spell checker.
      spellcheck: process.platform !== 'linux',
    },
  })
  // Maximized when it is SHOWN, not before: maximize() on a window that is still hidden is what let
  // the normal size above decay, and it did not reliably come up maximized either.
  //
  // WITHIN A MOMENT, NOT ONLY ON FIRST PAINT (2026-09-28). ready-to-show waits for the page, and the
  // page waits for a backend that may be busy indexing: opened from the tray, nothing appeared for
  // up to a minute and the click looked ignored. The background color above makes an early show
  // look like a window loading rather than a white flash.
  let shown = false
  const reveal = () => {
    if (shown || win.isDestroyed()) return
    shown = true
    // Started again by an update while it was hidden (restartWindow): it stays in the tray until
    // opened, as it was, and is maximized then.
    if (process.argv.includes(HIDDEN_ARG)) {
      maximizeOnShow = state.maximized === true
      return
    }
    if (state.maximized) win.maximize()
    win.show()
  }
  win.once('ready-to-show', reveal)
  setTimeout(reveal, 800)
  // CLOSING HIDES, since 2026-09-28. Closing used to quit the window's process, so every Open from
  // the tray started Electron from cold - on a managed laptop, with antivirus scanning a large
  // install, that was the reported minute. Now the tray's Open reaches a running process (see
  // 'second-instance') and only has to show it. Proxyma stopping still closes it (watchOwner).
  win.on('close', event => {
    saveState(win)
    if (quitting) return
    event.preventDefault()
    win.hide()
  })

  keepToOrigin(win)
  wireWindowControls(win)
  wireShortcuts(win)
  recoverFromCrashes(win)
  followBuild(win)
  loadWhenReady(win)
  watchOwner()
}

// THE WINDOW'S OWN BUTTONS, drawn by the page since the window lost its frame (2026-10-29). Three
// requests and one notice, and only from this window's own page: a request from any other frame or
// window is ignored. Close is the window's close, so it hides as the title bar's did.
function wireWindowControls(win) {
  const mine = event => !win.isDestroyed() && event.sender === win.webContents && event.senderFrame === win.webContents.mainFrame
  ipcMain.on('window:minimize', event => { if (mine(event)) win.minimize() })
  ipcMain.on('window:toggle-maximize', event => {
    if (!mine(event)) return
    if (win.isMaximized()) win.unmaximize()
    else win.maximize()
  })
  ipcMain.on('window:close', event => { if (mine(event)) win.close() })
  ipcMain.handle('window:is-maximized', event => (mine(event) ? win.isMaximized() : false))
  const tell = () => { if (!win.isDestroyed()) win.webContents.send('window:maximized', win.isMaximized()) }
  win.on('maximize', tell)
  win.on('unmaximize', tell)
}

// A NEW BUILD IS LOADED, since 2026-10-03 (the maintainer's report: after an update the window
// still showed the old interface while a browser showed the new one). The window outlives a
// backend restart, an update's included (see watchOwner), and the page it holds only reloads
// itself when it is the page that asked for the restart. An update applied from a browser tab,
// or by the launcher while the window was hidden, left this one running the previous build's
// JavaScript against the new backend until somebody pressed F5.
//
// So the window remembers the version the backend reported when the page loaded, and loads the
// page again once the backend reports another. Only a different VERSION does it: a restart into
// the same build leaves the page alone, and the page's own restart screens reload it anyway.
const BUILD_CHECK_MS = 5000

function followBuild(win) {
  const health = new URL('/api/v1/health', url).href
  const versionNow = async () => {
    try {
      const answer = await net.fetch(health)
      if (!answer.ok) return null
      const body = await answer.json()
      return typeof body.version === 'string' && body.version ? body.version : null
    } catch {
      return null
    }
  }
  let loaded = null
  win.webContents.on('did-finish-load', async () => { loaded = await versionNow() })
  setInterval(async () => {
    if (quitting || win.isDestroyed() || loaded === null) return
    const now = await versionNow()
    if (now === null || now === loaded) return
    if (shellChanged()) {
      logWindow(`backend is ${now}, and the update changed the window's own files; starting the window again`)
      restartWindow(win)
      return
    }
    logWindow(`backend is ${now}, the page was loaded from ${loaded}; loading it again`)
    loaded = now
    win.webContents.reloadIgnoringCache()
  }, BUILD_CHECK_MS)
}

// AN UPDATE THAT CHANGES THIS FILE OR preload.js STARTS THE WINDOW AGAIN (2026-10-30). The launcher
// copies the new files into the window before the new backend starts (ProxymaLauncher.useWindowOf),
// but a running window keeps the main.js it started with, while a reload reads preload.js afresh -
// so after the update to the borderless window the page drew its own buttons in a window that
// still had the title bar and no handler behind them. The maintainer's report. A reload is enough
// only while both files are the ones this process started with.
const SHELL_FILES = [__filename, path.join(__dirname, 'preload.js')]
const shellAtStart = shellFingerprint()

function shellFingerprint() {
  try {
    return SHELL_FILES.map(f => fs.readFileSync(f, 'utf8')).join('\0')
  } catch {
    return null
  }
}

function shellChanged() {
  const now = shellFingerprint()
  return shellAtStart !== null && now !== null && now !== shellAtStart
}

// Started again as it is now: shown if it was shown, still hidden in the tray if it was hidden. The
// new process is started as this one exits, so it takes the single-instance lock this one held.
// The access code goes back into the environment for it alone - the new window reads it and takes
// it out again, as this one did.
function restartWindow(win) {
  quitting = true
  saveState(win)
  const args = process.argv.slice(1).filter(a =>
    !a.startsWith('--url=') && !a.startsWith('--owner-pid=') && a !== HIDDEN_ARG)
  args.push('--url=' + url.href)
  if (ownerPid) args.push('--owner-pid=' + ownerPid)
  if (!win.isVisible()) args.push(HIDDEN_ARG)
  if (accessCode) process.env.PROXYMA_WINDOW_ACCESS = accessCode
  app.relaunch({ args })
  app.exit(0)
}

// Set only by restartWindow, for a window that was hidden when an update restarted it.
const HIDDEN_ARG = '--hidden'

// A PAGE THAT DIES IS LOADED AGAIN (2026-09-29). The window has outlived its page since closing
// became a hide: a renderer that crashed mid-conversation left a black window, and every reopen from
// the tray showed that same dead page while Proxyma itself - and the browser - worked on. The
// maintainer's report. The page is reloaded when its process goes (a clean exit aside), and each
// reason is written to window.log beside the window state, since nothing else records it.
function recoverFromCrashes(win) {
  win.webContents.on('render-process-gone', (_event, details) => {
    logWindow(`page process gone: ${details.reason} (exit ${details.exitCode})`)
    if (quitting || win.isDestroyed() || details.reason === 'clean-exit') return
    setTimeout(() => { if (!win.isDestroyed()) loadWhenReady(win) }, 500)
  })
  app.on('child-process-gone', (_event, details) => {
    logWindow(`${details.type} process gone: ${details.reason} (exit ${details.exitCode})`)
  })
  // A HUNG PAGE IS ENDED, and then loaded again like a dead one (2026-09-30). A page stuck in a loop
  // never crashes by itself - it grows until it runs out of memory, which took minutes - and until
  // then every reopen from the tray showed its last frame, or black. The maintainer's report: the
  // chat's markdown parser looped on a half-written heading. Chromium's own "unresponsive" fires
  // after a few seconds without an answer; a page still stuck HUNG_GRACE_MS after that is crashed
  // on purpose, and render-process-gone above reloads it.
  let hungTimer = null
  win.webContents.on('unresponsive', () => {
    logWindow('page unresponsive')
    if (hungTimer) return
    hungTimer = setTimeout(() => {
      hungTimer = null
      if (quitting || win.isDestroyed()) return
      logWindow('page still unresponsive; restarting it')
      win.webContents.forcefullyCrashRenderer()
    }, HUNG_GRACE_MS)
  })
  win.webContents.on('responsive', () => {
    if (hungTimer) {
      clearTimeout(hungTimer)
      hungTimer = null
      logWindow('page responsive again')
    }
  })
}

const HUNG_GRACE_MS = 15000

function logWindow(line) {
  try {
    fs.appendFileSync(path.join(app.getPath('userData'), 'window.log'), `${new Date().toISOString()} ${line}\n`)
  } catch { /* the log is a courtesy; recovery does not depend on it */ }
}

// Links out of Proxyma - documentation, a source's own page, a release note - open in the system
// browser. Only Proxyma's own origin is ever shown inside this window.
function keepToOrigin(win) {
  const sameOrigin = target => {
    try { return new URL(target).origin === url.origin } catch { return false }
  }
  win.webContents.setWindowOpenHandler(({ url: target }) => {
    if (!sameOrigin(target)) openOutside(target)
    else win.loadURL(target)
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate', (event, target) => {
    if (sameOrigin(target)) return
    event.preventDefault()
    openOutside(target)
  })
}

// What a browser gave for free and a window with no menu does not: reload, zoom, full screen.
function wireShortcuts(win) {
  win.webContents.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown') return
    const mod = input.control || input.meta
    const wc = win.webContents
    if (input.key === 'F5' || (mod && input.key.toLowerCase() === 'r')) wc.reload()
    else if (mod && (input.key === '=' || input.key === '+')) wc.setZoomLevel(wc.getZoomLevel() + 0.5)
    else if (mod && input.key === '-') wc.setZoomLevel(wc.getZoomLevel() - 0.5)
    else if (mod && input.key === '0') wc.setZoomLevel(0)
    else if (input.key === 'F11') win.setFullScreen(!win.isFullScreen())
    else return
    event.preventDefault()
  })
}

// Started by the backend once it answers, so the first load normally just works - but a window
// opened from the tray or a shortcut can race a restart, so a failed load is retried rather than
// leaving Chromium's error page up.
//
// FOR TEN MINUTES, up from one (2026-10-10) - the launcher's own limit for a start
// (ProxymaLauncher.APP_READY_TIMEOUT_MS). A restart into an update rebuilds the Python runtime
// before the backend starts at all, which on the maintainer's PC took 100 seconds before the
// rebuild was made faster, and a window that gave up after one minute showed an error page over a
// start that was going well.
const LOAD_RETRY_MS = 1000
const LOAD_ATTEMPTS = 600

function loadWhenReady(win, attempt = 0) {
  win.loadURL(entryUrl()).catch(() => {
    if (attempt < LOAD_ATTEMPTS && !win.isDestroyed()) setTimeout(() => loadWhenReady(win, attempt + 1), LOAD_RETRY_MS)
  })
}

// A stopped Proxyma closes the window. "Stopped" is decided by the process that OWNS the backend -
// ProxymaLauncher, whose pid the backend passes as --owner-pid - and NOT by the backend answering.
// A restart (Settings, an update, a reset) takes the backend away for longer than any sensible
// health timeout while the launcher shows its splash and starts a new one; a window that closed on
// silence closed on every restart, taking the Restarting dialog with it. The launcher lives
// across restarts and exits on Quit or the tray's Exit, so its going is the signal.
//
// With no owner known - the exec jar run by hand - silence is the only signal left, so it gets a
// grace long enough to outlast a restart.
const OWNERLESS_GRACE_MS = 3 * 60 * 1000

function watchOwner() {
  const health = new URL('/api/v1/health', url).href
  let lastSeen = null
  setInterval(async () => {
    if (ownerPid) {
      if (!alive(ownerPid)) app.quit()
      return
    }
    let ok = false
    try { ok = (await net.fetch(health)).ok } catch { ok = false }
    if (ok) lastSeen = Date.now()
    else if (lastSeen !== null && Date.now() - lastSeen > OWNERLESS_GRACE_MS) app.quit()
  }, 2000)
}

// Signal 0 tests for the process without touching it, on Windows as well as POSIX. EPERM means it
// exists and belongs to someone else, which still counts as alive.
function alive(pid) {
  try {
    process.kill(pid, 0)
    return true
  } catch (e) {
    return e.code === 'EPERM'
  }
}
