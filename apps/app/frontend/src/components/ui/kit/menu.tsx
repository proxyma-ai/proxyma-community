/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/menu.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { cn } from './cn'
import { exitingProps, usePresence } from './motion'
import { useMenuFocus } from './useMenuFocus'

/**
 * The parts every Proxyma dropdown menu is built from.
 *
 * <p>FIVE MENUS ACROSS FOUR APPS were each written whole: the app's `MobileMenu`, `AppMenu` and
 * `UserMenu`, and a `UserMenu` in each operator console. They agreed on the panel, the
 * outside-click, the row metrics and the hairline groups, and disagreed on which of those details
 * they had got right - one dismissed on Escape, two swapped the trigger glyph, the hover
 * background was `ink-caption/10` in three and `line/20` in the fourth, and the row padding came
 * in three heights.
 *
 * <p>`MenuSurface` is the shell: open state, dismissal, and the panel. It takes BOTH the trigger
 * and the rows as functions, because a menu's trigger is the one part that is never the same -
 * a hamburger, an account name, a gear - while everything behind it always is.
 */

/**
 * The panel itself, as a class string, for a menu that owns its own surface.
 *
 * <p>Exported so an app-local menu can look identical without importing the shell - the app's
 * `AppMenu` is the case. Prefer `MenuSurface` when you can: a class string cannot give you the
 * dismissal behavior, and dismissal is what these menus get wrong.
 */
export const MENU_PANEL =
  'absolute right-0 top-full mt-2 w-56 z-float ' +
  'surface-float border border-line/45 rounded-lg shadow-card overflow-hidden animate-dropdown ' +
  // A phone on its side has about 300px of usable height under the bar, and these menus can carry
  // a dozen rows. Scroll rather than run off the bottom of the screen - but ONLY then.
  //
  // THE CAP IS THE SPACE UNDER THE TRIGGER, not a share of the screen. It was `70dvh` until
  // 2026-09-30, and at 1280x720 that is 504px for a Super admin's menu of 534px, so the menu
  // opened with a scrollbar and Sign out below the fold on a screen with 130px to spare under it.
  // `MenuSurface` measures that space when it opens (fitMenuPanel below); this class is only the
  // fallback for a panel nobody measured - the whole height under a header and a banner.
  //
  // dvh, NOT vh. On iOS Safari `vh` is the LARGEST viewport - the height the page would have with
  // the address bar and toolbar hidden - while the page around it is `height: 100dvh; overflow:
  // hidden` and cannot scroll to reveal the rest. The bottom rows of a long menu were simply
  // unreachable, and Quit is the last one.
  'max-h-[calc(100dvh-6rem)] overflow-y-auto'

/** The least distance a measured menu panel keeps from the bottom of the viewport. */
const VIEWPORT_MARGIN = 8

/**
 * Caps an open panel at the height actually left under it, so it scrolls only when the screen is
 * genuinely too short for it.
 *
 * <p>Measured from the ANCHOR, not the panel: the panel is mid-animation when this runs, scaled
 * and shifted by `animate-dropdown`, so its own rectangle is off by several pixels. The anchor's
 * bottom plus the panel's top margin is where the panel will settle.
 */
function fitMenuPanel(anchor: HTMLElement, panel: HTMLElement) {
  const top = anchor.getBoundingClientRect().bottom + (parseFloat(getComputedStyle(panel).marginTop) || 0)
  const available = window.innerHeight - top - VIEWPORT_MARGIN
  // A floor, so a phone on its side still gets a usable scrolling panel rather than a sliver.
  panel.style.maxHeight = `${Math.max(160, Math.floor(available))}px`
  // And inside the window sideways: the panel hangs right-aligned from its trigger, which runs it
  // off the LEFT edge when the trigger sits near it (a narrow window, a menu that is not in a
  // header). Measured with the correction removed, so it never corrects a corrected position.
  // `translate`, not `transform`: the open animation holds the transform.
  panel.style.translate = ''
  const rect = panel.getBoundingClientRect()
  const shift = rect.left < VIEWPORT_MARGIN ? VIEWPORT_MARGIN - rect.left
    : rect.right > window.innerWidth - VIEWPORT_MARGIN ? window.innerWidth - VIEWPORT_MARGIN - rect.right : 0
  if (shift) panel.style.translate = `${Math.round(shift)}px 0`
}

