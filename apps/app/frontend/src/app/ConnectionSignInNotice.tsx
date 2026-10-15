import { useEffect, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import {
  connectionSignInCode,
  connectionSignInMessage,
  isConnectionSignInFailure,
  withoutConnectionSignInParam,
} from '@/features/settings/utils/connectionSignIn'
import { useTranslation } from '@/i18n'

/**
 * What a connection's OAuth2 sign-in callback left in the address bar - `/?connection-oauth=<code>`
 * - said as a toast, with the parameter then taken back out. The parameter is removed so a refresh
 * does not replay it, a failure stays until dismissed and a success does not, and it is mounted
 * beside the toasts so it reaches every route - the rules every sign-in notice keeps (Enterprise
 * Server's Microsoft 365 one too, mounted beside it through the edition seam).
 */
export function ConnectionSignInNotice() {
  const { t } = useTranslation()
  const location = useLocation()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  // StrictMode runs the effect twice before the navigate below has committed.
  const handled = useRef<string | null>(null)

  useEffect(() => {
    const code = connectionSignInCode(location.search)
    if (!code) return
    if (handled.current === location.search) return
    handled.current = location.search

    const failed = isConnectionSignInFailure(code)
    addToast(t(connectionSignInMessage(code)), failed ? 'danger' : 'success', failed ? {
      persist: true,
      link: {
        label: t('Open Settings -> Connections'),
        action: () => useSettingsModalStore.getState().open('Connections'),
      },
    } : undefined)

    // The connection's row and every source picker hold the state this redirect just changed.
    qc.invalidateQueries({ queryKey: keys.connections() })

    const search = withoutConnectionSignInParam(location.search)
    navigate({ pathname: location.pathname, search: search ? `?${search}` : '' }, { replace: true })
  }, [location.pathname, location.search, addToast, navigate, qc, t])

  return null
}
