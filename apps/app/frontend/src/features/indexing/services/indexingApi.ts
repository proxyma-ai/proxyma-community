import { http } from '@/services/httpClient'
import type { ConnectorConfig, ConnectorType, CursorPagedResult, GraphPayload, GraphProgress, GraphWindow, PagedResult, ConnectorSummary, TrackedItem } from '../types/indexing.types'
import type { DroppedFolderFingerprint } from '../utils/dirEntryWalk'

export interface ItemsCursor {
  cursorFailed?: string
  cursorTouched?: string
  cursorId?: string
}

export interface FileTypeCatalog {
  // Group key -> extensions, in the order the picker should render them. Keys are matched to
  // display labels in FileTypeFilter; the grouping itself is the backend's.
  groups: Record<string, string[]>
  defaults: string[]
}

// Only the keys a caller actually intends to change are sent - the backend leaves anything
// absent untouched, so a modal editing one field never has to round-trip the others.
export interface ConnectorConfigUpdate {
  // FILES, and SVN / NETWORK_SHARE (recursive) and SHAREPOINT (fileTypes only)
  recursive?: boolean
  // Comma-separated extensions; '' means "every supported type".
  fileTypes?: string
  // A LOCAL folder only: the folder it moved to. Relinks the source and re-indexes it from there.
  path?: string
  // WEB_PAGE
  maxDepth?: number
  maxPages?: number
  sameDomainOnly?: boolean
  renderJs?: boolean
  includePattern?: string
  // Every type that reads through a connection. Moves the source to another of its owner's
  // connections for the same service - the backend refuses any other - and re-syncs it from there.
  connectionId?: string
  // A type built on the connector framework: its descriptor's fields, by key, as text.
  [field: string]: string | number | boolean | undefined
}

// Crawl bounds, served by the backend from the same constants it validates against
// (WebCrawlConnector.MAX_CRAWL_DEPTH / MAX_CRAWL_PAGES). Never hardcode these in a form - the
// register modal, the edit modal and the backend each used to carry their own numbers, and the
// backend silently clamped whatever the forms let through.
export interface WebCrawlLimits {
  maxDepth: number
  maxPages: number
  defaultDepth: number
  defaultPages: number
}

export interface FolderInspection {
  path?: string
  name?: string
  exists: boolean
  isDirectory: boolean
  readable: boolean
  supportedFileCount?: number
  totalBytes?: number
  truncated?: boolean
  // Only sent when a NON-recursive look found nothing: whether a recursive scan would have found
  // anything. Separates "your files are one level down" from "nothing here is of a type you
  // selected", which need opposite things from the user. A yes/no rather than a count so the
  // backend can stop at the first hit instead of walking the tree for a number nobody acts on.
  hasSubdirectoryFiles?: boolean
}

