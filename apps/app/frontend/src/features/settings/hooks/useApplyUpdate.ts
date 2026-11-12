import { useMutation } from '@tanstack/react-query'
import { useToastStore } from '@/store/toastStore'
import { useRestartStore } from '@/store/restartStore'
import { systemApi } from '@/services/systemApi'
import { useTranslation } from '@/i18n'
import { updateApi } from '../services/updateApi'

/**
 * RESTART INTO THE STAGED UPDATE - Settings > Updates' "Restart and install" and the desktop window's
 * "Restart to update" (since 2026-11-04), one flow for both. The restart screen is the shell's own
 * (restartStore, RestartingModal), so it stays up whichever of them asked.
 *
 * The request is EXPECTED not to answer: the process exits ~200ms after accepting it, so the
 * connection drops before a response is written. Treating that as a failure would show an error
 * over a restart that is working - the same reasoning ResetPage documents for its own restarts.
 * A refusal WITH a status is a real one, though: the backend is still up and said no.
 *
 * Then it waits for the app to go and come back, and reloads - as every other restart does. It did
 * not until 2026-09-27: the desktop window lives across the restart (desktop-shell/main.js), so the
 * restart screen stayed up over a new, working backend until someone pressed F5.
 */
export function useApplyUpdate() {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const beginRestart = useRestartStore(s => s.begin)
  const endRestart = useRestartStore(s => s.end)
  return useMutation({
    mutationFn: async () => {
      beginRestart()
      try {
        await updateApi.apply()
      } catch (e) {
        if ((e as { response?: unknown }).response) throw e
      }
      await systemApi.waitForDown()
      await systemApi.waitForRestart()
      window.location.reload()
    },
    onError: () => {
      endRestart()
      addToast(t('Proxyma could not restart into the update. Restart Proxyma manually.'), 'danger')
    },
  })
}
