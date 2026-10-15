/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/IconMenu.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { cn } from './cn'
import { iconButton } from './iconButton'
import { MenuGroup, MenuSurface } from './menu'

/**
 * The header's icon-only button and the small panel of choices it opens - the shell under
 * `LanguageMenu` and `AppearanceMenu`, which the consoles carry on their bar and the app shows a
 * signed-out visitor.
 *
 * <p>Since 2026-10-14 it IS `MenuSurface` with an icon trigger. It had a panel and a row recipe of
 * its own (`iconMenuRow`: px-3.5 rows, no fill on the current one, a 70dvh cap that menu.tsx calls
 * the bug it fixed on 2026-09-30, `aria-haspopup="true"` and a panel with no role), so the same two
 * choices looked and behaved differently in a console's header and in the app's menu. Rows are
 * `menuRow()` now, the panel is measured to the space under it, and the keyboard is the menus'.
 */
export interface IconMenuProps {
  /** The trigger's glyph, 16px. */
  icon: ReactNode
  /** Already translated - the trigger is icon-only. */
  label: string
  children: (close: () => void) => ReactNode
  /** `menu` when every row is a `menuitem`/`menuitemradio`; omit for rows a caller draws as links. */
  role?: 'menu'
  className?: string
}

export function IconMenu({ icon, label, children, role, className }: IconMenuProps) {
  return (
    <MenuSurface
      className={cn('shrink-0', className)}
      panelClassName="w-auto min-w-[10rem]"
      role={role}
      ariaLabel={label}
      trigger={({ open, toggle, controls }) => (
        <button
          type="button"
          onClick={toggle}
          aria-label={label}
          aria-expanded={open}
          aria-haspopup="menu"
          aria-controls={controls}
          className={open ? cn(iconButton('lg', null), 'text-ink-primary') : iconButton('lg')}
        >
          {icon}
        </button>
      )}
    >
      {close => <MenuGroup>{children(close)}</MenuGroup>}
    </MenuSurface>
  )
}
