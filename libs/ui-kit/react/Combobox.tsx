'use client'

import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { AlertCircle, ChevronDown } from 'lucide-react'
import { cn } from './cn'
import { FIELD_BASE, fieldBorder } from './field'
import { useFloating } from './floating'
import { FieldNote } from './Input'
import { exitingProps, usePresence } from './motion'
import { Spinner } from './Spinner'
import { useListbox } from './useListbox'

export type ComboboxStatus = 'idle' | 'loading' | 'error' | 'success'

export interface ComboboxLabels {
  loading: string
  failed: string
  empty: string
  /** "Create {query}", for a creatable list. */
  create?: (query: string) => string
}

export interface ComboboxProps {
  value: string
  onChange: (value: string) => void
  /** The suggestions, as plain strings (a model name, a folder path). */
  options: string[]
  status: ComboboxStatus
  labels: ComboboxLabels
  /** Why the options failed to load, when the server said. */
  errorText?: string | null
  /** Called on every open - the list fetches lazily, and a stale list is refreshed. */
  onOpen?: () => void
  /** Offers to create what was typed when nothing matches it exactly. */
  onCreate?: (value: string) => void
  placeholder?: string
  disabled?: boolean
  /**
   * The line under the field, when there is no error - for a combobox with NO label. Inside a
   * labelled `Field`, give the hint to the `Field`, which puts it under the label (2026-10-29).
   */
  hint?: React.ReactNode
  error?: string
  shake?: boolean
  id?: string
  className?: string
  /** The field's name for a screen reader, where no `<label>` names it. */
  ariaLabel?: string
}

// 14/20 on `py-2`: a 36px row, the field's own size and text (44px on glass).
// Rounded and inset 4px, as a menu's rows (2026-10-30); the text stays 12px in, under the field's.
const ROW = 'flex items-center gap-2 w-[calc(100%-0.5rem)] mx-1 rounded-md text-left px-2 py-2 touch:min-h-tap text-sm transition-colors'

/**
 * FREE TEXT WITH A FETCHED LIST OF SUGGESTIONS - a model name, a folder. It moved into the kit
 * when the upload dialog's folder picker turned out to be a second copy of it (the first-consumer
 * rule kept it app-local until then), and gained `disabled` (two dialogs stood a disabled `Input`
 * in for it) and `onCreate`.
 *
 * Filtering runs against what was typed SINCE the list opened, not against `value`: the value is
 * usually the saved name, and filtering by it on open showed one row or none.
 *
 * Since 2026-10-14 the list is a PORTAL on the floating layer (`useFloating`), the width of the
 * field - it was `absolute` inside the field's box, the failure Select's own note describes: clipped
 * by a dialog, or growing the dialog's scroll. And it has the listbox keyboard (`useListbox`): focus
 * stays in the text box; ArrowDown opens the list and moves the active row, Enter chooses it (or
 * the create row), Escape closes it (marked handled, so the dialog stays open), Tab moves on. A
 * pointer chooses on click; the press itself is held so the caret stays in the field. The chevron
 * is a pointer convenience only - out of the tab order and hidden from assistive technology, since
 * the field itself opens the list.
 */
