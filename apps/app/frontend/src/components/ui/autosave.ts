/**
 * AN EXISTING ITEM SAVED FIELD BY FIELD (the maintainer, 2026-11-02: in an add/edit dialog "an existing
 * item is edited in place, field by field, each saving on blur; adding a new item keeps the form").
 *
 * The endpoints these dialogs write through take the WHOLE item (a skill, a provider, a connection), so
 * one field's change is sent as the item with that one field changed. Two fields left in quick
 * succession would otherwise race: the second request, built before the first had answered, would put
 * the first field back. The queue sends them one at a time, each built on what the server last
 * confirmed, and a refused change leaves nothing of itself behind - neither on screen nor in the next
 * request. Plain .ts with no DOM, so `node --test` reads it (autosave.test.ts).
 */

export interface SaveQueue<T extends object> {
  /** What the page shows: the last state the server confirmed, with every change still on its way laid over it. */
  shown(): T
  /** The last state the server confirmed. */
  saved(): T
  /** Sends `patch` once every change before it has settled. Resolves with the confirmed state; rejects as the send did. */
  save(patch: Partial<T>): Promise<T>
  /** A fresher state from elsewhere (a refetch). Taken only while nothing is on its way - returns whether it was. */
  rebase(next: T): boolean
  /** Whether a change is on its way. */
  busy(): boolean
  /** Settles once every change made so far has, whichever way - before a step that leaves the page. */
  idle(): Promise<void>
}

/**
 * `send` is given the whole item with the patch applied over the last confirmed state, and the patch
 * alone; it answers with the confirmed state (or nothing, when the sent one is it). `onChange` hears
 * every change of `shown()`.
 */
export function createSaveQueue<T extends object>(
  base: T,
  send: (next: T, patch: Partial<T>) => Promise<T | void>,
  onChange: (shown: T) => void = () => {},
): SaveQueue<T> {
  let saved = base
  // Each change still on its way, in the order it was made. A holder, so two equal patches stay two.
  const queued: { patch: Partial<T> }[] = []
  let tail: Promise<unknown> = Promise.resolve()

  const shown = (): T => queued.reduce<T>((acc, q) => ({ ...acc, ...q.patch }), saved)

  const save = (patch: Partial<T>): Promise<T> => {
    const entry = { patch }
    queued.push(entry)
    onChange(shown())
    const run = tail.then(async () => {
      const next = { ...saved, ...patch }
      try {
        const result = await send(next, patch)
        saved = result ?? next
        return saved
      } finally {
        queued.splice(queued.indexOf(entry), 1)
        onChange(shown())
      }
    })
    // The next change waits for this one to settle, whichever way it settles.
    tail = run.catch(() => undefined)
    return run
  }

  const rebase = (next: T): boolean => {
    if (queued.length > 0) return false
    saved = next
    onChange(shown())
    return true
  }

  return {
    shown, saved: () => saved, save, rebase, busy: () => queued.length > 0,
    idle: () => tail.then(() => undefined),
  }
}

/**
 * WHAT A DIALOG SAYS ABOUT ITS SAVES (the maintainer, 2026-11-03: a Client ID and secret pasted into an
 * existing connection "seems not saved or not have any indicator saying that the new value is saved").
 * The Settings window's quiet word beside its title, for every dialog that saves field by field: "Saving..."
 * while any change is on its way, then "Saved." for a moment once the last one is confirmed. A refusal
 * says nothing here - it is said where it happened (a toast, a line under the fields) and the value goes
 * back - and it takes away a "Saved." an earlier change left, which would now be untrue.
 */
export const SAVED_SHOWN_MS = 2500

export interface SaveStatus {
  /** Changes sent and not yet answered. */
  inFlight: number
  /** When the last change was confirmed (ms); null when nothing was, or a refusal came after. */
  savedAt: number | null
}

export type SaveWord = 'saving' | 'saved' | null

export const SAVE_IDLE: SaveStatus = { inFlight: 0, savedAt: null }

/** A change sent. */
export function saveStarted(s: SaveStatus): SaveStatus {
  return { inFlight: s.inFlight + 1, savedAt: s.savedAt }
}

/** A change answered - confirmed (`ok`) or refused - at `now`. */
export function saveSettled(s: SaveStatus, ok: boolean, now: number): SaveStatus {
  return { inFlight: Math.max(0, s.inFlight - 1), savedAt: ok ? now : null }
}

/** The word to show at `now`. */
export function saveWord(s: SaveStatus, now: number): SaveWord {
  if (s.inFlight > 0) return 'saving'
  return s.savedAt !== null && now - s.savedAt < SAVED_SHOWN_MS ? 'saved' : null
}

/** One word for a dialog with several save queues: saving while any is, else saved while any says so. */
export function combinedSaveWord(...words: SaveWord[]): SaveWord {
  if (words.includes('saving')) return 'saving'
  return words.includes('saved') ? 'saved' : null
}

/** Words typed in a comma-separated box, as a list: trimmed, the empty ones and repeats dropped. */
export function commaList(text: string): string[] {
  return [...new Set(text.split(',').map(s => s.trim()).filter(Boolean))]
}

/** Whether two lists hold the same entries, in any order - a set the reader sees as a set. */
export function sameSet(a: readonly string[], b: readonly string[]): boolean {
  if (a.length !== b.length) return false
  const s = new Set(a)
  return b.every(x => s.has(x))
}

export type NumberProblem = { code: 'whole'; min: number; max: number } | { code: 'decimal' }

/** What is wrong with a typed whole number between `min` and `max`; null when nothing is. */
export function wholeNumberProblem(text: string, min: number, max: number): NumberProblem | null {
  const v = text.trim()
  if (!/^\d+$/.test(v)) return { code: 'whole', min, max }
  const n = Number(v)
  return n < min || n > max ? { code: 'whole', min, max } : null
}

/** What is wrong with a typed amount that may be left empty: a number of zero or more; null when nothing is. */
export function decimalProblem(text: string): NumberProblem | null {
  const v = text.trim()
  if (v === '') return null
  return /^\d+(\.\d+)?$|^\.\d+$/.test(v) ? null : { code: 'decimal' }
}
