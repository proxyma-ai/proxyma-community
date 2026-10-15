/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/AppHeader.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { cn } from './cn'
import { PAGE_COLUMN } from './layout'
import { TAP_TEXT, TAP_TEXT_FROM_SM } from './tap'

/** The link home round the mark: the mark and wordmark, dimmed a step under a mouse. */
export const BRAND_LINK = 'flex items-center gap-2 no-underline mouse:hover:opacity-80 transition-opacity shrink-0 focus-ring rounded-md'

/**
 * A NAVIGATION WORD ON THE BAR: primary ink for the page you are on, caption ink otherwise, primary
 * under a MOUSE only (on glass a bare hover fires on tap and sticks, so the page just left goes on
 * looking current), with the 44px touch box. `from="sm"` for a console whose words move into the
 * account menu below `sm`. The app's header, both consoles' and the site's wrote this four times.
 */
export function headerNavLink(active = false, from: 'always' | 'sm' = 'always'): string {
  return cn(
    from === 'sm' ? TAP_TEXT_FROM_SM : TAP_TEXT,
    'text-sm no-underline transition-colors font-semibold',
    active ? 'text-ink-primary' : 'text-ink-caption mouse:hover:text-ink-primary',
  )
}

/**
 * The bar across the top of every Proxyma surface.
 *
 * <p>FOUR IMPLEMENTATIONS BEFORE 2026-09-06 - the landing site's `Navbar`, the app's `Topbar`, and
 * an `AppShell` in each of the two operator consoles - agreeing on the height, the glass and the
 * centred column, and disagreeing on the fill, the gap, whether the mark had a product suffix, and
 * whether a hand-tuned optical offset was still needed. Each was written by reading one of the
 * others, which is how four copies of one idea come to differ in six ways.
 *
 * <p>THREE SLOTS, because that is what they all actually have:
 *
 * <ul>
 *   <li>`brand` - the mark, and whatever names this surface. Left edge of the centred column.
 *   <li>`children` - nav words, a sign-in button, a menu trigger. Right side of the same column.
 *   <li>`edge` - the preference cluster, pinned to the right edge of the WINDOW rather than of the
 *       column. Optional, and only the app and the landing site pass it.
 * </ul>
 *
 * <p>THE COLUMN RESERVES ROOM FOR `edge`, AND THAT IS THE WHOLE REASON THIS COMPONENT EXISTS
 * rather than a shared class string. The cluster sits outside the centred column, so on any window
 * narrower than the column plus two clusters it will overlap whatever the column's right side
 * holds. Getting that reservation right cost two bugs in one afternoon:
 *
 * <ul>
 *   <li>reserving from `sm` upward put a phone's menu trigger UNDERNEATH the cluster - no
 *       navigation at all at 375px;
 *   <li>writing it as `px-4 sm:px-6 pr-[...]` silently lost the reservation from `sm` up, because
 *       Tailwind emits base utilities BEFORE media-query ones, so `sm:px-6` comes later in the
 *       stylesheet and wins on order. At 812x375 - a phone on its side - the nav drew on top of
 *       the language button.
 * </ul>
 *
 * <p>So the padding is written as separate sides, and the reservation is 7.5rem: two 44px touch
 * targets, their gap and their margin. On a mouse the cluster is 36px a side and this reserves
 * about 16px more than it needs, which is cheaper than a second breakpoint that can be wrong.
 * Above `xl` the gutter outside the column is wider than the cluster, so it goes back to normal.
 *
 * <p>`edgeFrom` EXISTS SO THE RESERVATION AND THE THING RESERVED FOR CANNOT DISAGREE, which is the
 * only way this component has ever been got wrong. The app shows its cluster at every width; the
 * landing site moves both controls into the phone menu below `sm`, because a Download button it
 * cannot afford to hide is already on that row. Two surfaces, two right answers - so the caller
 * says WHEN the cluster exists and this decides both the visibility and the padding from that one
 * word. Setting them separately is how a header reserves 120px on a phone for a cluster that is
 * not there, or draws a nav under one that is.
 */
export interface AppHeaderProps {
  brand: ReactNode
  children?: ReactNode
  edge?: ReactNode
  /**
   * From which width `edge` is on the bar. `'sm'` and `'lg'` hide it below that width and drop the
   * reservation with it; the default keeps it at every width. Ignored when there is no `edge`.
   *
   * <p>`'lg'` is for a header whose hamburger reaches tablet portrait - the application's, where
   * the preferences move into that menu rather than staying on a bar that is already carrying
   * navigation and an account trigger at 810px. Nav words beside it must leave the bar at the same
   * step: a cluster that leaves at `lg` and nav words that arrive at `sm` would leave a tablet
   * showing navigation with a 120px hole reserved beside it.
   */
  edgeFrom?: 'always' | 'sm' | 'lg'
  /** Extra classes on the `<header>` - a surface that is fixed rather than in flow, say. */
  className?: string
  /**
   * The column the bar's content sits in, `PAGE_COLUMN` unless a surface's pages are laid out in a
   * wider one - the landing site's documents (2026-10-15). The pages under the bar must then use the
   * SAME string, or the header reads as detached from them; pass the constant the pages import,
   * never a width retyped here.
   */
  column?: string
}

export function AppHeader({ brand, children, edge, edgeFrom = 'always', className, column = PAGE_COLUMN }: AppHeaderProps) {
  // Base first, media query second, and never a shorthand over either - see the note above on
  // Tailwind's emission order. `pr-4 sm:pr-[7.5rem]` is safe in that direction; the reverse is not.
  const reserve = edge
    ? {
        // Base first, media query second, in every arm - see the note above on emission order.
        sm: 'pr-3 sm:pr-[7.5rem] xl:pr-6',
        lg: 'pr-3 sm:pr-6 lg:pr-[7.5rem] xl:pr-6',
        always: 'pr-[7.5rem] xl:pr-6',
      }[edgeFrom]
    : 'pr-3 sm:pr-6'

  return (
    <header
      className={cn(
        // `relative` so `edge` can be positioned against the bar rather than the page.
        // 44px on a short screen (a phone on its side), where 56px of bar is an eighth of the
        // height and the page below gets what is left.
        'relative shrink-0 z-header h-14 short:h-11 header-bar border-b border-line',
        className,
      )}
    >
      {/* The bar's fill and bottom rule span the viewport; its CONTENT is capped at the same
          max-w-5xl column every page body uses (max-w-7xl from 2xl up, so a 1080p screen is not two
          wide empty margins), which is what makes the mark sit directly above a
          page's first heading. */}
      <div
        className={cn(
          column,
          'h-full flex items-center justify-between gap-3',
          // PAGE_GUTTER's left half - the two have to change together.
          'pl-3 sm:pl-6',
          reserve,
        )}
      >
        {brand}
        {children ? <div className="flex items-center gap-3 sm:gap-6 text-sm">{children}</div> : <span />}
      </div>

      {edge && (
        <div
          className={cn(
            'absolute right-6 top-0 h-full items-center gap-1',
            // One lookup for both halves, so the visibility and the reservation above are read off
            // the same word. They have been set separately before, and the result is a header that
            // reserves 120px on a phone for a cluster that is not there.
            { sm: 'hidden sm:flex', lg: 'hidden lg:flex', always: 'flex' }[edgeFrom],
          )}
        >
          {edge}
        </div>
      )}
    </header>
  )
}
