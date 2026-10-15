import { useState, useRef, useCallback, useEffect, useMemo } from 'react'
import {
  httpUrl,
  Collapse, FadeSwap, IconButton,
  Presence, RefreshIcon, ActionMenu, Button, Callout, CopyButton, DialogHeader, DisclosureChevron, EmptyState, ExternalLink,
  InlineNotice, ItemRow as KitItemRow, ItemRowSkeleton, MetaList, Spinner, StatusMark, textLink, Tooltip, cn,
} from '@/components/ui/kit'
import { useFileUpload } from '@/hooks/useFileUpload'
import { useRelinkFolder } from '../hooks/useRelinkFolder'
import { DeleteConfirmModal } from './DeleteConfirmModal'
import { KnowledgeGraphModal } from './KnowledgeGraphModal'
import { SourceChunksModal } from './SourceChunksModal'
import { useActiveItems, useConnectorItemsPage, useSyncNow, useSetShared, useSetPriority, useRemoveConnector, useRetryFailed, useReindex } from '../hooks/useConnectors'
import { SourceHealthMark, formatItemCount, extractDisplayName, getTypeIcon, connectorUrl, graphFinished, sourceState } from './SourceRow'
import { formatRelativeTime } from '@/utils/formatRelativeTime'
import { formatFileSize } from '@/utils/formatters'
import { useConnectorOperationStore, PHASE_LABELS } from '@/store/connectorOperationStore'
import { keys } from '@/services/queryKeys'
import { indexingApi } from '../services/indexingApi'
import { filesApi } from '../services/filesApi'
import type { ConnectorConfig, TrackedItem } from '../types/indexing.types'
import type { CatalogType } from '../services/catalogApi'
import { useCatalogType } from '../hooks/useConnectorCatalog'
import {
  Share2, RefreshCw, Trash2, RotateCcw, ListRestart, Rocket, SlidersHorizontal, LayoutList,
  Waypoints, FolderSearch,
} from 'lucide-react'
import { ConnectorSettingsModal, canConfigureConnector } from './ConnectorSettingsModal'
import { IngestApiPanel } from './IngestApiPanel'
import { useTranslation, type Translate } from '@/i18n'
import { useSSE } from '@/hooks/useSSE'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useToastStore } from '@/store/toastStore'
import { FEATURES } from '@/config/features'
import { ModalShell } from '@/components/ui/ModalShell'
import { ListPager } from '@/components/ui/ListPager'
import { withReason } from '@/services/apiError'
import { serverText } from '@/i18n/serverText'

interface Props {
  connector: ConnectorConfig
  onClose: () => void
}

const STATUS_LABELS: Record<string, string> = {
  DETECTED: 'Detected',
  QUEUED: 'Queued',
  DOWNLOADING: 'Downloading',
  CONVERTING: 'Converting',
  EMBEDDING: 'Embedding',
  COMPLETED: 'Indexed',
  FAILED: 'Failed',
  MODIFIED: 'Modified',
  DELETED: 'Deleted',
}

function chunkProgress(item: TrackedItem): string | null {
  if (!item.totalChunks || item.totalChunks <= 0) return null
  const pct = Math.round((item.currentChunks ?? 0) / item.totalChunks * 100)
  return `${pct}%`
}

// Within a day it is relative ("3h ago"); older, a date in the reader's own language - the item rows
// said "indexed Sep 27, 2026" on a Vietnamese screen, words and date format both English.
function formatTimestamp(iso: string, t: Translate, locale: string): string {
  const d = new Date(iso)
  if (Date.now() - d.getTime() < 86400_000) return formatRelativeTime(iso, { t })
  return d.toLocaleDateString(locale, { month: 'short', day: 'numeric', year: 'numeric' })
}

// An item's own web address: the one its connector stated while listing it (`sourceUrl` - a Jira
// issue's browse link, a GitHub pull request's page), else its id when that is itself an address (a
// crawled page is listed under its own URL). Never a file's: no file item has either. An http(s)
// address only - httpUrl refuses anything else.
function itemUrl(item: TrackedItem): string | undefined {
  const url = item.sourceUrl ?? item.sourceId
  return url && httpUrl(url) ? url : undefined
}

