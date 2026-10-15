import { useEffect, useState } from 'react'
import { FadeSwap } from '@/components/ui/kit'
import { StatusLayer } from '@/components/ui/StatusLayer'
import { http } from '@/services/httpClient'
import { useTranslation } from '@/i18n'

// The blocking screen shown while the app self-restarts (see systemApi.restart/reset +
// waitForRestart, and applyPendingUpdate in the desktop launcher, which runs during the same
// gap). Shared by every path that restarts: SettingsModal's restart-to-apply flow, AdminPage's
// three resets, and the desktop Settings > Reset page.
//
// UNMISTAKABLE, and closable by nothing. There is no X, no Cancel, no backdrop click and no
// Escape - the process this is covering is already gone, so every one of those would only let the
// user reach a dead UI and conclude the app had crashed. Everything behind it is darkened and
// blurred (the status screens' darker scrim): a reset or an in-place update leaves the app
// unresponsive for the best part of a minute, and a small spinner in the middle of an otherwise
// normal-looking page is indistinguishable from a hang. Whatever else changes here, it must stay
// unclosable and it must stay unmistakable.
//
// IT IS THE DESKTOP SPLASH, as a card the size of the launcher's splash window (kit SplashScreen,
// since 2026-10-10 - it covered the whole window for a day, and before that it was the kit's status
// card). On a desktop restart the launcher's own splash is on screen at the same time, so the two
// are drawn alike: the mark turning at the splash's rate (one turn in 12 s -
// SplashWindow.MARK_DEG_PER_MS, 30 deg/s), the name, ONE phase line in sentence case, and the
// 200x4 wave bar. The phase is read off the health endpoint rather than told by the caller:
// "Restarting" while the old process still answers, "Starting" from the first request it does not.
//
// A RESTART THAT IS TAKING LONGER THAN USUAL SAYS SO, after a minute (maintainer's request,
// 2026-10-10), in the line under the bar - the same sentence, at the same moment, as the launcher's
// splash (SplashWindow.SLOW_HINT). A restart into an update rebuilds the Python runtime before the
// backend starts, and a minute of an unchanged screen read as stuck. How long the wait may last
// before it is called a failure is systemApi.waitForRestart's.
export function RestartingModal() {
  const { t } = useTranslation()
  const [phase, setPhase] = useState<'stopping' | 'starting'>('stopping')
  const [slow, setSlow] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => setSlow(true), SLOW_START_MS)
    return () => clearTimeout(timer)
  }, [])

  useEffect(() => {
    if (phase !== 'stopping') return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout>
    const poll = () => {
      http.get('/api/v1/health')
        .then(() => { if (!cancelled) timer = setTimeout(poll, 700) })
        .catch(() => { if (!cancelled) setPhase('starting') })
    }
    timer = setTimeout(poll, 700)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [phase])

  // Escape is swallowed at the document, in the capture phase, so it never reaches whatever modal
  // this was opened from - several of them close on Escape, and closing the thing underneath the
  // restart screen is exactly the half-state this exists to prevent. A layer without an onClose
  // already swallows Escape as the top layer; this stays as the belt to that brace, because it
  // also holds when this screen mounts in the same commit as a dialog that registers after it.
  useEffect(() => {
    const swallow = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation() }
    }
    document.addEventListener('keydown', swallow, true)
    return () => document.removeEventListener('keydown', swallow, true)
  }, [])

  // A layer of the app's dialog stack (StatusLayer), portaled to the body like every dialog -
  // AppShell's root wrapper is `overflow-hidden` with a fixed height, which clips a fixed
  // descendant to its own box in practice. Its layer is `z-status`, as MaintenanceGate's is: the one
  // that must win over literally everything - including the onboarding walkthrough (`z-tour`), which
  // would otherwise draw its own dimmed overlay and a Next button on top of a restarting application.
  return (
    <StatusLayer
      // No onClose: nothing may dismiss this - see above.
      level={2}
      ariaLabel={t('Restarting')}
      panelProps={{ 'aria-live': 'assertive' }}
      splash
      busy
      title={<FadeSwap swapKey={phase}>{phase === 'stopping' ? t('Restarting') : t('Starting')}</FadeSwap>}
    >
      {slow ? t('This is taking a little longer than usual. Please wait.') : null}
    </StatusLayer>
  )
}

/** When a restart starts saying it is taking longer than usual - SplashWindow.SLOW_AFTER_MS. */
const SLOW_START_MS = 60_000
