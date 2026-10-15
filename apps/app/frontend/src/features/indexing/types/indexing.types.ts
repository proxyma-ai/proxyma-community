// A source type's wire name - files, webpage, jira, github, ... A string rather than a union: the
// types are the connector catalog's (useConnectorCatalog), and a connector added on the server
// arrives without this bundle changing. The few types with a dialog of their own are named where
// that dialog is chosen (SourcesPage).
export type ConnectorType = string

export interface ConnectorConfig {
  id: string
  type: ConnectorType
  name: string
  status: 'ACTIVE' | 'ERROR' | 'PAUSED'
  lastSyncError?: string
  itemCount: number
  processedCount: number
  failedCount: number
  shared: boolean
  // Whether sharing is offered for this type at all - from ConnectorType.isShareable() on the
  // server. False for the types read as their owner (network shares, SharePoint, Teams), where
  // sharing would hand others what the owner's own access keeps from them.
  shareable?: boolean
  // Single holder across all connectors - prioritizing one takes the flag from the previous
  // holder. While set, this connector gets all indexing throughput first.
  priority: boolean
  // false only when surfaced via the "show shared connectors from other users" toggle (server
  // mode) - always true in desktop mode, where there's no other-users concept.
  ownedByMe: boolean
  allItemsFetched: boolean
  // Whether a sync pass or a drain is running for this source right now - including a pass that
  // has started and is waiting for its type's lock. SyncOrchestratorService.isSyncing(), sent by
  // ConnectorController.toConnectorMap. In-memory on the server, so false after a restart.
  syncing?: boolean
  lastSyncedAt?: string
  // Connector-specific settings blob. Deliberately null for 'files' - there it IS the absolute
  // filesystem path, which never reaches a client; the two fields below carry that connector's
  // editable scope instead. For 'webpage' it's the newline-separated key=value crawl config.
  config?: string
  // A type built on the connector framework: its fields as saved (secrets never), which the
  // settings dialog edits through the type's descriptor. Absent for the older types.
  fields?: Record<string, string>
  // FILES, and the remote file-based types (svn, networkshare, sharepoint). recursive mirrors the
  // scan-subdirectories toggle (absent for sharepoint, which has none); fileTypes is the
  // comma-separated extension filter, null/absent meaning "every supported type". Both are
  // editable after registration via indexingApi.updateConfig.
  recursive?: boolean
  fileTypes?: string | null

  // The connection this source reads through (Jira, Confluence, Bitbucket, SVN and the Microsoft
  // 365 types), and its name. Both null on a source somebody else owns: to anybody but its owner a
  // source lends no connection, and naming the owner's would describe an account not theirs to use.
  connectionId?: string | null
  connectionName?: string | null
  // The server the source is actually reached on, resolved through its connection. Null for the
  // types with no server URL.
  serverUrl?: string | null
  // The source's own page in the service (a Jira project's, a GitHub repository's), which its name
  // links to - worked out by its connector on the server. Null where there is none.
  webUrl?: string | null

  // Every connector type, unlike the two above. Whether this corpus is extracted into the
  // knowledge graph - ANDed with the deployment-wide graph.enabled setting, so a true here does
  // not on its own mean extraction is running.
  graphEnabled?: boolean
  // Present only while graphEnabled. What extraction has actually produced, which is the number
  // an operator judges it by before letting anything depend on it.
  graphEntities?: number
  graphRelations?: number
  // Also only while graphEnabled. Extraction runs in the background over what is already
  // indexed, so these are how a source just switched on shows it is working: indexed items the
  // graph has read, still has to read, and has given up on after its retries.
  graphItemsExtracted?: number
  graphItemsPending?: number
  graphItemsFailed?: number
  // What is being read RIGHT NOW, in passages. An item counts as read only once every passage in
  // it has been, and a large document holds hundreds - so on a real corpus these are the only
  // numbers that move for hours, and without them the source looks stuck while it is working.
  graphChunksDone?: number
  graphChunksTotal?: number
  /** The graph worker has one of this source's items open right now. */
  graphExtracting?: boolean
  /** The knowledge-graph model is not answering; the dialog's progress says why. */
  graphModelUnreachable?: boolean
  // True from the moment a delete is requested until the connector actually disappears from the
  // list. Deletion is asynchronous server-side (the request returns as soon as the intent is
  // persisted), so this is what keeps the card rendering as deleting - unlike the transient
  // connectorOperationStore entry, it survives a page refresh.
  deleting?: boolean
  // True while a model-changed reindex is in flight - old search results may still reflect the
  // previous embedding model until this clears (either via an atomic shadow-collection swap, or
  // per-document in-place replacement, depending on the backing vector store).
  reindexing?: boolean
  // Only present while reindexing is true - the model it's reindexing TO (the current global
  // setting), not the old one still in effect until the reindex completes.
  reindexTargetModel?: string
}

export type DocumentStatus =
  | 'DETECTED' | 'QUEUED' | 'DOWNLOADING' | 'CONVERTING'
  | 'EMBEDDING' | 'COMPLETED' | 'FAILED' | 'MODIFIED' | 'DELETED'

