import { http } from '@/services/httpClient'

/**
 * Where "Send diagnostics to Proxyma" stands on this installation - DiagnosticsController.preview().
 * `sending` is the whole gate (endpoint configured, not Enterprise Server, switch on); the other flags
 * say which part of it is missing.
 */
export interface DiagnosticsStatus {
  sending: boolean
  switchedOn: boolean
  blockedByEdition: boolean
  blockedByTier: boolean
  configured: boolean
  daysWaiting: number
  today: string
  lastSentAt: string | null
}

export interface DiagnosticsPreview {
  status: DiagnosticsStatus
  /**
   * The report exactly as DiagnosticsReportBuilder builds it for sending - loosely typed on
   * purpose: the page shows it verbatim as JSON and must not reshape it.
   */
  report: { days?: { day: string }[] } & Record<string, unknown>
}

export const diagnosticsApi = {
  preview: () => http.get<DiagnosticsPreview>('/api/v1/diagnostics/preview').then(r => r.data),
  deleteCollected: () => http.delete('/api/v1/diagnostics/collected').then(() => undefined),
}
