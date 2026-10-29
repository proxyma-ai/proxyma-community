import { useState, useEffect } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import {
  Badge, Button, Caption, Checkbox, Chip, Combobox, ConfirmDialog, DialogBody, DialogFooter, DialogHeader,
  EmptyState, Field, FieldNote, FormColumns, FormStack, Input, List, ListRow, PickBox, SearchInput, Select,
  SettingsSection, StepCard, SwitchRow, Textarea, TEXT,
} from '@/components/ui/kit'
import { PagedItems } from '@/components/ui/ListPager'
import { ListSkeleton, TitleAction } from '../pages/shared'
import { skillsApi } from '../services/skillsApi'
import { providersApi } from '../services/providersApi'
import { useProviderModels } from '../hooks/useProviderModels'
import {
  workflowsApi, MAX_STEPS,
  type WorkflowSummary, type WorkflowDetail, type WorkflowStep,
} from '../services/workflowsApi'
import { useShake } from '@/components/ui/kit'
import { useDebounce } from '@/hooks/useDebounce'
import { useMentionSuggestions } from '@/features/chat/hooks/useMentionSuggestions'
import { useToastStore } from '@/store/toastStore'
import { queryClient } from '@/app/QueryClient'
import { keys } from '@/services/queryKeys'
import { FEATURES } from '@/config/features'
import { useTranslation } from '@/i18n'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'

// - Workflows tab -
//
// Deliberately built as a near-copy of SkillsTab rather than a generalization of it, matching the
// same decision made on the backend (WorkflowService is a near-copy of ChatSkillService). The
// frontmatter is identical, so everything above the step list IS the skill editor; the step list is
// the only genuinely new UI in the feature.
//
// What is NOT here, and why: no "official" badge, no "Restore default", no undeletable rows.
// Workflows have no seeder, so there is nothing to restore a default from and nothing that must be
// permanent. Every workflow on screen was written by a person.
//
// The distinction the whole feature rests on, for anyone reading this cold: a SKILL is
// instructions - the agent reads them and decides what to do, so nothing can be drawn before it
// happens. A WORKFLOW is a program whose steps its author fixed, which is what lets the chat draw
// the whole plan before any of it runs.

export function WorkflowsTab() {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const { data: workflows = [], isLoading } = useQuery({
    queryKey: keys.chatWorkflows(), queryFn: workflowsApi.list,
  })
  const [modalTarget, setModalTarget] = useState<'new' | WorkflowSummary | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<WorkflowSummary | null>(null)

  const remove = useMutation({
    mutationFn: (id: string) => workflowsApi.remove(id),
    onSuccess: () => {
      setDeleteTarget(null)
      queryClient.invalidateQueries({ queryKey: keys.chatWorkflows() })
      addToast(t('Workflow deleted'), 'success')
    },
    onError: () => addToast(t('Failed to delete workflow'), 'danger'),
  })

  // ONE list on desktop, two groups on server.
  //
  // `mine` is computed server-side as "userId is set AND equals the requester's", and on desktop
  // both sides are null - so every workflow the single user writes comes back mine:false and would
  // file itself under "Shared", on an install that has nobody to share with. SkillsTab has this
  // exact quirk today; it is not reproduced here rather than being copied along with the layout.
  //
  // A FEATURES.auth check inside a shared component rather than two edition modules: pages/types.ts
  // reserves the edition split for whole controls that differ, and this is one heading, on a tab
  // that is otherwise identical in both.
  const grouped = FEATURES.auth
  const mine = grouped ? workflows.filter(w => w.mine) : workflows
  const others = grouped ? workflows.filter(w => !w.mine) : []
  const none = workflows.length === 0

  return (
    <div className="space-y-6">
      {!none && (
        <TitleAction>
          <Button onClick={() => setModalTarget('new')}>
            <Plus size={14} aria-hidden="true" />
            {t('Add workflow')}
          </Button>
        </TitleAction>
      )}
      <Caption>{t('An ordered set of steps the agent runs one at a time. Type "/" in the chat box and pick it by name.')}</Caption>

      <div>
        {isLoading ? (
          <ListSkeleton count={3} />
        ) : none ? (
          // Worth more than "No workflows yet.": this list starts empty on every install, since
          // nothing seeds it, so the empty state is the first thing every user sees here.
          // A sentence and its one action - no icon tile, no bordered box (F12).
          <EmptyState
            title={t('No workflows yet')}
            action={(
              <Button onClick={() => setModalTarget('new')}>
                <Plus size={14} aria-hidden="true" />
                {t('Add workflow')}
              </Button>
            )}
          >
            {t('Useful when the same job recurs in the same shape, such as a release check or a weekly summary.')}
          </EmptyState>
        ) : (
          <div className="space-y-6 animate-fade-in">
            {mine.length > 0 && (grouped ? (
              <SettingsSection title={t('My workflows')} gap="list">
                <WorkflowList rows={mine} onEdit={setModalTarget} onDelete={setDeleteTarget} />
              </SettingsSection>
            ) : (
              <WorkflowList rows={mine} onEdit={setModalTarget} onDelete={setDeleteTarget} />
            ))}
            {others.length > 0 && (
              <SettingsSection title={t('Shared')} gap="list">
                <WorkflowList rows={others} onEdit={setModalTarget} onDelete={setDeleteTarget} />
              </SettingsSection>
            )}
          </div>
        )}
      </div>

      {modalTarget && (
        <WorkflowModal
          workflow={modalTarget === 'new' ? null : modalTarget}
          readOnly={modalTarget !== 'new' && !modalTarget.canEdit}
          onClose={() => setModalTarget(null)}
        />
      )}

      {deleteTarget && (
        <ConfirmDialog
          title={t('Delete workflow')}
          description={(
            <>
              {t('Are you sure you want to {verb}', { verb: t('delete') })}{' '}
              <span className="font-semibold text-ink-primary">{deleteTarget.name}</span>?
            </>
          )}
          confirmLabel={t('Delete')}
          cancelLabel={t('Cancel')}
          pendingLabel={t('Deleting...')}
          pending={remove.isPending}
          onConfirm={() => remove.mutate(deleteTarget.id)}
          onCancel={() => setDeleteTarget(null)}
        />
      )}
    </div>
  )
}

