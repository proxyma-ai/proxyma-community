/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/toolFold.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * WHICH CALLS OF A TOOL GROUP ARE DRAWN (maintainer's request, 2026-10-13). Folded - the default -
 * every call still running is drawn, however many run in parallel, and each one folds away as it
 * finishes; once none is running, the last call alone is left. Opened by the chevron, every call is
 * drawn.
 *
 * `foldable` is whether folding hides anything at all - the chevron is drawn only then. While every
 * call is still running there is nothing to hide, and no chevron.
 *
 * It replaced a list that was open by default inside a box five rows tall, which scrolled, so a
 * burst of parallel calls showed three of them and hid the rest under a fade.
 */
export interface ToolFold {
  shown: boolean[]
  foldable: boolean
}

export function toolFold(done: readonly boolean[], expanded: boolean): ToolFold {
  const n = done.length
  if (n === 0) return { shown: [], foldable: false }
  const folded = done.map(d => !d)
  if (!folded.some(Boolean)) folded[n - 1] = true
  const foldable = folded.some(s => !s)
  return { shown: expanded && foldable ? done.map(() => true) : folded, foldable }
}
