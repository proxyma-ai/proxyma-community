import { http } from '@/services/httpClient'

export type MemoryCategory = 'USER' | 'KNOWLEDGE'

// One entry of the agent's long-term memory - AgentMemoryService.MemoryEntry on the backend.
// `category` and `savedAt` are read off the id there, and are null for an entry written before
// either was recorded.
export interface MemoryEntry {
  id: string
  category: MemoryCategory | null
  savedAt: string | null
  text: string
}

// Always the caller's own memory: the backend scopes every call to the signed-in user (or
// Desktop's one user) and takes no user id from here.
export const memoryApi = {
  list: () =>
    http.get<MemoryEntry[]>('/api/v1/memory').then(r => r.data),
  edit: (id: string, text: string, category: MemoryCategory) =>
    http.put<MemoryEntry>(`/api/v1/memory/${encodeURIComponent(id)}`, { text, category }).then(r => r.data),
  remove: (ids: string[]) =>
    http.post<{ deleted: number }>('/api/v1/memory/delete', { ids }).then(r => r.data.deleted),
  recategorize: (ids: string[], category: MemoryCategory) =>
    http.post<{ changed: number }>('/api/v1/memory/category', { ids, category }).then(r => r.data.changed),
}
