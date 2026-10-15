import { useState, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  llmCallsApi, type LlmCallBucket, type LlmCallGroup, type LlmCallRow, type LlmCallsQuery,
  type LlmCallsSummary, type LlmCallSubjectGroup,
} from '../services/chatCostApi'
import { keys } from '@/services/queryKeys'
import { formatUsd, formatCompactCount } from '@/utils/formatters'
import {
  BarList, Button, Caption, cn, EmptyState, ColumnChart, ConfirmDialog, FieldLabel, FilterBar, HScroll,
  InlineNotice, LIST_PAGE_SIZE, List, ListRow, MetaList, Presence, Readout, SearchInput,
  SectionHeading, Select, Skeleton, SortTh, StatGrid, StatTile, StatusMark, TABLE_CLASS, TABLE_FLUSH,
  TableEmptyRow, TableSkeletonRows, TD_COMPACT, TEXT, Th, THEAD_TR, TR, usePagedList, usePageFor,
  type SortState,
} from '@/components/ui/kit'
import { ListPager } from '@/components/ui/ListPager'
import { useTranslation } from '@/i18n'
import { withReason } from '@/services/apiError'
import { useDebounce } from '@/hooks/useDebounce'
import { useToastStore } from '@/store/toastStore'
import { FEATURES } from '@/config/features'
import { DownloadMenu } from '@/components/ui/DownloadMenu'
import { serviceLabel } from '@/features/audit/utils/auditLabels'
import { PeriodSelect } from '@/features/audit/components/PeriodSelect'
import { useDates } from '@/hooks/useDates'

/**
 * What model calls cost over a period, then every call in it - TWO sections, one period.
 *
 * <p>ONE COMPONENT FOR THREE SURFACES - Settings on desktop, Settings on Enterprise Server, and
 * Administration - because the question is identical and only the SCOPE differs, and the scope is
 * decided by the server rather than by which screen mounted this. `scope` in the response says
 * which answer came back; a user who may see only their own calls is told so rather than being
 * shown an unexplained subset.
 *
 * <p>Restructured 2026-09-24 at the maintainer's request. The breakdowns used to be the FILTER for
 * the table below them; they are a summary now, beside a cost-over-time chart, and the one period
 * dropdown governs the chart, the breakdowns and the log alike. The log is its own "Logs" section
 * and shows cache writes and reads, and a warning on every call whose model has no rate - that
 * call's $0 is a missing rate, not a free call.
 *
 * <p>Rendered as a FRAGMENT of two sections, so the page's SettingsSections (Logs, on Settings and on
 * Administration) rules them off like any two of its own sections.
 *
 * <p>TWO REQUESTS since 2026-10-07 (maintainer's feedback: the table reloaded very slowly after a
 * filter changed): the summary once per period (useLlmCallSummary), and the log on its own as the
 * reader filters, sorts and scrolls (useLlmCallLog) - so a filter waits for one paged query rather
 * than for the chart and every breakdown being worked out again.
 */
