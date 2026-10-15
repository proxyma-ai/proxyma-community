/**
 * Whether a dialog's form holds unsaved work: the CURRENT values compared with a snapshot of the
 * values it opened with.
 *
 * <p>VALUES, NOT "WAS TOUCHED". Typing a character and deleting it again leaves nothing to lose,
 * and a dialog that asks "discard your changes?" when there are none teaches its reader to click
 * Discard without reading - which is the one habit the question exists to prevent.
 *
 * <p>Plain `.ts` with no JSX so `node --test` reaches it: see `formSnapshot.test.ts` beside it.
 */

/**
 * Structural equality over what a form holds: primitives, arrays, plain objects, Dates, Sets and
 * Maps. `undefined` and a missing key are the same thing, because a form that never set an
 * optional field and one that set it to nothing hold the same work. Functions compare by identity.
 */
export function sameValue(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false

  if (a instanceof Date || b instanceof Date) {
    return a instanceof Date && b instanceof Date && a.getTime() === b.getTime()
  }

  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false
    return a.every((v, i) => sameValue(v, b[i]))
  }

  if (a instanceof Set || b instanceof Set) {
    if (!(a instanceof Set) || !(b instanceof Set) || a.size !== b.size) return false
    for (const v of a) if (!b.has(v)) return false
    return true
  }

  if (a instanceof Map || b instanceof Map) {
    if (!(a instanceof Map) || !(b instanceof Map) || a.size !== b.size) return false
    for (const [k, v] of a) if (!b.has(k) || !sameValue(v, b.get(k))) return false
    return true
  }

  const ra = a as Record<string, unknown>
  const rb = b as Record<string, unknown>
  const keys = new Set([...Object.keys(ra), ...Object.keys(rb)])
  for (const k of keys) {
    if (!sameValue(ra[k], rb[k])) return false
  }
  return true
}
