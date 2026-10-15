import type { ReactNode } from 'react'
import { BootScreen, Button, Caption, ErrorBoundary, ErrorState, Page } from '@/components/ui/kit'
import { Logo } from '@/components/ui/Logo'
import { useTranslation } from '@/i18n'

// A RENDER ERROR IS A SCREEN, NOT A BLANK PAGE (2026-10-14, the cross-app review's X14). The kit's
// ErrorBoundary catches; these word what the reader sees, at three sizes:
// - the whole app (`ScreenErrorBoundary`, in Providers) - the last net, under everything;
// - one route (`PageErrorBoundary`, in Router's LazyRoute) - the header and the way out stay, and
//   a stale chunk after a deploy reloads the page once by itself;
// - one chat message (`MessageErrorBoundary`) - a message that cannot be drawn does not take the
//   thread with it.

function reload() {
  window.location.reload()
}

function ReloadButton() {
  const { t } = useTranslation()
  return <Button variant="secondary" onClick={reload}>{t('Reload')}</Button>
}

export function ScreenErrorBoundary({ children }: { children: ReactNode }) {
  return (
    <ErrorBoundary fallback={({ reloading }) => <ScreenFallback reloading={reloading} />}>
      {children}
    </ErrorBoundary>
  )
}

function ScreenFallback({ reloading }: { reloading: boolean }) {
  const { t } = useTranslation()
  return (
    <div className="h-[var(--app-height,100dvh)] animate-fade-in">
      <BootScreen
        logo={<Logo size={28} />}
        label={t('A new version is available. Reloading...')}
        message={reloading ? undefined : t('Something went wrong on this page. Reloading usually fixes it.')}
        action={reloading ? undefined : <ReloadButton />}
      />
    </div>
  )
}

export function PageErrorBoundary({ children, resetKey }: { children: ReactNode; resetKey?: unknown }) {
  return (
    <ErrorBoundary resetKey={resetKey} fallback={({ reloading }) => <PageFallback reloading={reloading} />}>
      {children}
    </ErrorBoundary>
  )
}

function PageFallback({ reloading }: { reloading: boolean }) {
  const { t } = useTranslation()
  return (
    <Page>
      {reloading
        ? <Caption className="animate-fade-in">{t('A new version is available. Reloading...')}</Caption>
        : (
          <ErrorState title={t('Something went wrong on this page.')} action={<ReloadButton />}>
            {t('Reloading usually fixes it.')}
          </ErrorState>
        )}
    </Page>
  )
}

export function MessageErrorBoundary({ children, resetKey }: { children: ReactNode; resetKey?: unknown }) {
  return (
    // No automatic reload from inside a message: a message has no lazy chunk of its own, and the
    // page around it is still working.
    <ErrorBoundary resetKey={resetKey} reloadOnStaleChunk={false} fallback={() => <MessageFallback />}>
      {children}
    </ErrorBoundary>
  )
}

function MessageFallback() {
  const { t } = useTranslation()
  return <ErrorState title={t('This message could not be shown.')} />
}
