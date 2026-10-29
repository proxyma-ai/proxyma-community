export const keys = {
  search:            (q: string) => ['search', q] as const,
  // includeShared omitted (invalidation calls) => bare prefix, matching every variant below it.
  connectorSummary:   (includeShared?: boolean) =>
    (includeShared !== undefined ? ['connector-summary', includeShared] as const : ['connector-summary'] as const),
  connectorsByType:   (type?: string, q?: string, includeShared?: boolean) =>
    (type ? ['connectors-by-type', type, q ?? '', !!includeShared] as const : ['connectors-by-type'] as const),
  // What the sources take on disk - the Sources page header's size.
  sourceStorage:      () => ['source-storage'] as const,
  connectorItemsActive: (id: string | null) => ['connector-items-active', id] as const,
  connectorItems:    (id: string | null) => ['connector-items', id] as const,
  // One document's chunks, cached so reopening a row in SourceChunksModal is free.
  documentChunks:    (id: string, documentId: string) => ['document-chunks', id, documentId] as const,
  // documentId omitted => the WHOLE SOURCE's graph. The two scopes share a prefix so deleting a
  // connector can drop both with one invalidation, and differ in the last segment so the source
  // view and an item view inside it are never served each other's cache.
  // The node cap is part of the key: raising it is a different picture, not a stale one, and two
  // caps served from one cache entry would show the reader whichever they asked for first.
  connectorGraph:    (id: string, documentId?: string, cap?: number) =>
    ['connector-graph', id, documentId ?? 'all', cap ?? 'uncapped'] as const,
  jobs:              ()          => ['jobs'] as const,
  conversations:    ()          => ['conversations'] as const,
  conversation:     (id: string)=> ['conversations', id] as const,
  // NOT under 'conversations': the history list is invalidated after every turn, and under that
  // prefix each invalidation refetched the meter too (2026-10-17).
  conversationUsage: (id: string) => ['conversation-usage', id] as const,
  // A key account's panel History - the caller's conversations bound to the account. Under
  // 'conversations', so every invalidation of `conversations()` refreshes it too, as an OBJECT, which
  // can never be read as a conversation id.
  keyAccountConversations: (accountId: string) => ['conversations', { keyAccount: accountId }] as const,
  // Under the conversation's own key, so whatever refreshes the conversation after a turn
  // refreshes its agent tree and its alarms with it.
  agentTree: (id: string) => ['conversations', id, 'agent-tree'] as const,
  alarms: (id: string) => ['conversations', id, 'alarms'] as const,
  // The caller's own long-term memory - Settings > Memory.
  agentMemory:      ()          => ['agent-memory'] as const,
  // Not under 'conversations': 'active' there would read as a conversation id.
  activeConversations: (surface?: string) => (surface ? ['activeConversations', surface] as const : ['activeConversations'] as const),
  // Keyed by share token, NOT by conversation id - a viewer never learns the real id.
  sharedConversation: (token: string) => ['shared-conversation', token] as const,
  settings:         (prefix: string) => ['settings', prefix] as const,
  providerModels:   (provider: string) => ['provider-models', provider] as const,
  modelLimits:      (provider: string, model: string) => ['model-limits', provider, model] as const,
  // The configured provider instances, and the protocol catalogue the Add-provider form renders.
  // Protocols are a build-time constant of the backend - fetched once, never invalidated.
  providerInstances: ()          => ['provider-instances'] as const,
  providerProtocols: ()          => ['provider-protocols'] as const,
  // The caller's own connector connections, and the protocol catalogue the Add-connection form
  // renders - the same pair as the two above, for Jira, Confluence, Bitbucket, SVN and Microsoft 365.
  // Every source type and service this deployment offers (GET /api/v1/connectors/catalog) - the
  // Connect menu, the source and connection dialogs, the marks. Re-read when the license changes,
  // since what it leaves out is listed locked.
  connectorCatalog:  ()          => ['connector-catalog'] as const,
  connections:       ()          => ['connections'] as const,
  connectionProtocols: ()        => ['connection-protocols'] as const,
  mcpTokens:        ()          => ['mcp-tokens'] as const,
  modelPricing:     ()          => ['model-pricing'] as const,
  // The built-in list price of one model id, which the Add model rate dialog prefills from.
  modelListPrice:   (model: string) => ['model-list-price', model] as const,
  llmCalls:         (q: unknown) => ['llm-calls', q] as const,
  // The two halves of Usage & Cost, under the same root so one invalidation reaches all three.
  llmCallsSummary:  (q: unknown) => ['llm-calls', 'summary', q] as const,
  llmCallsLog:      (q: unknown) => ['llm-calls', 'log', q] as const,
  // The Logs page's other tabs - every key under one root, so a Clear reaches them all.
  auditSummary:     (q: unknown) => ['audit', 'summary', q] as const,
  auditEvents:      (q: unknown) => ['audit', 'events', q] as const,
  auditEvent:       (id: string) => ['audit', 'event', id] as const,
  graphProgress:    (id: string) => ['graph-progress', id] as const,
  // A custom source's ingestion API - its endpoint and tokens (IngestApiPanel).
  ingest:           (id: string) => ['ingest', id] as const,
  // The boot-time provider sweep's verdict - see useProviderConnectivity.
  providerConnectivity: ()      => ['provider-connectivity'] as const,
  chatSkills:       ()          => ['chat-skills'] as const,
  // The composer's + menu: the MCP servers it offers, and the sources it lists to pick from.
  chatMcpServers:   ()          => ['chat-mcp-servers'] as const,
  chatSourcePicks:  ()          => ['chat-source-picks'] as const,
  chatSkill:        (id: string) => ['chat-skills', id] as const,
  // Workflow DEFINITIONS - what the composer's "/" dropdown lists alongside skills.
  chatWorkflows:    ()          => ['chat-workflows'] as const,
  chatWorkflow:     (id: string) => ['chat-workflows', id] as const,
  chatTools:        ()          => ['chat-tools'] as const,
  files:            ()          => ['files'] as const,
  fileQuota:        ()          => ['files', 'quota'] as const,
  // The upload destinations - under 'files', so what refreshes the files after an upload
  // refreshes the folder list too.
  fileFolders:      ()          => ['files', 'folders'] as const,
  // What the Outlook dialog picks from: a mailbox's mail folders or calendars, read through one
  // Microsoft 365 connection.
  outlookPicker:    (kind: string | undefined, mailbox: string | undefined, connectionId: string | undefined) =>
    ['outlook-picker', kind, mailbox, connectionId] as const,
  // Desktop only - read-only preview of a local folder the user is about to register. Keyed by
  // the file-type filter too: the preview's whole job is to report how many files the CURRENT
  // settings would pick up, so a filter change has to miss the cache the same way a path change does.
  localFolder:      (path: string, recursive: boolean, fileTypes: string) =>
    ['local-folder', path, recursive, fileTypes] as const,
  // Extension vocabulary for the FILES file-type pickers - deployment-wide constants, so this is
  // fetched once and never invalidated.
  fileTypeCatalog:  ()          => ['file-type-catalog'] as const,
  // Crawl bounds for WEB_PAGE connectors - deployment-wide constants like the catalog above, so
  // fetched once and never invalidated.
  webCrawlLimits:   ()          => ['web-crawl-limits'] as const,
  me:               ()          => ['me'] as const,
  // Document URLs and the version in force, for the re-consent gate. A deployment-wide constant
  // like the catalogs above - fetched once and never invalidated.
  consentRequirements: ()       => ['consent-requirements'] as const,
  userQuota:        ()          => ['me', 'quota'] as const,
  credentialStatus: ()          => ['credentialStatus'] as const,
  adminOverview:    ()          => ['admin', 'overview'] as const,
  adminUsers:       ()          => ['admin', 'users'] as const,
  adminUserDetail:  (id: string) => ['admin', 'users', id] as const,
  adminSystemLive:    ()             => ['admin', 'system', 'live'] as const,
  adminSystemHistory: (range: string) => ['admin', 'system', 'history', range] as const,
  adminUserGrowth:    ()             => ['admin', 'analytics', 'user-growth'] as const,
  adminActivity:      (range: string) => ['admin', 'analytics', 'activity', range] as const,
  adminActiveTimeTrend: (range: string) => ['admin', 'analytics', 'active-time', range] as const,
  adminFeatureUsage:    (range: string) => ['admin', 'analytics', 'feature-usage', range] as const,
  adminUsageByUser:     (range: string) => ['admin', 'analytics', 'usage-by-user', range] as const,
  adminFeedback:     (mood: string) => ['admin', 'feedback', mood] as const,
  adminBackupStatus:   ()        => ['admin', 'system', 'backup', 'status'] as const,
  adminBackupList:     ()        => ['admin', 'system', 'backup', 'list'] as const,
  adminBackupProgress: ()        => ['admin', 'system', 'backup', 'progress'] as const,
  evaluationOverview:     (since: string) => ['admin', 'evaluation', 'overview', since] as const,
  maintenanceStatus:   ()        => ['maintenance', 'status'] as const,
  maintenanceAnnouncement: ()    => ['maintenance', 'announcement'] as const,
  processingStatus:  ()          => ['processing', 'status'] as const,
  // The chat hero's astronomy fact - deployment-wide, not per-user, so one key with no arguments
  // (see useSpaceFact for the refetch cadence).
  spaceFact:         ()          => ['space-fact'] as const,
  // The anonymous visitor's remaining free turns. Keyed on nothing because the guest is identified
  // by an httpOnly cookie the frontend cannot read - the server resolves who is asking.
  guestAllowance:    ()          => ['guest-allowance'] as const,
  licenseStatus:     ()          => ['license', 'status'] as const,
} as const
