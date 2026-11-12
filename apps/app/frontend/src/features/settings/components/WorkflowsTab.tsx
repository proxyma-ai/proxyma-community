import { useState, useEffect, useMemo } from 'react'
import { MarkdownField } from '@/components/ui/MarkdownField'
import { EditableText } from '@/components/ui/EditableText'
import { EditableMarkdown } from '@/components/ui/EditableMarkdown'
import { ComboboxAutosave, quietly } from '@/components/ui/AutosaveFields'
import { useAutosave } from '@/components/ui/useAutosave'
import { SaveStatus } from '@/components/ui/SaveStatus'
import { commaList, sameSet } from '@/components/ui/autosave'
import { useQuery, useMutation } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import {
  Badge, Button, Caption, Checkbox, Chip, Combobox, ConfirmDialog, DialogBody, DialogFooter, DialogHeader, EmptyState, Field, FieldNote, FormColumns, FormStack, Input, List, ListRow, PickBox, SearchInput, Select, Presence, SettingsSection, StepCard, SwitchRow, Textarea, TEXT, IconButton,
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
import { catalogTypeName } from '@/features/indexing/utils/catalogRegistry'

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
      <Caption>{t('Steps the agent runs in order. Type "/" in chat to start one.')}</Caption>

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
            {t('For a job you repeat the same way, like a weekly summary.')}
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
          description={t('Are you sure you want to {verb}', { verb: t('delete') })}
          subject={`${deleteTarget.name}?`}
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
      // ROW ACTIONS ARE ICONS in every Settings list (the maintainer, 2026-11-10), as Connections' and Providers' are.
      action={workflow.canEdit && (
        <IconButton icon={Trash2} tone="danger" onClick={onDeleteClick}
          label={t('Delete {name}', { name: workflow.name })} tooltip={t('Delete')} />
      )}
    />
  )
}

/** A step being edited in the NEW workflow's form. Position in the array IS the step's identity - see WorkflowStep.index. */
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

/**
 * WHICH FIELD a refusal belongs to. The backend refuses three things and writes every message to be
 * read by the author: a step list that is empty or past MAX_STEPS (which explains the token cost), a
 * name a SKILL already holds ("/" is one namespace), and a provider chosen with no model. Shown
 * verbatim against the field at fault rather than flattened into a generic "failed to save". Null
 * when the server said nothing to show.
 */
function refusedField(e: unknown): { field: 'model' | 'name' | 'steps'; message: string } | null {
  const data = (e as { response?: { data?: { error?: string; untranslated?: { error?: string } } } })?.response?.data
  const message = data?.error
  if (!message) return null
  // WHICH FIELD is read off the ENGLISH wording, which the HTTP client keeps beside the translated
  // sentence - a Vietnamese message matches neither pattern below.
  const english = data?.untranslated?.error ?? message
  // Tested before the name pattern, not after: the model message reads "This workflow NAMES a
  // provider ...", so /name/i matches it too and would file it under the name field.
  if (/provider|model/i.test(english)) return { field: 'model', message }
  if (/name/i.test(english)) return { field: 'name', message }
  return { field: 'steps', message }
}

// Exported so the chat composer's attach menu can create a workflow inline, exactly as it already
// does with SkillModal - one import rather than a second copy of this form.
//
// TWO DIALOGS BEHIND ONE NAME, as SkillModal (the maintainer, 2026-11-02): ADDING a workflow is a
// form with its Save button; an EXISTING workflow the reader may change is edited where it is read,
// field by field, each saved as it is left (WorkflowEditDialog). One the reader may only read is the
// form's read-only view.
export function WorkflowModal({ workflow, onClose, readOnly = false, onSaved }: {
  workflow: WorkflowSummary | null
  onClose: () => void
  readOnly?: boolean
  onSaved?: (workflow: WorkflowDetail) => void
}) {
  if (workflow && !readOnly) return <WorkflowEditDialog workflow={workflow} onClose={onClose} onSaved={onSaved} />
  return <WorkflowForm workflow={workflow} onClose={onClose} onSaved={onSaved} />
}

