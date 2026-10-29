/**
 * Appearance - the three-way preference behind every theme menu, as PURE functions.
 *
 * <p>System, Light and Dark, since 2026-10-28 (the UI review: "no colored page backgrounds"). From
 * 0.13.0 until then there was a fourth, Proxyma - a teal-tinted dark stored as `gray` - and Dark was
 * stored as `black`, the name it shipped under when it was a true black. Both stored words, and the
 * `dark` of the two-way flip before 0.13.0, now read as Dark, so nobody who chose a dark appearance
 * wakes up to a white page. System follows the operating system live.
 *
 * <p>ONE ATTRIBUTE ON <html>: `data-mode`, light or dark. proxyma-tokens.css keys its Dark block on
 * it, and a page with no script run paints Light from the bare :root block. The second attribute
 * Dark used to need beside Proxyma (`data-shade`) is gone; applying an appearance removes one an
 * older build's pre-paint script may have left.
 *
 * <p>PLAIN .ts WITH NO IMPORTS AND NO JSX, so `node --test` can load it directly - see
 * appearance.test.ts beside it, which sync.mjs does not ship. Keep it strippable: no constructor
 * parameter properties, no enums. The pre-paint script that repeats `parseAppearance` and
 * `resolveAppearance` before any module exists is generated from one place -
 * `appearanceInitScript` (appearanceScript.ts), held to these two by its test.
 */

export type AppearancePreference = 'system' | 'light' | 'dark'

/** Menu order. */
export const APPEARANCE_PREFERENCES: readonly AppearancePreference[] = ['system', 'light', 'dark']

export type AppearanceMode = 'light' | 'dark'

/** What is actually stamped on <html>. */
export interface ResolvedAppearance {
  mode: AppearanceMode
}

/**
 * A stored string back to a preference.
 *
 * <p>MIGRATES EVERY WORD AN OLDER BUILD WROTE: `gray` (Proxyma, removed 2026-10-28) and `black`
 * (Dark's 0.13.0 name) are Dark, as the two-way flip's `dark` always was. Anything else - nothing
 * stored, storage disabled, a value from some future build - is `fallback`, so an unreadable value
 * never leaves a page with no theme at all.
 */
export function parseAppearance(stored: string | null | undefined, fallback: AppearancePreference): AppearancePreference {
  switch (stored) {
    case 'system':
    case 'light':
    case 'dark':
      return stored
    case 'gray':
    case 'black':
      return 'dark'
    default:
      return fallback
  }
}

/** A preference plus the OS setting to the one attribute. */
export function resolveAppearance(preference: AppearancePreference, systemPrefersDark: boolean): ResolvedAppearance {
  switch (preference) {
    case 'light':
      return { mode: 'light' }
    case 'dark':
      return { mode: 'dark' }
    case 'system':
      return { mode: systemPrefersDark ? 'dark' : 'light' }
  }
}

/** The slice of an element this needs, so a test can pass a plain object instead of a DOM. */
export interface AppearanceTarget {
  setAttribute(name: string, value: string): void
  removeAttribute(name: string): void
}

/** The canvas of a resolved appearance, for the browser chrome - THEME_COLOR in appearanceScript.ts. */
export function themeColor(resolved: ResolvedAppearance): string {
  return resolved.mode === 'light' ? '#ffffff' : '#161617'
}

/**
 * Stamps a resolved appearance on the root element - `document.documentElement` in a browser - and,
 * on a real document, the `theme-color` meta the phone's browser chrome takes its colour from (it
 * kept whatever the OS said while the page changed under it).
 */
export function applyAppearance(target: AppearanceTarget, resolved: ResolvedAppearance): void {
  target.setAttribute('data-mode', resolved.mode)
  // An older build's second attribute - harmless now, since no token reads it, but not left behind.
  target.removeAttribute('data-shade')
  const doc = (target as { ownerDocument?: Document | null }).ownerDocument
  if (doc?.head) {
    let meta = doc.querySelector('meta[name="theme-color"]')
    if (!meta) {
      meta = doc.createElement('meta')
      meta.setAttribute('name', 'theme-color')
      doc.head.appendChild(meta)
    }
    meta.setAttribute('content', themeColor(resolved))
  }
}

/** The media query System follows. */
export const SYSTEM_DARK_QUERY = '(prefers-color-scheme: dark)'
