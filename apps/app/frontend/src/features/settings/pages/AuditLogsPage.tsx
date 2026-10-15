import { AuditLogs } from '@/features/audit/components/AuditLogs'
import type { ExtraPageProps } from './types'

// Settings > Logs, DESKTOP edition (Home Server is this bundle too). Enterprise Server has the
// same page on Administration instead, where an administrator reads everyone's entries. A link from
// another tab names the sub-tab to open as its focus - About's diagnostics link opens Diagnostics.
export function AuditLogsPage({ focusKey }: ExtraPageProps) {
  return <AuditLogs initialTab={focusKey} />
}
