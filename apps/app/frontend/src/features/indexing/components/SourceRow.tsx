import { memo } from 'react'
import { ExternalLink, MetaList, SourceRow as KitSourceRow, StatusMark, Tooltip, httpUrl, useFileDrop } from '@/components/ui/kit'
import { useConnectorOperationStore, PHASE_LABELS, type ConnectorOperation } from '@/store/connectorOperationStore'
import { walkDataTransferItems, type DroppedFile } from '../utils/dirEntryWalk'
import { useFileUpload } from '@/hooks/useFileUpload'
import type { ConnectorConfig } from '../types/indexing.types'
import { Rocket } from 'lucide-react'
import { SourceTypeMark } from '../utils/sourceMarks'
import { catalogTypeCategory } from '../utils/catalogRegistry'
import { formatRelativeTime } from '@/utils/formatRelativeTime'
import { useTranslation, type Translate } from '@/i18n'
import { serverText } from '@/i18n/serverText'

interface Props {
  connector: ConnectorConfig
  onOpenDetail?: (id: string) => void
  onDropFiles?: (folderName: string, files: DroppedFile[]) => void
}

// The type's mark, in the brand ink on a row and in the source dialog's header - one ink for every
// type. The mapping is sourceMarks.tsx's.
function getTypeIcon(type: string, size: number): React.ReactNode {
  return <SourceTypeMark type={type} size={size} className="text-brand-legible" />
}

/**
 * What a source is doing, derived once for its row and its dialog alike - they used to compute it
 * separately and disagreed (the dialog read only `status`, so a source whose every sync failed
 * could look healthy there while its card showed the error).
 */
export function sourceState(connector: ConnectorConfig, activeOperation?: ConnectorOperation) {
  // Whether a sync is RUNNING, from the orchestrator. It was `!allItemsFetched`, which says whether
  // a full enumeration has ever finished - so a folder whose last pass stopped short spun forever at
  // 220/220+ with nothing processing, and the spinner hid the error that said why.
  const syncing = !!connector.syncing
  const indexing = connector.processedCount + connector.failedCount < connector.itemCount && connector.itemCount > 0
  const paused = connector.status === 'PAUSED'
  const processing = !paused && (syncing || indexing || !!activeOperation)
  // The knowledge graph reading one of this source's items - its own flag because the items are
  // COMPLETED and none of the counts above move.
  const graphReading = !paused && !!connector.graphExtracting
  // connector.status (ACTIVE/ERROR/PAUSED) reflects the connector's own fetch health, never
  // item-level failures - nothing flips it when documents fail to embed, so a connector with e.g.
  // 900/900 failedCount still reads ACTIVE.
  const failed = connector.failedCount > 0
  const syncFailed = connector.status === 'ERROR' && !!connector.lastSyncError
  // A healthy source with a note: the pass finished but did not see everything - a tier's file cap,
  // a crawl's page limit. What was indexed is fine, and the note says what was left out.
  const note = connector.status !== 'ERROR' && !!connector.lastSyncError
  return {
    syncing, indexing, processing, graphReading, failed, syncFailed, note, paused,
    healthy: !processing && !graphReading && !failed && !syncFailed && !note && !paused
      && !connector.reindexing && !connector.deleting && !activeOperation,
  }
}

/** Every indexed item of this source is in the knowledge graph. */
function graphFinished(connector: ConnectorConfig): boolean {
  return !!connector.graphEnabled && (connector.graphItemsPending ?? 0) === 0
    && (connector.graphItemsExtracted ?? 0) > 0
}

/**
 * A source's health mark - ONLY WHEN SOMETHING IS NOT FINE, since 2026-09-30 (the UI review). A
 * green check on seven of eight healthy sources drowned out the one error; now a healthy source
 * shows nothing here and every mark that remains means "look here". Null when healthy.
 *
 * The kit's StatusMark since the 2026-10 rebuild: its spokes for the graph, its arc for embedding
 * (a different silhouette, so graph work is not mistaken for indexing), the same pair the source
 * dialog's rows use.
 */
