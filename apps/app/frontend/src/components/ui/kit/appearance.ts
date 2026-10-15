/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/appearance.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * Appearance - the four-way preference behind every theme menu, as PURE functions.
 *
 * <p>System, Light, Proxyma and Dark, since 0.13.0. It was a two-way light/dark flip before that,
 * and the old "dark" is what Proxyma is now: the teal-tinted n950 canvas the product has always
 * had. Dark is a neutral VS Code-style gray beside it, and System follows the operating system
 * live - light to Light, dark to Dark (since 2026-09-26; it was Proxyma until then).
 *
 * <p>THE STORED VALUES ARE STILL `gray` AND `black`, the names Proxyma and Dark shipped under in
 * 0.13.0, when Dark was a true black. Renaming them would need a second migration and would make
 * the Privacy Policy's list of stored words untrue; only what a person reads changed.
 *
 * <p>TWO ATTRIBUTES ON <html>, NOT ONE WITH FOUR VALUES. `data-mode` stays the light/dark axis
 * it always was, so every `[data-mode="dark"]` selector in four frontends and the Tailwind preset's
 * `darkMode` keep matching for both dark shades without being touched. `data-shade="black"` is the
 * second axis and exists only when Dark (`black`) is in effect; proxyma-tokens.css re-points the
 * plane and ink tokens under it. A page with neither script run still paints Proxyma, from the
 * bare :root block.
 *
 * <p>PLAIN .ts WITH NO IMPORTS AND NO JSX, so `node --test` can load it directly - see
 * appearance.test.ts beside it, which sync.mjs does not ship. Keep it strippable: no constructor
 * parameter properties, no enums. The pre-paint script that repeats `parseAppearance` and
 * `resolveAppearance` before any module exists is generated from one place since 2026-10-14 -
 * `appearanceInitScript` (appearanceScript.ts), held to these two by its test.
 */

export type AppearancePreference = 'system' | 'light' | 'gray' | 'black'

/** Menu order. */
export const APPEARANCE_PREFERENCES: readonly AppearancePreference[] = ['system', 'light', 'black', 'gray']

export type AppearanceMode = 'light' | 'dark'

/** What is actually stamped on <html>. `shade` is `black` only for Dark, and null otherwise. */
export interface ResolvedAppearance {
  mode: AppearanceMode
  shade: 'black' | null
}

/**
 * A stored string back to a preference.
 *
 * <p>MIGRATES THE TWO-WAY VALUES every existing install has written: `dark` becomes `gray`, which
 * is the same canvas under its newer name, and `light` stays `light`. Anything else - nothing
 * stored, storage disabled, a value from some future build - is `fallback`, so an unreadable
 * value never leaves a page with no theme at all.
 */
export function parseAppearance(stored: string | null | undefined, fallback: AppearancePreference): AppearancePreference {
  switch (stored) {
    case 'system':
    case 'light':
    case 'gray':
    case 'black':
      return stored
    case 'dark':
      return 'gray'
    default:
      return fallback
  }
}

/**
 * A preference plus the OS setting to the two attributes. System maps a light OS to Light and a
 * dark OS to Dark (`black`), the neutral one - Proxyma's teal is the one a person picks on purpose.
 */
export function resolveAppearance(preference: AppearancePreference, systemPrefersDark: boolean): ResolvedAppearance {
  switch (preference) {
    case 'light':
      return { mode: 'light', shade: null }
    case 'gray':
      return { mode: 'dark', shade: null }
    case 'black':
      return { mode: 'dark', shade: 'black' }
    case 'system':
      // A dark OS means Dark - the neutral one - not Proxyma's teal. Changed 2026-09-26 at the
      // maintainer's request; System was Proxyma on a dark OS until then.
      return systemPrefersDark ? { mode: 'dark', shade: 'black' } : { mode: 'light', shade: null }
  }
}

/** The slice of an element this needs, so a test can pass a plain object instead of a DOM. */
export interface AppearanceTarget {
  setAttribute(name: string, value: string): void
  removeAttribute(name: string): void
}

/** The canvas of a resolved appearance, for the browser chrome - THEME_COLOR in appearanceScript.ts. */
export function themeColor(resolved: ResolvedAppearance): string {
  if (resolved.mode === 'light') return '#ffffff'
  return resolved.shade === 'black' ? '#181818' : '#11181a'
}

/**
 * Stamps a resolved appearance on the root element - `document.documentElement` in a browser - and,
 * on a real document, the `theme-color` meta the phone's browser chrome takes its colour from (it
 * kept whatever the OS said while the page changed under it).
 */
export function applyAppearance(target: AppearanceTarget, resolved: ResolvedAppearance): void {
  target.setAttribute('data-mode', resolved.mode)
  if (resolved.shade) target.setAttribute('data-shade', resolved.shade)
  else target.removeAttribute('data-shade')
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