// One item's state, as the kit's StatusMark: queued, working, indexed, in the graph and failed, and
// the three a sync has noticed but not yet acted on - detected, modified, deleted.
function ItemStatusMark({ status, graphDone, label }: { status: string; graphDone?: boolean; label: string }) {
  switch (status) {
    case 'QUEUED':
      return <StatusMark state="queued" label={label} />
    case 'DOWNLOADING':
    case 'CONVERTING':
    case 'EMBEDDING':
      // The same arc as the source's own health mark, so "this item is actively being worked on"
      // always reads as the one loading mark across the dialog.
      return <StatusMark state="working" label={label} />
    case 'COMPLETED':
      // Green: embedded. The brand color: embedded AND read by the knowledge graph. Drawn only
      // under the pointer since 2026-09-30 - see ItemRowContent.
      return <StatusMark state={graphDone ? 'ok-graph' : 'ok'} label={label} />
    case 'FAILED':
      return <StatusMark state="failed" label={label} />
    case 'DETECTED':
      return <StatusMark state="detected" label={label} />
    case 'MODIFIED':
      return <StatusMark state="modified" label={label} />
    case 'DELETED':
      return <StatusMark state="deleted" label={label} />
    default:
      return <StatusMark state="queued" label={label} />
  }
}

/**
 * "The knowledge graph is reading THIS item", on the row it belongs to.
 *
 * <p>The two kinds of work run independently - graph extraction happens long after an item is
 * indexed, on its own schedule - so a row can legitimately show one, the other, or neither, and a
 * reader has to be able to tell at a glance which is busy.
 *
 * <p>THE SHAPE CARRIES THAT, NOT THE COLOUR, and the palette is why. The indexing indicator is
 * the kit's Spinner, a rotating ARC; this is its ring of SPOKES (StatusMark's `graph`, the kit
 * Spinner's `spokes` since the 2026-10 rebuild), which is a different silhouette at
 * 14px where a second arc in a slightly different blue would not be. Colour cannot do the job on
 * its own here: `--brand-accent-legible` and `--brand-legible` resolve to the same value in dark
 * mode and to near-neighbours in light, and the only strongly separate hues left are success and
 * warning - both of which would say something untrue about a healthy background task.
 */
function GraphReadingMark({ done, total }: { done: number; total: number }) {
  const { t } = useTranslation()
  return (
    <StatusMark
      state="graph"
      size={14}
      label={total > 0
        ? t('Building the knowledge graph - passage {done} of {total}', {
            done: done.toLocaleString(), total: total.toLocaleString(),
          })
        : t('Building the knowledge graph')}
    />
  )
}

function metaParts(item: TrackedItem, t: Translate, locale: string): string[] {
  const parts: string[] = []
  if (item.sourceSizeBytes && item.sourceSizeBytes > 0) parts.push(formatFileSize(item.sourceSizeBytes))
  if (item.status === 'COMPLETED' && item.lastIndexedAt) {
    parts.push(t('indexed {when}', { when: formatTimestamp(item.lastIndexedAt, t, locale) }))
  }
  if (item.sourceLastModified) parts.push(t('modified {when}', { when: formatTimestamp(item.sourceLastModified, t, locale) }))
  if (item.createdAt) parts.push(t('added {when}', { when: formatTimestamp(item.createdAt, t, locale) }))
  return parts
}

// Sync now's glyph: the kit's refresh arrows, turning while a sync runs - the kit's sign that an
// action is under way (RefreshIcon), rather than IconButton's `busy`, which swaps in a Spinner.
// Wrapped because IconButton takes a glyph component, and RefreshIcon's size is a number only.
function SyncingGlyph({ size, className }: { size?: number | string; className?: string }) {
  return <RefreshIcon size={Number(size ?? 16)} spinning className={className} />
}

// The same arrows at rest, so the glyph does not change shape when a sync starts.
function SyncGlyph({ size, className }: { size?: number | string; className?: string }) {
  return <RefreshIcon size={Number(size ?? 16)} className={className} />
}

// What a hover shows of a long message. Copy error message in the row's menu has all of it; a
// tooltip holding a web server's whole error page ran off the bottom of the window.
const TOOLTIP_CHARS = 300

function clip(text: string): string {
  return text.length > TOOLTIP_CHARS ? `${text.slice(0, TOOLTIP_CHARS).trimEnd()}...` : text
}

// What a pass said beside succeeding, in full: text that can be selected, each address in it a
// link, and the whole of it one click from the clipboard.
function SyncNote({ note }: { note: string }) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  // An address ends at a space or at the bracket, comma or full stop the sentence closes it with.
  const parts = note.split(/(https?:\/\/[^\s)]+[^\s).,;])/)
  return (
    // The kit's one callout box, padding included.
    <Callout tone="neutral" icon={null} className="mt-2">
      <div className="flex items-start gap-1.5">
        <p className="flex-1 min-w-0 break-words select-text">
          {parts.map((part, i) => i % 2 === 1
            ? <ExternalLink key={i} href={part} iconSize={11}>{part}</ExternalLink>
            : part)}
        </p>
        <CopyButton
          getText={() => note}
          label={t('Copy')}
          copiedLabel={t('Copied')}
          onCopied={() => addToast(t('Copied'), 'success')}
        />
      </div>
    </Callout>
  )
}

