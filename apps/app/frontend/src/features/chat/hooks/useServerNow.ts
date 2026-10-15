import { useEffect, useState } from 'react'

// It was components/Elapsed.tsx, a file holding no component beside the kit's component named
// Elapsed (CHAT-25); the formatter is utils/formatElapsed.ts.
import { useChatStore } from '../instance/ChatInstanceContext'
import { useTranslation } from '@/i18n'
import { elapsedMs } from '../utils/toolBlocks'
import { formatElapsed } from '../utils/formatElapsed'
import type { ToolCallInfo } from '../types/chat.types'

// The server's clock as best this page knows it, re-read once a second while `ticking`.
//
// Tool calls are timed on the server (see ToolCallInfo.startedAt), so a counter for one still
// running needs "now" on that same clock: this page's own, corrected by the offset the event
// stream reports. Not ticking, it is read once per render - a finished call never needs it.
export function useServerNow(ticking: boolean): number {
  const skew = useChatStore(s => s.clockSkewMs)
  const [, tick] = useState(0)
  useEffect(() => {
    if (!ticking) return
    const timer = setInterval(() => tick(n => n + 1), 1000)
    return () => clearInterval(timer)
  }, [ticking])
  return Date.now() - skew
}

/**
 * How long a block of work took, counting while any of it still runs - as the words the kit's
 * `ActivityLine`, `ToolCallRow` and `SubAgentPanel` set in their `Elapsed` slot (disabled ink, the
 * readout face). The clock is this app's (the server's time, see useServerNow); the look is the kit's.
 *
 * Undefined for calls with no recorded times (a conversation stored before they were kept), and
 * for finished work that took under a second - "0s" beside a search says nothing.
 */
export function useElapsedText(calls: ToolCallInfo[]): string | undefined {
  const { t } = useTranslation()
  const running = calls.some(tc => !tc.done)
  const now = useServerNow(running)
  const ms = elapsedMs(calls, now)
  if (ms === undefined || (!running && ms < 1000)) return undefined
  return formatElapsed(ms, t)
}
