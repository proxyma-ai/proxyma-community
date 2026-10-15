import { useCallback, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useSSE } from '@/hooks/useSSE'
import { keys } from '@/services/queryKeys'

// The SSE events that change what a source LIST shows: a new source, a removed one, and the sync
// progress and completion that move its counts and "synced" time.
const LIST_EVENTS = new Set(['connector-registered', 'connector-removed', 'connector-synced', 'connector-update', 'connector-graph'])

/**
 * Keeps the cached source lists current from ANY page, not just while the Sources page is open.
 *
 * <p>MOUNTED ONCE, IN AppShell, since 2026-09-14. This invalidation lived inside SourcesPage's own
 * SSE handler, so it only ran while that page was mounted. A source the agent registered from chat -
 * or an MCP client from an editor - produced its events while the page was unmounted, nobody
 * invalidated anything, and returning to Sources showed the list cached on the last visit, still
 * "fresh" under the 30-second staleTime. Refreshing the browser was the only way to see the source.
 *
 * <p>Invalidating marks the queries stale and refetches only those currently observed, so on the chat
 * page this costs nothing until Sources is opened again. Debounced because a sync emits one
 * connector-update per document, and a burst of hundreds should be one refetch, not hundreds.
 */
export function useConnectorListSync() {
  const qc = useQueryClient()
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const onMessage = useCallback((type: string) => {
    if (!LIST_EVENTS.has(type)) return
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      qc.invalidateQueries({ queryKey: keys.connectorSummary() })
      qc.invalidateQueries({ queryKey: keys.connectorsByType() })
    }, type === 'connector-registered' || type === 'connector-removed' ? 50 : 500)
  }, [qc])

  useSSE(onMessage)
}
