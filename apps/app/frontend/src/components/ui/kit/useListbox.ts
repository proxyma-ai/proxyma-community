/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/useListbox.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import { createTypeahead, isNavKey, isTypeaheadKey, moveIndex, typeaheadIndex } from './listNav'

/**
 * THE KEYBOARD OF A LIST CHOSEN FROM A FIELD - `Select` and `Combobox` (WAI-ARIA APG, combobox with
 * a listbox popup). FOCUS STAYS ON THE FIELD: the trigger button, or the text input, holds focus
 * while the arrows move an ACTIVE row that it names in `aria-activedescendant`. That is the point
 * of the pattern here and not a detail - the list is a portal at the end of the body, and inside a
 * dialog (whose Tab trap wraps within its own panel) focus moved into it had nowhere to go next.
 *
 * - Closed: ArrowDown or ArrowUp opens it, on the chosen row (or the first or last).
 * - Open: arrows, Home/End, PageUp/PageDown move the active row; typing jumps by label when the
 *   field is a button (`labels`); Enter chooses - and Space too on a button (`spaceSelects`).
 * - Escape closes it and is marked handled, so the dialog beneath stays open; Tab closes it and
 *   lets focus move on.
 *
 * The component renders the rows; `optionProps(i)` gives each its id, its active mark (`data-active`,
 * which the row styles) and the pointer hover that moves the active row with the mouse.
 */
export interface ListboxOptions {
  open: boolean
  setOpen: (open: boolean) => void
  /** How many rows the list shows now. */
  count: number
  /** The chosen row, active when the list opens. -1 for none. */
  selectedIndex?: number
  /** Each row's text, for typeahead. Omit where typing goes into a text field instead. */
  labels?: readonly string[]
  disabled?: (index: number) => boolean
  onPick: (index: number) => void
  /** Space chooses, as on a button. Never for a text field, where Space is a character. */
  spaceSelects?: boolean
  /** Activate the first row whenever the rows change (a filtered list), rather than none. */
  activateFirst?: boolean
}

export interface Listbox {
  /** The list's id - for `aria-controls` on the field and `id` on the list. */
  listId: string
  optionId: (index: number) => string
  activeIndex: number
  setActiveIndex: (index: number) => void
  /** For the field (trigger button or text input). */
  onKeyDown: (e: ReactKeyboardEvent<HTMLElement>) => void
  /** Spread on the field. */
  fieldProps: { 'aria-controls': string; 'aria-expanded': boolean; 'aria-activedescendant'?: string }
  /** Spread on each row. */
  optionProps: (index: number) => { id: string; 'data-active'?: true; onMouseMove: () => void }
}

export function useListbox({
  open, setOpen, count, selectedIndex = -1, labels, disabled, onPick, spaceSelects = false, activateFirst = false,
}: ListboxOptions): Listbox {
  const listId = useId()
  const [activeIndex, setActive] = useState(-1)
  const typeahead = useRef(createTypeahead())
  // The arrow that opened the list, so it opens on the first row or the last.
  const openedBy = useRef<string | null>(null)
  const optionId = useCallback((i: number) => `${listId}-option-${i}`, [listId])
  const isDisabled = disabled ?? (() => false)

  // On open: the chosen row, or the first one where the list leads with it (a filtered list).
  useEffect(() => {
    if (!open) { setActive(-1); typeahead.current.reset(); return }
    const by = openedBy.current
    openedBy.current = null
    if (selectedIndex >= 0 && selectedIndex < count) setActive(selectedIndex)
    else if (by === 'ArrowUp') setActive(moveIndex(-1, count, 'End', isDisabled))
    else if (by === 'ArrowDown' || activateFirst) setActive(moveIndex(-1, count, 'Home', isDisabled))
    else setActive(-1)
    // Only on open: later changes to the chosen row come from this list itself.
  }, [open])

  // The rows changed (filtering) - keep an active row that still exists, or lead with the first.
  useEffect(() => {
    if (!open) return
    setActive(a => (activateFirst ? moveIndex(-1, count, 'Home', isDisabled) : a < count ? a : -1))
  }, [count, activateFirst])

  // The active row stays in view as the arrows move it through a list that scrolls.
  useEffect(() => {
    if (!open || activeIndex < 0) return
    document.getElementById(optionId(activeIndex))?.scrollIntoView?.({ block: 'nearest' })
  }, [open, activeIndex, optionId])

  const onKeyDown = useCallback((e: ReactKeyboardEvent<HTMLElement>) => {
    if (e.defaultPrevented || e.nativeEvent.isComposing) return
    if (!open) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        openedBy.current = e.key
        setOpen(true)
      }
      return
    }
    if (isNavKey(e.key)) {
      e.preventDefault()
      setActive(a => moveIndex(a, count, e.key as 'ArrowDown', isDisabled))
      return
    }
    if (e.key === 'Enter' || (e.key === ' ' && spaceSelects)) {
      if (activeIndex >= 0 && !isDisabled(activeIndex)) {
        e.preventDefault()
        onPick(activeIndex)
      } else if (e.key === ' ') {
        e.preventDefault()
      }
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      setOpen(false)
      return
    }
    if (e.key === 'Tab') {
      setOpen(false)
      return
    }
    if (labels && isTypeaheadKey(e.key, e)) {
      const at = typeaheadIndex(labels, activeIndex, typeahead.current.push(e.key, performance.now()), isDisabled)
      if (at >= 0) { e.preventDefault(); setActive(at) }
    }
  }, [open, setOpen, count, labels, activeIndex, onPick, spaceSelects, disabled])

  return {
    listId,
    optionId,
    activeIndex,
    setActiveIndex: setActive,
    onKeyDown,
    fieldProps: {
      'aria-controls': listId,
      'aria-expanded': open,
      ...(open && activeIndex >= 0 ? { 'aria-activedescendant': optionId(activeIndex) } : {}),
    },
    optionProps: (index: number) => ({
      id: optionId(index),
      ...(index === activeIndex ? { 'data-active': true as const } : {}),
      onMouseMove: () => { if (index !== activeIndex && !isDisabled(index)) setActive(index) },
    }),
  }
}
