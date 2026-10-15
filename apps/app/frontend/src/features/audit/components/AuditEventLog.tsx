import { Fragment, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  cn, FilterBar, FilterChips, HScroll, InlineNotice, LIST_PAGE_SIZE, Presence, Readout, SearchInput,
  Select, SortTh, TABLE_CLASS, TABLE_FLUSH, TableEmptyRow, TableSkeletonRows, TD_COMPACT, Th,
  THEAD_TR, TR, TR_INTERACTIVE, usePageFor, type SortState,
} from '@/components/ui/kit'
import { ListPager } from '@/components/ui/ListPager'
import { keys } from '@/services/queryKeys'
import { useTranslation, type Translate } from '@/i18n'
import { withReason } from '@/services/apiError'
import { useDebounce } from '@/hooks/useDebounce'
import { toolDisplayName } from '@/features/chat/utils/toolDisplayName'
import {
  auditApi, type AuditEventRow, type AuditEventsQuery, type AuditSummary, type AuditTab,
} from '../services/auditApi'
import {
  ACTIVITY_CATEGORIES, actionLabel, actorText, categoryLabel, durationText, outcomeLabel, outcomeTone,
  SERVER_ONLY_CATEGORIES, summaryText, words,
} from '../utils/auditLabels'
import { argumentLabel, argumentRows } from '@/features/chat/utils/argumentRows'
import { DownloadMenu } from '@/components/ui/DownloadMenu'
import { AuditEventDialog } from './AuditEventDialog'
import { useDates } from '@/hooks/useDates'

/**
 * One tab's entries, newest first - the same table, filters, search and pager as the LLM call log
 * beside it (LlmCallLogTable), so the page's five tabs read as one log.
 *
 * <p>PAGED, twenty entries a page since 2026-10-14 (LIST_PAGE_SIZE, like every list), with the page
 * numbers between Previous and Next (2026-10-12, maintainer's request: a long list somebody jumps
 * around in).
 *
 * <p>Everything is applied by the SERVER (AuditController), because the log is paged: a filter or a
 * sort over the twenty rows in the browser would answer for a slice while looking like the period.
 * The filters' choices come from the tab's summary, which ignores them, so choosing one never hides
 * the others.
 */
