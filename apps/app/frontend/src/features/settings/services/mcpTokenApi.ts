import { http } from '@/services/httpClient'

export interface McpToken {
  id: string
  label: string
  tokenPrefix: string
  createdAt: string
  lastUsedAt: string | null
  expiresAt: string | null
}

export interface McpTokenCreated extends McpToken {
  token: string
}

export const mcpTokenApi = {
  list: () => http.get<McpToken[]>('/api/v1/settings/mcp-tokens').then(r => r.data),
  generate: (label: string, expiresInDays: number) =>
    http.post<McpTokenCreated>('/api/v1/settings/mcp-tokens', { label, expiresInDays }).then(r => r.data),
  revoke: (id: string) => http.delete(`/api/v1/settings/mcp-tokens/${id}`),
}
