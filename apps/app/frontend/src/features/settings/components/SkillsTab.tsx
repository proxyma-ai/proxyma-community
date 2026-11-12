import { useState, useEffect, useMemo } from 'react'
import { MarkdownField } from '@/components/ui/MarkdownField'
import { EditableText } from '@/components/ui/EditableText'
import { EditableMarkdown } from '@/components/ui/EditableMarkdown'
import { quietly } from '@/components/ui/AutosaveFields'
import { useAutosave } from '@/components/ui/useAutosave'
import { SaveStatus } from '@/components/ui/SaveStatus'
import { commaList, sameSet } from '@/components/ui/autosave'
import { useQuery, useMutation } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import {
  Badge, Button, Checkbox, Chip, ConfirmDialog, DialogBody, DialogFooter, DialogHeader, EmptyState, FadeSwap, Field, FormColumns, FormStack, Input, List, ListRow, PickBox, Presence, SearchInput, SettingsSection, SettingsSections, SwitchRow, Textarea, TEXT, IconButton,
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
import { catalogTypeName } from '@/features/indexing/utils/catalogRegistry'
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
    <SettingsSections intro={t('Instructions the agent loads when needed.')}>
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
          {t('Instructions the agent reads when a question needs them.')}
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
      // ROW ACTIONS ARE ICONS in every Settings list (the maintainer, 2026-11-10), as Connections' and Providers' are.
      action={skill.canDelete && (
        <IconButton icon={Trash2} tone="danger" onClick={onDeleteClick}
          label={t('Delete {name}', { name: skill.name })} tooltip={t('Delete')} />
      )}
    />
  )
}

// Exported so the chat page's attach-skill menu can reuse this exact form for creating a new skill
// inline.
//
// TWO DIALOGS BEHIND ONE NAME (the maintainer, 2026-11-02): ADDING a skill is a form with its Save
// button; an EXISTING skill the reader may change is edited where it is read, field by field, each
// saved as it is left (SkillEditDialog). A skill the reader may only read is the form's read-only view.
export function SkillModal({ skill, onClose, readOnly = false, onSaved }: {
  skill: SkillSummary | null
  onClose: () => void
  readOnly?: boolean
  // Fired with the created skill right before onClose - lets a caller (e.g. the chat page's attach
  // menu) auto-select a skill it just created without duplicating this form - and with an existing
  // skill each time one of its fields is saved.
  onSaved?: (skill: SkillDetail) => void
}) {
  if (skill && !readOnly) return <SkillEditDialog skill={skill} onClose={onClose} onSaved={onSaved} />
  return <SkillForm skill={skill} onClose={onClose} onSaved={onSaved} />
}

/** What an existing skill's dialog saves: the update request, a field at a time over the rest. */
interface SkillFields {
  name: string
  shortDescription: string
  detail: string
  tags: string[]
  tools: string[]
  connectorNames: string[]
  shared: boolean
}

/**
 * AN EXISTING SKILL, EDITED IN PLACE. Every field is the skill's own value, click-to-edit or a control
 * that saves as it changes; the update endpoint takes the whole skill, so each save sends it whole with
 * the one field changed (useAutosave). Nothing opens for editing until the detail has arrived - a save
 * built before it would blank the detail, tags, tools and connectors. No footer: there is nothing left
 * to save when the dialog closes.
 */
