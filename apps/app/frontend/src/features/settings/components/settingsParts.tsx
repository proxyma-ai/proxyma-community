import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  Caption, Combobox, ConfirmDialog, Field, FormColumns, RadioGroup, Select,
  type ComboboxLabels, type SecretInputLabels,
} from '@/components/ui/kit'
import { providersApi } from '../services/providersApi'
import { useResolved } from '../pages/shared'
import { useProviderModels } from '../hooks/useProviderModels'
import { keys } from '@/services/queryKeys'
import { useTranslation } from '@/i18n'
import type { TabProps } from '../pages/shared'

// The parts several Settings tabs share - a radio-choice field, a provider-and-model pair, the
// named confirmation, the translated kit labels. Split out of SettingsModal.tsx with the tabs on
// 2026-10-14.

// The kit SecretInput's and Combobox's words, translated. Kit components cannot call t().
export function useSecretLabels(): SecretInputLabels {
  const { t } = useTranslation()
  return { reveal: t('Reveal value'), hide: t('Hide value'), show: t('Show value') }
}

export function useModelComboboxLabels(): ComboboxLabels {
  const { t } = useTranslation()
  return { loading: t('Loading models...'), failed: t('Failed to load models'), empty: t('No models found') }
}

/**
 * A field that is a set of radio choices: its name over the kit RadioGroup, its one-line hint
 * under it. `options` carry ENGLISH labels (the const arrays in each tab, each with a DYNAMIC entry
 * in check-catalogs.mjs) and are translated here - unless `translated`, for labels the caller
 * built in the reader's language ("30 days"). `figures` when the choices are numbers (8k, 128k).
 */
export function ChoiceField({ label, hint, value, onChange, options, translated, horizontal, figures, disabled }: {
  label?: string
  hint?: ReactNode
  value: string
  onChange: (v: string) => void
  options: { key: string; label: string }[]
  translated?: boolean
  horizontal?: boolean
  figures?: boolean
  disabled?: boolean
}) {
  const { t } = useTranslation()
  return (
    <Field label={label} hint={hint}>
      <RadioGroup
        ariaLabel={label}
        value={value}
        onChange={onChange}
        options={options.map(o => ({ value: o.key, label: translated ? o.label : t(o.label) }))}
        horizontal={horizontal}
        figures={figures}
        disabled={disabled}
      />
    </Field>
  )
}

/**
 * Removing something with a name, confirmed: the kit ConfirmDialog in its danger tone, saying
 * "Are you sure you want to delete <name>?" with what is lost under it - the name as the dialog's
 * `subject`, in the kit's own emphasis. Every label arrives translated.
 */
