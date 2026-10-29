import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, X } from 'lucide-react'
import {
  Button, Caption, CodeBlock, ExternalLink, FadeSwap, Field, MetaList, Presence, ProgressRow, Readout,
  RefreshIcon, Select, SettingsSection, SettingsSections, StatusMark, SwitchRow, TEXT,
} from '@/components/ui/kit'
import { useToastStore } from '@/store/toastStore'
import { useRestartStore } from '@/store/restartStore'
import { updateApi, type UpdateStatus } from '../services/updateApi'
import { formatFileSize } from '@/utils/formatters'
import { configApi } from '@/services/configApi'
import { systemApi } from '@/services/systemApi'
import { SignpostSection, TabFieldsSkeleton, useResolved } from './shared'
import { useDates } from '@/hooks/useDates'
import type { ExtraPageProps } from './types'
import { cn } from '@/utils/cn'
import { useTranslation, type Translate } from '@/i18n'
import { withReason } from '@/services/apiError'
import { serverText } from '@/i18n/serverText'

// Updates, DESKTOP edition.
//
// The machinery has existed since 1.0: UpdateService checks the releases API, downloads the update
// ZIP, stages it, and exits with code 3 so the launcher restarts into the new build. NONE OF IT WAS
// REACHABLE. There was no page, no button and no readout - the only setting was one boolean nobody
// could see, and the only way to know an update had been found was to read the log.
//
// So this is a surface over an engine, not a new mechanism, and it says the things the engine
// actually knows: which version is running, when it last looked, what it found, and what it did.
//
// SERVER HAS NO SUCH TAB, deliberately. A server deployment is a container the operator upgrades
// with `install.sh` or a `docker pull` - replacing files under a running JVM is not how it is
// updated, and offering a button that cannot work is worse than offering none. The endpoints stay
// available in both editions; only the page is edition-scoped.
const FREQUENCIES = [
  { value: '0',   label: 'Never - only when I ask' },
  { value: '6',   label: 'Every 6 hours' },
  { value: '24',  label: 'Once a day' },
  { value: '168', label: 'Once a week' },
]

function statusLine(status: string, availableVersion: string, canApplyInPlace: boolean, t: Translate): string {
  switch (status) {
    case 'CHECKING':    return t('Checking...')
    case 'DOWNLOADING': return t('Downloading Proxyma {version}', { version: availableVersion })
    case 'AVAILABLE':
      return canApplyInPlace
        ? t('Proxyma {version} is available', { version: availableVersion })
        : t('Proxyma {version} is available to download', { version: availableVersion })
    case 'READY':       return t('Proxyma {version} is ready to install', { version: availableVersion })
    case 'ERROR':       return t('The last check did not finish')
    default:            return t('Proxyma is up to date')
  }
}

// The bar under a DOWNLOADING status. It used to be a spinner and the word "Downloading..." for a
// transfer of several hundred megabytes, with nothing to say whether it was 5% in or stuck.
//
// PREPARING - reading and verifying the release, before anything is known about its size - is a
// travelling wave with a sentence, never a byte count: "0 B" beside a bar that has moved reads as
// broken, which is how it looked before. Once the size is known the bar is determinate, with the
// same wave moving along the filled part that every other progress bar in the app has. The kit
// ProgressBar draws both: no value is the indeterminate wave.
function DownloadProgress({ status, onCancel, cancelling, t }: {
  status: UpdateStatus; onCancel: () => void; cancelling: boolean; t: Translate
}) {
  const total = status.downloadTotalBytes
  const done = status.downloadedBytes
  const known = status.downloadStage === 'TRANSFERRING' && total > 0
  const pct = known ? Math.min(100, Math.floor((done * 100) / total)) : null

  const detail = known
    ? t('{done} of {total}', { done: formatFileSize(done), total: formatFileSize(total) })
    : t('Preparing the download...')

  // The kit's ProgressRow: the words at the left, the figures and the percent at the right, the bar
  // under both.
  return (
    <div className="mt-4 max-w-md">
      <ProgressRow label={t('Update download')} figures={detail} value={pct ?? undefined} />
      <div className="mt-3">
        <Button variant="secondary" onClick={onCancel} disabled={cancelling}>
          <X size={14} aria-hidden="true" />
          {cancelling ? t('Cancelling...') : t('Cancel download')}
        </Button>
      </div>
    </div>
  )
}

