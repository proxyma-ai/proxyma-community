import { http } from '@/services/httpClient'

/**
 * The updater's own status. Everything here is READ from the backend rather than inferred: which
 * version is running, whether this platform can install over itself, and when it last looked.
 */
export interface UpdateStatus {
  /**
   * UP_TO_DATE | CHECKING | AVAILABLE | DOWNLOADING | READY | ERROR - see UpdateService.
   */
  status: string
  availableVersion: string
  currentVersion: string
  /** ISO instant, or "" when this process has not checked yet. */
  lastCheckAt: string
  /** One line, or "" - never a stack trace; the backend deliberately sends only the message. */
  lastError: string
  /**
   * False where an update can be downloaded but not applied - anything the desktop launcher did
   * not start. The page reads
   * this instead of sniffing the platform, so the reason can be SHOWN rather than a button
   * silently missing.
   */
  canApplyInPlace: boolean
  /**
   * PARTS | DETECT_ONLY - how this installation applies an update: a desktop install downloads the
   * parts that changed and restarts into them; anything else only reports the new version.
   */
  installKind: string
  /** Bytes of the update written so far. Meaningful while status is DOWNLOADING. */
  downloadedBytes: number
  /** The size the server announced, or 0 when it did not say - the page then shows no percentage. */
  downloadTotalBytes: number
  /** PREPARING (reading and verifying the release) | TRANSFERRING - what a DOWNLOADING status is doing. */
  downloadStage: string
}

export interface UpdateHistoryEntry {
  at: string
  /** CHECKED | FOUND | DOWNLOADED | APPLIED | FAILED | CANCELLED */
  event: string
  fromVersion: string
  toVersion: string
  detail: string
}

export const updateApi = {
  status: () =>
    http.get<UpdateStatus>('/api/v1/update/status').then(r => r.data),

  /** A check the user asked for. Ignores the configured frequency; the backend never throws. */
  check: () =>
    http.post<void>('/api/v1/update/check').then(r => r.data),

  /**
   * Start downloading what the last check found. Returns once the download has STARTED; progress and
   * the outcome arrive through `status`.
   */
  download: () =>
    http.post<void>('/api/v1/update/download').then(r => r.data),

  /** Stop the running download. 409 when none is running - a late click, not a failure. */
  cancel: () =>
    http.post<void>('/api/v1/update/cancel', undefined, { validateStatus: s => s === 200 || s === 409 })
      .then(r => r.data),

  clearHistory: () =>
    http.delete<void>('/api/v1/update/history').then(r => r.data),

  /**
   * Restart into the staged version.
   *
   * The request may never be answered, and that is correct rather than a failure: the process
   * exits ~200ms after accepting it, so the connection can drop before the response is written.
   * The caller treats a network error here as success-in-progress and shows the restarting panel.
   */
  apply: () =>
    http.post<void>('/api/v1/update/apply').then(r => r.data),

  history: () =>
    http.get<UpdateHistoryEntry[]>('/api/v1/update/history').then(r => r.data),
}
