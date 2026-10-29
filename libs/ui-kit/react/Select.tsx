'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Check, ChevronDown } from 'lucide-react'
import { cn } from './cn'
import { FIELD_BASE, fieldBorder } from './field'
import { exitingProps, usePresence } from './motion'
import { useListbox } from './useListbox'

export interface SelectOption<T extends string = string> {
  value: T
  label: string
  /** A decorative glyph before the label, in the trigger and the list - a source's mark, say. */
  icon?: ReactNode
}

// The gap between trigger and panel, matching the `mt-1` this used to carry, plus how close to
// the viewport edge the panel may sit and how little room is worth opening downward into.
const GAP = 4
const VIEWPORT_MARGIN = 8
const MIN_PANEL = 120
// max-h-60, kept as the cap when there is plenty of room.
const LIST_MAX = 240

interface PanelPosition {
  left: number
  width: number
  /** Set when opening downward. Exactly one of top/bottom is set. */
  top?: number
  /** Set when flipped upward - distance from the viewport bottom to the panel's bottom edge. */
  bottom?: number
  listMaxHeight: number
}

interface SelectProps<T extends string = string> {
  value: T
  onChange: (value: T) => void
  options: SelectOption<T>[]
  id?: string
  className?: string
  disabled?: boolean
  /**
   * Opt-in for large option lists (the full IANA timezone list, a long model list) where
   * scanning an unfiltered panel is unusable. Filters locally against `label` and does not
   * touch value/onChange until an option is actually clicked, so the "value is always one
   * of options" contract is unchanged.
   */
  searchable?: boolean
  /** The filter box's placeholder, already translated ("Search..."). */
  searchPlaceholder?: string
  /** What the list says when the filter matches nothing, already translated ("No matches"). */
  emptyLabel?: string
  /** Shown in the trigger while `value` matches no option. */
  placeholder?: string
  ariaLabel?: string
}

/**
 * Brand-styled replacement for a native <select>.
 *
 * The OS-rendered dropdown list of a plain <select> cannot be restyled at all - no border
 * tokens, no brand colours, no hover states - so one sitting in a themed form reads as a
 * browser default dropped into the middle of the product.
 *
 * The trigger shares FIELD_BASE with Input, which is the fix for the drift this component
 * used to carry: in proxyma-app it rendered a different radius, fill, font and focus colour
 * than the text field beside it. See field.ts.
 *
 * THE OPEN PANEL IS A PORTAL, position: fixed, and that is not a detail. It used to be
 * `absolute top-full` inside the trigger's own box, which is fine on a page and wrong inside
 * anything that scrolls or clips - Modal above all, whose body is `overflow-y-auto`. An
 * absolutely positioned descendant still counts toward its scroll container's scrollable area,
 * so opening a Select near the bottom of a dialog GREW the dialog's scroll height and put a
 * scrollbar on it; on a short window the list was clipped at the dialog's edge instead. Both
 * failures are the same mistake: a menu that overlays the page cannot be laid out as part of it.
 *
 * Fixed positioning means the panel must be re-measured rather than re-flowed, which is what
 * the scroll and resize listeners below are for, and it must flip above the trigger when there
 * is no room beneath it.
 *
 * THE KEYBOARD (since 2026-10-14) is `useListbox`: focus stays on the trigger - or on the filter
 * box of a `searchable` one - and the arrows, Home/End and typing move the active option, which the
 * trigger names in `aria-activedescendant`. Enter or Space chooses, Escape closes (marked handled,
 * so the dialog beneath stays open) and Tab closes and moves on. Before it, the portaled options
 * could not be reached from the keyboard at all inside a dialog.
 */
