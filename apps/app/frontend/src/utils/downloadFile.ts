import type { AxiosResponse } from 'axios'

/**
 * A file the server sent, saved where the reader's browser - or the desktop window - saves
 * downloads. A blob URL rather than a data: URI, so a large log is never pushed through a string,
 * and revoked at once: the anchor has been clicked by then (as ConfigTransferPage does it).
 */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** The name the server gave the file (Content-Disposition), or `fallback` when it gave none. */
export function filenameOf(response: AxiosResponse, fallback: string): string {
  const header = String(response.headers['content-disposition'] ?? '')
  const match = header.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i)
  return match ? decodeURIComponent(match[1]) : fallback
}

/** What a log download returns to the page: whether it stopped at the server's row limit. */
export interface LogDownload {
  truncated: boolean
}

/** Saves a log-export response and says whether it was cut short (LogExport on the server). */
export function saveLogExport(response: AxiosResponse<Blob>, fallback: string): LogDownload {
  saveBlob(response.data, filenameOf(response, fallback))
  return { truncated: String(response.headers['x-proxyma-truncated'] ?? '') === 'true' }
}
