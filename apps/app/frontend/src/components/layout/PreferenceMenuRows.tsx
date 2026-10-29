import {
  AppearanceMenuRow as KitAppearanceMenuRow,
  LanguageMenuRow as KitLanguageMenuRow,
  type AppearancePreference,
} from '@/components/ui/kit'
import { useTranslation, useI18n, LOCALES, LOCALE_NAMES } from '@/i18n'
import { useThemeStore } from '@/store/themeStore'

/**
 * Appearance and language, as MENU ROWS - shared by every menu that carries them: MobileMenu below
 * `lg`, and AppMenu (desktop) and UserMenu (server) above it. Written once in MobileMenu until
 * 2026-09-14, when the maintainer moved both off the header bar into the account menu at every
 * width, and three copies of the same two groups would have been three places to disagree.
 *
 * Both are the same shape since 0.13.0 - one row showing the current value, opening a submenu of
 * every choice. The ROWS ARE THE KIT'S since the 2026-10 rebuild (`AppearanceMenuRow`,
 * `LanguageMenuRow`, on its `SubmenuRow`), shared with the site and both consoles; what is left here
 * is the adapter - this app's stores and its words.
 *
 * The caller wraps each in its own kit MenuGroup - all three menus are on the kit's shell since the
 * 2026-10 rebuild.
 */

/**
 * The names each appearance goes by in every menu, translated at the call site. See the kit's
 * appearance.ts for what each resolves to.
 */
export function useAppearanceNames(): Record<AppearancePreference, string> {
  const { t } = useTranslation()
  return { system: t('System'), light: t('Light'), dark: t('Dark') }
}

/**
 * Appearance - System, Light or Dark - as one row opening a submenu: the kit's
 * `AppearanceMenuRow`, fed this app's theme store and its words.
 *
 * <p>NEITHER THE SUBMENU NOR THE MENU CLOSES ON A CHOICE. Appearance is something people flip
 * between to compare, and the page behind the menu is the comparison. A press outside closes both.
 */
export function AppearanceMenuRow({ className }: { className?: string }) {
  const { t } = useTranslation()
  const names = useAppearanceNames()
  const preference = useThemeStore(s => s.preference)
  const setPreference = useThemeStore(s => s.setPreference)

  return (
    <KitAppearanceMenuRow
      title={t('Appearance')}
      names={names}
      current={preference}
      onChoose={setPreference}
      className={className}
    />
  )
}

/**
 * Language, as one row opening a submenu of every language: the kit's `LanguageMenuRow`, fed this
 * app's i18n store. BUTTONS, not anchors - the locale here is client state with nothing to link to.
 *
 * <p>Choosing one closes the submenu AND the menu (`onChosen`): a language change re-renders every
 * string on the page, and the menu itself is one of them.
 */
export function LanguageMenuRow({ onChosen, className }: { onChosen: () => void; className?: string }) {
  const { t } = useTranslation()
  const { locale, setLocale } = useI18n()

  return (
    <KitLanguageMenuRow
      title={t('Language')}
      locales={LOCALES}
      current={locale}
      nameOf={l => LOCALE_NAMES[l]}
      onPick={l => { setLocale(l); onChosen() }}
      className={className}
    />
  )
}
