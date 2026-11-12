import type { ReactNode } from 'react'
import { Field, SecretInput, cn } from '@/components/ui/kit'
import { EditableText } from '@/components/ui/EditableText'
import { useTranslation } from '@/i18n'
import { useSettingsSaver } from '../pages/shared'
import { useSettings } from '../hooks/useSettings'
import { useSettingGroup } from '../hooks/useSettingGroup'
import { normalizedNumber, numberProblem, type NumberRule } from '../utils/settingNumbers'
import { pendingValue } from '../utils/settingsSave'
import { useSecretLabels } from './settingsParts'

// THE SETTINGS WINDOW'S FIELDS, SAVED AS THEY ARE LEFT (the maintainer's click-to-edit rule,
// 2026-11-02). A text or a number is the shared EditableText - read as text, edited where it is read,
// saved on blur, Escape putting it back. A secret keeps its masked input and saves on blur, a blank
// one never sent. Fields meaningless apart save as one when the person leaves the group
// (hooks/useSettingGroup.ts). What a save does - the toast on failure, the revert, the "Saved."
// beside the title - is the window's (SettingsModal).

/** A number's problem as a sentence, or null - the validation a number field shows and saves by. */
export function useNumberCheck(rule: NumberRule | undefined) {
  const { t } = useTranslation()
  if (!rule) return undefined
  return (text: string): string | null => {
    // Empty is `required`'s to say.
    if (text.trim() === '') return null
    const p = numberProblem(text, rule)
    if (!p) return null
    switch (p.kind) {
      case 'notNumber': return t('Enter a number.')
      case 'notWhole': return t('Enter a whole number.')
      case 'tooSmall': return t('Enter {min} or more.', { min: p.min })
      case 'tooLarge': return t('Enter {max} or less.', { max: p.max })
    }
  }
}

/**
 * A SETTING'S TEXT OR NUMBER, labelled: the field's name and hint over an EditableText. Plain text for
 * a reader who cannot change it (`readOnly`, from the window). With `number`, what is typed is checked
 * against the rule before anything is saved - a wrong value keeps the editor open on why, an emptied
 * one is refused, and Escape puts the saved one back; it is stored without padding ("007" is 7).
 *
 * `onCommit` saves (useSaveSetting, whose promise the text shows settling and a refusal reverts) or,
 * inside a group, records the edit (useSettingGroup's `set`).
 */
export function SettingText({ label, hint, value, onCommit, placeholder, number, multiline, maxLength }: {
  label: string
  hint?: ReactNode
  value: string
  onCommit: (text: string) => void | Promise<unknown>
  placeholder?: string
  number?: NumberRule
  multiline?: boolean
  maxLength?: number
}) {
  const { t } = useTranslation()
  const { readOnly } = useSettingsSaver()
  const check = useNumberCheck(number)
  return (
    <Field label={label} hint={hint}>
      <EditableText
        value={value}
        onCommit={text => onCommit(number ? normalizedNumber(text) : text)}
        editable={!readOnly}
        multiline={multiline}
        label={label}
        placeholder={placeholder}
        maxLength={maxLength}
        validate={check}
        refuseInvalid={!!number}
        required={number ? t('Enter a number.') : undefined}
        inputMode={number ? (number.whole ? 'numeric' : 'decimal') : undefined}
        // A field's text as FIELD_BASE writes it - 13px, primary ink - so the value reads the same at
        // rest and in its editor; figures in the readout face.
        className={cn('text-sm text-ink-primary', number && 'data-readout')}
      />
    </Field>
  )
}

/**
 * A STORED SECRET - an API key, a client secret. Exempt from click-to-edit: the kit's masked
 * SecretInput, as before, but saved when it is left, since there is no Save bar - a group of one. A
 * blank one is never sent: blank means "keep the stored one" (shown masked, so it cannot be emptied by
 * accident).
 */
export function SettingSecret({ prefix, field, label, hint, placeholder, autoFocus }: {
  prefix: string
  field: string
  label: string
  hint?: ReactNode
  placeholder?: string
  autoFocus?: boolean
}) {
  const group = useSettingGroup()
  const { pending } = useSettingsSaver()
  const secretLabels = useSecretLabels()
  const { data } = useSettings(prefix)
  const stored = data?.[field] ? String(data[field]) : ''
  // While a new one saves it stays in the box (as dots), rather than the old preview coming back.
  const saving = pendingValue(pending, prefix, field)
  return (
    <Field label={label} hint={hint}>
      {id => (
        <div {...group.bind}>
          <SecretInput
            id={id}
            value={group.value(prefix, field, saving ?? stored)}
            serverValue={stored}
            onChange={v => group.set({ prefix, field, value: v }, { secret: true, saved: stored })}
            placeholder={placeholder}
            autoFocus={autoFocus}
            labels={secretLabels}
          />
        </div>
      )}
    </Field>
  )
}
