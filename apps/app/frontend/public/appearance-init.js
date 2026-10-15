// Pre-paint appearance stamp, loaded by index.html in <head>, before the body exists.
// src/store/themeStore.ts sets the same two attributes, but only once the bundle has been fetched,
// parsed and run - by which time the first frame has painted in the bare :root default (Proxyma), so
// a Light or Dark user saw a Proxyma flash on every load. It is parseAppearance and
// resolveAppearance from the kit's appearance.ts, by hand, because no module exists yet: change the
// three together, and keep the key in step with themeStore's MODE_STORAGE_KEY.
//
// A FILE, NOT AN INLINE SCRIPT, since 2026-10-14: the app's Content-Security-Policy allows scripts
// from its own origin only (SecurityHeadersConfig, swx-06), and an inline one would need a hash
// kept in step with every edit of these lines.
(function () {
  var p = 'system'
  try { p = localStorage.getItem('proxyma-theme-mode') || 'system' } catch (e) { /* storage disabled */ }
  if (p === 'dark') p = 'gray'
  if (p !== 'light' && p !== 'gray' && p !== 'black') p = 'system'
  if (p === 'system') {
    var dark = !window.matchMedia || window.matchMedia('(prefers-color-scheme: dark)').matches
    p = dark ? 'black' : 'light'
  }
  var root = document.documentElement
  root.setAttribute('data-mode', p === 'light' ? 'light' : 'dark')
  if (p === 'black') root.setAttribute('data-shade', 'black')
})()
