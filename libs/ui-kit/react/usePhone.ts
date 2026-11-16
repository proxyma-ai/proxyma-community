'use client'

import { useSyncExternalStore } from 'react'

/** Below Tailwind's `sm`: a phone held upright. */
export const PHONE_QUERY = '(max-width: 639.98px)'

/**
 * Whether the window is a phone's - for a part whose phone layout is another element, not another class (the Settings
 * window's list, a sort's single chip). Followed as the window changes; false on the server.
 */
export function usePhone(): boolean {
  return useSyncExternalStore(
    onChange => {
      if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return () => {}
      const list = window.matchMedia(PHONE_QUERY)
      list.addEventListener('change', onChange)
      return () => list.removeEventListener('change', onChange)
    },
    () => typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(PHONE_QUERY).matches,
    () => false,
  )
}
