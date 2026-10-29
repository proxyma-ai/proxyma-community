import { Field, FormColumns, Input, SettingsSections } from '@/components/ui/kit'
import { useSettings } from '../hooks/useSettings'
import { TabFieldsSkeleton, useResolved } from '../pages/shared'
import { FEATURES } from '@/config/features'
import { useTranslation } from '@/i18n'
import type { TabProps } from '../pages/shared'

// - Data Retention tab -
//
// Several of these tables had no expiry at all: one row per chat turn, one per search, one per
// platform LLM call, plus every piece of feedback ever submitted. On a busy deployment they are
// the fastest-growing tables in the database and nothing ever removed a row.
//
// It also surfaces a number that used to be hardcoded. Idle conversations were deleted after seven
// days with no setting and nothing in the UI saying so.
export function DataRetentionTab({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const { isLoading } = useSettings('retention')
  const conversationDays = useResolved('retention', 'conversation-days', pending, '7')
  const evaluationDays = useResolved('retention', 'evaluation-days', pending, '90')
  const usageDays = useResolved('retention', 'usage-days', pending, '365')
  const auditDays = useResolved('retention', 'audit-days', pending, '90')
  const feedbackDays = useResolved('retention', 'feedback-days', pending, '90')

  if (isLoading) return <TabFieldsSkeleton count={4} />

  const field = (label: string, hint: string, key: string, value: string) => (
    <Field label={t(label)} hint={t(hint)}>
      {id => (
        <Input
          id={id}
          type="number"
          value={value}
          onChange={e => trackChange('retention', key, e.target.value)}
          placeholder="0"
        />
      )}
    </Field>
  )

  return (
    <SettingsSections intro={<>
      {t('How long each kind of data is kept, swept daily.')}{' '}
      <strong className="text-ink-secondary">{t('0 keeps it forever')}</strong>.
    </>}>
      {/* Three columns, so Desktop's three fields fill one row rather than leaving a cell empty. */}
      <FormColumns cols={3} from="md">
        {field('Conversations (days)',
               'Idle conversations; pinned ones are kept.',
               'conversation-days', conversationDays)}
        {FEATURES.auth && field('Evaluation data (days)',
               'Per-turn and per-search records.',
               'evaluation-days', evaluationDays)}
        {field('Usage records (days)',
               'Every model call and its cost.',
               'usage-days', usageDays)}
        {field('Logs (days)',
               'Tool calls, guardrail checks and activity.',
               'audit-days', auditDays)}
        {FEATURES.auth && field('Feedback (days)',
               'Submitted feedback and its attachments.',
               'feedback-days', feedbackDays)}
      </FormColumns>
    </SettingsSections>
  )
}