export function AuditEventLog({ tab, days, summary, multiUser }: {
  tab: AuditTab
  days: number
  summary: AuditSummary | undefined
  multiUser: boolean
}) {
  const { t } = useTranslation()
  const [filters, setFilters] = useState<EventFilters>(NO_EVENT_FILTERS)
  const [openId, setOpenId] = useState<string | null>(null)
  const log = useAuditEvents(tab, days, filters)

  const filtered = !!(filters.text.trim() || filters.action || filters.outcome || filters.target
    || filters.actor || filters.category)
  const sortBy = (key: EventSortKey) => setFilters({
    ...filters,
    sort: filters.sort.key === key
      ? { key, dir: filters.sort.dir === 'desc' ? 'asc' : 'desc' }
      : { key, dir: key === 'time' || key === 'duration' ? 'desc' : 'asc' },
  })
  const sortTh = (key: EventSortKey, label: string, align?: 'right') => (
    <SortTh compact wrap label={label} sortKey={key} sort={filters.sort} onSort={sortBy} align={align} />
  )

  const columns = COLUMNS[tab].filter(c => c !== 'who' || multiUser)
  const actionOptions = (summary?.byAction ?? [])
    .filter(a => !filters.category || a.category === filters.category)
  const categories = ACTIVITY_CATEGORIES.filter(c => multiUser || !SERVER_ONLY_CATEGORIES.has(c))

  return (
    <>
      {tab === 'ACTIVITY' && (
        <FilterChips
          className="mb-3"
          wrap
          ariaLabel={t('Category')}
          active={filters.category || 'ALL'}
          onChange={key => setFilters({ ...filters, category: key === 'ALL' ? '' : key, action: '' })}
          options={[
            { key: 'ALL', label: t('All') },
            ...categories.map(c => ({ key: c, label: categoryLabel(c, t) })),
          ]}
        />
      )}
      <FilterBar
        className="mb-3"
        search={(
          <SearchInput
            value={filters.text}
            onChange={text => setFilters({ ...filters, text })}
            placeholder={t('Search the log')}
            ariaLabel={t('Search the log')}
            clearLabel={t('Clear search')}
          />
        )}
      >
        {tab === 'TOOL' ? (
          <Select
            className="flex-1 min-w-0 sm:flex-none sm:w-52"
            value={filters.target}
            onChange={target => setFilters({ ...filters, target })}
            ariaLabel={t('Tool')}
            searchable
            searchPlaceholder={t('Search...')}
            emptyLabel={t('No matches')}
            options={[
              { value: '', label: t('All tools') },
              ...(summary?.byTarget ?? []).map(g => ({ value: g.target, label: toolDisplayName(g.target, t) })),
            ]}
          />
        ) : (
          <Select
            className="flex-1 min-w-0 sm:flex-none sm:w-52"
            value={filters.action}
            onChange={action => setFilters({ ...filters, action })}
            ariaLabel={t('Event')}
            options={[
              { value: '', label: t('All events') },
              ...actionOptions.map(a => ({ value: a.action, label: actionLabel(a.action, t) })),
            ]}
          />
        )}
        <Select
          className="flex-1 min-w-0 sm:flex-none sm:w-40"
          value={filters.outcome}
          onChange={outcome => setFilters({ ...filters, outcome })}
          ariaLabel={t('Outcome')}
          options={[
            { value: '', label: t('All outcomes') },
            ...Object.keys(summary?.byOutcome ?? {}).map(o => ({ value: o, label: outcomeLabel(o, t) })),
          ]}
        />
        {multiUser && (
          <Select
            className="flex-1 min-w-0 sm:flex-none sm:w-48"
            value={filters.actor}
            onChange={actor => setFilters({ ...filters, actor })}
            ariaLabel={t('Who')}
            searchable
            searchPlaceholder={t('Search...')}
            emptyLabel={t('No matches')}
            options={[
              { value: '', label: t('Everyone') },
              ...(summary?.byActor ?? []).map(a => ({ value: a.actorId, label: actorText(a.actorKind, a.actorId, a.actorName, t) })),
            ]}
          />
        )}
        <DownloadMenu onDownload={format => auditApi.export(exportQuery(log.query), format)} />
      </FilterBar>

      <HScroll>
        <table className={cn(TABLE_CLASS, TABLE_FLUSH)}>
          <thead>
            <tr className={THEAD_TR}>
              {columns.map(c => {
                switch (c) {
                  case 'when': return <Fragment key={c}>{sortTh('time', t('When'))}</Fragment>
                  case 'tool': return <Fragment key={c}>{sortTh('target', t('Tool'))}</Fragment>
                  case 'check': return <Fragment key={c}>{sortTh('action', t('Check'))}</Fragment>
                  case 'fired': return <Fragment key={c}>{sortTh('target', t('What fired'))}</Fragment>
                  case 'event': return <Fragment key={c}>{sortTh('action', t('Event'))}</Fragment>
                  case 'category': return <Fragment key={c}>{sortTh('category', t('Category'))}</Fragment>
                  case 'who': return <Fragment key={c}>{sortTh('actor', t('Who'))}</Fragment>
                  case 'outcome': return <Fragment key={c}>{sortTh('outcome', t('Outcome'))}</Fragment>
                  case 'duration': return <Fragment key={c}>{sortTh('duration', t('Took'), 'right')}</Fragment>
                  default: return <Th key={c} compact />
                }
              })}
            </tr>
          </thead>
          <tbody className={cn('transition-opacity', log.stale && 'opacity-50')}>
            {log.loading ? (
              <TableSkeletonRows compact rows={5} columns={columns.map(() => 'w-20')} />
            ) : log.items.length === 0 ? (
              <TableEmptyRow colSpan={columns.length}>
                {filtered ? t('No entries in this period match.') : t('Nothing was recorded in this period.')}
              </TableEmptyRow>
            ) : log.items.map(row => (
              <tr
                key={row.id}
                className={cn(TR, TR_INTERACTIVE)}
                tabIndex={0}
                onClick={() => setOpenId(row.id)}
                onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setOpenId(row.id) } }}
              >
                {columns.map(c => <Cell key={c} column={c} row={row} t={t} />)}
              </tr>
            ))}
          </tbody>
        </table>
      </HScroll>

      <Presence show={log.error != null}>
        <InlineNotice tone="danger" className="mt-3">{withReason(t('Could not load the log'), log.error)}</InlineNotice>
      </Presence>
      <ListPager
        page={log.page}
        pages={log.loading ? 0 : log.pages}
        onPage={log.setPage}
        countText={t('1 entry|{count} entries', { count: log.totalItems })}
      />

      {openId && <AuditEventDialog id={openId} multiUser={multiUser} onClose={() => setOpenId(null)} />}
    </>
  )
}