export function Select<T extends string = string>({
  value,
  onChange,
  options,
  id,
  className,
  disabled,
  searchable,
  searchPlaceholder = 'Search...',
  emptyLabel = 'No matches',
  placeholder,
  ariaLabel,
}: SelectProps<T>) {
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState('')
  const [pos, setPos] = useState<PanelPosition | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const filterInputRef = useRef<HTMLInputElement>(null)
  // Kept for its exit; `pos` is only ever replaced by a new measurement, so it leaves in place.
  const { mounted, exiting } = usePresence(open)

  // Where the panel goes, in viewport coordinates. Measured from the trigger rather than laid
  // out next to it, because the panel is no longer a descendant of anything on this page.
  const measure = useCallback(() => {
    const trigger = containerRef.current
    if (!trigger) return
    const r = trigger.getBoundingClientRect()
    const below = window.innerHeight - r.bottom - GAP - VIEWPORT_MARGIN
    const above = r.top - GAP - VIEWPORT_MARGIN
    // Flip only when below genuinely cannot hold the panel AND above is roomier - never merely
    // because above happens to be larger, which would send a menu upward in the middle of a page.
    const flip = below < MIN_PANEL && above > below
    const available = Math.max(MIN_PANEL, flip ? above : below)
    setPos({
      left: r.left,
      width: r.width,
      top: flip ? undefined : r.bottom + GAP,
      bottom: flip ? window.innerHeight - r.top + GAP : undefined,
      listMaxHeight: Math.min(LIST_MAX, available),
    })
  }, [])

  const toggle = useCallback((next: boolean) => {
    if (next) measure()
    setOpen(next)
  }, [measure])

  const selectedOption = options.find(o => o.value === value)
  const visibleOptions =
    searchable && filter.trim()
      ? options.filter(o => o.label.toLowerCase().includes(filter.trim().toLowerCase()))
      : options

  const choose = useCallback((v: T) => {
    onChange(v)
    setOpen(false)
    // Back to the trigger from the filter box, so the next Tab goes on from the field.
    if (searchable) triggerRef.current?.focus({ preventScroll: true })
  }, [onChange, searchable])

  const list = useListbox({
    open,
    setOpen: toggle,
    count: visibleOptions.length,
    selectedIndex: visibleOptions.findIndex(o => o.value === value),
    labels: searchable ? undefined : visibleOptions.map(o => o.label),
    onPick: i => { const o = visibleOptions[i]; if (o) choose(o.value) },
    spaceSelects: !searchable,
    activateFirst: searchable && !!filter.trim(),
  })

  useEffect(() => {
    if (!open) return
    const handleClickOutside = (e: MouseEvent) => {
      const target = e.target as Node
      // The panel is a sibling of the app root now, so containerRef alone no longer covers it -
      // without this, clicking an option would close the menu before the click landed on it.
      if (containerRef.current?.contains(target) || panelRef.current?.contains(target)) return
      setOpen(false)
    }
    const handleEscape = (e: KeyboardEvent) => {
      // preventDefault marks the key as handled, so a dialog beneath this closes on the NEXT Escape
      // rather than on this one (see useModalLayer). A key the field already took is left alone.
      if (e.key === 'Escape' && !e.defaultPrevented) { e.preventDefault(); setOpen(false) }
    }
    // Capture, so a scroll inside ANY ancestor is caught, not only the window's own. A fixed
    // panel does not travel with the element it points at; it has to be told.
    const reposition = () => measure()
    document.addEventListener('mousedown', handleClickOutside)
    document.addEventListener('keydown', handleEscape)
    window.addEventListener('resize', reposition)
    window.addEventListener('scroll', reposition, true)
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
      document.removeEventListener('keydown', handleEscape)
      window.removeEventListener('resize', reposition)
      window.removeEventListener('scroll', reposition, true)
    }
  }, [open, measure])

  useEffect(() => {
    if (open && searchable) {
      setFilter('')
      filterInputRef.current?.focus()
    }
  }, [open, searchable])

  return (
    <div className={cn('relative', className)} ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        id={id}
        disabled={disabled}
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        {...list.fieldProps}
        // While a filter box holds focus, IT names the active option; the trigger does not.
        aria-activedescendant={searchable ? undefined : list.fieldProps['aria-activedescendant']}
        // Measured HERE rather than in a layout effect: both state updates land in the same
        // commit, so the panel's very first paint is already in the right place. A useLayoutEffect
        // would do the same job and warn on every server render in the Next.js consumer.
        onClick={() => toggle(!open)}
        onKeyDown={list.onKeyDown}
        className={cn(
          FIELD_BASE,
          'flex items-center justify-between gap-1 pl-3 pr-2 text-left',
          fieldBorder(undefined),
          open && 'border-brand-solid',
        )}
      >
        <span className="flex items-center gap-2 min-w-0">
          {selectedOption?.icon}
          <span className={cn('truncate', !selectedOption && 'text-ink-disabled')}>{selectedOption?.label ?? placeholder ?? ''}</span>
        </span>
        <ChevronDown
          size={14}
          aria-hidden="true"
          className={cn('shrink-0 text-ink-caption transition-transform', open && 'rotate-180')}
        />
      </button>
      {mounted && pos && createPortal(
        // On the floating layer (`z-float`), above every dialog, since the commonest place to open
        // one of these is inside a dialog, and a menu rendered behind the thing that opened it is
        // worse than no menu.
        <div
          ref={panelRef}
          style={{ left: pos.left, width: pos.width, top: pos.top, bottom: pos.bottom }}
          {...exitingProps(exiting)}
          data-side={pos.bottom !== undefined ? 'top' : 'bottom'}
          className={cn('fixed z-float surface-float border rounded-lg shadow-float overflow-hidden animate-dropdown', exiting && 'animate-pop-out')}
        >
          {searchable && (
            <input
              ref={filterInputRef}
              type="text"
              value={filter}
              onChange={e => setFilter(e.target.value)}
              onKeyDown={list.onKeyDown}
              placeholder={searchPlaceholder}
              role="combobox"
              aria-autocomplete="list"
              aria-label={searchPlaceholder}
              {...list.fieldProps}
              className="block w-full px-3 py-2 text-sm font-ui text-ink-primary bg-transparent placeholder:text-ink-caption outline-none border-b border-line/40"
            />
          )}
          <div
            id={list.listId}
            role="listbox"
            aria-label={ariaLabel}
            className="overflow-y-auto py-1"
            style={{ maxHeight: pos.listMaxHeight }}
          >
            {visibleOptions.length === 0 && (
              <div className="px-3 py-2 text-sm text-ink-caption">{emptyLabel}</div>
            )}
            {visibleOptions.map((o, i) => (
              <div
                key={o.value}
                role="option"
                aria-selected={o.value === value}
                {...list.optionProps(i)}
                // The pointer chooses on click; mousedown is held so focus stays on the field.
                onMouseDown={e => e.preventDefault()}
                onClick={() => choose(o.value)}
                className={cn(
                  // 14/20 on `py-2`: a 36px option, the field's own size and text (44px on glass).
                  // Rounded and inset 4px, as a menu's rows (2026-10-30); the text stays 12px in.
                  'flex items-center justify-between gap-2 w-[calc(100%-0.5rem)] mx-1 rounded-md text-left px-2 py-2 touch:min-h-tap text-sm cursor-pointer transition-colors',
                  'data-[active]:bg-ink-caption/10',
                  o.value === value ? 'text-brand-legible font-medium' : 'text-ink-secondary data-[active]:text-ink-primary',
                )}
              >
                <span className="flex items-center gap-2 min-w-0">
                  {o.icon}
                  <span className="truncate">{o.label}</span>
                </span>
                {o.value === value && <Check size={14} className="shrink-0" aria-hidden="true" />}
              </div>
            ))}
          </div>
        </div>,
        document.body,
      )}
    </div>
  )
}
