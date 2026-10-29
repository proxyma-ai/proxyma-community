import { useState, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  BarList, Button, ConfirmDialog, FieldLabel, InlineNotice, MetaList, Presence, SectionHeading,
} from '@/components/ui/kit'
import { keys } from '@/services/queryKeys'
import { useTranslation } from '@/i18n'
import { withReason } from '@/services/apiError'
import { useToastStore } from '@/store/toastStore'
import { auditApi, type AuditTab } from '../services/auditApi'
import { AuditEventLog } from './AuditEventLog'
import { PeriodSelect } from './PeriodSelect'

export { PeriodSelect }

/** A tab's summary for the period, kept on screen while another period loads. */
export function useAuditSummary(tab: AuditTab, days: number) {
  return useQuery({
    queryKey: keys.auditSummary({ tab, days }),
    queryFn: () => auditApi.summary(tab, days),
    placeholderData: prev => prev,
  })
}

/**
 * A tab: its summary (whatever the panel draws from it) under a heading carrying the period, then
 * the entries themselves under "Logs" - the shape of the LLM call log, so the page's tabs read alike.
 *
 * A FRAGMENT OF SECTIONS since 2026-10-14: the page (AuditLogs) lays them out in SettingsSections,
 * so the summary, anything extra and the log are ruled off with the same rhythm as every Settings
 * tab - in Settings and on Administration alike. It was `space-y-8` here, so the one component
 * looked different from the tabs around it.
 */
export function AuditTabBody({ tab, title, caption, multiUser, summary: drawSummary, logCaption, extra }: {
  tab: AuditTab
  title: string
  caption?: ReactNode
  multiUser: boolean
  summary?: (data: NonNullable<ReturnType<typeof useAuditSummary>['data']>, days: number) => ReactNode
  logCaption: string
  /** Drawn between the summary and the log - the diagnostics status, the in-memory matched text. */
  extra?: ReactNode
}) {
  const { t } = useTranslation()
  const [days, setDays] = useState(30)
  const { data, isError, error } = useAuditSummary(tab, days)
  return (
    <>
      <section className="space-y-6">
        <SectionHeading flush caption={caption} action={<PeriodSelect days={days} onChange={setDays} />}>
          {title}
        </SectionHeading>
        <Presence show={isError}>
          <InlineNotice tone="danger">{withReason(t('Could not load the summary'), error)}</InlineNotice>
        </Presence>
        {data && drawSummary?.(data, days)}
      </section>
      {extra}
      <section>
        <SectionHeading
          caption={logCaption}
          action={data?.canClear ? <ClearAuditButton tab={tab} /> : undefined}
        >
          {/* Not "Logs": that is the page's own title, right above (the UI review, 2026-10-28). */}
          {t('Entries')}
        </SectionHeading>
        <AuditEventLog tab={tab} days={days} summary={data} multiUser={multiUser} />
      </section>
    </>
  )
}

/**
 * Clears one tab, on Desktop and Home Server - the server refuses it on Enterprise Server, where
 * retention alone removes entries, and does not offer it there (`canClear`). The clear is itself
 * recorded, under Activity.
 */
function ClearAuditButton({ tab }: { tab: AuditTab }) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const [confirming, setConfirming] = useState(false)
  const clear = useMutation({
    mutationFn: () => auditApi.clear(tab),
    onSuccess: () => {
      setConfirming(false)
      void qc.invalidateQueries({ queryKey: ['audit'] })
      addToast(t('Log cleared'), 'success')
    },
    onError: (e: unknown) => addToast(withReason(t('Could not clear the log'), e), 'danger'),
  })
  return (
    <>
      <Button variant="danger-quiet" onClick={() => setConfirming(true)}>{t('Clear log')}</Button>
      {confirming && (
        <ConfirmDialog
          title={t('Clear this log?')}
          description={t('Every entry on this tab is deleted, in every period.')}
          consequences={[t('The clear itself is recorded under Activity.')]}
          confirmLabel={t('Clear log')}
          cancelLabel={t('Cancel')}
          pendingLabel={t('Clearing...')}
          pending={clear.isPending}
          onConfirm={() => clear.mutate()}
          onCancel={() => setConfirming(false)}
        />
      )}
    </>
  )
}

/**
 * A short ranked list - which tools, which rules fired: the kit's BarList under its name, a bar per
 * row scaled to the largest, its figures beside it. Eases in once there is anything to rank.
 */
export function RankedList({ title, rows }: {
  title: string
  rows: { key: string; label: string; value: number; figures: string[] }[]
}) {
  return (
    <Presence show={rows.length > 0}>
      <div>
        <FieldLabel as="p">{title}</FieldLabel>
        <BarList
          label={title}
          share={false}
          rows={rows.map(r => ({ key: r.key, label: r.label, value: r.value, figures: <MetaList items={r.figures} /> }))}
        />
      </div>
    </Presence>
  )
}
