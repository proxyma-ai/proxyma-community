import { useQuery } from '@tanstack/react-query'
import {
  Caption, CodeBlock, CopyButton, DialogBody, DialogDescription, DialogFooter, DialogHeader, FadeSwap, InlineNotice, Presence,
  Skeleton,
} from '@/components/ui/kit'
import { ModalShell } from '@/components/ui/ModalShell'
import { useTranslation } from '@/i18n'
import { useDates } from '@/hooks/useDates'
import { diagnosticsApi } from '../services/diagnosticsApi'

/**
 * "See what would be sent": the diagnostics report, verbatim, as JSON.
 *
 * NOT A SUMMARY OF THE REPORT - THE REPORT. The server builds it with the same call the daily send
 * makes, and this renders it untouched, so what a person reads here cannot drift from what leaves
 * the machine. It includes today, which a real send never does until the day is over (UTC); the
 * line under the status says so rather than hiding the day being collected.
 *
 * Shown whether sending is on or off. Off is when it matters most: it is how someone decides to
 * turn it on.
 */
export function DiagnosticsPreviewModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const dates = useDates()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['diagnostics-preview'],
    queryFn: diagnosticsApi.preview,
    // A fresh build each time the dialog opens - the point is to see what is true now.
    staleTime: 0,
    gcTime: 0,
  })

  const status = data?.status
  const json = data ? JSON.stringify(data.report, null, 2) : ''

  // ModalShell like every app dialog (it was the one settings dialog on the kit Modal, with a
  // smaller title than its siblings). A fixed height: the report is of unknown length, so it is the
  // one region that scrolls.
  return (
    <ModalShell onClose={onClose} size="xl" fill ariaLabel={t('What would be sent')}>
      <DialogHeader title={t('What would be sent')} />
      <DialogBody className="flex-1 min-h-0 flex flex-col gap-3">
        <Presence show={!!isError}>
          <InlineNotice tone="danger" size="md">{t('Could not build the report. Try again in a moment.')}</InlineNotice>
        </Presence>
        <Presence show={!!status}>
          {status && (
          <div className="space-y-1">
            <DialogDescription className="text-ink-primary">
              {status.sending
                ? t('Sending is on. {count} finished day is waiting to be sent.|Sending is on. {count} finished days are waiting to be sent.', { count: status.daysWaiting })
                : status.blockedByEdition
                  ? t('Proxyma Enterprise Server never sends diagnostics, so nothing here is stored or sent.')
                  : !status.configured
                    ? t('This installation has no diagnostics address, so nothing here is stored or sent.')
                    : t('Sending is off, so nothing is stored or sent. This is what would be sent if you turned it on.')}
            </DialogDescription>
            <Caption>
              {t('Today ({day}, UTC) is still being collected and is sent only after it ends. Days are sent together, about once a day.', { day: status.today })}
            </Caption>
            {status.lastSentAt && (
              <Caption>
                {t('Last sent: {time}', { time: dates.dateTime(status.lastSentAt) })}
              </Caption>
            )}
          </div>
          )}
        </Presence>
        {/* Wrapped, not scrolled sideways: a region that scrolls both ways cannot hand the wheel to
            its horizontal axis, and no horizontal scrollbar is shown anywhere. Indentation survives.
            The kit CodeBlock's `fill` makes it the dialog's one scrolling region; it fades in over
            its skeleton. */}
        <FadeSwap swapKey={isLoading ? 'loading' : !!data} className="flex-1 min-h-0 flex flex-col">
          {isLoading ? <Skeleton className="h-40 w-full" /> : data ? <CodeBlock text={json} wrap fill /> : null}
        </FadeSwap>
      </DialogBody>
      {/* Copy is the footer's one action; it had a Close beside it, which the backdrop and Escape
          already are (.claude/rules/frontend.md: a footer only for real actions). */}
      {data && (
        <DialogFooter>
          {/* The kit's copy - a tick for two seconds, and the textarea fallback where the clipboard
              API is refused. The button this replaces copied silently, or not at all over http. */}
          <CopyButton variant="button" getText={() => json} label={t('Copy')} copiedLabel={t('Copied')} />
        </DialogFooter>
      )}
    </ModalShell>
  )
}
