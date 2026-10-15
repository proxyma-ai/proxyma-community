import { useSignOut } from '../hooks/useSignOut'
import { useAuth } from '../hooks/useAuth'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import { useFeedbackModalStore } from '@/store/feedbackModalStore'
import { LogOut, MessageSquarePlus, Settings } from 'lucide-react'
import {
  UserMenu as KitUserMenu,
  MenuGroup,
  MenuIdentity,
  menuRow,
} from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { AppearanceMenuRow, LanguageMenuRow } from '@/components/layout/PreferenceMenuRows'

/**
 * The header's account menu, wired to this app's auth and stores.
 *
 * <p>THE SHELL IS THE KIT'S as of 2026-09-08 - the trigger, the outside-click, Escape, the panel
 * and the row geometry. Both operator consoles carried a copy of this file, each opening with a
 * comment that said "Port of the app's UserMenu"; there is one now, and only the rows below are
 * this app's.
 *
 * <p>NO HAMBURGER on the trigger, unlike the consoles: `Topbar` renders this inside a
 * `hidden sm:block`, so below that width `MobileMenu` is the phone menu and this does not exist.
 * The consoles have no separate phone menu and pass `withMenuIcon` for that reason.
 */
export function UserMenu() {
  const { user } = useAuth()
  const { t } = useTranslation()

  // The one sign-out (useSignOut): home, not /login, with the cache cleared first - and it says
  // so when it fails, which this one did not.
  const logout = useSignOut()

  if (!user) return null

  return (
    // The onboarding tour highlights this menu for its feedback step. It used to point at the
    // feedback button itself, which now lives inside the menu - and a tour cannot highlight an
    // element that is not rendered until the menu opens. On the wrapper rather than the trigger
    // because the trigger is the kit's now, and TourOverlay resolves the attribute with a plain
    // querySelector, which is how every other tour target in this app is written.
    <div data-tour-id="tour-account-menu">
      {/* w-64, wider than the kit's 14rem: the preference rows wrapped onto two lines at 14rem,
          Vietnamese most of all, and a row that wraps reads as two rows. */}
      <KitUserMenu label={t('Account menu')} name={user.displayName || user.email} panelClassName="w-64">
        {close => (
          <>
            <MenuIdentity>{user.email}</MenuIdentity>

            <MenuGroup>
              <button
                onClick={() => { close(); useSettingsModalStore.getState().open() }}
                className={menuRow()}
              >
                <Settings size={15} className="shrink-0" /> {t('Settings')}
              </button>
            </MenuGroup>

            {/* NO SETTINGS-TAB SHORTCUTS, since 2026-09-30 (F26 of that day's review, approved by
                the maintainer): seven of them made this menu thirteen rows long - a second settings
                rail, in a worse container - and each was one click from the Settings row above. */}

            {/* Was a floating button pinned to the bottom-left of the viewport, which on a phone or
                tablet sat permanently on top of the page content. It belongs with the other
                account-level action rather than over the composer. */}
            <MenuGroup>
              <button
                onClick={() => { close(); useFeedbackModalStore.getState().open() }}
                className={menuRow()}
              >
                <MessageSquarePlus size={15} className="shrink-0" /> {t('Send feedback')}
              </button>
            </MenuGroup>

            {/* Appearance and language, here rather than on the header bar since 2026-09-14. A
                signed-out visitor never sees this menu - the header shows Sign In instead - so
                Topbar keeps the two header controls for exactly that case. */}
            <MenuGroup>
              <AppearanceMenuRow />
            </MenuGroup>
            <MenuGroup>
              <LanguageMenuRow onChosen={close} />
            </MenuGroup>

            <MenuGroup>
              <button onClick={() => logout.mutate()} className={menuRow('danger')}>
                <LogOut size={15} className="shrink-0" /> {t('Sign out')}
              </button>
            </MenuGroup>
          </>
        )}
      </KitUserMenu>
    </div>
  )
}
