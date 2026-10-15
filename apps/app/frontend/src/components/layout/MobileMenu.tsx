import { NavLink } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/features/auth/services/authApi'
import {
  LibraryBig, LogIn, LogOut, MessageSquare, MessageSquarePlus, Settings, ShieldCheck,
  type LucideIcon,
} from 'lucide-react'
import {
  MobileMenu as KitMobileMenu,
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
import { AppearanceMenuRow, LanguageMenuRow } from './PreferenceMenuRows'

// Every item in this menu carries an icon, navigation included. A menu where three entries have
// icons and four do not reads as two lists that happen to share a border, and the un-iconed ones
// sit at a different text offset from the rest.
const NAV_ICONS: Record<string, LucideIcon> = {
  '/':        MessageSquare,
  '/sources': LibraryBig,
  '/admin':   ShieldCheck,
}

interface MobileMenuProps {
  /** `workingPath`: an agent is still working under this destination, and this is where. */
  items: { to: string; label: string; isActive: boolean; workingPath?: string }[]
  /** Server mode only - the signed-in identity. Sign out is owned here, exactly as UserMenu owns
   *  its own: same mutation, same cache clear, same redirect, so the two cannot diverge. */
  account?: { email: string }
  /** An agent is working somewhere the header's words cannot say, because they are off the bar. */
  agentWorking?: boolean
}

/**
 * The header's phone-width menu, wired to this app's stores and router.
 *
 * <p>THE SHELL IS THE KIT'S as of 2026-09-08 - the trigger, the outside-click, Escape, the panel
 * and the row geometry. Only the rows are here, because only the rows are this app's: react-router
 * `NavLink`s, two zustand modal stores and a Quit that exists on
 * desktop alone. The landing site's menu shares none of that and shares all of the rest.
 *
 * <p>Every nav item in the Topbar is `hidden sm:inline`, so below that width the header had a logo,
 * a theme toggle and nothing else - no Sources, no Administration, no Settings. Whichever page you
 * landed on was the only page you could reach. In server mode it was worse than empty: UserMenu's
 * trigger is a `hidden sm:block` span with no icon behind it, so the account button was still
 * there, still focusable, and rendered zero pixels wide.
 *
 * <p>Everything the header offers above sm is in here below it, in the order the header has it:
 * navigation, then Settings, then the account.
 */
export function MobileMenu({ items, account, agentWorking }: MobileMenuProps) {
  const qc = useQueryClient()
  const { t } = useTranslation()

  const logout = useMutation({
    mutationFn: authApi.logout,
    onSuccess: () => { qc.clear(); window.location.href = '/' },
  })
  // A signed-out visitor on a server: no account to show, and one thing to offer in its place.
  const guest = FEATURES.auth && !account

  // The trigger carries the same blinking dot as a nav word (the kit's StatusDot), pinned to its
  // corner. Decoration only - the row inside is what goes back to the agent.
  return (
    // The negative margin pulls the trigger's 36px (44px on glass) box left by its own inset, so
    // the GLYPH is one row gap from the last nav word, as the words are from each other:
    // (36 - 18) / 2 = 9px of box plus 2.25px of the glyph's side bearing is `-ml-3`, and on glass
    // (44 - 18) / 2 + 2.25 = 15.25px is `-ml-4` - but only from `sm`, where the row gap is 24px.
    // Below `sm` it is 12px, and pulling 16px would lay the trigger's target over the end of the
    // last word; `-ml-3` there stops exactly at it. Topbar's fit measurement counts margins.
    <div className="relative lg:hidden -ml-3 sm:touch:-ml-4">
      <KitMobileMenu label={t('Menu')}>
        {close => (
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
                  <LogIn size={15} /> {t('Sign in')}
                </NavLink>
              </MenuGroup>
            )}

            <MenuGroup>
              {items.map(item => {
                const NavIcon = NAV_ICONS[item.to] ?? MessageSquare
                return (
                  <NavLink
                    key={item.to}
                    // Straight back to the working agent, when there is one - more specific than the
                    // destination's own page.
                    to={item.workingPath ?? item.to}
                    onClick={close}
                    className={menuRow(item.isActive ? 'active' : 'default')}
                  >
                    <NavIcon size={15} /> {item.label}
                    <Presence show={!!item.workingPath} collapse={false} className="ml-auto flex">
                      <StatusDot label={t('An agent is still working - go back to it')} />
                    </Presence>
                  </NavLink>
                )
              })}
            </MenuGroup>

            <MenuGroup>
              {/* Not for a guest: AppShell mounts the Settings window only for someone signed in,
                  so the row opened nothing. */}
              {!guest && (
                <button
                  onClick={() => { close(); useSettingsModalStore.getState().open() }}
                  className={menuRow()}
                >
                  <Settings size={15} /> {t('Settings')}
                </button>
              )}

              {/* No settings-tab shortcuts here since 2026-09-30 (F26): they pushed Sign out below
                  the fold on a phone, and Settings above opens the same tabs. */}
              {/* Both editions. It was server-only, on the reasoning that desktop had no admin
                  surface to review submissions - but submissions go to Proxyma from either edition
                  (see FeedbackButton), and desktop's header menu carries this row too. */}
              <button
                onClick={() => { close(); useFeedbackModalStore.getState().open() }}
                className={menuRow()}
              >
                <MessageSquarePlus size={15} /> {t('Send feedback')}
              </button>
            </MenuGroup>

            {/* Appearance and language. In EVERY header menu since 2026-09-14 - see
                PreferenceMenuRows, which is where the rows live now so the three menus share them. */}
            <MenuGroup>
              <AppearanceMenuRow />
            </MenuGroup>

            <MenuGroup>
              <LanguageMenuRow onChosen={close} />
            </MenuGroup>

            {account && (
              <MenuGroup>
                <button onClick={() => { close(); logout.mutate() }} className={menuRow('danger')}>
                  <LogOut size={15} /> {t('Sign out')}
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
        )}
      </KitMobileMenu>
      <Presence show={!!agentWorking} collapse={false} className="pointer-events-none absolute right-1 top-1 flex">
        <StatusDot />
      </Presence>
    </div>
  )
}
