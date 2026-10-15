import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  applyClick, Badge, BulkActionBar, Button, ConfirmDialog, DialogBody, DialogFooter, DialogHeader, EmptyState,
  FadeSwap, Field, FilterBar, FilterChips, FormStack, headerState, List, ListRow, Presence, RadioGroup, Readout,
  RowActions, SearchInput, SelectableListHeader, SelectBox, SettingsSections, Textarea, toggleAllOnPage,
  usePagedList,
} from '@/components/ui/kit'
import { ListPager } from '@/components/ui/ListPager'
import { ModalShell } from '@/components/ui/ModalShell'
import { ListSkeleton } from '../pages/shared'
import { memoryApi, type MemoryCategory, type MemoryEntry } from '../services/memoryApi'
import { useToastStore } from '@/store/toastStore'
import { queryClient } from '@/app/QueryClient'
import { keys } from '@/services/queryKeys'
import { withReason } from '@/services/apiError'
import { useTranslation } from '@/i18n'
import { useDates } from '@/hooks/useDates'

// Settings > Memory: what the agent keeps across conversations, for the person it is about to read
// and correct (maintainer's feedback, 2026-10-03). Every user's own - the backend scopes each call
// to the caller - so the tab is in Workspace, beside Skills, in both editions.
//
// The list takes the telemetry console's multi-select (the kit's SelectBox, BulkActionBar and
// selection.ts): a set of ids, shift-click for a run, and a bar of actions over whatever is ticked.

type Filter = 'all' | MemoryCategory

// The two categories as a reader says them. USER is what the agent learned about YOU - your
// preferences, your role, your corrections - and KNOWLEDGE is everything else it learned. Two
// literal calls rather than a lookup table, so the catalog checker sees both strings.
function useCategoryLabel() {
  const { t } = useTranslation()
  return (c: MemoryCategory) => (c === 'USER' ? t('About you') : t('Knowledge'))
}

