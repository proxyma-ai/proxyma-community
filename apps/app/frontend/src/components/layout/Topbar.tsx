import { NavLink } from 'react-router-dom'
import { Settings } from 'lucide-react'
import { AppHeader, BRAND_LINK, Tooltip, cn, iconButton } from '@/components/ui/kit'
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
      // AS THE SITE'S HEADER (the maintainer, 2026-11-17: "The design of the app on mobile seems different from the landing
      // site" - the app follows the site): no plane and no rule of its own - it stands on the page's sheet (AppShell draws
      // it inside), so header and page are one surface - and the whole lockup, the mark beside the wordmark in the site's
      // proportions (29px beside 20px, `.site-lockup` at its 36px row). It was the mark alone on the bar's darker glass.
      className="border-b-0 !bg-transparent ![backdrop-filter:none] ![-webkit-backdrop-filter:none]"
      brand={(
        <NavLink to="/" className={cn(BRAND_LINK, '[&_.font-brand]:text-[20px] [&_.font-brand]:leading-[1.4]')} aria-label={t('Home')}>
          {/* Its mark turns as every mark does: the kit's `.mark-turn`, which Logo applies. */}
          <Logo size={29} />
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