// A row's second line WRAPS, two lines at most, rather than running off the side: one long line
// of arguments or of a setting's name pushed the Outcome column out of a 1280px window.
const SUMMARY = 'block text-ink-caption line-clamp-2 [overflow-wrap:anywhere]'

/**
 * A tool call's arguments as the chat's own tool rows word them - "Query: pump seals; Limit: 5" -
 * rather than as JSON. Recorded arguments are cut at a couple of hundred characters, so one that no
 * longer parses is shown with its punctuation thinned instead.
 */
function argumentsText(summary: string): string {
  const rows = argumentRows(summary)
  if (rows && rows.length > 0) return rows.map(r => `${r.label}: ${r.value.replace(/\s+/g, ' ')}`).join('; ')
  // The chat's rows leave out a server path; here it is already scrubbed to its last segment, so it
  // is shown - with its key in words, like every other argument.
  return summary.replace(/^\{|\}$/g, '').replace(/"([^"]+)":/g, (_, key: string) => `${argumentLabel(key)}: `)
    .replace(/"/g, '').replace(/,(?=\s*[A-Z][a-z ]*: )/g, '; ')
}

/** The log's query, as a download asks for it: every row, so no page and no page size. */
function exportQuery(q: Omit<AuditEventsQuery, 'page'>): Omit<AuditEventsQuery, 'page' | 'size'> {
  const { size: _size, ...rest } = q
  return rest
}

type Column = 'when' | 'tool' | 'check' | 'fired' | 'event' | 'category' | 'who' | 'outcome' | 'duration'

const COLUMNS: Record<AuditTab, Column[]> = {
  TOOL: ['when', 'tool', 'who', 'outcome', 'duration'],
  GUARDRAIL: ['when', 'check', 'outcome', 'fired', 'who', 'duration'],
  DIAGNOSTICS: ['when', 'event', 'outcome'],
  ACTIVITY: ['when', 'category', 'event', 'who', 'outcome'],
}

const TONE_CLASS = {
  success: 'text-brand-legible',
  danger: 'text-danger',
  warning: 'text-warning',
  neutral: 'text-ink-caption',
} as const

