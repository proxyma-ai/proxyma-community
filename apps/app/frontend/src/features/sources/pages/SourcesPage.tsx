import { Suspense, useState, useCallback, useMemo, useEffect, useRef } from 'react'
import {
  Button, Divider, DropOverlay, EmptyState, FadeSwap, FilterBar, FilterChips, ListGroupHeading,
  MetaList, Page, PageHeader, Presence, Readout, SearchInput, Skeleton, SOURCE_LIST_BOX, SourceListHeader,
  SwitchRow, Tooltip, useFileDrop, usePageFor,
} from '@/components/ui/kit'
import { ConnectMenu } from '../components/ConnectMenu'
import { FilterChipsSkeleton, SourceListSkeleton } from '@/components/ui/Skeleton'
import { SourceRow } from '@/features/indexing/components/SourceRow'
import { ConnectorConfigModal } from '@/features/indexing/components/ConnectorConfigModal'
import { EDITION_SOURCE_DIALOGS } from '@edition'
import { SourceDialog } from '@/features/indexing/components/SourceDialog'
import { useQuery } from '@tanstack/react-query'
import { FileUploadModal } from '@/features/indexing/components/FileUploadModal'
import { LocalFolderModal } from '@/features/indexing/components/LocalFolderModal'
import { ConnectorDetailModal } from '@/features/indexing/components/ConnectorDetailModal'
import { FEATURES } from '@/config/features'
import { useFileUpload } from '@/hooks/useFileUpload'
import { useConnectorSummary, useConnectorsByType } from '@/features/indexing/hooks/useConnectors'
import { useSSE } from '@/hooks/useSSE'
import { ListPager } from '@/components/ui/ListPager'
import { useDebounce } from '@/hooks/useDebounce'
import { useConnectorOperationStore } from '@/store/connectorOperationStore'
import { walkDataTransferItems, fingerprintDroppedFolder, droppedFolderPath, type DroppedFile } from '@/features/indexing/utils/dirEntryWalk'
import { indexingApi } from '@/features/indexing/services/indexingApi'
import { formatCompactCount, formatFileSize } from '@/utils/formatters'
import { keys } from '@/services/queryKeys'
import type { ConnectorConfig, ConnectorType } from '@/features/indexing/types/indexing.types'
import type { CatalogType } from '@/features/indexing/services/catalogApi'
import { useConnectorCatalog } from '@/features/indexing/hooks/useConnectorCatalog'
import { typeRank } from '@/features/indexing/utils/fieldForm'
import { Plus, ChevronRight } from 'lucide-react'
import { usePageTitle } from '@/hooks/usePageTitle'
import { useTranslation } from '@/i18n'

// EVERY TYPE THIS PAGE OFFERS IS THE CONNECTOR CATALOG'S (useConnectorCatalog) - the Connect menu
// and its groups, the filter chips, the group headings and their order, which types are switched
// off or locked. It was six tables here, one per question, and a new type that missed one of them
// failed silently. What is left is the one thing a descriptor cannot say: which types have a dialog
// of their own (CUSTOM_FLOWS below).

// The filter chips, in the order the types are listed. A chip appears ONLY FOR A TYPE THAT HAS
// SOURCES, since 2026-09-30 (the UI review): a row of every type this product can index, most of
// them empty, was the loudest thing on the page and said nothing about this install. Which types
// CAN be added is the Connect menu's job, and it still lists every one.
type FilterKey = 'all' | ConnectorType

// The types whose ADD dialog is their own - a folder or uploads (a picker, a drop), a web page (a
// crawl's bounds and warnings), a network share, and the edition's own (on Enterprise Server,
// Outlook: a mailbox's folders, listed live - EDITION_SOURCE_DIALOGS). Every other type is drawn
// from its descriptor by the generic SourceDialog.
const CUSTOM_FLOWS: readonly string[] = ['files', 'webpage', 'networkshare', ...Object.keys(EDITION_SOURCE_DIALOGS)]

// What an empty list says: one left-aligned sentence and the one action that fills it - no icon
// tile (the UI review, 2026-09-30). The action is the page's second add-a-source control after
// Connect, used by the unfiltered list and a filtered one alike so the two cannot drift apart.
function SourcesEmpty({ onAdd }: { onAdd: () => void }) {
  const { t } = useTranslation()
  const button = (
    <Button variant="secondary" onClick={onAdd}>
      <Plus size={16} /> {t('Add a source')}
    </Button>
  )
  return (
    <EmptyState action={button}>
      {t('No sources yet. Add a folder, a web page or a project, and what it holds becomes searchable in chat.')}
    </EmptyState>
  )
}