export interface TrackedItem {
  id: string
  sourceId: string
  // Present only for FILES-connector items with a matching uploaded-file row - lets the UI offer
  // a per-file delete action via DELETE /api/v1/files/{fileId}. sourceId is deliberately null for
  // FILES items (the raw server filesystem path is never sent to the client), so this is the only
  // id available to target such a delete.
  fileId?: string
  // The item's own web address, as its connector stated it - what "open" follows. Null for a file,
  // and for a type whose items have none.
  sourceUrl?: string | null
  title: string
  status: DocumentStatus
  currentChunks?: number
  totalChunks?: number
  contentSize?: number
  sourceSizeBytes?: number
  failureMessage?: string
  lastIndexedAt?: string
  sourceLastModified?: string
  createdAt?: string
  /** The knowledge graph has read this version of the item. */
  graphDone?: boolean
}

export interface PagedResult<T> {
  items: T[]
  page: number
  totalPages: number
  totalItems: number
  hasMore: boolean
}

// Keyset (cursor) pagination for tracked items - offset pagination breaks when items transition
// into/out of the "active" status set between page fetches (see ConnectorService.
// listInactiveItemsPaged() on the backend), so this tracks a cursor instead of a page number.
//
// The cursor has three parts because the order has three keys: failed items first, then most
// recently touched, then id to break ties. It was (title, id) until the ordering changed - and a
// client sending the old cursor gets page one back for every request, which an infinite-scroll
// list appends and then asks again, so it loops rather than merely paginating oddly.
export interface CursorPagedResult<T> {
  items: T[]
  hasMore: boolean
  nextCursorFailed?: string
  nextCursorTouched?: string
  nextCursorId?: string
}

export interface ConnectorTypeSummary {
  total: number
  preview: ConnectorConfig[]
}

export interface ConnectorSummary {
  totalSources: number
  totalDocs: number
  byType: Partial<Record<ConnectorType, ConnectorTypeSummary>>
  // Real-time DB snapshot (COUNT of DOWNLOADING/CONVERTING/EMBEDDING items), refreshed on the
  // same debounced SSE invalidation as the rest of this summary - not accumulated client-side
  // from individual SSE events, which under-reports until every active item happens to emit its
  // next status event after the page mounts.
  activeIndexingCount: number
}

// -- the knowledge graph, as the two view endpoints return it -------------------------------
// Mirrors GraphView on the backend (proxyma-core-graph). One shape for both scopes - a single
// item's graph and a whole source's - so the view component takes one type and does not need to
// know which button opened it.

export interface GraphNode {
  id: string
  name: string
  // Null when the extractor could not classify it. Null rather than an invented bucket: see
  // KgEntity.type, where defaulting it would have made the unique constraint merge two genuinely
  // different unclassified things.
  type: string | null
  // How many times this scope names the entity. The node's SIZE - not its degree, because a thing
  // named on every page of one file and a thing named once in each of forty read the same by
  // degree and are very different news.
  mentions: number
  // Edges in THIS VIEW touching it, so a node drawn with two visible edges never claims forty.
  degree: number
}

export interface GraphEdge {
  source: string
  target: string
  predicate: string
  description: string | null
  // How many passages independently stated it. The edge's THICKNESS.
  weight: number
  documentId: string | null
}

/**
 * How much of a graph view to ask for, and which slice of it to send now.
 *
 * @see GraphPayload.hasMore - a page says whether another one exists.
 */
export interface GraphWindow {
  /** The most nodes this view may ever hold - the most connected ones. */
  cap: number
  /** How far into the ranked node list this page starts. */
  offset: number
  /** How many nodes this page carries. */
  limit: number
}

export interface GraphPayload {
  nodes: GraphNode[]
  edges: GraphEdge[]
  // A cap was hit. Surfaced in the UI rather than swallowed: a graph clipped at the limit looks
  // exactly like a complete one, and a reader who is not told reads a missing edge as a missing
  // relation.
  truncated: boolean
  // Whether another PAGE of this same view exists. Not the same thing as `truncated`: a view is
  // delivered most-connected-first so the picture is drawable before all of it has arrived, and
  // `truncated` says the view will never hold everything the source does however many pages are
  // fetched.
  hasMore: boolean
  totalNodes: number
  totalEdges: number
}

/** What GET /api/v1/indexing/{id}/graph/progress answers - see LlmCallLog's sibling on cost. */
export interface GraphProgress {
  indexed: number
  extracted: number
  pending: number
  failed: number
  /** Passages read so far in the items being read right now, and what those items hold. */
  chunksDone: number
  chunksTotal: number
  /**
   * WHICH items those are. The worker keeps a handful open at once, so this is a short list by
   * construction - it is what lets an item row show that IT is being read, instead of a source
   * total nobody can match against twenty titles.
   */
  reading: GraphReadingItem[]
  /**
   * Set while the knowledge-graph model is not answering and the worker is waiting to ask again -
   * the reason nothing above moves. Host and reason are null for a member of a server with accounts.
   */
  modelUnreachable?: GraphModelOutage | null
}

export interface GraphModelOutage {
  since: string
  retryAt: string
  host: string | null
  reason: string | null
  /** The call went through the configured proxy - for a model on the local network, usually why. */
  throughProxy: boolean
}

export interface GraphReadingItem {
  documentId: string
  /** Null if the document went away between the two reads; the entry then matches no row. */
  title: string | null
  chunksDone: number
  chunksTotal: number
}
