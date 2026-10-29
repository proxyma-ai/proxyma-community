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
 * THE PRE-PAINT APPEARANCE SCRIPT, written once (2026-10-14). Each app stamped `data-mode` on <html>
 * before its first paint with a hand-written copy of `parseAppearance` and `resolveAppearance` -
 * three `index.html` files and the landing site's `MODE_INIT_SCRIPT` - each with a comment asking
 * the next person to change all of them together, and nothing holding them to `appearance.ts`. This
 * returns that script for a storage key, and `appearanceScript.test.ts` runs it against every stored
 * word and both OS settings and compares the result with `resolveAppearance`, so the copy can no
 * longer disagree with the source. All four apps use it: the app and both consoles serve it as a
 * file their Vite config emits (their Content-Security-Policy allows no inline script), and the site
 * inlines it.
 *
 * It also writes the `theme-color` meta, so the phone's browser chrome takes the page's canvas from
 * the first frame, and removes the `data-shade` attribute older builds stamped beside Dark. A stored
 * `gray` or `black` (the shades before 2026-10-28) is rewritten as `dark`, so the word the Privacy
 * and Cookie policies name is the only one a browser keeps.
 *
 * Plain .ts with no imports, strippable, for `node --test`.
 */

/** The canvas of each appearance, as the browser chrome's colour (`<meta name="theme-color">`). */
export const THEME_COLOR = {
  light: '#ffffff',
  dark: '#161617',
} as const

/**
 * The script's source for one app's storage key. `fallback` is what nothing stored means - every
 * app follows the operating system (`system`) since 2026-09-25.
 */
export function appearanceInitScript(storageKey: string, fallback: 'system' | 'light' | 'dark' = 'system'): string {
  const key = JSON.stringify(storageKey)
  const colors = JSON.stringify(THEME_COLOR)
  return `(function(){var p=null;try{p=localStorage.getItem(${key})}catch(e){}`
    + `if(p==='gray'||p==='black'){p='dark';try{localStorage.setItem(${key},'dark')}catch(e){}}if(p!=='system'&&p!=='light'&&p!=='dark')p=${JSON.stringify(fallback)};`
    + `if(p==='system'){var d=!window.matchMedia||window.matchMedia('(prefers-color-scheme: dark)').matches;p=d?'dark':'light'}`
    + `var r=document.documentElement;r.setAttribute('data-mode',p);r.removeAttribute('data-shade');`
    + `var c=${colors}[p];var m=document.querySelector('meta[name="theme-color"]');`
    + `if(!m){m=document.createElement('meta');m.setAttribute('name','theme-color');document.head.appendChild(m)}`
    + `m.setAttribute('content',c)})()`
}
