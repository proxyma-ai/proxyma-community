import { SidebarAppearanceRow, SidebarLanguageRow } from '@/components/ui/kit'
import { useI18n, useTranslation, LOCALES, LOCALE_NAMES, type Locale } from '@/i18n'
import { useThemeStore } from '@/store/themeStore'
import { useAppearanceNames } from './PreferenceMenuRows'

/**
 * Appearance and language as rows of the sidebar's foot (the maintainer, 2026-10-30: the account
 * menu holds only the account; what is not about the account is in the panel). The rows are the
 * kit's (SidebarChoiceRow, since both consoles take them too); this file is the store and `t()`.
 * Appearance's list also offers the background and the accent color (since 2026-11-09).
 */
export function AppearanceRow({ folded }: { folded: boolean }) {
  const { t } = useTranslation()
  const names = useAppearanceNames()
  const preference = useThemeStore(s => s.preference)
  const setPreference = useThemeStore(s => s.setPreference)
  const backdrop = useThemeStore(s => s.backdrop)
  const setBackdrop = useThemeStore(s => s.setBackdrop)
  const accent = useThemeStore(s => s.accent)
  const setAccent = useThemeStore(s => s.setAccent)
  return (
    <SidebarAppearanceRow
      title={t('Appearance')}
      names={names}
      current={preference}
      onChoose={setPreference}
      folded={folded}
      look={{
        backdropTitle: t('Background'),
        backdropNames: { flat: t('Flat'), gradient: t('Gradient') },
        backdrop,
        onBackdrop: setBackdrop,
        accentTitle: t('Accent color'),
        accentNames: {
          teal: t('Teal'), blue: t('Blue'), violet: t('Violet'), green: t('Green'), amber: t('Amber'), rose: t('Rose'),
        },
        accent,
        onAccent: setAccent,
      }}
    />
  )
}

export function LanguageRow({ folded }: { folded: boolean }) {
  const { t } = useTranslation()
  const { locale, setLocale } = useI18n()
  return (
    <SidebarLanguageRow<Locale>
      title={t('Language')}
      locales={LOCALES}
      current={locale}
      nameOf={l => LOCALE_NAMES[l]}
      onPick={setLocale}
      folded={folded}
    />
  )
}
