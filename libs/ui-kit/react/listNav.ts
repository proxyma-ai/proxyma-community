/**
 * THE KEYBOARD CORE OF EVERY LIST THAT OPENS - a Select's options, a Combobox's suggestions, the
 * rows of a menu, a popover menu and a submenu. Plain .ts with no imports, so `node --test` reaches
 * it (`listNav.test.ts`) and the two hooks built on it (`useListbox`, `useMenuFocus`) carry no
 * decisions of their own.
 *
 * Until 2026-10-14 none of these lists had arrow keys: Select and the popover menus were portaled
 * to the end of the body, focus never moved in, and Tab from the trigger went to the next control on
 * the page - inside a dialog, whose Tab trap wraps within the panel, the options could not be
 * reached at all. The rules every list now follows (WAI-ARIA Authoring Practices, listbox and menu):
 *
 * - ArrowDown / ArrowUp move one row, wrapping at the ends; Home / End go to the first and last;
 *   PageDown / PageUp move ten rows and stop at the ends. Disabled rows are skipped.
 * - Typing a printable character jumps to the next row whose label starts with what was typed in
 *   the last half second ("typeahead"); typing the same letter again cycles through those rows.
 * - Enter (and Space, where Space is not text) chooses; Escape and Tab close, and focus returns to
 *   the trigger. Those three live in the hooks, since they act on the page.
 *
 * It must stay STRIPPABLE (no enums, no parameter properties) - see frontend.md.
 */

/** The keys that move the active row. Anything else is not navigation. */
export type NavKey = 'ArrowDown' | 'ArrowUp' | 'Home' | 'End' | 'PageDown' | 'PageUp'

const NAV_KEYS: readonly string[] = ['ArrowDown', 'ArrowUp', 'Home', 'End', 'PageDown', 'PageUp']

/** How many rows PageDown and PageUp move. */
export const PAGE_STEP = 10

/** How long typed characters keep adding to one typeahead query. */
export const TYPEAHEAD_MS = 500

export function isNavKey(key: string): key is NavKey {
  return NAV_KEYS.includes(key)
}

/**
 * The row a navigation key moves to, from `current` (-1 when no row is active yet) in a list of
 * `count` rows. `disabled(i)` says which rows cannot take the focus. Returns -1 when no row can.
 *
 * From nothing active, ArrowDown goes to the first row and ArrowUp to the last, which is how a
 * closed list opened by an arrow key lands.
 */
export function moveIndex(
  current: number,
  count: number,
  key: NavKey,
  disabled: (index: number) => boolean = () => false,
): number {
  if (count <= 0) return -1
  const enabled = (i: number) => i >= 0 && i < count && !disabled(i)
  const firstFrom = (start: number, step: 1 | -1): number => {
    for (let i = start; i >= 0 && i < count; i += step) if (enabled(i)) return i
    return -1
  }

  switch (key) {
    case 'Home':
      return firstFrom(0, 1)
    case 'End':
      return firstFrom(count - 1, -1)
    case 'PageDown':
    case 'PageUp': {
      // Ten single steps that do NOT wrap: a page stops at the end of the list.
      const step = key === 'PageDown' ? 1 : -1
      let at = current >= 0 && current < count ? current : -1
      for (let n = 0; n < PAGE_STEP; n++) {
        const next = at < 0 ? firstFrom(step === 1 ? 0 : count - 1, step) : firstFrom(at + step, step)
        if (next === -1) break
        at = next
      }
      return at
    }
    case 'ArrowDown':
    case 'ArrowUp': {
      const step = key === 'ArrowDown' ? 1 : -1
      if (current < 0 || current >= count) return step === 1 ? firstFrom(0, 1) : firstFrom(count - 1, -1)
      // Wrap: walk the whole ring once, starting past the current row.
      for (let n = 1; n <= count; n++) {
        const i = (current + step * n + count * n) % count
        if (enabled(i)) return i
      }
      return -1
    }
  }
}

/** Whether a key is one typed character that typeahead should take - not a shortcut, not Space alone. */
export function isTypeaheadKey(key: string, mods: { ctrlKey?: boolean; altKey?: boolean; metaKey?: boolean } = {}): boolean {
  return key.length === 1 && key !== ' ' && !mods.ctrlKey && !mods.altKey && !mods.metaKey
}

/**
 * The row whose label starts with `query`, searching from the row AFTER `current` and wrapping - so
 * typing "s" twice moves from the first S to the second. A query of one repeated letter ("sss")
 * cycles the same way, as a desktop list does. Case- and accent-insensitive. -1 when nothing matches.
 */
export function typeaheadIndex(
  labels: readonly string[],
  current: number,
  query: string,
  disabled: (index: number) => boolean = () => false,
): number {
  const count = labels.length
  if (!query || count === 0) return -1
  const fold = (s: string) => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim()
  const q = fold(query)
  const repeated = q.length > 1 && [...q].every(c => c === q[0])
  const needle = repeated ? q[0] : q
  // A longer query refines the CURRENT row if it still matches; one letter (or a repeat) moves on.
  const start = !repeated && q.length > 1 && current >= 0 ? current : current + 1
  for (let n = 0; n < count; n++) {
    const i = (((start + n) % count) + count) % count
    if (!disabled(i) && fold(labels[i] ?? '').startsWith(needle)) return i
  }
  return -1
}

/**
 * The buffer behind typeahead: characters typed within `TYPEAHEAD_MS` of each other add up to one
 * query. `now` is passed in, so the test controls time.
 */
export interface Typeahead {
  /** Adds a character and returns the query so far. */
  push: (char: string, now: number) => string
  reset: () => void
}

export function createTypeahead(timeoutMs: number = TYPEAHEAD_MS): Typeahead {
  let buffer = ''
  let last = -Infinity
  return {
    push(char, now) {
      buffer = now - last > timeoutMs ? char : buffer + char
      last = now
      return buffer
    },
    reset() {
      buffer = ''
      last = -Infinity
    },
  }
}
