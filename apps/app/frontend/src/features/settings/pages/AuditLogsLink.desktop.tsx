import type { ReactNode } from 'react'
import { Button } from '@/components/ui/kit'
import type { OpenTab } from './types'

// The way to Logs, DESKTOP edition: a tab of this same window, so a link switches to it and
// opens the sub-tab asked for. See types.ts.
export function AuditLogsLink({ log, onOpenTab, children }: { log: string; onOpenTab: OpenTab; children: ReactNode }) {
  return (
    <Button variant="hyperlink" onClick={() => onOpenTab('Logs', log)}>
      {children}
    </Button>
  )
}
