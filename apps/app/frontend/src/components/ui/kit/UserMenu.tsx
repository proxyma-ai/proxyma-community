/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/UserMenu.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { ChevronUp, Menu } from 'lucide-react'
import { cn } from './cn'
import { MenuSurface } from './menu'
import { TAP_TEXT } from './tap'

/**
 * The header's account menu - who you are, and what you can do about it.
 *
 * <p>THREE IMPLEMENTATIONS BEFORE 2026-09-08: the app's, and one in each operator console. The two
 * console copies were literally byte-identical apart from a `navItems` block, both carrying a
 * comment saying "Port of the app's UserMenu" - which is the honest description of how the third
 * came to exist and exactly the arrangement `sync.mjs` was built to end.
 *
 * <p>WHAT THEY DISAGREED ON was the hover background (`line/20` in the app, `ink-caption/10` in
 * the consoles), whether Escape dismissed the panel (none of the three), and whether the trigger
 * had anything to show below `sm`. That last one is not cosmetic: the app's trigger is a
 * `hidden sm:block` span with no icon behind it, so on a phone it was a focusable control that
 * rendered zero pixels wide. The app avoids it by not rendering this below `sm` at all - it has
 * `MobileMenu` for that width - and the consoles have no separate phone menu, so THIS one becomes
 * it and the trigger turns into a hamburger. `withMenuIcon` is that difference, and it is the only
 * one worth a prop.
 *
 * <p>NO AVATAR, in any of the three. There is no real profile picture behind an SSO account, a
 * local account or an admin bearer token, so a generated-initial circle would be decoration
 * occupying header space. The name is the control.
 *
 * <p>THE SIDEBAR IS THE EXCEPTION, since 2026-10-29 (the maintainer's glass shell, which has no
 * header): at the foot of the sidebar the menu is a row - the person's initials in a circle, their
 * name and, under it, `caption` (an email) - and it opens UPWARD. The circle marks the row as the
 * account at a glance in a column of page names; folded to icons it is all that is left.
 */
export interface UserMenuProps {
  /** Accessible name for the trigger - below `sm` it can be a glyph with no text at all. */
  label: string
  /**
   * Shown on the trigger from `sm` up. A display name, an email, a username - whatever this
   * surface knows. Never truncated: a clipped identity in a header reads as broken rather than
   * deliberate, and this trigger has no fixed width to protect.
   */
  name: ReactNode
  /**
   * Turns the trigger into a hamburger below `sm`. For a surface where this menu IS the phone
   * menu, which is both consoles. An app with its own `MobileMenu` leaves it off.
   */
  withMenuIcon?: boolean
  /** The rows. `close` dismisses the panel, and every row that navigates or acts must call it. */
  children: (close: () => void) => ReactNode
  className?: string
  panelClassName?: string
  /** `sidebar`: the row at the foot of the app's sidebar, opening upward (see above). */
  variant?: 'bar' | 'sidebar'
  /** Under the name in the sidebar row - an email. */
  caption?: ReactNode
  /** The circle's letters in the sidebar row - or a glyph, for a visitor with no name. */
  initials?: ReactNode
  /** The sidebar folded to its icons: the circle alone. */
  folded?: boolean
}

/** Up to two letters for a name or an email: "Avery Admin" is AA, "avery@x.test" is A. */
export function initialsOf(name: string): string {
  const words = name.replace(/@.*$/, '').split(/[\s._-]+/).filter(Boolean)
  return (words.length > 1 ? words[0][0] + words[words.length - 1][0] : (words[0] ?? '?').slice(0, 1)).toUpperCase()
}

export function UserMenu({
  label, name, withMenuIcon, children, className, panelClassName, variant = 'bar', caption, initials, folded,
}: UserMenuProps) {
  if (variant === 'sidebar') {
    return (
      <MenuSurface
        className={cn('w-full', className)}
        panelClassName={panelClassName}
        side="top"
        trigger={({ open, toggle, controls }) => (
          <button
            type="button"
            onClick={toggle}
            aria-label={label}
            aria-expanded={open}
            aria-haspopup="menu"
            aria-controls={controls}
            className={cn('w-full flex items-center gap-2.5 rounded-lg p-1.5 text-left transition-colors focus-ring',
              'mouse:hover:bg-surface/70', open && 'bg-surface/70', folded && 'justify-center')}
          >
            <span aria-hidden="true"
              className="shrink-0 w-8 h-8 rounded-full grid place-items-center bg-brand-solid text-brand-on-solid text-xs font-semibold">
              {initials}
            </span>
            {!folded && (
              <>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium text-ink-primary">{name}</span>
                  {caption && <span className="block truncate text-xs text-ink-caption">{caption}</span>}
                </span>
                {/* Up: the menu opens above the row (the maintainer, 2026-10-30). */}
                <ChevronUp size={14} aria-hidden="true" className="shrink-0 text-ink-caption" />
              </>
            )}
          </button>
        )}
      >
        {children}
      </MenuSurface>
    )
  }
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
          // Plain text matching the nav words beside it, not a filled pill. All three carried a
          // pill branch that only rendered when a caller passed a trigger class, and the only
          // caller that ever did was a rebranded tenant - a code path removed with TENANT-A.
          // TAP_TEXT rather than a bare `flex items-center`: above `sm` the hamburger span below
          // is gone and this trigger is the account NAME and nothing else, which measured 22px
          // tall on an iPad mini - `text-sm`'s line box, under even WCAG 2.2's 24px floor, on a
          // bar whose every icon control is 44px. See tap.ts; it is the same decision the nav
          // words in all four headers take.
          className={cn(
            TAP_TEXT,
            'gap-2 transition-colors focus-ring',
            'text-sm text-ink-caption mouse:hover:text-ink-secondary',
          )}
        >
          {/* BELOW `sm` THIS BUTTON IS A GLYPH AND NOTHING ELSE, so the glyph carries a target of
              its own. Measured at 375 without it: 18x18, the icon and no padding - under even WCAG
              2.2's 24px floor, on the one control that IS the navigation at that width.

              THE BOX IS ON THE SPAN, NOT THE BUTTON, and that is not a style preference. Sizing
              the button meant `w-9 touch:w-11` against `sm:w-auto`, which are both media rules at
              equal specificity, so source order decided and `touch:` won - a 44px button with the
              account name overflowing it at 1024 wide. A `sm:hidden` span has no such argument: it
              is simply not there above `sm`, and the name sizes the button again. */}
          {withMenuIcon && (
            <span
              className="sm:hidden flex items-center justify-center shrink-0 w-9 h-9 touch:w-11 touch:h-11"
              aria-hidden="true"
            >
              <Menu size={18} />
            </span>
          )}
          <span className="hidden sm:block whitespace-nowrap">{name}</span>
        </button>
      )}
    >
      {children}
    </MenuSurface>
  )
}