export function LlmUsage({ showSubjects = false, intro }: {
  showSubjects?: boolean
  /** Shown under the Usage & Cost heading - the estimates notice, a signpost. */
  intro?: ReactNode
}) {
  const { t } = useTranslation()
  const [days, setDays] = useState(30)
  const [filters, setFilters] = useState<LogFilters>(NO_LOG_FILTERS)
  const { data, isLoading, isError, error } = useLlmCallSummary(days)
  const log = useLlmCallLog(days, filters)

  const total = (data?.byService ?? []).reduce(
    (acc, g) => ({ cost: acc.cost + g.costUsd, calls: acc.calls + g.calls }),
    { cost: 0, calls: 0 },
  )

  // Loading is shaped like what arrives - the figures and the chart, or the log's rows - never a
  // spinner in the middle of the section.
  const body = (content: (d: NonNullable<typeof data>) => ReactNode, skeleton: ReactNode) => {
    if (isLoading) return skeleton
    if (isError || !data) return <InlineNotice tone="danger">{withReason(t('Could not load the call history'), error)}</InlineNotice>
    return content(data)
  }

  return (
    <>
      <section className="space-y-6">
        {/* "Spending", not the tab's own name. The one period picker governs everything below it,
            so it is the heading's action - the same picker, with its name, as every tab of Logs. */}
        <SectionHeading
          flush
          caption={intro}
          action={<PeriodSelect days={days} onChange={setDays} />}
        >
          {t('Spending')}
        </SectionHeading>

        {body(d => (
          <>
            {/* The period's two figures, as the kit's stat tiles. A user who may see only their own
                calls is told so under the count rather than shown an unexplained subset. */}
            <StatGrid columns={4}>
              <StatTile label={t('Cost')} value={formatUsd(total.cost)} />
              <StatTile
                label={t('Calls')}
                value={formatCompactCount(total.calls)}
                sub={d.scope === 'mine' ? t('Your own calls only.') : undefined}
              />
            </StatGrid>

            <CostColumns series={d.series} unit={d.seriesUnit} />

            <div className="grid gap-6 md:grid-cols-3">
              <Breakdown title={t('By provider')} rows={d.byProvider} />
              <Breakdown title={t('By model')} rows={d.byModel} />
              <Breakdown title={t('By service')} rows={d.byService} label={k => serviceLabel(k, t)} />
            </div>

            <Presence show={showSubjects && !!d.bySubject && d.bySubject.length > 0}>
              <div>
                <FieldLabel as="p">{t('By who')}</FieldLabel>
                <SubjectList rows={d.bySubject ?? []} days={days} />
              </div>
            </Presence>
          </>
        ), (
          <div className="space-y-6">
            <StatGrid columns={4}>
              <StatTile label={t('Cost')} value="" loading />
              <StatTile label={t('Calls')} value="" loading />
            </StatGrid>
            <Skeleton className="h-36 w-full" />
          </div>
        ))}
      </section>

      <section>
        <SectionHeading
          caption={t('Each model call in the period, newest first. A column heading sorts by that column.')}
          action={data?.canClear ? <ClearLogButton /> : undefined}
        >
          {t('Logs')}
        </SectionHeading>
        <LlmCallLogTable
          summary={data}
          log={log}
          showSubjects={showSubjects}
          filters={filters}
          onFilters={setFilters}
        />
      </section>
    </>
  )
}

/**
 * The per-call log - a row per model call, newest first, with its pager. Exported for the
 * Administration page, which draws its own summary above it from the SAME response, so the log and
 * the figures over it can never be two different periods.
 *
 * <p>The app's one table style since 2026-09-30 (the kit's DataTable parts): sentence-case
 * headers in caption ink, and on a phone each call stacks into labelled lines. `frameClassName` is
 * the admin page's table card; without one (Settings, where nothing is boxed) the table is the kit's
 * TABLE_FLUSH, so the columns line up with the section heading above.
 *
 * <p>FILTERS, A SEARCH AND SORTABLE COLUMNS since the 0.28.0 feedback - all of them applied by the
 * SERVER (LlmCallsController), because the log is paged: sorting or searching one page of twenty in
 * the browser would sort a slice while looking like it sorted the period. The filters' choices are
 * the summary's own breakdowns, which ignore the filters, so choosing one never hides the others.
 *
 * <p>PAGED, twenty calls a page (LIST_PAGE_SIZE). While a changed filter or page loads, the rows
 * already shown stay, dimmed, so the table neither jumps nor passes them off as the answer.
 */
