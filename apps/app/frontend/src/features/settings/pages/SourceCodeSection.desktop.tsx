import { useQuery } from '@tanstack/react-query'
import { Code2, Scale } from 'lucide-react'
import { LinkRow, List, SettingsSection } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { configApi } from '@/services/configApi'
import { COMMUNITY_LICENSE_URL, communitySource } from '@/config/openSource'

// About's source-code offer, COMMUNITY edition (Desktop and the Home Server). Proxyma Community is
// AGPL-3.0-only, and section 13 of that license has a program its users reach over a network offer
// them its source - the Home Server is reached that way, so the offer is made here, where every
// user of it can find it, beside the edition and version it describes. Both addresses are in
// config/openSource.ts, which says what someone running a MODIFIED copy has to change.
//
// EVERY SENTENCE HERE HAS TO BE TRUE OF THE BUILD SHOWING IT (release review 2026-10-15, W14). It
// said "Its complete source code is public" and linked the repository's root - which is a release
// export that moves on, and which never carries a development build's source. So a release links
// its own tag, and a development build says its own source is not published (communitySource).
//
// Enterprise Server renders nothing here (index.server.tsx): it is not open source.
export function SourceCodeSection() {
  const { t } = useTranslation()
  // The ['config'] query AboutTab and ThemeProvider already hold, so this issues no request.
  const { data: config } = useQuery({ queryKey: ['config'], queryFn: configApi.get, staleTime: Infinity, retry: false })
  const source = communitySource(config?.version)
  const caption = source.kind === 'release'
    ? t('Proxyma Community is free software under the GNU Affero General Public License v3. The source code of this version is public.')
    : source.kind === 'development'
      ? t('Proxyma Community is free software under the GNU Affero General Public License v3. This development build is not published; each release is.')
      : t('Proxyma Community is free software under the GNU Affero General Public License v3. The source code of each release is public.')
  return (
    <SettingsSection title={t('Source code')} caption={caption} gap="list">
      <List>
        <LinkRow
          icon={Code2}
          title={source.kind === 'release'
            ? t('Proxyma Community {version} on GitHub', { version: config?.version ?? '' })
            : t('Proxyma Community on GitHub')}
          description={source.kind === 'release'
            ? t('The source code of this version, to read, build or change.')
            : t('The source code of each release, to read, build or change.')}
          href={source.url}
        />
        <LinkRow
          icon={Scale}
          title={t('GNU Affero General Public License v3')}
          description={t('The license this edition is under, and what it lets you do.')}
          href={COMMUNITY_LICENSE_URL}
        />
      </List>
    </SettingsSection>
  )
}