/** What an existing workflow's dialog saves: the update request, a field at a time over the rest. */
interface WorkflowFields {
  name: string
  shortDescription: string
  preamble: string
  steps: { title: string; instruction: string }[]
  tags: string[]
  tools: string[]
  connectorNames: string[]
  shared: boolean
  provider: string | null
  model: string | null
}

function fieldsOf(w: WorkflowDetail): WorkflowFields {
  return {
    name: w.name, shortDescription: w.shortDescription, preamble: w.preamble,
    steps: w.steps.map(s => ({ title: s.title, instruction: s.instruction })),
    tags: w.tags, tools: w.tools, connectorNames: w.connectorNames, shared: w.shared,
    provider: w.provider ?? null, model: w.model ?? null,
  }
}

/**
 * AN EXISTING WORKFLOW, EDITED IN PLACE - as SkillEditDialog, plus its steps and its model. A step's
 * title and instruction are click-to-edit; moving or removing one saves at once. "Add step" opens a
 * step that is this dialog's alone until something is written in it - an empty step is never sent -
 * and the last step cannot be removed, since a workflow with none is refused. A provider chosen is
 * held here, with the note that says so, until its model is chosen: the server refuses a provider
 * with no model, and both are then saved together.
 */
function WorkflowEditDialog({ workflow, onClose, onSaved }: {
  workflow: WorkflowSummary
  onClose: () => void
  onSaved?: (workflow: WorkflowDetail) => void
}) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const { data: existing } = useQuery({
    queryKey: keys.chatWorkflow(workflow.id),
    queryFn: () => workflowsApi.get(workflow.id),
  })
  // Inlined rather than imported from SettingsModal's own useProviderInstances: this file is
  // imported by the modal, so importing it back would be a cycle.
  const { data: providerInstances = [] } = useQuery({
    queryKey: keys.providerInstances(), queryFn: providersApi.list,
  })
  const [nameError, setNameError] = useState('')
  const [stepsError, setStepsError] = useState('')
  const [modelError, setModelError] = useState('')
  // A step opened by "Add step" and not yet written in.
  const [adding, setAdding] = useState(false)
  // A provider chosen and not yet saved, waiting for its model.
  const [heldProvider, setHeldProvider] = useState<string | null>(null)

  const base = useMemo(() => (existing ? fieldsOf(existing) : undefined), [existing])
  const { value, save, status } = useAutosave<WorkflowFields>(base, async next => {
    const saved = await workflowsApi.update(workflow.id, {
      name: next.name,
      shortDescription: next.shortDescription,
      preamble: next.preamble,
      // index is re-derived from position by the backend, which ignores whatever is sent - position
      // IS the identity.
      steps: next.steps.map((s, i): WorkflowStep => ({ index: i, title: s.title.trim(), instruction: s.instruction.trim() })),
      tags: next.tags,
      tools: next.tools,
      connectorNames: next.connectorNames,
      shared: next.shared,
      // Sent as null rather than '' so "no override" is one value on the wire.
      provider: next.provider || null,
      model: next.model?.trim() || null,
    })
    queryClient.invalidateQueries({ queryKey: keys.chatWorkflows() })
    queryClient.invalidateQueries({ queryKey: keys.chatWorkflow(workflow.id) })
    onSaved?.(saved)
    return fieldsOf(saved)
  }, {
    failure: t('Failed to update workflow'),
    // A refusal the server explains goes under its field; the field itself goes back.
    onError: e => {
      const refused = refusedField(e)
      if (!refused) addToast(t('Failed to update workflow'), 'danger')
      else if (refused.field === 'model') setModelError(refused.message)
      else if (refused.field === 'name') setNameError(refused.message)
      else setStepsError(refused.message)
    },
    onSaved: () => { setNameError(''); setStepsError(''); setModelError('') },
  })

  // Until the detail arrives, the summary's own fields stand in - shown, not yet editable.
  const v: WorkflowFields = value ?? {
    name: workflow.name, shortDescription: workflow.shortDescription, preamble: '', steps: [], tags: workflow.tags,
    tools: [], connectorNames: [], shared: workflow.shared, provider: null, model: null,
  }
  const loaded = value !== undefined

  const setStep = (i: number, patch: Partial<{ title: string; instruction: string }>) =>
    save({ steps: v.steps.map((s, j) => (j === i ? { ...s, ...patch } : s)) })
  const moveStep = (from: number, to: number) => {
    if (to < 0 || to >= v.steps.length) return
    const next = [...v.steps]
    const [moved] = next.splice(from, 1)
    next.splice(to, 0, moved)
    quietly(save({ steps: next }))
  }
  // The new step joins the list the moment something is written in it, and comes back if that is refused.
  const addStep = (step: { title: string; instruction: string }) => {
    setAdding(false)
    return save({ steps: [...v.steps, step] }).catch(e => { setAdding(true); throw e })
  }

  // Unfiltered, same as the Settings model roles: nothing declares per-role capability any more
  // (see ProviderProtocol), and a step whose provider cannot chat fails the run with the reason.
  const chatProviders = providerInstances
  const provider = heldProvider ?? v.provider ?? ''
  const model = heldProvider !== null ? '' : (v.model ?? '')
  // A workflow can name an instance that has since been deleted; the select stays on a value it
  // cannot name, so say so.
  const providerMissing = provider !== '' && !chatProviders.some(p => p.id === provider)
  const { options: modelOptions, status: modelStatus, errorText: modelsError, onOpen: openModels } = useProviderModels(provider)
  const pickModel = t('Pick a model, or use the chat model.')
  const stepCount = v.steps.length + (adding ? 1 : 0)

  return (
    <ModalShell onClose={onClose} initialFocus="panel" size="full" fill ariaLabel={t('Edit workflow')}>
      {() => (<>
        <DialogHeader title={t('Edit workflow')} actions={<SaveStatus word={status} />} />
        {/* The same three columns as the form: what the workflow is, its steps, and what it runs
            with. The steps and the tools fill their columns and scroll inside them. */}
        <DialogBody scroll className="flex flex-col">
          <FormColumns cols={3} from="lg" density="columns" className="lg:grid-rows-1 lg:flex-1 lg:min-h-0">
          <FormStack className="min-w-0">
            <Field label={t('Name')} description={t('What you type after "/" in chat. Must differ from skill names.')}>
              <EditableText
                value={v.name}
                label={t('Name')}
                placeholder={t('e.g. Release readiness')}
                editable={loaded}
                required={t('Name is required')}
                error={nameError || undefined}
                onCommit={name => save({ name })}
              />
            </Field>

            <Field label={t('Short description')} description={t('Shown in the "/" list and to the agent.')}>
              <EditableText
                value={v.shortDescription}
                label={t('Short description')}
                placeholder={t('Check whether a release is ready to ship')}
                multiline
                editable={loaded}
                required={t('Short description is required')}
                onCommit={shortDescription => save({ shortDescription })}
              />
            </Field>

            <Field label={t('Context')} description={t('Optional. Given before the first step.')}>
              {id => (
                <EditableMarkdown
                  id={id}
                  value={v.preamble}
                  onCommit={preamble => save({ preamble })}
                  placeholder={t('The release version is whatever the user named when they invoked this...')}
                  rows={3}
                  editable={loaded}
                />
              )}
            </Field>

            <Field label={t('Tags')} description={t('Comma-separated.')}>
              <EditableText
                value={v.tags.join(', ')}
                label={t('Tags')}
                placeholder="release, qa"
                editable={loaded}
                onCommit={text => {
                  const tags = commaList(text)
                  return sameSet(tags, v.tags) ? undefined : save({ tags })
                }}
              />
            </Field>

            <SwitchRow
              className="py-0"
              title={t('Shared')}
              caption={t('Every user can see and run it.')}
              checked={v.shared}
              onChange={shared => quietly(save({ shared }))}
              disabled={!loaded}
            />
          </FormStack>

          <div className="min-w-0 lg:min-h-0 lg:flex lg:flex-col">
            <Field
              label={t('Steps')}
              aside={t('{done} of {total}', { done: String(stepCount), total: String(MAX_STEPS) })}
              description={t('Steps run in order, one turn each. Each step adds cost.')}
              error={stepsError || undefined}
              className="lg:flex-1 lg:min-h-0"
            >
              {/* A numbered card per step, with its reorder and remove controls - a list of unknown
                  length, so it scrolls inside the column. */}
              <div className="space-y-3 max-h-96 lg:max-h-none lg:flex-1 lg:min-h-0 overflow-y-auto">
                {v.steps.map((step, i) => (
                  <StepCard
                    key={i}
                    index={i}
                    count={v.steps.length}
                    head={(
                      <EditableText
                        value={step.title}
                        label={t('Step {number} title', { number: i + 1 })}
                        placeholder={t('What this step is called')}
                        onCommit={title => setStep(i, { title })}
                      />
                    )}
                    onMove={moveStep}
                    // The last step stays: a workflow with no steps is refused.
                    onRemove={v.steps.length > 1 ? () => quietly(save({ steps: v.steps.filter((_, j) => j !== i) })) : undefined}
                    labels={{
                      up: t('Move step {number} up', { number: i + 1 }),
                      down: t('Move step {number} down', { number: i + 1 }),
                      remove: t('Remove step {number}', { number: i + 1 }),
                    }}
                  >
                    <EditableMarkdown
                      value={step.instruction}
                      onCommit={instruction => setStep(i, { instruction })}
                      aria-label={t('Step {number} instruction', { number: i + 1 })}
                      placeholder={t('What to do in this step and what to report.')}
                      rows={3}
                    />
                  </StepCard>
                ))}
                <Presence show={adding}>
                  <StepCard
                    index={v.steps.length}
                    count={v.steps.length + 1}
                    head={(
                      <EditableText
                        value=""
                        startOpen
                        label={t('Step {number} title', { number: v.steps.length + 1 })}
                        placeholder={t('What this step is called')}
                        onCommit={title => addStep({ title, instruction: '' })}
                      />
                    )}
                    onRemove={() => setAdding(false)}
                    labels={{
                      up: t('Move step {number} up', { number: v.steps.length + 1 }),
                      down: t('Move step {number} down', { number: v.steps.length + 1 }),
                      remove: t('Remove step {number}', { number: v.steps.length + 1 }),
                    }}
                  >
                    <EditableMarkdown
                      value=""
                      onCommit={instruction => addStep({ title: '', instruction })}
                      aria-label={t('Step {number} instruction', { number: v.steps.length + 1 })}
                      placeholder={t('What to do in this step and what to report.')}
                      rows={3}
                    />
                  </StepCard>
                </Presence>
              </div>
            </Field>

            <Presence show={loaded && !adding}>
              <div className="mt-3">
                {v.steps.length >= MAX_STEPS ? (
                  <Caption className="animate-fade-in">
                    {t('That is the maximum. Split longer ones into two workflows.')}
                  </Caption>
                ) : (
                  <Button className="animate-fade-in" variant="secondary" onClick={() => setAdding(true)}>
                    <Plus size={14} aria-hidden="true" />
                    {t('Add step')}
                  </Button>
                )}
              </div>
            </Presence>
          </div>

          <div className="min-w-0 space-y-4 lg:min-h-0 lg:flex lg:flex-col">
            <Field
              label={t('Model')}
              description={t('Optional. A cheaper model for every step.')}
              error={(heldProvider !== null ? pickModel : modelError) || undefined}
            >
              <FormColumns className="items-start">
                <div>
                  <Select
                    value={provider}
                    // A provider is saved WITH its model: a model name means nothing outside the
                    // catalogue it came from, and the server refuses a provider with none. So a new
                    // one is held until its model is chosen; "Main chat model" saves at once and
                    // clears the model with it.
                    onChange={p => {
                      setModelError('')
                      if (p === '') {
                        setHeldProvider(null)
                        if (v.provider || v.model) quietly(save({ provider: null, model: null }))
                      } else {
                        setHeldProvider(p === (v.provider ?? '') ? null : p)
                      }
                    }}
                    disabled={!loaded}
                    options={[
                      { value: '', label: t('Main chat model') },
                      ...chatProviders.map(p => ({ value: p.id, label: p.name })),
                    ]}
                  />
                  {providerMissing && (
                    <FieldNote error={t('This provider is gone. Pick another, or use the chat model.')} />
                  )}
                </div>
                <ComboboxAutosave
                  value={model}
                  placeholder={provider ? t('Model name') : t('Same as chat')}
                  options={modelOptions}
                  status={modelStatus}
                  errorText={modelsError}
                  labels={{ loading: t('Loading models...'), failed: t('Failed to load models'), empty: t('No models found') }}
                  disabled={!loaded}
                  // Never asks a provider that was not chosen: with no id the request would be to
                  // /api/v1/providers//models, which is not a route.
                  onOpen={() => { if (provider) openModels() }}
                  validate={m => (provider && !m ? pickModel : null)}
                  onCommit={m => {
                    setModelError('')
                    if (heldProvider === null) return save({ model: m || null })
                    return save({ provider: heldProvider, model: m }).then(() => setHeldProvider(null))
                  }}
                />
              </FormColumns>
            </Field>

            <WorkflowConnectorsField
              names={v.connectorNames}
              disabled={!loaded}
              onAdd={n => { if (!v.connectorNames.includes(n)) quietly(save({ connectorNames: [...v.connectorNames, n] })) }}
              onRemove={n => quietly(save({ connectorNames: v.connectorNames.filter(x => x !== n) }))}
            />
            <WorkflowToolsField
              picked={v.tools}
              disabled={!loaded}
              onToggle={n => quietly(save({ tools: v.tools.includes(n) ? v.tools.filter(x => x !== n) : [...v.tools, n] }))}
            />
          </div>
          </FormColumns>
        </DialogBody>
      </>)}
    </ModalShell>
  )
}