export type MenuTone = 'default' | 'active' | 'danger'

// THE KEYBOARD'S ROW IS THE POINTER'S ROW: a focused row takes the hover fill, not an offset ring -
// the panel clips (rounded, overflow hidden), so a ring round a full-width row showed as two lines.
const ROW_BASE =
  'w-full flex items-center gap-2.5 px-4 py-2 touch:min-h-tap ' +
  'text-sm text-left no-underline transition-colors focus-visible:outline-none'

/**
 * One row of a menu, in the tone the call site means.
 *
 * <p>ONE HEIGHT FOR EVERY MENU, decided by the POINTER rather than by which menu it is. These came
 * in three - `py-2` in the consoles, `py-2.5` in the phone menu, `py-2` again in the app's pointer
 * menus - on the reasoning that a phone menu wants taller rows. It does, but so does a console
 * menu opened on a tablet, and `touch:min-h-tap` asks the question the right way round: 36px under
 * a mouse, 44px under a finger, in all five menus.
 *
 * <p>Hover is `mouse:hover:` rather than a bare `hover:`, because on glass a bare hover fires on
 * tap and then STICKS until you tap elsewhere - the row you just left goes on looking like the row
 * you are on.
 *
 * @param tone `active` marks the row you are already on; `danger` is sign-out and quit.
 */
export function menuRow(tone: MenuTone = 'default', className?: string) {
  return cn(
    ROW_BASE,
    tone === 'active' && 'text-ink-primary font-semibold bg-brand-chip-bg/40',
    tone === 'active' && 'focus-visible:bg-brand-chip-bg/70',
    tone === 'danger' && 'text-danger mouse:hover:bg-danger/10 focus-visible:bg-danger/10',
    tone === 'default' && 'text-ink-secondary mouse:hover:text-ink-primary mouse:hover:bg-ink-caption/10 focus-visible:text-ink-primary focus-visible:bg-ink-caption/10',
    className,
  )
}

/**
 * A hairline-separated block of rows.
 *
 * <p>`last:border-b-0` rather than a `divide-y` on the panel, because the groups are conditional -
 * an account block, a nav list, a desktop-only Quit - and a trailing rule under the final group is
 * the one thing that reads as a mistake.
 *
 * <p>THE VERTICAL PADDING LIVES HERE RATHER THAN ON THE PANEL, so it falls either side of every
 * divider. A `py-1` on the panel alone gives the ends breathing room and presses the rows flat
 * against each rule. Rows therefore belong in a group, not loose in the panel.
 *
 * <p>AND NOT AT THE TWO ENDS - `first:pt-0 last:pb-0`, at the maintainer's request on 2026-09-16.
 * The panel is `rounded-lg overflow-hidden`, so a row that reaches the edge is clipped INTO that
 * radius and the first and last rows read as part of the container. Four pixels of padding there
 * instead left the top row's hover a square block floating inside a rounded box, which is the one
 * place the seam shows.
 */
export function MenuGroup({ children, className, label }: { children: ReactNode; className?: string; label?: ReactNode }) {
  return (
    <div
      role={label ? 'group' : undefined}
      aria-label={typeof label === 'string' ? label : undefined}
      className={cn('border-b border-line/45 last:border-b-0 py-1 first:pt-0 last:pb-0', className)}
    >
      {/* A quiet caption over the rows - the Connect menu's categories. It is never a row and takes
          no hover, and it keeps its own top padding, since a first group's pt-0 is there for a ROW
          taking the panel's rounded corner, which a caption does not. */}
      {label && <p className="px-4 pt-2 pb-1 text-xs font-medium text-ink-caption select-none">{label}</p>}
      {children}
    </div>
  )
}

