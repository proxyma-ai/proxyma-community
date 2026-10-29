import { useSyncExternalStore } from 'react'

/** Tailwind's `lg`: where the glass shell's sidebar opens out to its words. */
const WIDE = '(min-width: 1024px)'
/** Tailwind's `md`: where a browser takes the glass frame at all - its sidebar folded below `lg`. */
const FRAMED = '(min-width: 768px)'

function matches(query: string): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(query).matches
}

function useMedia(query: string): boolean {
  return useSyncExternalStore(
    onChange => {
      if (typeof window.matchMedia !== 'function') return () => {}
      const list = window.matchMedia(query)
      list.addEventListener('change', onChange)
      return () => list.removeEventListener('change', onChange)
    },
    () => matches(query),
    () => false,
  )
}

/**
 * Whether the window is wide enough for the sidebar's words (AppShell). A hook rather than a CSS
 * class because the two shells hold the ONE page between them: drawing both and hiding one would
 * mount every page twice.
 */
export function useWideLayout(): boolean {
  return useMedia(WIDE)
}

/**
 * Whether a browser draws the glass frame - from a tablet's width up, the sidebar folded to its
 * icons below `lg`, as the desktop app's narrow window is. A phone keeps the header.
 */
export function useFramedLayout(): boolean {
  return useMedia(FRAMED)
}
