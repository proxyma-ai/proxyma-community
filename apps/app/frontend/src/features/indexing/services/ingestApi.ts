import { http } from '@/services/httpClient'

// A custom source's ingestion API as its owner manages it (IngestTokenController): the address
// scripts push to, and the tokens they push with. The pushes themselves never come from the page -
// they come from the scripts, with a token, at the endpoint below.

export interface IngestToken {
  id: string
  label: string
  /** The first twelve characters - what the list shows; the token itself is shown once. */
  tokenPrefix: string
  createdAt: string
  lastUsedAt: string | null
}

export interface IngestTokenCreated extends IngestToken {
  token: string
}

export interface IngestInfo {
  /** A path; the page puts its own origin in front of it. */
  endpoint: string
  tokens: IngestToken[]
  limits: {
    maxTextChars: number
    maxFileBytes: number
    maxBatch: number
    maxDocuments: number
    maxTokens: number
  }
}

export const ingestApi = {
  get: (sourceId: string) =>
    http.get<IngestInfo>(`/api/v1/indexing/${sourceId}/ingest`).then(r => r.data),
  createToken: (sourceId: string, label: string) =>
    http.post<IngestTokenCreated>(`/api/v1/indexing/${sourceId}/ingest/tokens`, label ? { label } : {}).then(r => r.data),
  revokeToken: (sourceId: string, tokenId: string) =>
    http.delete(`/api/v1/indexing/${sourceId}/ingest/tokens/${tokenId}`).then(() => undefined),
}