function Cell({ column, row, t }: { column: Column; row: AuditEventRow; t: Translate }) {
  const dates = useDates()
  switch (column) {
    case 'when':
      // The row's name on a phone, as in the call log: the date over the time.
      return (
        <td className={cn(TD_COMPACT, 'text-ink-caption whitespace-nowrap data-readout align-top')}>
          <span className="sm:block">{dates.date(row.at)}</span>{' '}
          <span className="sm:block">{dates.time(row.at)}</span>
        </td>
      )
    case 'tool':
      return (
        <td data-label={t('Tool')} className={cn(TD_COMPACT, 'align-top min-w-0')}>
          <span>
            <span className="block text-ink-primary">{row.target ? toolDisplayName(row.target, t) : '-'}</span>
            {row.summary && <span className={SUMMARY}>{argumentsText(row.summary)}</span>}
          </span>
        </td>
      )
    case 'check':
      return <td data-label={t('Check')} className={cn(TD_COMPACT, 'text-ink-secondary align-top whitespace-nowrap')}>{actionLabel(row.action, t)}</td>
    case 'fired':
      return <td data-label={t('What fired')} className={cn(TD_COMPACT, 'text-ink-secondary align-top')}>{row.target ? words(row.target) : '-'}</td>
    case 'event':
      return (
        <td data-label={t('Event')} className={cn(TD_COMPACT, 'align-top min-w-0')}>
          <span>
            <span className="block text-ink-primary">{actionLabel(row.action, t)}</span>
            {summaryText(row, t) && <span className={SUMMARY}>{summaryText(row, t)}</span>}
          </span>
        </td>
      )
    case 'category':
      return <td data-label={t('Category')} className={cn(TD_COMPACT, 'text-ink-secondary align-top whitespace-nowrap')}>{categoryLabel(row.category, t)}</td>
    case 'who':
      return (
        <td data-label={t('Who')} className={cn(TD_COMPACT, 'text-ink-caption align-top truncate max-w-56')}>
          {actorText(row.actorKind, row.actorId, row.actorName, t)}
        </td>
      )
    case 'outcome':
      return (
        <td data-label={t('Outcome')} className={cn(TD_COMPACT, 'align-top whitespace-nowrap', TONE_CLASS[outcomeTone(row.outcome)])}>
          {outcomeLabel(row.outcome, t)}
        </td>
      )
    case 'duration':
      return (
        <td data-label={t('Took')} className={cn(TD_COMPACT, 'text-right align-top')}>
          <Readout tone="caption">{durationText(row.durationMs)}</Readout>
        </td>
      )
  }
}

// -- the query ------------------------------------------------------------------------------------

export type EventSortKey = 'time' | 'category' | 'action' | 'outcome' | 'target' | 'actor' | 'duration'

interface EventFilters {
  text: string
  category: string
  action: string
  outcome: string
  target: string
  actor: string
  sort: SortState<EventSortKey>
}

const NO_EVENT_FILTERS: EventFilters = {
  text: '', category: '', action: '', outcome: '', target: '', actor: '', sort: { key: 'time', dir: 'desc' },
}


function useAuditEvents(tab: AuditTab, days: number, filters: EventFilters) {
  const text = useDebounce(filters.text.trim(), 300)
  const newestFirst = filters.sort.key === 'time' && filters.sort.dir === 'desc'
  const query: Omit<AuditEventsQuery, 'page'> = {
    tab, days, size: LIST_PAGE_SIZE,
    ...(filters.category ? { category: filters.category } : {}),
    ...(filters.action ? { action: filters.action } : {}),
    ...(filters.outcome ? { outcome: filters.outcome } : {}),
    ...(filters.target ? { target: filters.target } : {}),
    ...(filters.actor ? { actor: filters.actor } : {}),
    ...(text ? { q: text } : {}),
    ...(newestFirst ? {} : { sort: filters.sort.key, dir: filters.sort.dir }),
  }
  const { page, setPage } = usePageFor(query)
  const q = useQuery({
    queryKey: keys.auditEvents({ ...query, page }),
    queryFn: () => auditApi.events({ ...query, page }),
    placeholderData: prev => prev,
  })
  return {
    query,
    items: q.data?.items ?? [],
    totalItems: q.data?.totalItems ?? 0,
    loading: q.isLoading,
    stale: q.isPlaceholderData,
    error: q.isError ? q.error : null,
    page,
    pages: q.data?.totalPages ?? 0,
    setPage,
  }
}
