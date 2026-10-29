import { http } from '@/services/httpClient'
import { saveLogExport } from '@/utils/downloadFile'
import type { CostSource } from '../utils/callCost'

// ---- the per-call ledger -------------------------------------------------------------------
// One row per model call, and the same window grouped. Written at the gateway since 2026-09-16,
// so what this returns is every call the deployment made - chat, deployed agent, embedding, rerank,
// graph extraction, OCR, guardrail and the rest - rather than the four platform activities the
// older per-call table covered.

export interface LlmCallRow {
  id: string
  /** ISO instant. */
  recordedAt: string
  /** The LlmRole name: CHAT, EMBEDDING, RERANK, GRAPH, OCR, GUARDRAIL ... */
  service: string
  provider: string
  model: string
  subjectKind: 'USER' | 'ANONYMOUS' | 'SYSTEM'
  subjectId: string
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  /**
   * The part of cacheWriteTokens written with a one-hour lifetime; the rest were five-minute.
   * Null on a row written before it was recorded.
   */
  longCacheWriteTokens?: number | null
  costUsd: number
  /**
   * Whether anything priced the call - the provider's reported cost, a rate or a list price. False
   * means the cost shown is $0 for want of one.
   */
  priced: boolean
  /** Where the cost came from (callCost.ts); null on a row written before it was recorded. */
  costSource?: CostSource | null
  /** What the prompt-cache clock made of the call (callCost.ts CacheVerdict); null for none. */
  cacheOutcome?: string | null
  /** Until when what the call read or wrote stays cached (ISO); null with no outcome. */
  cacheWarmUntil?: string | null
}

/** One column of the cost-over-time chart: a calendar day or month in the reader's zone. */
export interface LlmCallBucket {
  /** "YYYY-MM-DD", or "YYYY-MM" when seriesUnit is "month". */
  bucket: string
  calls: number
  costUsd: number
  /**
   * The part of costUsd charged to a user by a service whose cost center is the user - the rest is
   * the platform's. Absent from a server older than the cost-center split.
   */
  userUsd?: number
}

/** One bar of a breakdown: a provider, a provider/model pair, or a service. */
export interface LlmCallGroup {
  key: string
  calls: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  costUsd: number
  /** byModel only: whether that provider/model pair has a rate configured. */
  priced?: boolean
  /** byModel only: the pair apart, for the log's model filter. */
  provider?: string
  model?: string
}

export interface LlmCallSubjectGroup extends Omit<LlmCallGroup, 'key' | 'cacheReadTokens' | 'cacheWriteTokens'> {
  subjectKind: 'USER' | 'ANONYMOUS' | 'SYSTEM'
  subjectId: string
  /** A USER's display name, when the account still exists and the edition has accounts. */
  label?: string
}

export interface LlmCallsPage {
  windowDays: number
  /**
   * What the server decided this viewer may see - never what the client asked for. 'mine' on
   * Enterprise Server for anyone who is not an admin; 'all' on desktop, which has one user.
   */
  scope: 'all' | 'mine'
  items: LlmCallRow[]
  page: number
  size: number
  totalItems: number
  totalPages: number
  byProvider: LlmCallGroup[]
  byModel: LlmCallGroup[]
  byService: LlmCallGroup[]
  /** Every day (or month) of the window, oldest first, empty ones included. */
  series: LlmCallBucket[]
  seriesUnit: 'day' | 'month'
  /** Admins only - absent when the viewer sees only their own calls. */
  bySubject?: LlmCallSubjectGroup[]
  /** The window split by cost center, summed from the series - see LlmCallBucket.userUsd. */
  userUsd?: number
  platformUsd?: number
  /** Admins only: each LlmRole name to its cost center, defaults included. */
  costCenters?: Record<string, CostCenter>
  /** Whether this viewer may clear the log: the local user, or a Super admin on a server. */
  canClear?: boolean
}

/**
 * The SUMMARY half (`include=summary`): the chart and the breakdowns for the period. Asked for once
 * per period - the log's filters do not change it, so filtering does not wait for it.
 */
export type LlmCallsSummary = Omit<LlmCallsPage, 'items' | 'page' | 'size' | 'totalItems' | 'totalPages'>

/** The LOG half (`include=log`): one page of calls under the log's filters, search and sort. */
export type LlmCallsLog = Pick<LlmCallsPage, 'scope' | 'items' | 'page' | 'size' | 'totalItems' | 'totalPages'>

/** Who pays for a kind of model call - see CostCenterSettings on the server. */
export type CostCenter = 'user' | 'platform'

export interface LlmCallsQuery {
  days: number
  page: number
  size: number
  service?: string
  provider?: string
  model?: string
  /** IANA zone the chart's days are cut in - the reader's own. */
  tz?: string
  /** The log's search: service, provider, model or who, case-insensitive. The summary ignores it. */
  q?: string
  /** The log's sort column (LlmCallsController.SORTABLE) and direction - sorted on the server. */
  sort?: string
  dir?: 'asc' | 'desc'
}

function callParams(q: LlmCallsQuery) {
  return {
    days: q.days,
    page: q.page,
    size: q.size,
    // Omitted rather than sent empty: the backend treats a blank as "no filter" too, but a
    // query string full of empty parameters makes every cache key unique for no reason.
    ...(q.service ? { role: q.service } : {}),
    ...(q.provider ? { provider: q.provider } : {}),
    ...(q.model ? { model: q.model } : {}),
    ...(q.tz ? { tz: q.tz } : {}),
    ...(q.q ? { q: q.q } : {}),
    ...(q.sort ? { sort: q.sort, dir: q.dir ?? 'desc' } : {}),
  }
}

export const llmCallsApi = {
  /** Both halves in one answer. */
  page: (q: LlmCallsQuery) =>
    http.get<LlmCallsPage>('/api/v1/usage/calls', { params: callParams(q) }).then(r => r.data),
  summary: (q: Pick<LlmCallsQuery, 'days' | 'tz'>) =>
    http.get<LlmCallsSummary>('/api/v1/usage/calls', {
      params: { days: q.days, ...(q.tz ? { tz: q.tz } : {}), include: 'summary' },
    }).then(r => r.data),
  log: (q: LlmCallsQuery) =>
    http.get<LlmCallsLog>('/api/v1/usage/calls', { params: { ...callParams(q), include: 'log' } }).then(r => r.data),
  /** The log as a file under these filters - every page of it, up to the server's row limit. */
  export: (q: Omit<LlmCallsQuery, 'page' | 'size'>, format: 'csv' | 'json') =>
    http.get<Blob>('/api/v1/usage/calls/export', {
      params: { ...callParams({ ...q, page: 0, size: 0 }), page: undefined, size: undefined, format },
      responseType: 'blob',
    }).then(r => saveLogExport(r, `proxyma-llm-calls.${format}`)),
  /** Deletes every recorded call. Refused unless `canClear` said otherwise. */
  clear: () => http.delete<{ deleted: number }>('/api/v1/usage/calls').then(r => r.data),
}
