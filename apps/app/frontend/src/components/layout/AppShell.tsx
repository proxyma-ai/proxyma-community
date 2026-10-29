import { useEffect, useRef, useState, Suspense, lazy } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Topbar } from './Topbar'
import { Sidebar } from './Sidebar'
import { AutoHideHeader, GlassFrame, ProxymaBackground, WindowControls } from '@/components/ui/kit'
import { useFramedLayout, useWideLayout } from '@/hooks/useWideLayout'
import { useDesktopWindow } from '@/hooks/useDesktopWindow'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { AccountDeletionModal } from '@/features/auth/components/AccountDeletionModal'
import { ReConsentModal } from '@/features/auth/components/ReConsentModal'
import { GuestBanner } from '@/features/auth/components/GuestBanner'
import { FeedbackButton } from '@/features/feedback/components/FeedbackButton'
import { EditionBanners } from '@edition'
import { TourOverlay } from '@/features/tour/components/TourOverlay'
import { FEATURES } from '@/config/features'
import { authApi } from '@/features/auth/services/authApi'
import { takePostLoginTarget } from '@/features/auth/services/postLoginRedirect'
import { useToastStore } from '@/store/toastStore'
import { useThemeStore } from '@/store/themeStore'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import { useRestartStore } from '@/store/restartStore'
import { RestartingModal } from '@/components/ui/RestartingModal'
import { shouldShowMcpAd, markMcpAdShownToday, markMcpAdClosed, markMcpAdDismissedForever } from '@/store/mcpAdStore'
import { useProviderConnectivity } from '@/features/settings/hooks/useProviderConnectivity'
import { configApi } from '@/services/configApi'
import { shouldShowWhatsNew, markWhatsNewSeen } from '@/store/whatsNewStore'
import { whatsNewFor } from '@/features/system/data/whatsNew'
import { WhatsNewModal } from '@/features/system/components/WhatsNewModal'
import { QuitDialogs } from '@/features/system/components/QuitButton'
import { useTranslation } from '@/i18n'
import { useConnectorListSync } from '@/hooks/useConnectorListSync'
import { useConnectorCatalog } from '@/features/indexing/hooks/useConnectorCatalog'
import { withReason } from '@/services/apiError'
import { startupDialogs } from './startupDialogs'

// Lazy so Settings' own bundle (dozens of tab components) only downloads the first time it's
// actually opened, not on every authenticated page load - same reasoning Router.tsx used for its
// old route-level split, just triggered by the modal mounting instead of a route match.
const SettingsModal = lazy(() => import('@/features/settings/components/SettingsModal').then(m => ({ default: m.SettingsModal })))

