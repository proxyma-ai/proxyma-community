import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from './QueryClient'
import { Router } from './Router'
import { SSEProvider } from './SSEProvider'
import { ThemeProvider } from './ThemeProvider'
import { MaintenanceGate } from './MaintenanceGate'
import { MaintenanceBanner } from './MaintenanceBanner'
import { SandboxBanner } from './SandboxBanner'
import { AccessGate } from './AccessGate'
import { I18nProvider, catalogs } from '@/i18n'
import { ScreenErrorBoundary } from '@/components/ui/ErrorBoundaries'

export function Providers() {
  return (
    <QueryClientProvider client={queryClient}>
      {/* Outside ThemeProvider because a string can be needed by anything below this point,
          including MaintenanceGate's blocking overlay - and nothing above it renders text. */}
      <I18nProvider catalogs={catalogs}>
      {/* The last net: a render error anywhere below is a screen with a Reload, not a blank page.
          Inside the I18nProvider so that screen is in the reader's language. */}
      <ScreenErrorBoundary>
      {/* Above everything that calls the API: on Desktop and the Home Server nothing may, until the
          access code has been presented (AccessGate). A passthrough on Enterprise Server. */}
      <AccessGate>
      <ThemeProvider>
        <MaintenanceGate>
          {/* The one box the size of the VISIBLE part of the screen - the box the kit pins the body
              to (`data-app-shell`, see index.css) - is claimed here, not on AppShell: the banners
              above the routes have a variable height (a schedule can wrap to two lines), so this
              column gives them what their content needs and everything below the rest
              (flex-1 min-h-0), and AppShell, LoginPage and the rest size themselves to that with
              h-full. It is named by its variable rather than as h-full so it does not depend on
              every provider above it rendering no element of its own; it was h-dvh, which the
              on-screen keyboard does not shrink. */}
          <div className="h-[var(--app-height,100dvh)] flex flex-col overflow-hidden">
            {/* Mounted here (above Router, inside MaintenanceGate's children) rather than inside
                AppShell/Topbar - Topbar only renders for routes nested under AppShell, but /login
                and /setup are standalone top-level routes with no header at all. This is the one
                spot that wraps every route (including those two) while still being skipped, along
                with the rest of `children`, on the rare turn where MaintenanceGate itself renders
                its full-screen blocking overlay instead. */}
            <MaintenanceBanner />
            {/* BELOW the maintenance banner, deliberately. On the one occasion both are up, the
                temporary problem is the news and the permanent condition is the context - and a
                banner that jumps position depending on whether another one is showing is worse
                than either order. Off the sandbox it is an empty Presence, so this costs every
                other deployment one check. */}
            <SandboxBanner />
            <div className="flex-1 min-h-0">
              {/* ToastContainer renders inside Router (see Router.tsx) rather than here as a
                  sibling - toasts can carry a <Link>, which needs a Router ancestor in context. */}
              <SSEProvider>
                <Router />
              </SSEProvider>
            </div>
          </div>
        </MaintenanceGate>
      </ThemeProvider>
      </AccessGate>
      </ScreenErrorBoundary>
      </I18nProvider>
    </QueryClientProvider>
  )
}
