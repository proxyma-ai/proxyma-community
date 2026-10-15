import { useState, useEffect } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import {
  Badge, Button, Checkbox, Chip, ConfirmDialog, DialogBody, DialogFooter, DialogHeader, EmptyState, FadeSwap,
  Field, FormColumns, FormStack, Input, List, ListRow, PickBox, Presence, SearchInput, SettingsSection,
  SettingsSections, SwitchRow, Textarea, TEXT,
} from '@/components/ui/kit'
import { PagedItems } from '@/components/ui/ListPager'
import { ListSkeleton, TitleAction } from '../pages/shared'
import { skillsApi, type SkillSummary, type SkillDetail } from '../services/skillsApi'
import { useShake } from '@/components/ui/kit'
import { useDebounce } from '@/hooks/useDebounce'
import { useMentionSuggestions } from '@/features/chat/hooks/useMentionSuggestions'
import { useThemeStore } from '@/store/themeStore'
import { useToastStore } from '@/store/toastStore'
import { queryClient } from '@/app/QueryClient'
import { keys } from '@/services/queryKeys'
import { useTranslation } from '@/i18n'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
import { withReason } from '@/services/apiError'

// Every field is the kit's - Input, Textarea, SearchInput, Checkbox - so a skill's text boxes are
// cut into the panel like every other field in Settings (F22 of the 2026-09-30 review: they were a
// `bg-surface` recipe of their own, then a FIELD_BASE textarea class retyped here).

// - Skills tab -
//
// Open to every user (no admin gate) - see pages/index.{desktop,server}.tsx. Everyone can create
// and manage their own skills; skills owned by other users (or official/shared ones) are listed
// too but only editable when the backend's `canEdit` flag says so.

