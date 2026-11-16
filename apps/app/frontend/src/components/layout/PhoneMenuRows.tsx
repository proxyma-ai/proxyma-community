import { NavLink } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/features/auth/services/authApi'
import {
  LibraryBig, LogIn, LogOut, MessageSquare, MessageSquarePlus, Settings, ShieldCheck,
  type LucideIcon,
} from 'lucide-react'
import {
  MenuGroup,
  MenuIdentity,
  Presence,
  StatusDot,
  menuRow,
} from '@/components/ui/kit'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import { useFeedbackModalStore } from '@/store/feedbackModalStore'
import { QuitButton } from '@/features/system/components/QuitButton'
import { FEATURES } from '@/config/features'
import { useTranslation } from '@/i18n'
import { AccentMenuRow, AppearanceMenuRow, BackdropMenuRow, LanguageMenuRow } from './PreferenceMenuRows'

// Every item in this menu carries an icon, navigation included. A menu where three entries have
// icons and four do not reads as two lists that happen to share a border, and the un-iconed ones
// sit at a different text offset from the rest.
const NAV_ICONS: Record<string, LucideIcon> = {
  '/':        MessageSquare,
  '/sources': LibraryBig,
  '/admin':   ShieldCheck,
}

interface PhoneMenuRowsProps {
  /** Pages the dock had no room for; `workingPath`: an agent is still working under one, and this is where. */
  items: { to: string; label: string; isActive: boolean; workingPath?: string; icon?: LucideIcon }[]
  /** Server mode only - the signed-in identity. Sign out is owned here, exactly as UserMenu owns
   *  its own: same mutation, same cache clear, same redirect, so the two cannot diverge. */
  account?: { email: string }
  close: () => void
}

/**
 * THE PHONE'S MENU, the rows of the app dock's MORE (AppDock, since 2026-11-16 - the maintainer asked for the bottom dock
 * in place of the hamburger, "as the landing site"): the pages the dock has no room for, Settings, Send feedback, the
 * appearance and the language, then Sign out or Quit. It was the hamburger's panel in the header (the kit's MobileMenu)
 * until then, and the rows are the same.
 *
 * <p>Every nav item in the Topbar is `hidden sm:inline`, so below that width the header had a logo,
 * a theme toggle and nothing else - no Sources, no Administration, no Settings. Whichever page you
 * landed on was the only page you could reach; a phone's menu is where everything else is.
 */
export function PhoneMenuRows({ items, account, close }: PhoneMenuRowsProps) {
  const qc = useQueryClient()
  const { t } = useTranslation()

  const logout = useMutation({
    mutationFn: authApi.logout,
    onSuccess: () => { qc.clear(); window.location.href = '/' },
  })
  // A signed-out visitor on a server: no account to show, and one thing to offer in its place.
  const guest = FEATURES.auth && !account

  return (
          <>
            {account && <MenuIdentity>{account.email}</MenuIdentity>}

            {/* Sign in, where the identity sits for someone who has one. The header's own Sign in
                button is `lg` and up, so below it this row is the only standing way in: the guest
                banner offers it too, and stays closed for the session once dismissed - which left
                a phone visitor to the Enterprise sandbox with no way to sign in at all
                (maintainer's report, 2026-10-02). */}
            {guest && (
              <MenuGroup>
                <NavLink to="/login" onClick={close} className={menuRow()}>
                  <LogIn size={15} className="shrink-0" aria-hidden="true" /> {t('Sign in')}
                </NavLink>
              </MenuGroup>
            )}

            {items.length > 0 && <MenuGroup>
              {items.map(item => {
                const NavIcon = item.icon ?? NAV_ICONS[item.to] ?? MessageSquare
                return (
                  <NavLink
                    key={item.to}
                    // Straight back to the working agent, when there is one - more specific than the
                    // destination's own page.
                    to={item.workingPath ?? item.to}
                    onClick={close}
                    className={menuRow(item.isActive ? 'active' : 'default')}
                  >
                    <NavIcon size={15} className="shrink-0" aria-hidden="true" /> {item.label}
                    <Presence show={!!item.workingPath} collapse={false} className="ml-auto flex">
                      <StatusDot label={t('An agent is still working - go back to it')} />
                    </Presence>
                  </NavLink>
                )
              })}
            </MenuGroup>}

            <MenuGroup>
              {/* Not for a guest: AppShell mounts the Settings window only for someone signed in,
                  so the row opened nothing. */}
              {!guest && (
                <button
                  type="button"
                  onClick={() => { close(); useSettingsModalStore.getState().open() }}
                  className={menuRow()}
                >
                  <Settings size={15} className="shrink-0" aria-hidden="true" /> {t('Settings')}
                </button>
              )}

              {/* No settings-tab shortcuts here since 2026-09-30 (F26): they pushed Sign out below
                  the fold on a phone, and Settings above opens the same tabs. */}
              {/* Both editions. It was server-only, on the reasoning that desktop had no admin
                  surface to review submissions - but submissions go to Proxyma from either edition
                  (see FeedbackButton), and desktop's header menu carries this row too. */}
              <button
                type="button"
                onClick={() => { close(); useFeedbackModalStore.getState().open() }}
                className={menuRow()}
              >
                <MessageSquarePlus size={15} className="shrink-0" aria-hidden="true" /> {t('Send feedback')}
              </button>
            </MenuGroup>

            {/* Appearance and language. In EVERY header menu since 2026-09-14 - see
                PreferenceMenuRows, which is where the rows live now so the three menus share them. */}
            <MenuGroup>
              <AppearanceMenuRow />
              <BackdropMenuRow />
              <AccentMenuRow />
            </MenuGroup>

            <MenuGroup>
              <LanguageMenuRow onChosen={close} />
            </MenuGroup>

            {account && (
              <MenuGroup>
                <button type="button" onClick={() => { close(); logout.mutate() }} className={menuRow('danger')}>
                  <LogOut size={15} className="shrink-0" aria-hidden="true" /> {t('Sign out')}
                </button>
              </MenuGroup>
            )}

            {/* Desktop's Quit, in the position Sign out holds on server - and it has to be here,
                because Quit moved off the header row into AppMenu, which is `hidden sm:flex`.
                Without this line a desktop user on a phone-width window would have no way to stop
                Proxyma at all: the tray icon is unsupported under GNOME and Wayland, which is why
                this control exists in the first place. */}
            {!FEATURES.auth && (
              <MenuGroup>
                <QuitButton onOpenConfirm={close} />
              </MenuGroup>
            )}
          </>
  )
}
