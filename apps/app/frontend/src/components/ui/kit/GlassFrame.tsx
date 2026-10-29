/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/GlassFrame.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { cn } from './cn'

/**
 * THE APP AS ONE FROSTED FRAME (the maintainer's shell, 2026-10-29: "no header at all + glass
 * effect"; all of it glass the same day: "I want to have the glass effect"). Three layers:
 *
 * <ol>
 * <li>the FIELD - the window behind everything, one flat cool color: the app shell's own
 *   body (`.app-field` in proxyma-primitives.css), so it is behind this frame, behind the header
 *   and page below `lg`, and behind the sign-in screens alike. No text sits on it;</li>
 * <li>the FRAME - one rounded pane of frosted glass over the field (`.app-glass`: translucent, a
 *   backdrop blur, a light edge and a deep soft shadow), holding the sidebar on its left;</li>
 * <li>the SHEET - the page itself, a pane of glass inside the frame, more opaque than the frame so
 *   its text stays crisp, with a soft teal light in its bottom-right corner
 *   (`.app-sheet`). The cards a page draws are glass on it (`.surface-card`).</li>
 * </ol>
 *
 * Where the reader asks the system for less transparency, every pane is its solid equivalent (the
 * tokens). The frame is the shell from `lg` up; below it the app keeps its header and phone menu
 * over the same field and sheet, so a caller renders this only there.
 *
 * TWO PLACES IT IS DRAWN IN (the maintainer's call, 2026-10-29):
 * - `page` - a browser tab, a server's or a desktop opened in a browser: the frame IS the page, edge
 *   to edge, with no margin and no outer corners; the field only tints its glass.
 * - `window` - the desktop app's own borderless window: the frame IS the window, edge to edge, as a
 *   page is (the maintainer: "The app must be this box. Nothing outside it."), with a strip across
 *   the top of the page column holding the window's own buttons (`windowBar`, the kit's
 *   WindowControls) - the window's drag handle, a double-click maximizing. Its corners are the
 *   operating system's: Windows 11 rounds a frameless window.
 */
export function GlassFrame({ sidebar, children, mode = 'page', windowBar, onToggleMaximize, className }: {
  /** The navigation column, on the glass (AppSidebar). */
  sidebar: ReactNode
  /** The page: banners, then the scrolling content. */
  children: ReactNode
  mode?: 'page' | 'window'
  /** `window`: the window's buttons, at the right of the strip over the page. */
  windowBar?: ReactNode
  /** `window`: a double-click on the strip. */
  onToggleMaximize?: () => void
  className?: string
}) {
  const windowed = mode === 'window'
  return (
    <div className={cn('relative h-full', className)}>
      <div className="app-glass h-full min-h-0 flex gap-2 p-2 rounded-none border-0">
        {sidebar}
        <div className="flex-1 min-w-0 min-h-0 flex flex-col gap-1">
          {windowed && (
            <div className="shrink-0 h-8 flex items-center justify-end [-webkit-app-region:drag]" onDoubleClick={onToggleMaximize}>
              {windowBar}
            </div>
          )}
          <div className="app-sheet flex-1 min-w-0 min-h-0 flex flex-col overflow-hidden rounded-xl border shadow-card">
            {children}
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * The sidebar column on the glass: its head (the logo and the fold button), what it lists, and its
 * foot (Settings and the account). 15rem wide, 4rem folded to its icons; the width eases between
 * the two.
 */
export function AppSidebar({ head, children, foot, folded, ariaLabel }: {
  head: ReactNode
  children: ReactNode
  foot: ReactNode
  folded: boolean
  ariaLabel: string
}) {
  return (
    <nav
      aria-label={ariaLabel}
      className={cn('shrink-0 min-h-0 flex flex-col gap-1 py-1 transition-[width] duration-base ease-out',
        folded ? 'w-14 items-center' : 'w-60')}
    >
      <div className="shrink-0">{head}</div>
      <div className="flex-1 min-h-0 flex flex-col gap-1 overflow-y-auto overflow-x-hidden">{children}</div>
      <div className="shrink-0 flex flex-col gap-1 pt-1">{foot}</div>
    </nav>
  )
}

/**
 * A row of the sidebar - a page, a recent conversation, Settings: its icon and its words, the
 * current one LIFTED out of the glass (a glass card pill, `.surface-card`, with the card shadow, its icon in the
 * brand's ink), the rest quiet until the pointer is on them. Folded, it is the icon alone, square.
 * A class rather than a component, so a router's link and a button both take it.
 */
export function sidebarRow({ active = false, folded = false, quiet = false }: { active?: boolean; folded?: boolean; quiet?: boolean } = {}): string {
  return cn(
    // Medium weight throughout: Space Grotesk's regular renders soft at 14px on Windows.
    'group relative flex items-center gap-2.5 rounded-lg text-sm font-medium transition-[background-color,color,box-shadow] duration-fast focus-ring',
    folded ? 'w-10 h-10 justify-center' : 'w-full h-9 touch:h-11 px-2.5',
    active
      ? 'surface-card text-ink-primary shadow-card [&_svg]:text-brand-legible'
      : quiet
        ? 'text-ink-secondary mouse:hover:bg-surface/60 mouse:hover:text-ink-primary'
        : 'text-ink-secondary mouse:hover:bg-surface/60 mouse:hover:text-ink-primary',
  )
}

/** A small heading over a group of the sidebar's rows ("Recent chats"), with an optional action at its right. */
export function SidebarSection({ title, action, className }: { title: ReactNode; action?: ReactNode; className?: string }) {
  return (
    <div className={cn('flex items-center justify-between gap-2 px-2.5 pt-4 pb-1', className)}>
      <span className="text-xs font-semibold text-ink-caption">{title}</span>
      {action}
    </div>
  )
}
