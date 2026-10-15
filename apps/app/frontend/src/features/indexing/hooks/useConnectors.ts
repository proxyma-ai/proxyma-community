import { useState } from 'react'
import { keepPreviousData, useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { LIST_PAGE_SIZE } from '@/components/ui/kit'
import { keys } from '@/services/queryKeys'
import { indexingApi } from '../services/indexingApi'
import type { ItemsCursor } from '../services/indexingApi'
import { useToastStore } from '@/store/toastStore'
import { useTranslation } from '@/i18n'
import type { ConnectorType } from '../types/indexing.types'
import { apiErrorText, withReason } from '@/services/apiError'

// Mutations don't know which connector-type tab a connector belongs to (they only get an id), so
// invalidation targets the broad key prefixes - TanStack Query matches any query whose key
// starts with the given array, so this invalidates the summary and every open by-type page list.
// Resolves once the observed lists have refetched, for a caller that has to await the fresh data.
function invalidateConnectorLists(qc: ReturnType<typeof useQueryClient>) {
  return Promise.all([
    qc.invalidateQueries({ queryKey: keys.connectorSummary() }),
    qc.invalidateQueries({ queryKey: keys.connectorsByType() }),
  ])
}

export function useConnectorSummary(includeShared = false) {
  return useQuery({
    queryKey: keys.connectorSummary(includeShared),
    queryFn: () => indexingApi.getSummary(includeShared),
  })
}

// type=undefined + a non-empty query searches connector titles across every connector type (the
// "All" tab's search box), backed by the "all" path sentinel on the backend. Without a query and
// without a type, the query is disabled - the "All" tab's default (non-search) view uses
// useConnectorSummary()'s capped preview instead of this paginated listing.
//
// ONE NUMBERED PAGE of twenty (LIST_PAGE_SIZE, 2026-10-14 - it loaded the next twenty on scrolling
// to the end). The rows shown stay while another page loads, so the list does not blank.
export function useConnectorsByType(type: ConnectorType | undefined, query: string | undefined, includeShared: boolean, page: number) {
  return useQuery({
    queryKey: [...keys.connectorsByType(type ?? (query ? 'all' : undefined), query, includeShared), { page }],
    queryFn: () => indexingApi.listByType(type ?? 'all', page, query, LIST_PAGE_SIZE, includeShared),
    enabled: !!type || !!query,
    placeholderData: keepPreviousData,
  })
}

export function useSyncNow() {
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const { t } = useTranslation()
  return useMutation({
    mutationFn: indexingApi.syncNow,
    // AWAITED, so the mutation stays pending until the lists carry the source's `syncing` flag.
    // The POST answers as soon as the sync thread is started, and without the wait the button's
    // spinner stopped in that gap - before the refetched status could take over - so a click
    // looked like nothing had happened.
    onSuccess: async (data) => {
      await invalidateConnectorLists(qc)
      if (!data.started) addToast(t('A sync is already running for this source'), 'info')
    },
    onError: (err) => {
      // The server's sentence when it gave one - never a machine code or axios's own English.
      const reason = apiErrorText(err)
      addToast(reason ? t('Failed to start sync: {reason}', { reason }) : t('Failed to start sync'), 'danger')
    },
  })
}

// Says what happened either way, as priority does: a refused share used to leave the menu's label
// where it was and say nothing at all.
export function useSetShared() {
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const { t } = useTranslation()
  return useMutation({
    mutationFn: ({ id, shared }: { id: string; shared: boolean }) => indexingApi.setShared(id, shared),
    onSuccess: (_data, { shared }) => {
      invalidateConnectorLists(qc)
      addToast(shared ? t('This source is now shared with others') : t('This source is no longer shared'), 'success')
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to change sharing'), e), 'danger'),
  })
}

// Invalidates the full connector lists (not just the toggled connector) because prioritizing one
// connector clears the flag on whichever connector held it before - both cards must re-render.
export function useSetPriority() {
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const { t } = useTranslation()
  return useMutation({
    mutationFn: ({ id, priority }: { id: string; priority: boolean }) => indexingApi.setPriority(id, priority),
    onSuccess: (_data, { priority }) => {
      invalidateConnectorLists(qc)
      addToast(priority ? t('This source will be processed first') : t('Priority removed'), 'success')
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to update priority'), e), 'danger'),
  })
}

export function useRemoveConnector() {
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const { t } = useTranslation()
  return useMutation({
    mutationFn: indexingApi.removeConnector,
    onSuccess: () => invalidateConnectorLists(qc),
    onError: (e: unknown) => {
      addToast(withReason(t('Failed to delete the source'), e), 'danger')
    },
  })
}

export function useReindex() {
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const { t } = useTranslation()
  return useMutation({
    mutationFn: indexingApi.reindex,
    onSuccess: (_data, id) => {
      invalidateConnectorLists(qc)
      qc.invalidateQueries({ queryKey: keys.connectorItemsActive(id) })
      qc.invalidateQueries({ queryKey: keys.connectorItems(id) })
      addToast(t('Re-indexing started'), 'success')
    },
    onError: (e: unknown) => {
      addToast(withReason(t('Failed to start re-indexing'), e), 'danger')
    },
  })
}

export function useRetryFailed() {
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const { t } = useTranslation()
  return useMutation({
    mutationFn: indexingApi.retryFailed,
    onSuccess: (_data, id) => {
      invalidateConnectorLists(qc)
      qc.invalidateQueries({ queryKey: keys.connectorItemsActive(id) })
      qc.invalidateQueries({ queryKey: keys.connectorItems(id) })
      addToast(t('Retrying failed items'), 'success')
    },
    onError: (e: unknown) => {
      addToast(withReason(t('Failed to retry items'), e), 'danger')
    },
  })
}

// Items currently downloading/converting/embedding - small and unpaginated, refetched on SSE
// invalidation. Rendered as a pinned section above the lazy-loaded main list.
export function useActiveItems(id: string | null) {
  return useQuery({
    queryKey: keys.connectorItemsActive(id),
    queryFn: () => indexingApi.listActiveItems(id!),
    enabled: !!id,
  })
}

// Everything else (queued/completed/failed/...), ONE PAGE of twenty at a time (LIST_PAGE_SIZE,
// 2026-10-14 - it loaded the next page as the list scrolled) - a connector can have thousands of
// tracked documents. Keyset (cursor) pagination, not offset - see CursorPagedResult /
// ConnectorService.listInactiveItemsPaged() for why - so the pager is Previous and Next with no
// page numbers: the cursors of the pages already passed are kept, which is what Previous returns to,
// and there is no way to name page 7 without reading pages 1 to 6. A refresh re-reads the page the
// reader is on from its own cursor; another source starts again at the first.
export function useConnectorItemsPage(id: string | null) {
  const [state, setState] = useState<{ id: string | null; cursors: ItemsCursor[] }>({ id, cursors: [{}] })
  const cursors = state.id === id ? state.cursors : [{}]
  const page = cursors.length - 1
  const query = useQuery({
    queryKey: [...keys.connectorItems(id), cursors[page]],
    queryFn: () => indexingApi.listItems(id!, cursors[page], LIST_PAGE_SIZE),
    enabled: !!id,
    placeholderData: keepPreviousData,
  })
  const last = query.data
  const setPage = (to: number) => {
    if (to === page + 1 && last?.hasMore) {
      setState({ id, cursors: [...cursors, {
        cursorFailed: last.nextCursorFailed,
        cursorTouched: last.nextCursorTouched,
        cursorId: last.nextCursorId,
      }] })
    } else if (to >= 0 && to < page) {
      setState({ id, cursors: cursors.slice(0, to + 1) })
    }
  }
  return { ...query, page, hasMore: !!last?.hasMore, setPage }
}
