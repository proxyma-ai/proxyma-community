/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Collections.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { ArrowDown, ArrowUp, Plus, Trash2, X } from 'lucide-react'
import { Button } from './Button'
import { Card } from './Card'
import { cn } from './cn'
import { iconButton } from './iconButton'
import { SelectBox } from './SelectBox'
import { Readout } from './Text'
import { Tooltip } from './Tooltip'
import { TEXT } from './typeScale'

/**
 * EDITING A LIST OF THINGS - the parts a form uses when its value is a list: a bordered box to pick
 * from, a select-all line over a list, rows of fields that can be added and removed, numbered
 * instructions, and the numbered, reorderable card of a workflow step.
 */

/**
 * A BOX TO PICK FROM: a bordered, scrolling column of rows (the kit `List` of checkboxes a skill
 * picks its tools from). `max-h-40` by default; `fill` lets it take the rest of a fixed-height
 * column from `lg`, where a dialog has one.
 */
export function PickBox({ children, maxHeight = 'max-h-40', fill, className }: {
  children: ReactNode
  maxHeight?: string
  fill?: boolean
  className?: string
}) {
  return (
    <div className={cn('overflow-y-auto rounded-md border border-line/45 px-3', maxHeight, fill && 'lg:max-h-none lg:flex-1 lg:min-h-0', className)}>
      {children}
    </div>
  )
}

/**
 * THE LINE OVER A SELECTABLE LIST: the box that selects every row shown (checked, mixed or empty)
 * and what the list holds, over a hairline. Memory's entries and the feedback list.
 */
export function SelectableListHeader({ state, onToggle, label, children, className }: {
  state: 'none' | 'some' | 'all'
  onToggle: () => void
  /** The box's accessible name ("Select every entry shown"). */
  label: string
  /** Beside the box - a count. */
  children?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('flex items-center gap-3 pb-2 border-b border-line/30', className)}>
      <SelectBox checked={state === 'all'} indeterminate={state === 'some'} label={label} onToggle={() => onToggle()} />
      {children && <span className={TEXT.caption}>{children}</span>}
    </div>
  )
}

/**
 * ROWS OF FIELDS THAT GROW AND SHRINK - a provider's custom headers, the servers a tool may reach.
 * Each row is the caller's fields then a remove button (danger tone, named), and "Add" sits under
 * the last row as a text action. The fields stay the caller's: a header is two inputs, a server
 * one, and a row that knew which would be a form builder.
 */
export function ListEditor<T>({ items, renderRow, onAdd, onRemove, addLabel, removeLabel, removeTooltip, className }: {
  items: T[]
  /** The row's fields, laid out in a flex row - give each one `flex-1` or a width. */
  renderRow: (item: T, index: number) => ReactNode
  onAdd: () => void
  onRemove: (index: number) => void
  addLabel: string
  /**
   * The remove button's name ("Remove header"), or a name per row ("Remove fileserver.corp") where
   * each row is its own thing.
   */
  removeLabel: string | ((item: T, index: number) => string)
  /** The tooltip, when it is shorter than a per-row name ("Remove"). Defaults to the name. */
  removeTooltip?: string
  className?: string
}) {
  return (
    <div className={cn('space-y-2', className)}>
      {items.map((item, i) => {
        const name = typeof removeLabel === 'function' ? removeLabel(item, i) : removeLabel
        return (
          <div key={i} className="flex items-center gap-2">
            {renderRow(item, i)}
            <Tooltip label={removeTooltip ?? name}>
              <button type="button" aria-label={name} className={iconButton('md', 'danger')} onClick={() => onRemove(i)}>
                <X size={14} aria-hidden="true" />
              </button>
            </Tooltip>
          </div>
        )
      })}
      <Button variant="hyperlink" type="button" onClick={onAdd}>
        <Plus size={14} aria-hidden="true" />
        {addLabel}
      </Button>
    </div>
  )
}

/**
 * NUMBERED STEPS a reader follows - "1. Add this to mcp.json", then the box to copy. The number is
 * drawn rather than a `list-decimal` marker: an outside marker hangs left of the content edge, which
 * put the number, the words and the code box under them on three different left edges (maintainer,
 * 2026-09-30). Here the number sits on the edge, the words beside it, and every box under a step
 * spans from that same edge.
 */
export function StepList({ intro, children, className }: { intro?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <div className={cn('space-y-3', className)}>
      {intro && <p className={TEXT.caption}>{intro}</p>}
      <ol className={cn('space-y-3 [counter-reset:step]', TEXT.body)}>{children}</ol>
    </div>
  )
}

/** One step: its words after the drawn number, and anything under them (a code block). */
export function StepItem({ text, children }: { text: ReactNode; children?: ReactNode }) {
  return (
    <li className="space-y-2 [counter-increment:step]">
      <p className="before:content-[counter(step)_'.'] before:mr-1.5">{text}</p>
      {children}
    </li>
  )
}

/**
 * ONE STEP OF A SEQUENCE BEING EDITED - its number, its head (a title field), move up, move down and
 * remove, then its body (the instruction). An outline card, `padding="sm"`. The controls are icon
 * buttons, the one place a list's actions are icons: a dense sub-list, by the Settings rule.
 * Omit `onMove`/`onRemove` for a read-only view.
 */
export function StepCard({ index, count, head, children, onMove, onRemove, labels, className }: {
  /** Zero-based; shown from 1. */
  index: number
  count: number
  head: ReactNode
  children?: ReactNode
  onMove?: (from: number, to: number) => void
  onRemove?: () => void
  labels: { up: string; down: string; remove: string }
  className?: string
}) {
  return (
    <Card variant="outline" padding="sm" className={cn('space-y-2', className)}>
      <div className="flex items-center gap-2">
        <Readout tone="disabled" className="text-xs shrink-0 w-5">{index + 1}</Readout>
        <div className="flex-1 min-w-0">{head}</div>
        {(onMove || onRemove) && (
          <div className="flex items-center shrink-0">
            {onMove && (
              <>
                <button type="button" onClick={() => onMove(index, index - 1)} disabled={index === 0} aria-label={labels.up} className={iconButton('md')}>
                  <ArrowUp size={14} aria-hidden="true" />
                </button>
                <button type="button" onClick={() => onMove(index, index + 1)} disabled={index === count - 1} aria-label={labels.down} className={iconButton('md')}>
                  <ArrowDown size={14} aria-hidden="true" />
                </button>
              </>
            )}
            {onRemove && (
              <button type="button" onClick={onRemove} aria-label={labels.remove} className={iconButton('md', 'danger')}>
                <Trash2 size={14} aria-hidden="true" />
              </button>
            )}
          </div>
        )}
      </div>
      {children}
    </Card>
  )
}
