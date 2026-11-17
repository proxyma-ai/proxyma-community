import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { FileText } from 'lucide-react'
import { Button, Caption, DialogBody, DialogDescription, DialogFooter, DialogTitle } from '@/components/ui/kit'
import { ModalShell } from '@/components/ui/ModalShell'
import { ConsentFields } from '@/features/auth/components/ConsentFields'
import { useAppConfig } from '@/hooks/useAppConfig'
import { useDates } from '@/hooks/useDates'
import { useTranslation } from '@/i18n'
import { apiErrorText } from '@/services/apiError'
import { systemApi, type LegalStatus } from '@/services/systemApi'
import { useQuitStore } from '@/store/quitStore'

/** The query the shell reads the gate's state from - shared so accepting can seed it. */
export const LEGAL_STATUS_KEY = ['desktop-legal'] as const

// THE START'S LEGAL GATE, Desktop and Home Server (2026-11-14, the maintainer: "Proxyma here is like
// the bridge between Youtube and the user's AI model ... The user is responsible about whether their
// youtube data is being trained or not by their AI model", then "the best place to show our
// disclaimer is when Proxyma is started for the first time. Force the user to agree ... If the user
// don't, shutdown the app, next start ask again. If the user agree, log the agreement,
// non-deletable."). Raised by AppShell through startupDialogs before anything else.
//
// Blocking and not dismissable, as ReConsentModal is on Enterprise Server: no Escape, no backdrop,
// no "later". Declining is quitting Proxyma, which is the honest alternative to accepting. What is
// said is the one thing every edition's data path shares - Proxyma passes data to the AI services
// the person connects, by their API or to an assistant app over MCP - and that what those keep or
// train on is the person's setting with them. The server keeps the acceptance where nothing in the
// app deletes it (LegalAcceptances), and shows it on the Logs page.
//
// NO QUIT ON HOME SERVER (the 2026-11-16 release review): its compose file restarts the container
// whatever it exits with (`restart: unless-stopped`), so Quit stopped Proxyma only for Docker to start
// it again while the page said it had stopped. There the gate stays until the documents are accepted,
// and says so (Privacy 3.6); whoever runs the server stops it with Docker.
export function LegalGate({ status }: { status: LegalStatus }) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const dates = useDates()
  const { data: config } = useAppConfig()
  const homeServer = config?.homeServer === true
  const quitNow = useQuitStore(s => s.now)
  const quitting = useQuitStore(s => s.phase !== 'idle')
  const [accepted, setAccepted] = useState(false)
  const [error, setError] = useState<string | undefined>()

  const accept = useMutation({
    // The version the page was shown, never a fresh read: the server refuses any other.
    mutationFn: () => systemApi.acceptLegal(status.version),
    onSuccess: next => { qc.setQueryData(LEGAL_STATUS_KEY, next) },
    onError: err => setError(apiErrorText(err) ?? t('Could not record your acceptance. Please try again.')),
  })

  const busy = accept.isPending || quitting
  // An install that accepted an earlier version is asked because the documents changed.
  const changed = !!status.acceptedVersion && status.acceptedVersion !== status.version
  const title = changed ? t('Our terms have changed') : t('Before you start')

  return (
    <ModalShell closeOnBackdrop={false} role="alertdialog" ariaLabel={title} size="lg">
      <DialogBody centered>
        <FileText size={32} className="mb-4 text-brand-legible" aria-hidden="true" />
        <DialogTitle>{title}</DialogTitle>
        <DialogDescription className="mt-2 self-stretch text-justify">
          {t('For each task, Proxyma may send the AI services you connect what that task needs: your question, excerpts found in your sources, and the full text of a document or comment when the task reads or indexes it. It goes through their APIs, or to your AI assistant app over MCP.')}
        </DialogDescription>
        <DialogDescription className="mt-2 self-stretch text-justify">
          {t("Whether a service keeps that data or uses it to train its models is set in your account with it and is outside Proxyma's control. Check it there.")}
        </DialogDescription>

        <div className="mt-4 self-stretch text-left">
          <ConsentFields
            requirements={{
              version: status.version,
              termsUrl: status.termsUrl,
              privacyUrl: status.privacyUrl,
              eulaUrl: status.eulaUrl,
            }}
            accepted={accepted}
            onAcceptedChange={next => { setAccepted(next); setError(undefined) }}
            disabled={busy}
            error={error}
          />
        </div>

        {homeServer && (
          <Caption className="mt-4 self-stretch text-justify">
            {t('Proxyma Home Server stays closed until the documents are accepted.')}
          </Caption>
        )}

        {changed && status.acceptedAt && (
          <Caption className="mt-4 self-stretch text-justify">
            {t('You last accepted version {version} on {date}.', {
              version: status.acceptedVersion ?? '',
              date: dates.date(status.acceptedAt),
            })}
          </Caption>
        )}
      </DialogBody>
      {/* Quitting is the alternative to accepting, so it sits where Cancel would. */}
      <DialogFooter>
        {!homeServer && (
          <Button variant="secondary" onClick={quitNow} disabled={busy}>
            {t('Quit Proxyma')}
          </Button>
        )}
        <Button
          variant="primary"
          onClick={() => {
            if (!accepted) {
              setError(t('You must accept the terms to continue.'))
              return
            }
            accept.mutate()
          }}
          disabled={busy}
          loading={accept.isPending}
        >
          {accept.isPending ? t('Saving...') : t('Accept and continue')}
        </Button>
      </DialogFooter>
    </ModalShell>
  )
}
