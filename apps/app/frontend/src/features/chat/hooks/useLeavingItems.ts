import { useLayoutEffect, useRef, useState } from 'react'
import { MOTION_MS, prefersReducedMotion } from '@/components/ui/kit'

/**
 * A LIST WHOSE ITEMS LEAVE BY EASING OUT - the prompts at the foot of the thread, which the store
 * drops the moment they are answered. Each item comes back with `leaving`, and one just dropped is
 * kept where it stood for `ms` with `leaving: true`, so the caller can draw every item inside the
 * kit's `<Presence show={!leaving}>` and it collapses away instead of vanishing in one frame with
 * the thread jumping under it. (The kit's Presence keeps ONE thing for its exit; a list needs its
 * dropped members kept too, which is this. The toast stack does the same by hand.)
 *
 * Reduced motion keeps nothing: an item goes when the store drops it.
 */
export function useLeavingItems<T>(items: T[], keyOf: (item: T) => string, ms: number = MOTION_MS.base): Array<{ item: T; key: string; leaving: boolean }> {
  const [gone, setGone] = useState<Array<{ item: T; key: string; index: number }>>([])
  const previous = useRef(items)
  const keyRef = useRef(keyOf)
  keyRef.current = keyOf
  // Before paint, or the dropped item would be missing for one frame and then come back to leave.
  useLayoutEffect(() => {
    const keys = new Set(items.map(i => keyRef.current(i)))
    const removed = previous.current.flatMap((item, index) => {
      const key = keyRef.current(item)
      return keys.has(key) ? [] : [{ item, key, index }]
    })
    previous.current = items
    if (removed.length === 0 || prefersReducedMotion()) return
    setGone(g => [...g.filter(x => !removed.some(r => r.key === x.key)), ...removed])
    // Not cleared by the next change: a second drop inside the window must not keep the first for
    // ever. A set after unmount is a no-op.
    setTimeout(() => setGone(g => g.filter(x => !removed.some(r => r.key === x.key))), ms)
  }, [items, ms])
  const out = items.map(item => ({ item, key: keyOf(item), leaving: false }))
  for (const { item, key, index } of gone) {
    if (!out.some(o => o.key === key)) out.splice(Math.min(index, out.length), 0, { item, key, leaving: true })
  }
  return out
}