function SkillEditDialog({ skill, onClose, onSaved }: {
  skill: SkillSummary
  onClose: () => void
  onSaved?: (skill: SkillDetail) => void
}) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const { data: existing } = useQuery({ queryKey: keys.chatSkill(skill.id), queryFn: () => skillsApi.get(skill.id) })
  const [restoring, setRestoring] = useState(false)

  const base = useMemo<SkillFields | undefined>(() => existing && {
    name: existing.name,
    shortDescription: existing.shortDescription,
    detail: existing.detail,
    tags: existing.tags,
    tools: existing.tools,
    connectorNames: existing.connectorNames,
    shared: existing.shared,
  }, [existing])

  const { value, save, status } = useAutosave<SkillFields>(base, async next => {
    const saved = await skillsApi.update(skill.id, next)
    queryClient.invalidateQueries({ queryKey: keys.chatSkills() })
    queryClient.invalidateQueries({ queryKey: keys.chatSkill(skill.id) })
    onSaved?.(saved)
    return {
      name: saved.name, shortDescription: saved.shortDescription, detail: saved.detail, tags: saved.tags,
      tools: saved.tools, connectorNames: saved.connectorNames, shared: saved.shared,
    }
  }, { failure: t('Failed to update skill') })

  // Until the detail arrives, the summary's own fields stand in - shown, not yet editable.
  const v: SkillFields = value ?? {
    name: skill.name, shortDescription: skill.shortDescription, detail: '', tags: skill.tags,
    tools: [], connectorNames: [], shared: skill.shared,
  }
  const loaded = value !== undefined

  // The skill's seeded original, written at once: an action pressed on purpose, like Delete, and said
  // in a toast. Tools, connectors and sharing are left alone - those are configuration an admin chose
  // for this deployment, not part of the authored text the default describes.
  async function restoreDefault() {
    setRestoring(true)
    try {
      let d: { name: string; shortDescription: string; detail: string }
      try {
        d = await skillsApi.getDefault(skill.id)
      } catch (e) {
        addToast(withReason(t('Failed to load the default for this skill'), e), 'danger')
        return
      }
      try {
        await save({ name: d.name, shortDescription: d.shortDescription, detail: d.detail })
        addToast(t('Default restored'), 'success')
      } catch {
        // Refused: useAutosave has said why, and the skill is as it was.
      }
    } finally {
      setRestoring(false)
    }
  }

  return (
    <ModalShell onClose={onClose} initialFocus="panel" size="wide" fill ariaLabel={t('Edit skill')}>
      {() => (<>
        <DialogHeader
          title={t('Edit skill')}
          actions={<>
            <SaveStatus word={status} />
            {skill.hasDefault && (
              // Built-in skills are seeded create-if-absent, so once edited nothing ever puts them back.
              // Only offered where a seeded original actually exists (hasDefault).
              <Button variant="hyperlink" onClick={restoreDefault} disabled={restoring || !loaded}>
                {restoring ? t('Restoring...') : t('Restore default')}
              </Button>
            )}
          </>}
        />
        {/* The same three columns as the form below: the short fields, what the skill reaches for,
            then Detail - which fills its column and scrolls inside itself. */}
        <DialogBody scroll className="flex flex-col">
          <FormColumns cols={3} from="lg" density="columns" className="md:grid-cols-2 lg:grid-rows-1 lg:flex-1 lg:min-h-0">
          <FormStack className="min-w-0">
            <Field label={t('Name')} description={t('A short name others will see.')}>
              <EditableText
                value={v.name}
                label={t('Name')}
                placeholder={t('e.g. Jira Workflow')}
                editable={loaded}
                required={t('Name is required')}
                onCommit={name => save({ name })}
              />
            </Field>
            <Field label={t('Short description')} description={t('Sent with every prompt. Keep it short.')}>
              <EditableText
                value={v.shortDescription}
                label={t('Short description')}
                placeholder={t('Shown to the agent in every system prompt')}
                multiline
                editable={loaded}
                required={t('Short description is required')}
                onCommit={shortDescription => save({ shortDescription })}
              />
            </Field>
            <Field label={t('Tags')} description={t('Comma-separated.')}>
              <EditableText
                value={v.tags.join(', ')}
                label={t('Tags')}
                placeholder="jira, reporting"
                editable={loaded}
                onCommit={text => {
                  const tags = commaList(text)
                  return sameSet(tags, v.tags) ? undefined : save({ tags })
                }}
              />
            </Field>
            {!skill.official && (
              <SwitchRow
                className="py-0"
                title={t('Shared')}
                caption={t('Every user can see and use it.')}
                checked={v.shared}
                onChange={shared => quietly(save({ shared }))}
                disabled={!loaded}
              />
            )}
          </FormStack>

          <div className="min-w-0 space-y-4 lg:min-h-0 lg:flex lg:flex-col">
            <SkillConnectorsField
              names={v.connectorNames}
              disabled={!loaded}
              onAdd={n => { if (!v.connectorNames.includes(n)) quietly(save({ connectorNames: [...v.connectorNames, n] })) }}
              onRemove={n => quietly(save({ connectorNames: v.connectorNames.filter(x => x !== n) }))}
            />
            <SkillToolsField
              picked={v.tools}
              disabled={!loaded}
              onToggle={n => quietly(save({ tools: v.tools.includes(n) ? v.tools.filter(x => x !== n) : [...v.tools, n] }))}
            />
          </div>
          <Field
            label={t('Detail')}
            description={t('Loaded only when this skill is needed.')}
            className="md:col-span-2 lg:col-span-1 lg:min-h-0"
          >
            {id => (
              <EditableMarkdown
                id={id}
                fill
                value={v.detail}
                onCommit={detail => save({ detail })}
                placeholder={t('Steps the agent follows with this skill...')}
                rows={10}
                editable={loaded}
                className="md:min-h-64"
              />
            )}
          </Field>
          </FormColumns>
        </DialogBody>
      </>)}
    </ModalShell>
  )
}

/**
 * A NEW SKILL'S FORM, and a skill the reader may only read: every field held here until Save.
 * Sharing is not on the new one - a brand-new skill always starts private server-side, so create()
 * never sends it.
 */
