import { useEffect, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { settingsApi } from '@/features/settings/services/settingsApi'
import { useSettings } from '@/features/settings/hooks/useSettings'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { PRODUCT_TOUR, useTourStore } from '@/store/tourStore'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import { useEditionWalkthroughs } from '@edition'
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

// An edition's walkthrough is recorded beside it, by its name: on the server as `<name>-tour-completed` in the same
// `onboarding` settings, in the browser under a key of its own.
function perBrowserKey(tour: string): string {
  return tour === PRODUCT_TOUR ? PER_BROWSER_TOUR_KEY : `proxyma.tour.${tour}-completed`
}

/** The `onboarding` setting that says a walkthrough was finished or skipped. */
export function tourField(tour: string): string {
  return tour === PRODUCT_TOUR ? 'tour-completed' : `${tour}-tour-completed`
}

function perBrowserTourCompleted(tour: string = PRODUCT_TOUR): boolean {
  try { return localStorage.getItem(perBrowserKey(tour)) === 'true' } catch { return false }
}

function markPerBrowserTourCompleted(tour: string = PRODUCT_TOUR) {
  try { localStorage.setItem(perBrowserKey(tour), 'true') } catch { /* storage unavailable - it shows again */ }
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
  // An edition may start one of its walkthroughs in place of the product's - a creator's, who came for their YouTube
  // comments. Finishing it counts for both.
  const first = useEditionWalkthroughs().first

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
    // Not before the edition knows which tour comes first (its add-ons' status).
    if (!enabled || !data || !config || first === undefined || autoStartAttempted) return
    autoStartAttempted = true
    if (perBrowser) {
      // A visitor who arrived on any page but chat followed a link to THAT page; the tour's first
      // step would navigate them off it. Nothing is recorded, so the tour still starts the next time
      // they load chat itself.
      if (perBrowserTourCompleted() || (first && perBrowserTourCompleted(first)) || window.location.pathname !== '/') return
      start(first ?? PRODUCT_TOUR)
      return
    }
    const completed = data['tour-completed'] === 'true' || (!!first && data[tourField(first)] === 'true')
    if (!completed) start(first ?? PRODUCT_TOUR)
  }, [enabled, data, config, perBrowser, start, first])

  const completeTour = useMutation({
    mutationFn: async (fields: string[]) => {
      for (const f of fields) await settingsApi.saveField('onboarding', f, 'true')
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.settings('onboarding') }),
  })

  // The walkthrough running is recorded as seen - and the product's too when it stood in for it. On the sandbox the
  // server row is shared by every visitor signed in as the same account, so completion is recorded locally instead.
  const complete = () => {
    const tour = useTourStore.getState().tour
    const tours = tour === first && tour !== PRODUCT_TOUR ? [tour, PRODUCT_TOUR] : [tour]
    if (perBrowser) tours.forEach(markPerBrowserTourCompleted)
    else completeTour.mutate(tours.map(tourField))
  }

  return { completeTour: complete }
}

// Each page's walkthrough is offered once per page load at most, whatever remounts the page.
const offeredHere = new Set<string>()

/**
 * A PAGE'S WALKTHROUGH ON THE FIRST VISIT (2026-11-10, the maintainer: a walkthrough "for YouTube Assistant and also for
 * the constellations"): started when the page is first opened by somebody who has not finished or skipped it - once
 * the product's own first-run tour is behind them, so the two never meet, and never over a tour already running.
 * `ready` holds it until the page has what its first step points at.
 */
export function useWalkthroughOnFirstVisit(tour: string, ready = true) {
  const { user } = useAuth()
  const enabled = FEATURES.auth ? !!user : true
  const { data } = useSettings('onboarding', enabled)
  const { data: config } = useQuery({ queryKey: ['config'], queryFn: configApi.get, staleTime: Infinity, retry: false })
  // Not over a window the reader is in (2026-11-10): the YouTube assistant's page turned active behind Settings > Add-ons
  // as its trial began, and its walkthrough started there. It waits for Settings and any dialog to close.
  const settingsOpen = useSettingsModalStore(s => s.isOpen)
  const [dialogUp, setDialogUp] = useState(false)
  useEffect(() => {
    if (!enabled || !ready || settingsOpen || offeredHere.has(tour)) return
    const check = () => setDialogUp(!!document.querySelector('[data-dialog-panel]'))
    check()
    const timer = window.setInterval(check, 1000)
    return () => window.clearInterval(timer)
  }, [enabled, ready, settingsOpen, tour])
  useEffect(() => {
    if (!enabled || !ready || !data || !config || offeredHere.has(tour)) return
    if (settingsOpen || dialogUp) return
    const store = useTourStore.getState()
    if (store.active) return
    const perBrowser = config.sandbox === true
    const done = (name: string) => (perBrowser ? perBrowserTourCompleted(name) : String(data[tourField(name)]) === 'true')
    if (!done(PRODUCT_TOUR)) return
    offeredHere.add(tour)
    if (done(tour)) return
    store.start(tour)
    // SHOWN ONCE, on the very first visit (the maintainer, 2026-11-10: "Don't show it every time the user enter the
    // add-on page"): recorded as seen the moment it starts, so leaving the page or the app part way does not bring it
    // back. Finishing or skipping records the same; the page's help button replays it.
    if (perBrowser) markPerBrowserTourCompleted(tour)
    else {
      void settingsApi.saveField('onboarding', tourField(tour), 'true')
        .then(() => queryClient.invalidateQueries({ queryKey: keys.settings('onboarding') }))
        .catch(() => undefined)
    }
  }, [enabled, ready, data, config, tour, settingsOpen, dialogUp])
}
