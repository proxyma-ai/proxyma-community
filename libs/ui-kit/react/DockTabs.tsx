'use client'

import { forwardRef, useEffect, useRef, type ComponentType, type ReactNode } from 'react'
import { MoreHorizontal } from 'lucide-react'
import { cn } from './cn'
import { HScroll } from './HScroll'
import { MenuGroup } from './menu'
import { MenuRow, Popover } from './Popover'
import { StatusDot } from './StateMark'
import { useHideOnSwipe } from './useAutoHide'

type DockIcon = ComponentType<{ size?: number | string; className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>

export interface DockTab<T extends string> {
  value: T
  label: string
  icon: DockIcon
  /** What waits there - approvals, questions - beside the icon; nothing when zero or absent. */
  count?: number
  /** Something works there - an agent - while the reader is elsewhere: a blinking dot on the icon's corner. */
  working?: boolean
  /** A walkthrough's handle on the tab (`data-tour-id`). */
  tourId?: string
}

/**
 * ONE ENTRY OF A DOCK - an icon over its name, the chosen one in the primary ink with a bar along the dock's top edge -
 * for `DockTabs`' own tabs and for a last button a caller adds (`after`): a "More" that opens a menu. A ref reaches the
 * button, so a popover can anchor on it.
 */
export const DockButton = forwardRef<HTMLButtonElement, {
  icon: DockIcon
  label: string
  on?: boolean
  count?: number
  working?: boolean
  /** The dot's name for a screen reader ("An agent is still working"). */
  workingLabel?: string
  onClick: () => void
  /** For a button that opens a menu: `aria-haspopup` and `aria-expanded`. */
  menu?: { open: boolean }
  /** A walkthrough's handle on the button (`data-tour-id`). */
  tourId?: string
}>(function DockButton({ icon: Icon, label, on = false, count, working = false, workingLabel, onClick, menu, tourId }, ref) {
  return (
    <button
      ref={ref}
      type="button"
      data-tour-id={tourId}
      aria-current={on && !menu ? 'page' : undefined}
      aria-label={count ? `${label}, ${count}` : undefined}
      aria-haspopup={menu ? 'menu' : undefined}
      aria-expanded={menu ? menu.open : undefined}
      onClick={onClick}
      className={cn(
        // EVERY ENTRY THE SAME WIDTH (2026-11-16, the maintainer: "Dock items should have the same width"), at least
        // 72px - more entries than fit scroll sideways; the caller keeps to what fits (AppDock: three pages and More).
        'relative flex-1 basis-0 min-w-[4.5rem] h-16 flex flex-col items-center justify-center gap-1 px-1 focus-ring',
        'transition-colors duration-fast ease-out',
        on ? 'text-ink-primary' : 'text-ink-caption mouse:hover:text-ink-primary',
      )}
    >
      {/* The chosen entry's mark: a bar in the brand's ink along the dock's top edge, as a selected row carries one
          along its side (SELECTED_ROW) - never a teal fill. */}
      <span aria-hidden="true" className={cn(
        'absolute top-0 inset-x-3 h-0.5 rounded-sm bg-brand-accent transition-opacity duration-base ease-out',
        on ? 'opacity-100' : 'opacity-0',
      )} />
      <span className="relative">
        <Icon size={20} aria-hidden="true" />
        {/* The count just right of the icon, the icon still centered over its name: over the icon's corner it covered
            the glyph. */}
        {!!count && (
          <span aria-hidden="true" className="absolute left-full top-1/2 -translate-y-1/2 ml-1 text-xs font-medium leading-none data-readout">
            {count > 99 ? '99+' : count}
          </span>
        )}
        {working && <StatusDot label={workingLabel} className="absolute -top-1 -right-1" />}
      </span>
      <span className={cn('max-w-full truncate text-xs leading-4 whitespace-nowrap', on ? 'font-medium' : 'font-normal')}>{label}</span>
    </button>
  )
})

/**
 * PAGES OR SECTIONS AS A DOCK AT THE SCREEN'S FOOT, on a phone (2026-11-16, the maintainer: "these kind of sub menu bar
 * could be moved to the bottom dock, scroll up auto hide, scroll down auto show, similar to the landing site", then the
 * app's own pages in place of the hamburger): an icon over each name, the chosen one in the primary ink with a bar
 * along its top edge, a count beside an icon, a dot where something works. In the shell's flow, so the page above it
 * gives way; it LEAVES WITH THE HEADER (`AutoHideHeader`'s own signal, `useHideOnSwipe` - the finger moving on down the
 * page) by a negative margin of its own height, and comes back on a swipe back, on a change of `value` and whenever
 * `away` lets it. More entries than fit scroll sideways. `after` is a last entry of the caller's - a `DockButton`.
 *
 * The caller renders it below `sm` (a project's sections) or below `md` (the app's pages); wider, the same choices are
 * at the top or in the sidebar.
 */
/** How many entries a dock keeps before the rest go under More (`more`): four and More are five equal entries. */
const DOCK_KEEP = 4

export function DockTabs<T extends string>({ tabs, value, onChange, ariaLabel, away = false, after, workingLabel, more, className }: {
  tabs: readonly DockTab<T>[]
  /** The entry on screen; one no tab has (a page under `after`'s menu) marks none. */
  value: T
  onChange: (value: T) => void
  ariaLabel: string
  /** Out of the way whatever the finger says - something of the page's own is at the foot (a chat's composer). */
  away?: boolean
  after?: ReactNode
  /** The working dot's name, translated. */
  workingLabel?: string
  /**
   * More than five entries: the first four stay, the rest go under a "More" entry named by this label, translated (the
   * full test, 2026-11-16 - a key account's nine sections scrolled sideways with no cue, and the one on screen was off
   * it). More is lit while the entry on screen is one of those under it. Without it, entries past the width scroll.
   */
  more?: string
  className?: string
}) {
  // A new entry starts with its dock, as a new page starts with its header.
  const swiped = useHideOnSwipe(value)
  const hidden = away || swiped
  const ref = useRef<HTMLElement>(null)
  // Set on the node: React 18's types do not know `inert` (AutoHideHeader does the same).
  useEffect(() => { ref.current?.toggleAttribute('inert', hidden) }, [hidden])
  return (
    <nav
      ref={ref}
      aria-label={ariaLabel}
      className={cn(
        // The header's glass (`.header-bar`), its rule on the top edge; the home indicator's room under it.
        'shrink-0 header-bar border-t pb-[env(safe-area-inset-bottom)] transition-[margin-bottom] duration-slow ease-out',
        hidden && '-mb-[calc(4rem+env(safe-area-inset-bottom))]',
        className,
      )}
    >
      <HScroll focusable={false}>
        <div className="flex w-max min-w-full">
          {(more && tabs.length > DOCK_KEEP + 1 ? tabs.slice(0, DOCK_KEEP) : tabs).map(tab => (
            <DockButton key={tab.value} icon={tab.icon} label={tab.label} on={tab.value === value} count={tab.count}
              working={tab.working} workingLabel={workingLabel} tourId={tab.tourId} onClick={() => onChange(tab.value)} />
          ))}
          {more && tabs.length > DOCK_KEEP + 1 && (
            <Popover
              placement="top-end"
              widthClass="w-64"
              ariaLabel={more}
              trigger={({ open, toggle, ref }) => (
                <DockButton ref={ref} icon={MoreHorizontal} label={more} menu={{ open }} onClick={toggle}
                  on={open || tabs.slice(DOCK_KEEP).some(tab => tab.value === value)}
                  count={tabs.slice(DOCK_KEEP).reduce((n, tab) => n + (tab.count ?? 0), 0)}
                  working={tabs.slice(DOCK_KEEP).some(tab => tab.working)} workingLabel={workingLabel} />
              )}
            >
              {close => (
                <MenuGroup>
                  {tabs.slice(DOCK_KEEP).map(tab => {
                    const Icon = tab.icon
                    return (
                      <MenuRow
                        key={tab.value}
                        icon={<Icon size={16} aria-hidden="true" />}
                        title={tab.label}
                        tone={tab.value === value ? 'active' : 'default'}
                        trailing={tab.count ? <span className="text-xs text-ink-caption data-readout">{tab.count}</span> : undefined}
                        onSelect={() => { close(); onChange(tab.value) }}
                      />
                    )
                  })}
                </MenuGroup>
              )}
            </Popover>
          )}
          {after}
        </div>
      </HScroll>
    </nav>
  )
}
