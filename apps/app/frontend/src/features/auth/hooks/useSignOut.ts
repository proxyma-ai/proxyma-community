import { useMutation, useQueryClient } from '@tanstack/react-query'
import { authApi } from '../services/authApi'
import { useToastStore } from '@/store/toastStore'
import { apiErrorText } from '@/services/apiError'
import { useTranslation } from '@/i18n'

/**
 * SIGNING OUT, the one way - the account menu, Settings > Account and the consent gate each wrote
 * it, and only the gate said so when it failed.
 *
 * HOME, not /login. Signing out is not a request to sign back in, and in server mode the chat page
 * is open to anonymous visitors anyway - so landing there leaves someone with a working product,
 * while landing on a login form implies they are locked out of one. /login remains the destination
 * for httpClient's SESSION-LOST redirect, which is a different event.
 *
 * A full location assignment rather than navigate(), and the query cache cleared before it, so no
 * cached answer from the session that just ended can be rendered to whoever is at the keyboard next.
 */
export function useSignOut() {
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const { t } = useTranslation()
  return useMutation({
    mutationFn: authApi.logout,
    onSuccess: () => { qc.clear(); window.location.href = '/' },
    onError: (e: unknown) => addToast(apiErrorText(e) ?? t('Failed to sign out.'), 'danger'),
  })
}
