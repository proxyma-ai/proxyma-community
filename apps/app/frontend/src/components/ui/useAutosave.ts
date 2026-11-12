import { useEffect, useRef, useState } from 'react'
import { useToastStore } from '@/store/toastStore'
import { withReason } from '@/services/apiError'
import {
  SAVE_IDLE, SAVED_SHOWN_MS, createSaveQueue, saveSettled, saveStarted, saveWord, type SaveQueue, type SaveStatus,
  type SaveWord,
} from './autosave'

/**
 * An existing item edited field by field (autosave.ts): `value` is what the dialog shows - the item as
 * the server last confirmed it with every change still on its way laid over it - and `save(patch)`
 * sends the item with that change. A refused change is taken off the page and said in a toast
 * (`failure`, with the server's reason), and the promise rejects - so an EditableText puts its old text
 * back. `onError` replaces the toast, for a dialog that shows the refusal against its field instead.
 *
 * `status` is what the dialog says about its saves - "Saving...", then "Saved." for a moment (SaveStatus,
 * beside the dialog's title). `idle()` settles once every change made so far has: a step that leaves the
 * page (a sign-in that navigates) waits for it, so a field left by that very click is not lost.
 *
 * `base` may arrive late (a detail fetched after the dialog opened): `value` is undefined until it
 * does, and the dialog keeps its fields closed until then - a save built on a half-loaded item would
 * blank what had not arrived. A fresher `base` (a refetch) is taken while nothing is on its way.
 */
export function useAutosave<T extends object>(
  base: T | undefined,
  send: (next: T, patch: Partial<T>) => Promise<T | void>,
  options: { failure: string; onError?: (e: unknown) => void; onSaved?: (saved: T) => void },
): { value: T | undefined; save: (patch: Partial<T>) => Promise<T>; busy: boolean; status: SaveWord; idle: () => Promise<void> } {
  const addToast = useToastStore(s => s.addToast)
  const latest = useRef({ send, options })
  latest.current = { send, options }
  const queue = useRef<SaveQueue<T> | null>(null)
  const [value, setValue] = useState<T | undefined>(base)
  const [busy, setBusy] = useState(false)
  const [saveStatus, setSaveStatus] = useState<SaveStatus>(SAVE_IDLE)
  // Re-renders once "Saved." has been up for its moment, so it goes.
  const [, setExpired] = useState(0)
  useEffect(() => {
    if (saveStatus.savedAt === null) return
    const timer = setTimeout(() => setExpired(n => n + 1), SAVED_SHOWN_MS)
    return () => clearTimeout(timer)
  }, [saveStatus.savedAt])

  if (base !== undefined && queue.current === null) {
    queue.current = createSaveQueue<T>(base, (next, patch) => latest.current.send(next, patch), shown => {
      setValue(shown)
      setBusy(queue.current?.busy() ?? false)
    })
  }

  // Compared by content, so a caller that builds `base` afresh on every render does not rebase it each time.
  const baseKey = base === undefined ? '' : JSON.stringify(base)
  useEffect(() => {
    if (base !== undefined && queue.current) queue.current.rebase(base)
    // Only a new base from outside; the queue's own changes arrive through onChange.
  }, [baseKey])

  const save = (patch: Partial<T>): Promise<T> => {
    const q = queue.current
    if (!q) return Promise.reject(new Error('not loaded'))
    setSaveStatus(saveStarted)
    return q.save(patch).then(
      saved => {
        setSaveStatus(s => saveSettled(s, true, Date.now()))
        latest.current.options.onSaved?.(saved)
        return saved
      },
      e => {
        setSaveStatus(s => saveSettled(s, false, Date.now()))
        const { failure, onError } = latest.current.options
        if (onError) onError(e)
        else addToast(withReason(failure, e), 'danger')
        throw e
      },
    )
  }

  return {
    value: queue.current ? value ?? queue.current.shown() : undefined,
    save,
    busy,
    status: saveWord(saveStatus, Date.now()),
    idle: () => queue.current?.idle() ?? Promise.resolve(),
  }
}
