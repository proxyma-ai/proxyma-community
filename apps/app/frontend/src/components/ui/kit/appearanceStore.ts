/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/appearanceStore.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useSyncExternalStore } from 'react'
import { SYSTEM_DARK_QUERY, applyAppearance, parseAppearance, resolveAppearance, type AppearancePreference } from './appearance'
import { applyAppearanceSmoothly } from './motion'

/**
 * AN APPEARANCE PREFERENCE, STORED AND APPLIED - the module both consoles carried byte for byte
 * (`theme.ts`, 65 lines) and the app and the site wrote again in their own state layers. One
 * `useSyncExternalStore` store per storage key: read once, applied to <html> at once (so the first
 * frame after the pre-paint script agrees with it), System following the OS live, a change while
 * open crossfaded (`applyAppearanceSmoothly`), and the choice written back under the app's key.
 *
 * Keys stay per app: `proxyma-admin-mode` (both consoles), the app's and the site's - the site's
 * stored words are a published claim in its Privacy and Cookie policies. Pair it with
 * `appearanceInitScript(key)` in the document head.
 *
 * Call it once, at module scope; on a server (the site's prerender) it does nothing until a browser
 * runs it.
 */
export interface AppearanceStore {
  getAppearance: () => AppearancePreference
  setAppearance: (preference: AppearancePreference) => void
  useAppearance: () => AppearancePreference
}

export function createAppearanceStore(storageKey: string, fallback: AppearancePreference = 'system'): AppearanceStore {
  const listeners = new Set<() => void>()
  const browser = typeof window !== 'undefined'
  const query = browser && typeof window.matchMedia === 'function' ? window.matchMedia(SYSTEM_DARK_QUERY) : null

  const read = (): AppearancePreference => {
    try { return parseAppearance(localStorage.getItem(storageKey), fallback) } catch { return fallback }
  }
  let current: AppearancePreference = browser ? read() : fallback

  const apply = (preference: AppearancePreference, smoothly: boolean) => {
    if (!browser) return
    const resolved = resolveAppearance(preference, query?.matches ?? true)
    if (smoothly) applyAppearanceSmoothly(resolved)
    else applyAppearance(document.documentElement, resolved)
  }
  apply(current, false)
  query?.addEventListener('change', () => { if (current === 'system') apply(current, true) })

  return {
    getAppearance: () => current,
    setAppearance(preference) {
      current = preference
      try { localStorage.setItem(storageKey, preference) } catch { /* storage disabled - this page view still changes */ }
      apply(preference, true)
      listeners.forEach(l => l())
    },
    useAppearance() {
      return useSyncExternalStore(
        listener => { listeners.add(listener); return () => listeners.delete(listener) },
        () => current,
        () => fallback,
      )
    },
  }
}