export function RemoveConfirm({ name, title, verb, confirmLabel, pendingLabel, consequences, pending, onConfirm, onCancel }: {
  name: string
  title: string
  verb: string
  confirmLabel: string
  pendingLabel?: string
  consequences: string[]
  pending?: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  return (
    <ConfirmDialog
      title={title}
      // Split at the name rather than interpolating it, so the name keeps its own weight. The
      // trailing "?" reads correctly in English and Vietnamese alike.
      description={t('Are you sure you want to {verb}', { verb })}
      subject={`${name}?`}
      consequences={consequences}
      confirmLabel={confirmLabel}
      cancelLabel={t('Cancel')}
      pendingLabel={pendingLabel}
      pending={pending}
      onConfirm={onConfirm}
      onCancel={onCancel}
    />
  )
}

// A provider and its model, side by side. The ONE shape for both, everywhere the pair appears -
// agent, sub-agent, OCR, query expansion, reranking and embedding, six places that had six
// near-identical copies of the same twenty lines.
//
// A DROPDOWN, not a row of radios. There are nine providers: as radio pills they wrapped onto
// three lines and gave a closed single-choice list more visual weight than anything else on the
// tab, including the model it governs. A dropdown is the control for "pick one of a fixed list
// you already know", and it collapses to one line.
//
// SIDE BY SIDE because they are one decision, not two. A model belongs to a provider - changing
// the provider re-fetches this very list - so stacking them put a heading, a control, and another
// heading between two halves of the same choice.
export function ProviderModelRow({
  role, providerLabel, modelLabel, modelPlaceholder, modelHint, unsetProviderLabel, fallbackProvider = '', trackChange, pending,
}: {
  role: string
  providerLabel: string
  modelLabel: string
  modelPlaceholder: string
  // What the empty provider choice says, already translated, for a role where empty is a real
  // answer rather than "nothing chosen yet": the sub-agent, which then runs on the agent's.
  unsetProviderLabel?: string
  // The provider that empty choice stands for, whose models the model list offers meanwhile. A
  // sub-agent on "Same as the agent" picks from the agent's provider: it asked an empty provider
  // for its models until 2026-10-03, and "Failed to load models" was all it could say.
  fallbackProvider?: string
  // Rendered under the pair, full width, ALREADY TRANSLATED. Two roles need one: the sub-agent
  // (pick something cheaper, that is the point) and OCR (must be vision-capable). It was looked up
  // a second time here, so a Vietnamese hint was used as a key - right only by luck.
  modelHint?: ReactNode
} & TabProps) {
  const { t } = useTranslation()
  const modelLabels = useModelComboboxLabels()
  // The stored value is a provider INSTANCE id. No default: on a deployment with nothing
  // configured the select is genuinely empty, and inventing a default here would show a provider
  // that was never chosen.
  const provider = useResolved(role, 'provider', pending, '')
  const model = useResolved(role, 'model', pending)
  const asked = provider || fallbackProvider
  const { options: modelOptions, status: modelStatus, errorText: modelsError, onOpen: openModels } = useProviderModels(asked)
  // With no provider there is nothing to ask - the list says what to do first rather than asking
  // /providers//models and reporting "Failed to load models".
  const comboboxLabels = asked ? modelLabels : { ...modelLabels, empty: t('Choose a provider first') }
  // EVERY configured instance, for every role - this list is deliberately unfiltered. The backend
  // no longer declares which protocol can serve which role (see ProviderProtocol): a pairing the
  // adapters cannot honor fails when it is used and says so, which tells an operator more than an
  // option quietly missing from a dropdown ever did.
  const { data: eligible = [], isSuccess: instancesLoaded } = useProviderInstances()

  // A role can still point at an instance that has since been DELETED. Showing the select as
  // though nothing were chosen would hide that; it stays on a value it cannot name, so say so.
  // Only once the list has ANSWERED - while it loads, `eligible` is empty and every configured
  // role read as pointing at a deleted provider for a moment.
  const selectedMissing = instancesLoaded && provider !== '' && !eligible.some(p => p.id === provider)

  return (
    <div className="space-y-3">
      {/* One column below sm: at that width the two controls would each be too narrow to show a
          model name, which is the longest string either of them ever holds. */}
      <FormColumns className="items-start">
        <Field
          label={t(providerLabel)}
          error={selectedMissing ? t('The provider this was set to no longer exists - choose another.') : undefined}
          hint={instancesLoaded && eligible.length === 0 ? t('Add one on the AI Providers tab.') : undefined}
        >
          {id => <Select
            id={id}
            value={provider}
            // CHANGING THE PROVIDER CLEARS THE MODEL. A model name is only meaningful inside one
            // provider's catalogue, so carrying `gpt-4o` across to an Ollama instance leaves a
            // selection that reads as chosen, survives Save, and fails at the first call with an
            // error about a model the user can see named right there in the box. The empty value
            // takes the deleteField path in performSave, so it clears on the server too rather
            // than being left at whatever was stored.
            //
            // Both calls are safe in one handler: trackChange uses a functional setPending and
            // replaces by (prefix, field), so the two entries do not race.
            onChange={v => {
              trackChange(role, 'provider', v)
              trackChange(role, 'model', '')
            }}
            options={[
              { value: '', label: unsetProviderLabel ?? (eligible.length === 0 ? t('No providers configured') : t('Select a provider')) },
              ...eligible.map(p => ({ value: p.id, label: p.name })),
            ]}
          />}
        </Field>
        <Field label={t(modelLabel)}>
          {id => (
            <Combobox
              id={id}
              value={model}
              onChange={v => trackChange(role, 'model', v)}
              placeholder={modelPlaceholder}
              options={modelOptions}
              status={modelStatus}
              errorText={modelsError}
              onOpen={openModels}
              labels={comboboxLabels}
            />
          )}
        </Field>
      </FormColumns>
      {modelHint && <Caption as="div">{modelHint}</Caption>}
    </div>
  )
}

export function useProviderInstances() {
  return useQuery({
    queryKey: keys.providerInstances(),
    queryFn: providersApi.list,
  })
}

export function useProviderProtocols() {
  return useQuery({
    queryKey: keys.providerProtocols(),
    queryFn: providersApi.listProtocols,
    staleTime: Infinity,
  })
}