function WorkflowList({ rows, onEdit, onDelete }: {
  rows: WorkflowSummary[]
  onEdit: (w: WorkflowSummary) => void
  onDelete: (w: WorkflowSummary) => void
}) {
  // Twenty a page (LIST_PAGE_SIZE), in the browser: the list is read from files, whole.
  return (
    <PagedItems items={rows}>
      {page => (
        <List>
          {page.map(w => (
            <WorkflowRow key={w.id} workflow={w} onEditClick={() => onEdit(w)} onDeleteClick={() => onDelete(w)} />
          ))}
        </List>
      )}
    </PagedItems>
  )
}

// Delete is offered exactly where canEdit is, unlike a skill row - the backend applies one rule to
// both (owner or Super Admin, see WorkflowService), because there is no built-in workflow that is
// editable but permanent. The row is the kit ListRow that opens and carries Delete (see SkillRow).
function WorkflowRow({ workflow, onEditClick, onDeleteClick }: {
  workflow: WorkflowSummary
  onEditClick: () => void
  onDeleteClick: () => void
}) {
  const { t } = useTranslation()
  return (
    <ListRow
      onClick={onEditClick}
      chevron
      title={workflow.name}
      meta={(
        <>
          {/* The step count is the one thing a workflow row says that a skill row cannot, and it
              is the number that decides whether someone runs it: every step is another turn
              carrying all the previous ones. A figure, so a numeric badge. */}
          <Badge label={t('{count} step|{count} steps', { count: workflow.stepCount })} numeric size="sm" />
          {workflow.shared && <Badge label={t('Shared')} variant="outline" size="sm" />}
          {/* A tag is a word the user typed - a Chip, not an upper-case status Badge. */}
          {workflow.tags.map(tag => <Chip key={tag} label={tag} />)}
        </>
      )}
      caption={workflow.shortDescription && <span className="block truncate">{workflow.shortDescription}</span>}
      action={workflow.canEdit && (
        <Button className="animate-fade-in" variant="danger-quiet" onClick={onDeleteClick} aria-label={t('Delete {name}', { name: workflow.name })}>
          {t('Delete')}
        </Button>
      )}
    />
  )
}

