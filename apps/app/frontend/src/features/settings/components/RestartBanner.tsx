import { Clock, RotateCw } from 'lucide-react'
import { Button, Callout, Tooltip } from '@/components/ui/kit'
import { systemApi } from '@/services/systemApi'
import { useRestartStore } from '@/store/restartStore'
import { useTranslation } from '@/i18n'

/**
 * WAITS FOR THE APPLICATION TO COME BACK after something restarted it - a reset, an update, a
 * restore - with the restart screen over the page, and reloads the page once it answers. Resolves
 * false when it did not come back in time, for the caller to say so. Settings, Reset, Updates and
 * Administration each wrote this wait by hand.
 */
export function useAwaitRestart(): () => Promise<boolean> {
  const beginRestart = useRestartStore(s => s.begin)
  const endRestart = useRestartStore(s => s.end)
  return async () => {
    beginRestart()
    try {
      await systemApi.waitForRestart()
      window.location.reload()
      return true
    } catch {
      endRestart()
      return false
    }
  }
}

/**
 * Restarts the application and reloads the page once it answers again. Resolves false when the
 * restart could not be done, for the caller to say so.
 */
export function useRestartNow(): () => Promise<boolean> {
  const beginRestart = useRestartStore(s => s.begin)
  const endRestart = useRestartStore(s => s.end)
  return async () => {
    beginRestart()
    try {
      await systemApi.restart()
      await systemApi.waitForRestart()
      window.location.reload()
      return true
    } catch {
      endRestart()
      return false
    }
  }
}

// "A restart is required", with the two answers. Its own file since 2026-09-27, when a
// configuration import that touches Network settings started needing the same banner the Settings
// save shows - one banner, so the two cannot come to say different things.
//
// The kit's warning Callout since the 2026-10 rebuild: Restart now is the primary action (the
// product has no warning-filled button), Later the quiet text action beside it.
export function RestartBanner({ onRestartNow, onRestartLater, message }: {
  onRestartNow: () => void
  onRestartLater: () => void
  /** Defaults to the Settings save's sentence. */
  message?: string
}) {
  const { t } = useTranslation()
  return (
    <Callout
      tone="warning"
      role="status"
      actions={(
        <>
          <Tooltip label={t('Restart the application now to apply the new settings immediately')}>
            <Button onClick={onRestartNow}>
              <RotateCw size={14} aria-hidden="true" />
              {t('Restart now')}
            </Button>
          </Tooltip>
          <Tooltip label={t('Dismiss this banner. Changes will take effect on the next restart')}>
            <Button variant="quiet" onClick={onRestartLater}>
              <Clock size={14} aria-hidden="true" />
              {t('Restart later')}
            </Button>
          </Tooltip>
        </>
      )}
    >
      {message ?? t('Settings saved. A restart is required for changes to take effect.')}
    </Callout>
  )
}
