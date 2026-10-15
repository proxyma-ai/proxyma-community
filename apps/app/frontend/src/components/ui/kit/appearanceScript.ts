/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/appearanceScript.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * THE PRE-PAINT APPEARANCE SCRIPT, written once (2026-10-14). Each app stamped `data-mode` and
 * `data-shade` on <html> before its first paint with a hand-written copy of `parseAppearance` and
 * `resolveAppearance` - three `index.html` files and the landing site's `MODE_INIT_SCRIPT` - each
 * with a comment asking the next person to change all of them together, and nothing holding them to
 * `appearance.ts`. This returns that script for a storage key, and `appearanceScript.test.ts` runs
 * it against every stored word and both OS settings and compares the result with
 * `resolveAppearance`, so the copy can no longer disagree with the source.
 *
 * Inline it in the document head (a Vite `transformIndexHtml` hook, or Next's `<script
 * dangerouslySetInnerHTML>`); it runs before any module exists. It also writes the
 * `theme-color` meta, so the phone's browser chrome takes the page's canvas from the first frame.
 *
 * Plain .ts with no imports, strippable, for `node --test`.
 */

/** The canvas of each appearance, as the browser chrome's colour (`<meta name="theme-color">`). */
export const THEME_COLOR = {
  light: '#ffffff',
  /** Proxyma: n950. */
  gray: '#11181a',
  /** Dark: the neutral #181818. */
  black: '#181818',
} as const

/**
 * The script's source for one app's storage key. `fallback` is what nothing stored means - every
 * app follows the operating system (`system`) since 2026-09-25.
 */
export function appearanceInitScript(storageKey: string, fallback: 'system' | 'light' | 'gray' | 'black' = 'system'): string {
  const key = JSON.stringify(storageKey)
  const colors = JSON.stringify(THEME_COLOR)
  return `(function(){var p=null;try{p=localStorage.getItem(${key})}catch(e){}`
    + `if(p==='dark')p='gray';if(p!=='system'&&p!=='light'&&p!=='gray'&&p!=='black')p=${JSON.stringify(fallback)};`
    + `if(p==='system'){var d=!window.matchMedia||window.matchMedia('(prefers-color-scheme: dark)').matches;p=d?'black':'light'}`
    + `var r=document.documentElement;r.setAttribute('data-mode',p==='light'?'light':'dark');`
    + `if(p==='black')r.setAttribute('data-shade','black');else r.removeAttribute('data-shade');`
    + `var c=${colors}[p];var m=document.querySelector('meta[name="theme-color"]');`
    + `if(!m){m=document.createElement('meta');m.setAttribute('name','theme-color');document.head.appendChild(m)}`
    + `m.setAttribute('content',c)})()`
}
