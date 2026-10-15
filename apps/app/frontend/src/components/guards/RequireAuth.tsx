import { Navigate, useLocation } from 'react-router-dom'
import { BootScreen } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { FEATURES } from '@/config/features'

interface Props {
  children: React.ReactNode
}

export function RequireAuth({ children }: Props) {
  const { user, isLoading } = useAuth()
  const { t } = useTranslation()
  const location = useLocation()

  if (!FEATURES.auth) return <>{children}</>

  if (isLoading) {
    // The kit's boot screen - the same one SetupGate shows, in the box the route gives it.
    return <BootScreen label={t('Loading...')} />
  }

  // ?next= carries the page that was actually asked for, so signing in returns there rather than
  // dropping the user on chat. Matters most for a link someone was SENT (a /shared/{token}
  // conversation), where landing on the app root loses the thing they clicked. LoginPage only
  // honors same-origin relative paths - see its `next` derivation.
  if (!user) {
    const next = `${location.pathname}${location.search}`
    return <Navigate to={`/login?next=${encodeURIComponent(next)}`} replace />
  }

  return <>{children}</>
}