function SourceHealthMark({ connector, busy, graphReading, size = 16 }: {
  connector: ConnectorConfig
  busy: boolean
  graphReading: boolean
  size?: 14 | 16
}) {
  const { t } = useTranslation()
  if (busy) return <StatusMark state="working" size={size} />
  if (graphReading) return <StatusMark state="graph" size={size} label={t('Building the knowledge graph')} />
  if (connector.failedCount > 0) return <StatusMark state="failed" size={size} />
  if (connector.status === 'ERROR' && connector.lastSyncError) {
    return <StatusMark state="failed" size={size} label={serverText(connector.lastSyncError) ?? undefined} />
  }
  if (connector.lastSyncError) {
    return <StatusMark state="warning" size={size} label={serverText(connector.lastSyncError) ?? undefined} />
  }
  if (connector.status === 'PAUSED') return <StatusMark state="paused" size={size} />
  return null
}

/**
 * The quiet half of the health mark: a healthy source's check, shown only while the pointer (or
 * keyboard focus) is on its row. Kept rather than dropped because it carries one thing nothing
 * else on the row does - whether the knowledge graph has read every item (the check in the brand
 * ink) or only the index is done (green).
 */
function HealthyMark({ connector, size = 16 }: { connector: ConnectorConfig; size?: 14 | 16 }) {
  const { t } = useTranslation()
  const inGraph = connector.status === 'ACTIVE' && graphFinished(connector)
  return inGraph
    ? <StatusMark state="ok-graph" size={size} label={t('Indexed and in the knowledge graph')} />
    : <StatusMark state="ok" size={size} label={t('Indexed')} />
}

function formatItemCount(count: number, allFetched: boolean): string {
  if (!allFetched && count > 0) return `${count}+`
  return String(count)
}

// A source of files is named by its folder's path; the row shows the folder's own name.
function extractDisplayName(name: string, type: string): string {
  if (catalogTypeCategory(type) === 'FILES' || type === 'files') {
    const sep = name.includes('\\') ? '\\' : '/'
    const parts = name.split(sep).filter(Boolean)
    return parts[parts.length - 1] || name
  }
  return name
}

// A web address for the source, or nothing - the server's `webUrl` (the connector's own answer:
// a Jira project's page, a GitHub repository's), checked once more here as a whole http(s) address
// before it becomes a link (security review swx-11). The kit's ExternalLink refuses anything else
// too, but a source the caller cannot vouch for should not even be offered to it.
function connectorUrl(connector: ConnectorConfig): string | undefined {
  const url = connector.webUrl ?? undefined
  return url && httpUrl(url) ? url : undefined
}

type StatusTone = 'plain' | 'work' | 'danger' | 'warning'

/** One phrase of a source's status: its words, its color, and - where the words are a summary -
 *  the detail behind them. */
interface StatusPart {
  text: string
  tone: StatusTone
  detail?: string
}

/**
 * What is wrong or under way, as phrases - empty when the source is fine. Shared by the row's Status
 * column and the phone row's meta line, so the two can never say different things.
 *
 * <p>What is under way, then what went wrong - BOTH, since 2026-09-30. It was the first that
 * applied, so a web crawl whose every known page was embedded read "4743/4744 indexed" beside a
 * spinner for as long as the crawl ran - one page to go, apparently - while its one failure and the
 * graph being built went unsaid (maintainer's feedback). The count is shown only while items are
 * actually waiting; a pass still looking for changes says so.
 *
 * <p>PLAIN PHRASES since 2026-10-01, so the whole status can also be one tooltip: the column
 * truncates, and "building the knowledge graph - 3 ..." cut off the part that said what failed.
 */
