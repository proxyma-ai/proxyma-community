'use client'

import { Lock } from 'lucide-react'
import type { GlyphComponent } from './BrandMark'
import { cn } from './cn'
import { moveIndex, type NavKey } from './listNav'
import { HScroll } from './HScroll'
import { Tooltip } from './Tooltip'

export interface SubNavTab<T extends string> {
  key: T
  label: string
  // OPTIONAL since 2026-09-30. A lucide icon, or a source's mark from markIcon() where the tabs are
  // things that have a real mark (source types, AI clients). Page sections - Overview, Users - pass
  // none: a generic line icon before every word is decoration, not information.
  icon?: GlyphComponent
  count?: number
  // Tier-gated: the tab stays in the bar, dimmed, with a lock glyph and `lockReason` as its
  // tooltip. It is NOT disabled - onChange still fires, and the caller decides what a click means
  // (the Connectors page opens the upgrade offer). Disabling it would kill the tooltip too, since
  // a disabled button fires no pointer events, leaving a greyed tab with no stated reason.
  locked?: boolean
  lockReason?: string
}

interface Props<T extends string> {
  tabs: readonly SubNavTab<T>[]
  active: T
  onChange: (key: T) => void
  /** Names the tab list for a screen reader - "Administration sections", say. */
  ariaLabel?: string
  /**
   * `compact` - 13px words in tighter pills - for a row inside a narrow panel (a Constellations agent's
   * seven tabs in a 32rem panel, maintainer's review 2026-10-29), so it fits rather than scrolls.
   */
  density?: 'regular' | 'compact'
}

// Shared sub-page tab bar (Settings / Admin / Sources and both consoles use it, so the interaction
// cannot drift between them).
//
// A WELL WITH THE CURRENT TAB LIFTED OUT OF IT, since 2026-10-29 (the maintainer's review: "this
// sub menu bar should be redesigned"), the approved "Lifted" style of the app's sidebar: the tabs
// sit in a sunken, rounded well, the current one a raised pill (the float plane and the card
// shadow) in primary ink, the rest secondary ink that brightens under the pointer. Words as written,
// no icons for page sections, a count as a plain caption. The underline below is what it replaced.
//
// UNDERLINED, SENTENCE-CASE TABS, from 2026-09-30 (app UI review, finding F2) - the landing site's
// tab shape (ShowcaseSection, GuardrailsSection): the words as written, the selected one in
// ink-primary over a brand rule, the rest in caption ink, one hairline under the row. It was a row
// of bordered, uppercase, icon-led pills from 2026-09-06, which was the loudest thing on every page
// it opened and the "pill row" that reads as generated.
//
// ONE ROW, NEVER WRAPPED: an underline on a second line looks like a typographic accident, which is
// what made the pill version wrap. The row scrolls sideways instead, through the kit's HScroll - no
// bar, the wheel scrolls it, the cut edge fades. Not a tab stop of its own (`focusable={false}`):
// tabbing to a tab already scrolls it into view.
//
// The baseline and the selection rule are both drawn INSIDE the row (pseudo-elements at bottom 0)
// rather than as a border with a -1px margin, because a scrolling region clips anything that
// hangs past its edge.
export function SubNavTabs<T extends string>({ tabs, active, onChange, ariaLabel, density = 'regular' }: Props<T>) {
  // ONE TAB STOP FOR THE ROW, and the arrows walk it (since 2026-10-14; WAI-ARIA tabs, manual
  // activation): Left/Right move between the tabs, Home/End go to the ends, Enter or Space opens
  // the one focused. Opening on every arrow would load a page per keypress.
  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const keys: Record<string, NavKey> = { ArrowRight: 'ArrowDown', ArrowLeft: 'ArrowUp', Home: 'Home', End: 'End' }
    const nav = keys[e.key]
    if (!nav) return
    const buttons = Array.from(e.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]'))
    const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
    const next = moveIndex(at, buttons.length, nav)
    if (next < 0) return
    e.preventDefault()
    buttons[next].focus()
  }
  return (
    <HScroll focusable={false}>
      <div
        role="tablist"
        aria-label={ariaLabel}
        onKeyDown={onKeyDown}
        // ONE SHAPE FOR EVERY WELL OF TABS (the maintainer, 2026-10-30: corners differed between two
        // bars of one kind): a 12px track, a 4px inset and 8px tabs, so the corners are concentric at
        // either density - the same as SegmentedControl's.
        className="inline-flex w-max items-center gap-0.5 rounded-lg bg-surface-alt shadow-well p-1"
      >
        {tabs.map(t => {
          const Icon = t.icon
          const isActive = active === t.key
          const tab = (
            <button
              key={t.key}
              type="button"
              role="tab"
              aria-selected={isActive}
              tabIndex={isActive || (!tabs.some(x => x.key === active) && t === tabs[0]) ? 0 : -1}
              onClick={() => onChange(t.key)}
              className={cn(
                'relative shrink-0 flex items-center gap-2 whitespace-nowrap font-medium rounded-md focus-ring',
                'transition-[background-color,color,box-shadow] duration-base ease-out',
                density === 'compact' ? 'h-7 touch:h-10 px-2.5 text-xs' : 'h-8 touch:h-10 px-3.5 text-sm',
                t.locked
                  ? 'text-ink-disabled'
                  : isActive
                    ? 'bg-surface-float text-ink-primary shadow-card'
                    : 'text-ink-secondary mouse:hover:text-ink-primary mouse:hover:bg-surface/50',
              )}
            >
              {/* Quiet: the tab's own ink, at the rules' 14px for marks in tabs. */}
              {Icon && <Icon size={14} className={cn('shrink-0', t.locked && 'opacity-50')} aria-hidden="true" />}
              <span>{t.label}</span>
              {/* A locked tab shows the lock INSTEAD of a count. Its count is always zero - the
                  plan does not allow the connector, so none can exist - and "0" beside a name
                  reads as "you have none of these yet" rather than "this is not in your plan". */}
              {t.locked ? (
                <Lock size={12} className="shrink-0 text-ink-disabled" aria-hidden="true" />
              ) : !!t.count && (
                // A plain caption, not a chip: the number is a fact about the tab, not a control.
                <span className="text-xs font-normal text-ink-caption data-readout">{t.count}</span>
              )}
            </button>
          )

          return t.locked && t.lockReason
            ? <Tooltip key={t.key} label={t.lockReason} side="bottom">{tab}</Tooltip>
            : tab
        })}
      </div>
    </HScroll>
  )
}