// Shared row body for both the pinned (always-active) section and the paged list - `active` is passed explicitly rather than derived from status, since the paginated
// list is guaranteed to never contain active-status items (the backend splits them out).
function ItemRowContent({ item, connector, active, graphReading, className }: {
  item: TrackedItem; connector: ConnectorConfig; active: boolean
  /** Passage counts when the graph has THIS item open right now, absent otherwise. */
  graphReading?: { done: number; total: number }
  className?: string
}) {
  const { t, locale } = useTranslation()
  const url = itemUrl(item)
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const readOnly = !connector.ownedByMe

  // Deletes just this one uploaded file - distinct from "Delete connector" (the whole folder) in
  // the modal header. Disabled the instant it's clicked (isPending) so a slow connection can't be
  // clicked twice into a double-delete, and shows a spinner in place of the trash icon rather than
  // just going unresponsive, so it's clear the click registered and the user should wait.
  const deleteFile = useMutation({
    mutationFn: () => filesApi.remove(item.fileId!),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.connectorItemsActive(connector.id) })
      qc.invalidateQueries({ queryKey: keys.connectorItems(connector.id) })
      qc.invalidateQueries({ queryKey: keys.fileQuota() })
      qc.invalidateQueries({ queryKey: keys.connectorSummary() })
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to delete "{title}"', { title: item.title }), e), 'danger'),
  })

  const statusLabel = t(STATUS_LABELS[item.status] ?? item.status)
  const statusTooltip = active && item.status === 'EMBEDDING' && chunkProgress(item)
    ? `${statusLabel} ${chunkProgress(item)}`
    : statusLabel
  // An indexed item is the normal case and carries no mark at rest (the UI review, 2026-09-30):
  // a check on every row hid the one that failed. Its check - green, or the brand ink once the
  // knowledge graph has read it - still appears under the pointer, with the words in its tooltip.
  const settled = item.status === 'COMPLETED'

  // Stored in English when the item failed, read in whatever language the page is in now.
  const failure = item.status === 'FAILED' ? serverText(item.failureMessage) : undefined
  const canDelete = !!item.fileId && !readOnly && !deleteFile.isSuccess

  // ONE ROW GEOMETRY for every item, in progress or settled - the kit's ItemRow: the mark, a title
  // line, a one-line meta, and - on a failure - the reason, truncated with the whole of it on
  // hover. The row's actions are one menu in a fixed slot on the title line, so its right edge is
  // the same on every row whether the item is a file, has failed, or neither. The copy button used
  // to sit at the end of the failure line, lined up with nothing.
  const meta = metaParts(item, t, locale)
  return (
    <KitItemRow
      // `py-3` and the dialog's gutter; the meta line ONE LINE, clipped rather than wrapped, because
      // the virtualized list sizes each row before it is drawn (and on a phone the date used to wrap
      // under a truncated title).
      className={cn('px-4 sm:px-6 py-3', className)}
      metaTruncate
      statusMark={(
        <span className={cn('flex items-center', settled && 'opacity-0 mouse:group-hover:opacity-100 transition-opacity')}>
          <ItemStatusMark status={item.status} graphDone={item.graphDone} label={statusTooltip} />
        </span>
      )}
      title={item.title}
      titleLink={url ? cls => (
        <ExternalLink href={url} className={cls} labelClassName="truncate" title={item.title}>
          {item.title}
        </ExternalLink>
      ) : undefined}
      meta={meta.length > 0 ? meta : undefined}
      // The reason, truncated with the start of it on hover, and a copy button at the END OF THE
      // LINE since 2026-10-01 (maintainer's feedback) - it was an entry in the row's menu, one
      // click further from the one thing you do with an error.
      error={failure ? (
        <span className="flex items-center gap-1.5 min-w-0 animate-fade-in">
          <span className="min-w-0 flex-1">
            <Tooltip label={clip(failure)} fullWidth>
              <span className="block truncate">{failure}</span>
            </Tooltip>
          </span>
          <CopyButton
            getText={() => failure}
            label={t('Copy error message')}
            copiedLabel={t('Error message copied')}
            onCopied={() => addToast(t('Error message copied'), 'success')}
          />
        </span>
      ) : undefined}
      trailing={graphReading && <GraphReadingMark done={graphReading.done} total={graphReading.total} />}
      // NO PER-ITEM VIEW BUTTONS. The chunk list loses nothing: the source-wide chunks dialog is a
      // list of ITEMS that expand, so one document's passages are one click further in. The
      // per-item GRAPH has no other door, and that is a real subtraction - the view still exists
      // (KnowledgeGraphModal takes a documentId) and wants one entry in this menu to return.
      actions={(
        <span className="w-6 h-5 flex items-center justify-center">
          {deleteFile.isPending ? <Spinner size="sm" /> : (
            <ActionMenu
              label={t('Actions for {title}', { title: item.title })}
              size="sm"
              groups={[
                [canDelete && {
                  label: t('Delete this file'),
                  icon: <Trash2 size={16} />,
                  danger: true,
                  onSelect: () => deleteFile.mutate(),
                }],
              ]}
            />
          )}
        </span>
      )}
    />
  )
}

