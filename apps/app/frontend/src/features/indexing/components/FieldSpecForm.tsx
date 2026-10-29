import {
  Checkbox, Disclosure, Field, FormStack, Input, ListEditor, SecretInput, Select, SwitchRow, Textarea,
} from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import type { FieldSpec } from '../services/catalogApi'
import { splitAdvanced, type FieldProblem, type FieldValues } from '../utils/fieldForm'

type Translate = ReturnType<typeof useTranslation>['t']

/** The sentence under a refused field, in the reader's language. */
export function fieldProblemText(problem: FieldProblem | undefined, t: Translate): string | undefined {
  if (!problem) return undefined
  switch (problem.code) {
    case 'required': return t('Required')
    case 'number':
      return problem.min != null && problem.max != null
        ? t('Must be a whole number between {min} and {max}', { min: problem.min, max: problem.max })
        : t('Must be a whole number')
    case 'url': return t('Must start with http:// or https://')
    case 'option': return t('Choose one of the options')
    case 'pattern':
      return problem.example
        ? t('Not in the form expected, for example {example}', { example: problem.example })
        : t('Not in the form expected')
    case 'oneLine': return t('Must be on one line')
  }
}

/**
 * A descriptor's fields as a form - one kit form part per field type, the advanced ones behind
 * "More options". The field's label and help come from the server in English and are translated
 * here (i18n/connectorLabels.generated.ts lists them); a placeholder is an example of a value and
 * is shown as it is.
 *
 * <p>Owns no state: the dialog around it holds the values, checks them (fieldForm.ts) and sends
 * them, so the source dialog and the connection dialog share every rule.
 */
export function FieldSpecForm({ fields, values, onChange, problems = {}, disabled, storedSecrets = {}, advancedOpen }: {
  fields: FieldSpec[]
  values: FieldValues
  onChange: (key: string, value: string) => void
  problems?: Record<string, FieldProblem>
  disabled?: boolean
  /** A secret already held, by key - what its masked box shows until it is typed over. */
  storedSecrets?: Record<string, string>
  /** Opens "More options" - a refused advanced field must not hide behind a closed line. */
  advancedOpen?: boolean
}) {
  const { t } = useTranslation()
  const { main, advanced } = splitAdvanced(fields)
  const one = (f: FieldSpec) => (
    <FieldControl
      key={f.key}
      field={f}
      value={values[f.key] ?? ''}
      onChange={v => onChange(f.key, v)}
      error={fieldProblemText(problems[f.key], t)}
      disabled={disabled}
      storedSecret={storedSecrets[f.key]}
    />
  )
  return (
    <FormStack>
      {main.map(one)}
      {advanced.length > 0 && (
        <Disclosure
          summary={t('More options')}
          open={advancedOpen || advanced.some(f => problems[f.key]) ? true : undefined}
        >
          <FormStack className="pt-3">{advanced.map(one)}</FormStack>
        </Disclosure>
      )}
    </FormStack>
  )
}

function FieldControl({ field: f, value, onChange, error, disabled, storedSecret }: {
  field: FieldSpec
  value: string
  onChange: (value: string) => void
  error?: string
  disabled?: boolean
  storedSecret?: string
}) {
  const { t } = useTranslation()
  const label = t(f.label)
  const help = f.help ? t(f.help) : undefined
  const placeholder = f.placeholder ?? undefined

  switch (f.type) {
    case 'BOOLEAN':
      return (
        <SwitchRow
          className="py-0"
          title={label}
          caption={help}
          checked={value === 'true'}
          onChange={v => onChange(v ? 'true' : 'false')}
          disabled={disabled}
        />
      )
    case 'SECRET':
      return (
        // The error is the SecretInput's (its border and the line under it), not the Field's too -
        // both drew it, so a refused secret said so twice.
        <Field label={label} hint={help}>
          {id => (
            <SecretInput
              id={id}
              value={value}
              serverValue={storedSecret}
              onChange={onChange}
              placeholder={placeholder}
              error={error}
              labels={{ reveal: t('Reveal value'), hide: t('Hide value'), show: t('Show value') }}
            />
          )}
        </Field>
      )
    case 'SELECT':
      return (
        <Field label={label} hint={help} error={error}>
          {id => (
            <Select
              id={id}
              value={value}
              onChange={onChange}
              disabled={disabled}
              placeholder={placeholder ?? t('Choose...')}
              options={f.options.map(o => ({ value: o.value, label: t(o.label) }))}
            />
          )}
        </Field>
      )
    case 'MULTISELECT': {
      const picked = value.split(',').map(p => p.trim()).filter(Boolean)
      const toggle = (option: string, on: boolean) => {
        const next = on ? [...picked.filter(p => p !== option), option] : picked.filter(p => p !== option)
        // In the options' own order, so the stored value does not depend on the order of clicks.
        onChange(f.options.map(o => o.value).filter(v => next.includes(v)).join(','))
      }
      return (
        <Field label={label} hint={help} error={error}>
          <div className="space-y-2">
            {f.options.map(o => (
              <Checkbox key={o.value} checked={picked.includes(o.value)} onChange={on => toggle(o.value, on)} disabled={disabled}>
                {t(o.label)}
              </Checkbox>
            ))}
          </div>
        </Field>
      )
    }
    case 'LIST': {
      // Rows of one entry each; the value is one entry a line, and an empty row is dropped on send.
      const rows = value === '' ? [] : value.split('\n')
      const set = (next: string[]) => onChange(next.join('\n'))
      return (
        <Field label={label} hint={help} error={error}>
          <ListEditor
            items={rows}
            renderRow={(row, i) => (
              <Input
                className="flex-1"
                value={row}
                placeholder={placeholder}
                disabled={disabled}
                aria-label={label}
                onChange={e => set(rows.map((r, j) => (j === i ? e.target.value : r)))}
              />
            )}
            onAdd={() => set([...rows, ''])}
            onRemove={i => set(rows.filter((_, j) => j !== i))}
            addLabel={t('Add')}
            removeLabel={t('Remove')}
          />
        </Field>
      )
    }
    case 'TEXTAREA':
      return (
        <Textarea
          label={label}
          hint={help}
          error={error}
          value={value}
          placeholder={placeholder}
          disabled={disabled}
          rows={4}
          onChange={e => onChange(e.target.value)}
        />
      )
    case 'NUMBER':
      return (
        <Input
          label={label}
          hint={help}
          error={error}
          type="number"
          inputMode="numeric"
          min={f.min ?? undefined}
          max={f.max ?? undefined}
          value={value}
          placeholder={placeholder}
          disabled={disabled}
          onChange={e => onChange(e.target.value)}
        />
      )
    default:
      return (
        <Input
          label={label}
          hint={help}
          error={error}
          type={f.type === 'URL' ? 'url' : 'text'}
          value={value}
          placeholder={placeholder}
          disabled={disabled}
          autoComplete="off"
          onChange={e => onChange(e.target.value)}
        />
      )
  }
}
