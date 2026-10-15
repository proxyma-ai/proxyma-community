/**
 * THE APP SHELL FITS THE PART OF THE SCREEN THE READER CAN SEE.
 *
 * <p>The application and the two consoles are one fixed box with scrolling regions inside it, and
 * on a phone "the screen" is not one size. The on-screen keyboard covers the bottom of the layout
 * viewport without resizing it, and Safari then slides the whole page up to bring the focused
 * field into view - so a shell sized in `dvh` ends up with its header above the top of the screen
 * and its composer under the keyboard, with nothing to scroll either back (the maintainer's
 * report, 2026-10-02: "sometimes impossible to access the menu or even the chat input").
 *
 * <p>The VISUAL viewport is the part actually on screen. This writes its height and its offset
 * onto `<html>` as `--app-height` and `--app-top`, and the shell's CSS
 * (`html[data-app-shell]` in proxyma-primitives.css) pins the body to exactly that box: the header
 * at the top of what is visible, the composer at the bottom of it, keyboard up or not.
 *
 * <p>NOT WHILE THE READER HAS PINCHED IN. A zoomed page reports a visual viewport the size of the
 * magnified window, and fitting the shell to that would shrink the app under the very zoom the
 * reader asked for. The box keeps the size it had and they pan around it, as on any page.
 */

export interface VisibleViewport {
  height: number
  offsetTop: number
  scale: number
}

export interface AppBox {
  height: number
  top: number
}

/** The box to pin the shell to, or null to leave it as it is (the reader has zoomed in). */
export function appBox(viewport: VisibleViewport): AppBox | null {
  if (viewport.scale > 1.01) return null
  return {
    height: Math.round(viewport.height),
    // Never negative: an overscroll bounce reports a small negative offset for a frame or two,
    // and following it would bounce the whole app with it.
    top: Math.max(0, Math.round(viewport.offsetTop)),
  }
}

/**
 * Keeps `--app-height` and `--app-top` on `<html>` equal to the visual viewport. Call it once, at
 * startup, before the first render. Returns the function that stops it.
 */
export function installAppViewport(): () => void {
  const root = document.documentElement
  const visual = window.visualViewport
  const sync = () => {
    const box = appBox(visual
      ? { height: visual.height, offsetTop: visual.offsetTop, scale: visual.scale }
      : { height: window.innerHeight, offsetTop: 0, scale: 1 })
    if (!box) return
    root.style.setProperty('--app-height', `${box.height}px`)
    root.style.setProperty('--app-top', `${box.top}px`)
  }
  sync()
  const target: EventTarget = visual ?? window
  target.addEventListener('resize', sync)
  target.addEventListener('scroll', sync)
  return () => {
    target.removeEventListener('resize', sync)
    target.removeEventListener('scroll', sync)
  }
}
