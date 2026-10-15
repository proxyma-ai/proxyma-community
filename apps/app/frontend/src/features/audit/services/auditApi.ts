import { http } from '@/services/httpClient'
import { saveLogExport } from '@/utils/downloadFile'

/**
 * The Logs page's endpoints - AuditController. The LLM call log beside them is
 * `/api/v1/usage/calls` (chatCostApi).
 */

/** A tab of the page, as the server groups its categories. */
export type AuditTab = 'TOOL' | 'GUARDRAIL' | 'DIAGNOSTICS' | 'ACTIVITY'

export interface AuditEventRow {
  id: string
  at: string
  category: string
  action: string
  outcome: string
  actorKind: 'USER' | 'ANONYMOUS' | 'SYSTEM' | string
  actorId: string
  actorName: string | null
  target: string | null
  conversationId: string | null
  summary: string | null
  durationMs: number | null
}

export interface AuditEventDetail extends AuditEventRow {
  /** Labelled values the entry was recorded with - already scrubbed of server paths. */
  detail: Record<string, unknown> | null
}

export interface AuditEventPage {
  windowDays: number
  items: AuditEventRow[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface AuditLatency {
  samples: number
  p50?: number
  p95?: number
  max?: number
}

export interface AuditSummary {
  windowDays: number
  multiUser: boolean
  canClear: boolean
  total: number
  byOutcome: Record<string, number>
  byAction: { category: string; action: string; count: number }[]
  byTarget: { target: string; count: number; failed: number; avgMs: number }[]
  byActor?: { actorKind: string; actorId: string; actorName: string | null; count: number }[]
  latency: AuditLatency
}

export interface AuditEventsQuery {
  tab: AuditTab
  days: number
  page?: number
  size?: number
  category?: string
  action?: string
  outcome?: string
  target?: string
  actor?: string
  q?: string
  sort?: string
  dir?: 'asc' | 'desc'
}

export const auditApi = {
  events: (q: AuditEventsQuery) =>
    http.get<AuditEventPage>('/api/v1/audit/events', { params: q }).then(r => r.data),
  event: (id: string) =>
    http.get<AuditEventDetail>(`/api/v1/audit/events/${encodeURIComponent(id)}`).then(r => r.data),
  summary: (tab: AuditTab, days: number) =>
    http.get<AuditSummary>('/api/v1/audit/summary', { params: { tab, days } }).then(r => r.data),
  /** The tab as a file under these filters - every page of it, up to the server's row limit. */
  export: (q: Omit<AuditEventsQuery, 'page' | 'size'>, format: 'csv' | 'json') =>
    http.get<Blob>('/api/v1/audit/export', { params: { ...q, format }, responseType: 'blob' })
      .then(r => saveLogExport(r, `proxyma-audit-${q.tab.toLowerCase()}.${format}`)),
  clear: (tab: AuditTab) =>
    http.delete<{ deleted: number }>('/api/v1/audit/events', { params: { tab } }).then(r => r.data),
}
