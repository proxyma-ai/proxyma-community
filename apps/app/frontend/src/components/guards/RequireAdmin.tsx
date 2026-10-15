import { Navigate } from 'react-router-dom'
import { BootScreen } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { FEATURES } from '@/config/features'

interface Props {
  children: React.ReactNode
}

export function RequireAdmin({ children }: Props) {
  const { user, isLoading } = useAuth()
  const { t } = useTranslation()

  if (!FEATURES.auth) return <>{children}</>

  if (isLoading) {
    // The kit's boot screen - the same one SetupGate shows, in the box the route gives it.
    return <BootScreen label={t('Loading...')} />
  }

  if (!user) return <Navigate to="/login" replace />

  const isAdmin = user.role === 'SUPER_ADMIN' || user.role === 'ADMIN'
  if (!isAdmin) return <Navigate to="/" replace />

  return <>{children}</>
}