export function MemoryTab() {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const { data: entries = [], isLoading } = useQuery({ queryKey: keys.agentMemory(), queryFn: memoryApi.list })
  const [filter, setFilter] = useState<Filter>('all')
  const [query, setQuery] = useState('')
  const [checked, setChecked] = useState<Set<string>>(new Set())
  const anchor = useRef<string | null>(null)
  const [editing, setEditing] = useState<MemoryEntry | null>(null)
  const [deleting, setDeleting] = useState<string[] | null>(null)
  const categoryLabel = useCategoryLabel()
  const dates = useDates()

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return entries.filter(e =>
      (filter === 'all' || e.category === filter) && (!q || e.text.toLowerCase().includes(q)))
  }, [entries, filter, query])
  // Twenty a page (LIST_PAGE_SIZE), in the browser - the search and the category words filter the
  // whole list here, which the server sends whole. A new search or category starts at the first
  // page. The header's box and a shift-click run act on the page on screen, as the telemetry
  // console's list does; a ticked entry stays ticked on another page.
  const { page, pages, setPage, pageItems } = usePagedList(shown, [filter, query.trim()])
  const shownIds = useMemo(() => pageItems.map(e => e.id), [pageItems])
  const pageState = headerState(checked, shownIds)

  // An entry that has gone - deleted here, or replaced by the agent - takes its checkmark with it.
  useEffect(() => {
    setChecked(prev => {
      const ids = new Set(entries.map(e => e.id))
      const next = new Set([...prev].filter(id => ids.has(id)))
      return next.size === prev.size ? prev : next
    })
  }, [entries])

  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.agentMemory() })

  const remove = useMutation({
    mutationFn: (ids: string[]) => memoryApi.remove(ids),
    onSuccess: (count) => {
      setDeleting(null)
      setChecked(new Set())
      refresh()
      addToast(t('{count} memory entries deleted', { count }), 'success')
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to delete memory entries'), e), 'danger'),
  })

  const recategorize = useMutation({
    mutationFn: ({ ids, category }: { ids: string[]; category: MemoryCategory }) =>
      memoryApi.recategorize(ids, category),
    onSuccess: () => {
      setChecked(new Set())
      refresh()
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to move memory entries'), e), 'danger'),
  })

  const busy = remove.isPending || recategorize.isPending

  const clickRow = (id: string, on: boolean, shift: boolean) => {
    setChecked(prev => applyClick(prev, shownIds, anchor.current, id, on, shift))
    if (!shift || anchor.current === null) anchor.current = id
  }

  const count = (c: MemoryCategory) => entries.filter(e => e.category === c).length

  return (
    // The introduction is SettingsSections' intro, as on every tab that opens with one, and it is
    // there while the list loads under it.
    <SettingsSections intro={t('What the agent has saved to use in later conversations. It checks this before searching your sources, so correct or delete anything that is wrong or out of date.')}>
      <FadeSwap swapKey={isLoading ? 'loading' : entries.length > 0}>
      {isLoading ? (
        <ListSkeleton count={5} />
      ) : entries.length === 0 ? (
        <EmptyState>
          {t('Nothing saved yet. The agent saves facts and your preferences here as it learns them.')}
        </EmptyState>
      ) : (
        <div className="space-y-4">
          <FilterBar
            filters={(
              <FilterChips<Filter>
                ariaLabel={t('Memory categories')}
                active={filter}
                onChange={setFilter}
                options={[
                  { key: 'all', label: t('All'), count: entries.length },
                  { key: 'USER', label: categoryLabel('USER'), count: count('USER') },
                  { key: 'KNOWLEDGE', label: categoryLabel('KNOWLEDGE'), count: count('KNOWLEDGE') },
                ]}
              />
            )}
            search={<SearchInput value={query} onChange={setQuery} placeholder={t('Filter entries')} clearLabel={t('Clear search')} />}
          />

          {/* In while anything is ticked, and OUT again when nothing is - it vanished in one frame. */}
          <Presence show={checked.size > 0}>
            <BulkActionBar countText={t('{count} selected', { count: checked.size })}>
              <Button
                variant="secondary"
                disabled={busy}
                onClick={() => recategorize.mutate({ ids: [...checked], category: 'USER' })}
              >
                {t('Move to About you')}
              </Button>
              <Button
                variant="secondary"
                disabled={busy}
                onClick={() => recategorize.mutate({ ids: [...checked], category: 'KNOWLEDGE' })}
              >
                {t('Move to Knowledge')}
              </Button>
              <Button variant="hyperlink" disabled={busy} onClick={() => setChecked(new Set())}>
                {t('Clear selection')}
              </Button>
              <Button variant="danger-quiet" className="ml-2" disabled={busy} onClick={() => setDeleting([...checked])}>
                {t('Delete')}
              </Button>
            </BulkActionBar>
          </Presence>

          <FadeSwap swapKey={shown.length > 0}>
          {shown.length === 0 ? (
            <EmptyState>{t('No entries match this filter.')}</EmptyState>
          ) : (
            <div>
              <SelectableListHeader
                state={pageState}
                label={t('Select every entry shown')}
                onToggle={() => {
                  setChecked(prev => toggleAllOnPage(prev, shownIds))
                  anchor.current = null
                }}
              >
                <Readout>{t('{count} entries', { count: shown.length })}</Readout>
              </SelectableListHeader>
              <List>
                {pageItems.map(entry => (
                  <ListRow
                    key={entry.id}
                    selected={checked.has(entry.id)}
                    leading={(
                      <SelectBox
                        checked={checked.has(entry.id)}
                        label={t('Select this entry')}
                        onToggle={(on, shift) => clickRow(entry.id, on, shift)}
                      />
                    )}
                    // The entry is what the agent remembers, in its own words - read at the option
                    // weight, up to four lines.
                    title={<span className="block font-normal whitespace-pre-wrap break-words line-clamp-4">{entry.text}</span>}
                    // The actions end the metadata line, in the row's bottom right corner (0.27.0
                    // feedback) - so the entry's text has the full width above them.
                    caption={(
                      <div className="mt-0.5 flex flex-wrap items-center gap-2">
                        {entry.category && <Badge variant="outline" size="sm" label={categoryLabel(entry.category)} />}
                        {entry.savedAt && <Readout>{dates.dateTime(entry.savedAt)}</Readout>}
                        <RowActions
                          className="ml-auto"
                          destructive={(
                            <Button variant="danger-quiet" disabled={busy} onClick={() => setDeleting([entry.id])}>
                              {t('Delete')}
                            </Button>
                          )}
                        >
                          <Button variant="hyperlink" disabled={busy} onClick={() => setEditing(entry)}>{t('Edit')}</Button>
                        </RowActions>
                      </div>
                    )}
                  />
                ))}
              </List>
              <ListPager page={page} pages={pages} onPage={setPage} />
            </div>
          )}
          </FadeSwap>
        </div>
      )}
      </FadeSwap>

      {editing && (
        <MemoryEditModal
          entry={editing}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); refresh() }}
        />
      )}

      {deleting && (
        <ConfirmDialog
          title={deleting.length === 1 ? t('Delete memory entry') : t('Delete memory entries')}
          description={t('Are you sure you want to {verb}', { verb: t('delete') })}
          subject={`${deleting.length === 1 ? t('this memory entry') : t('{count} memory entries', { count: deleting.length })}?`}
          consequences={[t('The agent will no longer know this in any conversation.')]}
          confirmLabel={t('Delete')}
          cancelLabel={t('Cancel')}
          pendingLabel={t('Deleting...')}
          pending={remove.isPending}
          onConfirm={() => remove.mutate(deleting)}
          onCancel={() => setDeleting(null)}
        />
      )}
    </SettingsSections>
  )
}

