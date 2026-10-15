import { useQuery } from '@tanstack/react-query'
import {
  Caption, DialogBody, DialogHeader, Eyebrow, InlineNotice, KeyValue, KeyValueList, Skeleton, cn,
} from '@/components/ui/kit'
import { ModalShell } from '@/components/ui/ModalShell'
import { keys } from '@/services/queryKeys'
import { useTranslation } from '@/i18n'
import { useDates } from '@/hooks/useDates'
import { withReason } from '@/services/apiError'
import { ToolCallDetailModal } from '@/features/chat/components/ToolCallDetailModal'
import { JsonFieldTree } from '@/features/chat/components/JsonFieldTree'
import { jsonFields } from '@/features/chat/utils/jsonFields'
import { auditApi, type AuditEventDetail } from '../services/auditApi'
import {
  actionLabel, actorText, categoryLabel, durationText, outcomeLabel, outcomeTone, readableDetail,
  summaryText,
} from '../utils/auditLabels'

/**
 * One entry, opened. A TOOL CALL opens the dialog the chat's own tool rows open - its input and its
 * result as labelled fields - so a call reads the same in the log as in the conversation it was
 * made in. Every other entry is its facts, then whatever it was recorded with, as fields; nothing
 * here is printed as JSON.
 */
export function AuditEventDialog({ id, multiUser, onClose }: { id: string; multiUser: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const { data, isLoading, isError, error } = useQuery({
    queryKey: keys.auditEvent(id),
    queryFn: () => auditApi.event(id),
    staleTime: Infinity,
  })

  if (data && data.category === 'TOOL' && data.target) {
    const detail = data.detail ?? {}
    return (
      <ToolCallDetailModal
        toolName={data.target}
        arguments={typeof detail.arguments === 'string' ? detail.arguments : '{}'}
        result={typeof detail.result === 'string' ? detail.result : undefined}
        onClose={onClose}
      />
    )
  }

  return (
    <ModalShell
      onClose={onClose}
      size="lg"
      // As tall as the entry up to the window, the body the one region that scrolls (the kit's cap).
      cap
      ariaLabel={data ? actionLabel(data.action, t) : t('Log entry')}
    >
      <DialogHeader
        title={data ? actionLabel(data.action, t) : t('Log entry')}
        subtitle={data ? summaryText(data, t) || undefined : undefined}
        wrapTitle
      />
      {/* The recorded detail is of unknown length, so the body is the one region that scrolls. */}
      <DialogBody scroll className="space-y-5">
        {isLoading ? (
          <div className="space-y-2">
            <Skeleton className="h-4 w-2/3" />
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-4 w-3/5" />
          </div>
        ) : isError || !data ? (
          <InlineNotice tone="danger">{withReason(t('Could not load this entry'), error)}</InlineNotice>
        ) : (
          <EntryBody entry={data} multiUser={multiUser} />
        )}
      </DialogBody>
    </ModalShell>
  )
}

function EntryBody({ entry, multiUser }: { entry: AuditEventDetail; multiUser: boolean }) {
  const { t } = useTranslation()
  const dates = useDates()
  const tone = outcomeTone(entry.outcome)
  // Flags as Yes or No and capital codes as words; names, numbers and arguments as recorded.
  const fields = entry.detail ? jsonFields(JSON.stringify(readableDetail(entry.detail, t))) : null
  return (
    <>
      <KeyValueList layout="rows">
        <KeyValue layout="rows" label={t('When')} figures>{dates.dateTime(entry.at)}</KeyValue>
        <KeyValue layout="rows" label={t('Category')}>{categoryLabel(entry.category, t)}</KeyValue>
        <KeyValue layout="rows" label={t('Outcome')}>
          <span className={cn(
            tone === 'danger' && 'text-danger',
            tone === 'warning' && 'text-warning',
            tone === 'success' && 'text-brand-legible',
          )}>
            {outcomeLabel(entry.outcome, t)}
          </span>
        </KeyValue>
        {multiUser && (
          <KeyValue layout="rows" label={t('Who')}>{actorText(entry.actorKind, entry.actorId, entry.actorName, t)}</KeyValue>
        )}
        {entry.target && <KeyValue layout="rows" label={t('Target')}>{entry.target}</KeyValue>}
        {entry.durationMs != null && (
          <KeyValue layout="rows" label={t('Took')} figures>{durationText(entry.durationMs)}</KeyValue>
        )}
        {entry.conversationId && (
          <KeyValue layout="rows" label={t('Conversation')} figures>{entry.conversationId}</KeyValue>
        )}
      </KeyValueList>

      {fields && fields.length > 0 && (
        <div>
          <Eyebrow className="mb-1.5">{t('Recorded with it')}</Eyebrow>
          <JsonFieldTree fields={fields} />
        </div>
      )}
      {(!fields || fields.length === 0) && (
        <Caption>{t('Nothing else was recorded with this entry.')}</Caption>
      )}
    </>
  )
}
