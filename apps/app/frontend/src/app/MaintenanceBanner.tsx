import { useQuery } from '@tanstack/react-query'
import { http } from '@/services/httpClient'
import { keys } from '@/services/queryKeys'
import { FEATURES } from '@/config/features'
import { formatDateWithZone } from '@/utils/formatters'
import { useTranslation } from '@/i18n'
import { Banner, Presence } from '@/components/ui/kit'

interface MaintenanceAnnouncement {
  bannerEnabled: boolean
  reason: string | null
  scheduledStart: string | null
  expectedBackAt: string | null
}

// Public, unauthenticated poll (same shape as MaintenanceGate's own status poll) so a heads-up
// about upcoming maintenance reaches every visitor - logged in or not - ahead of the actual
// blocking gate flipping on. Renders in normal document flow (not fixed/absolute) so it pushes
// whatever sits below it down rather than overlapping it; mounted in Providers.tsx above the
// Router so it covers every route, including the standalone /login and /setup pages that render
// outside AppShell/Topbar and therefore have no header of their own to sit above.
export function MaintenanceBanner() {
  const { t } = useTranslation()
  const { data } = useQuery({
    queryKey: keys.maintenanceAnnouncement(),
    queryFn: () => http.get<MaintenanceAnnouncement>('/api/v1/maintenance/announcement').then(r => r.data),
    enabled: FEATURES.auth,
    refetchInterval: 8000,
    retry: false,
  })

  // A Presence in both returns, at the same place in the tree: it eases in and out with the poll.
  if (!FEATURES.auth || !data?.bannerEnabled) return <Presence show={false} />

  const scheduleParts: string[] = []
  if (data.scheduledStart) scheduleParts.push(t('starts {time}', { time: formatDateWithZone(data.scheduledStart) }))
  if (data.expectedBackAt) scheduleParts.push(t('expected back by {time}', { time: formatDateWithZone(data.expectedBackAt) }))
  const schedule = scheduleParts.join(' - ')

  return (
    // The kit's page-top strip in the warning tone, since the 2026-10 rebuild. It was a fixed
    // amber-400 band with near-black text, the one raw palette colour in the app's chrome, kept
    // bright on purpose when --warning was swapped for a muted amber on light tenants; the kit's
    // warning tint and glyph are what every other warning strip uses now.
    <Presence show>
      <Banner tone="warning" title={data.reason || t('Scheduled maintenance is coming up.')}>
        {schedule ? `(${schedule})` : null}
      </Banner>
    </Presence>
  )
}
