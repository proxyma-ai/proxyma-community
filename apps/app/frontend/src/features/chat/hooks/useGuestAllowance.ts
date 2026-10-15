import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { FEATURES } from '@/config/features'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { guestApi } from '../services/guestApi'

// The free allowance an anonymous visitor has left, for the sign-in banner.
//
// Fires ONLY for a signed-out visitor in server mode - the mirror image of useUserQuota, which
// fires only for a signed-in one. Desktop has no guests at all, and a signed-in user is metered on
// $ spend instead, so in both cases this would be asking a question with no answer.
//
// isLoading is what keeps the banner from flashing at a signed-in user on a cold load: useAuth
// resolves /auth/me asynchronously, so `!user` is briefly true for everyone. Gating on
// `!isLoading` means the query - and therefore the banner - waits for that verdict.
//
// Not polled. The count only changes when this browser sends a turn, and useChat invalidates this
// key when it does, so a poll would ask a question whose answer it already caused.
export function useGuestAllowance() {
  const { user, isLoading } = useAuth()
  const isGuest = FEATURES.auth && !isLoading && !user
  const { data } = useQuery({
    queryKey: keys.guestAllowance(),
    queryFn: guestApi.allowance,
    enabled: isGuest,
    retry: false,
    staleTime: 30_000,
  })
  // MASKED ON THE SAME CONDITION THAT ENABLES THE QUERY, and that is the whole point of returning
  // a narrowed object rather than the query itself. `enabled: false` stops the FETCH, not the
  // cache read: an allowance fetched while the visitor was signed out keeps being handed back
  // afterwards, and GuestBanner's own observer keeps the entry alive for as long as the app is
  // mounted. Signing in is an in-page navigation with no reload and no qc.clear(), so the banner
  // went on telling a signed-in user how many free guest messages they had left - a number that
  // was never about them (see ChatController.chat: a signed-in caller is metered on spend, never
  // on the guest allowance).
  return { data: isGuest ? data : undefined }
}