/**
 * The identity line at the top of an account menu - the email or username, never a destination.
 *
 * <p>Truncated rather than wrapped: an address long enough to wrap turns a 36px row into three,
 * and the panel is a fixed width by design. The full text is the row's tooltip, since the
 * truncated end of an email is the part that tells two accounts apart.
 *
 * <p>THE PADDING IS ON THE LINE, NOT THE GROUP. It was `px-4 py-2.5` on the MenuGroup until
 * 2026-09-30, and MenuGroup's own `first:pt-0` - there so a first ROW takes the panel's rounded
 * corner - beat the `py-2.5` for the one group that is always first. The address sat flush
 * against the top edge of the panel and read as clipped. On the line it is the same 36px as a
 * menu row, whatever the group does at the ends.
 */
export function MenuIdentity({ children, title }: { children: ReactNode; title?: string }) {
  return (
    <MenuGroup>
      <p
        className="px-4 py-2.5 text-xs text-ink-disabled truncate"
        title={title ?? (typeof children === 'string' ? children : undefined)}
      >
        {children}
      </p>
    </MenuGroup>
  )
}

export interface MenuSurfaceProps {
  /**
   * The button that opens the menu. `controls` is the panel's generated id - put it on
   * `aria-controls`, and pair it with `aria-expanded={open}`.
   */
  trigger: (args: { open: boolean; toggle: () => void; controls: string }) => ReactNode
  /** The rows. `close` dismisses the panel, and every row that navigates or acts must call it. */
  children: (close: () => void) => ReactNode
  className?: string
  panelClassName?: string
  /** `menu` when the rows are `menuitem`s (the appearance menu); omit for rows that are links. */
  role?: 'menu'
  /** Names the panel when it has a role. */
  ariaLabel?: string
}

/**
 * THE MENU SHELL under a header trigger: open state, dismissal (a press outside, Escape) and the
 * panel, plus the menus' keyboard (`useMenuFocus`) - opened from the keyboard it moves focus onto
 * the first row; arrows, Home/End and typing move between rows; Escape and Tab close it and give
 * focus back to the trigger.
 */
export function MenuSurface({ trigger, children, className, panelClassName, role, ariaLabel }: MenuSurfaceProps) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const panelId = useId()
  // Kept mounted for its exit (usePresence): it fades as it closes rather than vanishing.
  const { mounted, exiting } = usePresence(open)

  // Pointerdown rather than click, matching LanguageMenu: a press that lands on another control
  // should close this AND reach that control, which is what happens when the close runs on the
  // way down.
  //
  // ESCAPE IS HERE BECAUSE FOUR OF THE FIVE MENUS DID NOT HAVE IT. A dropdown that can only be
  // dismissed by pointing somewhere else is one a keyboard cannot get out of.
  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: PointerEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false)
    }
    // preventDefault marks the key as handled, so a dialog beneath the menu stays open.
    const onKeyDown = (e: KeyboardEvent) => { if (e.key === 'Escape' && !e.defaultPrevented) { e.preventDefault(); setOpen(false) } }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  // The trigger is the caller's markup, so it is found rather than handed over: the control that
  // names this panel in aria-controls, else the first control in the root.
  const triggerRef = useMemo<RefObject<HTMLElement>>(() => ({
    get current() {
      const root = rootRef.current
      if (!root) return null
      return Array.from(root.querySelectorAll<HTMLElement>('[aria-controls]')).find(el => el.getAttribute('aria-controls') === panelId)
        ?? root.querySelector<HTMLElement>('button, a[href]')
    },
  }), [panelId])
  const onMenuKeyDown = useMenuFocus(panelRef, { open, triggerRef, close: () => setOpen(false) })

  // Before paint, so the panel never shows a scrollbar for one frame and then loses it.
  useLayoutEffect(() => {
    if (!open) return
    const root = rootRef.current
    const panel = panelRef.current
    if (!root || !panel) return
    const fit = () => fitMenuPanel(root, panel)
    fit()
    window.addEventListener('resize', fit)
    return () => window.removeEventListener('resize', fit)
  }, [open])

  return (
    <div className={cn('relative', className)} ref={rootRef}>
      {trigger({ open, toggle: () => setOpen(o => !o), controls: panelId })}

      {mounted && (
        <div id={panelId} ref={panelRef} role={role} aria-label={role ? ariaLabel : undefined} {...exitingProps(exiting)} onKeyDown={onMenuKeyDown} className={cn(MENU_PANEL, panelClassName, exiting && 'animate-pop-out')}>
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  )
}
