import { Coffee, Heart } from 'lucide-react'
import { LinkRow, List, SettingsSection } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { COMMUNITY_GITHUB_SPONSORS_URL, COMMUNITY_KOFI_URL } from '@/config/openSource'

// About's support links, COMMUNITY edition (Desktop and the Home Server): the maintainer's GitHub
// Sponsors and Ko-fi pages, the one income Proxyma Community has (the maintainer, 2026-10-26). The
// addresses are in config/openSource.ts.
//
// Enterprise Server renders nothing here (index.server.tsx): it is paid.
export function SponsorSection() {
  const { t } = useTranslation()
  return (
    <SettingsSection
      title={t('Support Proxyma')}
      caption={t('Proxyma Community is free and built by one developer.')}
      gap="list"
    >
      <List>
        <LinkRow icon={Heart} title={t('Sponsor the developer on GitHub')} href={COMMUNITY_GITHUB_SPONSORS_URL} />
        <LinkRow icon={Coffee} title={t('Buy the developer a coffee on Ko-fi')} href={COMMUNITY_KOFI_URL} />
      </List>
    </SettingsSection>
  )
}