export function LlmCallLogTable({ summary, log, showSubjects = false, filters, onFilters, frameClassName }: {
  /** The period's summary - the filters' choices and the names of who. Undefined while it loads. */
  summary: LlmCallsSummary | undefined
  log: LlmCallLogState
  showSubjects?: boolean
  filters: LogFilters
  onFilters: (filters: LogFilters) => void
  frameClassName?: string
}) {
  const { t } = useTranslation()
  const dates = useDates()
  // A USER row carries an id; the by-subject breakdown carries its name when it has one.
  const names = new Map((summary?.bySubject ?? []).filter(s => s.label).map(s => [s.subjectId, s.label as string]))
  // The filters as the download asks for them - the same debounced search and order the rows use.
  const logQuery = useLogQuery(filters)
  const columns = showSubjects ? 9 : 8
  const filtered = !!(filters.service || filters.model || filters.text.trim())
  // A column's first click sorts it largest (or newest, or last in the alphabet) first; a second
  // click turns it round.
  const sortBy = (key: LogSortKey) => onFilters({
    ...filters,
    sort: filters.sort.key === key
      ? { key, dir: filters.sort.dir === 'desc' ? 'asc' : 'desc' }
      : { key, dir: 'desc' },
  })
  const sortTh = (key: LogSortKey, label: string, align?: 'right') => (
    <SortTh compact wrap label={label} sortKey={key} sort={filters.sort} onSort={sortBy} align={align} />
  )
  return (
    <>
      <FilterBar
        className="mb-3"
        search={(
          <SearchInput
            value={filters.text}
            onChange={text => onFilters({ ...filters, text })}
            placeholder={showSubjects ? t('Search service, model, provider or who') : t('Search service, model or provider')}
            ariaLabel={t('Search the log')}
            clearLabel={t('Clear search')}
          />
        )}
      >
        <Select
          className="flex-1 min-w-0 sm:flex-none sm:w-40"
          value={filters.service}
          onChange={service => onFilters({ ...filters, service })}
          ariaLabel={t('Service')}
          options={[
            { value: '', label: t('All services') },
            ...(summary?.byService ?? []).map(g => ({ value: g.key, label: serviceLabel(g.key, t) })),
          ]}
        />
        <Select
          className="flex-1 min-w-0 sm:flex-none sm:w-56"
          value={filters.model}
          onChange={model => onFilters({ ...filters, model })}
          ariaLabel={t('Model')}
          searchable
          searchPlaceholder={t('Search...')}
          emptyLabel={t('No matches')}
          options={[
            { value: '', label: t('All models') },
            ...(summary?.byModel ?? [])
              .filter(g => g.provider != null && g.model != null)
              .map(g => ({ value: modelFilterValue(g.provider!, g.model!), label: g.key })),
          ]}
        />
        <DownloadMenu
          onDownload={format => llmCallsApi.export({ days: summary?.windowDays ?? 30, tz: readerZone(), ...logQuery }, format)}
        />
      </FilterBar>
      {/* On a phone the table stacks and its headings are not drawn, so the sort the headings
          offer is a menu there instead. */}
      <Select
        className="mb-3 sm:hidden"
        value={`${filters.sort.key}:${filters.sort.dir}`}
        onChange={v => {
          const [key, dir] = v.split(':') as [LogSortKey, 'asc' | 'desc']
          onFilters({ ...filters, sort: { key, dir } })
        }}
        ariaLabel={t('Sort by')}
        options={[
          { value: 'time:desc', label: t('Newest first') },
          { value: 'time:asc', label: t('Oldest first') },
          { value: 'cost:desc', label: t('Highest cost first') },
          { value: 'in:desc', label: t('Most input tokens first') },
          { value: 'out:desc', label: t('Most output tokens first') },
          { value: 'service:asc', label: t('By service') },
          { value: 'model:asc', label: t('By model') },
        ]}
      />
      {/* A table needs its own horizontal scroll, and inside a rounded box that clips it is
          not optional - see .claude/rules/frontend.md. */}
      <div className={frameClassName}>
        <HScroll>
          {/* The kit's COMPACT density - 13px, `px-3 py-2` - NOT the tables' text-sm: this is a LOG, a
              row per model call, and the densest thing on the panel must not also be the loudest. */}
          <table
            className={cn(
              TABLE_CLASS,
              !frameClassName && TABLE_FLUSH,
            )}
          >
            <thead>
              {/* Headers WRAP, since 2026-09-30: with nine columns of one-line headings, "Cache
                  write" and "Cache hit" alone were wider than their numbers and Settings' panel cut
                  Cost off at the right edge. A wrapped heading sits on the bottom line, over its
                  column, aligned the way the column is. */}
              <tr className={THEAD_TR}>
                {sortTh('time', t('When'))}
                {sortTh('service', t('Service'))}
                {sortTh('model', t('Model'))}
                {showSubjects && <Th compact wrap>{t('Who')}</Th>}
                {sortTh('in', t('In'), 'right')}
                {sortTh('out', t('Out'), 'right')}
                {sortTh('cacheWrite', t('Cache write'), 'right')}
                {sortTh('cacheRead', t('Cache hit'), 'right')}
                {sortTh('cost', t('Cost'), 'right')}
              </tr>
            </thead>
            <tbody className={cn('transition-opacity', log.stale && 'opacity-50')}>
              {log.loading ? (
                <TableSkeletonRows compact rows={5} columns={['w-20', 'w-16', 'w-28', 'w-10', 'w-10', 'w-12', 'w-12', 'w-12']} />
              ) : log.items.length === 0 ? (
                <TableEmptyRow colSpan={columns}>
                  {filtered ? t('No calls in this period match.') : t('No calls in this period.')}
                </TableEmptyRow>
              ) : log.items.map(row => (
                <tr key={row.id} className={TR}>
                  {/* The row's name on a phone: the moment the call was made. The date over the
                      time, like the model over its provider - on one line it was the widest
                      column, and in Settings' narrower panel it pushed Cost out of view. One line again
                      on a phone, where it heads the stacked row. */}
                  <td className={cn(TD_COMPACT, 'text-ink-caption whitespace-nowrap data-readout')}>
                    <span className="sm:block">{dates.date(row.recordedAt)}</span>{' '}
                    <span className="sm:block">{dates.time(row.recordedAt)}</span>
                  </td>
                  <td data-label={t('Service')} className={cn(TD_COMPACT, 'text-ink-secondary')}>{serviceLabel(row.service, t)}</td>
                  {/* The model over its provider, each on one line: side by side they made this the
                      widest column and pushed Cost out of view, and left to wrap they broke a model
                      name mid-word. ONE child, because the stacked phone row lays a labelled cell
                      out as label and value, and two would be split apart. */}
                  <td data-label={t('Model')} className={cn(TD_COMPACT, 'text-ink-secondary whitespace-nowrap')}>
                    <span>
                      <span className="block text-ink-primary">{row.model}</span>
                      <span className="block text-ink-caption">{row.provider}</span>
                    </span>
                  </td>
                  {showSubjects && (
                    <td data-label={t('Who')} className={cn(TD_COMPACT, 'text-ink-caption truncate max-w-56')}>
                      {names.get(row.subjectId) ?? subjectLabel(row.subjectKind, row.subjectId, t)}
                    </td>
                  )}
                  <td data-label={t('In')} className={cn(TD_COMPACT, 'text-right data-readout text-ink-secondary')}>{formatCompactCount(row.inputTokens)}</td>
                  <td data-label={t('Out')} className={cn(TD_COMPACT, 'text-right data-readout text-ink-secondary')}>{formatCompactCount(row.outputTokens)}</td>
                  <td data-label={t('Cache write')} className={cn(TD_COMPACT, 'text-right data-readout text-ink-secondary')}>{formatCompactCount(row.cacheWriteTokens)}</td>
                  <td data-label={t('Cache hit')} className={cn(TD_COMPACT, 'text-right data-readout text-ink-secondary')}>{formatCompactCount(row.cacheReadTokens)}</td>
                  <td data-label={t('Cost')} className={cn(TD_COMPACT, 'text-right data-readout text-ink-primary whitespace-nowrap')}>
                    <span className="inline-flex items-center justify-end gap-1.5">
                      {!row.priced && <UnpricedMark />}
                      {formatUsd(row.costUsd)}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </HScroll>
      </div>

      <Presence show={log.error != null}>
        <InlineNotice tone="danger" className="mt-3">{withReason(t('Could not load the call history'), log.error)}</InlineNotice>
      </Presence>
      {/* PAGED, with the page numbers between Previous and Next (2026-10-12, maintainer's request:
          a long list somebody jumps around in). Twenty calls a page since 2026-10-14, like every
          list (LIST_PAGE_SIZE); the count at the left, and no pager while one page holds them all. */}
      <ListPager
        page={log.page}
        pages={log.loading ? 0 : log.pages}
        onPage={log.setPage}
        countText={t('{count} call|{count} calls', { count: log.totalItems })}
      />
    </>
  )
}

/** The log as the table reads it - see useLlmCallLog. */
export interface LlmCallLogState {
  items: LlmCallRow[]
  totalItems: number
  /** The first page of these filters has not arrived and nothing is on screen yet. */
  loading: boolean
  /** The rows shown are an earlier filter's or page's, kept while this one loads. */
  stale: boolean
  error: unknown
  /** 0-based. */
  page: number
  pages: number
  setPage: (page: number) => void
}


/** The period's chart and breakdowns - the summary half, asked for once per period. */
export function useLlmCallSummary(days: number) {
  const query = { days, tz: readerZone() }
  return useQuery({
    queryKey: keys.llmCallsSummary(query),
    queryFn: () => llmCallsApi.summary(query),
    // Changing the period keeps the old figures on screen rather than blanking the section.
    placeholderData: prev => prev,
  })
}

/**
 * The log half: one page of the calls under these filters - twenty a page (LIST_PAGE_SIZE), the
 * page the reader picked, the first again whenever the filters or the period change. The summary is NOT asked again
 * for any of it - see LlmCallsController's `include`.
 */
export function useLlmCallLog(days: number, filters: LogFilters): LlmCallLogState {
  const logQuery = useLogQuery(filters)
  const query: Omit<LlmCallsQuery, 'page'> = { days, size: LIST_PAGE_SIZE, tz: readerZone(), ...logQuery }
  const { page, setPage } = usePageFor(query)
  const q = useQuery({
    queryKey: keys.llmCallsLog({ ...query, page }),
    queryFn: () => llmCallsApi.log({ ...query, page }),
    placeholderData: prev => prev,
  })
  return {
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

/**
 * Clears the log, after saying what that does (2026-10-07 feedback: "there is no way to clear/reset
 * the logs"). Shown only where the server said this viewer may (`canClear`): the local user, or a
 * Super admin on Enterprise Server. The spending limit counts these calls, so the confirmation says
 * that clearing starts the month's count again.
 */
export function ClearLogButton() {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const [confirming, setConfirming] = useState(false)
  const clear = useMutation({
    mutationFn: llmCallsApi.clear,
    onSuccess: () => {
      setConfirming(false)
      qc.invalidateQueries({ queryKey: ['llm-calls'] })
      qc.invalidateQueries({ queryKey: ['usages'] })
      addToast(t('Call log cleared'), 'success')
    },
    onError: (e: unknown) => addToast(withReason(t('Could not clear the call log'), e), 'danger'),
  })
  return (
    <>
      <Button variant="danger-quiet" onClick={() => setConfirming(true)}>{t('Clear log')}</Button>
      {confirming && (
        <ConfirmDialog
          title={t('Clear the call log?')}
          description={t('Every model call recorded so far is deleted, in every period.')}
          // On Enterprise Server there are two limits and only the deployment's counts this log -
          // each user's quota counts its own events (UserSpendAdapter) and is not reset.
          consequences={[
            t('The costs, charts and breakdowns on this page start again from nothing.'),
            FEATURES.auth
              ? t("The deployment's monthly limit counts these calls, so this month is counted again from zero. Each user's own quota is not reset.")
              : t('The monthly spending limit counts these calls, so this month is counted again from zero.'),
          ]}
          confirmLabel={t('Clear log')}
          cancelLabel={t('Cancel')}
          pendingLabel={t('Clearing...')}
          pending={clear.isPending}
          onConfirm={() => clear.mutate()}
          onCancel={() => setConfirming(false)}
        />
      )}
    </>
  )
}

/** The log's sortable columns, by the names LlmCallsController.SORTABLE accepts. */
export type LogSortKey = 'time' | 'service' | 'model' | 'in' | 'out' | 'cacheWrite' | 'cacheRead' | 'cost'

/** What the reader has narrowed and ordered the log by. `model` is a provider and model together. */
export interface LogFilters {
  service: string
  model: string
  text: string
  sort: SortState<LogSortKey>
}

export const NO_LOG_FILTERS: LogFilters = { service: '', model: '', text: '', sort: { key: 'time', dir: 'desc' } }

// A provider and a model in one Select value. The unit separator cannot appear in either name.
const PAIR = '\u001f'
function modelFilterValue(provider: string, model: string): string {
  return `${provider}${PAIR}${model}`
}

/**
 * The log's filters as the query's parameters: the search debounced so a word is one request
 * rather than one per keystroke, and the default order (newest first) left unsaid.
 */
export function useLogQuery(filters: LogFilters): Pick<LlmCallsQuery, 'service' | 'provider' | 'model' | 'q' | 'sort' | 'dir'> {
  const text = useDebounce(filters.text.trim(), 300)
  const [provider, model] = filters.model ? filters.model.split(PAIR) : [undefined, undefined]
  const newestFirst = filters.sort.key === 'time' && filters.sort.dir === 'desc'
  return {
    ...(filters.service ? { service: filters.service } : {}),
    ...(provider && model ? { provider, model } : {}),
    ...(text ? { q: text } : {}),
    ...(newestFirst ? {} : { sort: filters.sort.key, dir: filters.sort.dir }),
  }
}

// Said once, above the figures, in both editions. Every cost here is the configured per-model rate
// multiplied by token counts Proxyma measured itself - an estimate of what a provider will charge,
// not a reading of what it did. Presented without saying so, it invites reconciling against an
// invoice and concluding the product is wrong, when the two were never the same measurement.
export function CostEstimateNote() {
  const { t } = useTranslation()
  return (
    <Caption className="max-w-2xl">
      <span className="font-semibold text-ink-secondary">{t('These costs are estimates.')}</span>{' '}
      {t('Calculated from configured rates and counted tokens, not from provider billing. Check the provider dashboard for actual charges.')}
    </Caption>
  )
}

// The reader's IANA zone, so a call at 03:00 lands on the day the reader lived it. Undefined when
// the runtime cannot say, and the server then cuts days in UTC.
export function readerZone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined
  } catch {
    return undefined
  }
}

export function UnpricedMark() {
  const { t } = useTranslation()
  return <StatusMark state="warning" size={14} label={t('No rate set for this model, so it counts as $0.')} />
}

// Cost per day (per month over a year) as the kit's ColumnChart. One series, so no legend: the
// section heading names it. The fill is the brand ACCENT, the decorative step, and every figure a
// reader takes away is text in ink tokens - the hover readout above the plot and the axis labels.
//
// The readout replaces a floating tooltip: it sits in a fixed place, never clips at the panel's
// edge, and with nothing hovered it states the peak, so the chart says something before it is
// touched. Each column's hit target is the full plot height, not just its bar, so a zero day can
// be pointed at too.
function CostColumns({ series, unit }: { series: LlmCallBucket[]; unit: 'day' | 'month' }) {
  const { t, locale } = useTranslation()
  const [hover, setHover] = useState<number | null>(null)
  if (series.length === 0) return null

  const max = Math.max(...series.map(b => b.costUsd), 0)
  // NOTHING TO PLOT IS A SENTENCE, not an empty chart - as on Administration's Usage & cost: a
  // period with no priced call drew thirty empty columns under "No cost in this period."
  if (max <= 0) {
    return (
      <EmptyState>
        {unit === 'month'
          ? t('Model calls with a price appear here as cost per month. Rates are set per provider in Settings > AI Providers.')
          : t('Model calls with a price appear here as cost per day. Rates are set per provider in Settings > AI Providers.')}
      </EmptyState>
    )
  }
  const peakIndex = series.reduce((best, b, i) => (b.costUsd > series[best].costUsd ? i : best), 0)
  const label = (b: LlmCallBucket) => bucketLabel(b.bucket, unit, locale)
  // Bounds-checked: the previous period's chart stays on screen while a shorter one loads
  // (placeholderData), so a column hovered then can be past the end of the series that arrives.
  const shown = (hover != null ? series[hover] : undefined) ?? series[peakIndex]

  return (
    <ColumnChart
      values={series.map(b => ({ key: b.bucket, value: b.costUsd }))}
      active={hover}
      onActive={setHover}
      ariaLabel={unit === 'month' ? t('Cost per month') : t('Cost per day')}
      readout={(
        <MetaList items={[
          hover == null && max > 0 && t('Peak'),
          <span className="text-ink-secondary">{label(shown)}</span>,
          <span className="text-ink-primary">{formatUsd(shown.costUsd)}</span>,
          t('{count} call|{count} calls', { count: shown.calls }),
        ]} />
      )}
      labels={series.map(b => bucketAxis(b.bucket, unit, locale).label)}
      groups={series.map(b => bucketAxis(b.bucket, unit, locale).group)}
    />
  )
}

// "2026-09-24" -> "Sep 24", "2026-09" -> "Sep 2026", in the READER's language (`locale`), not the
// browser's. Parsed as UTC noon and printed in UTC: the bucket is already a calendar date in the
// reader's zone, so shifting it again could roll it back.
export function bucketLabel(bucket: string, unit: 'day' | 'month', locale?: string): string {
  const [y, m, d] = bucket.split('-').map(Number)
  if (!y || !m) return bucket
  const date = new Date(Date.UTC(y, m - 1, d || 1, 12))
  return unit === 'month'
    ? date.toLocaleDateString(locale, { month: 'short', year: 'numeric', timeZone: 'UTC' })
    : date.toLocaleDateString(locale, { month: 'short', day: 'numeric', timeZone: 'UTC' })
}

// One column's axis label and the group it belongs to: "24" in "Sep" for a day, "Sep" in "2026" for
// a month. The chart writes the label under every column it has room for and the group once where
// it starts, so a 30-day chart reads 6 7 8 ... 30 1 2 over "Sep ... Oct". Printed in UTC for the
// same reason as bucketLabel.
export function bucketAxis(bucket: string, unit: 'day' | 'month', locale?: string): { label: string; group: string } {
  const [y, m, d] = bucket.split('-').map(Number)
  if (!y || !m) return { label: bucket, group: '' }
  const date = new Date(Date.UTC(y, m - 1, d || 1, 12))
  return unit === 'month'
    ? { label: date.toLocaleDateString(locale, { month: 'short', timeZone: 'UTC' }), group: String(y) }
    : { label: String(d), group: date.toLocaleDateString(locale, { month: 'short', timeZone: 'UTC' }) }
}

// A summary now, not a filter: the kit's BarList under the breakdown's name - a bar per row scaled to
// the largest, the cost beside it. The model breakdown carries the same unpriced warning as the log.
// An empty breakdown says so in a sentence; it was a lone "-".
export function Breakdown({ title, rows, label = k => k }: {
  title: string
  rows: LlmCallGroup[]
  /** A row's name in words - a service's, say - where the key is a code. */
  label?: (key: string) => string
}) {
  const { t } = useTranslation()
  return (
    <div>
      <FieldLabel as="p">{title}</FieldLabel>
      <BarList
        label={title}
        share={false}
        emptyText={t('No calls in this period.')}
        rows={rows.slice(0, 6).map(r => ({
          key: r.key,
          label: label(r.key),
          value: r.costUsd,
          figures: (
            <span className="inline-flex items-center gap-1.5">
              {r.priced === false && <UnpricedMark />}
              {formatUsd(r.costUsd)}
            </span>
          ),
        }))}
      />
    </div>
  )
}

// SYSTEM is not a user and must not read as one: it is ingest, scheduled work and anything with no
// person behind it. Naming it "System" rather than showing the raw marker is the difference between
// a reader concluding "somebody called system spent this" and "the machine spent this".
/**
 * "By who": what each person (or service) spent in the period, most first as the server sends it.
 * One row per user on Enterprise Server, so it grows with the deployment - twenty a page, in the
 * browser, since the summary arrives whole; a new period starts again at the first page.
 */
function SubjectList({ rows, days }: { rows: LlmCallSubjectGroup[]; days: number }) {
  const { t } = useTranslation()
  const { page, pages, setPage, pageItems } = usePagedList(rows, days)
  return (
    <>
      <List>
        {pageItems.map(s => (
          <ListRow
            key={`${s.subjectKind}:${s.subjectId}`}
            className="py-1.5"
            title={<span className="font-normal text-ink-secondary">{s.label ?? subjectLabel(s.subjectKind, s.subjectId, t)}</span>}
            trailing={(
              <Readout className={cn(TEXT.option, 'text-ink-primary')}>
                <MetaList items={[formatUsd(s.costUsd), formatCompactCount(s.calls)]} />
              </Readout>
            )}
          />
        ))}
      </List>
      <ListPager page={page} pages={pages} onPage={setPage} />
    </>
  )
}

export function subjectLabel(kind: string, id: string, t: (s: string) => string) {
  if (kind === 'SYSTEM') return t('System')
  if (kind === 'ANONYMOUS') return t('Signed-out visitors')
  return id
}