function SkillForm({ skill, onClose, onSaved }: {
  skill: SkillSummary | null
  onClose: () => void
  onSaved?: (skill: SkillDetail) => void
}) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  // A skill passed in here is one the reader may only read.
  const readOnly = skill !== null
  const { data: existing } = useQuery({
    queryKey: keys.chatSkill(skill?.id ?? ''),
    queryFn: () => skillsApi.get(skill!.id),
    enabled: readOnly,
  })

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

  // What the form holds, against what it opened with. Tools and connectors are sets as far as the
  // user is concerned, so unticking one and ticking it again is not a change.
  const formValues = {
    name, shortDescription, detail, tagsInput,
    tools: [...tools].sort(), connectorNames: [...connectorNames].sort(),
  }
  const { dirty: formDirty } = useFormDirty(formValues)
  const dirty = !readOnly && formDirty

  // The read-only view fills in once the detail arrives.
  useEffect(() => {
    if (existing) {
      setDetail(existing.detail)
      setTagsInput(existing.tags.join(', '))
      setTools(existing.tools)
      setConnectorNames(existing.connectorNames)
    }
  }, [existing])

  const save = useMutation({
    mutationFn: () => skillsApi.create({ name, shortDescription, detail, tags: commaList(tagsInput), tools, connectorNames }),
    onSuccess: (saved) => {
      queryClient.invalidateQueries({ queryKey: keys.chatSkills() })
      onSaved?.(saved)
      onClose()
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to create skill'), e), 'danger'),
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

  return (
    <ModalShell
      onClose={onClose}
      closable={!save.isPending}
      dirty={dirty}
      initialFocus={readOnly ? 'panel' : 'first-field'}
      size="wide"
      fill
      // Named by what it holds, like every dialog - it opened with no accessible name.
      ariaLabel={readOnly && skill ? skill.name : t('Add skill')}
    >
      {requestClose => (<>
        <DialogHeader title={readOnly && skill ? skill.name : t('Add skill')} />
        {/* THREE COLUMNS FROM lg, since 2026-09-27 (the maintainer's rule that no dialog scrolls):
            the short fields, then what the skill reaches for - connectors and tools - then Detail.
            The panel has a fixed height there, and the two things of unknown length fill their
            column and scroll inside it: the tool list and the Detail text. Two columns at md (Detail
            across the bottom), one below, in the original order - where the body is the one region
            that scrolls. */}
        <DialogBody scroll className="flex flex-col">
          <FormColumns cols={3} from="lg" density="columns" className="md:grid-cols-2 lg:grid-rows-1 lg:flex-1 lg:min-h-0">
          <FormStack className="min-w-0">
            <Field label={t('Name')} description={t('A short name others will see.')}>
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
            <Field label={t('Short description')} description={t('Sent with every prompt. Keep it short.')}>
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
            <Field label={t('Tags')} description={t('Comma-separated.')}>
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

            {/* Read-only only: whether somebody else's skill is shared, shown and not changed. */}
            {skill && !skill.official && (
              <SwitchRow
                className="py-0"
                title={t('Shared')}
                caption={t('Every user can see and use it.')}
                checked={skill.shared}
                onChange={() => undefined}
                disabled
              />
            )}
          </FormStack>

          {/* The middle column: connectors on top, then the tool list filling what is left of the
              column and scrolling inside itself - there are dozens of tools. */}
          <div className="min-w-0 space-y-4 lg:min-h-0 lg:flex lg:flex-col">
            <SkillConnectorsField
              names={connectorNames}
              disabled={readOnly}
              onAdd={n => setConnectorNames(prev => prev.includes(n) ? prev : [...prev, n])}
              onRemove={n => setConnectorNames(prev => prev.filter(x => x !== n))}
            />
            <SkillToolsField
              picked={tools}
              disabled={readOnly}
              onToggle={n => setTools(prev => prev.includes(n) ? prev.filter(x => x !== n) : [...prev, n])}
            />
          </div>
          {/* The right column. Stretches to the row's height (grid items do by default), and the
              textarea fills it - so Detail is as tall as the dialog allows, never less than the ten
              rows it used to be, and scrolls inside itself past that. */}
          <Field
            label={t('Detail')}
            description={t('Loaded only when this skill is needed.')}
            className="md:col-span-2 lg:col-span-1 lg:min-h-0"
          >
            {id => (
              <MarkdownField
                id={id}
                fill
                value={detail}
                onChange={setDetail}
                placeholder={t('Steps the agent follows with this skill...')}
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
            has been typed, to carry Cancel and Save - a Save beside a form nobody has touched
            offers an action that would do nothing. Read-only never has one. */}
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
 * The connectors a skill prefers - a search for one to add, and the chosen ones as chips. Disabled
 * while read-only (or loading): nothing can be added, and the field stays in place.
 */
function SkillConnectorsField({ names, disabled, onAdd, onRemove }: {
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
    <Field label={t('Connectors')} description={t('Searched first with this skill.')}>
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
            {names.map(name => (
              <Chip
                key={name}
                label={name}
                onRemove={disabled ? undefined : () => onRemove(name)}
                removeLabel={t('Remove {name}', { name })}
              />
            ))}
          </div>
        </Presence>
      </div>
    </Field>
  )
}

/** The tools a skill makes available - a filter over the catalog and a box per tool. */
function SkillToolsField({ picked, disabled, onToggle }: {
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
      description={t('Tools this skill makes available.')}
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
