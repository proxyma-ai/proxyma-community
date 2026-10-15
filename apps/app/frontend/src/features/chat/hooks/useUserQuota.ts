import { useQuery } from '@tanstack/react-query'
import { http } from '@/services/httpClient'
import { keys } from '@/services/queryKeys'
import { FEATURES } from '@/config/features'
import { useAuth } from '@/features/auth/hooks/useAuth'
import type { UserQuotaStatus } from '../types/chat.types'

// Server-mode-only (see FEATURES.auth - desktop has no auth/billing concept, so this query is
// simply disabled there rather than ever hitting a 404). Separate from useConversationUsage:
// quota is per-user-per-cycle, not per-conversation, so it has no reason to refetch on every
// conversation switch - a 30s poll (matching SourcesPage/AdminPage's existing quota-poll
// cadence) keeps it fresh enough for an 80%-threshold indicator without per-turn plumbing.
//
// ALSO GATED ON A RESOLVED USER, which is not the same condition as FEATURES.auth. Chat is open
// to anonymous visitors in server mode (see SecurityConfig's /api/v1/chat/** permitAll and
// ChatController's guest-session handling), and this endpoint is not - so on FEATURES.auth alone
// it fired for every guest, 401ed, and httpClient's interceptor bounced the whole page to /login.
// A guest has no per-user quota to show; they have the guest allowance instead (useGuestChat).
export function useUserQuota() {
  const { user } = useAuth()
  return useQuery({
    queryKey: keys.userQuota(),
    queryFn: () => http.get<UserQuotaStatus>('/api/v1/users/me/quota').then(r => r.data),
    enabled: FEATURES.auth && !!user,
    refetchInterval: 30_000,
  })
}
