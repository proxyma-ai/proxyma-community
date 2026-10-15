import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Trash2, Plus, Pencil } from 'lucide-react'
import {
  BrandMark, Button, Callout, Caption, Checkbox, RuleChecklist, Combobox, DialogBody, DialogFooter,
  DialogHeader, EmptyState, Field, FormColumns, FormStack, InlineNotice, Input, List, ListRow,
  MetaList, Presence, Readout, RowActions, SecretInput, Select, SettingsSections, FadeSwap,
  IconButton,
} from '@/components/ui/kit'
import { providerMarkId } from '@/features/indexing/utils/sourceMarks'
import { costPricingApi, type ModelPricing } from '../services/costPricingApi'
import { providerModelsApi } from '../services/providerModelsApi'
import { providersApi } from '../services/providersApi'
import type { ProviderInstance } from '../services/providersApi'
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
// per-provider accordion: this is a create-or-edit form with its own validation and its own save,
// not a group of fields belonging to the page's shared save button.
function ProviderModal({ instance, onClose }: { instance: ProviderInstance | null; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const secretLabels = useSecretLabels()
  const { data: protocols = [] } = useProviderProtocols()
  const editing = instance !== null

  const [name, setName] = useState(instance?.name ?? '')
  const [protocol, setProtocol] = useState(instance?.protocol ?? 'OPENAI')
  const [baseUrl, setBaseUrl] = useState(instance?.baseUrl ?? '')
  const [apiKey, setApiKey] = useState('')
  const [nativeRerank, setNativeRerank] = useState(instance?.nativeRerank ?? false)
  const [gatewayApi, setGatewayApi] = useState(instance?.api ?? 'ANTHROPIC_MESSAGES')
  const [blockingCalls, setBlockingCalls] = useState(instance?.blockingCalls ?? false)
  const [headerRows, setHeaderRows] = useState<{ name: string; value: string }[]>(
    Object.entries(instance?.headers ?? {}).map(([n, v]) => ({ name: n, value: v })))
  const [error, setError] = useState<string | null>(null)
  const { dirty } = useFormDirty({ name, protocol, baseUrl, apiKey, nativeRerank, gatewayApi, blockingCalls, headerRows })

  const protocolInfo = protocols.find(p => p.value === protocol)

  const save = useMutation({
    mutationFn: () => {
      const headers: Record<string, string> = {}
      headerRows.forEach(h => { if (h.name.trim()) headers[h.name.trim()] = h.value })
      const input = {
        name, protocol, baseUrl,
        // Only send a key when one was typed - an untouched field must not clear what is stored.
        ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}),
        headers,
        nativeRerank,
        // A gateway's own settings, sent only for a protocol that has them.
        ...(protocolInfo?.apis.length ? { api: gatewayApi } : {}),
        ...(protocolInfo?.mayCallWithoutStreaming ? { blockingCalls } : {}),
      }
      return editing ? providersApi.update(instance!.id, input) : providersApi.create(input)
    },
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
      initialFocus={editing ? 'panel' : 'first-field'}
      size="form"
      // Capped to the window (the kit's `cap`), with the BODY as its one scrolling region: the
      // headers and the rates are lists of no fixed length, and a provider with many of either
      // scrolls the form rather than growing the dialog past the screen - or squeezing a list into a
      // box of its own with a scrollbar beside its row actions, as the rates did until 0.28.0.
      cap
      ariaLabel={editing ? t('Edit provider') : t('Add provider')}
    >
      {requestClose => (<>
        <DialogHeader title={editing ? t('Edit provider') : t('Add provider')} />
        <DialogBody scroll>
          {/* Two columns from `lg`: the provider and how to reach it on the left - its address, its
              key and the headers sent with every request (moved here from the right in the 0.28.0
              feedback) - and what it can do and costs on the right. */}
          <FormColumns from="lg" density="columns" className="items-start">
            <FormStack className="min-w-0">
              {!editing && (
                <Field
                  label={t('Start from')}
                  hint={t('Fills in the protocol and address. Anything speaking one of these protocols works, listed here or not.')}
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
              )}

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
                    placeholder={protocol === 'AZURE_OPENAI'
                      ? 'https://my-resource.openai.azure.com'
                      : protocol === 'LITELLM'
                        ? 'https://litellm.company.com'
                        : t('Leave blank for this protocol default')}
                  />
                )}
              </Field>

              {/* THE ONE STORED-SECRET CONTROL (the kit SecretInput): the stored key shows masked,
                  and typing replaces it. It was a raw password input with the masked key in its
                  placeholder, beside the SecretInput every other secret in Settings used. */}
              <Field label={t('API key')}>
                {id => (
                  <SecretInput
                    id={id}
                    value={apiKey}
                    serverValue={instance?.apiKeyConfigured ? instance.apiKeyMasked ?? '' : undefined}
                    onChange={setApiKey}
                    placeholder={instance?.apiKeyConfigured
                      ? t('Stored ({masked}) - type to replace', { masked: instance.apiKeyMasked ?? '' })
                      : 'sk-...'}
                    labels={secretLabels}
                  />
                )}
              </Field>

              <ProviderHeadersField rows={headerRows} onChange={setHeaderRows} />
            </FormStack>

            <FormStack className="min-w-0">
              {/* "Runs locally" and "Cost reporting" used to sit either side of this. Both are gone:
                  cost is now always the configured rate times the tokens counted, so neither a
                  provider-reported figure nor a "this one is free" assertion has anything to change.
                  Reranking is what is left, and one checkbox is not a two-column grid. */}
              {/* What the chosen protocol can do: each eases in and out as the protocol changes. */}
              <Presence show={!!protocolInfo?.maySupportNativeRerank}>
                <Field
                  label={t('Reranking')}
                  hint={t('For servers with a /v1/rerank endpoint: vLLM, llama.cpp, Jina, Cohere, Together AI and others. Leave off and reranking uses embeddings instead.')}
                >
                  <Checkbox checked={nativeRerank} onChange={setNativeRerank}>
                    {t('Serves /v1/rerank')}
                  </Checkbox>
                </Field>
              </Presence>

              <Presence show={!!protocolInfo && protocolInfo.apis.length > 0}>
                <Field
                  label={t('API')}
                  hint={t('Anthropic Messages keeps Claude models in their own format, with prompt caching. OpenAI Chat Completions suits every other model.')}
                >
                  {id => (
                    <Select
                      id={id}
                      value={gatewayApi}
                      onChange={setGatewayApi}
                      options={(protocolInfo?.apis ?? []).map(a => ({ value: a.value, label: a.label }))}
                    />
                  )}
                </Field>
              </Presence>

              <Presence show={!!protocolInfo?.mayCallWithoutStreaming}>
                <Field
                  label={t('Streaming')}
                  hint={t('For a deployment that answers with one reply instead of a stream once tools are in use. Answers then appear whole, step by step.')}
                >
                  <Checkbox checked={blockingCalls} onChange={setBlockingCalls}>
                    {t('Wait for whole replies')}
                  </Checkbox>
                </Field>
              </Presence>

              {/* Editing only. Rates are keyed on the provider's NAME (see ProviderPricingModal), so
                  there is nothing to hang one on until this provider has been saved once. Its own CRUD
                  commits immediately and is deliberately NOT staged behind this form's Save - one button
                  committing a credential change and a price change together would be two decisions
                  wearing one label. */}
              {editing && instance && <ProviderPricingSection instance={instance} />}
            </FormStack>
          </FormColumns>
        </DialogBody>
        <DialogFooter start={error && <InlineNotice tone="danger">{error}</InlineNotice>}>
          <Button variant="secondary" onClick={requestClose}>{t('Cancel')}</Button>
          <Button
            onClick={() => { setError(null); save.mutate() }}
            disabled={!name.trim() || save.isPending}
          >
            {save.isPending ? t('Saving...') : editing ? t('Save') : t('Add provider')}
          </Button>
        </DialogFooter>
      </>)}
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
        // Stacked below sm: the three buttons took the whole width of a phone and squeezed the name
        // out of the row, leaving only the protocol label overlapping the first button.
        className="flex-col sm:flex-row"
        leading={<BrandMark id={providerMarkId(instance.protocol)} size={16} className="text-brand-legible" />}
        title={instance.name}
        meta={<Caption as="span">{instance.protocolLabel}</Caption>}
        caption={<>
          <MetaList items={[
            <span key="url" className="break-all whitespace-normal">{instance.baseUrl || t('Default address')}</span>,
            instance.apiKeyConfigured
              ? t('key {masked}', { masked: instance.apiKeyMasked ?? '' })
              : t('no key'),
          ]} />
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
          <RowActions
            className="flex-wrap"
            destructive={
              <Button variant="danger-quiet" onClick={() => setConfirmDelete(true)}>
                <Trash2 size={14} />
                {t('Delete')}
              </Button>
            }
          >
            <Button variant="secondary" onClick={() => test.mutate()} disabled={test.isPending}>
              {test.isPending ? t('Testing...') : t('Test')}
            </Button>
            <Button variant="secondary" onClick={onEdit}>{t('Edit')}</Button>
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
            t('Any model role pointing at this provider stops working until you choose another one.'),
            t('No other provider is selected in its place - that stays your choice.'),
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
// rates cost tracking prices calls with (no rate counts as $0). Everything else on the AI &
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
    { key: 'embedding', label: t('Search: choose the embedding model that indexes your sources'), done: embeddingDone, open: () => onOpenTab('Search') },
    { key: 'ocr', label: t('Conversion: choose a vision model to read scanned pages (optional)'), done: ocrDone, open: () => onOpenTab('Conversion') },
    { key: 'rates', label: t('Model pricing: set rates to track cost (optional)'), done: ratesDone, open: () => { if (ratesFor) onOpenRates(ratesFor) } },
  ]

  return (
    <Presence show={show}>
      <div className="pt-4">
        <Callout tone="info" title={t('Next steps')} role="status">
          {/* A done step is a ticked line; one still to do is the link that goes to it. */}
          <RuleChecklist
            className="mt-1.5"
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
    <SettingsSections intro={t('Add any service that speaks a supported protocol. Two accounts on one service are two providers.')}>
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
            {t('A provider runs the AI models: the one that answers in chat and the one that indexes your sources. Add the service you have an API key for, or a local one such as Ollama.')}
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
// Immediate CRUD, not part of the enclosing ProviderModal's own Save: a rate is its own row with
// its own endpoint, exactly like ApiTokensTab's tokens, and staging it behind the provider form's
// Save would mean one button committing two unrelated kinds of change.
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
      description={t('What this provider charges per million tokens, used for every cost figure and spend quota. A model with no rate counts as $0.')}
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
          consequences={[t('Calls to this model will be estimated at $0 cost going forward.')]}
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

// A provider's custom headers, as a list like its rates below (0.28.0 feedback): the header's name
// over its value - a credential's value shown by its ends only (headerValue.ts) - with Edit and
// Delete, and "Add header" opening a small dialog like "Add model rate". Unlike the rates these are
// part of the PROVIDER form: an add, edit or delete here changes the form, and the provider's own
// Save writes them, in the same shape as ever (a name-to-value map, blank names dropped).
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
      description={t('Sent with every request. Gateways often need one - OpenRouter uses HTTP-Referer and X-Title for attribution.')}
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

// Add or edit one header - the same small dialog as a model rate's. Nothing is saved from here: the
// row goes back to the provider form, whose Save writes it.
function ProviderHeaderModal({ initial, taken, onSave, onClose }: {
  initial: HeaderRow | null
  taken: string[]
  onSave: (row: HeaderRow) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState(initial?.name ?? '')
  const [value, setValue] = useState(initial?.value ?? '')
  const { dirty } = useFormDirty({ name, value })
  const duplicate = taken.includes(name.trim().toLowerCase())
  const canSave = name.trim().length > 0 && !duplicate
  const save = () => { if (canSave) onSave({ name: name.trim(), value }) }

  return (
    <ModalShell
      onClose={onClose}
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
          <Button onClick={save} disabled={!canSave}>{initial ? t('Save') : t('Add header')}</Button>
        </DialogFooter>
      </>)}
    </ModalShell>
  )
}