/**
 * A NEW WORKFLOW'S FORM, and a workflow the reader may only read: every field held here until Save.
 * Sharing is not on the new one - create() never sends it.
 */
function WorkflowForm({ workflow, onClose, onSaved }: {
  workflow: WorkflowSummary | null
  onClose: () => void
  onSaved?: (workflow: WorkflowDetail) => void
}) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  // A workflow passed in here is one the reader may only read.
  const readOnly = workflow !== null
  const { data: existing } = useQuery({
    queryKey: keys.chatWorkflow(workflow?.id ?? ''),
    queryFn: () => workflowsApi.get(workflow!.id),
    enabled: readOnly,
  })
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
  // '' on both means "run on the main chat model", which is what every workflow did before this
  // field existed and is still the default for a new one.
  const [provider, setProvider] = useState('')
  const [model, setModel] = useState('')
  const [modelError, setModelError] = useState('')

  // What the form holds, against what it opened with. A step's `key` is React bookkeeping, not
  // content, and tools and connectors are sets - so none of those alone is a change.
  const formValues = {
    name, shortDescription, preamble, tagsInput, provider, model,
    steps: steps.map(s => ({ title: s.title, instruction: s.instruction })),
    tools: [...tools].sort(), connectorNames: [...connectorNames].sort(),
  }
  const { dirty: formDirty } = useFormDirty(formValues)
  const dirty = !readOnly && formDirty

  // The read-only view fills in once the detail arrives.
  useEffect(() => {
    if (existing) {
      setPreamble(existing.preamble)
      setSteps(existing.steps.length > 0
        ? existing.steps.map(s => newDraftStep(s.title, s.instruction))
        : [newDraftStep()])
      setTagsInput(existing.tags.join(', '))
      setTools(existing.tools)
      setConnectorNames(existing.connectorNames)
      setProvider(existing.provider ?? '')
      setModel(existing.model ?? '')
    }
  }, [existing])

  const save = useMutation({
    mutationFn: () => {
      // index is re-derived from position by the backend, which ignores whatever is sent - position
      // IS the identity, and trusting a client-supplied index would let a malformed request produce
      // a workflow whose steps run in an order this editor never showed.
      const payloadSteps: WorkflowStep[] = steps.map((s, i) => ({
        index: i, title: s.title.trim(), instruction: s.instruction.trim(),
      }))
      return workflowsApi.create({
        name, shortDescription, preamble, steps: payloadSteps, tags: commaList(tagsInput), tools, connectorNames,
        // Sent as null rather than '' so "no override" is one value on the wire, and the same one
        // the backend writes back when it omits both keys from the file.
        provider: provider || null,
        model: model.trim() || null,
      })
    },
    onSuccess: (saved) => {
      queryClient.invalidateQueries({ queryKey: keys.chatWorkflows() })
      onSaved?.(saved)
      onClose()
    },
    onError: (e: unknown) => {
      const refused = refusedField(e)
      if (!refused) addToast(t('Failed to create workflow'), 'danger')
      else if (refused.field === 'model') setModelError(refused.message)
      else if (refused.field === 'name') { setNameError(refused.message); nameShake.trigger() }
      else setStepsError(refused.message)
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
      setModelError(t('Pick a model, or use the chat model.'))
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

  // Unfiltered, same as the Settings model roles: nothing declares per-role capability any more
  // (see ProviderProtocol), and a step whose provider cannot chat fails the run with the reason.
  const chatProviders = providerInstances
  // A workflow can name an instance that has since been deleted. Showing the select as though
  // nothing were chosen would hide that; it stays on a value it cannot name, so say so.
  const providerMissing = provider !== '' && !chatProviders.some(p => p.id === provider)
  const { options: modelOptions, status: modelStatus, errorText: modelsError, onOpen: openModels } = useProviderModels(provider)

  return (
    <ModalShell
      onClose={onClose}
      closable={!save.isPending}
      dirty={dirty}
      initialFocus={readOnly ? 'panel' : 'first-field'}
      size="full"
      fill
      ariaLabel={readOnly && workflow ? workflow.name : t('Add workflow')}
    >
      {requestClose => (<>
        <DialogHeader title={readOnly && workflow ? workflow.name : t('Add workflow')} />
        {/* THREE COLUMNS FROM lg, since 2026-09-27 (the maintainer's rule that no dialog
            scrolls): what the workflow is, its steps, and what it runs with. The panel has a
            fixed height there, and the two lists of unknown length - the steps and the tools -
            fill their columns and scroll inside them. One column below lg, where the body is the
            one region that scrolls; the panel is max-w-3xl there, since a step row is a title and
            a multi-line instruction with reorder controls. */}
        <DialogBody scroll className="flex flex-col">
          <FormColumns cols={3} from="lg" density="columns" className="lg:grid-rows-1 lg:flex-1 lg:min-h-0">
          <FormStack className="min-w-0">
            <Field label={t('Name')} description={t('What you type after "/" in chat. Must differ from skill names.')}>
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

            <Field label={t('Short description')} description={t('Shown in the "/" list and to the agent.')}>
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

            <Field label={t('Context')} description={t('Optional. Given before the first step.')}>
              {id => (
                <MarkdownField
                  id={id}
                  value={preamble}
                  onChange={setPreamble}
                  placeholder={t('The release version is whatever the user named when they invoked this...')}
                  rows={3}
                  disabled={readOnly}
                />
              )}
            </Field>

            <Field label={t('Tags')} description={t('Comma-separated.')}>
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

            {/* Read-only only: whether somebody else's workflow is shared, shown and not changed. */}
            {workflow && (
              <SwitchRow
                className="py-0"
                title={t('Shared')}
                caption={t('Every user can see and run it.')}
                checked={workflow.shared}
                onChange={() => undefined}
                disabled
              />
            )}
          </FormStack>

          <div className="min-w-0 lg:min-h-0 lg:flex lg:flex-col">
            <Field
              label={t('Steps')}
              aside={t('{done} of {total}', { done: String(steps.length), total: String(MAX_STEPS) })}
              description={t('Steps run in order, one turn each. Each step adds cost.')}
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
                    <MarkdownField
                      value={step.instruction}
                      onChange={value => updateStep(step.key, { instruction: value })}
                      placeholder={t('What to do in this step and what to report.')}
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
                    {t('That is the maximum. Split longer ones into two workflows.')}
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
              description={t('Optional. A cheaper model for every step.')}
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
                    <FieldNote error={t('This provider is gone. Pick another, or use the chat model.')} />
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

            <WorkflowConnectorsField
              names={connectorNames}
              disabled={readOnly}
              onAdd={n => setConnectorNames(prev => prev.includes(n) ? prev : [...prev, n])}
              onRemove={n => setConnectorNames(prev => prev.filter(x => x !== n))}
            />
            <WorkflowToolsField
              picked={tools}
              disabled={readOnly}
              onToggle={n => setTools(prev => prev.includes(n) ? prev.filter(x => x !== n) : [...prev, n])}
            />
          </div>
          </FormColumns>
        </DialogBody>
        {/* No footer until something has changed, Cancel and Save once it has - the same rule as
            SkillModal's form, for the same reason. Read-only never has one: it was a lone Close, and
            a dialog closes on its backdrop and Escape. */}
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

/**
 * The connectors a workflow prefers - a search for one to add, and the chosen ones as chips.
 * Disabled while read-only (or loading): nothing can be added, and the field stays in place.
 */
function WorkflowConnectorsField({ names, disabled, onAdd, onRemove }: {
  names: string[]
  disabled: boolean
  onAdd: (name: string) => void
  onRemove: (name: string) => void
}) {
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const debounced = useDebounce(search.trim(), 300)
  const { data: suggestions = [] } = useMentionSuggestions(debounced ? debounced : null)
  const available = suggestions.filter(r => !names.includes(r.name))
  return (
    <Field label={t('Connectors')} description={t('Searched first in this workflow.')}>
      <div className="space-y-2">
        <SearchInput
          value={search}
          onChange={setSearch}
          placeholder={t('Search connectors by name...')}
          ariaLabel={t('Connectors')}
          clearLabel={t('Clear search')}
          disabled={disabled}
        />
        <Presence show={!!search.trim()}>
          <PickBox>
            {available.length === 0 ? (
              <EmptyState className="py-2">{t('No matching connectors.')}</EmptyState>
            ) : (
              <List>
                {available.map(r => (
                  <ListRow
                    key={r.id}
                    className="py-2"
                    onClick={() => onAdd(r.name)}
                    disabled={disabled}
                    title={r.name}
                    trailing={<span className={TEXT.caption}>{t(catalogTypeName(r.type) ?? r.type)}</span>}
                  />
                ))}
              </List>
            )}
          </PickBox>
        </Presence>
        <Presence show={names.length > 0}>
          <div className="flex flex-wrap gap-2">
            {names.map(n => (
              <Chip
                key={n}
                label={n}
                onRemove={disabled ? undefined : () => onRemove(n)}
                removeLabel={t('Remove {name}', { name: n })}
              />
            ))}
          </div>
        </Presence>
      </div>
    </Field>
  )
}

/** The tools the agent may use in every step - a filter over the catalog and a box per tool. */
function WorkflowToolsField({ picked, disabled, onToggle }: {
  picked: string[]
  disabled: boolean
  onToggle: (name: string) => void
}) {
  const { t } = useTranslation()
  const { data: allTools = [] } = useQuery({ queryKey: keys.chatTools(), queryFn: skillsApi.listTools })
  const [toolSearch, setToolSearch] = useState('')
  const q = toolSearch.trim().toLowerCase()
  const filtered = q
    ? allTools.filter(tool => tool.name.toLowerCase().includes(q) || tool.description.toLowerCase().includes(q))
    : allTools
  return (
    <Field
      label={t('Tools')}
      description={t('Tools the agent may use in every step.')}
      className="lg:flex-1 lg:min-h-0"
    >
      <div className="space-y-2 lg:flex-1 lg:min-h-0 lg:flex lg:flex-col">
        <SearchInput
          value={toolSearch}
          onChange={setToolSearch}
          placeholder={t('Search tools...')}
          ariaLabel={t('Tools')}
          clearLabel={t('Clear search')}
          disabled={disabled}
        />
        <PickBox maxHeight="max-h-72" fill>
          {filtered.length === 0 ? (
            <EmptyState className="py-3">
              {allTools.length === 0 ? t('No tools available.') : t('No tools match your search.')}
            </EmptyState>
          ) : (
            <List>
              {filtered.map(tool => (
                <Checkbox
                  key={tool.name}
                  className="flex py-2"
                  checked={picked.includes(tool.name)}
                  onChange={() => { if (!disabled) onToggle(tool.name) }}
                  disabled={disabled}
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
  )
}
