import { Palette, PanelsTopLeft } from 'lucide-react'
import {
  ACCENT_PREFERENCES,
  ACCENT_SWATCH,
  AppearanceMenuRow as KitAppearanceMenuRow,
  BACKDROP_PREFERENCES,
  LanguageMenuRow as KitLanguageMenuRow,
  SubmenuRow,
  type AccentPreference,
  type AppearancePreference,
  type BackdropPreference,
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
 * The BACKGROUND and the ACCENT COLOR beside the appearance, as rows of the same menus (since
 * 2026-11-09 - the sidebar's Appearance list offers them from `lg`, and a phone has the menu instead,
 * so these are how a phone reaches them). Neither closes on a choice, for the same reason Appearance
 * does not: the page behind is the comparison.
 */
export function BackdropMenuRow({ className }: { className?: string }) {
  const { t } = useTranslation()
  const backdrop = useThemeStore(s => s.backdrop)
  const setBackdrop = useThemeStore(s => s.setBackdrop)
  const names: Record<BackdropPreference, string> = { flat: t('Flat'), gradient: t('Gradient') }
  return (
    <SubmenuRow<BackdropPreference>
      icon={<PanelsTopLeft size={15} className="shrink-0" aria-hidden="true" />}
      title={t('Background')}
      value={names[backdrop]}
      valueText={names[backdrop]}
      options={BACKDROP_PREFERENCES.map(b => ({ key: b, label: names[b] }))}
      current={backdrop}
      onPick={setBackdrop}
      closeOnPick={false}
      className={className}
    />
  )
}

export function AccentMenuRow({ className }: { className?: string }) {
  const { t } = useTranslation()
  const accent = useThemeStore(s => s.accent)
  const setAccent = useThemeStore(s => s.setAccent)
  const names: Record<AccentPreference, string> = {
    teal: t('Teal'), blue: t('Blue'), violet: t('Violet'), green: t('Green'), amber: t('Amber'), rose: t('Rose'),
  }
  const swatch = (a: AccentPreference) => (
    <span aria-hidden="true" className="h-[15px] w-[15px] shrink-0 rounded-[4px]" style={{ backgroundColor: ACCENT_SWATCH[a] }} />
  )
  return (
    <SubmenuRow<AccentPreference>
      icon={<Palette size={15} className="shrink-0" aria-hidden="true" />}
      title={t('Accent color')}
      value={names[accent]}
      valueText={names[accent]}
      options={ACCENT_PREFERENCES.map(a => ({ key: a, label: names[a], icon: swatch(a) }))}
      current={accent}
      onPick={setAccent}
      closeOnPick={false}
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