function ActiveItemRow({ item, connector, graphReading }: {
  item: TrackedItem; connector: ConnectorConfig; graphReading?: { done: number; total: number }
}) {
  // The same padding and height as a settled row - it was inset by a left rule and tinted, so the
  // two halves of one list had different left edges. A quiet tint is all that marks the pinned
  // rows now (on their container, so it reaches under the scrollbar gutter); the mark beside the
  // title says what is happening.
  return <ItemRowContent item={item} connector={connector} active graphReading={graphReading} />
}
export function ConnectorDetailModal({ connector, onClose }: Props) {
  const { t } = useTranslation()
  const { data: activeItems, isLoading: activeLoading } = useActiveItems(connector.id)
  const {
    data: pagedData, isLoading: itemsLoading, page: itemsPage, hasMore, setPage: setItemsPage,
  } = useConnectorItemsPage(connector.id)
  const syncNow = useSyncNow()
  const setShared = useSetShared()
  const setPriority = useSetPriority()
  const remove = useRemoveConnector()
  const retryFailed = useRetryFailed()
  const reindex = useReindex()
  const qc = useQueryClient()
  const [deleteConfirm, setDeleteConfirm] = useState(false)
  const [configOpen, setConfigOpen] = useState(false)
  const [sourceGraphOpen, setSourceGraphOpen] = useState(false)
  const [allChunksOpen, setAllChunksOpen] = useState(false)
  const [noteOpen, setNoteOpen] = useState(false)
  // A custom source's ingestion API - open by itself while nothing has been pushed yet, which is
  // when the endpoint and a token are what the owner came for.
  const [ingestOpen, setIngestOpen] = useState(connector.itemCount === 0)
  const activeOperation = useConnectorOperationStore(s => s.operations[connector.id])

  const sseDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => { if (sseDebounceRef.current) clearTimeout(sseDebounceRef.current) }, [])
  const handleSSE = useCallback((_type: string, raw: string) => {
    try {
      const data = JSON.parse(raw)
      if ((data.type === 'connector-update' || data.type === 'connector-synced') && data.connectorId === connector.id) {
        if (sseDebounceRef.current) clearTimeout(sseDebounceRef.current)
        sseDebounceRef.current = setTimeout(() => {
          qc.invalidateQueries({ queryKey: keys.connectorItemsActive(connector.id) })
          qc.invalidateQueries({ queryKey: keys.connectorItems(connector.id) })
          qc.invalidateQueries({ queryKey: keys.connectorSummary() })
          qc.invalidateQueries({ queryKey: keys.connectorsByType() })
        }, 500)
      }
      // The graph opened or closed one of this source's items: re-read the pinned rows and the
      // passage counts now rather than on the next ten-second poll.
      if (data.type === 'connector-graph' && data.connectorId === connector.id) {
        qc.invalidateQueries({ queryKey: keys.connectorItemsActive(connector.id) })
        qc.invalidateQueries({ queryKey: keys.graphProgress(connector.id) })
      }
    } catch { /* non-JSON SSE */ }
  }, [connector.id, qc])

  useSSE(handleSSE)

  // Drops anything PINNED above the list. An item the knowledge graph is reading is COMPLETED, so
  // since 2026-09-24 it arrives in both the pinned set and a page - once is enough.
  const inactiveItems = useMemo(() => {
    const pinned = new Set<string>((activeItems ?? []).map(i => i.id))
    return (pagedData?.items ?? []).filter(item => !pinned.has(item.id))
  }, [pagedData, activeItems])

  // WHICH ITEMS THE GRAPH HAS OPEN, polled while this modal is on screen.
  //
  // Extraction runs on its own schedule long after indexing, so nothing in the item list would
  // otherwise change while it works - the source looked idle, and the only place that said
  // anything was a line of totals in the settings dialog, which is not where somebody asking
  // "what is it doing" looks. Ten seconds is far finer than the thing it watches, a passage
  // taking roughly a minute on a local model, and it stops the moment the modal closes.
  const graphProgress = useQuery({
    queryKey: keys.graphProgress(connector.id),
    queryFn: () => indexingApi.graphProgress(connector.id),
    enabled: connector.graphEnabled === true,
    refetchInterval: 10_000,
  })

  const graphReading = useMemo(() => {
    const map = new Map<string, { done: number; total: number }>()
    for (const r of graphProgress.data?.reading ?? []) {
      map.set(r.documentId, { done: r.chunksDone, total: r.chunksTotal })
    }
    return map
  }, [graphProgress.data])

  // What the Sync now icon shows: the click's request in flight, then the server's own word that a
  // pass or a drain is running. The request alone lasted milliseconds - the sync runs on a thread
  // of its own - so the spinner used to blink and stop while the source went on syncing, and a
  // click on a source already syncing changed nothing at all.
  const syncActive = syncNow.isPending || connector.syncing === true

  // The list refetches on SSE, but nothing is sent when a sync pass or a drain merely ENDS (and a
  // pass that throws sends nothing at all), so a list read while `syncing` was true could keep the
  // icon spinning for good. Re-read it while the flag is up, only while this modal is open.
  useEffect(() => {
    if (!connector.syncing) return
    const timer = setInterval(() => {
      qc.invalidateQueries({ queryKey: keys.connectorSummary() })
      qc.invalidateQueries({ queryKey: keys.connectorsByType() })
    }, 3000)
    return () => clearInterval(timer)
  }, [connector.syncing, qc])

  // Whether a sync is RUNNING, and the rest of what the source is doing - the same derivation its
  // row on the Sources page uses (sourceState), so the two never disagree.
  const state = sourceState(connector, activeOperation)
  const isSyncing = state.syncing
  const isProcessing = state.processing
  // Gates Retry. Deliberately tied to failedCount alone, not connector.status === 'ERROR':
  // retryFailed() resets every FAILED item back to QUEUED, so failedCount always drops to 0 as an
  // immediate, direct result of choosing it - the entry must disappear right along with it rather
  // than linger for a connector-level status that hasn't caught up yet. A connector-level fetch
  // failure with zero failed items (e.g. an expired credential) is instead retried via Sync now.
  const hasFailed = connector.failedCount > 0
  // A source surfaced only via the "show shared sources from other users" toggle - view only,
  // matching its row on the Sources page.
  const readOnly = !connector.ownedByMe
  // A LOCAL folder only - an uploaded one lives in the server's own sandbox and cannot move.
  const uploadsAreHowFilesArrive = useFileUpload()
  const canRelink = connector.type === 'files' && !readOnly && !uploadsAreHowFilesArrive
  const relinkFolder = useRelinkFolder(connector)
  const canConfigure = canConfigureConnector(connector)
  const descriptor = useCatalogType(connector.type)
  // Documents are pushed to it (a custom source): its owner gets the ingestion API's panel.
  const pushSource = descriptor?.capabilities.push === true && !readOnly
  const busy = !!activeOperation

  const displayName = extractDisplayName(connector.name, connector.type)
  const destUrl = connectorUrl(connector)

  const initialLoading = activeLoading || itemsLoading
  const isEmpty = !initialLoading && (activeItems?.length ?? 0) === 0 && inactiveItems.length === 0

  // TWO LABELED ACTIONS AND ONE MENU, since 2026-09-30 (the UI review). The header was a row of
  // eight unlabeled icons in four groups, which a reader had to hover one by one to learn, and on a
  // phone it ran off the right edge and pushed the source's own name out of the dialog. What people
  // do most - sync it, change what it reads - is labeled; everything else is in the menu, grouped
  // the way the icons were: what you can LOOK at, what you can SET, what you can RUN, and Delete
  // last, apart from the rest, because it is the one thing here that cannot be undone. On a phone
  // the two labeled actions join the menu too, so the header is the mark, the name and one button.
  const syncItem = {
    label: syncActive ? t('Syncing...') : t('Sync now'),
    icon: <RefreshCw size={16} />,
    disabled: syncActive || busy,
    onSelect: () => syncNow.mutate(connector.id),
  }
  const settingsItem = canConfigure && {
    label: t('Settings'),
    icon: <SlidersHorizontal size={16} />,
    disabled: busy,
    onSelect: () => setConfigOpen(true),
  }
  const menuGroups = (phone: boolean) => [
    phone ? [syncItem, settingsItem] : [],
    [
      connector.itemCount > 0 && {
        label: t('View indexed chunks'), icon: <LayoutList size={16} />, onSelect: () => setAllChunksOpen(true),
      },
      connector.graphEnabled && {
        label: t('View the knowledge graph'), icon: <Waypoints size={16} />, onSelect: () => setSourceGraphOpen(true),
      },
    ],
    [
      // Hidden, not refused: the server rejects sharing a type that is read as its owner, and an
      // entry that can only fail is worse than none.
      FEATURES.sharing && connector.shareable !== false && {
        label: connector.shared ? t('Stop sharing') : t('Share with others'),
        icon: <Share2 size={16} />,
        onSelect: () => setShared.mutate({ id: connector.id, shared: !connector.shared }),
      },
      {
        label: connector.priority ? t('Remove priority') : t('Process first'),
        icon: <Rocket size={16} />,
        disabled: setPriority.isPending,
        onSelect: () => setPriority.mutate({ id: connector.id, priority: !connector.priority }),
      },
    ],
    [
      hasFailed && {
        label: t('Retry all failed items'),
        icon: <RotateCcw size={16} />,
        disabled: retryFailed.isPending || busy,
        onSelect: () => retryFailed.mutate(connector.id),
      },
      {
        label: t('Re-index'),
        icon: <ListRestart size={16} />,
        disabled: reindex.isPending || busy,
        onSelect: () => reindex.mutate(connector.id),
      },
    ],
    [
      {
        label: t('Delete source'),
        icon: <Trash2 size={16} />,
        danger: true,
        disabled: remove.isPending || busy,
        onSelect: () => setDeleteConfirm(true),
      },
    ],
  ]

  // The source's name, linked out where it has an address, with its health beside it (only when
  // something is not fine). A long name truncates rather than wrapping the actions onto a line of
  // their own.
  const title = (
    <span className="flex items-center gap-3 min-w-0">
      {destUrl ? (
        <ExternalLink
          href={destUrl}
          className="min-w-0 max-w-full mouse:hover:text-brand-legible"
          labelClassName="truncate"
          iconSize={14}
          title={connector.name}
        >
          {displayName}
        </ExternalLink>
      ) : (
        <span className="truncate min-w-0 animate-fade-in">{displayName}</span>
      )}
      <SourceHealthMark connector={connector} busy={isProcessing} graphReading={state.graphReading} />
    </span>
  )

  // Actions - none at all for a source somebody else owns and shares.
  const actions = !readOnly && (
    <>
      {/* ICON BUTTONS, no border, since 2026-10-01 (maintainer's feedback) - the same shape as the
          menu beside them, each named by its tooltip. Two outlined buttons and a bare menu read as
          two kinds of control; this is one row of one kind. The phone keeps everything in the menu. */}
      <div className="hidden sm:flex items-center gap-1">
        <IconButton
          icon={syncActive ? SyncingGlyph : SyncGlyph}
          label={syncItem.label}
          onClick={syncItem.onSelect}
          disabled={syncItem.disabled}
        />
        {canConfigure && (
          <IconButton
            icon={SlidersHorizontal}
            label={t('Settings')}
            tooltip={configureLabel(connector.type, descriptor, !!connector.fields, t)}
            onClick={() => setConfigOpen(true)}
            disabled={busy}
          />
        )}
        <ActionMenu label={t('More actions')} groups={menuGroups(false)} />
      </div>
      <div className="sm:hidden">
        <ActionMenu label={t('More actions')} groups={menuGroups(true)} />
      </div>
    </>
  )

  // Under the name: what the source holds, then why its last sync failed, the note a healthy pass
  // left, and the fix for a folder that moved.
  const subtitle = (
    <>
      <MetaList
        className="flex flex-wrap items-center gap-y-0.5 min-w-0"
        items={[
          <span>
            {activeOperation
              ? t(PHASE_LABELS[activeOperation.phase])
              : state.indexing
                ? t('{done}/{total} indexed', {
                    done: connector.processedCount,
                    total: formatItemCount(connector.itemCount, connector.allItemsFetched),
                  })
                : isSyncing && connector.itemCount === 0
                  ? t('Discovering items...')
                  : t('{shown} item|{shown} items', {
                      count: connector.itemCount,
                      shown: formatItemCount(connector.itemCount, connector.allItemsFetched),
                    })}
          </span>,
          connector.lastSyncedAt && <span>{t('synced {when}', { when: formatRelativeTime(connector.lastSyncedAt, { t }) })}</span>,
          // The connection it reads through - named to its owner only, since to anybody else the
          // source lends none. The server it resolves to is in the tooltip.
          connector.connectionName && (
            connector.serverUrl ? (
              <Tooltip label={connector.serverUrl}>
                <span>{t('via {connection}', { connection: connector.connectionName })}</span>
              </Tooltip>
            ) : (
              <span className="animate-fade-in">{t('via {connection}', { connection: connector.connectionName })}</span>
            )
          ),
          FEATURES.sharing && (readOnly
            ? <span>{t('shared with you')}</span>
            : connector.shared && <span>{t('shared')}</span>),
          connector.priority && (
            <span className="flex items-center gap-1 text-brand-legible animate-fade-in">
              <Rocket size={14} /> {t('prioritized')}
            </span>
          ),
          // What the green-or-brand check on a healthy source used to say by colour alone.
          state.healthy && graphFinished(connector) && <span>{t('in the knowledge graph')}</span>,
          connector.reindexing && (
            <Tooltip label={connector.reindexTargetModel
            ? t('Reindexing to {model} - old results still shown until it finishes', { model: connector.reindexTargetModel })
            : t('Reindexing to a new model - old results still shown until it finishes')}>
              <span className="flex items-center gap-1 text-brand-accent-legible">
                <ListRestart size={14} /> {t('reindexing')}
              </span>
            </Tooltip>
          ),
          connector.status === 'PAUSED' && (
            <Tooltip label={t('Storage quota exceeded - processing resumes automatically once usage drops back under the limit')}>
              <span className="flex items-center gap-1 text-warning">
                {t('paused - quota exceeded')}
              </span>
            </Tooltip>
          ),
          hasFailed && <span className="text-danger">{t('{count} failed', { count: connector.failedCount })}</span>,
          // A NOTE on a healthy pass - pages a crawl could not load, a folder past its tier's cap -
          // is one phrase here, since 2026-09-30. It was a paragraph under the header, and a crawl
          // whose same pages fail every pass kept it there for good (maintainer's feedback).
          //
          // The whole of it OPENS UNDER THE HEADER ON A CLICK, since 2026-10-02. It was a hover
          // tooltip: the addresses in it could be read and nothing else - not selected, not
          // copied, not followed - and a hover does not exist on a touch screen at all
          // (maintainer's feedback). A warning-toned text link with the kit's disclosure chevron.
          pushSource && (
            <button
              type="button"
              onClick={() => setIngestOpen(open => !open)}
              aria-expanded={ingestOpen}
              className={textLink()}
            >
              {t('Ingestion API')}
              <DisclosureChevron open={ingestOpen} />
            </button>
          ),
          state.note && !syncActive && (
            <button
              type="button"
              onClick={() => setNoteOpen(open => !open)}
              aria-expanded={noteOpen}
              className={textLink('warning')}
            >
              {t('Not everything was indexed')}
              <DisclosureChevron open={noteOpen} />
            </button>
          ),
        ]}
      />

      {/* The reason, in words. It was a tooltip on the card only, so the dialog - where someone
          goes to find out what is wrong - never said. Hidden while a sync runs: the note describes
          the PREVIOUS pass, and the one now running replaces it when it ends (SyncService) - left
          up, it read as a problem with the sync the user had just started. The row's spinner does
          the same. */}
      <Presence show={!!(state.syncFailed && !syncActive)}>
        <InlineNotice tone="danger" className="mt-2">
          <span className="line-clamp-3">{serverText(connector.lastSyncError)}</span>
        </InlineNotice>
      </Presence>
      <Collapse open={!!(state.note && !syncActive && noteOpen)}>
        {state.note && <SyncNote note={serverText(connector.lastSyncError!)} />}
      </Collapse>
      {/* A local folder that is not where it was: the fix, beside the reason. */}
      <Presence show={canRelink && connector.status === 'ERROR'}>
        <div className="mt-2 flex items-center gap-3">
          <Button variant="secondary" size="sm" onClick={relinkFolder.relink} loading={relinkFolder.busy} disabled={!!activeOperation}>
            {!relinkFolder.busy && <FolderSearch size={14} />} {t('Relink folder')}
          </Button>
          <Presence show={!!relinkFolder.error} collapse={false}><InlineNotice tone="danger">{relinkFolder.error}</InlineNotice></Presence>
        </div>
      </Presence>
    </>
  )

  return (
    <>
        <ModalShell onClose={onClose} size="xl" fill ariaLabel={displayName}>
            <DialogHeader
              icon={getTypeIcon(connector.type, 20)}
              title={title}
              subtitle={subtitle}
              actions={actions || undefined}
            />

            {/* Waits on the type's descriptor - the Collapse opens it, so it arrives rather than appears. */}
            <Collapse open={pushSource && ingestOpen}>
              {pushSource && <IngestApiPanel sourceId={connector.id} />}
            </Collapse>

            {/* Items list */}
            {/* Loading, empty and the list swap with a fade, in the one region that scrolls. */}
            <FadeSwap swapKey={initialLoading ? 'loading' : isEmpty ? 'empty' : 'list'} className="flex-1 min-h-0 flex flex-col">
              {initialLoading ? (
                <div className="divide-y divide-line/20 animate-fade-in" aria-hidden="true">
                  {Array.from({ length: 8 }, (_, i) => <ItemRowSkeleton key={i} className="px-4 sm:px-6 py-3" />)}
                </div>
              ) : isEmpty ? (
                <EmptyState className="px-4 sm:px-6">
                  {pushSource ? t('Nothing has been pushed to this source yet.') : t('No items yet')}
                </EmptyState>
              ) : (
                <>
                  {/* Pinned section: items actively downloading/converting/embedding right now,
                      and - since 2026-09-24 - the ones the knowledge graph is reading.
                      Always small (bounded by in-flight concurrency), never paginated - a newly
                      active item appears here immediately via SSE-driven refetch, so there's no
                      need to scroll to find it within the (possibly huge) list below. */}
                  {/* The gutter is reserved here AND on the list below, so a pinned row and a settled
                      one end at the same right edge whether or not the list scrolls. */}
                  <Presence show={!!activeItems && activeItems.length > 0} className="flex-shrink-0">
                    <div className="divide-y divide-line/20 border-b border-line/30 bg-brand-solid/5 overflow-y-hidden sm:[scrollbar-gutter:stable]">
                      {(activeItems ?? []).map(item => (
                        <ActiveItemRow key={item.id} item={item} connector={connector}
                                       graphReading={graphReading.get(item.id)} />
                      ))}
                    </div>
                  </Presence>
                  {/* TWENTY A PAGE (LIST_PAGE_SIZE), the region scrolling inside the dialog when a
                      page is taller than it; the pager stays under it. It was a virtualized list
                      that loaded more as it scrolled, which twenty rows do not need. */}
                  <div className="flex-1 min-h-0 overflow-y-auto sm:[scrollbar-gutter:stable]">
                    {inactiveItems.map(item => (
                      <div key={item.id} data-item-id={item.id} className="animate-fade-in">
                        <ItemRowContent item={item} connector={connector} active={false}
                                        graphReading={graphReading.get(item.id)} className="border-b border-line/20" />
                      </div>
                    ))}
                  </div>
                  <ListPager
                    className="flex-shrink-0 px-4 sm:px-6 border-t border-line/30"
                    page={itemsPage}
                    pages={null}
                    hasMore={hasMore}
                    onPage={setItemsPage}
                  />
                </>
              )}
            </FadeSwap>
        </ModalShell>
      {deleteConfirm && (
        <DeleteConfirmModal
          connectorName={displayName}
          // Dismisses both this confirmation and the detail modal behind it right away - the
          // DELETE returns as soon as the intent is persisted, and the teardown that follows is
          // reported on the source's row (SourceRow shows it as deleting).
          onConfirm={() => {
            remove.mutate(connector.id)
            setDeleteConfirm(false)
            onClose()
          }}
          onCancel={() => setDeleteConfirm(false)}
        />
      )}
      {configOpen && (
        <ConnectorSettingsModal connector={connector} onClose={() => setConfigOpen(false)} />
      )}
      {sourceGraphOpen && (
        <KnowledgeGraphModal
          connectorId={connector.id}
          title={connector.name}
          onClose={() => setSourceGraphOpen(false)}
        />
      )}
      {allChunksOpen && (
        <SourceChunksModal
          connector={connector}
          onClose={() => setAllChunksOpen(false)}
        />
      )}
    </>
  )
}

// What the Configure dialog holds for this type, said in its tooltip - read off the type's
// descriptor (what it reads through, whether it has file types and subfolders, fields of its own),
// so a new type is described without a case here. It used to say "crawl depth and page limit" for
// every source that was not a folder, which since 0.18.0 was wrong for most of them.
function configureLabel(type: string, descriptor: CatalogType | undefined, editableFields: boolean, t: (key: string) => string): string {
  if (type === 'webpage') return t('Configure crawl depth and page limit')
  if (!descriptor) return t('Configure this source')
  const connection = !!descriptor.protocol
  const files = descriptor.capabilities.fileTypeFilter
  const subfolders = descriptor.capabilities.recursiveScope
  if (connection && files && subfolders) return t('Configure connection, file types and subfolder scanning')
  if (connection && files) return t('Configure connection and file types')
  if (files && subfolders) return t('Configure file types and subfolder scanning')
  if (connection && editableFields) return t('Configure connection and settings')
  if (connection) return t('Configure connection')
  return t('Configure this source')
}
