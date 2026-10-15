import { useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ServerCrash } from 'lucide-react'
import { http } from '@/services/httpClient'
import { keys } from '@/services/queryKeys'
import { FEATURES } from '@/config/features'
import { formatDateWithZone } from '@/utils/formatters'
import { authApi } from '@/features/auth/services/authApi'
import { useThemeStore } from '@/store/themeStore'
import { useTranslation } from '@/i18n'
import { StatusLayer } from '@/components/ui/StatusLayer'
import { Caption } from '@/components/ui/kit'

interface MaintenanceStatus {
  active: boolean
  reason: string | null
  since: string | null
  expectedBackAt: string | null
}

// Polls a public, unauthenticated endpoint (no role/auth guard) so it blocks every visitor -
// admin or not, logged in or not - the instant a restore starts, not just admin-facing pages.
// Desktop mode has no maintenance mode at all (single-user, no destructive server-wide restore
// path), so the query is disabled entirely there and this renders as a no-op passthrough.
export function MaintenanceGate({ children }: { children: React.ReactNode }) {
  const { t } = useTranslation()
  const wasActiveRef = useRef(false)
  const productName = useThemeStore(s => s.branding.productName)

  const { data } = useQuery({
    queryKey: keys.maintenanceStatus(),
    queryFn: () => http.get<MaintenanceStatus>('/api/v1/maintenance/status').then(r => r.data),
    enabled: FEATURES.auth,
    refetchInterval: 4000,
    retry: false,
  })

  const active = FEATURES.auth && !!data?.active

  // The gate portal replaces `children` entirely while active, so nothing that would normally
  // populate the auth store (Topbar, route guards, ...) ever mounts - a fresh page load (or
  // reload) while maintenance is already on would otherwise never learn who's looking at this
  // screen. Fetched independently here, gated on `active`, so it works even on that cold-load
  // path - this is also the ONLY way a super admin's role is known before deciding whether to
  // bypass the gate below. The backend's MaintenanceModeFilter makes the same SUPER_ADMIN-only
  // decision independently (verifying the access token itself, not trusting this query) - this
  // is purely about what the frontend renders, not a security boundary on its own.
  // refetchInterval (not just enabled-on-mount) matters for the /login bypass below: after a
  // successful login there, the SPA navigates away from /login via client-side routing, which
  // doesn't remount this query - without polling, a freshly-authenticated super admin would
  // stay stuck behind the overlay (stale isSuperAdmin=false) until an unrelated refetch trigger
  // (e.g. window focus) happened to fire.
  const { data: viewer } = useQuery({
    queryKey: keys.me(),
    queryFn: authApi.me,
    enabled: active,
    retry: false,
    refetchInterval: 5000,
  })
  const isSuperAdmin = viewer?.role === 'SUPER_ADMIN'

  // The underlying data may have completely changed while maintenance was active (restore
  // swaps out the whole database + index) - a full reload is the simplest way to guarantee
  // every already-mounted component (not just Tanstack Query's cache) picks that up, rather
  // than trying to enumerate every stale in-memory state a partial invalidation could miss.
  // Only applies to non-super-admins, who were actually held behind the gate below - a super
  // admin's session never left `children` mounted, so there's nothing stale to reload for them.
  useEffect(() => {
    if (active && !isSuperAdmin) {
      wasActiveRef.current = true
    } else if (wasActiveRef.current) {
      wasActiveRef.current = false
      window.location.reload()
    }
  }, [active, isSuperAdmin])

  if (!active) return <>{children}</>

  // A verified super admin bypasses the block entirely - the backend already lets their
  // requests through (MaintenanceModeFilter), so the real app renders and actually works,
  // letting them use/test the live system during maintenance rather than just watch a "disable"
  // button. ADMIN and regular users still see the blocking overlay below with no special
  // controls - they can no longer disable maintenance from here (only from the live app itself,
  // which only a super admin can reach right now).
  // isSuperAdmin is false both for a confirmed non-super-admin AND while `viewer` is still
  // resolving, so the overlay below is also what renders during that brief resolution window -
  // matching the pre-bypass default of not flashing the real app before we know whether to
  // bypass it.
  if (isSuperAdmin) return <>{children}</>

  // A super admin who is logged out (expired session, different browser, ...) has no other way
  // to get past this gate - everything else here blocks unconditionally. Letting /login itself
  // render (instead of the overlay) gives them a path back in: authenticate, then the next
  // render picks up isSuperAdmin=true above and the real app takes over. Non-super-admins can
  // reach the form too, but the backend now refuses to issue them a token during maintenance
  // (MaintenanceAuthPolicy): their login submit returns 503 and LoginPage shows the maintenance
  // notice, so this bypass only unblocks the one route that leads a super admin back through it.
  if (window.location.pathname === '/login') return <>{children}</>

  // The kit's status screen over the bare canvas - no scrim, since nothing behind it is rendered.
  return (
    <StatusLayer
      // No onClose anywhere: nothing behind this works until maintenance ends.
      level={2}
      scrim={false}
      icon={ServerCrash}
      ariaLabel={t('{product} is temporarily unavailable', { product: productName })}
      title={t('{product} is temporarily unavailable', { product: productName })}
    >
      <p>{data?.reason || t('The system is undergoing maintenance. This will only take a moment.')}</p>
      {data?.expectedBackAt && (
        <Caption className="mt-2 animate-fade-in">
          {t('Expected back by {time}', { time: formatDateWithZone(data.expectedBackAt) })}
        </Caption>
      )}
    </StatusLayer>
  )
}
