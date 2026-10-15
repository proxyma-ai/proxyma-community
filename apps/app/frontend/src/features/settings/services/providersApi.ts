import { http } from '@/services/httpClient'

// A configured place to get models from. Mirrors ProviderInstanceView on the backend - note there
// is no `apiKey` field in either direction on a read: the server sends a mask and a boolean, and
// the real key only ever travels one way.
export interface ProviderInstance {
  id: string
  name: string
  protocol: string
  protocolLabel: string
  baseUrl: string | null
  apiKeyMasked: string | null
  apiKeyConfigured: boolean
  headers: Record<string, string>
  nativeRerank: boolean
  /** The API a gateway is spoken to through (LiteLLM); null for a protocol with one API. */
  api: string | null
  /** One blocking call per step instead of a stream (LiteLLM only). */
  blockingCalls: boolean
}

export interface ProviderProtocolInfo {
  value: string
  label: string
  maySupportNativeRerank: boolean
  /** The APIs an instance chooses between - empty for a protocol with one. */
  apis: { value: string; label: string }[]
  /** Whether an instance may be told not to stream. */
  mayCallWithoutStreaming: boolean
}

// `apiKey` omitted means "leave the stored one alone" - the form never receives the real key, so
// it cannot echo one back. Clearing is `clearApiKey`, never an empty string, which is only ever an
// accident.
export interface ProviderInstanceInput {
  name: string
  protocol: string
  baseUrl?: string
  apiKey?: string
  clearApiKey?: boolean
  headers?: Record<string, string>
  nativeRerank?: boolean
  api?: string
  blockingCalls?: boolean
}

export const providersApi = {
  // The protocol catalogue comes from the backend rather than a copy in this file, so the two
  // cannot disagree about what protocols exist.
  listProtocols: () =>
    http.get<ProviderProtocolInfo[]>('/api/v1/providers/protocols').then(r => r.data),
  list: () =>
    http.get<ProviderInstance[]>('/api/v1/providers/instances').then(r => r.data),
  create: (input: ProviderInstanceInput) =>
    http.post<ProviderInstance>('/api/v1/providers/instances', input).then(r => r.data),
  update: (id: string, input: ProviderInstanceInput) =>
    http.patch<ProviderInstance>(`/api/v1/providers/instances/${id}`, input).then(r => r.data),
  remove: (id: string) =>
    http.delete<void>(`/api/v1/providers/instances/${id}`).then(() => undefined),
}
