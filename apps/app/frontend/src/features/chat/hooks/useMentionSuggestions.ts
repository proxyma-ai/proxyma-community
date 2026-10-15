import { useQuery } from '@tanstack/react-query'
import { indexingApi } from '@/features/indexing/services/indexingApi'
import { keys } from '@/services/queryKeys'
import type { ConnectorConfig } from '@/features/indexing/types/indexing.types'

// Backs the chat input's @mention dropdown - reuses the Connectors page's existing "all types,
// filtered by title, including shared" listing rather than adding a new endpoint. `query` is the
// text typed after "@" so far; `null` means no mention is currently being typed (query disabled).
export function useMentionSuggestions(query: string | null) {
  return useQuery({
    queryKey: keys.connectorsByType('all', query ?? '', true),
    queryFn: () => indexingApi.listByType('all', 0, query || undefined, 8, true),
    enabled: query !== null,
    staleTime: 10_000,
    placeholderData: (prev) => prev,
    select: (data): ConnectorConfig[] => data.items,
  })
}