function statusParts(connector: ConnectorConfig, activeOperation: ConnectorOperation | undefined, t: Translate): StatusPart[] {
  const s = sourceState(connector, activeOperation)
  if (connector.deleting) {
    return [{ text: activeOperation ? t(PHASE_LABELS[activeOperation.phase]) : t('Deleting...'), tone: 'plain' }]
  }
  if (activeOperation) return [{ text: t(PHASE_LABELS[activeOperation.phase]), tone: 'plain' }]
  if (s.paused) {
    return [{
      text: t('paused - quota exceeded'),
      tone: 'warning',
      detail: t('Storage quota exceeded - processing resumes automatically once usage drops back under the limit'),
    }]
  }
  const parts: StatusPart[] = []
  if (s.processing) {
    parts.push({
      tone: 'work',
      text: s.indexing
        ? t('{done}/{total} indexed', {
            done: connector.processedCount,
            total: formatItemCount(connector.itemCount, connector.allItemsFetched),
          })
        : connector.itemCount > 0 ? t('Syncing...') : t('Discovering items...'),
    })
  } else if (connector.reindexing) {
    parts.push({
      tone: 'work',
      text: t('reindexing'),
      detail: connector.reindexTargetModel
        ? t('Reindexing to {model} - old results still shown until it finishes', { model: connector.reindexTargetModel })
        : t('Reindexing to a new model - old results still shown until it finishes'),
    })
  } else if (s.graphReading) {
    parts.push({ tone: 'work', text: t('building the knowledge graph') })
  }
  if (s.failed) {
    parts.push({ tone: 'danger', text: t('{count} failed', { count: connector.failedCount }) })
  } else if (s.syncFailed) {
    parts.push({ tone: 'danger', text: t('Sync failed'), detail: serverText(connector.lastSyncError) ?? undefined })
  } else if (s.note && parts.length === 0) {
    parts.push({ tone: 'warning', text: t('Not everything was indexed'), detail: serverText(connector.lastSyncError) ?? undefined })
  }
  return parts
}

const TONE_CLASS: Record<StatusTone, string> = {
  plain: '',
  work: 'text-brand-accent-legible',
  danger: 'text-danger',
  warning: 'text-warning',
}

/** The phrases, each in its tone - joined by the kit MetaList in the column, flattened into the
 *  phone's meta line. */
function statusSpans(parts: StatusPart[]): React.ReactNode[] {
  return parts.map((p, i) => <span key={i} className={TONE_CLASS[p.tone]}>{p.text}</span>)
}

/** The same phrases as one string, with each one's detail - the tooltip over the column. */
function statusTooltip(parts: StatusPart[]): string {
  return parts.map(p => (p.detail ? `${p.text}: ${p.detail}` : p.text)).join(' - ')
}


/**
 * One registered source, as a row of the Sources list.
 *
 * <p>IT WAS A TILE IN A GALLERY until 2026-09-30 (the UI review): one card per source, grouped by
 * type, so eight sources took 1,300px and every card said the same things in the same places. A
 * row puts what people compare - how much is in it, when it last synced, whether anything is
 * wrong - in columns they can read down.
 *
 * <p>Everything the card did, the row does: a click (or Enter) opens the source dialog, the name
 * links out to the source where it has an address, and an uploaded folder takes dropped files.
 * The card had no menu of its own - every action lives in the dialog one click away.
 */
