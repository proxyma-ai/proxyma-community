import { Navigate } from 'react-router-dom'
import { FEATURES } from '@/config/features'

interface Props {
  children: React.ReactNode
}

// Blocks routes that only make sense in server mode - login/setup (desktop has no auth) and
// admin (its content is entirely server-profile-only endpoints today, see AdminPage's SystemTab)
// - redirecting desktop requests to the chat home instead of rendering a dead-end page.
export function RequireServerMode({ children }: Props) {
  if (!FEATURES.auth) return <Navigate to="/" replace />
  return <>{children}</>
}
