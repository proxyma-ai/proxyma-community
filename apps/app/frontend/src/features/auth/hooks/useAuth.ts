import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { authApi } from '../services/authApi'
import { useAuthStore } from '@/store/authStore'
import { FEATURES } from '@/config/features'
import { useEffect } from 'react'
import { retrySessionProbe, sessionProbeDelay } from '../utils/sessionProbeRetry'

export function useAuth() {
  const setUser = useAuthStore(s => s.setUser)
  const { data, isLoading } = useQuery({
    queryKey: keys.me(),
    queryFn: authApi.me,
    // A 429 or a restarting server is "not now", not "nobody is signed in": asked again, after
    // Retry-After, while the boot screen stays up - see sessionProbeRetry.
    retry: retrySessionProbe,
    retryDelay: sessionProbeDelay,
    // Desktop mode has no auth at all - never fire /auth/me there, just report "no user".
    enabled: FEATURES.auth,
  })
  useEffect(() => { setUser(data ?? null) }, [data, setUser])
  return { user: data, isLoading }
}
