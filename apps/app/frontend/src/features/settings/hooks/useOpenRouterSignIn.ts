import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { safeExternalHref } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { apiErrorText } from '@/services/apiError'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { providerModelsApi } from '../services/providerModelsApi'
import { providersApi } from '../services/providersApi'
import { settingsApi } from '../services/settingsApi'
import { OPENROUTER_POLL_MS, openRouterWaitOver, pickOpenRouterModel } from '../utils/openRouterSignIn'

/** Where the page's OpenRouter sign-in stands. */
export type OpenRouterPhase = 'idle' | 'waiting' | 'choosing' | 'done' | 'failed'

/**
 * SIGN IN WITH OPENROUTER, from the page (OpenRouterSignIn on the server): `start` opens OpenRouter in the person's
 * browser - the Desktop window hands another origin's address to the system browser - and the hook asks where the
 * sign-in stands until it ends; then it makes the provider the agent's, on the model it picks from OpenRouter's own
 * list (utils/openRouterSignIn.ts). `error` says why it did not.
 */
export function useOpenRouterSignIn() {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const [phase, setPhase] = useState<OpenRouterPhase>('idle')
  const [error, setError] = useState<string | null>(null)
  const [model, setModel] = useState<string | null>(null)
  const startedAt = useRef(0)
  // A failure is said once, where the person is, whatever started it.
  const fail = (message: string) => {
    setPhase('failed')
    setError(message)
    addToast(message, 'danger')
  }

  const status = useQuery({
    queryKey: ['providers', 'openrouter-sign-in'],
    queryFn: providersApi.openRouterSignInStatus,
    enabled: phase === 'waiting',
    refetchInterval: OPENROUTER_POLL_MS,
    gcTime: 0,
  })

  const start = async () => {
    setError(null)
    try {
      const safe = safeExternalHref(await providersApi.beginOpenRouterSignIn())
      if (!safe) {
        fail(t('Could not start the sign-in.'))
        return
      }
      startedAt.current = Date.now()
      setPhase('waiting')
      window.open(safe, '_blank', 'noopener')
    } catch (e) {
      fail(apiErrorText(e) ?? t('Could not start the sign-in.'))
    }
  }

  useEffect(() => {
    if (phase !== 'waiting' || !openRouterWaitOver(status.data, Date.now() - startedAt.current)) return
    const s = status.data
    if (!s || s.state !== 'SIGNED_IN' || !s.providerId) {
      fail(s?.state === 'DENIED' ? t('The sign-in was declined on OpenRouter.')
        : s?.state === 'EXPIRED' || !s || s.state === 'PENDING' ? t('The sign-in took too long. Start it again.')
        : t('The sign-in was not completed. Try again.'))
      return
    }
    const providerId = s.providerId
    setPhase('choosing')
    void (async () => {
      try {
        const { models } = await providerModelsApi.listModels(providerId)
        const picked = pickOpenRouterModel(models)
        if (!picked) throw new Error(t('OpenRouter answered, but listed no model to use. Choose one in Settings > Agents.'))
        await settingsApi.saveField('chat', 'provider', providerId)
        await settingsApi.saveField('chat', 'model', picked)
        setModel(picked)
        setPhase('done')
        addToast(t('Signed in to OpenRouter.'), 'success')
      } catch (e) {
        fail(apiErrorText(e) ?? (e instanceof Error && e.message ? e.message : t('Could not set up OpenRouter.')))
      } finally {
        void qc.invalidateQueries({ queryKey: keys.providerInstances() })
        void qc.invalidateQueries({ queryKey: keys.providerConnectivity() })
        void qc.invalidateQueries({ queryKey: keys.settings('chat') })
      }
    })()
  }, [phase, status.data, t, qc, addToast])

  return { phase, error, model, start, waiting: phase === 'waiting' || phase === 'choosing' }
}
