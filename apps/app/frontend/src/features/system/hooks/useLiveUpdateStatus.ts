import { useRef } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSSE } from '@/hooks/useSSE'
import { updateApi, type UpdateStatus } from '@/features/settings/services/updateApi'
import { heldStatus } from '../utils/updateNotice'

/** How old a read status may be before a focus of the window asks again. */
const FOCUS_STALE_MS = 10 * 60_000

/**
 * The updater's status for the desktop window's title row, TOLD rather than polled: read once when
 * the window opens, then again whenever the page's one /api/v1/events stream says it changed
 * (`update-status`, from UpdateService - see UpdateStatusChangedEvent). A focus of the window reads
 * it again only when the last read is ten minutes old, for an event a reconnecting stream missed.
 *
 * The same query as Settings > Updates (['update-status']), so the two never disagree; that page
 * polls on its own while it is open. A check in progress is held through (heldStatus), so the
 * control does not leave the row while a scheduled check asks GitHub.
 *
 * `enabled` false - a browser, where there is no title row, and Enterprise Server, which has no
 * updater at all - reads nothing.
 */
export function useLiveUpdateStatus(enabled: boolean): UpdateStatus | undefined {
  const qc = useQueryClient()
  const { data } = useQuery({
    queryKey: ['update-status'],
    queryFn: updateApi.status,
    enabled,
    staleTime: FOCUS_STALE_MS,
    refetchOnWindowFocus: true,
    retry: false,
  })
  useSSE(type => {
    if (enabled && type === 'update-status') void qc.invalidateQueries({ queryKey: ['update-status'] })
  })
  const held = useRef<UpdateStatus | undefined>(undefined)
  held.current = heldStatus(held.current, enabled ? data : undefined)
  return enabled ? held.current : undefined
}