/** A step being edited. Position in the array IS the step's identity - see WorkflowStep.index. */
interface DraftStep {
  // Stable across reorders so React keeps each textarea's DOM node (and its caret) with its step
  // rather than with its position. Using the array index as the key made a "move up" look like the
  // text had jumped between two rows.
  key: string
  title: string
  instruction: string
}

let draftStepSeq = 0
function newDraftStep(title = '', instruction = ''): DraftStep {
  draftStepSeq += 1
  return { key: `s${draftStepSeq}`, title, instruction }
}

// Exported so the chat composer's attach menu can create a workflow inline, exactly as it already
// does with SkillModal - one import rather than a second copy of this form.
export function WorkflowModal({ workflow, onClose, readOnly = false, onSaved }: {
  workflow: WorkflowSummary | null
  onClose: () => void
  readOnly?: boolean
  onSaved?: (workflow: WorkflowDetail) => void
}) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const isEdit = workflow !== null
  const { data: existing } = useQuery({
    queryKey: keys.chatWorkflow(workflow?.id ?? ''),
    queryFn: () => workflowsApi.get(workflow!.id),
    enabled: isEdit,
  })
  const { data: allTools = [] } = useQuery({ queryKey: keys.chatTools(), queryFn: skillsApi.listTools })
  const [toolSearch, setToolSearch] = useState('')
  // Inlined rather than imported from SettingsModal's own useProviderInstances: this file is
  // imported by the modal, so importing it back would be a cycle.
  const { data: providerInstances = [] } = useQuery({
    queryKey: keys.providerInstances(), queryFn: providersApi.list,
  })

  const [name, setName] = useState(workflow?.name ?? '')
  const [nameError, setNameError] = useState('')
  const nameShake = useShake()
  const [shortDescription, setShortDescription] = useState(workflow?.shortDescription ?? '')
  const [shortDescriptionError, setShortDescriptionError] = useState('')
  const shortDescriptionShake = useShake()
  const [preamble, setPreamble] = useState('')
  const [steps, setSteps] = useState<DraftStep[]>([newDraftStep()])
  const [stepsError, setStepsError] = useState('')
  const [tagsInput, setTagsInput] = useState('')
  const [tools, setTools] = useState<string[]>([])
  const [connectorNames, setConnectorNames] = useState<string[]>([])
  const [connectorSearch, setConnectorSearch] = useState('')
  const debouncedConnectorSearch = useDebounce(connectorSearch.trim(), 300)
  const { data: connectorSuggestions = [] } = useMentionSuggestions(debouncedConnectorSearch ? debouncedConnectorSearch : null)
  const availableConnectorSuggestions = connectorSuggestions.filter(r => !connectorNames.includes(r.name))
  const [shared, setShared] = useState(workflow?.shared ?? false)
  // '' on both means "run on the main chat model", which is what every workflow did before this
  // field existed and is still the default for a new one.
  const [provider, setProvider] = useState('')
  const [model, setModel] = useState('')
  const [modelError, setModelError] = useState('')

  // What the form holds, against what it opened with. A step's `key` is React bookkeeping, not
  // content, and tools and connectors are sets - so none of those alone is a change.
  const formValues = {
    name, shortDescription, preamble, tagsInput, shared, provider, model,
    steps: steps.map(s => ({ title: s.title, instruction: s.instruction })),
    tools: [...tools].sort(), connectorNames: [...connectorNames].sort(),
  }
  const { dirty: formDirty, rebase } = useFormDirty(formValues)
  const dirty = !readOnly && formDirty

  // The seeded values become the baseline, so the fill itself does not read as an edit - see the
  // same effect in SkillModal.
  useEffect(() => {
    if (existing) {
      const seededSteps = existing.steps.length > 0
        ? existing.steps.map(s => newDraftStep(s.title, s.instruction))
        : [newDraftStep()]
      setPreamble(existing.preamble)
      setSteps(seededSteps)
      setTagsInput(existing.tags.join(', '))
      setTools(existing.tools)
      setConnectorNames(existing.connectorNames)
      setProvider(existing.provider ?? '')
      setModel(existing.model ?? '')
      rebase({
        name: workflow?.name ?? '',
        shortDescription: workflow?.shortDescription ?? '',
        preamble: existing.preamble,
        tagsInput: existing.tags.join(', '),
        shared: workflow?.shared ?? false,
        provider: existing.provider ?? '',
        model: existing.model ?? '',
        steps: seededSteps.map(s => ({ title: s.title, instruction: s.instruction })),
        tools: [...existing.tools].sort(),
        connectorNames: [...existing.connectorNames].sort(),
      })
    }
    // `workflow` is the summary this modal was opened for and does not change while it is open.
  }, [existing, rebase])

  const save = useMutation({
    mutationFn: () => {
      const tags = tagsInput.split(',').map(t => t.trim()).filter(Boolean)
      // index is re-derived from position by the backend, which ignores whatever is sent - position
      // IS the identity, and trusting a client-supplied index would let a malformed request produce
      // a workflow whose steps run in an order this editor never showed.
      const payloadSteps: WorkflowStep[] = steps.map((s, i) => ({
        index: i, title: s.title.trim(), instruction: s.instruction.trim(),
      }))
      const body = {
        name, shortDescription, preamble, steps: payloadSteps, tags, tools, connectorNames,
        // Sent as null rather than '' so "no override" is one value on the wire, and the same one
        // the backend writes back when it omits both keys from the file.
        provider: provider || null,
        model: model.trim() || null,
      }
      return isEdit
        ? workflowsApi.update(workflow.id, { ...body, shared })
        : workflowsApi.create(body)
    },
    onSuccess: (saved) => {
      queryClient.invalidateQueries({ queryKey: keys.chatWorkflows() })
      if (isEdit) queryClient.invalidateQueries({ queryKey: keys.chatWorkflow(workflow.id) })
      onSaved?.(saved)
      onClose()
    },
    onError: (e: unknown) => {
      // The backend refuses three things here and writes every message to be read by the author:
      // a step list that is empty or past MAX_STEPS (which explains the token cost), a name a SKILL
      // already holds ("/" is one namespace), and a provider chosen with no model. Shown verbatim
      // against the field at fault rather than flattened into a generic "failed to save".
      const data = (e as { response?: { data?: { error?: string; untranslated?: { error?: string } } } })?.response?.data
      const message = data?.error
      // WHICH FIELD is read off the ENGLISH wording, which the HTTP client keeps beside the
      // translated sentence - a Vietnamese message matches neither pattern below.
      const english = data?.untranslated?.error ?? message
      // Tested before the name pattern, not after: the model message reads "This workflow NAMES a
      // provider ...", so /name/i matches it too and would file it under the name field.
      if (message && english && /provider|model/i.test(english)) {
        setModelError(message)
      } else if (message && english && /name/i.test(english)) {
        setNameError(message)
        nameShake.trigger()
      } else if (message) {
        setStepsError(message)
      } else {
        addToast(isEdit ? t('Failed to update workflow') : t('Failed to create workflow'), 'danger')
      }
    },
  })

  const handleSave = () => {
    const trimmedName = name.trim()
    const trimmedShortDescription = shortDescription.trim()
    const filled = steps.filter(s => s.title.trim() || s.instruction.trim())
    let valid = true
    if (!trimmedName) {
      setNameError(t('Name is required'))
      nameShake.trigger()
      valid = false
    } else {
      setNameError('')
    }
    if (!trimmedShortDescription) {
      setShortDescriptionError(t('Short description is required'))
      shortDescriptionShake.trigger()
      valid = false
    } else {
      setShortDescriptionError('')
    }
    // Checked here as well as server-side, because the server's answer to an empty list is a 400
    // and this one can point at the thing that is empty.
    if (filled.length === 0) {
      setStepsError(t('A workflow needs at least one step.'))
      valid = false
    } else {
      setStepsError('')
    }
    // Same rule the backend enforces, checked here so it is reported next to the empty control
    // rather than as a 400 after a round trip. A model with no provider is NOT an error - it means
    // "the chat role's provider, this model".
    if (provider && !model.trim()) {
      setModelError(t('Pick a model from this provider, or set the provider back to the chat model.'))
      valid = false
    } else {
      setModelError('')
    }
    if (!valid) return
    save.mutate()
  }

  const updateStep = (key: string, patch: Partial<DraftStep>) => {
    if (readOnly) return
    setSteps(prev => prev.map(s => (s.key === key ? { ...s, ...patch } : s)))
  }

  const addStep = () => {
    if (readOnly || steps.length >= MAX_STEPS) return
    setSteps(prev => [...prev, newDraftStep()])
    setStepsError('')
  }

  const removeStep = (key: string) => {
    if (readOnly) return
    // Never drops to zero - the form always shows one row, because an editor with no fields at all
    // looks broken rather than empty.
    setSteps(prev => (prev.length <= 1 ? [newDraftStep()] : prev.filter(s => s.key !== key)))
  }

  const moveStep = (from: number, to: number) => {
    if (readOnly || to < 0 || to >= steps.length) return
    setSteps(prev => {
      const next = [...prev]
      const [moved] = next.splice(from, 1)
      next.splice(to, 0, moved)
      return next
    })
  }

  const toggleTool = (toolName: string) => {
    if (readOnly) return
    setTools(prev => prev.includes(toolName) ? prev.filter(t => t !== toolName) : [...prev, toolName])
  }

  const addConnector = (n: string) => {
    if (readOnly) return
    setConnectorNames(prev => prev.includes(n) ? prev : [...prev, n])
  }

  const removeConnector = (n: string) => {
    if (readOnly) return
    setConnectorNames(prev => prev.filter(x => x !== n))
  }

  // Unfiltered, same as the Settings model roles: nothing declares per-role capability any more
  // (see ProviderProtocol), and a step whose provider cannot chat fails the run with the reason.
  const chatProviders = providerInstances
  // A workflow can name an instance that has since been deleted. Showing the select as though
  // nothing were chosen would hide that; it stays on a value it cannot name, so say so.
  const providerMissing = provider !== '' && !chatProviders.some(p => p.id === provider)
  const { options: modelOptions, status: modelStatus, errorText: modelsError, onOpen: openModels } = useProviderModels(provider)

  const filteredTools = toolSearch.trim()
    ? allTools.filter(t =>
        t.name.toLowerCase().includes(toolSearch.toLowerCase()) ||
        t.description.toLowerCase().includes(toolSearch.toLowerCase()))
    : allTools

  return (
    <ModalShell
      onClose={onClose}
      closable={!save.isPending}
      dirty={dirty}
      initialFocus={isEdit ? 'panel' : 'first-field'}
      size="full"
      fill
      ariaLabel={readOnly && workflow ? workflow.name : isEdit ? t('Edit workflow') : t('Add workflow')}
    >
      {requestClose => (<>
        <DialogHeader title={readOnly && workflow ? workflow.name : isEdit ? t('Edit workflow') : t('Add workflow')} />
        {/* THREE COLUMNS FROM lg, since 2026-09-27 (the maintainer's rule that no dialog
            scrolls): what the workflow is, its steps, and what it runs with. The panel has a
            fixed height there, and the two lists of unknown length - the steps and the tools -
            fill their columns and scroll inside them. One column below lg, where the body is the
            one region that scrolls; the panel is max-w-3xl there, since a step row is a title and
            a multi-line instruction with reorder controls. */}
        <DialogBody scroll className="flex flex-col">
          <FormColumns cols={3} from="lg" density="columns" className="lg:grid-rows-1 lg:flex-1 lg:min-h-0">
          <FormStack className="min-w-0">
            <Field label={t('Name')} description={t("What you type after \"/\" in the chat box to run it. Must not match a skill's name.")}>
              {id => (
                <Input
                  id={id}
                  value={name}
                  onChange={e => setName(e.target.value)}
                  placeholder={t('e.g. Release readiness')}
                  error={nameError}
                  shake={nameShake.shaking}
                  disabled={readOnly}
                />
              )}
            </Field>

            <Field label={t('Short description')} description={t('Shown in the "/" list and to the agent, so it knows this workflow exists.')}>
              {id => (
                <Textarea
                  id={id}
                  value={shortDescription}
                  onChange={e => setShortDescription(e.target.value)}
                  placeholder={t('Check whether a release is ready to ship')}
                  rows={2}
                  disabled={readOnly}
                  error={shortDescriptionError || undefined}
                  shake={shortDescriptionShake.shaking}
                />
              )}
            </Field>

            <Field label={t('Context')} description={t('Optional. Given once before the first step and kept for every step.')}>
              {id => (
                <Textarea
                  id={id}
                  value={preamble}
                  onChange={e => setPreamble(e.target.value)}
                  placeholder={t('The release version is whatever the user named when they invoked this...')}
                  rows={3}
                  disabled={readOnly}
                />
              )}
            </Field>

            <Field label={t('Tags')} description={t('Comma-separated, used to organize your workflows list.')}>
              {id => (
                <Input
                  id={id}
                  value={tagsInput}
                  onChange={e => setTagsInput(e.target.value)}
                  placeholder="release, qa"
                  disabled={readOnly}
                />
              )}
            </Field>

            {/* Same rule as the skill modal: create() never sends `shared`, so there is nothing for
                this control to do until the workflow exists. */}
            {isEdit && (
              <SwitchRow
                className="py-0"
                title={t('Shared')}
                caption={t('Visible and runnable by every user, not just you.')}
                checked={shared}
                onChange={setShared}
                disabled={readOnly}
              />
            )}
          </FormStack>

          <div className="min-w-0 lg:min-h-0 lg:flex lg:flex-col">
            <Field
              label={t('Steps')}
              aside={t('{done} of {total}', { done: String(steps.length), total: String(MAX_STEPS) })}
              description={t('Each step is one turn, run in order, so later steps can use earlier findings. Every step also re-reads all previous ones, adding cost.')}
              error={stepsError || undefined}
              className="lg:flex-1 lg:min-h-0"
            >
              {/* A numbered card per step, with its reorder and remove controls - a list of unknown
                  length, so it scrolls inside the column. */}
              <div className="space-y-3 max-h-96 lg:max-h-none lg:flex-1 lg:min-h-0 overflow-y-auto">
                {steps.map((step, i) => (
                  <StepCard
                    key={step.key}
                    index={i}
                    count={steps.length}
                    head={(
                      <Input
                        value={step.title}
                        onChange={e => updateStep(step.key, { title: e.target.value })}
                        placeholder={t('What this step is called')}
                        disabled={readOnly}
                      />
                    )}
                    onMove={readOnly ? undefined : moveStep}
                    onRemove={readOnly ? undefined : () => removeStep(step.key)}
                    labels={{
                      up: t('Move step {number} up', { number: i + 1 }),
                      down: t('Move step {number} down', { number: i + 1 }),
                      remove: t('Remove step {number}', { number: i + 1 }),
                    }}
                  >
                    <Textarea
                      value={step.instruction}
                      onChange={e => updateStep(step.key, { instruction: e.target.value })}
                      placeholder={t('What the agent should do in this step, and what it should report back.')}
                      rows={3}
                      disabled={readOnly}
                    />
                  </StepCard>
                ))}
              </div>
            </Field>

            {!readOnly && (
              <div className="mt-3 animate-fade-in">
                {steps.length >= MAX_STEPS ? (
                  <Caption className="animate-fade-in">
                    {t('That is the maximum. Split longer procedures into two workflows, since cost grows faster than the list.')}
                  </Caption>
                ) : (
                  <Button className="animate-fade-in" variant="secondary" onClick={addStep}>
                    <Plus size={14} aria-hidden="true" />
                    {t('Add step')}
                  </Button>
                )}
              </div>
            )}
          </div>

          <div className="min-w-0 space-y-4 lg:min-h-0 lg:flex lg:flex-col">
            <Field
              label={t('Model')}
              description={t('Optional. Pick a cheaper model and every step runs on it, instead of costing the main chat model once per step.')}
              error={modelError || undefined}
            >
              {/* One column below sm, same as the Settings model rows: at that width neither control
                  is wide enough for a model name, which is the longest string either one holds. */}
              <FormColumns className="items-start">
                <div>
                  <Select
                    value={provider}
                    // Clears the model with it, for the reason spelled out on ProviderModelRow in
                    // SettingsModal: a model name means nothing outside the catalogue it came from,
                    // and one carried across a provider change is a selection that looks chosen and
                    // fails on the first step of the first run. Back to "Main chat model" (v === '')
                    // clears it too - there is no model to name when the workflow inherits one.
                    onChange={v => { setProvider(v); setModel(''); setModelError('') }}
                    disabled={readOnly}
                    options={[
                      { value: '', label: t('Main chat model') },
                      ...chatProviders.map(p => ({ value: p.id, label: p.name })),
                    ]}
                  />
                  {providerMissing && (
                    <FieldNote error={t('The provider this was set to is gone - pick another, or go back to the main chat model.')} />
                  )}
                </div>
                <Combobox
                  value={model}
                  onChange={v => { setModel(v); setModelError('') }}
                  placeholder={provider ? t('Model name') : t('Same as chat')}
                  options={modelOptions}
                  status={modelStatus}
                  errorText={modelsError}
                  labels={{ loading: t('Loading models...'), failed: t('Failed to load models'), empty: t('No models found') }}
                  // The kit Combobox has a disabled state, so the read-only view is the same
                  // control, greyed - it was a disabled Input standing in for one.
                  disabled={readOnly}
                  // Never asks a provider that was not chosen: with no id the request would be to
                  // /api/v1/providers//models, which is not a route.
                  onOpen={() => { if (provider) openModels() }}
                />
              </FormColumns>
            </Field>

            <Field label={t('Connectors')} description={t('Prefer these connectors when the agent searches during this workflow - a preference, not a restriction.')}>
              <div className="space-y-2">
                {/* Disabled while read-only: nothing can be added, and the field stays in place. */}
                <SearchInput
                  value={connectorSearch}
                  onChange={setConnectorSearch}
                  placeholder={t('Search connectors by name...')}
                  ariaLabel={t('Connectors')}
                  clearLabel={t('Clear search')}
                  disabled={readOnly}
                />
                {connectorSearch.trim() && (
                  <PickBox>
                    {availableConnectorSuggestions.length === 0 ? (
                      <EmptyState className="py-2">{t('No matching connectors.')}</EmptyState>
                    ) : (
                      <List>
                        {availableConnectorSuggestions.map(r => (
                          <ListRow
                            key={r.id}
                            className="py-2"
                            onClick={() => addConnector(r.name)}
                            disabled={readOnly}
                            title={r.name}
                            trailing={<span className={TEXT.caption}>{r.type}</span>}
                          />
                        ))}
                      </List>
                    )}
                  </PickBox>
                )}
                {connectorNames.length > 0 && (
                  <div className="flex flex-wrap gap-2 animate-fade-in">
                    {connectorNames.map(n => (
                      <Chip
                        key={n}
                        label={n}
                        onRemove={readOnly ? undefined : () => removeConnector(n)}
                        removeLabel={t('Remove {name}', { name: n })}
                      />
                    ))}
                  </div>
                )}
              </div>
            </Field>

            <Field
              label={t('Tools')}
              description={t('Platform tools the agent may use in every step of this workflow.')}
              className="lg:flex-1 lg:min-h-0"
            >
              <div className="space-y-2 lg:flex-1 lg:min-h-0 lg:flex lg:flex-col">
                <SearchInput
                  value={toolSearch}
                  onChange={setToolSearch}
                  placeholder={t('Search tools...')}
                  ariaLabel={t('Tools')}
                  clearLabel={t('Clear search')}
                  disabled={readOnly}
                />
                <PickBox maxHeight="max-h-72" fill>
                  {filteredTools.length === 0 ? (
                    <EmptyState className="py-3">
                      {allTools.length === 0 ? t('No tools available.') : t('No tools match your search.')}
                    </EmptyState>
                  ) : (
                    <List>
                      {filteredTools.map(tool => (
                        <Checkbox
                          key={tool.name}
                          className="flex py-2"
                          checked={tools.includes(tool.name)}
                          onChange={() => toggleTool(tool.name)}
                          disabled={readOnly}
                          hint={tool.description}
                        >
                          {tool.name}
                        </Checkbox>
                      ))}
                    </List>
                  )}
                </PickBox>
              </div>
            </Field>
          </div>
          </FormColumns>
        </DialogBody>
        {/* No footer until something has changed, Cancel and Save once it has - the same rule as
            SkillModal, for the same reason. Read-only never has one: it was a lone Close, and a
            dialog closes on its backdrop and Escape. */}
        {!readOnly && dirty && (
          <DialogFooter>
            <Button variant="secondary" onClick={requestClose} disabled={save.isPending}>{t('Cancel')}</Button>
            <Button onClick={handleSave} disabled={save.isPending}>
              {save.isPending ? t('Saving...') : t('Save')}
            </Button>
          </DialogFooter>
        )}
      </>)}
    </ModalShell>
  )
}
