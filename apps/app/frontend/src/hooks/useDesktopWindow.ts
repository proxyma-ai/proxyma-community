import { useEffect, useState } from 'react'

/** What Proxyma's own window lends the page (desktop-shell/preload.js); absent in a browser. */
interface WindowControlsBridge {
  minimize: () => void
  toggleMaximize: () => void
  close: () => void
  isMaximized: () => Promise<boolean>
  onMaximizedChange: (callback: (maximized: boolean) => void) => () => void
}

function bridge(): WindowControlsBridge | null {
  const w = window as unknown as { proxymaWindow?: { controls?: WindowControlsBridge } }
  return w.proxymaWindow?.controls ?? null
}

/**
 * THE WINDOW'S OWN BUTTONS, when the page is in Proxyma's own borderless window (the desktop app,
 * 2026-10-29): minimize, maximize or restore, close, and whether it is maximized now. Null in a
 * browser - a server's page, or a desktop opened in the browser - and in a window built before the
 * buttons existed, where the window has its own title bar.
 */
export function useDesktopWindow(): { controls: WindowControlsBridge; maximized: boolean } | null {
  const [controls] = useState(bridge)
  const [maximized, setMaximized] = useState(false)
  useEffect(() => {
    if (!controls) return
    let live = true
    controls.isMaximized().then(m => { if (live) setMaximized(m) }).catch(() => {})
    const stop = controls.onMaximizedChange(setMaximized)
    return () => { live = false; stop() }
  }, [controls])
  return controls ? { controls, maximized } : null
}