export function Combobox({
  value, onChange, options, status, labels, errorText, onOpen, onCreate, placeholder, disabled, hint, error, shake, id, className,
  ariaLabel,
}: ComboboxProps) {
  const [open, setOpen] = useState(false)
  const [filterText, setFilterText] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)
  const fieldRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const noteId = useId()
  const { mounted, exiting } = usePresence(open)
  const floating = useFloating(fieldRef, panelRef, { open, mounted, placement: 'bottom-start', matchWidth: true })

  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: PointerEvent) => {
      const t = e.target as Node
      if (containerRef.current?.contains(t) || panelRef.current?.contains(t)) return
      setOpen(false)
    }
    const onKeyDown = (e: KeyboardEvent) => { if (e.key === 'Escape' && !e.defaultPrevented) { e.preventDefault(); setOpen(false) } }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  const openList = (next: boolean) => {
    if (next && !open) {
      if (disabled) return
      setFilterText('')
      onOpen?.()
    }
    setOpen(next)
  }

  const q = filterText.trim().toLowerCase()
  const filtered = status === 'success' || status === 'idle' ? (q ? options.filter(o => o.toLowerCase().includes(q)) : options) : []
  const canCreate = !!onCreate && !!filterText.trim() && !options.some(o => o.toLowerCase() === q)
  // The rows the keyboard walks: the suggestions, then the create row.
  const count = filtered.length + (canCreate ? 1 : 0)

  const select = (v: string) => { onChange(v); setOpen(false) }
  const create = () => { onCreate?.(filterText.trim()); setOpen(false) }

  const list = useListbox({
    open,
    setOpen: openList,
    count,
    selectedIndex: filtered.indexOf(value),
    onPick: i => (i < filtered.length ? select(filtered[i]) : create()),
    // Once something is typed, the first row is active, so Enter takes it - the one suggestion
    // left, or the create row when nothing matches. Without it Enter did nothing after typing a new
    // name, while the upload dialog's own note says "Press Enter".
    activateFirst: !!filterText.trim(),
  })

  const note = error || hint ? noteId : undefined

  return (
    <div className={cn('relative', className)} ref={containerRef}>
      <div className="relative" ref={fieldRef}>
        <input
          id={id}
          type="text"
          value={value}
          disabled={disabled}
          role="combobox"
          aria-autocomplete="list"
          aria-label={ariaLabel}
          aria-invalid={error ? true : undefined}
          aria-describedby={note}
          {...list.fieldProps}
          onChange={e => {
            onChange(e.target.value)
            setFilterText(e.target.value)
            if (!open) { setOpen(true); onOpen?.() }
          }}
          onFocus={() => { if (!open) openList(true) }}
          onKeyDown={list.onKeyDown}
          placeholder={placeholder}
          className={cn(FIELD_BASE, fieldBorder(error), 'pl-3 pr-8', shake && 'animate-shake')}
        />
        <button
          type="button"
          tabIndex={-1}
          aria-hidden="true"
          disabled={disabled}
          onMouseDown={e => e.preventDefault()}
          onClick={() => openList(!open)}
          className="absolute right-0 top-0 h-full w-8 flex items-center justify-center text-ink-caption mouse:hover:text-ink-primary transition-colors disabled:opacity-40"
        >
          <ChevronDown size={14} className={cn('transition-transform', open && 'rotate-180')} aria-hidden="true" />
        </button>
      </div>
      <FieldNote id={noteId} error={error} hint={hint} />
      {mounted && createPortal(
        <div
          ref={panelRef}
          id={list.listId}
          role="listbox"
          {...exitingProps(exiting)}
          data-side={floating.side}
          style={floating.style}
          className={cn('fixed z-float surface-float border rounded-lg shadow-float overflow-hidden max-h-60 overflow-y-auto py-1 animate-dropdown', exiting && 'animate-pop-out')}
        >
          {status === 'loading' && (
            <div className={cn(ROW, 'text-ink-caption')} role="presentation"><Spinner size="sm" />{labels.loading}</div>
          )}
          {status === 'error' && (
            <div className={cn(ROW, 'text-danger')} role="presentation"><AlertCircle size={14} className="shrink-0" aria-hidden="true" />{errorText ?? labels.failed}</div>
          )}
          {status === 'success' && filtered.length === 0 && !canCreate && (
            <div className={cn(ROW, 'text-ink-caption')} role="presentation">{labels.empty}</div>
          )}
          {filtered.map((o, i) => (
            <div
              key={o}
              role="option"
              aria-selected={o === value}
              {...list.optionProps(i)}
              onMouseDown={e => e.preventDefault()}
              onClick={() => select(o)}
              className={cn(
                ROW, 'cursor-pointer data-[active]:bg-ink-caption/10',
                o === value ? 'text-brand-legible font-medium' : 'text-ink-secondary data-[active]:text-ink-primary',
              )}
            >
              <span className="truncate">{o}</span>
            </div>
          ))}
          {canCreate && (
            <div
              role="option"
              aria-selected={false}
              {...list.optionProps(filtered.length)}
              onMouseDown={e => e.preventDefault()}
              onClick={create}
              className={cn(ROW, 'cursor-pointer text-brand-legible font-medium data-[active]:bg-ink-caption/10')}
            >
              {labels.create ? labels.create(filterText.trim()) : filterText.trim()}
            </div>
          )}
        </div>,
        document.body,
      )}
    </div>
  )
}