// The list's column headings - sentence case, and hidden on a phone, where each row carries its
// own one-line meta instead. On the marks' edge, like the group headings under it.
function ListHeader() {
  const { t } = useTranslation()
  return <SourceListHeader columns={[t('Name'), t('Items'), t('Last sync'), t('Status')]} />
}

// The list's skeleton: the rows on the real rows' columns and padding - the route's own.
const ListSkeleton = SourceListSkeleton

// What every state of the list sits in - rows, search results, a filtered type, the skeleton - is
// the kit's SOURCE_LIST_BOX. NO BOX AND NO LINE BETWEEN ROWS, since 2026-09-30 (maintainer's
// feedback): a bordered box ruled under every row read as a spreadsheet. The header keeps its rule
// and the type groups are separated the way Settings separates its sections. The negative margin
// hangs the rows' own padding outside the page's edge, so their text lines up with the search
// field above while a hovered row's fill runs past it - equal to the page's gutter (2026-10-02).

// The registered SOURCES - this Jira project, that Confluence space, this folder. Distinct from
// Settings > Connections, which configures the connector TYPES those sources are reached through:
// one Jira server URL, one token, one set of tool permissions, shared by every Jira source here.
// The page was called Connectors until both existed at once and the name stopped saying which.
export function SourcesPage() {
  usePageTitle('Sources')
  const { t } = useTranslation()
  const [modal, setModal] = useState<ConnectorType | null>(null)
  // An add dialog the edition brings for one of its own types (EDITION_SOURCE_DIALOGS).
  const EditionDialog = modal ? EDITION_SOURCE_DIALOGS[modal] : undefined
  const { catalog } = useConnectorCatalog()
  const descriptorOf = useCallback((type: string): CatalogType | undefined => catalog.types.find(d => d.type === type), [catalog])
  // A type's name in the list - its descriptor's plural noun, "Jira projects"; the type's wire name
  // only for one this server no longer lists.
  const groupLabel = (type: string) => {
    const d = descriptorOf(type)
    return d ? t(d.nounPlural) : type
  }
  const [fileUpload, setFileUpload] = useState(false)
  // Desktop counterpart to `fileUpload` - null when closed, otherwise the props the local-folder
  // picker should open with (a drop supplies a resolved path, or the reason it could not).
  const [localFolder, setLocalFolder] = useState<
    { suggestedName?: string; dropProblem?: 'not-a-folder' | 'not-found'; initialPath?: string; resolvingDrop?: boolean } | null
  >(null)
  const [droppedFiles, setDroppedFiles] = useState<DroppedFile[] | undefined>(undefined)
  const [initialFolder, setInitialFolder] = useState<string | undefined>(undefined)
  // Sharing, not auth: what this toggle offers is other users' sources, which is a question
  // about whether sharing exists at all rather than about sign-in.
  const isServer = FEATURES.sharing
  // Whether adding files means uploading bytes or pointing at a local path. Runtime, not a build
  // constant: the home server is the desktop bundle and it uploads. See useFileUpload.
  const canUpload = useFileUpload()
  const [query, setQuery] = useState('')
  const debouncedQuery = useDebounce(query, 300)
  const [filterType, setFilterType] = useState<ConnectorType | undefined>(undefined)
  // Enabled by default. Shared connectors are read-only and were deliberately published by their
  // owner for colleagues to use, so hiding them until someone finds the toggle meant the common
  // case - "the thing I need is already indexed by someone else" - looked like "nothing is indexed
  // here". The toggle stays, so anyone who wants only their own connectors can still say so. Server
  // mode only; desktop has no auth/other-users concept, and passes false regardless below.
  const [includeShared, setIncludeShared] = useState(true)
  const { data: summary, isLoading: summaryLoading } = useConnectorSummary(isServer && includeShared)
  // Search box filters by connector title - when a query is present, byType fetches title matches
  // (scoped to filterType, or across every type when filterType is undefined) instead of the
  // plain unfiltered listing.
  // Twenty a page, numbered, on the server (LIST_PAGE_SIZE); back to the first page when the type,
  // the search or the shared toggle changes, and kept while a sync refreshes the list.
  const { page: listPage, setPage: setListPage } = usePageFor([filterType, debouncedQuery, isServer && includeShared])
  const byType = useConnectorsByType(filterType, debouncedQuery, isServer && includeShared, listPage)
  const [menuOpen, setMenuOpen] = useState(false)
  const [detailId, setDetailId] = useState<string | null>(null)
  // The header's size. Directory walks on the server, so asked for once a minute at most rather than
  // on every sync event the summary follows.
  const { data: storage } = useQuery({
    queryKey: keys.sourceStorage(),
    queryFn: indexingApi.getStorage,
    staleTime: 60_000,
    refetchInterval: 60_000,
  })

  const setOperation = useConnectorOperationStore(s => s.setOperation)
  const clearOperation = useConnectorOperationStore(s => s.clearOperation)

  // THE LIST ITSELF IS REFRESHED APP-WIDE, by useConnectorListSync in AppShell, since 2026-09-14 -
  // it used to be invalidated here, which only ran while this page was mounted and so missed every
  // source the agent registered from chat. What stays here is this page's own per-card state: the
  // operation labels a card shows while it syncs, reindexes or deletes.
  const handleSSE = useCallback((_type: string, raw: string) => {
    try {
      const data = JSON.parse(raw)
      if ((data.type === 'connector-update' || data.type === 'connector-synced' || data.type === 'connector-removed')
          && data.connectorId) {
        clearOperation(data.connectorId)
        // A source removed elsewhere (another tab, the agent) takes its open dialog with it.
        if (data.type === 'connector-removed') setDetailId(open => (open === data.connectorId ? null : open))
      } else if (data.type === 'connector-operation' && data.connectorId) {
        setOperation(data.connectorId, { operation: data.operation, phase: data.phase })
      }
    } catch { /* non-JSON SSE */ }
  }, [setOperation, clearOperation])

  useSSE(handleSSE)

  const modalOpen = fileUpload || !!localFolder

  // The counter that keeps the overlay from flickering as the pointer crosses child elements is the
  // kit's useFileDrop.
  const onPageDrop = useCallback((data: DataTransfer) => {
    // Same guard the dragenter/dragover handlers already apply - a dragged text selection or link
    // is not a connector. Harmless in server mode (the walk just yields no files) but desktop would
    // otherwise pop a native folder chooser for it.
    if (data.items.length === 0 || !data.types.includes('Files')) return
    if (!canUpload) {
      // Desktop: a drop is an intent, not a payload. A browser can never tell us where the dropped
      // folder actually lives (see LocalPathController), so its name AND its immediate contents are
      // read synchronously - before the DataTransferItemList goes stale - and the backend, which
      // runs on this same machine, looks for the one directory that matches both.
      //
      // The modal opens NOW, in a resolving state, and is re-seeded when the lookup answers. That
      // search walks real directories on a disk of unknown speed, and a drop that appeared to do
      // nothing for several seconds would read as the drop having been ignored.
      //
      // A resolved path lands in the field ready to add. Anything else leaves the modal open SAYING
      // why, with Browse beside the field - registering the wrong folder silently would be far worse.
      //
      // IT NO LONGER OPENS THE NATIVE CHOOSER BY ITSELF, since 2026-09-13. It used to, on the theory
      // that a drop already says "register this", and what the user saw was the modal they expected
      // and then, unasked, a Windows folder dialog sitting on its default of Documents - for loose
      // files most of all, which can never resolve to a folder. Browse is one click away.
      //
      // IN PROXYMA'S OWN WINDOW THE PATH IS SIMPLY KNOWN, since 2026-09-27, and none of the above
      // runs. The search reaches only the home directory, so a folder on another drive was never
      // found - after the search had spent its whole budget, which is a long wait on a big profile.
      const knownPath = droppedFolderPath(data)
      if (knownPath) {
        const name = knownPath.split(/[\\/]/).filter(Boolean).pop() ?? knownPath
        setLocalFolder({ suggestedName: name, initialPath: knownPath })
        return
      }
      setLocalFolder({ resolvingDrop: true })
      fingerprintDroppedFolder(data.items).then(async read => {
        if (read.kind === 'not-a-folder') {
          setLocalFolder({ dropProblem: 'not-a-folder' })
          return
        }
        if (read.kind === 'unreadable') {
          setLocalFolder({ dropProblem: 'not-found' })
          return
        }
        const { fingerprint } = read
        const resolvedPath = await indexingApi.resolveDroppedFolder(fingerprint)
        setLocalFolder(resolvedPath
          ? { suggestedName: fingerprint.name, initialPath: resolvedPath }
          : { suggestedName: fingerprint.name, dropProblem: 'not-found' })
      })
      return
    }
    walkDataTransferItems(data.items).then(({ files, folderName }) => {
      if (files.length > 0) {
        setDroppedFiles(files)
        if (folderName) setInitialFolder(folderName)
        setFileUpload(true)
      }
    })
  }, [canUpload])

  const pageDrop = useFileDrop({ enabled: !modalOpen, onDrop: onPageDrop })

  const byTypeItems = useMemo(() => byType.data?.items ?? [], [byType.data])
  const listPages = byType.data?.totalPages ?? 0
  // A source removed from the last page moves the reader to the page before it.
  useEffect(() => {
    if (byType.data && listPage > 0 && listPage >= listPages) setListPage(Math.max(0, listPages - 1))
  }, [byType.data, listPage, listPages, setListPage])

  const totalSources = summary?.totalSources ?? 0
  const totalDocs = summary?.totalDocs ?? 0
  const byTypeGroups = useMemo(
    () => {
      const entries = Object.entries(summary?.byType ?? {}) as
        [ConnectorType, NonNullable<typeof summary>['byType'][ConnectorType]][]
      // In the catalog's order, so the groups cannot disagree with the chips above them or with the
      // Connect menu. Without it the groups render in whatever key order /summary's JSON happened
      // to use. A type the catalog does not list sorts to the end.
      const rank = typeRank(catalog.types)
      return entries.sort((a, b) => rank(a[0]) - rank(b[0]))
    },
    [summary, catalog],
  )

  // A chip for each type that has sources, plus All - and none at all while only one type does,
  // where "All 3" beside "Files 3" would be two names for the same list. Unless that one type is
  // the filter on ("View 12 more" turns it on): then All is the way back.
  const filterChips = useMemo(() => {
    const present = byTypeGroups.filter(([, g]) => (g?.total ?? 0) > 0)
    if (present.length < 2 && !filterType) return []
    return [
      { key: 'all' as FilterKey, label: t('All'), count: totalSources },
      ...present.map(([type, g]) => {
        const d = descriptorOf(type)
        return { key: type as FilterKey, label: d ? t(d.displayName) : type, count: g?.total ?? 0 }
      }),
    ]
  }, [byTypeGroups, totalSources, descriptorOf, t, filterType])

  // A filter whose last source went away (deleted here, or unshared by its owner) has no chip to
  // show it is on, so it turns itself off rather than leaving an unexplained empty list.
  useEffect(() => {
    if (summary && filterType && !(summary.byType[filterType]?.total)) setFilterType(undefined)
  }, [summary, filterType])

  // A folder row stops its own drag events (the kit's `stop`), so the page's overlay is already
  // down by the time a row takes a drop.
  const handleCardDrop = useCallback((folderName: string, files: DroppedFile[]) => {
    setDroppedFiles(files)
    setInitialFolder(folderName)
    setFileUpload(true)
  }, [])

  // Single entry point for "add a Files connector", so the menu item and the empty-state button
  // can never drift apart on which of the two modals they open.
  const openFilesFlow = useCallback(() => {
    if (canUpload) setFileUpload(true)
    else setLocalFolder({})
  }, [canUpload])

  // Every other type: its own dialog where it has one, else the one drawn from its descriptor.
  const openConnectorFlow = useCallback((type: ConnectorType) => {
    if (type === 'files') openFilesFlow()
    else setModal(type)
  }, [openFilesFlow])

  // A filtered list's "Add a source" adds one of THAT type - while the type is still offered and
  // licensed; otherwise, and on the unfiltered list, it opens the Connect menu.
  const handleEmptyClick = () => {
    const d = filterType ? descriptorOf(filterType) : undefined
    if (filterType && d && d.offered && d.licensed) openConnectorFlow(filterType)
    else setMenuOpen(true)
  }

  const allConnectors = debouncedQuery.trim() || filterType
    ? byTypeItems
    : byTypeGroups.flatMap(([, g]) => g?.preview ?? [])

  // THE OPEN SOURCE'S DIALOG OUTLIVES ITS ROW. Unfiltered, the list is the server's capped preview of
  // each type (the newest few), so a source added elsewhere - from chat, by the agent, in another
  // tab - can push the open one out of it; the dialog used to unmount with it, taking a settings
  // dialog with unsaved edits along. It keeps the source it last found, and follows it whenever the
  // list holds it; one removed elsewhere closes it (the SSE handler above).
  const lastDetail = useRef<ConnectorConfig | null>(null)
  const listedDetail = detailId ? allConnectors.find(c => c.id === detailId) : undefined
  if (listedDetail) lastDetail.current = listedDetail
  else if (!detailId) lastDetail.current = null
  const detail = listedDetail ?? (lastDetail.current?.id === detailId ? lastDetail.current : null)

  const row = (c: ConnectorConfig) => (
    <SourceRow key={c.id} connector={c} onOpenDetail={setDetailId} onDropFiles={handleCardDrop} />
  )
  // A flat, paged list: search results, or one type's sources (a type can have thousands, so the
  // server sends twenty at a time, numbered).
  const pagedList = (
    <div className={SOURCE_LIST_BOX}>
      <ListHeader />
      <div>
        {byTypeItems.map(row)}
      </div>
      <ListPager
        className="px-3 sm:px-4 border-t border-line/20"
        page={listPage}
        pages={listPages}
        onPage={setListPage}
        countText={t('{count} source|{count} sources', { count: byType.data?.totalItems ?? 0 })}
      />
    </div>
  )

  // The Connect menu - every type the catalog offers, grouped and searchable (ConnectMenu).
  const connect = (
    <ConnectMenu
      types={catalog.types}
      categories={catalog.categories}
      open={menuOpen}
      onOpenChange={setMenuOpen}
      onPick={type => openConnectorFlow(type as ConnectorType)}
      // Files is the one entry whose name is decided at render, by the SERVER - a deployment that
      // takes uploads says "Upload files", one that indexes a folder in place says "Local folder"
      // (see useFileUpload). Two calls each wrapping a LITERAL, so the catalog checker sees both.
      filesTitle={canUpload ? t('Upload files') : t('Local folder')}
    />
  )

  return (
    <div className="relative" {...pageDrop.handlers}>
      <DropOverlay active={pageDrop.active} label={canUpload ? t('Drop files to upload') : t('Drop a folder to index')} />
      <Page>
        <PageHeader
          title={t('Sources')}
          // THE COUNTS WAIT FOR THE ANSWER. They read "0 sources, 0 documents" while the summary
          // loaded, which is a statement - and a false one on every install that has sources.
          meta={summaryLoading ? (
            <span className="flex items-center gap-4 h-4 animate-fade-in" aria-hidden="true">
              <Skeleton className="h-3 w-20" />
              <Skeleton className="h-3 w-24" />
            </span>
          ) : (
            // The NUMBER stays in its own Readout; only the word is translated, so the plural form
            // is selected with `count` and nothing is interpolated. Vietnamese does not inflect, so
            // its side is one form.
            <MetaList
              className="flex flex-wrap items-center"
              items={[
                <span key="sources">
                  <Readout>{formatCompactCount(totalSources)}</Readout>{' '}
                  {t('source|sources', { count: totalSources })}
                </span>,
                <span key="docs">
                  <Readout>{formatCompactCount(totalDocs)}</Readout>{' '}
                  {t('document|documents', { count: totalDocs })}
                </span>,
                // WHAT THE SOURCES TAKE ON DISK, where "N items indexing" was (0.28.0 feedback:
                // "show DB sizes instead"). Indexing is not lost: every source's own row says it is
                // indexing and how far it has got, which the one count up here never could.
                storage && (
                  <Tooltip
                    key="size"
                    label={t('Index {index}, uploaded files {uploads}', {
                      index: formatFileSize(storage.indexBytes),
                      uploads: formatFileSize(storage.uploadsBytes),
                    })}
                  >
                    <span>
                      <Readout>{formatFileSize(storage.totalBytes)}</Readout>{' '}
                      {t('on disk')}
                    </span>
                  </Tooltip>
                ),
              ]}
            />
          )}
          actions={connect}
        />

        {/* THE FILTERS ARE QUIET TEXT CHIPS, since 2026-09-30 (the UI review): sentence case, no
            icons, no pill borders, the count a plain caption - the kit's FilterChips since the
            2026-10 rebuild. While the summary loads the row keeps its height, so the list under it
            does not jump. Search and the shared toggle share a row from `sm`; on a phone they
            stack, because side by side the toggle's label ran off the edge of the screen. */}
        <FilterBar
          filters={summaryLoading ? <FilterChipsSkeleton /> : filterChips.length > 0 ? (
            <FilterChips<FilterKey>
              ariaLabel={t('Filter sources by type')}
              options={filterChips}
              active={filterType ?? 'all'}
              onChange={key => setFilterType(key === 'all' ? undefined : key)}
            />
          ) : undefined}
          search={(
            <SearchInput
              value={query}
              onChange={setQuery}
              placeholder={t('Search source title')}
              ariaLabel={t('Search source title')}
              clearLabel={t('Clear search')}
            />
          )}
        >
          {isServer && (
            <SwitchRow
              size="sm"
              title={t('Show shared sources from other users')}
              checked={includeShared}
              onChange={setIncludeShared}
            />
          )}
        </FilterBar>

        {/* One view at a time - search results, a type's list, the grouped preview, or the skeleton
            before any of them - and each fades in over the last rather than replacing it. */}
        <FadeSwap swapKey={`${debouncedQuery.trim() ? 'search' : filterType ?? 'all'}:${debouncedQuery.trim() || filterType ? byType.isLoading : summaryLoading}`}>
        {debouncedQuery.trim() ? (
          // Title search results - paged like a filtered list, since a broad query can match
          // thousands of sources. Uses the same byType page query (scoped to filterType, or
          // across all types when no filter is on) rather than the semantic document-content
          // search used elsewhere in the app.
          byType.isLoading ? <ListSkeleton /> : byTypeItems.length === 0 ? (
            <EmptyState>{t('No sources match "{query}".', { query: debouncedQuery })}</EmptyState>
          ) : pagedList
        ) : filterType ? (
          byType.isLoading ? <ListSkeleton /> : byTypeItems.length === 0 ? (
            <SourcesEmpty onAdd={handleEmptyClick} />
          ) : pagedList
        ) : summaryLoading ? (
          <ListSkeleton />
        ) : allConnectors.length === 0 ? (
          <SourcesEmpty onAdd={handleEmptyClick} />
        ) : (
          // Unfiltered - a capped preview (server-side, ALL_TAB_GROUP_LIMIT) of each type, never the
          // full per-type list, under a light subheading. "View more" hands off to that type's filter.
          <div className={SOURCE_LIST_BOX}>
            <ListHeader />
            {byTypeGroups.filter(([, group]) => group && group.total > 0).map(([groupType, group], index) => {
              if (!group) return null
              const remaining = group.total - group.preview.length
              return (
                <section key={groupType} aria-label={groupLabel(groupType)} className="pb-2">
                  {index > 0 && <Divider />}
                  <ListGroupHeading label={groupLabel(groupType)} count={group.total} />
                  <div>
                    {group.preview.map(row)}
                  </div>
                  <Presence show={remaining > 0}>
                    <div className="px-3 sm:px-4 py-2">
                      <Button variant="quiet" onClick={() => setFilterType(groupType)}>
                        {t('View {count} more', { count: remaining })}
                        <ChevronRight size={14} aria-hidden="true" />
                      </Button>
                    </div>
                  </Presence>
                </section>
              )
            })}
          </div>
        )}
        </FadeSwap>

      {EditionDialog && <Suspense fallback={null}><EditionDialog onClose={() => setModal(null)} /></Suspense>}
      {(modal === 'webpage' || modal === 'networkshare') && <ConnectorConfigModal type={modal} onClose={() => setModal(null)} />}
      {modal && !CUSTOM_FLOWS.includes(modal) && <SourceDialog type={modal} onClose={() => setModal(null)} />}
      {fileUpload && <FileUploadModal initialFiles={droppedFiles} initialFolder={initialFolder} onClose={() => { setFileUpload(false); setDroppedFiles(undefined); setInitialFolder(undefined) }} />}
      {localFolder && (
        <LocalFolderModal
          suggestedName={localFolder.suggestedName}
          dropProblem={localFolder.dropProblem}
          initialPath={localFolder.initialPath}
          resolvingDrop={localFolder.resolvingDrop}
          onClose={() => setLocalFolder(null)}
        />
      )}
      {detail && <ConnectorDetailModal connector={detail} onClose={() => setDetailId(null)} />}
      </Page>
    </div>
  )
}