function MemoryEditModal({ entry, onClose, onSaved }: {
  entry: MemoryEntry
  onClose: () => void
  onSaved: () => void
}) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const [text, setText] = useState(entry.text)
  const [category, setCategory] = useState<MemoryCategory>(entry.category ?? 'KNOWLEDGE')
  const categoryLabel = useCategoryLabel()
  const dirty = text.trim() !== entry.text.trim() || category !== (entry.category ?? 'KNOWLEDGE')

  const save = useMutation({
    mutationFn: () => memoryApi.edit(entry.id, text.trim(), category),
    onSuccess: onSaved,
    onError: (e: unknown) => addToast(withReason(t('Failed to save the memory entry'), e), 'danger'),
  })
  const canSave = dirty && text.trim().length > 0 && !save.isPending

  return (
    <ModalShell
      onClose={onClose}
      closable={!save.isPending}
      dirty={dirty}
      onSubmit={canSave ? () => save.mutate() : undefined}
      ariaLabel={t('Edit memory entry')}
      size="lg"
    >
      {requestClose => (<>
        <DialogHeader title={t('Edit memory entry')} />
        <DialogBody>
          <FormStack>
            <Field label={t('What the agent remembers')}>
              {id => (
                <Textarea
                  id={id}
                  value={text}
                  onChange={e => setText(e.target.value)}
                  rows={6}
                  maxLength={2000}
                />
              )}
            </Field>
            <Field label={t('Category')}>
              <RadioGroup<MemoryCategory>
                value={category}
                onChange={setCategory}
                horizontal
                ariaLabel={t('Category')}
                options={[
                  { value: 'USER', label: categoryLabel('USER') },
                  { value: 'KNOWLEDGE', label: categoryLabel('KNOWLEDGE') },
                ]}
              />
            </Field>
          </FormStack>
        </DialogBody>
        <DialogFooter>
          <Button variant="secondary" onClick={requestClose} disabled={save.isPending}>{t('Cancel')}</Button>
          <Button onClick={() => save.mutate()} disabled={!canSave}>
            {save.isPending ? t('Saving...') : t('Save')}
          </Button>
        </DialogFooter>
      </>)}
    </ModalShell>
  )
}
