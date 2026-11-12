import { useState } from 'react'
import { Button, Caption, FormColumns, Input, Presence, SettingsSections } from '@/components/ui/kit'
import { useSettings } from '../hooks/useSettings'
import { TabFieldsSkeleton, TitleAction, useSettingsSaver } from '../pages/shared'
import { FEATURES } from '@/config/features'
import { useTranslation } from '@/i18n'
import type { TabProps } from '../pages/shared'
import { useNumberCheck } from '../components/SettingFields'
import { DAYS } from '../utils/settingNumbers'
import { lowered, stagedChanges, stagedProblems } from '../utils/stagedSave'

// - Data Retention tab -
//
// Several of these tables had no expiry at all: one row per chat turn, one per search, one per
// platform LLM call, plus every piece of feedback ever submitted. On a busy deployment they are
// the fastest-growing tables in the database and nothing ever removed a row.
//
// It also surfaces a number that used to be hardcoded. Idle conversations were deleted after seven
// days with no setting and nothing in the UI saying so.
//
// SAVED BY A BUTTON, NOT AS A FIELD IS LEFT (the maintainer, 2026-11-10: "For danger/destructive actions, it should
// always use explicit Save button"): a lower number deletes data at the next daily sweep, so the values are held as typed
// until Save on the title row, which is said under the fields when a limit goes down. Every other tab saves a field as
// it is left.

/** Each kind of data's field, with the backend's own default (RetentionSettings) while none is stored. */
const FIELDS = [
  { field: 'conversation-days', fallback: '7', server: false },
  { field: 'evaluation-days', fallback: '90', server: true },
  { field: 'usage-days', fallback: '365', server: false },
  { field: 'audit-days', fallback: '90', server: false },
  { field: 'feedback-days', fallback: '90', server: true },
] as const

export function DataRetentionTab(_props: TabProps) {
  const { t } = useTranslation()
  const { data, isLoading } = useSettings('retention')
  const { save, readOnly } = useSettingsSaver()
  const check = useNumberCheck(DAYS)
  const [typed, setTyped] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [tried, setTried] = useState(false)

  if (isLoading) return <TabFieldsSkeleton count={4} />

  const shown = FIELDS.filter(f => !f.server || FEATURES.auth)
  const saved: Record<string, string> = Object.fromEntries(shown.map(f => {
    const v = data?.[f.field]
    return [f.field, v != null && v !== '' ? String(v) : f.fallback]
  }))
  const changes = stagedChanges(saved, typed)
  const problems = stagedProblems(typed, DAYS)
  const dirty = Object.keys(changes).length > 0 || problems.length > 0
  const deletes = lowered(saved, changes).length > 0

  const onSave = async () => {
    setTried(true)
    if (problems.length > 0 || Object.keys(changes).length === 0) return
    setSaving(true)
    try {
      await save(Object.entries(changes).map(([field, value]) => ({ prefix: 'retention', field, value })))
      setTyped({})
      setTried(false)
    } catch {
      // The saver has said why in a toast and put the saved values back; what was typed stays to try again.
    } finally {
      setSaving(false)
    }
  }

  const label = (field: string) => {
    switch (field) {
      case 'conversation-days': return t('Conversations (days)')
      case 'evaluation-days': return t('Evaluation data (days)')
      case 'usage-days': return t('Usage records (days)')
      case 'audit-days': return t('Logs (days)')
      default: return t('Feedback (days)')
    }
  }
  const hint = (field: string) => {
    switch (field) {
      case 'conversation-days': return t('Idle conversations; pinned ones are kept.')
      case 'evaluation-days': return t('Per-turn and per-search records.')
      case 'usage-days': return t('Every model call and its cost.')
      case 'audit-days': return t('Tool calls, guardrail checks and activity.')
      default: return t('Submitted feedback and its attachments.')
    }
  }

  return (
    <>
      {!readOnly && (
        <TitleAction>
          <Presence show={dirty} collapse={false} className="flex items-center gap-2">
            <Button variant="secondary" disabled={saving} onClick={() => { setTyped({}); setTried(false) }}>{t('Cancel')}</Button>
            <Button loading={saving} onClick={() => void onSave()}>{t('Save')}</Button>
          </Presence>
        </TitleAction>
      )}
      <SettingsSections intro={<>
        {t('How long each kind of data is kept, swept daily.')}{' '}
        <strong className="text-ink-secondary">{t('0 keeps it forever')}</strong>.
      </>}>
        {/* Three columns, so Desktop's three fields fill one row rather than leaving a cell empty. */}
        <FormColumns cols={3} from="md">
          {shown.map(f => {
            const text = typed[f.field] ?? saved[f.field]
            const problem = text.trim() === '' ? t('Enter a number.') : check?.(text) ?? null
            return (
              <Input
                key={f.field}
                label={label(f.field)}
                hint={hint(f.field)}
                value={text}
                inputMode="numeric"
                disabled={readOnly || saving}
                error={tried || f.field in typed ? problem ?? undefined : undefined}
                onChange={e => setTyped(prev => ({ ...prev, [f.field]: e.target.value }))}
                className="data-readout"
              />
            )
          })}
        </FormColumns>
        <Presence show={deletes}>
          <Caption tone="warning">{t('Data older than a lowered limit is deleted at the next daily sweep.')}</Caption>
        </Presence>
      </SettingsSections>
    </>
  )
}
