import { AppearanceMenu as KitAppearanceMenu } from '@/components/ui/kit'
import { useThemeStore } from '@/store/themeStore'
import { useTranslation } from '@/i18n'
import { useAppearanceNames } from './PreferenceMenuRows'

/**
 * Appearance - System, Light or Dark - as the header's icon menu, wired to this app's theme
 * store.
 *
 * <p>It was `ThemeToggleButton`, a sun/moon flip, until 0.13.0. The control has been a bare icon in
 * the header, a row inside the three menus, and a header icon again as of 2026-09-06 - beside the
 * language control, in the same corner the landing site puts the same pair. It is still that pair;
 * it now opens a panel like its neighbor instead of flipping, because three choices cannot flip.
 *
 * <p>THE CONTROL ITSELF IS THE KIT'S, on the same shell as the language menu beside it. All this
 * file decides is where the preference comes from.
 *
 * <p>Visible at every width: the menu rows that carried this preference below `sm` are gone, so
 * hiding it would leave a phone with no way to change appearance.
 */
export function AppearanceMenu() {
  const preference = useThemeStore(s => s.preference)
  const setPreference = useThemeStore(s => s.setPreference)
  const names = useAppearanceNames()
  const { t } = useTranslation()

  return (
    <KitAppearanceMenu
      current={preference}
      onChoose={setPreference}
      names={names}
      label={t('Appearance')}
    />
  )
}