export function AppShell() {
  const { user } = useAuth()
  const wide = useWideLayout()
  const framedWidth = useFramedLayout()
  const desktopWindow = useDesktopWindow()
  // Source lists stay current whichever page is open - see the hook for why it lives here.
  useConnectorListSync()
  // The connector catalog, asked for as the app starts: every mark, mention chip and source name
  // drawn anywhere reads it (sourceMarks.tsx, mentionStyle.ts), so it is in hand before any is.
  useConnectorCatalog()
  const location = useLocation()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const removeToast = useToastStore(s => s.removeToast)
  const productName = useThemeStore(s => s.branding.productName)
  const [dismissedGate, setDismissedGate] = useState(false)
  const isSettingsOpen = useSettingsModalStore(s => s.isOpen)
  const settingsInitialTab = useSettingsModalStore(s => s.initialTab)
  const settingsInitialFocus = useSettingsModalStore(s => s.initialFocus)
  const closeSettings = useSettingsModalStore(s => s.close)
  const openSettings = useSettingsModalStore(s => s.open)
  const restarting = useRestartStore(s => s.restarting)
  const { data: connectivity } = useProviderConnectivity()
  // A toast's text is resolved when it is RAISED, not when it renders, so one already on screen
  // when the language changes keeps the wording it was raised with. Both toasts below are raised
  // once per app mount and are dismissible, so the alternative - re-raising them on every locale
  // change - would show the same notice twice to anyone who switches language.
  //
  // `ready` made that safe while I18nProvider detected the locale in an effect, which ran AFTER
  // this component's mount effect (a child's effects run first) - a toast raised there was worded
  // in English and never re-worded. Since 2026-10-14 the locale is read before the first render
  // and `ready` is always true; the guard stays as a no-op rather than a trap for whoever next
  // changes how the locale is found.
  const { t, ready } = useTranslation()

  // WHAT CHANGED, once, the first time this browser sees a version it has not run before. Both
  // editions deliberately: a server deployment upgrades its users without asking them, so they are
  // MORE likely to wonder what moved than a desktop user who pressed the button themselves.
  //
  // Reuses the ['config'] query ThemeProvider already fetches for branding - same key, same
  // staleTime - so the version costs no additional request.
  const { data: appConfig } = useQuery({
    queryKey: ['config'], queryFn: configApi.get, staleTime: Infinity, retry: false,
  })
  const appVersion = appConfig?.version ?? ''
  const [whatsNewOpen, setWhatsNewOpen] = useState(false)
  useEffect(() => {
    if (appVersion && shouldShowWhatsNew(appVersion)) setWhatsNewOpen(true)
  }, [appVersion])
  const whatsNew = whatsNewFor(appVersion)
  const closeWhatsNew = () => { markWhatsNewSeen(appVersion); setWhatsNewOpen(false) }

  // First-run setup routing is owned entirely by SetupGate (see app/SetupGate.tsx) - AppShell only
  // ever mounts once setup is confirmed complete, so it must not run its own setup check (competing
  // setup redirects are what caused the earlier / <-> /setup loop).

  // Periodic, non-intrusive nudge toward MCP/API - never shown more than once a day, and never
  // again once the user says so ("Don't show again", since 2026-10-02 - until then the only way
  // to stop it at once was to follow its link) or has clicked through. See mcpAdStore.ts.
  useEffect(() => {
    if (!ready) return
    if (!shouldShowMcpAd()) return
    markMcpAdShownToday()
    addToast(
      // `{product}` rather than a template literal: a translatable string has to be one quoted
      // literal the checker can read, and the runtime does the substitution.
      t('Did you know you can query {product} from your IDE or scripts via MCP/API?', { product: productName }),
      'info',
      {
        persist: true,
        link: { label: t('Learn more'), href: 'https://proxyma.ai/docs/api/#mcp' },
        onClose: markMcpAdClosed,
        onLinkClick: markMcpAdDismissedForever,
        quietAction: { label: t("Don't show again"), action: markMcpAdDismissedForever },
      },
    )
    // Intentionally runs once per app mount only - re-rolling on every productName/addToast
    // identity change would re-roll the daily chance far more often than intended. `ready` is
    // the one dependency, and it goes false -> true exactly once.
  }, [ready])

  // Nothing verified a provider was actually reachable until a chat turn failed: the boot-time
  // model construction in LlmProviderFactory makes no network call, so a dead Ollama still left
  // every role reporting ready. The backend sweeps every configured provider at startup AND on
  // every provider settings change (ProviderConnectivityService); this surfaces the verdict.
  //
  // `warn` is the BACKEND's verdict, not recomputed here - it already encodes the parts that are
  // easy to get subtly wrong: only warn once the sweep is DONE, only when at least one provider
  // could actually be probed, and never count Azure OpenAI (which has no model-listing API) as a
  // failure. A first-run install with nothing configured therefore gets no warning, which is the
  // one moment a false alarm would do the most damage.
  //
  // Persistent, because it is a standing condition rather than an event: chat and search will keep
  // failing until someone changes something, and a notice that fades after four seconds is a notice
  // the user scrolling past it never saw.
  const warnProviders = connectivity?.warn === true
  // The id of the warning currently on screen, so it can be TAKEN BACK. Until 2026-09-08 the
  // backend swept once at boot and never again, so this could only ever go false -> true and
  // there was nothing to take back. It re-probes on every provider change now, which means the
  // verdict genuinely goes true -> false the moment someone connects a working provider - and a
  // persistent toast still saying chat will not work is then simply wrong.
  const providerToastId = useRef<string | null>(null)
  useEffect(() => {
    if (!warnProviders) {
      if (providerToastId.current) {
        removeToast(providerToastId.current)
        providerToastId.current = null
      }
      return
    }
    // Guarded rather than keyed on a one-way transition: the verdict can now flip more than once
    // per mount, and re-raising without this would stack a second identical warning each time a
    // provider is saved while still unreachable.
    if (providerToastId.current) return
    providerToastId.current = addToast(
      t('No AI provider is responding. Chat and search will not work until one is reachable.'),
      'danger',
      {
        persist: true,
        link: { label: t('Open Settings'), action: () => openSettings('AI Providers') },
        // The user dismissing it themselves must clear the handle too, or the guard above would
        // suppress the warning for the rest of the session even if it starts failing again.
        onClose: () => { providerToastId.current = null },
      },
    )
  }, [warnProviders])

  // The end of an OAuth2 sign-in: the backend's success handler can only redirect to "/", so a
  // user who was sent a link needing sign-in (typically /shared/{token}) arrives here having lost
  // it. LoginPage stashed the real target before leaving for the provider - claim it now. Scoped
  // to the "/" landing so a target left over from an abandoned flow can't hijack a later page.
  useEffect(() => {
    if (location.pathname !== '/') return
    const target = takePostLoginTarget()
    if (target) navigate(target, { replace: true })
  }, [])

  // Reset the "keep waiting" dismissal whenever a fresh PENDING_DELETION state is observed (e.g.
  // a new login session) so the gate reliably reappears rather than staying dismissed forever.
  useEffect(() => {
    if (user?.accountStatus !== 'PENDING_DELETION') setDismissedGate(false)
  }, [user?.accountStatus, user?.deletionRequestedAt])

  const cancelDeletion = useMutation({
    mutationFn: authApi.cancelDeletion,
    onSuccess: () => {
      addToast(t('Account recovered.'), 'success')
      qc.invalidateQueries({ queryKey: ['me'] })
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to recover account.'), e), 'danger'),
  })

  const deleteNow = useMutation({
    mutationFn: authApi.deleteNow,
    onSuccess: () => { qc.clear(); window.location.href = '/login' },
    onError: (e: unknown) => addToast(withReason(t('Failed to delete account.'), e), 'danger'),
  })

  // The shell's own dialogs, one at a time and in one order (startupDialogs.ts): the deletion
  // gate, the consent gate, "What's new", the walkthrough. The re-consent gate is second behind
  // the deletion gate, and that order is load-bearing rather than cosmetic:
  // AccountDeletionLockoutFilter rejects every endpoint except /me, /logout and the three deletion
  // ones, so POST /me/consent returns 403 for a PENDING_DELETION account - showing it first would
  // be a blocking overlay whose only action cannot succeed. The walkthrough is last: it started
  // beside the consent gate, under it, for every account that had yet to accept the terms.
  // Desktop and Home Server are excluded from both gates by FEATURES.auth - one user, no accounts,
  // nothing to hold a consent record.
  const dialogs = startupDialogs({
    accounts: FEATURES.auth,
    user,
    deletionGateDismissed: dismissedGate,
    whatsNewPending: whatsNewOpen && !!whatsNew,
  })
  const showGate = dialogs.deletionGate
  const showReConsent = dialogs.reConsent

  // THE GLASS SHELL (the maintainer's request, 2026-10-29: "no header at all + glass effect", then
  // "every page, every thing" - the browser identical to the desktop app): the sidebar on a frosted
  // frame, the page a glass sheet inside it (the kit's GlassFrame), signed in or not, the sign-in,
  // setup and password pages included. From `md` in a browser, the sidebar folded to its icons below
  // `lg`; on a phone, a glass header bar and the phone menu over the same field and sheet.
  // The desktop app's borderless window is ALWAYS the frame, whatever its width: it has no title bar
  // of its own, and the frame's strip carries its buttons and is what it is dragged by.
  const framed = framedWidth || !!desktopWindow
  // The sign-in, setup and password pages: no banner urging the visitor to sign in over them.
  const authPage = ['/login', '/setup', '/forgot-password', '/reset-password'].includes(location.pathname)
  const page = (
    <>
        {/* Above the scroll container, so a license problem cannot be scrolled away from. Enterprise
            Server only (its license re-check and trial banners) - Desktop and the single-user
            server have no license, and their edition renders nothing here. Renders nothing at all
            unless the server reported one. */}
        <EditionBanners />
        {/* Server mode, signed out only - see GuestBanner, which gates itself. Not on the sign-in,
            setup and password pages, which render in this shell too: a banner urging someone to
            sign in has nothing to say there. */}
        {!authPage && <GuestBanner />}
        {/* The global thin scrollbar, like every other scroller in four apps - it was the one page
            scroller that hid its bar (X12), so a long page gave no sign of how long it was. */}
        {/* The scrollbar's room is ALWAYS kept (the maintainer, 2026-10-30): a page whose skeleton or
            entrance ran a little taller than the window showed the bar for a moment, shifting the
            page left and back. On the right only - kept on both sides, it left a strip beside a
            full-height page's left panel (Constellations). */}
        <main className="flex-1 overflow-auto [scrollbar-gutter:stable]">
          {/* Keyed by pathname so a top-level nav change (Chat/Connectors/Docs/...) remounts this
              wrapper and replays the fade-up entrance - Suspense's own fallback (PageSkeleton)
              already covers the lazy-chunk loading gap, this is purely the settled-content
              transition on top of that. */}
          <div key={location.pathname} className="animate-fade-up h-full">
            <Outlet />
          </div>
        </main>
    </>
  )

  return (
    <>
      {/* The field behind the glass, at every width (an app shell's ProxymaBackground). */}
      <ProxymaBackground />
      {framed ? (
        <div className="relative z-10 h-full overflow-hidden">
          {/* In the desktop app's own borderless window the frame floats on the field with the
              window's buttons over the page; in a browser it is the page, edge to edge. */}
          <GlassFrame
            sidebar={<Sidebar compact={!wide} />}
            mode={desktopWindow ? 'window' : 'page'}
            onToggleMaximize={desktopWindow?.controls.toggleMaximize}
            windowBar={desktopWindow && (
              <WindowControls
                maximized={desktopWindow.maximized}
                onMinimize={desktopWindow.controls.minimize}
                onToggleMaximize={desktopWindow.controls.toggleMaximize}
                onClose={desktopWindow.controls.close}
                labels={{ minimize: t('Minimize'), maximize: t('Maximize'), restore: t('Restore'), close: t('Close') }}
              />
            )}
          >
            {page}
          </GlassFrame>
        </div>
      ) : (
        <div className="relative z-10 flex flex-col h-full overflow-hidden">
          {/* On a phone the bar steps aside while the reader swipes on down a page, and comes back
              when they turn round - see the kit's AutoHideHeader. Every page starts with it. */}
          <AutoHideHeader resetKey={location.pathname}>
            <Topbar />
          </AutoHideHeader>
          {/* The page is the same glass sheet as in the frame, full bleed (the kit's .app-sheet). */}
          <div className="app-sheet flex-1 min-h-0 flex flex-col">{page}</div>
        </div>
      )}
      {showGate && (
        <AccountDeletionModal
          mode="gate"
          deletionRequestedAt={user!.deletionRequestedAt}
          isSubmitting={cancelDeletion.isPending || deleteNow.isPending}
          onKeepWaiting={() => setDismissedGate(true)}
          onRecover={() => cancelDeletion.mutate()}
          onDeleteNow={() => deleteNow.mutate()}
        />
      )}
      {showReConsent && <ReConsentModal user={user!} />}
      <FeedbackButton />
      {/* Both modes - the walkthrough is how a first-run desktop user finds Connectors, Settings
          and the MCP/API surfaces at all. Steps whose subject is server-only (the feedback
          button, storage quota) drop themselves out via requiresFeature/optionalTarget rather
          than the whole overlay being gated here. Mounted only once no gate or "What's new" is
          up, so its auto-start (useOnboardingTour) cannot fire under one: an account that must
          accept the terms meets the walkthrough after accepting, and never if it signs out. */}
      {dialogs.tour && <TourOverlay />}
      {/* Defense-in-depth auth check: AppShell itself isn't blanket-gated (e.g. /chat, /docs,
          /help need no auth), only individual routes are via RequireAuth. Every trigger that
          calls open() already only renders for a signed-in user (UserMenu returns null when
          logged out; Topbar's standalone link only exists in no-auth desktop mode), so this
          never actually fires for a logged-out visitor - it's just a second guard matching
          RequireAuth's own `!user` check, in case a future trigger point forgets that rule. */}
      {isSettingsOpen && (!FEATURES.auth || user) && (
        <Suspense fallback={null}>
          <SettingsModal onClose={closeSettings} initialTab={settingsInitialTab} initialFocus={settingsInitialFocus} />
        </Suspense>
      )}
      {/* LAST, and owned by the shell rather than by whoever triggered the restart. Three of the
          four restart triggers live inside the Settings modal, which closes on a backdrop click -
          rendered from there, the blocking screen went away with it and handed the user a fully
          interactive app whose backend had already exited. See store/restartStore.ts. */}
      {restarting && <RestartingModal />}
      {/* The same reasoning for Quit: both of its triggers are menu rows, and the menu closes as
          the confirmation opens. See store/quitStore.ts. Renders nothing until one is pressed. */}
      <QuitDialogs />

      {/* Gated on an ENTRY existing as well as on the version being new, so a patch that changed
          nothing a user would notice interrupts nobody. shouldShowWhatsNew has already recorded
          the version by then, so it does not ask again on the next load either. */}
      {dialogs.whatsNew && whatsNew && (
        <WhatsNewModal version={appVersion} entry={whatsNew} onClose={closeWhatsNew} />
      )}
    </>
  )
}
