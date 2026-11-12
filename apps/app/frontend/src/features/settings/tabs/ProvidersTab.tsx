import { useEffect, useState, type ReactNode } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Trash2, Plus, Pencil, PlugZap } from 'lucide-react'
import {
  BrandMark, Button, Callout, Caption, Checkbox, RuleChecklist, Combobox, DialogBody, DialogFooter,
  DialogHeader, Disclosure, EmptyState, Field, FormColumns, FormStack, InlineNotice, Input, List, ListRow,
  MetaList, Presence, Readout, RowActions, SecretInput, Select, SettingsSections, FadeSwap,
  IconButton, StaticValue, Tooltip,
} from '@/components/ui/kit'
import { EditableText } from '@/components/ui/EditableText'
import { SecretAutosave, quietly } from '@/components/ui/AutosaveFields'
import { useAutosave } from '@/components/ui/useAutosave'
import { SaveStatus } from '@/components/ui/SaveStatus'
import { decimalProblem, wholeNumberProblem } from '@/components/ui/autosave'
import { providerMarkId } from '@/features/indexing/utils/sourceMarks'
import { costPricingApi, type ModelPricing } from '../services/costPricingApi'
import { EMPTY_RATE_FORM, hasLongTier, rateFormOf, ratePayload, type RateForm } from '../utils/callCost'
import { tokenCountLabel } from '../utils/modelDefaults'
import { useDebounce } from '@/hooks/useDebounce'
import { useDates } from '@/hooks/useDates'
import { providerModelsApi } from '../services/providerModelsApi'
import { providersApi } from '../services/providersApi'
import type { ProviderInstance, ProviderInstanceInput, ProviderProtocolInfo } from '../services/providersApi'
import { apiErrorText, withReason } from '@/services/apiError'
import { useSettings } from '../hooks/useSettings'
import { ListSkeleton, TitleAction, useResolved } from '../pages/shared'
import { useProviderModels } from '../hooks/useProviderModels'
import { useToastStore } from '@/store/toastStore'
import { formatUsd } from '@/utils/formatters'
import { displayHeaderValue } from '../utils/headerValue'
import { queryClient } from '@/app/QueryClient'
import { keys } from '@/services/queryKeys'
import { useTranslation, type Translate } from '@/i18n'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
import { PagedItems } from '@/components/ui/ListPager'
import type { PendingChange, TabProps } from '../pages/shared'
import type { TabKey } from '../components/SettingsModal'
import {
  RemoveConfirm, useProviderInstances, useProviderProtocols, useSecretLabels, useModelComboboxLabels,
} from '../components/settingsParts'

// - AI Providers tab -

// GET /api/v1/providers/{provider}/models fails with { error: <code>, message: <human text> }
// (ProviderModelsController.classifyError, proxyma-core-provider) - the actual cause, a bad API key
// against an unreachable host against a wrong base URL. Read by the one reader of every refusal
// (apiErrorText), which takes the sentence and leaves the machine code for code to branch on.
function connectionErrorText(error: unknown, hasApiKey: boolean, t: Translate): string {
  return apiErrorText(error) ?? (hasApiKey
    ? t('Connection failed - check the base URL and API key.')
    : t('Connection failed - check the base URL.'))
}

