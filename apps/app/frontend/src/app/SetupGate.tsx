import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { FEATURES } from '@/config/features'
import { authApi } from '@/features/auth/services/authApi'
import { BootScreen } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'

// Authoritative first-run-setup gate. Until the server confirms an explicit needed:false, NOTHING
// past this point renders - no route, no page. It fails CLOSED: any error, empty/garbled body, a
// 429, or an in-flight request is treated as "not yet confirmed" and keeps the app blocked (and
// retrying) - it is NEVER interpreted as "setup complete". Setup must be finished before anything
// else can run.
//
// This is the ONLY place that routes for setup. AppShell / LoginPage / SetupPage deliberately do
// NOT run their own setup redirects anymore, so there is nothing for this gate to ping-pong
// against. An earlier implementation let multiple components (an axios interceptor + SetupPage)
// each redirect on the pre-setup 503 storm, which produced an infinite / <-> /setup full-reload
// loop that also tripped the server rate limiter - see ../proxyma-docs/LESSONS_LEARNED.md.
export function SetupGate({ children }: { children: React.ReactNode }) {
  // Desktop builds have no auth and no /api/v1/setup/status endpoint - passthrough, no query at all.
  if (!FEATURES.auth) return <>{children}</>
  return <ServerSetupGate>{children}</ServerSetupGate>
}

function ServerSetupGate({ children }: { children: React.ReactNode }) {
  const { pathname } = useLocation()
  const navigate = useNavigate()

  // Cached for the session (staleTime Infinity) so a confirmed-ready deployment pays exactly one
  // request per page load. Retries with capped backoff on transient failure so a backend that is
  // briefly down (e.g. mid-restart after a reset) resolves on its own rather than being read as
  // "not needed".
  const { data, isSuccess } = useQuery({
    queryKey: ['setup-status'],
    queryFn: authApi.setupStatus,
    retry: true,
    retryDelay: attempt => Math.min(1000 * 2 ** attempt, 10000),
    refetchOnWindowFocus: false,
    staleTime: Infinity,
    gcTime: Infinity,
  })

  // Only an explicit boolean counts. `undefined` (still loading / errored / empty body) is neither.
  const ready = isSuccess && data?.needed === false
  const needed = isSuccess && data?.needed === true

  useEffect(() => {
    if (needed && pathname !== '/setup') navigate('/setup', { replace: true })
    else if (ready && pathname === '/setup') navigate('/', { replace: true })
  }, [needed, ready, pathname, navigate])

  // Confirmed complete: run the app - but never leave the user on the setup form once it is done
  // (the effect above bounces them off /setup first).
  if (ready) return pathname === '/setup' ? <BootBlocker /> : <>{children}</>

  // Setup required: only /setup (the create-super-admin form) may render; everything else waits
  // for the redirect above to land.
  if (needed) return pathname === '/setup' ? <>{children}</> : <BootBlocker />

  // Unknown - still checking, or retrying after an error. Fail closed: block everything.
  return <BootBlocker />
}

// The kit's boot screen: the whole window while the app decides what to show.
function BootBlocker() {
  const { t } = useTranslation()
  return <BootScreen label={t('Loading...')} />
}
