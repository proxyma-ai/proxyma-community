import { SidebarAppearanceRow, SidebarLanguageRow } from '@/components/ui/kit'
import { useI18n, useTranslation, LOCALES, LOCALE_NAMES, type Locale } from '@/i18n'
import { useThemeStore } from '@/store/themeStore'
import { useAppearanceNames } from './PreferenceMenuRows'

/**
 * Appearance and language as rows of the sidebar's foot (the maintainer, 2026-10-30: the account
 * menu holds only the account; what is not about the account is in the panel). The rows are the
 * kit's (SidebarChoiceRow, since both consoles take them too); this file is the store and `t()`.
 */
export function AppearanceRow({ folded }: { folded: boolean }) {
  const { t } = useTranslation()
  const names = useAppearanceNames()
  const preference = useThemeStore(s => s.preference)
  const setPreference = useThemeStore(s => s.setPreference)
  return <SidebarAppearanceRow title={t('Appearance')} names={names} current={preference} onChoose={setPreference} folded={folded} />
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
