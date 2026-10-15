/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/errorRecovery.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * WHAT A RENDER ERROR MEANS, AND WHETHER TO RELOAD FOR IT (2026-10-14, the cross-app review's X14).
 *
 * The usual error after a deploy is not a bug in the page: a tab still holding the old index asks
 * for a lazily loaded chunk whose hashed name no longer exists, and the import rejects. Reloading
 * once fetches the new index and the new chunk names, and the reader never needs to know. A second
 * failure soon after is a real fault, and reloading again would loop - so the reload is claimed in
 * session storage and granted once per window. Plain `.ts` with no imports, so `node --test`
 * reaches it.
 */

/** The messages browsers and bundlers give a dynamic import that could not be fetched. */
const CHUNK_LOAD_PATTERNS = [
  /Failed to fetch dynamically imported module/i, // Chromium
  /error loading dynamically imported module/i, // Firefox
  /Importing a module script failed/i, // Safari
  /Unable to preload CSS/i, // Vite's own preload helper
  /Loading (CSS )?chunk [\w-]+ failed/i, // webpack / Next.js
]

export function isChunkLoadError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const { name, message } = error as { name?: unknown; message?: unknown }
  if (name === 'ChunkLoadError') return true
  return typeof message === 'string' && CHUNK_LOAD_PATTERNS.some(p => p.test(message))
}

export const CHUNK_RELOAD_KEY = 'proxyma.chunkReloadAt'

/** How long one automatic reload covers: a second chunk failure inside it is shown, not retried. */
export const CHUNK_RELOAD_WINDOW_MS = 60_000

export interface ReloadClaimStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

/**
 * True when the caller should reload now, and records that it did. False when a reload already
 * happened inside the window - or when there is no storage to record it in, since a reload that
 * cannot be counted could repeat for ever.
 */
export function claimChunkReload(storage: ReloadClaimStorage | null, now: number, windowMs = CHUNK_RELOAD_WINDOW_MS): boolean {
  if (!storage) return false
  try {
    const last = Number(storage.getItem(CHUNK_RELOAD_KEY))
    if (Number.isFinite(last) && last > 0 && now - last >= 0 && now - last < windowMs) return false
    storage.setItem(CHUNK_RELOAD_KEY, String(now))
    return true
  } catch {
    return false
  }
}