// Well-known services, as a convenience: picking one fills in the name, protocol and base URL, and
// nothing about it is stored or privileged. A service missing from this list is not unsupported -
// one speaking OpenAI's shape is an OpenAI-compatible provider with its URL typed in. The native
// protocols and the gateways come first (ProviderProtocol: native protocols first).
const PROVIDER_PRESETS: { label: string; protocol: string; baseUrl: string }[] = [
  { label: 'OpenAI',      protocol: 'OPENAI',            baseUrl: '' },
  { label: 'Anthropic',   protocol: 'ANTHROPIC',         baseUrl: '' },
  { label: 'Gemini',      protocol: 'GEMINI',            baseUrl: '' },
  { label: 'Azure OpenAI', protocol: 'AZURE_OPENAI',     baseUrl: '' },
  { label: 'Ollama',      protocol: 'OLLAMA',            baseUrl: 'http://127.0.0.1:11434' },
  { label: 'OpenRouter',  protocol: 'OPENROUTER',        baseUrl: '' },
  { label: 'LiteLLM',     protocol: 'LITELLM',           baseUrl: 'http://127.0.0.1:4000' },
  { label: 'DeepSeek',    protocol: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.deepseek.com' },
  { label: 'Mistral',     protocol: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.mistral.ai' },
  { label: 'Groq',        protocol: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.groq.com/openai' },
  { label: 'Together',    protocol: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.together.xyz' },
  { label: 'vLLM',        protocol: 'OPENAI_COMPATIBLE', baseUrl: 'http://127.0.0.1:8000' },
  { label: 'LM Studio',   protocol: 'OPENAI_COMPATIBLE', baseUrl: 'http://127.0.0.1:1234' },
]

// Add or edit one provider. Deliberately a modal rather than an inline expander like the old
// per-provider accordion. ADDING it is a form with its own validation and its own Add; an EXISTING
// provider is edited in place, field by field, each saved as it is left or changed (the maintainer,
// 2026-11-02) - never a group of fields behind the page's shared save button.
function ProviderModal({ instance, onClose }: { instance: ProviderInstance | null; onClose: () => void }) {
  return instance ? <ProviderEditModal instance={instance} onClose={onClose} /> : <ProviderAddModal onClose={onClose} />
}

function ProviderAddModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const secretLabels = useSecretLabels()
  const { data: protocols = [] } = useProviderProtocols()

  const [name, setName] = useState('')
  const [protocol, setProtocol] = useState('OPENAI')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [nativeRerank, setNativeRerank] = useState(false)
  const [gatewayApi, setGatewayApi] = useState('ANTHROPIC_MESSAGES')
  const [blockingCalls, setBlockingCalls] = useState(false)
  const [headerRows, setHeaderRows] = useState<HeaderRow[]>([])
  const [error, setError] = useState<string | null>(null)
  const { dirty } = useFormDirty({ name, protocol, baseUrl, apiKey, nativeRerank, gatewayApi, blockingCalls, headerRows })

  const protocolInfo = protocols.find(p => p.value === protocol)

  const save = useMutation({
    mutationFn: () => providersApi.create(providerInput(
      { name, protocol, baseUrl, headers: headerRows, nativeRerank, api: gatewayApi, blockingCalls }, protocolInfo, apiKey)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: keys.providerInstances() })
      // The saved provider is one the backend is at this moment re-probing - saving publishes
      // ProviderSettingsChangedEvent, which puts the connectivity verdict back into PROBING.
      // Without this the client keeps the boot-time answer forever (staleTime: Infinity), which
      // is how an install with two working providers went on saying none of them responded.
      // Refetching lands on PROBING, and the hook's own poll then follows it to DONE.
      queryClient.invalidateQueries({ queryKey: keys.providerConnectivity() })
      onClose()
    },
    // The backend writes these refusals to be read verbatim by the user - "Azure OpenAI needs a base
    // URL", "There is already a provider called X" - so show that rather than a generic failure.
    // See ProviderInstanceService.validate.
    onError: (e: unknown) => setError(apiErrorText(e) ?? t('Could not save this provider.')),
  })

  return (
    <ModalShell
      onClose={onClose}
      closable={!save.isPending}
      dirty={dirty}
      initialFocus="first-field"
      size="form"
      // Capped to the window (the kit's `cap`), with the BODY as its one scrolling region: the
      // headers are a list of no fixed length, and a provider with many scrolls the form rather than
      // growing the dialog past the screen.
      cap
      ariaLabel={t('Add provider')}
    >
      {requestClose => (<>
        <DialogHeader title={t('Add provider')} />
        <DialogBody scroll>
          {/* Two columns from `lg`: the provider and how to reach it on the left - its address, its
              key and the headers sent with every request (moved here from the right in the 0.28.0
              feedback) - and what it can do on the right. */}
          <FormColumns from="lg" density="columns" className="items-start">
            <FormStack className="min-w-0">
              <Field
                label={t('Start from')}
                hint={t('Fills in the protocol and address.')}
              >
                {id => (
                  <Select
                    id={id}
                    value=""
                    onChange={v => {
                      const preset = PROVIDER_PRESETS.find(p => p.label === v)
                      if (!preset) return
                      setName(preset.label)
                      setProtocol(preset.protocol)
                      setBaseUrl(preset.baseUrl)
                    }}
                    options={[{ value: '', label: t('Blank') },
                      ...PROVIDER_PRESETS.map(p => ({ value: p.label, label: p.label }))]}
                  />
                )}
              </Field>

              <FormColumns>
                <Field label={t('Name')}>
                  {id => <Input id={id} value={name} onChange={e => setName(e.target.value)} placeholder={t('OpenRouter')} />}
                </Field>
                <Field label={t('Protocol')}>
                  {id => (
                    <Select
                      id={id}
                      value={protocol}
                      onChange={setProtocol}
                      options={protocols.map(p => ({ value: p.value, label: p.label }))}
                    />
                  )}
                </Field>
              </FormColumns>

              <Field label={t('Base URL')}>
                {id => (
                  <Input
                    id={id}
                    value={baseUrl}
                    onChange={e => setBaseUrl(e.target.value)}
                    placeholder={baseUrlPlaceholder(protocol, t)}
                  />
                )}
              </Field>

              {/* THE ONE STORED-SECRET CONTROL (the kit SecretInput) - the same box an existing
                  provider shows its stored key masked in. */}
              <Field label={t('API key')}>
                {id => (
                  <SecretInput id={id} value={apiKey} onChange={setApiKey} placeholder="sk-..." labels={secretLabels} />
                )}
              </Field>

              <ProviderHeadersField rows={headerRows} onChange={setHeaderRows} />
            </FormStack>

            <FormStack className="min-w-0">
              <ProviderCapabilities
                protocolInfo={protocolInfo}
                nativeRerank={nativeRerank}
                onNativeRerank={setNativeRerank}
                gatewayApi={gatewayApi}
                onGatewayApi={setGatewayApi}
                blockingCalls={blockingCalls}
                onBlockingCalls={setBlockingCalls}
              />
            </FormStack>
          </FormColumns>
        </DialogBody>
        <DialogFooter start={error && <InlineNotice tone="danger">{error}</InlineNotice>}>
          <Button variant="secondary" onClick={requestClose}>{t('Cancel')}</Button>
          <Button
            onClick={() => { setError(null); save.mutate() }}
            disabled={!name.trim() || save.isPending}
          >
            {save.isPending ? t('Saving...') : t('Add provider')}
          </Button>
        </DialogFooter>
      </>)}
    </ModalShell>
  )
}

// A provider's fields as either dialog holds them. The key is never among them: the page is not sent
// it, so it can only be given - see providerInput.
interface ProviderForm {
  name: string
  protocol: string
  baseUrl: string
  headers: HeaderRow[]
  nativeRerank: boolean
  api: string
  blockingCalls: boolean
}

function providerFormOf(p: ProviderInstance): ProviderForm {
  return {
    name: p.name,
    protocol: p.protocol,
    baseUrl: p.baseUrl ?? '',
    headers: Object.entries(p.headers ?? {}).map(([n, v]) => ({ name: n, value: v })),
    nativeRerank: p.nativeRerank,
    api: p.api ?? 'ANTHROPIC_MESSAGES',
    blockingCalls: p.blockingCalls,
  }
}

// What is sent - the WHOLE provider, an edit's included, since one field's save is the provider with
// that field changed. A key only when one was given: an untouched key must not clear what is stored.
function providerInput(f: ProviderForm, protocolInfo: ProviderProtocolInfo | undefined, apiKey?: string): ProviderInstanceInput {
  const headers: Record<string, string> = {}
  f.headers.forEach(h => { if (h.name.trim()) headers[h.name.trim()] = h.value })
  return {
    name: f.name, protocol: f.protocol, baseUrl: f.baseUrl,
    ...(apiKey?.trim() ? { apiKey: apiKey.trim() } : {}),
    headers,
    nativeRerank: f.nativeRerank,
    // A gateway's own settings, sent only for a protocol that has them.
    ...(protocolInfo?.apis.length ? { api: f.api } : {}),
    ...(protocolInfo?.mayCallWithoutStreaming ? { blockingCalls: f.blockingCalls } : {}),
  }
}

function baseUrlPlaceholder(protocol: string, t: Translate): string {
  return protocol === 'AZURE_OPENAI'
    ? 'https://my-resource.openai.azure.com'
    : protocol === 'LITELLM'
      ? 'https://litellm.company.com'
      : t('Leave blank for this protocol default')
}

// What the chosen protocol can do, each easing in and out as the protocol changes - shared by both
// dialogs; on an existing provider each change is saved as it is made. "Runs locally" and "Cost
// reporting" used to sit beside these. Both are gone: cost is now always the configured rate times
// the tokens counted, so neither a provider-reported figure nor a "this one is free" assertion has
// anything to change.
function ProviderCapabilities({ protocolInfo, nativeRerank, onNativeRerank, gatewayApi, onGatewayApi, blockingCalls, onBlockingCalls }: {
  protocolInfo: ProviderProtocolInfo | undefined
  nativeRerank: boolean
  onNativeRerank: (on: boolean) => void
  gatewayApi: string
  onGatewayApi: (api: string) => void
  blockingCalls: boolean
  onBlockingCalls: (on: boolean) => void
}) {
  const { t } = useTranslation()
  return (<>
    <Presence show={!!protocolInfo?.maySupportNativeRerank}>
      <Field
        label={t('Reranking')}
        hint={t('For servers with a /v1/rerank endpoint. Off: reranking uses embeddings.')}
      >
        <Checkbox checked={nativeRerank} onChange={onNativeRerank}>
          {t('Serves /v1/rerank')}
        </Checkbox>
      </Field>
    </Presence>

    <Presence show={!!protocolInfo && protocolInfo.apis.length > 0}>
      <Field
        label={t('API')}
        hint={t('Anthropic Messages for Claude models, OpenAI Chat Completions for the rest.')}
      >
        {id => (
          <Select
            id={id}
            value={gatewayApi}
            onChange={onGatewayApi}
            options={(protocolInfo?.apis ?? []).map(a => ({ value: a.value, label: a.label }))}
          />
        )}
      </Field>
    </Presence>

    <Presence show={!!protocolInfo?.mayCallWithoutStreaming}>
      <Field
        label={t('Streaming')}
        hint={t('For a deployment that does not stream when tools are used.')}
      >
        <Checkbox checked={blockingCalls} onChange={onBlockingCalls}>
          {t('Wait for whole replies')}
        </Checkbox>
      </Field>
    </Presence>
  </>)
}

type ProviderEdit = ProviderForm & { apiKey?: string }

// AN EXISTING PROVIDER, EDITED IN PLACE: each field saved on its own - a text when it is left, a
// choice or a box as it changes, the key when a new one is typed and left (a blank one is never
// sent). Each save sends the whole provider with that one field changed (useAutosave), and a refusal
// - "Azure OpenAI needs a base URL", "There is already a provider called X", written by
// ProviderInstanceService.validate to be read verbatim - is said in a toast and the field goes back.
// No footer: there is nothing left to save, so Escape and the backdrop close it.
function ProviderEditModal({ instance, onClose }: { instance: ProviderInstance; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const secretLabels = useSecretLabels()
  const { data: protocols = [] } = useProviderProtocols()
  // The provider as the server last answered - its key's mask and its name (the rates are keyed on
  // it) follow each save; the row this dialog was opened from does not.
  const [current, setCurrent] = useState(instance)
  const base = providerFormOf(instance)

  const { value: form = base, save, status } = useAutosave<ProviderEdit>(
    base,
    async (next, patch) => {
      const info = protocols.find(p => p.value === next.protocol)
      const saved = await providersApi.update(instance.id, providerInput(next, info, patch.apiKey))
      setCurrent(saved)
      queryClient.invalidateQueries({ queryKey: keys.providerInstances() })
      // Saving re-probes the provider (see ProviderAddModal), so the verdict is read again.
      queryClient.invalidateQueries({ queryKey: keys.providerConnectivity() })
      // A rename carries the provider's rates to the new name on the server (they are keyed on it).
      if (patch.name !== undefined) queryClient.invalidateQueries({ queryKey: keys.modelPricing() })
      // The key is never kept: what the page holds of it is the server's mask.
      return providerFormOf(saved)
    },
    {
      failure: t('Could not save this provider.'),
      onError: (e: unknown) => addToast(apiErrorText(e) ?? t('Could not save this provider.'), 'danger'),
    },
  )
  const protocolInfo = protocols.find(p => p.value === form.protocol)
  const set = (patch: Partial<ProviderEdit>) => quietly(save(patch))

  return (
    <ModalShell
      onClose={onClose}
      initialFocus="panel"
      size="form"
      // Capped to the window, with the BODY as its one scrolling region: the headers and the rates
      // are lists of no fixed length, and a provider with many of either scrolls the body rather
      // than growing the dialog past the screen - or squeezing a list into a box of its own with a
      // scrollbar beside its row actions, as the rates did until 0.28.0.
      cap
      ariaLabel={t('Edit provider')}
    >
      <DialogHeader title={t('Edit provider')} actions={<SaveStatus word={status} />} />
      <DialogBody scroll>
        <FormColumns from="lg" density="columns" className="items-start">
          <FormStack className="min-w-0">
            <FormColumns>
              <Field label={t('Name')}>
                <EditableText
                  value={form.name}
                  label={t('Name')}
                  required={t('Name is required')}
                  onCommit={name => save({ name })}
                />
              </Field>
              <Field label={t('Protocol')}>
                {id => (
                  <Select
                    id={id}
                    value={form.protocol}
                    onChange={protocol => set({ protocol })}
                    options={protocols.map(p => ({ value: p.value, label: p.label }))}
                  />
                )}
              </Field>
            </FormColumns>

            <Field label={t('Base URL')}>
              <EditableText
                value={form.baseUrl}
                label={t('Base URL')}
                placeholder={baseUrlPlaceholder(form.protocol, t)}
                onCommit={baseUrl => save({ baseUrl })}
              />
            </Field>

            {/* The stored key masked, never shown as text; one typed over it is saved when the box is
                left, and a blank box sends nothing. */}
            <Field label={t('API key')}>
              {id => (
                <SecretAutosave
                  id={id}
                  serverValue={current.apiKeyConfigured ? current.apiKeyMasked ?? '' : undefined}
                  onCommit={apiKey => save({ apiKey })}
                  placeholder={current.apiKeyConfigured
                    ? t('Stored ({masked}) - type to replace', { masked: current.apiKeyMasked ?? '' })
                    : 'sk-...'}
                  labels={secretLabels}
                />
              )}
            </Field>

            <SavedHeadersField rows={form.headers} onCommit={headers => save({ headers })} />
          </FormStack>

          <FormStack className="min-w-0">
            <ProviderCapabilities
              protocolInfo={protocolInfo}
              nativeRerank={form.nativeRerank}
              onNativeRerank={nativeRerank => set({ nativeRerank })}
              gatewayApi={form.api}
              onGatewayApi={api => set({ api })}
              blockingCalls={form.blockingCalls}
              onBlockingCalls={blockingCalls => set({ blockingCalls })}
            />

            {/* Rates are keyed on the provider's NAME (see ProviderPricingModal), so there is nothing
                to hang one on until the provider exists - which is why they are here and not in the
                add dialog. Each rate is its own row with its own endpoint. */}
            <ProviderPricingSection instance={current} />
          </FormStack>
        </FormColumns>
      </DialogBody>
    </ModalShell>
  )
}

// One configured provider. Shows what it is and where it points; the Test button reuses the same
// model-list call every model combobox already makes, because a successful model list IS a
// successful connection test - so this needs no dedicated endpoint and cannot disagree with what
// the comboboxes see.
function ProviderInstanceRow({ instance, onEdit }: {
  instance: ProviderInstance
  onEdit: () => void
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [confirmDelete, setConfirmDelete] = useState(false)
  const addToast = useToastStore(s => s.addToast)
  const test = useMutation({ mutationFn: () => providerModelsApi.listModels(instance.id) })
  const remove = useMutation({
    mutationFn: () => providersApi.remove(instance.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: keys.providerInstances() })
      // Deleting changes the verdict too, in both directions: removing the one dead provider
      // clears the warning, and removing the only working one raises it.
      queryClient.invalidateQueries({ queryKey: keys.providerConnectivity() })
    },
    // A refused delete - an administrator who may read but not change, a provider still in use -
    // said nothing: the dialog closed and the row stayed. It says why now.
    onError: (e: unknown) => addToast(withReason(t('Could not delete this provider'), e), 'danger'),
  })

  return (
    <>
      <ListRow
        // Stacked below lg: the three buttons took the width of a phone, and beside the rail at
        // 768 they left the facts 130px, where the address broke inside its port.
        className="flex-col items-stretch lg:flex-row lg:items-start"
        leading={<BrandMark id={providerMarkId(instance.protocol)} size={16} className="text-brand-legible" />}
        title={instance.name}
        meta={<Caption as="span">{instance.protocolLabel}</Caption>}
        caption={<>
          {/* ONE LINE: the address is cut at its end, never broken inside it, and shown whole on
              hover; the key keeps its place after it. */}
          <span className="flex items-baseline min-w-0">
            <Tooltip label={instance.baseUrl || t('Default address')} className="flex min-w-0">
              <span className="truncate">{instance.baseUrl || t('Default address')}</span>
            </Tooltip>
            <span className="shrink-0 whitespace-nowrap">
              <span className="text-ink-disabled" aria-hidden="true">&nbsp;&middot;&nbsp;</span>
              {instance.apiKeyConfigured ? t('key {masked}', { masked: instance.apiKeyMasked ?? '' }) : t('no key')}
            </span>
          </span>
          <Presence show={!!test.isSuccess}>
            {test.isSuccess && (
              <InlineNotice tone="success" className="mt-1">
                {t('Reachable - {count} model.|Reachable - {count} models.', { count: test.data.models.length })}
              </InlineNotice>
            )}
          </Presence>
          <Presence show={!!test.isError}>
            <InlineNotice tone="danger" className="mt-1">{connectionErrorText(test.error, instance.apiKeyConfigured, t)}</InlineNotice>
          </Presence>
        </>}
        action={
          // ONE LINE OF ICON BUTTONS, as a connection's (the maintainer, 2026-11-03: "Use icon similar to
          // the connectors"): Test, Edit, then Delete in danger ink, set apart - each named by its tooltip.
          <RowActions destructive={
            <IconButton icon={Trash2} tone="danger" label={t('Delete')} onClick={() => setConfirmDelete(true)} />
          }>
            <IconButton icon={PlugZap} label={test.isPending ? t('Testing...') : t('Test')} busy={test.isPending}
              onClick={() => test.mutate()} />
            <IconButton icon={Pencil} label={t('Edit')} onClick={onEdit} />
          </RowActions>
        }
      />
      {confirmDelete && (
        <RemoveConfirm
          name={instance.name}
          title={t('Delete provider')}
          verb={t('delete')}
          confirmLabel={t('Delete')}
          consequences={[
            t('Anything using this provider stops working until you choose another.'),
            t('No other provider is chosen for you.'),
          ]}
          onConfirm={() => { remove.mutate(); setConfirmDelete(false) }}
          onCancel={() => setConfirmDelete(false)}
        />
      )}
    </>
  )
}

// WHAT TO SET UP NEXT, once a provider exists (2026-10-10, maintainer's report: after adding a
// provider nobody could tell what had to be configured next). A provider on its own does nothing:
// every model role names a provider AND a model, and none has a default (ChatSettings,
// EmbeddingSettings, OcrSettings - all ''). Two roles are required for the product to work at all -
// the agent's model (chat) and the embedding model (indexing and semantic search) - and two are
// worth knowing about while setting up: a vision model for OCR (blank leaves OCR off) and the
// rates cost tracking prices calls with (no rate falls back to a list price, else $0). Everything else on the AI &
// Knowledge tabs follows the agent or is off by default, so it is not listed.
//
// Read from the SAME settings queries the tabs use (useResolved, pending edits included), so a step
// ticks the moment it is chosen on its tab. Hidden once both required steps are done - the optional
// two are not worth a standing notice. A role pointing at a deleted provider is not done.
function ProviderNextSteps({ instances, onOpenTab, onOpenRates, pending }: {
  instances: ProviderInstance[]
  onOpenTab: (tab: TabKey) => void
  onOpenRates: (instance: ProviderInstance) => void
  pending: PendingChange[]
}) {
  const { t } = useTranslation()
  const exists = (id: string) => instances.some(p => p.id === id)
  const chatProvider = useResolved('chat', 'provider', pending, '')
  const chatModel = useResolved('chat', 'model', pending, '')
  const embProvider = useResolved('embedding', 'provider', pending, '')
  const embModel = useResolved('embedding', 'model', pending, '')
  const ocrEngine = useResolved('ocr', 'engine', pending, 'vision')
  const ocrProvider = useResolved('ocr', 'provider', pending, '')
  const ocrModel = useResolved('ocr', 'model', pending, '')
  const { data: rates = [] } = useQuery({ queryKey: keys.modelPricing(), queryFn: costPricingApi.list })
  const { isSuccess: chatLoaded } = useSettings('chat')
  const { isSuccess: embLoaded } = useSettings('embedding')

  const agentDone = exists(chatProvider) && chatModel.trim() !== ''
  const embeddingDone = exists(embProvider) && embModel.trim() !== ''
  const ocrDone = ocrEngine === 'tesseract' || (exists(ocrProvider) && ocrModel.trim() !== '')
  // Rates live in each provider's own dialog; the agent's provider is the one whose calls cost.
  const ratesFor = instances.find(p => p.id === chatProvider) ?? instances[0]
  const ratesDone = !!ratesFor && rates.some(r => r.provider === ratesFor.name)

  // Not before both answers are in: a notice that opened on "choose the agent's model" and then
  // vanished a moment later would have told the reader something false.
  const show = chatLoaded && embLoaded && !(agentDone && embeddingDone)

  const steps: { key: string; label: string; done: boolean; open: () => void }[] = [
    { key: 'agent', label: t('Agents: choose the model that answers'), done: agentDone, open: () => onOpenTab('Agents') },
    { key: 'embedding', label: t('Search: choose the embedding model'), done: embeddingDone, open: () => onOpenTab('Search') },
    { key: 'ocr', label: t('Conversion: choose a vision model for scans (optional)'), done: ocrDone, open: () => onOpenTab('Conversion') },
    { key: 'rates', label: t('Model pricing: set rates to track cost (optional)'), done: ratesDone, open: () => { if (ratesFor) onOpenRates(ratesFor) } },
  ]

  return (
    <Presence show={show}>
      <div className="pt-4">
        <Callout tone="info" title={t('Next steps')} role="status">
          {/* A done step is a ticked line; one still to do is the link that goes to it. */}
          <RuleChecklist
            className="mt-2"
            items={steps.map(s => ({
              key: s.key,
              pass: s.done,
              label: s.done
                ? s.label
                : <Button variant="hyperlink" className="text-xs text-left" onClick={s.open}>{s.label}</Button>,
            }))}
          />
        </Callout>
      </div>
    </Presence>
  )
}

export function ProvidersTab({ pending, onOpenTab, canEdit }: TabProps & { onOpenTab: (tab: TabKey) => void; canEdit: boolean }) {
  const { t } = useTranslation()
  const { data: instances = [], isLoading } = useProviderInstances()
  // null = closed; undefined = open for a NEW provider; an instance = open for editing it.
  const [editing, setEditing] = useState<ProviderInstance | null | undefined>(null)

  return (
    <SettingsSections intro={t('Add any service that speaks a supported protocol.')}>
      <div>
        <TitleAction>
          <Presence show={!isLoading && instances.length > 0} collapse={false}>
            <Button onClick={() => setEditing(undefined)}><Plus size={14} />{t('Add provider')}</Button>
          </Presence>
        </TitleAction>
        <FadeSwap swapKey={isLoading ? 'loading' : instances.length > 0}>
        {isLoading ? (
          <ListSkeleton count={2} />
        ) : instances.length === 0 ? (
          // An empty state that says what is missing and what a provider is for, because on a fresh
          // deployment this is the reason chat and search do not work - and a bare "None yet" would
          // leave the user to work that out.
          <EmptyState
            title={t('No providers configured yet.')}
            action={<Button onClick={() => setEditing(undefined)}><Plus size={14} />{t('Add provider')}</Button>}
          >
            {t('Add a service you have an API key for, or a local one like Ollama.')}
          </EmptyState>
        ) : (
          <>
            {/* Twenty a page, in the browser: the checklist below and every model picker read them all. */}
            <PagedItems items={instances}>
              {page => (
                <List>
                  {page.map(p => (
                    <ProviderInstanceRow key={p.id} instance={p} onEdit={() => setEditing(p)} />
                  ))}
                </List>
              )}
            </PagedItems>
            {/* Only for someone who can work through the steps: an admin reading a server's
                configuration is shown the providers, not a to-do list they may not act on. */}
            {canEdit && (
              <ProviderNextSteps instances={instances} onOpenTab={onOpenTab} onOpenRates={setEditing} pending={pending} />
            )}
          </>
        )}
        </FadeSwap>
      </div>

      {editing !== null && (
        <ProviderModal instance={editing ?? null} onClose={() => setEditing(null)} />
      )}
    </SettingsSections>
  )
}

// - Model pricing, inside one provider's dialog -
//
// Immediate CRUD, a rate being its own row with its own endpoint, exactly like ApiTokensTab's
// tokens - saved through its own dialog, never as a field of the provider.
//
// Only rendered when EDITING an existing provider. Rates are keyed on the provider's name, so a
// provider that has not been saved yet has no key to hang one on.
function ProviderPricingSection({ instance }: { instance: ProviderInstance }) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const { data: allRows, isLoading } = useQuery({
    queryKey: keys.modelPricing(),
    queryFn: costPricingApi.list,
  })
  const [modalOpen, setModalOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<ModelPricing | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<ModelPricing | null>(null)

  // Filtered client-side off the one cached list rather than a per-provider endpoint: the whole
  // table is a handful of rows on any real deployment, and one cached query keeps every open
  // dialog consistent after an edit without a second key to invalidate.
  const rows = (allRows ?? []).filter(r => r.provider === instance.name)

  const remove = useMutation({
    mutationFn: (id: string) => costPricingApi.delete(id),
    onSuccess: () => {
      setDeleteTarget(null)
      queryClient.invalidateQueries({ queryKey: keys.modelPricing() })
      addToast(t('Pricing deleted'), 'success')
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to delete pricing'), e), 'danger'),
  })

  const addButton = (
    <Button variant="hyperlink" type="button" onClick={() => setModalOpen(true)}>
      <Plus size={14} />
      {t('Add model rate')}
    </Button>
  )

  return (
    // The explanation sits ABOVE the list (the Field's description), because it says what the
    // rows mean before they are read.
    <Field
      label={t('Model pricing')}
      description={t('Price per million tokens, for costs and quotas. A model with no rate uses its list price or $0.')}
    >
      {isLoading ? (
        <ListSkeleton count={2} />
      ) : rows.length > 0 ? (
        <>
          {/* Rates are one per model and so of no fixed number. The list does NOT scroll inside
              itself (0.28.0 feedback: a narrow box with a scrollbar beside the row actions): the
              provider dialog's body is the one scrolling region. */}
          <PagedItems items={rows}>
            {page => (
              <List>
                {page.map(r => (
                  <PricingRow
                    key={r.id}
                    row={r}
                    onEditClick={() => setEditTarget(r)}
                    onDeleteClick={() => setDeleteTarget(r)}
                  />
                ))}
              </List>
            )}
          </PagedItems>
          <div className="mt-2">{addButton}</div>
        </>
      ) : (
        <EmptyState className="py-0" action={addButton}>{t('No rates set for this provider.')}</EmptyState>
      )}

      {(modalOpen || editTarget) && (
        <ProviderPricingModal
          instance={instance}
          editing={editTarget}
          onClose={() => { setModalOpen(false); setEditTarget(null) }}
        />
      )}

      {deleteTarget && (
        <RemoveConfirm
          name={`${deleteTarget.provider} / ${deleteTarget.model}`}
          title={t('Delete pricing')}
          verb={t('delete')}
          confirmLabel={t('Delete')}
          pendingLabel={t('Deleting...')}
          consequences={[t('This model will use its list price or $0.')]}
          pending={remove.isPending}
          onConfirm={() => remove.mutate(deleteTarget.id)}
          onCancel={() => setDeleteTarget(null)}
        />
      )}
    </Field>
  )
}

// One rate. A dense sub-list inside a dialog, so its actions are ICONS (the kit's rule: text
// actions on rows that carry a name, icons only in a price list or a step list), with the
// destructive one last and set apart.
function PricingRow({ row, onEditClick, onDeleteClick }: {
  row: ModelPricing
  onEditClick: () => void
  onDeleteClick: () => void
}) {
  const { t } = useTranslation()
  return (
    <ListRow
      // The model alone - this list only ever renders inside the provider it belongs to, so
      // repeating the provider name on every row would say the same thing four times.
      title={row.model}
      caption={
        <Readout>
          <MetaList items={[
            t('{price} in', { price: formatUsd(row.inputPricePerMillion) }),
            t('{price} out', { price: formatUsd(row.outputPricePerMillion) }),
            t('{price} cache read', { price: formatUsd(row.cacheReadPricePerMillion) }),
            t('{price} cache write', { price: formatUsd(row.cacheWritePricePerMillion) }),
            row.cacheWriteLongPricePerMillion != null
              && t('{price} 1-hour cache write', { price: formatUsd(row.cacheWriteLongPricePerMillion) }),
            // The long tier by its in and out alone - each blank one is the standard rate.
            row.longContextThresholdTokens != null && row.longContextThresholdTokens > 0
              && t('above {tokens} tokens: {input} in, {output} out', {
                tokens: tokenCountLabel(row.longContextThresholdTokens),
                input: formatUsd(row.longInputPricePerMillion ?? row.inputPricePerMillion),
                output: formatUsd(row.longOutputPricePerMillion ?? row.outputPricePerMillion),
              }),
            row.pricingSchedule === 'DEEPSEEK_OFF_PEAK' && t('off-peak half price'),
            <span key="unit" className="text-ink-disabled">{t('per 1M tokens')}</span>,
          ]} />
        </Readout>
      }
      action={
        <IconRowActions
          editLabel={t('Edit pricing for {provider} / {model}', { provider: row.provider, model: row.model })}
          deleteLabel={t('Delete pricing for {provider} / {model}', { provider: row.provider, model: row.model })}
          onEdit={onEditClick}
          onDelete={onDeleteClick}
        />
      }
    />
  )
}

// Edit and Delete as icons on a dense sub-list row - a rate, a header: the kit's IconButton (the
// tooltip, the name and the glyph in one part) in the kit's icon RowActions, the destructive one
// last. Each is named in full ("Delete pricing for X / Y") and its tooltip says the verb.
function IconRowActions({ editLabel, deleteLabel, onEdit, onDelete }: {
  editLabel: string
  deleteLabel: string
  onEdit: () => void
  onDelete: () => void
}) {
  const { t } = useTranslation()
  return (
    <RowActions
      icons
      destructive={<IconButton icon={Trash2} size="sm" tone="danger" label={deleteLabel} tooltip={t('Delete')} onClick={onDelete} />}
    >
      <IconButton icon={Pencil} size="sm" label={editLabel} tooltip={t('Edit')} onClick={onEdit} />
    </RowActions>
  )
}

type HeaderRow = { name: string; value: string }

// A NEW provider's custom headers, as a list like its rates (0.28.0 feedback): the header's name
// over its value - a credential's value shown by its ends only (headerValue.ts) - with Edit and
// Delete, and "Add header" opening a small dialog like "Add model rate". Unlike the rates these are
// part of the PROVIDER form: an add, edit or delete here changes the form, and the provider's own
// Add writes them, in the same shape as ever (a name-to-value map, blank names dropped). An existing
// provider's are SavedHeadersField's.
function ProviderHeadersField({ rows, onChange }: { rows: HeaderRow[]; onChange: (rows: HeaderRow[]) => void }) {
  const { t } = useTranslation()
  const [editing, setEditing] = useState<number | 'new' | null>(null)
  // Rows saved before this list existed may have a blank name; the form never sends those.
  const shown = rows.map((row, index) => ({ row, index })).filter(({ row }) => row.name.trim())

  const addButton = (
    <Button variant="hyperlink" type="button" onClick={() => setEditing('new')}>
      <Plus size={14} />
      {t('Add header')}
    </Button>
  )

  return (
    <Field
      label={t('Custom headers')}
      description={t('Sent with every request.')}
    >
      {shown.length > 0 ? (
        <>
          <List>
            {shown.map(({ row, index }) => (
              <ListRow
                key={index}
                title={<span className="break-all">{row.name}</span>}
                caption={<span className="break-all">{displayHeaderValue(row.name, row.value) || '-'}</span>}
                action={
                  <IconRowActions
                    editLabel={t('Edit header {name}', { name: row.name })}
                    deleteLabel={t('Remove header {name}', { name: row.name })}
                    onEdit={() => setEditing(index)}
                    onDelete={() => onChange(rows.filter((_, j) => j !== index))}
                  />
                }
              />
            ))}
          </List>
          <div className="mt-2">{addButton}</div>
        </>
      ) : (
        <EmptyState className="py-0" action={addButton}>{t('No custom headers.')}</EmptyState>
      )}

      {editing !== null && (
        <ProviderHeaderModal
          initial={editing === 'new' ? null : rows[editing]}
          taken={rows.filter((_, j) => j !== editing).map(r => r.name.trim().toLowerCase())}
          onSave={row => {
            onChange(editing === 'new' ? [...rows, row] : rows.map((r, j) => (j === editing ? row : r)))
            setEditing(null)
          }}
          onClose={() => setEditing(null)}
        />
      )}
    </Field>
  )
}

// AN EXISTING provider's custom headers, edited where they are read (the maintainer, 2026-11-02): each
// row's name and value click-to-edit, saved as the row is left - the value shown at rest by its ends
// when it is a credential (headerValue.ts), whole once opened, as the header dialog showed it - and a
// removed row saved at once. A name another header already has is refused before anything is sent.
// "Add header" keeps its small dialog, whose Add saves the provider. Every change sends the provider
// with its whole header map (`onCommit`), as the form always did; there is no per-header endpoint.
function SavedHeadersField({ rows, onCommit }: { rows: HeaderRow[]; onCommit: (rows: HeaderRow[]) => Promise<unknown> }) {
  const { t } = useTranslation()
  const [adding, setAdding] = useState(false)
  // Rows saved before this list existed may have a blank name; they are never sent, so not shown.
  const shown = rows.map((row, index) => ({ row, index })).filter(({ row }) => row.name.trim())
  const takenBy = (index: number) => rows.filter((_, j) => j !== index).map(r => r.name.trim().toLowerCase())
  const duplicateText = t('This provider already sends a header with that name.')
  const change = (index: number, patch: Partial<HeaderRow>) => onCommit(rows.map((r, j) => (j === index ? { ...r, ...patch } : r)))

  const addButton = (
    <Button variant="hyperlink" type="button" onClick={() => setAdding(true)}>
      <Plus size={14} />
      {t('Add header')}
    </Button>
  )

  return (
    <Field
      label={t('Custom headers')}
      description={t('Sent with every request.')}
    >
      {shown.length > 0 ? (
        <>
          <List>
            {shown.map(({ row, index }) => (
              <ListRow
                key={`${index}:${row.name}`}
                title={(
                  <EditableText
                    value={row.name}
                    label={t('Name')}
                    className="break-all"
                    required={t('Name is required')}
                    validate={text => (takenBy(index).includes(text.trim().toLowerCase()) ? duplicateText : undefined)}
                    onCommit={name => (takenBy(index).includes(name.trim().toLowerCase())
                      ? Promise.reject(new Error(duplicateText))
                      : change(index, { name: name.trim() }))}
                  />
                )}
                caption={(
                  <EditableText
                    value={row.value}
                    label={t('Value')}
                    placeholder="-"
                    trim={false}
                    className="break-all"
                    onCommit={value => change(index, { value })}
                  >
                    {displayHeaderValue(row.name, row.value) || '-'}
                  </EditableText>
                )}
                action={(
                  <RowActions
                    icons
                    destructive={(
                      <IconButton
                        icon={Trash2}
                        size="sm"
                        tone="danger"
                        label={t('Remove header {name}', { name: row.name })}
                        tooltip={t('Delete')}
                        onClick={() => quietly(onCommit(rows.filter((_, j) => j !== index)))}
                      />
                    )}
                  />
                )}
              />
            ))}
          </List>
          <div className="mt-2">{addButton}</div>
        </>
      ) : (
        <EmptyState className="py-0" action={addButton}>{t('No custom headers.')}</EmptyState>
      )}

      {adding && (
        <ProviderHeaderModal
          initial={null}
          taken={rows.map(r => r.name.trim().toLowerCase())}
          onSave={row => onCommit([...rows, row]).then(() => setAdding(false))}
          onClose={() => setAdding(false)}
        />
      )}
    </Field>
  )
}

// Add or edit one header - the same small dialog as a model rate's. On a new provider nothing is
// saved from here: the row goes back to the provider form, whose Add writes it. On an existing one
// it only ever ADDS (SavedHeadersField - a saved header is edited in its row), and `onSave`'s
// promise is the provider's save: the dialog waits for it and closes only once it is kept.
function ProviderHeaderModal({ initial, taken, onSave, onClose }: {
  initial: HeaderRow | null
  taken: string[]
  onSave: (row: HeaderRow) => void | Promise<unknown>
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState(initial?.name ?? '')
  const [value, setValue] = useState(initial?.value ?? '')
  const [saving, setSaving] = useState(false)
  const { dirty } = useFormDirty({ name, value })
  const duplicate = taken.includes(name.trim().toLowerCase())
  const canSave = name.trim().length > 0 && !duplicate && !saving
  const save = () => {
    if (!canSave) return
    const result = onSave({ name: name.trim(), value })
    if (result) {
      setSaving(true)
      result.then(() => undefined, () => setSaving(false))
    }
  }

  return (
    <ModalShell
      onClose={onClose}
      closable={!saving}
      dirty={dirty}
      size="md"
      initialFocus="first-field"
      ariaLabel={initial ? t('Edit header') : t('Add header')}
    >
      {requestClose => (<>
        <DialogHeader title={initial ? t('Edit header') : t('Add header')} />
        <DialogBody>
          <FormStack>
            <Field label={t('Name')}>
              {id => (
                <Input
                  id={id}
                  value={name}
                  onChange={e => setName(e.target.value)}
                  placeholder="HTTP-Referer"
                  error={duplicate ? t('This provider already sends a header with that name.') : undefined}
                />
              )}
            </Field>
            <Field label={t('Value')}>
              {id => (
                <Input
                  id={id}
                  value={value}
                  onChange={e => setValue(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); save() } }}
                  placeholder="https://example.com"
                />
              )}
            </Field>
          </FormStack>
        </DialogBody>
        <DialogFooter>
          <Button variant="secondary" onClick={requestClose}>{t('Cancel')}</Button>
          <Button onClick={save} disabled={!canSave}>
            {saving ? t('Saving...') : initial ? t('Save') : t('Add header')}
          </Button>
        </DialogFooter>
      </>)}
    </ModalShell>
  )
}

// One model's rate, always scoped to ONE provider - `instance` is the provider whose dialog opened
// it, so there is no provider picker any more. ADDING it is a form with its own Add; an EXISTING rate
// is edited in place, each box saved as it is left (RateEditModal).
function ProviderPricingModal({ instance, editing, onClose }: {
  instance: ProviderInstance
  editing: ModelPricing | null
  onClose: () => void
}) {
  return editing
    ? <RateEditModal instance={instance} rate={editing} onClose={onClose} />
    : <RateAddModal instance={instance} onClose={onClose} />
}

// The rate boxes both dialogs draw, in one order: three columns of the standard rates and the
// schedule, then the less common long-context tier behind a disclosure - open from the start for a
// row that has one. WIDER, NOT TALLER (no dialog scrolls).
function RateBoxes({ box, schedule, longOpen, onLongOpen }: {
  box: (label: string, key: Exclude<keyof RateForm, 'schedule'>, placeholder?: string) => ReactNode
  schedule: ReactNode
  longOpen: boolean
  onLongOpen: (open: boolean) => void
}) {
  const { t } = useTranslation()
  return (<>
    <FormColumns cols={3}>
      {box(t('Input $/1M tokens'), 'input')}
      {box(t('Output $/1M tokens'), 'output')}
      {box(t('Cache read $/1M tokens'), 'cacheRead')}
      {box(t('Cache write, 5 min $/1M tokens'), 'cacheWrite')}
      {box(t('Cache write, 1 hour $/1M tokens'), 'cacheWriteLong', t('1.6x the 5 min rate'))}
      {schedule}
    </FormColumns>

    <Disclosure summary={t('Long context pricing')} open={longOpen} onToggle={onLongOpen}>
      {/* The tier's description opens it, before its boxes - it was the grid's last cell. */}
      <Caption className="pt-3 max-w-[60ch]">
        {t('Above this prompt size, these rates apply to the whole call.')}
      </Caption>
      <FormColumns cols={3} className="pt-3">
        {box(t('Threshold (prompt tokens)'), 'threshold', '200000')}
        {box(t('Input $/1M tokens'), 'longInput', t('Standard rate'))}
        {box(t('Output $/1M tokens'), 'longOutput', t('Standard rate'))}
        {box(t('Cache read $/1M tokens'), 'longCacheRead', t('Standard rate'))}
        {box(t('Cache write, 5 min $/1M tokens'), 'longCacheWrite', t('Standard rate'))}
      </FormColumns>
    </Disclosure>
  </>)
}

function useScheduleOptions(): { value: RateForm['schedule']; label: string }[] {
  const { t } = useTranslation()
  return [
    { value: '', label: t('Same price all day') },
    { value: 'DEEPSEEK_OFF_PEAK', label: t('DeepSeek off-peak (half price outside peak hours)') },
  ]
}

// ADDING a rate: a model picked or typed is looked up in the server's built-in LIST PRICES (debounced)
// and, while no price has been typed, its rates fill the form, with a line saying so and as of when.
// A price the user typed is never overwritten, and a model the list does not know changes nothing
// typed - it only takes back a prefill the list made for an earlier model.
//
// THE MODEL COMBOBOX ACTUALLY WORKS NOW. GET /api/v1/providers/{provider}/models resolves its path
// segment as an instance ID (see ProviderModelsController -> ProviderInstanceService.resolve),
// and the old Cost Settings version had only the provider NAME to pass - so every dropdown there
// failed to resolve and offered nothing to pick. Opened from the provider's own dialog, the id is
// simply at hand.
function RateAddModal({ instance, onClose }: { instance: ProviderInstance; onClose: () => void }) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const comboboxLabels = useModelComboboxLabels()
  const scheduleOptions = useScheduleOptions()
  const dates = useDates()

  const [model, setModel] = useState('')
  const [form, setForm] = useState<RateForm>(EMPTY_RATE_FORM)
  const [longOpen, setLongOpen] = useState(false)
  // A price the user typed: from then on no list price touches the form. The schedule chosen by
  // hand is kept apart - it is not a price, so it neither stops a prefill nor is replaced by one.
  const [typed, setTyped] = useState(false)
  const [scheduleChosen, setScheduleChosen] = useState(false)
  // The list price the form holds, by its date - shown as one line until something is typed.
  const [prefilledAsOf, setPrefilledAsOf] = useState<string | null>(null)
  const edit = (key: keyof RateForm) => (value: string) => {
    setTyped(true)
    setForm(f => ({ ...f, [key]: value }))
  }

  const { options: modelOptions, status: modelStatus, errorText: modelsError, onOpen: openModels } = useProviderModels(instance.id)

  // 404 (a model the list does not know) arrives as null.
  const lookup = useDebounce(model.trim(), 400)
  const listPrice = useQuery({
    queryKey: keys.modelListPrice(lookup),
    queryFn: () => costPricingApi.listPrice(lookup),
    enabled: lookup.length > 0,
    retry: false,
    staleTime: Infinity,
  })
  // No model is no list price; undefined while the answer is on its way.
  const found = lookup ? listPrice.data : null
  useEffect(() => {
    if (typed || found === undefined) return
    if (found) {
      const prefill = rateFormOf(found)
      setForm(f => (scheduleChosen ? { ...prefill, schedule: f.schedule } : prefill))
      setLongOpen(open => open || hasLongTier(prefill))
      setPrefilledAsOf(found.asOf)
    } else if (prefilledAsOf) {
      // An earlier model's list price, nothing typed over it: it is not this model's price.
      setForm(f => (scheduleChosen ? { ...EMPTY_RATE_FORM, schedule: f.schedule } : EMPTY_RATE_FORM))
      setPrefilledAsOf(null)
    }
    // Only a new answer prefills: the form, or the flags, changing under it must not run this again.
  }, [found])

  const save = useMutation({
    // The NAME, not the id: that is what the cost ledger records for every call and therefore what a
    // rate has to be keyed on to be found again. ProviderInstanceService carries these rows across a
    // rename so the two cannot drift apart.
    mutationFn: () => costPricingApi.create({ provider: instance.name, model, ...ratePayload(form) }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: keys.modelPricing() })
      addToast(t('Pricing added'), 'success')
      onClose()
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to add pricing'), e), 'danger'),
  })

  const canSave = model.trim().length > 0
  const { dirty } = useFormDirty({ model, form })

  const box = (label: string, key: keyof RateForm, placeholder = '0.00') => (
    <Field label={label}>
      {id => (
        <Input
          id={id}
          type="number"
          min="0"
          step={key === 'threshold' ? '1000' : '0.01'}
          value={form[key]}
          onChange={e => edit(key)(e.target.value)}
          placeholder={placeholder}
        />
      )}
    </Field>
  )

  return (
    <ModalShell
      onClose={onClose}
      closable={!save.isPending}
      dirty={dirty}
      size="form"
      ariaLabel={t('Add model rate')}
    >
      {requestClose => (<>
        <DialogHeader title={t('Add model rate')} subtitle={instance.name} />
        <DialogBody>
          <FormStack>
            <Field label={t('Model')}>
              {id => (
                <Combobox
                  id={id}
                  value={model}
                  onChange={setModel}
                  options={modelOptions}
                  status={modelStatus}
                  errorText={modelsError}
                  onOpen={openModels}
                  placeholder={t('Model name')}
                  labels={comboboxLabels}
                />
              )}
            </Field>

            {/* What the rates below are, BEFORE them (the field rule of 2026-10-29 - these closed
                the form, after every box they explain). */}
            <div className="space-y-1">
              <Presence show={!typed && prefilledAsOf != null}>
                <Caption>
                  {t('List price as of {date}. Check your bill.', {
                    date: prefilledAsOf ? dates.date(`${prefilledAsOf}T12:00:00`) : '',
                  })}
                </Caption>
              </Presence>
              <Caption>{t('A cost the provider reports is used instead.')}</Caption>
            </div>
            <RateBoxes
              box={box}
              longOpen={longOpen}
              onLongOpen={setLongOpen}
              schedule={(
                <Field label={t('Time-of-day pricing')}>
                  {id => (
                    <Select<RateForm['schedule']>
                      id={id}
                      value={form.schedule}
                      onChange={schedule => { setScheduleChosen(true); setForm(f => ({ ...f, schedule })) }}
                      options={scheduleOptions}
                    />
                  )}
                </Field>
              )}
            />
          </FormStack>
        </DialogBody>
        <DialogFooter>
          <Button variant="secondary" onClick={requestClose} disabled={save.isPending}>{t('Cancel')}</Button>
          <Button onClick={() => save.mutate()} disabled={save.isPending || !canSave}>
            {save.isPending ? t('Saving...') : t('Save')}
          </Button>
        </DialogFooter>
      </>)}
    </ModalShell>
  )
}

