import type { AxiosProgressEvent } from 'axios'
import { http } from '@/services/httpClient'

// UPLOADED FILES (FileController) - where the server edition and the home server keep the bytes a
// file source indexes: the folders they go into, the storage they take, the checks made before a
// batch is sent, and the hand-off that registers a folder once its upload has finished. The upload
// dialog and a source's own item rows call these; nothing here decides what the dialog shows.

export interface FileQuota {
  usedBytes: number
  quotaBytes: number
}

/** A picked file whose content is already indexed - shared by somebody else, or the caller's own. */
export interface SharedDuplicateMatch {
  relativePath: string
  matchedConnectorName: string
  ownedByRequester: boolean
}

export interface SharedDuplicateCandidate {
  relativePath: string
  sizeBytes: number
  sha256: string
}

export const filesApi = {
  quota: () => http.get<FileQuota>('/api/v1/files/quota').then(r => r.data),
  folders: () => http.get<string[]>('/api/v1/files/folders').then(r => r.data),
  ensureFolder: (folder: string) => http.post('/api/v1/files/folders/ensure', { folder }).then(r => r.data),
  // Creates the folder's file source and starts its sync - see FileUploadModal.registerFolder.
  registerFolder: (folder: string) => http.post('/api/v1/files/folders/register', { folder }).then(r => r.data),
  // Which of these paths the folder already holds.
  checkDuplicates: (folder: string, relativePaths: string[]) =>
    http.post<string[]>('/api/v1/files/check-duplicates', { folder, relativePaths }).then(r => r.data),
  checkSharedDuplicates: (candidates: SharedDuplicateCandidate[]) =>
    http.post<SharedDuplicateMatch[]>('/api/v1/files/check-shared-duplicates', { candidates }).then(r => r.data),
  // Through `http` (not a raw axios call), so an expired session's 401 takes the shared
  // interceptor's refresh and re-login path like every other request.
  upload: (form: FormData, opts: { signal: AbortSignal; onUploadProgress: (e: AxiosProgressEvent) => void }) =>
    http.post<{ id: string }>('/api/v1/files', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      signal: opts.signal,
      onUploadProgress: opts.onUploadProgress,
    }),
  remove: (fileId: string) => http.delete(`/api/v1/files/${fileId}`).then(r => r.data),
}