// One shared modal for both add and edit, always scoped to ONE provider - `instance` is the
// provider whose dialog opened it, so there is no provider picker any more. When `editing` is set
// the model is disabled too (provider + model identify the row; the PATCH endpoint only accepts
// the 4 price fields) and only the prices are submitted as an update instead of a create.
//
// THE MODEL COMBOBOX ACTUALLY WORKS NOW. GET /api/v1/providers/{provider}/models resolves its path
// segment as an instance ID (see ProviderModelsController -> ProviderInstanceService.resolve),
// and the old Cost Settings version had only the provider NAME to pass - so every dropdown there
// failed to resolve and offered nothing to pick. Opened from the provider's own dialog, the id is
// simply at hand.
function ProviderPricingModal({ instance, editing, onClose }: {
  instance: ProviderInstance
  editing: ModelPricing | null
  onClose: () => void
}) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const isEdit = editing !== null
  const comboboxLabels = useModelComboboxLabels()

  const [model, setModel] = useState(editing?.model ?? '')
  const [inputPrice, setInputPrice] = useState(String(editing?.inputPricePerMillion ?? ''))
  const [outputPrice, setOutputPrice] = useState(String(editing?.outputPricePerMillion ?? ''))
  const [cacheReadPrice, setCacheReadPrice] = useState(String(editing?.cacheReadPricePerMillion ?? ''))
  const [cacheWritePrice, setCacheWritePrice] = useState(String(editing?.cacheWritePricePerMillion ?? ''))

  const { options: modelOptions, status: modelStatus, errorText: modelsError, onOpen: openModels } = useProviderModels(instance.id)

  const save = useMutation({
    mutationFn: () => {
      const data = {
        inputPricePerMillion: Number(inputPrice) || 0,
        outputPricePerMillion: Number(outputPrice) || 0,
        cacheReadPricePerMillion: Number(cacheReadPrice) || 0,
        cacheWritePricePerMillion: Number(cacheWritePrice) || 0,
      }
      return isEdit
        ? costPricingApi.update(editing.id, data)
        // The NAME, not the id: that is what the cost ledger records for every call and therefore
        // what a rate has to be keyed on to be found again. ProviderInstanceService carries these
        // rows across a rename so the two cannot drift apart.
        : costPricingApi.create({ provider: instance.name, model, ...data })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: keys.modelPricing() })
      addToast(isEdit ? t('Pricing updated') : t('Pricing added'), 'success')
      onClose()
    },
    onError: (e: unknown) => addToast(withReason(isEdit ? t('Failed to update pricing') : t('Failed to add pricing'), e), 'danger'),
  })

  const canSave = model.trim().length > 0
  const { dirty } = useFormDirty({ model, inputPrice, outputPrice, cacheReadPrice, cacheWritePrice })

  const priceField = (label: string, value: string, set: (v: string) => void) => (
    <Field label={label}>
      {id => (
        <Input
          id={id}
          type="number"
          step="0.01"
          value={value}
          onChange={e => set(e.target.value)}
          placeholder="0.00"
        />
      )}
    </Field>
  )

  return (
    <ModalShell
      onClose={onClose}
      closable={!save.isPending}
      dirty={dirty}
      size="md"
      ariaLabel={isEdit ? t('Edit rate') : t('Add model rate')}
    >
      {requestClose => (<>
        <DialogHeader title={isEdit ? t('Edit rate') : t('Add model rate')} subtitle={instance.name} />
        <DialogBody>
          <FormStack>
            {/* Provider + model identify the row (matches the PATCH endpoint's contract, which only
                accepts the 4 price fields), so an edit shows the model disabled. */}
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
                  disabled={isEdit}
                  labels={comboboxLabels}
                />
              )}
            </Field>

            <FormColumns>
              {priceField(t('Input $/1M tokens'), inputPrice, setInputPrice)}
              {priceField(t('Output $/1M tokens'), outputPrice, setOutputPrice)}
              {priceField(t('Cache read $/1M tokens'), cacheReadPrice, setCacheReadPrice)}
              {priceField(t('Cache write $/1M tokens'), cacheWritePrice, setCacheWritePrice)}
            </FormColumns>
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