// AN EXISTING RATE, EDITED IN PLACE (the maintainer, 2026-11-02): each box click-to-edit and saved as
// it is left, the schedule as it is chosen. Provider + model identify the row, so the model is shown
// and not edited; the PATCH takes the rates only and replaces them ALL, so each save sends every rate
// with the one change (useAutosave). A box that is not a price of zero or more - or, for the
// threshold, a whole number of tokens - is refused before anything is sent. A blank long-context
// rate is the standard rate, and the long rates go only with a threshold: one typed with none is not
// kept, as the form never kept it.
function RateEditModal({ instance, rate, onClose }: { instance: ProviderInstance; rate: ModelPricing; onClose: () => void }) {
  const { t } = useTranslation()
  const scheduleOptions = useScheduleOptions()
  const base = rateFormOf(rate)
  const [longOpen, setLongOpen] = useState(() => hasLongTier(base))
  const { value: form = base, save, status } = useAutosave<RateForm>(
    base,
    async next => {
      const saved = await costPricingApi.update(rate.id, ratePayload(next))
      queryClient.invalidateQueries({ queryKey: keys.modelPricing() })
      return rateFormOf(saved)
    },
    { failure: t('Failed to update pricing') },
  )

  const priceText = t('Must be a number of 0 or more')
  const wholeText = t('Must be a whole number')
  const problem = (key: keyof RateForm, text: string): string | undefined => {
    if (key === 'threshold') return text.trim() !== '' && wholeNumberProblem(text, 0, Number.MAX_SAFE_INTEGER) ? wholeText : undefined
    return decimalProblem(text) ? priceText : undefined
  }

  const box = (label: string, key: Exclude<keyof RateForm, 'schedule'>, placeholder = '0.00') => (
    <Field label={label}>
      <EditableText
        value={form[key]}
        label={label}
        placeholder={placeholder}
        className="data-readout"
        validate={text => problem(key, text)}
        onCommit={text => {
          const refused = problem(key, text)
          return refused ? Promise.reject(new Error(refused)) : save({ [key]: text.trim() })
        }}
      />
    </Field>
  )

  return (
    <ModalShell onClose={onClose} size="form" ariaLabel={t('Edit rate')}>
      <DialogHeader title={t('Edit rate')} subtitle={instance.name} actions={<SaveStatus word={status} />} />
      <DialogBody>
        <FormStack>
          <Field label={t('Model')}>
            <StaticValue className="break-all">{rate.model}</StaticValue>
          </Field>
          <Caption>{t('A cost the provider reports is used instead.')}</Caption>
          <RateBoxes
            box={box}
            longOpen={longOpen}
            onLongOpen={setLongOpen}
            schedule={(
              <Field label={t('Time-of-day pricing')}>
                {id => (
                  <Select<RateForm['schedule']>
                    id={id}
                    value={form.schedule}
                    onChange={schedule => quietly(save({ schedule }))}
                    options={scheduleOptions}
                  />
                )}
              </Field>
            )}
          />
        </FormStack>
      </DialogBody>
    </ModalShell>
  )
}