// The Home Server is a container: it cannot replace its own image, so an update it finds is shown
// as the two lines that install it. The version is the image tag - the release tag without its "v".
// A command a reader is told to run carries a copy button, like every command in the product.
function DockerUpgrade({ version, t }: { version: string; t: Translate }) {
  const tag = version.replace(/^v/i, '')
  return (
    <div className="mt-3">
      <Caption>
        {t('In the install directory, set this line in .env, then run the two commands:')}
      </Caption>
      <CodeBlock
        className="mt-2"
        text={`PROXYMA_IMAGE=ghcr.io/proxyma-ai/proxyma-homeserver:${tag}\ndocker compose pull proxyma\n./install.sh`}
        copyLabel={t('Copy')}
        copiedLabel={t('Copied')}
      />
    </div>
  )
}

export function UpdatesPage({ trackChange, pending, onOpenTab }: ExtraPageProps) {
  const { t } = useTranslation()
  // The primed ['config'] query the About tab reads - no request of its own.
  const { data: config } = useQuery({ queryKey: ['config'], queryFn: configApi.get, staleTime: Infinity, retry: false })
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const beginRestart = useRestartStore(s => s.begin)
  const endRestart = useRestartStore(s => s.end)
  const dates = useDates()

  // Polls while the tab is open, because a check and a download both finish on their own schedule
  // and neither pushes an event. Cheap: two in-memory fields and a bounded file read.
  //
  // Every second while a download runs, so the bar moves; every five otherwise. The mutation below
  // also refetches as it starts, so the bar appears without waiting out the slower interval.
  const { data: status, isLoading } = useQuery({
    queryKey: ['update-status'],
    queryFn: updateApi.status,
    refetchInterval: query => (query.state.data?.status === 'DOWNLOADING' ? 1000 : 5000),
  })
  const frequency = useResolved('updates', 'check-frequency-hours', pending, '24')
  const autoUpdate = useResolved('updates', 'auto-update', pending, 'true') === 'true'
  const devBuilds = useResolved('updates', 'dev-builds', pending, 'false') === 'true'

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['update-status'] })
  }

  const check = useMutation({
    mutationFn: updateApi.check,
    onSuccess: refresh,
    onError: (e: unknown) => addToast(withReason(t('Could not check for updates'), e), 'danger'),
  })

  // The POST returns as soon as the download has STARTED; the bar comes from the polled status.
  const download = useMutation({
    mutationFn: updateApi.download,
    onSuccess: refresh,
    onError: (e: unknown) => addToast(withReason(t('Could not download the update'), e), 'danger'),
  })

  // Asked, until the status stops saying DOWNLOADING: the POST returns at once, and the download
  // takes a moment to stop - a button that came straight back read as a cancel that did nothing.
  const [cancelAsked, setCancelAsked] = useState(false)
  const cancel = useMutation({
    mutationFn: updateApi.cancel,
    onSuccess: () => { setCancelAsked(true); refresh() },
    onError: (e: unknown) => addToast(withReason(t('Could not cancel the download'), e), 'danger'),
  })

  // The request is EXPECTED not to answer: the process exits ~200ms after accepting it, so the
  // connection drops before a response is written. Treating that as a failure would show an error
  // over a restart that is working - the same reasoning ResetPage documents for its own restarts.
  // A refusal WITH a status is a real one, though: the backend is still up and said no.
  //
  // Then it waits for the app to go and come back, and reloads - as every other restart does. It
  // did not until 2026-09-27: the desktop window lives across the restart (desktop-shell/main.js),
  // so the restart screen stayed up over a new, working backend until someone pressed F5.
  const apply = useMutation({
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
      addToast(t('Could not restart into the update. Restart Proxyma manually.'), 'danger')
    },
  })

  if (isLoading || !status) return <TabFieldsSkeleton count={4} />

  // A DOWNLOAD IS NOT A CHECK, and the page used to show it as one: the check spinner ran for the
  // whole transfer, because a check with auto-update on held its request open until the download
  // finished. Downloads now run in the background, and while one runs the page shows the bar and a
  // Cancel button and nothing else.
  //
  // check.isPending still covers a manual check's own request - CHECKING is essentially never seen
  // by a poll for one, since the check finishes before the next status refetch.
  const downloading = status.status === 'DOWNLOADING'
  // The download has stopped, so the next one starts with a working Cancel button.
  if (!downloading && cancelAsked) setCancelAsked(false)
  const checking = !downloading && (status.status === 'CHECKING' || check.isPending)

  // Tested against the VALUE, not against statusLine's default branch. That default is a
  // fallback for a status this build does not know about, and an unrecognized status is exactly
  // the case that must not be decorated with a green tick saying everything is fine.
  const isUpToDate = status.status === 'UP_TO_DATE'

  return (
    <SettingsSections>
      <div>
        <div className={cn('flex items-center gap-2', TEXT.section)}>
          {/* The one status worth a glyph, and only this one. UP_TO_DATE is the state a person
              opens this page hoping to confirm, and it is the only line here that is purely good
              news - AVAILABLE and READY are calls to action the buttons below already carry, and
              ERROR has its own warning-coloured detail line. The kit's StatusMark, the health
              glyph every source and backup wears. */}
          <Presence show={isUpToDate} collapse={false}>
            <StatusMark state="ok" />
          </Presence>
          {statusLine(status.status, status.availableVersion, status.canApplyInPlace, t)}
        </div>
        <MetaList
          className="text-xs text-ink-caption mt-1"
          items={[
            <>{t('You are running')}&nbsp;<Readout tone="default">{status.currentVersion}</Readout></>,
            status.lastCheckAt
              ? t('last checked {time}', { time: dates.dateTime(status.lastCheckAt) })
              : t('not checked yet in this session'),
          ]}
        />

        <Presence show={!!status.lastError}>
          {status.lastError && <Caption tone="warning" className="mt-2">{serverText(status.lastError)}</Caption>}
        </Presence>

        {/* The progress and the buttons take turns; each fades in where the other was. */}
        <FadeSwap swapKey={downloading}>
          {downloading ? (
            <DownloadProgress status={status} onCancel={() => cancel.mutate()} cancelling={cancel.isPending || cancelAsked} t={t} />
          ) : (
            <div className="flex flex-wrap items-center gap-2 mt-4">
              <Button variant="secondary" onClick={() => check.mutate()} disabled={checking}>
                <RefreshIcon size={14} spinning={checking} />
                {t('Check now')}
              </Button>

              {/* Only when there is something to download and this platform installs in place. With
                  auto-update on, the download starts by itself and this never appears. */}
              <Presence show={status.status === 'AVAILABLE' && status.canApplyInPlace} collapse={false}>
                <Button onClick={() => download.mutate()} disabled={download.isPending}>
                  <Download size={14} aria-hidden="true" />
                  {t('Download {version}', { version: status.availableVersion })}
                </Button>
              </Presence>

              <Presence show={status.status === 'READY'} collapse={false}>
                <Button onClick={() => apply.mutate()} disabled={apply.isPending}>
                  {t('Restart and install {version}', { version: status.availableVersion })}
                </Button>
              </Presence>
            </div>
          )}
        </FadeSwap>

        {/* Said where the missing button would be, rather than left as an absence.
            This used to cover all of Linux and no longer does: a .deb hands off to the system
            installer, so what is left here is an installation Proxyma
            cannot identify - somebody running the exec jar directly, or a package a third party
            built - plus macOS, which has no build yet. */}
        <Presence show={status.status === 'AVAILABLE' && !status.canApplyInPlace}>
          <>
            <Caption className="mt-3">
              {t('This installation updates by downloading the new release, not in place.')}{' '}
              {/* /docs/manual/ was never a page. This tab is desktop-profile only: Home Server or Desktop. */}
              <ExternalLink href="https://proxyma.ai/docs/manual/#updates">{t('How to update')}</ExternalLink>
            </Caption>
            {config?.homeServer && <DockerUpgrade version={status.availableVersion} t={t} />}
          </>
        </Presence>
      </div>

      {/* Headed like every other section of the tab - it was the one section with no heading. */}
      <SettingsSection title={t('Automatic updates')}>
        {/* The Field's render function ties the Select to its label - it was a plain child, so the
            label pointed at nothing and the Select had no name. */}
        <Field
          label={t('Check for updates')}
          hint={t('How often to look for a newer version; checking downloads nothing.')}
        >
          {id => (
            <Select
              id={id}
              value={frequency}
              onChange={v => trackChange('updates', 'check-frequency-hours', v)}
              options={FREQUENCIES.map(o => ({ ...o, label: t(o.label) }))}
            />
          )}
        </Field>

        <SwitchRow
          className="py-0"
          title={t('Download updates automatically')}
          caption={t('Download new versions when found; each installs the next time Proxyma starts.')}
          checked={autoUpdate}
          onChange={v => trackChange('updates', 'auto-update', String(v))}
        />

        <SwitchRow
          className="py-0"
          title={t('Development builds')}
          caption={t('Also offer development builds, which may be broken.')}
          checked={devBuilds}
          onChange={v => trackChange('updates', 'dev-builds', String(v))}
        />
      </SettingsSection>

      {/* THE HISTORY MOVED TO LOGS on 2026-10-12, under Activity > Updates, with every other
          log this installation keeps. A section that silently vanished would read as a regression,
          so its heading stays as the way there. */}
      <SignpostSection
        title={t('History')}
        caption={t('Every update found, installed or failed is in Logs, under Activity.')}
        action={(
          <Button variant="hyperlink" onClick={() => onOpenTab('Logs', 'activity')}>
            {t('Open Logs')}
          </Button>
        )}
      />
    </SettingsSections>
  )
}
