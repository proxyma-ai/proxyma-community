import { NavLink } from 'react-router-dom'
import { Settings } from 'lucide-react'
import { AppHeader, BRAND_LINK, Tooltip, iconButton } from '@/components/ui/kit'
import { Logo } from '@/components/ui/Logo'
import { FEATURES } from '@/config/features'
import { useTranslation } from '@/i18n'
import { useAuthStore } from '@/store/authStore'
import { useSettingsModalStore } from '@/store/settingsModalStore'

/**
 * THE PHONE'S HEADER: the logo home and the Settings gear (2026-11-16, the maintainer: "remove the hamburger menu and use
 * the bottom dock as the landing site", then the gear). The pages, the appearance, the language, the account and Quit
 * are the dock's at the screen's foot (AppDock, its More holding what the hamburger held). AppShell draws it only where there is no frame: a
 * browser below `md`. From `md` the frame's sidebar carries all of it.
 *
 * Until then the bar carried the page words that fitted beside the logo (measured), the hamburger, the desktop's menu
 * from `sm` and the account from `lg` - widths at which this header is no longer drawn since the frame took `md`.
 */
export function Topbar() {
  const { t } = useTranslation()
  const user = useAuthStore(s => s.user)
  // SETTINGS AT THE TOP RIGHT (2026-11-16, the maintainer: "there is no way to access settings. I think we could have a
  // gear icon top right"): the one control the bar carries besides the logo, on every page - the dock's More holds it
  // too, but a page inside another (a channel, a project) has no app dock. Not for a guest: AppShell mounts the
  // Settings window only for someone signed in.
  const canOpen = !FEATURES.auth || !!user
  return (
    <AppHeader
      // No rule under it: with the page's large title right below, bar and title read as one header, as a phone's
      // large-title bar does (the mobile audit, 2026-11-16).
      className="border-b-0"
      brand={(
        <NavLink to="/" className={BRAND_LINK} aria-label={t('Home')}>
          {/* THE MARK ALONE (the mobile audit): the page's own large title names the page; the brand is the chat home's
              to show, once. Its mark turns as every mark does: the kit's `.mark-turn`, which Logo applies. */}
          <Logo markOnly />
        </NavLink>
      )}
    >
      {canOpen && (
        <Tooltip label={t('Settings')}>
          <button type="button" aria-label={t('Settings')} className={iconButton('lg')}
            onClick={() => useSettingsModalStore.getState().open()}>
            <Settings size={18} aria-hidden="true" />
          </button>
        </Tooltip>
      )}
    </AppHeader>
  )
}
