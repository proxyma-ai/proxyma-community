import { useEffect } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { settingsApi } from '@/features/settings/services/settingsApi'
import { useSettings } from '@/features/settings/hooks/useSettings'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { useTourStore } from '@/store/tourStore'
import { FEATURES } from '@/config/features'
import { queryClient } from '@/app/QueryClient'
import { keys } from '@/services/queryKeys'
import { configApi } from '@/services/configApi'

// ON THE PUBLIC ENTERPRISE SANDBOX, COMPLETION LIVES IN THE VISITOR'S BROWSER, since 2026-10-06. Its
// three accounts are shared by every visitor, so a server-side flag set by the first visitor to finish
// the tour as Member hid it from every later one.
//
// Per browser, which is the right scope for a public instance anyway. Wrapped like mcpAdStore:
// storage can throw (private browsing, blocked site data), and a tour that shows again is a far
// better failure than a page that does not render.
//
// The key keeps the name it was given for the retired public demo, which recorded completion the same
// way: renaming it would show the tour again to every sandbox visitor who already finished it.
const PER_BROWSER_TOUR_KEY = 'proxyma.demo.tour-completed'

function perBrowserTourCompleted(): boolean {
  try { return localStorage.getItem(PER_BROWSER_TOUR_KEY) === 'true' } catch { return false }
}

function markPerBrowserTourCompleted() {
  try { localStorage.setItem(PER_BROWSER_TOUR_KEY, 'true') } catch { /* storage unavailable - it shows again */ }
}

// Module-level (not component-state) guard: the auto-start check must fire at most once per
// browser session even if useOnboardingTour's owner remounts (e.g. AppShell re-rendering) before
// the settings fetch resolves - otherwise a slow first request could race a second mount into
// starting the tour twice.
let autoStartAttempted = false

// Auto-starts the walkthrough once for a user who hasn't completed it yet, and exposes a
// `completeTour` mutation used by that completion.
//
// WHEN it may start is not decided here: AppShell mounts TourOverlay, this hook's one caller, only
// once no deletion gate, consent gate or "What's new" is up (components/layout/startupDialogs.ts).
// Deciding it here as well would be a second copy of that order to drift from the first.
//
// The manual "Replay walkthrough" entry point is the button on Settings > About, which calls
// `useTourStore.getState().start()` directly rather than going through this hook. It used to live
// on the Help page; that page was removed and the control went with it, leaving the tour's own
// closing step pointing at a replay that did not exist anywhere.
export function useOnboardingTour() {
  const { user } = useAuth()
  const start = useTourStore(s => s.start)

  // Runs in both modes. Server mode waits for a signed-in user, since "has this user finished the
  // walkthrough" is per-user state read from their own settings row. Desktop mode has no user to
  // wait for - the `onboarding` prefix is user-scoped on the backend too, and resolves to the
  // single global row when there is no principal (see SettingsController's VIEWER_PREFIXES), so
  // the same completed/not-completed flag works unchanged there.
  const enabled = FEATURES.auth ? !!user : true
  const { data } = useSettings('onboarding', enabled)

  // The shared ['config'] query, read here rather than through useIsSandbox because this decision has
  // to WAIT for it: useIsSandbox reads "still loading" as "not the sandbox", and deciding on that would
  // start the server-backed tour on the sandbox whenever config answered after settings.
  const { data: config } = useQuery({ queryKey: ['config'], queryFn: configApi.get, staleTime: Infinity, retry: false })
  // Recorded in the browser on the public sandbox - see PER_BROWSER_TOUR_KEY.
  const perBrowser = config?.sandbox === true

  useEffect(() => {
    if (!enabled || !data || !config || autoStartAttempted) return
    autoStartAttempted = true
    if (perBrowser) {
      // A visitor who arrived on any page but chat followed a link to THAT page; the tour's first
      // step would navigate them off it. Nothing is recorded, so the tour still starts the next time
      // they load chat itself.
      if (perBrowserTourCompleted() || window.location.pathname !== '/') return
      start()
      return
    }
    const completed = data['tour-completed'] === 'true'
    if (!completed) start()
  }, [enabled, data, config, perBrowser, start])

  const completeTour = useMutation({
    mutationFn: () => settingsApi.saveField('onboarding', 'tour-completed', 'true'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.settings('onboarding') }),
  })

  // On the sandbox the server row is shared by every visitor signed in as the same account, so
  // completion is recorded locally instead.
  const complete = () => {
    if (perBrowser) markPerBrowserTourCompleted()
    else completeTour.mutate()
  }

  return { completeTour: complete }
}
