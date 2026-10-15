import { useCallback } from 'react'
import { create } from 'zustand'

/**
 * What the user was typing, kept while they are elsewhere in the app.
 *
 * The chat page held its composer in component state, so leaving for Sources and coming back threw
 * the draft away - text, @mentions, picked skills, attached files. It lives here now, outside the
 * page, keyed by where it was typed: `hero` for the new-chat page, and the conversation's own id
 * for a conversation, so switching between conversations keeps each one's draft to itself.
 *
 * IN MEMORY ONLY, deliberately. A draft is what somebody was halfway through saying; writing it to
 * browser storage would put it on disk, unasked, where it outlives the session and the app.
 */
type Drafts = Record<string, Record<string, unknown>>

interface ComposerDraftState {
  drafts: Drafts
  set: (key: string, field: string, value: unknown) => void
}

export const useComposerDraftStore = create<ComposerDraftState>(set => ({
  drafts: {},
  set: (key, field, value) => set(s => ({
    drafts: { ...s.drafts, [key]: { ...s.drafts[key], [field]: value } },
  })),
}))

/**
 * `useState`, kept in the draft store under `key` / `field`. The setter takes a value or an updater,
 * as React's does, and is stable for a given key and field.
 */
export function useDraftState<T>(key: string, field: string, initial: T): [T, (next: T | ((prev: T) => T)) => void] {
  const stored = useComposerDraftStore(s => s.drafts[key]?.[field]) as T | undefined
  const setValue = useCallback((next: T | ((prev: T) => T)) => {
    const store = useComposerDraftStore.getState()
    const prev = (store.drafts[key]?.[field] as T | undefined) ?? initial
    store.set(key, field, typeof next === 'function' ? (next as (p: T) => T)(prev) : next)
    // `initial` is a constant at every call site; leaving it out keeps the setter stable.
  }, [key, field])
  return [stored ?? initial, setValue]
}
