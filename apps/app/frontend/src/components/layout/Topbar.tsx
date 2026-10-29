import { useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { FeatureGuard } from '@/components/guards/FeatureGuard'
import { UserMenu } from '@/features/auth/components/UserMenu'
import { AppMenu } from '@/components/layout/AppMenu'
import { MobileMenu } from '@/components/layout/MobileMenu'
import { AppearanceMenu } from '@/components/layout/AppearanceMenu'
import { LanguageMenu } from '@/components/layout/LanguageMenu'
import { AppHeader, BRAND_LINK, Button, Presence, WorkingBadge, headerNavLink, useIdleRotation } from '@/components/ui/kit'
import { Logo } from '@/components/ui/Logo'
import { useAuthStore } from '@/store/authStore'
import { useWorkingAgents, type WorkingAgent } from '@/hooks/useWorkingAgents'
import { useNavItems } from './navItems'
import { FEATURES } from '@/config/features'
import { useTranslation } from '@/i18n'
import { cn } from '@/utils/cn'

// The pages this reader may open come from useNavItems (navItems.ts), shared with the phone menu and,
// from `lg`, the sidebar of the glass shell (2026-10-29).

// A nav word on the bar is the kit's headerNavLink: primary ink for the page you are on, caption
// ink otherwise, primary under a MOUSE only (on glass a bare hover fires on tap and then STICKS
// until you tap elsewhere, so the page you just navigated away from goes on looking like the page
// you are on). The logo link home is the kit's BRAND_LINK.

export function Topbar() {
  const navigate = useNavigate()
  const user = useAuthStore(s => s.user)
  const { t } = useTranslation()

  // The mark's idle turn - the kit's loop, shared with both consoles.
  const iconRef = useRef<SVGSVGElement>(null)
  useIdleRotation(iconRef)

  // THE NAV WORDS STAY ON THE BAR WHILE THEY FIT, since 2026-09-27 (the maintainer's request).
  // They used to leave at a fixed `lg` - reasoned from a tablet held upright, where the bar ran out
  // of width - which also took them off every bar that had plenty of room for them. Now the bar
  // MEASURES: the words are shown whenever they fit beside the logo and the menus, and step out of
  // the way only when they would reach the logo. The hamburger below `lg` still carries them as
  // rows, so nothing is ever unreachable.
  const brandRef = useRef<HTMLAnchorElement>(null)
  const navRef = useRef<HTMLDivElement>(null)
  const navFits = useFitsBesideBrand(brandRef, navRef)
  const workingAgents = useWorkingAgents()

  const visibleNavItems = useNavItems()

  return (
    <AppHeader
      brand={
        <NavLink ref={brandRef} to="/" className={BRAND_LINK}>
          <Logo iconRef={iconRef} />
        </NavLink>
      }
      /* APPEARANCE AND LANGUAGE LIVE IN THE MENUS, since 2026-09-14 - the maintainer's call, which
         reverses 2026-09-06's move onto the bar. At every width they are rows in whichever menu
         this header shows: AppMenu on desktop and UserMenu on server from `lg` up, MobileMenu
         below it. See PreferenceMenuRows.

         THE ONE EXCEPTION IS A SIGNED-OUT SERVER VISITOR, who has no menu at `lg` and up - the
         header shows Sign In where UserMenu would be. Taking the controls off the bar for them too
         would leave no way to change either, so for exactly that case they stay at the right edge
         of the window, `edgeFrom="lg"` so they never overlap MobileMenu's rows below it. The rule
         since 0.5.0 still holds: never reachable from two places at once. */
      edge={FEATURES.auth && !user ? <><LanguageMenu /><AppearanceMenu /></> : undefined}
      edgeFrom="lg"
    >
      {/* Out of the flow rather than unmounted when they do not fit, so they can still be measured
          - which is how the bar knows when they fit again. `inert` takes them out of the tab order
          and the accessibility tree while they are hidden. */}
      <div
        ref={navRef}
        className={cn(
          'flex items-center gap-3 sm:gap-6',
          !navFits && 'absolute invisible pointer-events-none',
        )}
        {...(navFits ? {} : { inert: '' })}
      >
        {visibleNavItems.map(item => {
          // Not gated on the word being the current page: useWorkingAgents already leaves out the
          // conversation on screen, and other conversations run on while you are in Chat.
          const working = workingAgentFor(workingAgents, item.to)
          return (
            <span key={item.to} className="inline-flex items-center gap-2 whitespace-nowrap">
              <NavLink to={item.to} className={() => headerNavLink(item.isActive)}>
                {item.label}
              </NavLink>
              {/* Fades in and out as agents start and finish elsewhere, rather than in one frame. */}
              <Presence show={!!working} collapse={false}>
                {working && <WorkingAgentDot agent={working} />}
              </Presence>
            </span>
          )
        })}
      </div>

      {/* The wrapper div lives INSIDE FeatureGuard, not around it: an empty div is still a flex
          item, so in desktop mode (auth off, guard renders nothing) an outer wrapper would
          silently consume one extra gap between the last nav item and the menu trigger, making
          that gap read as double-width. */}
      <FeatureGuard feature="auth">
        <div className="hidden lg:block">
          {user ? (
            <UserMenu />
          ) : (
            <Button className="animate-fade-in" onClick={() => navigate('/login')}>{t('Sign in')}</Button>
          )}
        </div>
      </FeatureGuard>

      {/* Desktop's menu, and the counterpart to UserMenu above: Settings and Quit both live
          inside it. They used to be loose in this row - Settings as a nav word,
          Quit as a bare power icon - which gave a configuration screen and the one irreversible
          control the same weight as a page you switch to.

          Still desktop-only, for the reason it always was: a server install is stopped by whoever
          operates the host, and ShutdownController is not compiled into proxyma-server at all. */}
      {!FEATURES.auth && <AppMenu />}

      <MobileMenu
        items={visibleNavItems.map(i => ({
          to: i.to,
          label: i.label,
          isActive: i.isActive,
          workingPath: workingAgentFor(workingAgents, i.to)?.path,
        }))}
        account={user ? { email: user.email } : undefined}
        // The words are not on the bar, so the menu's trigger is the only place left to say so.
        agentWorking={!navFits && visibleNavItems.some(i => workingAgentFor(workingAgents, i.to))}
      />
    </AppHeader>
  )
}

function workingAgentFor(agents: WorkingAgent[], navTo: string): WorkingAgent | undefined {
  return agents.find(a => a.navTo === navTo)
}

// A badge beside a nav word: how many agents are working there while you look at something else -
// the kit's WorkingBadge (the blinking dot and the count, a button of its own). A button rather
// than part of the word's link, because it goes somewhere more specific - the most recently
// started of them, not the page's default.
function WorkingAgentDot({ agent }: { agent: WorkingAgent }) {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const label = t('{count} conversation still working - go to the latest|{count} conversations still working - go to the latest', { count: agent.count })
  return <WorkingBadge count={agent.count} label={label} onClick={() => navigate(agent.path)} />
}

// Whether the nav words fit on the bar beside the logo and everything else on it.
//
// Computed from widths, never from whether the row currently overflows: the words are measured
// where they are hidden too (out of the flow, still laid out), so the answer does not depend on
// the state it decides and cannot flicker between the two.
function useFitsBesideBrand(brandRef: RefObject<HTMLElement>, navRef: RefObject<HTMLElement>): boolean {
  const [fits, setFits] = useState(true)
  useLayoutEffect(() => {
    const brand = brandRef.current
    const nav = navRef.current
    // AppHeader's markup: the content column holds the brand and the row the words sit in.
    const row = nav?.parentElement
    const column = row?.parentElement
    if (!brand || !nav || !row || !column) return

    const measure = () => {
      const col = getComputedStyle(column)
      const content = column.clientWidth - parseFloat(col.paddingLeft) - parseFloat(col.paddingRight)
      const colGap = parseFloat(col.columnGap) || 0
      const rowGap = parseFloat(getComputedStyle(row).columnGap) || 0
      let rest = 0
      let others = 0
      for (const child of Array.from(row.children) as HTMLElement[]) {
        if (child === nav) continue
        // A `hidden` sibling (a menu that belongs to the other breakpoint) takes no width and no
        // gap. Tested on `display`, not on a zero width: a displayed flex item takes a gap however
        // narrow it is, which is how AppMenu once put the hamburger two gaps from Sources.
        const style = getComputedStyle(child)
        if (style.display === 'none') continue
        // Margins too: the menu triggers pull themselves left by their glyph's inset (AppMenu,
        // MobileMenu), and that is width the bar gets back.
        rest += child.offsetWidth + (parseFloat(style.marginLeft) || 0) + (parseFloat(style.marginRight) || 0)
        others++
      }
      const needed = brand.offsetWidth + colGap + nav.offsetWidth + rest + rowGap * others
      setFits(needed <= content + 0.5)
    }

    const observer = new ResizeObserver(measure)
    for (const el of [column, brand, nav, ...Array.from(row.children)]) observer.observe(el)
    measure()
    return () => observer.disconnect()
  }, [brandRef, navRef])
  return fits
}
