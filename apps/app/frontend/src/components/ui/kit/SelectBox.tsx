/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/SelectBox.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useEffect, useRef } from 'react'
import { cn } from './cn'
import { ChoiceMark } from './RadioGroup'

/**
 * A multi-select list's row and header checkbox - the telemetry console's feedback list and the
 * app's Settings > Memory. In the kit since 2026-10-03, when the second one arrived.
 *
 * Not the kit's Checkbox, which is generated and offers neither of the two things a multi-select
 * column needs: an indeterminate state for the header, and whether Shift was held for a range.
 * It keeps that component's construction - a visually hidden native input inside a label - so
 * Tab, Space and the accessibility tree still come from the browser, and its look.
 *
 * Shift is read on mousedown and keydown, BEFORE the change event: a click on the label reaches
 * the hidden input as a second, synthesized click, and whether that one carries the modifier is
 * up to the browser.
 */
export function SelectBox({ checked, indeterminate = false, label, disabled, onToggle }: {
  checked: boolean
  indeterminate?: boolean
  label: string
  disabled?: boolean
  onToggle: (checked: boolean, shift: boolean) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const shift = useRef(false)

  useEffect(() => {
    if (input.current) input.current.indeterminate = indeterminate
  }, [indeterminate])

  return (
    <label
      // A row that opens something on click (the feedback list's detail dialog) must not open it
      // for a click on its checkbox.
      onClick={e => e.stopPropagation()}
      onMouseDown={e => {
        shift.current = e.shiftKey
        // Shift-click would otherwise select the text of every row in between.
        if (e.shiftKey) e.preventDefault()
      }}
      className={cn(
        'inline-flex items-center justify-center p-1 -m-1 group',
        disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer',
      )}
    >
      <input
        ref={input}
        type="checkbox"
        checked={checked}
        disabled={disabled}
        aria-label={label}
        onKeyDown={e => { shift.current = e.shiftKey }}
        onChange={e => {
          onToggle(e.target.checked, shift.current)
          shift.current = false
        }}
        className="sr-only peer"
      />
      {/* The kit's own box (ChoiceMark), the focus ring on it while the hidden input has focus. */}
      <ChoiceMark
        multi
        checked={checked}
        indeterminate={indeterminate}
        className="peer-focus-visible:ring-2 peer-focus-visible:ring-brand-solid peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-canvas"
      />
    </label>
  )
}
