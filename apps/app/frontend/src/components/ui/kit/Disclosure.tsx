/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Disclosure.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { useId, useState } from 'react'
import { ChevronRight } from 'lucide-react'
import { cn } from './cn'
import { iconButton, type IconButtonSize } from './iconButton'

/**
 * The chevron of anything that opens and closes: ONE 14px `ChevronRight` that ROTATES (and so
 * animates), TRAILING the line it belongs to. There were 13px Down/Right swaps, a 16px rotating
 * one, and chevrons leading in one place and trailing in the next. Trailing because the line is a
 * sentence with its affordance at the end ("Read 4 files >").
 */
export function DisclosureChevron({ open, className }: { open: boolean; className?: string }) {
  return (
    <ChevronRight
      size={14}
      aria-hidden="true"
      className={cn('shrink-0 transition-transform duration-fast ease-in-out', open && 'rotate-90', className)}
    />
  )
}

/**
 * THE CHEVRON AS A BUTTON ON ITS OWN - a row whose title does something else (a sub-agent's name
 * goes to it in the thread) and whose chevron opens what is under it. An `iconButton` with the
 * rotating chevron, `aria-expanded`, and the words for both states ("Show steps" / "Hide steps").
 * It was drawn three ways: the agents map's, the tool group's ChevronDown, the sub-agent panel's.
 */
export function DisclosureButton({ open, onToggle, labels, controls, size = 'sm', className }: {
  open: boolean
  onToggle: () => void
  /** Its name in each state, translated. */
  labels: { show: string; hide: string }
  /** The id of what it opens, for `aria-controls`. */
  controls?: string
  size?: IconButtonSize
  className?: string
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={open}
      aria-controls={controls}
      aria-label={open ? labels.hide : labels.show}
      className={cn(iconButton(size), className)}
    >
      <DisclosureChevron open={open} />
    </button>
  )
}

/**
 * WHAT OPENS AND CLOSES, OPENING AND CLOSING - its height eased over 200ms rather than jumping
 * (maintainer's request, 2026-10-08: nothing that expanded in the app did so smoothly). Every
 * disclosure body in the kit is this, and so is every one an app draws by hand.
 *
 * The CSS grid technique: one row sized `0fr` closed and `1fr` open, transitioned, around a child
 * that clips - so no height is measured, and content that grows while open (a running sub-agent,
 * a tool list) grows the box with it. The body STAYS MOUNTED while closed, `inert` and hidden from
 * assistive technology, which is what lets it animate shut; `className` is the body's own padding
 * and spacing, on an inner box so it closes to nothing too. Under reduced motion it snaps.
 */
export function Collapse({ open, children, className, id }: {
  open: boolean
  children: ReactNode
  /** The body's own padding and spacing - applied inside, so closed is zero height. */
  className?: string
  id?: string
}) {
  // React 18 has no `inert` prop; the attribute is passed through as it is.
  const inert = open ? {} : ({ inert: '' } as Record<string, string>)
  return (
    <div
      id={id}
      aria-hidden={!open}
      {...inert}
      className={cn('grid transition-[grid-template-rows,opacity] duration-base ease-out motion-reduce:transition-none',
        open ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0')}
    >
      <div className="min-h-0 overflow-hidden">
        <div className={className}>{children}</div>
      </div>
    </div>
  )
}

/**
 * A line that opens what is under it. The caller owns `open` when it must (a tool group the
 * thread opens by itself); otherwise `defaultOpen` and the component keeps it. `aria-expanded`
 * always - the credential groups in Settings had none.
 *
 * `boxed` draws the line and its body in a bordered panel (an FAQ item, a decision row). Hover is
 * colour only, never a fill.
 */
export function Disclosure({ summary, meta, open: controlled, defaultOpen = false, onToggle, boxed, children, className, summaryClassName }: {
  summary: ReactNode
  /** Quiet trailing detail before the chevron - an elapsed time, a lock. */
  meta?: ReactNode
  open?: boolean
  defaultOpen?: boolean
  onToggle?: (open: boolean) => void
  boxed?: boolean
  children: ReactNode
  className?: string
  summaryClassName?: string
}) {
  const [own, setOwn] = useState(defaultOpen)
  const open = controlled ?? own
  const bodyId = useId()
  const toggle = () => {
    const next = !open
    if (controlled === undefined) setOwn(next)
    onToggle?.(next)
  }
  return (
    <div className={cn(boxed && 'rounded-lg border surface-card shadow-card', className)}>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={toggle}
        className={cn(
          'w-full flex items-center gap-2 text-left transition-colors focus-ring rounded-md',
          'text-ink-secondary mouse:hover:text-ink-primary',
          boxed && 'px-4 py-3',
          summaryClassName,
        )}
      >
        <span className="flex-1 min-w-0">{summary}</span>
        {meta && <span className="shrink-0 text-xs text-ink-caption">{meta}</span>}
        <DisclosureChevron open={open} className="text-ink-caption" />
      </button>
      {/* Always in the page (Collapse): a closed body is hidden, not unmounted, so a crawler and an
          in-page search still find what is in it. */}
      <Collapse open={open} id={bodyId} className={boxed ? 'px-4 pb-4' : 'pt-2'}>
        {children}
      </Collapse>
    </div>
  )
}
