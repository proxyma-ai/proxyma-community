/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/SearchInput.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { Search, X } from 'lucide-react'
import { cn } from './cn'
import { FIELD_BASE, fieldBorder } from './field'

interface Props {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  className?: string
  /** The clear button's accessible name, already translated. It was hard-coded English. */
  clearLabel?: string
  /** The field's accessible name when no visible label names it. */
  ariaLabel?: string
  autoFocus?: boolean
  /** Read-only views keep the field in place, greyed, rather than dropping it from the layout. */
  disabled?: boolean
  id?: string
}

/**
 * A search field is a BOX, so it takes the control radius from FIELD_BASE, not
 * `rounded-full`. Pill-shaped search fields were converted for exactly this reason: a pill
 * reads as a chip or a tag, and a field the user types into should look like the other
 * fields they type into.
 */
export function SearchInput({ value, onChange, placeholder, className, clearLabel = 'Clear search', ariaLabel, autoFocus, disabled, id }: Props) {
  return (
    <div className={cn('relative', className)}>
      <Search
        size={14}
        className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-disabled pointer-events-none"
        aria-hidden="true"
      />
      <input
        id={id}
        type="search"
        value={value}
        aria-label={ariaLabel}
        autoFocus={autoFocus}
        disabled={disabled}
        onChange={e => onChange(e.target.value)}
        // Escape empties a field that has something in it and is marked handled, so the dialog the
        // field sits in stays open; an empty field lets Escape through to close it.
        onKeyDown={e => { if (e.key === 'Escape' && value) { e.preventDefault(); e.stopPropagation(); onChange('') } }}
        placeholder={placeholder}
        className={cn(
          FIELD_BASE,
          'pl-8 pr-8',
          fieldBorder(undefined),
          // The native clear affordance is a browser-drawn glyph that ignores every token
          // here; the button below replaces it.
          '[&::-webkit-search-cancel-button]:appearance-none',
        )}
      />
      {value && !disabled && (
        <button
          type="button"
          onClick={() => onChange('')}
          aria-label={clearLabel}
          className="absolute right-2 top-1/2 -translate-y-1/2 w-5 h-5 touch:w-9 touch:h-9 rounded-full inline-flex items-center justify-center text-ink-disabled mouse:hover:text-ink-primary transition-colors focus-ring"
        >
          <X size={14} />
        </button>
      )}
    </div>
  )
}