export function SkillsTab() {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const { data: skills = [], isLoading } = useQuery({ queryKey: keys.chatSkills(), queryFn: skillsApi.list })
  const [modalTarget, setModalTarget] = useState<'new' | SkillSummary | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<SkillSummary | null>(null)

  const remove = useMutation({
    mutationFn: (id: string) => skillsApi.remove(id),
    onSuccess: () => {
      setDeleteTarget(null)
      queryClient.invalidateQueries({ queryKey: keys.chatSkills() })
      addToast(t('Skill deleted'), 'success')
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to delete skill'), e), 'danger'),
  })

  // THREE GROUPS, each named once (F8 of the 2026-09-30 review): yours, the ones Proxyma ships,
  // and the ones other people shared. "Made by Proxyma" used to be a solid badge on every one of a
  // dozen rows, beside the same document icon on every row - repetition that read as decoration
  // and made the list look like an ad for itself. A heading says it once.
  const productName = useThemeStore(s => s.branding.productName)
  const mySkills = skills.filter(s => s.mine)
  const officialSkills = skills.filter(s => !s.mine && s.official)
  const sharedSkills = skills.filter(s => !s.mine && !s.official)
  const noSkills = skills.length === 0
  const groups: { key: string; title: string; rows: SkillSummary[] }[] = [
    { key: 'mine', title: t('My skills'), rows: mySkills },
    { key: 'official', title: t('Made by {product}', { product: productName }), rows: officialSkills },
    { key: 'shared', title: t('Shared'), rows: sharedSkills },
  ].filter(g => g.rows.length > 0)

  return (
    // The introduction is SettingsSections' intro, as on every tab that opens with one.
    <SettingsSections intro={t('Skills are named instructions the agent loads only when relevant, instead of always consuming context.')}>
      <TitleAction>
        <Presence show={!isLoading && !noSkills} collapse={false}>
          <Button onClick={() => setModalTarget('new')}>
            <Plus size={14} aria-hidden="true" />
            {t('Add skill')}
          </Button>
        </Presence>
      </TitleAction>

      {/* Tour anchor on the SKILLS THEMSELVES, not on the caption above them - and on a wrapper
          that is always in the DOM, so the step still has something to point at while the list is
          loading or empty (a first-run install has only the seeded skills, and a fresh server
          deployment may have none at all). Anchoring the list itself would leave the step
          unanchored in exactly the case a first-run walkthrough is most likely to hit. */}
      <div data-tour-id="tour-settings-skills">
      <FadeSwap swapKey={isLoading ? 'loading' : noSkills}>
      {isLoading ? (
        <ListSkeleton count={3} />
      ) : noSkills ? (
        // EMPTY STATE: the sentence and its one action (F12).
        <EmptyState
          title={t('No skills yet.')}
          action={(
            <Button onClick={() => setModalTarget('new')}>
              <Plus size={14} aria-hidden="true" />
              {t('Add skill')}
            </Button>
          )}
        >
          {t('A skill is a set of instructions the agent reads only when a question needs it.')}
        </EmptyState>
      ) : (
        // Each group a SUBSECTION - ruled off from the one above with the tighter subsection rhythm,
        // so the three read as parts of one list rather than as the page's sections.
        <div>
          {groups.map(g => (
            <SettingsSection key={g.key} title={g.title} gap="list" className="settings-subsection">
              {/* Each group twenty a page (LIST_PAGE_SIZE), in the browser - the composer's skills
                  menu reads the same list whole. */}
              <PagedItems items={g.rows}>
                {page => (
                  <List>
                    {page.map(s => (
                      <SkillRow
                        key={s.id}
                        skill={s}
                        onEditClick={() => setModalTarget(s)}
                        onDeleteClick={() => setDeleteTarget(s)}
                      />
                    ))}
                  </List>
                )}
              </PagedItems>
            </SettingsSection>
          ))}
        </div>
      )}
      </FadeSwap>
      </div>

      {modalTarget && (
        <SkillModal
          skill={modalTarget === 'new' ? null : modalTarget}
          readOnly={modalTarget !== 'new' && !modalTarget.canEdit}
          onClose={() => setModalTarget(null)}
        />
      )}

      {deleteTarget && (
        <ConfirmDialog
          title={t('Delete skill')}
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
    </SettingsSections>
  )
}

// Clicking the row opens the modal (edit form for canEdit rows, read-only view otherwise - see
// SkillModal's `readOnly` prop) - the detail body is only ever fetched once the modal actually
// needs it. Delete is rendered for rows the caller can DELETE, which is narrower than canEdit: a
// built-in product skill is editable but permanent (see ChatSkillService), so it shows no Delete at
// all rather than one that 403s.
//
// The row is the kit ListRow that opens AND carries an action: its text is the button, and Delete
// sits beside it rather than inside it - a button cannot hold another button.
function SkillRow({ skill, onEditClick, onDeleteClick }: {
  skill: SkillSummary
  onEditClick: () => void
  onDeleteClick: () => void
}) {
  const { t } = useTranslation()
  return (
    <ListRow
      onClick={onEditClick}
      chevron
      title={skill.name}
      meta={(skill.mine && skill.shared) || skill.tags.length > 0 ? (
        <>
          {/* Only on YOUR skills: the other groups' headings already say who made or shared a
              skill, but whether one of your own is shared is a fact about that row alone. */}
          {skill.mine && skill.shared && <Badge label={t('Shared')} variant="outline" size="sm" />}
          {/* A tag is a word the user typed - a Chip, not an upper-case status Badge. */}
          {skill.tags.map(tag => <Chip key={tag} label={tag} />)}
        </>
      ) : undefined}
      caption={skill.shortDescription && <span className="block truncate">{skill.shortDescription}</span>}
      action={skill.canDelete && (
        <Button variant="danger-quiet" onClick={onDeleteClick} aria-label={t('Delete {name}', { name: skill.name })}>
          {t('Delete')}
        </Button>
      )}
    />
  )
}

// Exported so the chat page's attach-skill menu (a later, separate task) can reuse this exact
// form for creating a new skill inline.
export function SkillModal({ skill, onClose, readOnly = false, onSaved }: {
  skill: SkillSummary | null
  onClose: () => void
  readOnly?: boolean
  // Fired with the created/updated skill right before onClose - lets a caller (e.g. the chat
  // page's attach menu) auto-select a skill it just created without duplicating this form.
  onSaved?: (skill: SkillDetail) => void
}) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const isEdit = skill !== null
  const { data: existing } = useQuery({
    queryKey: keys.chatSkill(skill?.id ?? ''),
    queryFn: () => skillsApi.get(skill!.id),
    enabled: isEdit,
  })
  const { data: allTools = [] } = useQuery({ queryKey: keys.chatTools(), queryFn: skillsApi.listTools })
  const [toolSearch, setToolSearch] = useState('')

  const [name, setName] = useState(skill?.name ?? '')
  const [nameError, setNameError] = useState('')
  const nameShake = useShake()
  const [shortDescription, setShortDescription] = useState(skill?.shortDescription ?? '')
  const [shortDescriptionError, setShortDescriptionError] = useState('')
  const shortDescriptionShake = useShake()
  const [detail, setDetail] = useState('')
  const [tagsInput, setTagsInput] = useState('')
  const [tools, setTools] = useState<string[]>([])
  const [connectorNames, setConnectorNames] = useState<string[]>([])
  const [connectorSearch, setConnectorSearch] = useState('')
  const debouncedConnectorSearch = useDebounce(connectorSearch.trim(), 300)
  const { data: connectorSuggestions = [] } = useMentionSuggestions(debouncedConnectorSearch ? debouncedConnectorSearch : null)
  const availableConnectorSuggestions = connectorSuggestions.filter(r => !connectorNames.includes(r.name))
  // Already known from the summary passed in (no need to wait for the detail fetch) - `shared`
  // isn't part of what a create() call ever sends (see save.mutate below), so it only matters
  // once isEdit is true anyway.
  const [shared, setShared] = useState(skill?.shared ?? false)
  const [restoring, setRestoring] = useState(false)

  // What the form holds, against what it opened with. Tools and connectors are sets as far as the
  // user is concerned, so unticking one and ticking it again is not a change.
  const formValues = {
    name, shortDescription, detail, tagsInput, shared,
    tools: [...tools].sort(), connectorNames: [...connectorNames].sort(),
  }
  const { dirty: formDirty, rebase } = useFormDirty(formValues)
  const dirty = !readOnly && formDirty

  // Fills the form with the skill's seeded original WITHOUT saving - the user reviews it and still
  // presses Save, so restoring is cancellable and never a silent write. Same contract as the
  // System Prompt modal's reset. Deliberately leaves tools/connectorNames/shared alone:
  // those are configuration an admin chose for this deployment, not part of the authored text the
  // default describes, and silently reverting them would be a surprise nobody asked for.
  async function restoreDefault() {
    if (!skill) return
    setRestoring(true)
    try {
      const d = await skillsApi.getDefault(skill.id)
      setName(d.name)
      setShortDescription(d.shortDescription)
      setDetail(d.detail)
      setNameError('')
      setShortDescriptionError('')
    } catch (e) {
      addToast(withReason(t('Failed to load the default for this skill'), e), 'danger')
    } finally {
      setRestoring(false)
    }
  }

  // Once the edit fetch resolves, seed the fields the summary didn't already carry - same
  // one-time catch-up pattern the old SkillModal used for `detail` alone.
  //
  // The seeded values become the baseline, so the fill itself does not read as an edit. Name and
  // short description come from the summary the form opened with, not from what may have been
  // typed since - a name changed before the fetch landed is still a change.
  useEffect(() => {
    if (existing) {
      setDetail(existing.detail)
      setTagsInput(existing.tags.join(', '))
      setTools(existing.tools)
      setConnectorNames(existing.connectorNames)
      rebase({
        name: skill?.name ?? '',
        shortDescription: skill?.shortDescription ?? '',
        detail: existing.detail,
        tagsInput: existing.tags.join(', '),
        shared: skill?.shared ?? false,
        tools: [...existing.tools].sort(),
        connectorNames: [...existing.connectorNames].sort(),
      })
    }
    // `skill` is the summary this modal was opened for and does not change while it is open.
  }, [existing, rebase])

  const save = useMutation({
    mutationFn: () => {
      const tags = tagsInput.split(',').map(t => t.trim()).filter(Boolean)
      // create() never sends shared - a brand-new skill always starts private server-side, so
      // there is nothing meaningful to submit for that field yet.
      return isEdit
        ? skillsApi.update(skill.id, { name, shortDescription, detail, tags, tools, connectorNames, shared })
        : skillsApi.create({ name, shortDescription, detail, tags, tools, connectorNames })
    },
    onSuccess: (saved) => {
      queryClient.invalidateQueries({ queryKey: keys.chatSkills() })
      if (isEdit) queryClient.invalidateQueries({ queryKey: keys.chatSkill(skill.id) })
      onSaved?.(saved)
      onClose()
    },
    onError: (e: unknown) => addToast(withReason(isEdit ? t('Failed to update skill') : t('Failed to create skill'), e), 'danger'),
  })

  const handleSave = () => {
    const trimmedName = name.trim()
    const trimmedShortDescription = shortDescription.trim()
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
    if (!valid) return
    save.mutate()
  }

  const toggleTool = (toolName: string) => {
    if (readOnly) return
    setTools(prev => prev.includes(toolName) ? prev.filter(t => t !== toolName) : [...prev, toolName])
  }

  const addConnector = (name: string) => {
    if (readOnly) return
    setConnectorNames(prev => prev.includes(name) ? prev : [...prev, name])
  }

  const removeConnector = (name: string) => {
    if (readOnly) return
    setConnectorNames(prev => prev.filter(n => n !== name))
  }

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
      size="wide"
      fill
      // Named by what it holds, like every dialog - it opened with no accessible name.
      ariaLabel={readOnly && skill ? skill.name : isEdit ? t('Edit skill') : t('Add skill')}
    >
      {requestClose => (<>
        <DialogHeader
          title={readOnly && skill ? skill.name : isEdit ? t('Edit skill') : t('Add skill')}
          actions={!readOnly && skill?.hasDefault && skill.canEdit && (
            // Same affordance and placement as the System Prompt modal's own "Reset to default",
            // and there for the same reason: built-in skills are seeded create-if-absent, so once
            // edited nothing ever puts them back. Only offered where a seeded original actually
            // exists (hasDefault) and the user can save the result.
            <Button variant="hyperlink" onClick={restoreDefault} disabled={restoring || save.isPending}>
              {restoring ? t('Restoring...') : t('Restore default')}
            </Button>
          )}
        />
        {/* THREE COLUMNS FROM lg, since 2026-09-27 (the maintainer's rule that no dialog scrolls):
            the short fields, then what the skill reaches for - connectors and tools - then Detail.
            The panel has a fixed height there, and the two things of unknown length fill their
            column and scroll inside it: the tool list and the Detail text. Two columns at md (Detail
            across the bottom), one below, in the original order - where the body is the one region
            that scrolls. */}
        <DialogBody scroll className="flex flex-col">
          <FormColumns cols={3} from="lg" density="columns" className="md:grid-cols-2 lg:grid-rows-1 lg:flex-1 lg:min-h-0">
          <FormStack className="min-w-0">
            <Field label={t('Name')} description={t('A short, memorable name - this is what the agent and other users will see.')}>
              {id => (
                <Input
                  id={id}
                  value={name}
                  onChange={e => setName(e.target.value)}
                  placeholder={t('e.g. Jira Workflow')}
                  error={nameError}
                  shake={nameShake.shaking}
                  disabled={readOnly}
                />
              )}
            </Field>
            <Field label={t('Short description')} description={t('Shown to the agent in every system prompt, so keep it brief.')}>
              {id => (
                <Textarea
                  id={id}
                  value={shortDescription}
                  onChange={e => setShortDescription(e.target.value)}
                  placeholder={t('Shown to the agent in every system prompt')}
                  rows={2}
                  disabled={readOnly}
                  error={shortDescriptionError || undefined}
                  shake={shortDescriptionShake.shaking}
                />
              )}
            </Field>
            <Field label={t('Tags')} description={t('Comma-separated, used to organize your skills list.')}>
              {id => (
                <Input
                  id={id}
                  value={tagsInput}
                  onChange={e => setTagsInput(e.target.value)}
                  placeholder="jira, reporting"
                  disabled={readOnly}
                />
              )}
            </Field>

            {/* Sharing only makes sense once the skill actually exists - create() never sends the
                field (see save.mutate above), so there is nothing for this control to do until
                isEdit. */}
            {isEdit && !skill.official && (
              <SwitchRow
                className="py-0"
                title={t('Shared')}
                caption={t('Visible and selectable by every user, not just you.')}
                checked={shared}
                onChange={setShared}
                disabled={readOnly}
              />
            )}
          </FormStack>

          {/* The middle column: connectors on top, then the tool list filling what is left of the
              column and scrolling inside itself - there are dozens of tools. */}
          <div className="min-w-0 space-y-4 lg:min-h-0 lg:flex lg:flex-col">
            <Field label={t('Connectors')} description={t('Prefer these connectors when the agent searches for this skill - a preference, not a restriction.')}>
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
                <Presence show={!!connectorSearch.trim()}>
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
                </Presence>
                <Presence show={connectorNames.length > 0}>
                  <div className="flex flex-wrap gap-1.5">
                    {connectorNames.map(name => (
                      <Chip
                        key={name}
                        label={name}
                        onRemove={readOnly ? undefined : () => removeConnector(name)}
                        removeLabel={t('Remove {name}', { name })}
                      />
                    ))}
                  </div>
                </Presence>
              </div>
            </Field>
            <Field
              label={t('Tools')}
              description={t("Let this skill's instructions also make these platform tools available to the agent.")}
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
          {/* The right column. Stretches to the row's height (grid items do by default), and the
              textarea fills it - so Detail is as tall as the dialog allows, never less than the ten
              rows it used to be, and scrolls inside itself past that. */}
          <Field
            label={t('Detail')}
            description={t('Full instructions, loaded by the agent only when this skill is relevant.')}
            className="md:col-span-2 lg:col-span-1 lg:min-h-0"
          >
            {id => (
              <Textarea
                id={id}
                fill
                value={detail}
                onChange={e => setDetail(e.target.value)}
                placeholder={t('Step-by-step instructions the agent follows once it loads this skill...')}
                rows={10}
                disabled={readOnly}
                className="md:min-h-64"
              />
            )}
          </Field>
          </FormColumns>
        </DialogBody>
        {/* NO CLOSE BUTTON, since 2026-09-24 (maintainer's request): Escape and a click outside
            the panel close it, both through ModalShell. The footer appears only once something
            has changed, to carry Cancel and Save - a Save beside a form nobody has touched offers
            an action that would do nothing. Read-only never has one. */}
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
