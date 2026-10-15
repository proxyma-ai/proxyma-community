import { http } from '@/services/httpClient'

// One exported configuration. Only the fields this UI actually reads are typed - the file itself
// carries more (every settings prefix, the sealed envelope) and is round-tripped as an opaque
// object, because the frontend has no business interpreting a credential envelope it cannot open.
export interface ConfigExportFile {
  proxyma: {
    format: number
    exportedAt: string
    appVersion: string
    mode: 'desktop' | 'server'
    hasSecrets: boolean
  }
  // Everything else, passed straight back to the server on import.
  [key: string]: unknown
}

export type ImportOutcome = 'CREATED' | 'UPDATED' | 'UNCHANGED' | 'SKIPPED'

// The groups a person picks from - TransferGroups on the backend, named after the Settings tabs.
// The ids are the wire format; the labels are the page's.
export type TransferGroup =
  | 'providers' | 'agents' | 'skills' | 'workflows' | 'retrieval' | 'documents' | 'connectors'
  | 'network' | 'usage' | 'guardrails' | 'retention' | 'updates' | 'privacy' | 'administration'
  | 'other'

export interface ImportEntry {
  section: string
  group: TransferGroup
  item: string
  outcome: ImportOutcome
  // Why, for a SKIPPED row - and a note for some of the others. Never carries a secret's value:
  // the report is rendered on screen and travels back over the wire.
  detail: string | null
  // Takes effect only after a restart once written - said in the preview, before applying.
  requiresRestart: boolean
}

export interface ImportReport {
  entries: ImportEntry[]
  // Every group the FILE holds something for, whatever was chosen - what the import offers.
  groups: TransferGroup[]
  // A preview: applying it will need a restart. An apply: it did write such a setting (today: the
  // proxy settings on the Network tab).
  requiresRestart: boolean
  // False when the file carried secrets that were NOT applied - exported without a passphrase, or
  // none supplied. Said out loud rather than letting the reader assume a credential came across.
  secretsApplied: boolean
  created: number
  updated: number
  unchanged: number
  skipped: number
}

// Its own top-level path rather than under /api/v1/settings/ - that prefix hosts the generic
// /{prefix}/{field} route, which structurally overlaps any two-segment path beneath it. See
// ConfigTransferController.
//
// Export is a POST despite being a read: the passphrase travels in the body so it never reaches an
// access log, a proxy log or the browser's history.
export const configTransferApi = {
  // `groups`: what to include. Null means everything - the file, or the deployment, has.
  exportConfig: (passphrase: string | null, groups: TransferGroup[] | null) =>
    http.post<ConfigExportFile>('/api/v1/config-transfer/export', { passphrase, groups }).then(r => r.data),
  previewImport: (file: ConfigExportFile, passphrase: string | null, groups: TransferGroup[] | null) =>
    http.post<ImportReport>('/api/v1/config-transfer/import/preview', { file, passphrase, groups })
      .then(r => r.data),
  applyImport: (file: ConfigExportFile, passphrase: string | null, groups: TransferGroup[] | null) =>
    http.post<ImportReport>('/api/v1/config-transfer/import', { file, passphrase, groups }).then(r => r.data),
}

// Shortest passphrase the backend will seal under - mirrors SecretEnvelope.MIN_PASSPHRASE_CHARS so
// the form can say so before a round trip. The backend enforces it regardless; this is only the
// courtesy of not making someone submit to find out.
export const MIN_PASSPHRASE_CHARS = 12
