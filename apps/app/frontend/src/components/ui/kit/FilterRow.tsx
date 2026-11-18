/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/FilterRow.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { Fragment, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { ChevronDown, ListFilter } from 'lucide-react'
import { cn } from './cn'
import { fitCount } from './filterFit'
import { Popover } from './Popover'
import { Select } from './Select'

/** One filter on the row: a chip (a `Select look="chip"`, a `ChoiceFilter`) and whether a choice of it is in force. */
export interface FilterRowItem {
  key: string
  node: ReactNode
  active?: boolean
}

/** The space between two chips: `gap-2`. */
const GAP = 8

/** A chip's own button, for the More chip: the `Select look="chip"` trigger's recipe. */
function chipClass(active: boolean, open: boolean): string {
  return cn(
    'inline-flex max-w-full items-center gap-1.5 h-9 touch:h-tap pl-3 pr-2 rounded-md border bg-field text-sm font-ui whitespace-nowrap',
    'outline-none focus-ring transition-colors duration-fast ease-out',
    active ? 'border-brand-solid/60 text-brand-legible font-medium'
      : 'border-line text-ink-secondary mouse:hover:border-ink-disabled mouse:hover:text-ink-primary',
    open && 'border-brand-solid',
  )
}

function MoreChip({ label, hiddenActive, open }: { label: string; hiddenActive: number; open: boolean }) {
  return (
    <>
      <span>{label}</span>
      {hiddenActive > 0 && <span className="data-readout">{hiddenActive}</span>}
      <ChevronDown size={14} aria-hidden="true" className={cn('shrink-0 text-ink-caption transition-transform', open && 'rotate-180')} />
    </>
  )
}

/**
 * EVERY FILTER OF A LIST ON ONE LINE, AND WHAT DOES NOT FIT UNDER "MORE" (2026-11-16, the maintainer: "Open, Answered,
 * Dismissed ... belongs to the filter as well ... If there is not enough space to show all filters, show a 'More'
 * dropdown. Apply this for all pages that have filter."). "Filter" with its glyph (a line of its own on a phone), then
 * the chips in order. From `sm` they keep to one line: as many as fit, and when not all do, a More chip after them
 * whose panel holds the rest, its count the choices in force among them. On a phone they all show, wrapping. Measured on a hidden copy of every chip, so the line knows each
 * one's width whether it is shown or not, and laid out again as the line or a chip's words change. `end` - Clear
 * filters - stands after the line.
 */
export function FilterRow({ items, label, moreLabel, ariaLabel, end, className }: {
  items: readonly FilterRowItem[]
  /** The row's word ("Filter"); its glyph is the kit's. Absent: the chips alone. */
  label?: string
  /** The More chip's word, translated. */
  moreLabel: string
  /** The chips' group name ("Filters"). */
  ariaLabel: string
  end?: ReactNode
  className?: string
}) {
  const lineRef = useRef<HTMLDivElement>(null)
  const copyRef = useRef<HTMLDivElement>(null)
  const [fit, setFit] = useState(items.length)
  // ONE LINE AT EVERY WIDTH, the first chips that fit and More for the rest (the maintainer, 2026-11-17: "Show some first
  // filters and a 'More' dropdown in one line" - reversing 2026-11-16's "More on wide screens only", which wrapped every
  // chip onto lines of their own on a phone).
  useLayoutEffect(() => {
    const line = lineRef.current
    const copy = copyRef.current
    if (!line || !copy) return
    const measure = () => {
      const kids = Array.from(copy.children) as HTMLElement[]
      const widths = kids.slice(0, items.length).map(k => k.getBoundingClientRect().width)
      const more = kids[items.length]?.getBoundingClientRect().width ?? 0
      setFit(fitCount(widths, more, line.clientWidth, GAP))
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const watch = new ResizeObserver(measure)
    watch.observe(line)
    watch.observe(copy)
    return () => watch.disconnect()
  }, [items.length])

  // The copy takes no part in the page: set on the node (React 18's types do not know `inert`).
  useLayoutEffect(() => { copyRef.current?.setAttribute('inert', '') }, [])

  const shown = items.slice(0, fit)
  const rest = items.slice(fit)
  const hiddenActive = rest.filter(i => i.active).length

  return (
    <div className={cn('flex flex-wrap items-center gap-2', className)}>
      {label && (
        // Not on a phone (the mobile audit, 2026-11-16): a line of its own there for one word, and each chip carries the
        // glyph of what it filters.
        <span className="hidden sm:inline-flex items-center gap-1.5 mr-1 text-sm text-ink-caption">
          <ListFilter size={14} aria-hidden="true" />{label}
        </span>
      )}
      <div ref={lineRef} role="group" aria-label={ariaLabel} className="relative flex-1 min-w-0 flex flex-nowrap items-center gap-2">
        {shown.map(i => <div key={i.key} className="shrink-0 min-w-0">{i.node}</div>)}
        {rest.length > 0 && (
          <Popover
            role="dialog"
            ariaLabel={moreLabel}
            placement="bottom-start"
            // A FIXED WIDTH: the panel is placed once, as it opens, so a chip that grows inside it ("Viewer: Baica Li")
            // would carry it off the screen's edge. Its chips fill it and their words are cut short instead.
            widthClass="w-64"
            panelClassName="p-2"
            menuKeys={false}
            closeOnScroll={false}
            trigger={({ open, toggle, ref }) => (
              <button ref={ref} type="button" onClick={toggle} aria-expanded={open} aria-haspopup="dialog"
                className={cn('shrink-0', chipClass(hiddenActive > 0, open))}>
                <MoreChip label={moreLabel} hiddenActive={hiddenActive} open={open} />
              </button>
            )}
          >
            {() => (
              <div className={cn(
                'flex flex-col items-stretch gap-2 [&_button[aria-haspopup=listbox]]:w-full [&_button[aria-haspopup=listbox]]:justify-between',
                // A CHOICE CHIP IS A ROW OF THE LIST, as every dropdown's rows are (2026-11-17: "Should be similar to other
                // dropdowns"): the panel's width, no edge or fill of its own, a fill under the pointer, the chosen one in the
                // brand's ink; the rows sit closer than the chips they were.
                '[&:has(>button[aria-pressed])]:gap-0.5',
                '[&>button[aria-pressed]]:w-full [&>button[aria-pressed]]:justify-between [&>button[aria-pressed]]:border-transparent',
                '[&>button[aria-pressed]]:bg-transparent [&>button[aria-pressed]]:px-2 mouse:[&>button[aria-pressed]:hover]:bg-ink-caption/10',
              )}>
                {rest.map(i => <Fragment key={i.key}>{i.node}</Fragment>)}
              </div>
            )}
          </Popover>
        )}
        {/* Every chip and the More chip, laid out unseen at their own widths: what the line is measured from. Clipped
            to the line, so a copy wider than a phone does not widen the page. */}
        <div aria-hidden="true" className="absolute inset-0 overflow-hidden invisible pointer-events-none">
          <div ref={copyRef} className="flex items-center gap-2 w-max">
            {items.map(i => <div key={i.key} className="shrink-0">{i.node}</div>)}
            <span className={chipClass(false, false)}><MoreChip label={moreLabel} hiddenActive={0} open={false} /></span>
          </div>
        </div>
      </div>
      {end}
    </div>
  )
}

/** One choice of a `ChoiceFilter`: its words and, where the list counts it, how many it holds. */
export interface ChoiceFilterOption<K extends string> {
  key: K
  label: string
  count?: number
}

/**
 * A FILTER OF ONE CHOICE - Waiting or Decided, Open or Answered, a source type - as a chip on a `FilterRow`, beside the
 * filters of several: the choice's words on the chip, every choice with its count in the list. `active` once a choice
 * other than the list's default is in force, so the chip says the list is narrowed (2026-11-16: they were a row of
 * pills of their own over the filters).
 */
export function ChoiceFilter<K extends string>({ options, value, onChange, ariaLabel, icon, active, name, defaultKey }: {
  options: readonly ChoiceFilterOption<K>[]
  value: K
  onChange: (value: K) => void
  ariaLabel: string
  /** The chip's 14px glyph - what the filter is about. */
  icon?: ReactNode
  /** A choice other than the default is in force. */
  active?: boolean
  /**
   * The filter's name, on the chip while `defaultKey` is chosen - "Category" rather than a bare "All", as a facet reads
   * "Tag" until a tag is picked. A default that says something of its own ("Open", "Waiting") needs none.
   */
  name?: string
  defaultKey?: K
}) {
  const words = (o: ChoiceFilterOption<K>) => (o.count === undefined ? o.label : `${o.label} (${o.count.toLocaleString()})`)
  return (
    <Select<K>
      look="chip"
      active={active}
      chipIcon={icon}
      chipLabel={name !== undefined && value === defaultKey ? name : undefined}
      ariaLabel={ariaLabel}
      className="max-w-[20rem]"
      value={value}
      onChange={onChange}
      options={options.map(o => ({ value: o.key, label: words(o) }))}
    />
  )
}
