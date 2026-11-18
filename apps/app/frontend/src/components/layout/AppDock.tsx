import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { MoreHorizontal } from 'lucide-react'
import { DockButton, DockTabs, Popover } from '@/components/ui/kit'
import { useAuthStore } from '@/store/authStore'
import { useWorkingAgents } from '@/hooks/useWorkingAgents'
import { useTranslation } from '@/i18n'
import { INSIDE_PAGE, useNavItems } from './navItems'
import { PhoneMenuRows } from './PhoneMenuRows'

/**
 * THREE PAGES AND MORE, four equal entries (the maintainer: "Dock items should have the same width"): five equal shares
 * of a 375px phone are 75px, narrower than "Constellations", "Key accounts", "Administration" or the Vietnamese for Sources
 * at 12px; four are 94px, which every name fits. The pages past the third are in More's menu.
 */
const TABS = 3
/** The sign-in, setup and password pages: a form, with nothing to go to until it is done (the mobile audit). */
const AUTH_PAGES = ['/login', '/setup', '/forgot-password', '/reset-password']
/** The value that marks More as the place on screen: a page that is in its menu. */
const MORE = '__more'


/**
 * Whether the on-screen keyboard is up: the visible part of the window is shorter than the window by more than a
 * keyboard's height. Not a field's focus - the chat focuses its composer as a page opens, and a phone opens no keyboard
 * for a focus it did not tap, so the dock went away on every chat page with nothing in its place.
 */
function useKeyboardUp(): boolean {
  const [up, setUp] = useState(false)
  useEffect(() => {
    const vv = window.visualViewport
    if (!vv) return
    // A field holds the focus AND the visible part is a keyboard's height shorter - either alone is not a keyboard: a
    // composer focused as the page opens opens none, and a window's own chrome (a test browser's device frame) can
    // differ from its layout viewport with nothing typed.
    const editing = () => {
      const el = document.activeElement
      return el instanceof HTMLElement && (el.isContentEditable || el.tagName === 'TEXTAREA'
        || (el.tagName === 'INPUT' && !['checkbox', 'radio', 'button', 'range'].includes((el as HTMLInputElement).type)))
    }
    const check = () => setUp(editing() && vv.scale <= 1.01 && window.innerHeight - vv.height > 150)
    check()
    vv.addEventListener('resize', check)
    document.addEventListener('focusin', check)
    document.addEventListener('focusout', check)
    return () => {
      vv.removeEventListener('resize', check)
      document.removeEventListener('focusin', check)
      document.removeEventListener('focusout', check)
    }
  }, [])
  return up
}

/**
 * THE APP'S PAGES AS A DOCK AT THE FOOT OF A PHONE (2026-11-16, the maintainer: "remove the hamburger menu and use the
 * bottom dock as the landing site", then "redesign the layout ... for mobile"): the first three of Chat, Sources and the
 * add-ons' pages as tabs, and MORE opening the rest of what the hamburger held (PhoneMenuRows):
 * the pages without a tab, Settings, Send feedback, the appearance and the language, Sign out or Quit. A page where an
 * agent works while the reader is elsewhere carries the blinking dot, and the tab goes straight back to it.
 *
 * It leaves with the header as the reader moves on down a page (the kit's DockTabs), steps aside while the keyboard is
 * up, and is not drawn on a page inside another (INSIDE_PAGE). Drawn by AppShell where there is no frame - a browser below
 * `md`.
 */
export function AppDock() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const user = useAuthStore(s => s.user)
  const items = useNavItems()
  const working = useWorkingAgents()
  const keyboard = useKeyboardUp()
  // A page inside another has its own dock (navItems' INSIDE_PAGE); the sign-in pages need none.
  if (INSIDE_PAGE.test(location.pathname) || AUTH_PAGES.includes(location.pathname)) return null

  const workingFor = (to: string) => working.find(a => a.navTo === to)
  const tabs = items.slice(0, TABS)
  const rest = items.slice(tabs.length)
  const current = items.find(i => i.isActive)
  const value = current && tabs.includes(current) ? current.to : rest.some(i => i.isActive) ? MORE : ''

  return (
    <DockTabs
      ariaLabel={t('Pages')}
      workingLabel={t('An agent is still working - go back to it')}
      value={value}
      away={keyboard}
      tabs={tabs.map(i => ({ value: i.to, label: i.short, icon: i.icon, working: !!workingFor(i.to) && !i.isActive }))}
      onChange={to => navigate(workingFor(to)?.path ?? to)}
      after={(
        <Popover
          placement="top-end"
          widthClass="w-64"
          // AS TALL AS ITS ROWS, up to all the room above the dock (2026-11-17, the maintainer: "The More menu I have to scroll
          // it a little bit"): the popover's 24rem cap left its last row under the fold with half the screen free above.
          panelClassName="max-h-[var(--float-room,32rem)]"
          ariaLabel={t('More')}
          trigger={({ open, toggle, ref }) => (
            <DockButton ref={ref} icon={MoreHorizontal} label={t('More')} on={value === MORE || open} menu={{ open }}
              working={rest.some(i => !!workingFor(i.to) && !i.isActive)} workingLabel={t('An agent is still working - go back to it')}
              onClick={toggle} />
          )}
        >
          {close => (
            <PhoneMenuRows
              close={close}
              account={user ? { email: user.email } : undefined}
              items={rest.map(i => ({ to: i.to, label: i.label, icon: i.icon, isActive: i.isActive, workingPath: workingFor(i.to)?.path }))}
            />
          )}
        </Popover>
      )}
    />
  )
}