export const SourceRow = memo(function SourceRow({ connector, onOpenDetail, onDropFiles }: Props) {
  const { t } = useTranslation()
  const activeOperation = useConnectorOperationStore(s => s.operations[connector.id])

  // A row surfaced only via the "show shared sources from other users" toggle - view only.
  const readOnly = !connector.ownedByMe
  // Only where uploads are how files arrive. Dropping onto an existing folder means "add these
  // files to that folder", which has no equivalent where the source IS a directory on the user's
  // own disk: adding to it means putting files in it from the file manager, and Proxyma picks them
  // up on the next sync. Rather than fake an operation that doesn't exist, those rows aren't drop
  // targets at all - the page-level drop still registers a NEW folder (see SourcesPage).
  // Called unconditionally and THEN used - not inlined into the && chain below, where the
  // short-circuit would skip the call on a non-files row and change the hook order between
  // renders of the same component.
  const uploadsAreHowFilesArrive = useFileUpload()
  const isDropTarget = connector.type === 'files' && !readOnly && uploadsAreHowFilesArrive

  // The kit's drop counter; `stop` keeps the page's own drop target from lighting up as well.
  const drop = useFileDrop({
    enabled: isDropTarget,
    stop: true,
    onDrop: data => {
      if (data.items.length === 0 || !onDropFiles) return
      // The upload destination must be the source's actual registered name (which may itself
      // contain "/" for a nested folder) - extractDisplayName() truncates to the last path segment
      // for the TITLE only and must never be reused as the upload folder, or files silently land
      // under a different (wrong, truncated) folder.
      const folderName = connector.name
      walkDataTransferItems(data.items).then(({ files }) => {
        if (files.length > 0) onDropFiles(folderName, files)
      })
    },
  })

  const state = sourceState(connector, activeOperation)
  // Server-side deletion is asynchronous, so the row outlives the request that triggered it and
  // has to represent that state itself. connector.deleting is the durable signal (survives a
  // refresh); the operation covers the window before the list has refetched.
  const isDeleting = connector.deleting || activeOperation?.operation === 'delete'

  const displayName = extractDisplayName(connector.name, connector.type)
  const destUrl = connector.type !== 'files' ? connectorUrl(connector) : undefined
  const items = formatItemCount(connector.itemCount, connector.allItemsFetched)
  const synced = connector.lastSyncedAt ? formatRelativeTime(connector.lastSyncedAt, { t }) : null
  const status = statusParts(connector, activeOperation, t)
  const tag = readOnly ? t('shared with you') : connector.shared ? t('shared') : null

  // The row itself - its columns, its phone line, its focus ring, hover, drop highlight and the
  // dimming while it is torn down - is the kit's SourceRow; this works out what it says.
  return (
    <KitSourceRow
      ariaLabel={displayName}
      onOpen={() => onOpenDetail?.(connector.id)}
      // Nothing on a source being torn down is actionable any more - the backend has already
      // committed to the delete and every sync/drain path has stopped cooperating with it.
      busy={isDeleting}
      drop={isDropTarget ? drop : undefined}
      dropLabel={t('Drop here')}
      mark={getTypeIcon(connector.type, 16)}
      name={displayName}
      nameLink={destUrl ? cls => (
        <ExternalLink
          href={destUrl}
          stopPropagation
          className={cls}
          labelClassName="truncate"
          iconSize={12}
          title={connector.name}
        >
          {displayName}
        </ExternalLink>
      ) : undefined}
      badges={connector.priority && (
        <Tooltip label={t('Processed first')}>
          <Rocket size={14} className="shrink-0 text-brand-legible" aria-label={t('prioritized')} />
        </Tooltip>
      )}
      tag={tag}
      items={items}
      synced={synced ?? t('Not synced yet')}
      // The column truncates, so the whole of it - each phrase with its detail - is the tooltip.
      // A HEALTHY SOURCE SAYS SO, in words and the caption's ink (the UI review, 2026-10-28: the
      // column was empty for every healthy row) - never the green check on every row it once was.
      status={!state.healthy && status.length > 0 ? (
        <Tooltip label={statusTooltip(status)} fullWidth>
          <MetaList items={statusSpans(status)} className="truncate" />
        </Tooltip>
      ) : state.healthy ? t('Up to date') : undefined}
      healthMark={state.healthy ? undefined : <SourceHealthMark connector={connector} busy={state.processing} graphReading={state.graphReading} size={14} />}
      healthyMark={state.healthy ? <HealthyMark connector={connector} size={14} /> : undefined}
      // Phone: items, last sync and status on ONE line, truncated rather than wrapped.
      phoneMeta={[
        t('{shown} item|{shown} items', { count: connector.itemCount, shown: items }),
        synced,
        ...statusSpans(status),
      ]}
    />
  )
})

export { SourceHealthMark, graphFinished, formatItemCount, extractDisplayName, getTypeIcon, connectorUrl }