export const indexingApi = {
  getWebCrawlLimits: () =>
    http.get<WebCrawlLimits>('/api/v1/indexing/webpage/limits').then(r => r.data),
  // includeShared surfaces the Connectors page's "show shared connectors from other users" toggle
  // (server mode only - desktop has no auth/other-users concept, so it's always omitted there).
  getSummary: (includeShared?: boolean) =>
    http.get<ConnectorSummary>('/api/v1/indexing/summary', { params: { includeShared: includeShared || undefined } }).then(r => r.data),
  // What the sources take on disk: the index (vectors and the keyword index) and the uploads. The
  // whole store on desktop, the caller's own sources on a server - see ConnectorController.
  getStorage: () =>
    http.get<{ indexBytes: number; uploadsBytes: number; totalBytes: number }>('/api/v1/indexing/storage').then(r => r.data),
  // type="all" (a path sentinel, not a real ConnectorType) lists every connector type at once; the
  // "All" tab asks for it only with a non-empty q, to search titles across every type (without one
  // it shows the summary's capped preview instead - see useConnectorsByType).
  listByType: (type: ConnectorType | 'all', page: number, q?: string, size = 20, includeShared?: boolean) =>
    http.get<PagedResult<ConnectorConfig>>('/api/v1/indexing/by-type/' + type, {
      params: { page, size, q: q || undefined, includeShared: includeShared || undefined },
    }).then(r => r.data),
  syncNow: (id: string) => http.post<{ started: boolean }>(`/api/v1/indexing/${id}/sync`).then(r => r.data),
  removeConnector: (id: string) => http.delete(`/api/v1/indexing/${id}`).then(r => r.data),
  setShared: (id: string, shared: boolean) =>
    http.patch(`/api/v1/indexing/${id}/shared`, { shared }).then(r => r.data),
  setPriority: (id: string, priority: boolean) =>
    http.patch(`/api/v1/indexing/${id}/priority`, { priority }).then(r => r.data),
  listActiveItems: (id: string) =>
    http.get<TrackedItem[]>(`/api/v1/indexing/${id}/items/active`).then(r => r.data),
  listItems: (id: string, cursor: ItemsCursor, size = 50) =>
    http.get<CursorPagedResult<TrackedItem>>(`/api/v1/indexing/${id}/items`, {
      params: {
        cursorFailed: cursor.cursorFailed,
        cursorTouched: cursor.cursorTouched,
        cursorId: cursor.cursorId,
        size,
      },
    }).then(r => r.data),
  // Chunks currently stored in the BM25 index for one document - the original converted markdown
  // isn't durably kept for anything but FILES uploads, so this is the closest thing to a content
  // preview: overlapping chunks, not a seamless reconstruction (see ConnectorService.getDocumentChunks).
  previewDocument: (connectorId: string, documentId: string) =>
    http.get<{ chunks: string[] }>(`/api/v1/indexing/${connectorId}/items/${documentId}/preview`).then(r => r.data),
  // The knowledge graph, at the two scopes the modal offers it. Both are the same shape, so the
  // view component takes one type and does not care which button opened it.
  //
  // The per-item call returns only what THAT file asserted. The per-connector call is the one
  // worth having: entity resolution is global by normalised name, so a thing two files both
  // mention is one node there with each file's edges hanging off it - the cross-file joins that
  // are invisible when you look at either file alone.
  //
  // BOTH ARE PAGED, and the window's three numbers are not interchangeable - `cap` chooses which
  // nodes are in the picture at all (the most connected ones), `offset` and `limit` only choose
  // how that picture is delivered. See KnowledgeGraphService.Window on the backend.
  documentGraph: (connectorId: string, documentId: string, window?: GraphWindow) =>
    http.get<GraphPayload>(`/api/v1/indexing/${connectorId}/items/${documentId}/graph`,
      { params: window }).then(r => r.data),
  connectorGraph: (connectorId: string, window?: GraphWindow) =>
    http.get<GraphPayload>(`/api/v1/indexing/${connectorId}/graph`, { params: window }).then(r => r.data),
  retryFailed: (id: string) =>
    http.post(`/api/v1/indexing/${id}/retry-failed`).then(r => r.data),
  reindex: (id: string) =>
    http.post(`/api/v1/indexing/${id}/reindex`).then(r => r.data),
  // Deployment-wide constants (FilesConnector's own extension groups) - what the file-type
  // pickers offer as ready-made choices, and which of them a new folder starts with.
  getFileTypeCatalog: () =>
    http.get<FileTypeCatalog>('/api/v1/indexing/file-types').then(r => r.data),
  // Desktop only. Turns a dropped folder's name and immediate contents into the absolute path
  // it has on this machine, which the browser itself can never supply - see LocalPathController.
  // Returns undefined whenever the backend is not certain (no match, or more than one), and the
  // caller then opens the native chooser instead. Never throws: a drop that cannot be resolved
  // has a perfectly good fallback, so a failed lookup must not become an error the user sees.
  resolveDroppedFolder: (fingerprint: DroppedFolderFingerprint) =>
    http.post<{ path?: string }>('/api/v1/desktop/resolve-dropped-folder', fingerprint)
      .then(r => r.data.path)
      .catch(() => undefined),
  // Polled by the source's settings dialog while extraction is in flight. Its own call rather
  // than a summary refetch: the only number that moves is this one, once a passage.
  graphProgress: (id: string) =>
    http.get<GraphProgress>(`/api/v1/indexing/${id}/graph/progress`).then(r => r.data),

  // The knowledge-graph opt-in. Separate from updateConfig on purpose: that one re-syncs the whole
  // connector, and flipping the graph opt-in must not re-index a corpus - the graph extracts from
  // what is already indexed, in the background. See ConnectorService.setGraphEnabled.
  // WAS `/api/v1/connectors/{id}/graph`, which is a path NOTHING is mapped at - every attempt to
  // switch the knowledge graph on or off 404'd. ConnectorController is `/api/v1/indexing`, like
  // every other call in this file. Nothing caught it because the mutation's only failure path was
  // a toast, and a feature nobody could turn on produced no other symptom.
  setGraphEnabled: (id: string, graphEnabled: boolean) =>
    http.patch<void>(`/api/v1/indexing/${id}/graph`, { graphEnabled }).then(() => undefined),

  // Post-registration edits to what a connector covers: crawl bounds for a web page, file-type
  // filter and recursion for a folder, a framework type's fields, the connection. Triggers a forced
  // full re-sync server-side, so a narrowed scope actually drops the items it now excludes.
  updateConfig: (id: string, config: ConnectorConfigUpdate) =>
    http.patch(`/api/v1/indexing/${id}/config`, config).then(r => r.data),

  // -- registering a source -----------------------------------------------------------------------
  // The generic form (SourceDialog): every type drawn from its descriptor, through one endpoint.
  register: (payload: Record<string, unknown>) =>
    http.post('/api/v1/indexing/register', payload).then(r => r.data),
  // The two types whose add dialog is their own (ConnectorConfigModal). That endpoint reads a
  // Map<String, String> for a network share, and the crawl's options as they are for a web page.
  registerCustom: (type: 'webpage' | 'networkshare', payload: Record<string, unknown>) =>
    http.post(`/api/v1/indexing/register/${type}`, payload).then(r => r.data),
  // A local folder, by its absolute path on this machine (desktop only - LocalFolderModal).
  registerLocalFolder: (payload: { path: string; recursive: boolean; fileTypes: string }) =>
    http.post('/api/v1/indexing/register/files', payload).then(r => r.data),

  // -- the local machine (desktop only, LocalPathController) --------------------------------------
  // Puts the native folder chooser on screen and answers when it closes. It stays open for as long
  // as the chooser does - there is no timeout - and "cancelled" is a normal 200, with a `reason`
  // when the chooser could not be shown at all.
  browseFolder: (suggestedName?: string) =>
    http.post<{ path?: string; name?: string; cancelled?: boolean; reason?: string }>(
      '/api/v1/desktop/browse-folder', { suggestedName },
    ).then(r => r.data),
  // A read-only look at a folder before it is registered: whether it is there and readable, and how
  // many files the CURRENT settings would pick up. `fileTypes` omitted means every supported type.
  inspectFolder: (params: { path: string; recursive: boolean; fileTypes?: string }) =>
    http.get<FolderInspection>('/api/v1/desktop/inspect-folder', { params }).then(r => r.data),
}
