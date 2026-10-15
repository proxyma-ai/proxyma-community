'use client'

import type { ReactNode } from 'react'
import { Menu, X } from 'lucide-react'
import { cn } from './cn'
import { MenuSurface } from './menu'
import { iconButton } from './iconButton'

/**
 * The header's phone-width menu.
 *
 * <p>TWO IMPLEMENTATIONS BEFORE 2026-09-08, in the app and on the landing site, agreeing on the
 * shell and the row metrics and disagreeing on four things that were accidents rather than
 * decisions: only one swapped the trigger glyph for a close X, only one dismissed on Escape, only
 * one wired `aria-controls`, and the rows sat at `px-3.5` on the site and `px-4` in the app. Each
 * was written by reading the other, which is how two copies of one idea come to differ four ways.
 *
 * <p>THE ROWS ARE NOT SHARED, AND THAT IS THE POINT OF THE SPLIT. The site's menu holds three
 * anchors, a language list and a theme row; the app's holds react-router `NavLink`s, a settings
 * modal, sign-out and desktop's Quit. Nothing about those is common, they reach different state
 * layers, and a component that tried to own both would take a
 * dozen props to describe one menu. So this owns the trigger, `MenuSurface` owns the shell, and
 * `children` is a function of `close` - the one thing every row needs from it.
 *
 * <p>`menuRow()` is how the two stay identical where they SHOULD be identical: one row geometry,
 * three tones, and a call site that says which tone it means.
 *
 * <p>VISIBILITY BELONGS TO THE CALLER. Both apps pass `sm:hidden` today, which is the width-only
 * rule the responsive contract warns about - at 812x375, a phone on its side, `sm` matches and
 * this menu disappears in favour of a nav bar on a viewport 375px tall. Keeping it a prop rather
 * than baking it in means that gets fixed in the wrappers when the responsive pass reaches them,
 * not by re-cutting a shared component under four apps at once.
 */
export interface MobileMenuProps {
  /** Already translated - the trigger is icon-only. */
  label: string
  /** The rows. `close` dismisses the panel, and every row that navigates or acts must call it. */
  children: (close: () => void) => ReactNode
  /** Extra classes on the root. Both apps pass the width rule that decides when this exists. */
  className?: string
  /** Extra classes on the panel - a menu with more rows than the default 14rem is comfortable at. */
  panelClassName?: string
}

export function MobileMenu({ label, children, className, panelClassName }: MobileMenuProps) {
  return (
    <MenuSurface
      className={className}
      panelClassName={panelClassName}
      trigger={({ open, toggle, controls }) => (
        <button
          type="button"
          onClick={toggle}
          aria-label={label}
          aria-expanded={open}
          aria-haspopup="menu"
          aria-controls={controls}
          // The one icon-only recipe (iconButton.ts), like every other icon button on the bar.
          className={open ? cn(iconButton('lg', null), 'text-ink-primary') : iconButton('lg')}
        >
          {open ? <X size={18} aria-hidden="true" /> : <Menu size={18} aria-hidden="true" />}
        </button>
      )}
    >
      {children}
    </MenuSurface>
  )
}
