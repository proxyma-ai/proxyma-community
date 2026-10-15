import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Button, Caption, ConfirmDialog, FadeSwap, KeyValue, KeyValueList, Presence, Skeleton,
} from '@/components/ui/kit'
import { useDates } from '@/hooks/useDates'
import { useTranslation } from '@/i18n'
import { withReason } from '@/services/apiError'
import { useToastStore } from '@/store/toastStore'
import { diagnosticsApi } from '@/features/settings/services/diagnosticsApi'
import { DiagnosticsPreviewModal } from '@/features/settings/components/DiagnosticsPreviewModal'
import { AuditTabBody } from './AuditShared'

/**
 * What "Send diagnostics to Proxyma" has collected and sent - Desktop and Home Server only, since
 * Enterprise Server never collects any. The switch itself stays in Settings > About > Privacy, beside
 * the policy that describes it; what it did is here: whether it is on, what is waiting, exactly what
 * would go next, and every send - with the report as it was sent - and every switch, failure and
 * deletion in the log below.
 */
export function DiagnosticsPanel() {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const [showPreview, setShowPreview] = useState(false)
  // Deleting what was collected asks first, as every destructive action in Settings does.
  const [confirmDelete, setConfirmDelete] = useState(false)
  const dates = useDates()
  const { data, isLoading } = useQuery({ queryKey: ['diagnostics-preview'], queryFn: diagnosticsApi.preview })
  const remove = useMutation({
    mutationFn: diagnosticsApi.deleteCollected,
    onSuccess: () => {
      setConfirmDelete(false)
      void qc.invalidateQueries({ queryKey: ['diagnostics-preview'] })
      void qc.invalidateQueries({ queryKey: ['audit'] })
      addToast(t('Collected diagnostics deleted'), 'success')
    },
    onError: (e: unknown) => addToast(withReason(t('Could not delete the collected diagnostics'), e), 'danger'),
  })
  const status = data?.status

  const state = !status ? null
    : status.sending ? t('On - sent once a day')
    : status.blockedByEdition ? t('Off - Proxyma Enterprise Server never sends diagnostics')
    : !status.configured ? t('Off - this build has nowhere to send them')
    : t('Off')

  return (
    <AuditTabBody
      tab="DIAGNOSTICS"
      title={t('Diagnostics')}
      caption={t('Error types, timings and enabled features - never your documents, questions, file names or account. The switch is in Settings > About > Privacy.')}
      multiUser={false}
      logCaption={t('Each send, failure, switch and deletion in the period, newest first. A send carries the report exactly as it went.')}
      summary={() => (
        <FadeSwap swapKey={isLoading || !status}>
        {isLoading || !status ? (
          <div className="space-y-2">
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-4 w-1/3" />
          </div>
        ) : (
          <div className="space-y-4">
            <KeyValueList layout="rows">
              <KeyValue layout="rows" label={t('Sending')}>{state}</KeyValue>
              <KeyValue layout="rows" label={t('Days waiting')} figures>{status.daysWaiting}</KeyValue>
              <KeyValue layout="rows" label={t('Last sent')} figures>
                {status.lastSentAt ? dates.dateTime(status.lastSentAt) : t('Not since the app started')}
              </KeyValue>
            </KeyValueList>
            <div className="flex flex-wrap gap-x-4 gap-y-1">
              <Button variant="hyperlink" onClick={() => setShowPreview(true)}>{t('See what would be sent')}</Button>
              {/* Destructive, so the danger ink - see the note this came from in Privacy. */}
              <Button variant="danger-quiet" disabled={remove.isPending} onClick={() => setConfirmDelete(true)}>
                {t('Delete collected data')}
              </Button>
            </div>
            <Presence show={!status.switchedOn}>
              <Caption>{t('Nothing is collected while the switch is off; the preview shows what today would contain.')}</Caption>
            </Presence>
            {showPreview && <DiagnosticsPreviewModal onClose={() => setShowPreview(false)} />}
            {confirmDelete && (
              <ConfirmDialog
                title={t('Delete collected data?')}
                description={t('Diagnostics waiting to be sent are deleted, and nothing already sent is affected.')}
                confirmLabel={t('Delete')}
                cancelLabel={t('Cancel')}
                pendingLabel={t('Deleting...')}
                pending={remove.isPending}
                onConfirm={() => remove.mutate()}
                onCancel={() => setConfirmDelete(false)}
              />
            )}
          </div>
        )}
        </FadeSwap>
      )}
    />
  )
}
