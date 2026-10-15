import { Suspense, lazy, useLayoutEffect, useRef, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { PageErrorBoundary } from '@/components/ui/ErrorBoundaries'
import { AppShell } from '@/components/layout/AppShell'
import { RequireAuth } from '@/components/guards/RequireAuth'
import { RequireAdmin } from '@/components/guards/RequireAdmin'
import { RequireServerMode } from '@/components/guards/RequireServerMode'
import { ToastContainer } from '@/components/ui/ToastContainer'
import {
  AuthPageSkeleton, PageSkeleton, ChatPageSkeleton, SharedConversationSkeleton, SourcesPageSkeleton,
} from '@/components/ui/Skeleton'
import { EDITION_ROUTES, EditionNotices } from '@edition'
import { LoginPage } from '@/features/auth/pages/LoginPage'
import { SetupPage } from '@/features/auth/pages/SetupPage'
import { SetupGate } from './SetupGate'
import { ConnectionSignInNotice } from './ConnectionSignInNotice'

// Route-level code splitting: each page's bundle (and everything it imports) loads only when
// its route is visited, instead of all pages shipping in the single initial bundle. Auth pages
// (Login/Setup) are the very first thing a fresh session renders, so they stay eager - splitting
// them would trade "faster main bundle" for "slower first paint" on the most latency-sensitive path.
const ChatPage = lazy(() => import('@/features/chat/pages/ChatPage').then(m => ({ default: m.ChatPage })))
const SharedConversationPage = lazy(() => import('@/features/chat/pages/SharedConversationPage').then(m => ({ default: m.SharedConversationPage })))
const SourcesPage = lazy(() => import('@/features/sources/pages/SourcesPage').then(m => ({ default: m.SourcesPage })))
const ForgotPasswordPage = lazy(() => import('@/features/auth/pages/ForgotPasswordPage').then(m => ({ default: m.ForgotPasswordPage })))
// THE KIT GALLERY, development only: `import.meta.env.DEV` is replaced by `false` in every build,
// so this route and the gallery's own chunk never reach an installed app.
const KitGallery = import.meta.env.DEV
  ? lazy(() => import('@/dev/KitGallery').then(m => ({ default: m.KitGallery })))
  : null
const ResetPasswordPage = lazy(() => import('@/features/auth/pages/ResetPasswordPage').then(m => ({ default: m.ResetPasswordPage })))

// Each route gets a fallback shaped like its own real layout (see Skeleton.tsx) instead of one
// generic header+card-list skeleton for every page - the password pages get the sign-in card's
// shape (AuthPageSkeleton), and the generic PageSkeleton is left for a route with no dedicated one.
//
// The page fades in over its skeleton when its chunk arrives, rather than replacing it in one
// frame - and ONLY then. A route change with the chunk already loaded is AppShell's to animate (its
// outlet fades up per path); this wrapper fading in as well ran two entrances at once, one inside
// the other. So the fallback marks that it was shown, and the page fades in only after one.
// `h-full` so a page sized to its route container still is.
//
// A page that throws while rendering - or whose chunk is gone because a deploy replaced it under an
// open tab - is caught HERE, so the header stays and the reader can go elsewhere; the stale chunk
// reloads the page once by itself. Moving to another path clears it (`resetKey`).
function LazyRoute({ children, fallback = <PageSkeleton /> }: { children: React.ReactNode; fallback?: React.ReactNode }) {
  const { pathname } = useLocation()
  const waited = useRef(false)
  return (
    <PageErrorBoundary resetKey={pathname}>
      <Suspense fallback={<Waiting onShown={() => { waited.current = true }}>{fallback}</Waiting>}>
        <Arrived fade={waited}>{children}</Arrived>
      </Suspense>
    </PageErrorBoundary>
  )
}

/** The route's skeleton, noting that it was on screen. */
function Waiting({ onShown, children }: { onShown: () => void; children: React.ReactNode }) {
  useLayoutEffect(onShown, [onShown])
  return <>{children}</>
}

/** The page, fading in over the skeleton it replaces - read once, as it mounts. */
function Arrived({ fade, children }: { fade: React.MutableRefObject<boolean>; children: React.ReactNode }) {
  const [faded] = useState(fade.current)
  return <div className={faded ? 'h-full animate-fade-in' : 'h-full'}>{children}</div>
}

export function Router() {
  return (
    <BrowserRouter>
      {/* Rendered inside BrowserRouter, not as a sibling of <Router /> in Providers.tsx - toasts
          (e.g. AppShell's MCP nudge) can carry a <Link>, which throws if it renders without a
          Router ancestor in its context, taking down the whole app. */}
      <ToastContainer />
      {/* What the edition's own sign-ins say when they redirect back (Enterprise Server's Microsoft
          365 sign-in reads `?microsoft=<code>` off the URL, says what it means, and takes the
          parameter back out). Here rather than inside a page for the same reason ToastContainer
          is: it needs the router, it belongs to no route, and the callback's landing URL is the app
          root whichever page happens to render there. Nothing in the Community edition. */}
      <EditionNotices />
      <ConnectionSignInNotice />
      <SetupGate>
        <Routes>
          <Route path="/login" element={<RequireServerMode><LoginPage /></RequireServerMode>} />
          <Route path="/setup" element={<RequireServerMode><SetupPage /></RequireServerMode>} />
          <Route path="/forgot-password" element={<LazyRoute fallback={<AuthPageSkeleton />}><ForgotPasswordPage /></LazyRoute>} />
          <Route path="/reset-password" element={<LazyRoute fallback={<AuthPageSkeleton />}><ResetPasswordPage /></LazyRoute>} />
          {KitGallery && <Route path="/__kit" element={<LazyRoute><KitGallery /></LazyRoute>} />}
          <Route element={<AppShell />}>
            <Route index element={<LazyRoute fallback={<ChatPageSkeleton />}><ChatPage /></LazyRoute>} />
            <Route path="/chat" element={<LazyRoute fallback={<ChatPageSkeleton />}><ChatPage /></LazyRoute>} />
            <Route path="/chat/:conversationId" element={<LazyRoute fallback={<ChatPageSkeleton />}><ChatPage /></LazyRoute>} />
            {/* Read-only view of a conversation someone shared. RequireAuth (a no-op in desktop
                mode) mirrors the backend's own rule for this one path - see SecurityConfig's
                /api/v1/chat/shared/** matcher: the token is unguessable, but on its own it is
                deliberately not enough to read the content. */}
            {/* RequireServerMode as well as RequireAuth: with no way to MINT a share link in
                desktop (see MessageBubble's ShareButton), a viewer for one is a page that can only
                ever be reached by hand-typing a token. Existing rows in an upgraded install keep
                resolving on the backend - the endpoint is shared code with server - they simply
                have no route to render on. */}
            <Route path="/shared/:token" element={<RequireServerMode><RequireAuth><LazyRoute fallback={<SharedConversationSkeleton />}><SharedConversationPage /></LazyRoute></RequireAuth></RequireServerMode>} />
            <Route path="/sources" element={<RequireAuth><LazyRoute fallback={<SourcesPageSkeleton />}><SourcesPage /></LazyRoute></RequireAuth>} />
            {/* This page was /connectors until Settings grew a Connections tab of its own and the
                name stopped saying which one you meant. The old path stays as a redirect rather
                than 404ing: it is bookmarked, it is in the browser history of everyone already
                using this, and it appears in older release notes. `replace` so the redirect does
                not sit in history and trap the back button on it. */}
            <Route path="/connectors" element={<Navigate to="/sources" replace />} />
            {/* THE EDITION'S OWN PAGES (src/edition/types.ts): key accounts and Administration on
                Enterprise, none on Desktop and the Home Server, whose build does not carry them at
                all. Each is server-mode only, and signed-in or admin only. */}
            {EDITION_ROUTES.map(r => (
              <Route key={r.path} path={r.path} element={
                <RequireServerMode>
                  {r.access === 'admin'
                    ? <RequireAdmin><LazyRoute fallback={r.fallback}><r.page /></LazyRoute></RequireAdmin>
                    : <RequireAuth><LazyRoute fallback={r.fallback}><r.page /></LazyRoute></RequireAuth>}
                </RequireServerMode>
              } />
            ))}
            {/* Anything else lands on chat rather than a blank page - which is what a desktop user
                reaching /admin or /key-accounts by an old link got while those routes were in every
                build behind RequireServerMode, and still gets now that the desktop build has none. */}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>
      </SetupGate>
    </BrowserRouter>
  )
}
