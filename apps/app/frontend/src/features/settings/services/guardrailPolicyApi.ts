import { http } from '@/services/httpClient'

export type PolicyDocumentStatus = 'PENDING' | 'INDEXED' | 'FAILED'

// Which corpus a document belongs to, and therefore how it is used. RULES are prose, retrieved
// and judged by the model. EXAMPLES are specimens of what to block, matched by similarity alone
// with no model call - see PolicyDocument.Kind on the backend for why the two cannot be mixed.
export type PolicyKind = 'RULES' | 'EXAMPLES'

export interface PolicyDocument {
  kind: PolicyKind
  id: string
  displayName: string
  fileName: string | null
  // Set when the document was fetched from a page rather than uploaded.
  sourceUrl: string | null
  sizeBytes: number
  addedAt: string
  indexedAt: string | null
  status: PolicyDocumentStatus
  // Why indexing failed. Safe to show: it describes OUR conversion of the operator's own file,
  // never the file's contents.
  failureMessage: string | null
  chunkCount: number
}

export interface PolicyList {
  documents: PolicyDocument[]
  maxBytes: number
}

// The guardrail's policy corpus - the rule documents the semantic layer is judged against. Its
// own index, deliberately: these never appear in ordinary chat search, because that reaches
// collections through registered connectors and this collection belongs to no connector.
export const guardrailPolicyApi = {
  list: () =>
    http.get<PolicyList>('/api/v1/guardrail/policies').then(r => r.data),

  upload: (file: File, kind: PolicyKind) => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post<PolicyDocument>('/api/v1/guardrail/policies', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      params: { kind },
    }).then(r => r.data)
  },

  remove: (id: string) =>
    http.delete<void>(`/api/v1/guardrail/policies/${id}`).then(r => r.data),

  // Re-converts and re-embeds everything. Needed after an embedding-model change, which leaves
  // existing vectors in a space the new model does not share.
  reindex: () =>
    http.post<void>('/api/v1/guardrail/policies/reindex').then(r => r.data),
}
